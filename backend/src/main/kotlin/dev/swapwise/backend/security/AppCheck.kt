package dev.swapwise.backend.security

import dev.swapwise.backend.SwapwiseProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Decides whether a request really comes from our app on a genuine device. */
fun interface AppCheckVerifier {
    fun isValid(token: String): Boolean
}

/**
 * Verifies Firebase App Check tokens. The app gets a token from Firebase (backed by Play Integrity on Android)
 * and sends it in `X-Firebase-AppCheck`. A token is a JWT signed by Firebase; we check the signature against
 * Firebase's public keys (fetched and cached by Nimbus), the issuer, the audience and the expiry.
 *
 * This is what stops someone who copied the API URL from calling the LLM endpoint with a script.
 */
@Component
class FirebaseAppCheckVerifier(properties: SwapwiseProperties) : AppCheckVerifier {
    private val log = LoggerFactory.getLogger(javaClass)
    private val decoder: JwtDecoder? = properties.appCheck.takeIf { it.enabled }?.let { settings ->
        require(settings.projectNumber.isNotBlank()) { "swapwise.app-check.project-number is required when App Check is enabled" }
        val audience = "projects/${settings.projectNumber}"
        NimbusJwtDecoder.withJwkSetUri(settings.jwksUri)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build()
            .apply {
                setJwtValidator(
                    DelegatingOAuth2TokenValidator(
                        JwtValidators.createDefaultWithIssuer("https://firebaseappcheck.googleapis.com/${settings.projectNumber}"),
                        JwtClaimValidator<List<String>?>(JwtClaimNames.AUD) { aud -> aud != null && audience in aud },
                    ),
                )
            }
    }

    override fun isValid(token: String): Boolean {
        val decoder = this.decoder ?: return true // disabled (local development and tests only)
        return try {
            val jwt: Jwt = decoder.decode(token)
            jwt.subject != null
        } catch (e: JwtException) {
            log.debug("Rejected App Check token: {}", e.message)
            false
        }
    }
}

/** Requires a valid App Check token on every `/api/` request. Health and metrics endpoints are not affected. */
@Component
class AppCheckFilter(
    private val properties: SwapwiseProperties,
    private val verifier: AppCheckVerifier,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.appCheck.enabled || !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = request.getHeader(HEADER)
        if (token.isNullOrBlank() || !verifier.isValid(token)) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            response.writer.write(
                """{"type":"https://swapwise.dev/problems/app-check-failed","title":"Unauthorized","status":401,""" +
                    """"detail":"Missing or invalid App Check token."}""",
            )
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Firebase-AppCheck"
    }
}
