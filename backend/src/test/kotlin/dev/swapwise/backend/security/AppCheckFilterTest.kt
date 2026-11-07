package dev.swapwise.backend.security

import dev.swapwise.backend.SwapwiseProperties
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppCheckFilterTest {
    private val enabled = SwapwiseProperties(appCheck = SwapwiseProperties.AppCheck(enabled = true, projectNumber = "123"))
    private val filter = AppCheckFilter(enabled) { token -> token == "good" }

    private fun run(uri: String, token: String?): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest("POST", uri).apply { token?.let { addHeader(AppCheckFilter.HEADER, it) } }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }

    @Test
    fun `lets a valid token through`() {
        val (response, chain) = run("/api/v1/events", "good")
        assertEquals(200, response.status)
        assertNotNull(chain.request, "request reached the controller")
    }

    @Test
    fun `rejects a missing or invalid token with 401 problem details`() {
        for (token in listOf(null, "", "forged")) {
            val (response, chain) = run("/api/v1/receipts/parse", token)
            assertEquals(401, response.status)
            assertEquals("application/problem+json", response.contentType)
            assertNull(chain.request, "request must not reach the controller")
        }
    }

    @Test
    fun `does not guard health checks`() {
        val (response, chain) = run("/actuator/health", null)
        assertEquals(200, response.status)
        assertNotNull(chain.request)
    }

    @Test
    fun `verifier refuses to start without a project number`() {
        val misconfigured = SwapwiseProperties(appCheck = SwapwiseProperties.AppCheck(enabled = true, projectNumber = ""))
        assertFailsWith<IllegalArgumentException> { FirebaseAppCheckVerifier(misconfigured) }
    }
}
