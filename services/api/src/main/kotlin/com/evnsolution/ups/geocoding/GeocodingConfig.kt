package com.evnsolution.ups.geocoding

import com.evnsolution.ups.partner.KeyFile
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/**
 * Turns on placing an address when an administrator types its Korean form. It is off unless
 * `ups.vworld.key-file` is set (environment variable UPS_VWORLD_KEY_FILE); without it a typed address is
 * saved and waits in the queue. The setting is the path of the key file, never the key.
 */
@Configuration
@ConditionalOnProperty("ups.vworld.key-file")
class GeocodingConfig {
    @Bean
    fun geocoder(@Value("\${ups.vworld.key-file}") keyFile: String): Geocoder =
        VWorldGeocoder(KeyFile.read(Path.of(keyFile), "V-World key"))
}
