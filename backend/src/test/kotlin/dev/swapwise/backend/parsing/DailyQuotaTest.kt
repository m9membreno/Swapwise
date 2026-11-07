package dev.swapwise.backend.parsing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyQuotaTest {
    /** A clock tests can move forward. */
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-11-07T10:00:00Z"))

    @Test
    fun `enforces the per-install limit`() {
        val quota = DailyQuota(perInstallLines = 10, globalLines = 1_000, clock = clock)
        assertTrue(quota.tryAcquire("phone-a", 8))
        assertFalse(quota.tryAcquire("phone-a", 3), "would exceed 10")
        assertTrue(quota.tryAcquire("phone-a", 2))
        assertTrue(quota.tryAcquire("phone-b", 10), "another install has its own limit")
    }

    @Test
    fun `enforces the global limit across installs`() {
        val quota = DailyQuota(perInstallLines = 100, globalLines = 15, clock = clock)
        assertTrue(quota.tryAcquire("a", 10))
        assertFalse(quota.tryAcquire("b", 10), "global cap reached even though b is new")
        assertTrue(quota.tryAcquire("b", 5))
    }

    @Test
    fun `a refused request records no usage`() {
        val quota = DailyQuota(perInstallLines = 10, globalLines = 1_000, clock = clock)
        assertFalse(quota.tryAcquire("a", 11))
        assertTrue(quota.tryAcquire("a", 10))
    }

    @Test
    fun `released lines can be used again`() {
        val quota = DailyQuota(perInstallLines = 10, globalLines = 10, clock = clock)
        assertTrue(quota.tryAcquire("a", 10))
        quota.release("a", 4)
        assertTrue(quota.tryAcquire("a", 4))
    }

    @Test
    fun `resets at midnight UTC`() {
        val quota = DailyQuota(perInstallLines = 10, globalLines = 10, clock = clock)
        assertTrue(quota.tryAcquire("a", 10))
        clock.now = Instant.parse("2026-11-07T23:59:59Z")
        assertFalse(quota.tryAcquire("a", 1))
        clock.now = clock.now.plus(Duration.ofSeconds(1))
        assertTrue(quota.tryAcquire("a", 10))
    }

    @Test
    fun `concurrent requests never exceed the global limit`() {
        val quota = DailyQuota(perInstallLines = 1_000, globalLines = 500, clock = clock)
        val granted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        repeat(2_000) { i -> pool.execute { if (quota.tryAcquire("install-${i % 50}", 1)) granted.incrementAndGet() } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(500, granted.get())
    }
}
