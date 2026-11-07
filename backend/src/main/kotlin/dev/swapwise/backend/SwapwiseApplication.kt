package dev.swapwise.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SpringBootApplication
@ConfigurationPropertiesScan
class SwapwiseApplication

fun main(args: Array<String>) {
    runApplication<SwapwiseApplication>(*args)
}

@Configuration(proxyBeanMethods = false)
class InfrastructureConfig {
    /** Injected everywhere time matters, so tests can control it. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** Runs LLM calls so they can be abandoned after a timeout. Virtual threads: cheap to block. */
    @Bean(destroyMethod = "close")
    fun llmExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
}
