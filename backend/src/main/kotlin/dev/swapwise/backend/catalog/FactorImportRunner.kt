package dev.swapwise.backend.catalog

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * One-off import, run from the command line (see README):
 *
 *     java -jar swapwise-backend.jar --spring.main.web-application-type=none \
 *       --swapwise.import.agribalyse-file=agribalyse.csv --swapwise.import.mapping-file=category-mapping.csv
 *
 * Builds the factors, publishes them as a new release, prints a summary and exits. Fails before writing
 * anything if the files are inconsistent.
 */
@Component
@ConditionalOnProperty("swapwise.import.agribalyse-file")
class FactorImportRunner(
    private val repository: FactorRepository,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val env = context.environment
        val agribalyseFile = Path.of(env.getRequiredProperty("swapwise.import.agribalyse-file"))
        val mappingFile = Path.of(env.getRequiredProperty("swapwise.import.mapping-file"))
        val delimiter = env.getProperty("swapwise.import.delimiter", ";").single()

        val products = Files.newBufferedReader(agribalyseFile).use { FactorBuilder.readAgribalyse(it, delimiter) }
        val mappings = Files.newBufferedReader(mappingFile).use { FactorBuilder.readMapping(it) }
        val result = FactorBuilder.build(products, mappings)
        val version = repository.publish("Agribalyse (${agribalyseFile.fileName})", result.factors)

        log.info("Published factor release v{}: {} categories from {} Agribalyse products", version, result.factors.size, products.size)
        if (result.categoriesWithoutFactor.isNotEmpty()) {
            log.warn("No factor yet for: {}", result.categoriesWithoutFactor.joinToString())
        }
        exitProcess(SpringApplication.exit(context, ExitCodeGenerator { 0 }))
    }
}
