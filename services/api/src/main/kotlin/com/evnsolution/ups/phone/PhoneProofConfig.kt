package com.evnsolution.ups.phone

import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.time.Duration

/**
 * Turns phone proof on. It is off unless `ups.firebase.project-id` is set (environment variable
 * UPS_FIREBASE_PROJECT_ID). The project ID is not a secret. No credential is needed to check a token.
 */
@Configuration
@ConditionalOnProperty("ups.firebase.project-id")
class PhoneProofConfig {
    @Bean
    fun phoneProof(
        @Value("\${ups.firebase.project-id}") projectId: String,
        @Value("\${ups.firebase.proof-max-minutes:15}") maxMinutes: Long,
    ): PhoneProof {
        require(PROJECT_ID.matches(projectId)) { "ups.firebase.project-id is not a Firebase project ID" }
        val keys = JWKSourceBuilder.create<SecurityContext>(URI(KEYS_URL).toURL()).build()
        return FirebasePhoneProof(projectId, keys, maxAge = Duration.ofMinutes(maxMinutes))
    }

    private companion object {
        const val KEYS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
        val PROJECT_ID = Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")
    }
}
