package dev.swapwise.app

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.swapwise.optimizer.Alternative
import dev.swapwise.optimizer.BasketLine
import dev.swapwise.optimizer.Familiarity
import dev.swapwise.optimizer.Product
import dev.swapwise.optimizer.SwapEngine
import dev.swapwise.optimizer.SwapProblem
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Measures how long one receipt's suggestions take on the device the test runs on: the README's
 * "optimizer p95 on a mid-range phone" number. Run on a real phone (not an emulator) with:
 *
 *     ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.swapwise.app.OptimizerTimingTest
 *
 * then read the result with `adb logcat -s OptimizerTiming`. Debug builds are slower than release, so treat
 * the number as an upper bound. The assertion only catches large regressions; the target is 300 ms.
 */
@RunWith(AndroidJUnit4::class)
class OptimizerTimingTest {
    @Test
    fun suggestionsForA30LineReceipt() {
        val engine = SwapEngine()
        val timings = (0 until WARM_UP + RUNS).map { seed ->
            val problem = receipt(seed.toLong(), lines = 30)
            val start = System.nanoTime()
            engine.suggest(problem)
            (System.nanoTime() - start) / 1_000_000
        }.drop(WARM_UP).sorted()

        val p50 = timings[timings.size / 2]
        val p95 = timings[(timings.size * 95) / 100]
        Log.i("OptimizerTiming", "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}): p50=$p50 ms, p95=$p95 ms, n=$RUNS")
        assertTrue("p95 was $p95 ms", p95 < 1_000)
    }

    /** Random receipt with up to 3 alternatives per line, like the JVM quality tests. */
    private fun receipt(seed: Long, lines: Int): SwapProblem {
        val random = Random(seed)
        return SwapProblem(
            List(lines) { i ->
                val price = random.nextLong(100, 1_500)
                val kg = random.nextDouble(0.1, 20.0)
                BasketLine(
                    Product("L$i", "L$i", "c$i", null, price, kg),
                    1,
                    List(random.nextInt(0, 4)) { j ->
                        Alternative(
                            Product("L$i-$j", "alt", "c$i", null, (price * random.nextDouble(0.6, 1.4)).toLong().coerceAtLeast(1), kg * random.nextDouble(0.1, 1.2)),
                            Familiarity.SAME_CATEGORY,
                        )
                    },
                )
            },
        )
    }

    private companion object {
        const val WARM_UP = 10
        const val RUNS = 50
    }
}
