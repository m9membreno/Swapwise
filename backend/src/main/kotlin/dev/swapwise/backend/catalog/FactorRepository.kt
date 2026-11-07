package dev.swapwise.backend.catalog

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

data class FactorRelease(val version: Int, val source: String)

/**
 * Carbon factors are versioned: an import never edits rows, it adds a new release and then marks it current.
 * Old releases stay, so a swap saved with factor version 3 can still be explained after version 4 ships.
 */
@Repository
class FactorRepository(
    private val jdbc: JdbcClient,
    private val batchJdbc: NamedParameterJdbcTemplate,
) {
    fun currentRelease(): FactorRelease? =
        jdbc.sql("select version, source from factor_release where is_current")
            .query(RowMapper { rs, _ -> FactorRelease(rs.getInt("version"), rs.getString("source")) })
            .optional()
            .orElse(null)

    fun factors(version: Int): List<CategoryFactor> =
        jdbc.sql(
            """
            select category, kg_co2e_per_kg, kg_co2e_per_kg_low, kg_co2e_per_kg_high, typical_item_kg, source_codes
            from carbon_factor
            where version = :version
            order by category
            """.trimIndent(),
        )
            .param("version", version)
            .query(
                RowMapper { rs, _ ->
                    CategoryFactor(
                        category = FoodCategory.valueOf(rs.getString("category")),
                        kgCo2ePerKg = rs.getDouble("kg_co2e_per_kg"),
                        kgCo2ePerKgLow = rs.getDouble("kg_co2e_per_kg_low"),
                        kgCo2ePerKgHigh = rs.getDouble("kg_co2e_per_kg_high"),
                        typicalItemKg = rs.getDouble("typical_item_kg").takeUnless { rs.wasNull() },
                        sourceCodes = rs.getString("source_codes").split('|').filter { it.isNotEmpty() },
                    )
                },
            )
            .list()

    /**
     * Stores a new release and makes it current in one transaction, so the app never sees a half-imported
     * version: readers get either the old release or the complete new one.
     */
    @Transactional
    fun publish(source: String, factors: List<CategoryFactor>): Int {
        require(factors.isNotEmpty()) { "Refusing to publish an empty factor release" }
        val version = jdbc.sql("select coalesce(max(version), 0) + 1 from factor_release").query(Int::class.javaObjectType).single()
        jdbc.sql("insert into factor_release (version, source) values (:version, :source)")
            .param("version", version)
            .param("source", source)
            .update()
        batchJdbc.batchUpdate(
            """
            insert into carbon_factor
                (version, category, kg_co2e_per_kg, kg_co2e_per_kg_low, kg_co2e_per_kg_high, typical_item_kg, source_codes)
            values (:version, :category, :mean, :low, :high, :typicalItemKg, :sourceCodes)
            """.trimIndent(),
            factors.map { f ->
                MapSqlParameterSource()
                    .addValue("version", version)
                    .addValue("category", f.category.name)
                    .addValue("mean", f.kgCo2ePerKg)
                    .addValue("low", f.kgCo2ePerKgLow)
                    .addValue("high", f.kgCo2ePerKgHigh)
                    .addValue("typicalItemKg", f.typicalItemKg)
                    .addValue("sourceCodes", f.sourceCodes.joinToString("|"))
            }.toTypedArray(),
        )
        // Two statements because a partial unique index allows only one current release at a time.
        jdbc.sql("update factor_release set is_current = false where is_current").update()
        jdbc.sql("update factor_release set is_current = true where version = :version").param("version", version).update()
        return version
    }
}
