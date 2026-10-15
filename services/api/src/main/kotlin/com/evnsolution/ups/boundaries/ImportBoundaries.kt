package com.evnsolution.ups.boundaries

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.sql.DataSource
import kotlin.system.exitProcess

/** Writes boundaries. An import replaces every row in one transaction, so a reader never sees half of two versions. */
class BoundaryStore(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {
    fun replace(rows: List<Boundary>, source: String, asOf: LocalDate) {
        val now = OffsetDateTime.now(clock)
        transactions.executeWithoutResult {
            // Children first: a parent row is referred to by its children.
            jdbc.sql("DELETE FROM admin_boundary WHERE level = 'DONG'").update()
            jdbc.sql("DELETE FROM admin_boundary WHERE level = 'SGG'").update()
            jdbc.sql("DELETE FROM admin_boundary").update()
            rows.forEach { row ->
                val box = row.geometry.envelopeInternal
                jdbc.sql(
                    "INSERT INTO admin_boundary (code, level, name, parent_code, stat_code, geometry, " +
                        "min_lng, min_lat, max_lng, max_lat, source, as_of, imported_at) VALUES (:code, :level, " +
                        ":name, :parent, :stat, :geometry, :x0, :y0, :x1, :y1, :source, :asOf, :at)",
                )
                    .param("code", row.code).param("level", row.level).param("name", row.name)
                    .param("parent", row.parentCode).param("stat", row.statCode)
                    .param("geometry", geoJson(row.geometry))
                    .param("x0", box.minX).param("y0", box.minY).param("x1", box.maxX).param("y1", box.maxY)
                    .param("source", source).param("asOf", asOf).param("at", now)
                    .update()
            }
        }
    }
}

/**
 * Imports the 행정동 file from a terminal, then ends the process:
 *
 *     java -jar ups-api.jar --ups.import-boundaries=<HangJeongDong_verYYYYMMDD.geojson>
 *
 * The date is read from the file name (ver20260701). Needs the database settings of a normal run.
 */
@Component
@ConditionalOnProperty("ups.import-boundaries")
class ImportBoundaries(
    private val dataSource: DataSource,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val context: ConfigurableApplicationContext,
    @param:Value("\${ups.import-boundaries}") private val file: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val path = Path.of(file)
        val asOf = VERSION.find(path.fileName.toString())?.groupValues?.get(1)
            ?.let { LocalDate.parse("${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}") }
        if (asOf == null || !Files.isRegularFile(path)) {
            System.err.println("Give the admdongkor file named with its version: HangJeongDong_ver20260701.geojson")
            exitProcess(1)
        }
        val rows = AdmDongKor.read(Files.readString(path))
        val source = "vuski/admdongkor ${path.fileName} (SGIS 통계청 행정동 경계, CC BY 4.0)"
        BoundaryStore(JdbcClient.create(dataSource), transactions, clock).replace(rows, source, asOf)
        rows.groupingBy { it.level }.eachCount().forEach { (level, count) -> println("$level: $count") }
        exitProcess(SpringApplication.exit(context))
    }

    private companion object {
        val VERSION = Regex("ver(\\d{8})")
    }
}
