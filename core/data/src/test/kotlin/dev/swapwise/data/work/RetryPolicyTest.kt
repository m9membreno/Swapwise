package dev.swapwise.data.work

import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {
    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun `retries network errors, quota and server errors`() {
        assertTrue(IOException("offline").isRetryable())
        assertTrue(http(429).isRetryable())
        assertTrue(http(503).isRetryable())
        assertTrue(http(500).isRetryable())
    }

    @Test
    fun `does not retry requests the server rejected as wrong`() {
        assertFalse(http(400).isRetryable())
        assertFalse(http(401).isRetryable())
        assertFalse(IllegalStateException("bug").isRetryable())
    }
}
