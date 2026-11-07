package dev.swapwise.backend.parsing

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ParseRequest(val lines: List<String> = emptyList())

@RestController
@RequestMapping("/api/v1/receipts")
class ReceiptController(private val service: ReceiptParsingService) {
    /**
     * Parses cleaned OCR lines into items.
     *
     * `X-Install-Id` is a random UUID the app creates on first launch. It identifies an install for rate
     * limiting, not a person, and is not linked to any account.
     */
    @PostMapping("/parse")
    fun parse(
        @RequestHeader("X-Install-Id") installId: UUID,
        @RequestBody request: ParseRequest,
    ): ParseResult = service.parse(installId, request.lines)
}
