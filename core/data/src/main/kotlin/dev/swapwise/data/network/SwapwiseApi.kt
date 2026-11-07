package dev.swapwise.data.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** The backend's API. DTOs mirror the Spring Boot controllers; unknown JSON fields are ignored (see NetworkModule). */
interface SwapwiseApi {
    @POST("api/v1/receipts/parse")
    suspend fun parse(@Body body: ParseRequestDto): ParseResponseDto

    /** Returns 304 with no body when [etag] still matches; that is why this returns a raw [Response]. */
    @GET("api/v1/factors")
    suspend fun factors(@Header("If-None-Match") etag: String?): Response<FactorsResponseDto>

    @POST("api/v1/events")
    suspend fun uploadEvents(@Body body: EventBatchDto): EventBatchResultDto
}

@Serializable
data class ParseRequestDto(val lines: List<String>)

@Serializable
data class ParsedItemDto(val index: Int, val name: String, val category: String, val quantity: Double, val unit: String)

@Serializable
data class ParseResponseDto(val items: List<ParsedItemDto>, val skippedIndexes: List<Int>, val promptVersion: String)

@Serializable
data class FactorDto(
    val category: String,
    val label: String,
    val kgCo2ePerKg: Double,
    val kgCo2ePerKgLow: Double,
    val kgCo2ePerKgHigh: Double,
    val typicalItemKg: Double? = null,
)

@Serializable
data class FactorsResponseDto(val version: Int, val source: String, val factors: List<FactorDto>)

@Serializable
data class SwapEventDto(
    val eventId: String,
    val type: String,
    val suggestionId: String,
    val swapCount: Int,
    val kgCo2eSaved: Double,
    val costDeltaCents: Long,
    val factorVersion: Int,
    val occurredAt: String,
)

@Serializable
data class EventBatchDto(val events: List<SwapEventDto>)

@Serializable
data class EventBatchResultDto(val accepted: Int, val duplicates: Int)
