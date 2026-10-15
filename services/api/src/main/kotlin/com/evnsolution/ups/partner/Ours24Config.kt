package com.evnsolution.ups.partner

import com.evnsolution.ups.orders.CachingOrderStore
import com.evnsolution.ups.orders.OrderStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.nio.file.Path
import java.time.Clock

/**
 * Connects the API to Ours24. It is off unless `ours24.key-file` is set (environment variable OURS24_KEY_FILE),
 * so a plain run and the tests never reach Ours24. The setting is the path of the key file, never the key.
 */
@Configuration
@ConditionalOnProperty("ours24.key-file")
class Ours24Config {
    @Bean
    fun ours24Client(
        @Value("\${ours24.base-url:https://api.ours-24.com}") baseUrl: String,
        @Value("\${ours24.key-file}") keyFile: String,
    ): Ours24Client {
        val path = Path.of(keyFile)
        KeyFile.read(path) // fail at startup, not on the first request, when the key file is unusable
        return Ours24Client(baseUrl, path)
    }

    @Bean
    @Primary
    fun ours24OrderStore(client: Ours24Client, clock: Clock): OrderStore =
        CachingOrderStore(Ours24OrderStore(client), clock)
}
