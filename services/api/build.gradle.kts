import dev.detekt.gradle.Detekt

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("dev.detekt") version "2.0.0-alpha.3"
}

group = "com.evnsolution.ups"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    // JDBC, a pool and migrations without Spring Boot's DataSource auto-configuration: the database is
    // opt-in (ups.db.url), so a plain run and most tests never need one.
    implementation("org.springframework:spring-jdbc")
    implementation("com.zaxxer:HikariCP")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // Verifies Firebase ID tokens (phone proof) against Google's public keys.
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    // Geometry for administrative boundaries: union of 행정동 into 시·군·구 now, snapping and overlap in zone editing.
    // The database has no PostGIS, so shapes are computed here. EPL 2.0 / EDL 1.0.
    implementation("org.locationtech.jts:jts-core:1.20.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.19.0")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // ContractTest reads this file, so Gradle must rerun tests when it changes.
    inputs.file("../../contract/openapi.yaml")
}

detekt {
    toolVersion = "2.0.0-alpha.3"
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = false
    parallel = false
    config.setFrom(files("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
}
