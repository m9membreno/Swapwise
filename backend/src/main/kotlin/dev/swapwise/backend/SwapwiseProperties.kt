package dev.swapwise.backend

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Everything tunable lives here, bound from `swapwise.*` properties. */
@ConfigurationProperties(prefix = "swapwise")
data class SwapwiseProperties(
    val parse: Parse = Parse(),
    val appCheck: AppCheck = AppCheck(),
) {
    data class Parse(
        /** Bump when the prompt changes; old cache entries then stop matching. */
        val promptVersion: String = "v1",
        /** How long to wait for the LLM before answering 503. */
        val timeout: Duration = Duration.ofSeconds(10),
        val maxLinesPerRequest: Int = 80,
        val maxLineLength: Int = 120,
        /** LLM lines per install per day. Cached lines do not count. */
        val perInstallDailyLines: Int = 600,
        /** LLM lines per day for everyone together: the hard cap on the bill. */
        val globalDailyLines: Int = 20_000,
    )

    data class AppCheck(
        /** Off only for local development and tests. */
        val enabled: Boolean = true,
        /** Firebase project number, used to check the token's issuer and audience. */
        val projectNumber: String = "",
        val jwksUri: String = "https://firebaseappcheck.googleapis.com/v1/jwks",
    )
}
