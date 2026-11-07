package dev.swapwise.backend.catalog

import com.jayway.jsonpath.JsonPath
import dev.swapwise.backend.BaseIT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FactorsIT : BaseIT() {
    @Autowired
    lateinit var repository: FactorRepository

    @Autowired
    lateinit var jdbc: JdbcClient

    private fun factor(category: FoodCategory, mean: Double) =
        CategoryFactor(category, mean, mean * 0.8, mean * 1.2, null, listOf("TEST-${category.name}"))

    /** One test so the order of releases is predictable (the database is shared by all tests). */
    @Test
    fun `serves the current release with an ETag, answers 304 when unchanged, and switches atomically`() {
        if (repository.currentRelease() == null) {
            assertEquals(404, mvc.get().uri("/api/v1/factors").exchange().response.status)
        }

        val v1 = repository.publish("test", listOf(factor(FoodCategory.BEEF, 25.0), factor(FoodCategory.LEGUMES, 1.0)))
        val first = mvc.get().uri("/api/v1/factors").exchange()
        assertEquals(200, first.response.status)
        val etag = first.response.getHeader("ETag")!!
        assertEquals("\"factors-v$v1\"", etag)
        assertTrue(first.response.getHeader("Cache-Control")!!.contains("max-age"))
        val json = first.response.contentAsString
        assertEquals(v1, JsonPath.read(json, "$.version"))
        assertEquals(listOf("BEEF", "LEGUMES"), JsonPath.read<List<String>>(json, "$.factors[*].category"))
        assertEquals(20.0, JsonPath.read<Double>(json, "$.factors[0].kgCo2ePerKgLow"), 1e-9)

        val unchanged = mvc.get().uri("/api/v1/factors").header("If-None-Match", etag).exchange()
        assertEquals(304, unchanged.response.status)
        assertEquals("", unchanged.response.contentAsString)

        val v2 = repository.publish("test", listOf(factor(FoodCategory.BEEF, 27.0)))
        val changed = mvc.get().uri("/api/v1/factors").header("If-None-Match", etag).exchange()
        assertEquals(200, changed.response.status)
        assertNotEquals(etag, changed.response.getHeader("ETag"))
        assertEquals(v2, JsonPath.read(changed.response.contentAsString, "$.version"))

        val current = jdbc.sql("select count(*) from factor_release where is_current").query(Long::class.javaObjectType).single()
        assertEquals(1L, current, "exactly one current release")
        assertEquals(2, repository.factors(v1).size, "old releases are kept")
    }

    @Test
    fun `refuses to publish an empty release`() {
        assertFailsWith<IllegalArgumentException> { repository.publish("test", emptyList()) }
    }
}
