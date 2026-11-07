package dev.swapwise.backend.parsing

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Limits LLM spend per day at two levels:
 * - per install: stops one phone (or one script pretending to be it) from parsing thousands of receipts;
 * - globally: caps the whole bill even if someone rotates install IDs.
 *
 * Counts reset at midnight UTC. State lives in memory, which is fine for a single instance; with two or
 * more instances the counters would move to Postgres or Redis so they are shared (see ADR-003).
 */
class DailyQuota(
    private val perInstallLines: Int,
    private val globalLines: Int,
    private val clock: Clock,
) {
    private val perInstall = ConcurrentHashMap<String, Int>()
    private var globalUsed = 0
    private var day: LocalDate = today()

    init {
        require(perInstallLines > 0 && globalLines > 0) { "Quotas must be positive" }
    }

    /**
     * Reserves [lines] LLM lines for [installId]. Returns true and records the usage if both limits allow
     * it; otherwise records nothing and returns false. Synchronized so two requests cannot both squeeze
     * past the last remaining capacity.
     */
    @Synchronized
    fun tryAcquire(installId: String, lines: Int): Boolean {
        require(lines >= 0) { "lines must not be negative" }
        rollOverIfNewDay()
        if (lines == 0) return true
        val used = perInstall[installId] ?: 0
        if (used + lines > perInstallLines || globalUsed + lines > globalLines) return false
        perInstall[installId] = used + lines
        globalUsed += lines
        return true
    }

    /** Gives back lines that were reserved but not used, for example when the LLM call failed. */
    @Synchronized
    fun release(installId: String, lines: Int) {
        rollOverIfNewDay()
        perInstall.computeIfPresent(installId) { _, used -> (used - lines).coerceAtLeast(0) }
        globalUsed = (globalUsed - lines).coerceAtLeast(0)
    }

    private fun rollOverIfNewDay() {
        val now = today()
        if (now != day) {
            day = now
            perInstall.clear()
            globalUsed = 0
        }
    }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
}
