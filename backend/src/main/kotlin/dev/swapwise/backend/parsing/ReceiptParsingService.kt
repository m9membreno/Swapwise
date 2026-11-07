package dev.swapwise.backend.parsing

import dev.swapwise.backend.SwapwiseProperties
import dev.swapwise.backend.shared.InvalidRequestException
import dev.swapwise.backend.shared.ParserUnavailableException
import dev.swapwise.backend.shared.QuotaExceededException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One parsed receipt line, pointing back at its position in the request so the app can show and fix it. */
data class ParsedItem(
    val index: Int,
    val name: String,
    val category: String,
    val quantity: Double,
    val unit: String,
)

data class ParseResult(
    val items: List<ParsedItem>,
    /** Lines that were not products (totals, payment, fees...). */
    val skippedIndexes: List<Int>,
    val promptVersion: String,
)

/**
 * The parse flow for one receipt:
 * 1. Validate sizes, then clean every line ([LineSanitizer]); drop non-product lines.
 * 2. Look up each remaining line in the cache by its hash.
 * 3. Only for cache misses: reserve quota, call the LLM once with a timeout, store the results.
 * 4. Answer in the original line order.
 *
 * Failure handling: if the LLM times out or fails, the reserved quota is given back and the caller gets a 503
 * with Retry-After. The app keeps the raw lines and retries later; lines cached in the meantime are free.
 */
@Service
class ReceiptParsingService(
    private val parser: ReceiptLineParser,
    private val cache: ParseCacheRepository,
    private val quota: DailyQuota,
    private val properties: SwapwiseProperties,
    private val llmExecutor: ExecutorService,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val settings get() = properties.parse
    private val llmTimer: Timer = Timer.builder("swapwise.parse.llm.latency")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meters)

    fun parse(installId: UUID, rawLines: List<String>): ParseResult {
        validate(rawLines)

        val cleaned = rawLines.map { raw -> LineSanitizer.sanitize(raw) }
        val keys = cleaned.map { line -> line?.let { ParseCacheKey.of(settings.promptVersion, LineSanitizer.normalize(it)) } }

        val cached = cache.findAll(keys.filterNotNull().toSet())
        // Distinct by key: the same product twice on one receipt costs one LLM line.
        val misses = keys.indices
            .filter { keys[it] != null && keys[it] !in cached }
            .distinctBy { keys[it] }
        meters.counter("swapwise.parse.lines", "source", "cache").increment((keys.count { it != null } - misses.size).toDouble())

        val fresh = if (misses.isEmpty()) emptyMap() else parseMisses(installId, misses.map { cleaned[it]!! }, misses.map { keys[it]!! })
        val all = cached + fresh

        val items = ArrayList<ParsedItem>()
        val skipped = ArrayList<Int>()
        keys.forEachIndexed { index, key ->
            val line = key?.let { all[it] }
            if (line == null || !line.isGrocery) {
                skipped += index
            } else {
                items += ParsedItem(index, line.name, line.category.name, line.quantity, line.unit.name)
            }
        }
        // Counts only: receipt text never goes to the logs.
        log.info("Parsed receipt: {} lines, {} items, {} from LLM", rawLines.size, items.size, misses.size)
        return ParseResult(items, skipped, settings.promptVersion)
    }

    private fun parseMisses(installId: UUID, lines: List<String>, keys: List<String>): Map<String, ParsedLine> {
        if (!quota.tryAcquire(installId.toString(), lines.size)) {
            meters.counter("swapwise.parse.quota_rejections").increment()
            throw QuotaExceededException(secondsUntilMidnightUtc())
        }
        val results = try {
            callWithTimeout(lines)
        } catch (e: Exception) {
            quota.release(installId.toString(), lines.size)
            val reason = if (e is TimeoutException) "timeout" else "error"
            meters.counter("swapwise.parse.failures", "reason", reason).increment()
            log.warn("LLM parse failed ({}) for {} lines: {}", reason, lines.size, e.javaClass.simpleName)
            throw ParserUnavailableException(RETRY_AFTER_SECONDS, e)
        }
        meters.counter("swapwise.parse.lines", "source", "llm").increment(lines.size.toDouble())
        val fresh = keys.zip(results).toMap()
        cache.saveAll(settings.promptVersion, fresh)
        return fresh
    }

    private fun callWithTimeout(lines: List<String>): List<ParsedLine> {
        val sample = Timer.start(meters)
        val future = CompletableFuture.supplyAsync({ parser.parse(lines) }, llmExecutor)
        try {
            val results = future.get(settings.timeout.toMillis(), TimeUnit.MILLISECONDS)
            check(results.size == lines.size) { "Parser returned ${results.size} results for ${lines.size} lines" }
            return results
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } finally {
            sample.stop(llmTimer)
        }
    }

    private fun validate(lines: List<String>) {
        if (lines.isEmpty()) throw InvalidRequestException("lines must not be empty")
        if (lines.size > settings.maxLinesPerRequest) {
            throw InvalidRequestException("At most ${settings.maxLinesPerRequest} lines per request")
        }
        val tooLong = lines.indexOfFirst { it.length > settings.maxLineLength }
        if (tooLong >= 0) throw InvalidRequestException("Line $tooLong is longer than ${settings.maxLineLength} characters")
    }

    private fun secondsUntilMidnightUtc(): Long {
        val now = clock.instant()
        val midnight = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        return Duration.between(now, midnight).seconds.coerceAtLeast(1)
    }

    private companion object {
        const val RETRY_AFTER_SECONDS = 60L
    }
}

@Configuration(proxyBeanMethods = false)
class ParsingConfig {
    @Bean
    fun dailyQuota(properties: SwapwiseProperties, clock: Clock) =
        DailyQuota(properties.parse.perInstallDailyLines, properties.parse.globalDailyLines, clock)
}
