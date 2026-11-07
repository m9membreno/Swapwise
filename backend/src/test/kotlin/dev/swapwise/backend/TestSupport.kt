package dev.swapwise.backend

import dev.swapwise.backend.catalog.FoodCategory
import dev.swapwise.backend.catalog.PurchaseUnit
import dev.swapwise.backend.parsing.ParsedLine
import dev.swapwise.backend.parsing.ReceiptLineParser
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

    @Bean
    @Primary
    fun fakeParser(): FakeReceiptLineParser = FakeReceiptLineParser()
}

/**
 * Stands in for the LLM. Understands a tiny made-up format so tests can predict its answers:
 * a line containing "BEEF" is beef, "FEE" is not a grocery item, anything else is vegetables.
 */
class FakeReceiptLineParser : ReceiptLineParser {
    val calls = AtomicInteger()
    val linesSeen = AtomicInteger()

    @Volatile var delayMillis = 0L

    @Volatile var failWith: RuntimeException? = null

    override fun parse(lines: List<String>): List<ParsedLine> {
        calls.incrementAndGet()
        linesSeen.addAndGet(lines.size)
        if (delayMillis > 0) Thread.sleep(delayMillis)
        failWith?.let { throw it }
        return lines.map { line ->
            when {
                "FEE" in line.uppercase() -> ParsedLine(false, "Fee", FoodCategory.OTHER_FOOD, 1.0, PurchaseUnit.EACH)
                "BEEF" in line.uppercase() -> ParsedLine(true, "Ground beef", FoodCategory.BEEF, 1.0, PurchaseUnit.LB)
                else -> ParsedLine(true, line.lowercase(), FoodCategory.VEGETABLES, 1.0, PurchaseUnit.EACH)
            }
        }
    }

    fun reset() {
        calls.set(0)
        linesSeen.set(0)
        delayMillis = 0
        failWith = null
    }
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
abstract class BaseIT {
    @Autowired
    protected lateinit var mvc: MockMvcTester

    @Autowired
    protected lateinit var parser: FakeReceiptLineParser

    protected fun postJson(uri: String, json: String, installId: UUID = UUID.randomUUID()): MvcTestResult =
        mvc.post().uri(uri)
            .header("X-Install-Id", installId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .exchange()

    /**
     * A random 10-letter word, so every test gets lines nobody has cached yet. Letters only (long digit runs
     * would be masked), and only G to Z, so a word can never contain "BEEF" or "FEE", which the fake parser
     * treats specially.
     */
    protected fun uniqueWord(): String = String(CharArray(10) { ('G'..'Z').random() })
}
