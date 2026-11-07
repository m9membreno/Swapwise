package dev.swapwise.backend.catalog

import dev.swapwise.backend.shared.NotFoundException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.WebRequest
import java.time.Duration

data class FactorDto(
    val category: String,
    val label: String,
    val kgCo2ePerKg: Double,
    val kgCo2ePerKgLow: Double,
    val kgCo2ePerKgHigh: Double,
    val typicalItemKg: Double?,
)

data class FactorsResponse(val version: Int, val source: String, val factors: List<FactorDto>)

@RestController
@RequestMapping("/api/v1/factors")
class FactorController(private val repository: FactorRepository) {
    /**
     * The current carbon factors. The ETag is the release version, so checking for changes costs one tiny
     * query: if the app's `If-None-Match` matches, Spring answers 304 with no body and the factor rows are
     * never read. The app stores the factors in Room and re-checks at most every few hours.
     */
    @GetMapping
    fun current(request: WebRequest): ResponseEntity<FactorsResponse>? {
        val release = repository.currentRelease() ?: throw NotFoundException("No carbon factors have been imported yet")
        val etag = "factors-v${release.version}"
        if (request.checkNotModified(etag)) return null // Spring has already written the 304

        val factors = repository.factors(release.version).map {
            FactorDto(it.category.name, it.category.label, it.kgCo2ePerKg, it.kgCo2ePerKgLow, it.kgCo2ePerKgHigh, it.typicalItemKg)
        }
        return ResponseEntity.ok()
            .eTag(etag)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(6)))
            .body(FactorsResponse(release.version, release.source, factors))
    }
}
