package dev.swapwise.backend.shared

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.net.URI

/**
 * API errors as RFC 9457 problem details (`application/problem+json`). Spring renders any
 * [ErrorResponseException] in that format, headers included, so controllers just throw these.
 *
 * Each has a stable `type` URI the app can switch on, instead of parsing human-readable messages.
 */
private fun problem(status: HttpStatus, type: String, title: String, detail: String): ProblemDetail =
    ProblemDetail.forStatusAndDetail(status, detail).apply {
        this.type = URI.create("https://swapwise.dev/problems/$type")
        this.title = title
    }

class InvalidRequestException(detail: String) :
    ErrorResponseException(HttpStatus.BAD_REQUEST, problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail), null)

/** The LLM timed out or failed. The app keeps the raw lines and retries later. */
class ParserUnavailableException(retryAfterSeconds: Long, cause: Throwable?) :
    ErrorResponseException(
        HttpStatus.SERVICE_UNAVAILABLE,
        problem(HttpStatus.SERVICE_UNAVAILABLE, "parser-unavailable", "Receipt parser unavailable", "Try again later."),
        cause,
    ) {
    init {
        headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
    }
}

class QuotaExceededException(retryAfterSeconds: Long) :
    ErrorResponseException(
        HttpStatus.TOO_MANY_REQUESTS,
        problem(HttpStatus.TOO_MANY_REQUESTS, "quota-exceeded", "Daily parsing limit reached", "Try again tomorrow."),
        null,
    ) {
    init {
        headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
    }
}

class NotFoundException(detail: String) :
    ErrorResponseException(HttpStatus.NOT_FOUND, problem(HttpStatus.NOT_FOUND, "not-found", "Not found", detail), null)
