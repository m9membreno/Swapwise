package dev.swapwise.backend.parsing

import dev.swapwise.backend.catalog.FoodCategory
import dev.swapwise.backend.catalog.PurchaseUnit
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Parsed lines keyed by [ParseCacheKey]. Stores the hash and the result, never the receipt line itself. */
@Repository
class ParseCacheRepository(
    private val jdbc: JdbcClient,
    private val batchJdbc: NamedParameterJdbcTemplate,
) {
    fun findAll(keys: Collection<String>): Map<String, ParsedLine> {
        if (keys.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            select cache_key, is_grocery, name, category, quantity, unit
            from parse_cache
            where cache_key in (:keys)
            """.trimIndent(),
        )
            .param("keys", keys.toList())
            .query(
                RowMapper { rs, _ ->
                    rs.getString("cache_key") to ParsedLine(
                        isGrocery = rs.getBoolean("is_grocery"),
                        name = rs.getString("name"),
                        category = FoodCategory.valueOf(rs.getString("category")),
                        quantity = rs.getDouble("quantity"),
                        unit = PurchaseUnit.valueOf(rs.getString("unit")),
                    )
                },
            )
            .list()
            .toMap()
    }

    /** Inserts new entries; a key that is already cached (for example, from a concurrent request) is kept as is. */
    fun saveAll(promptVersion: String, entries: Map<String, ParsedLine>) {
        if (entries.isEmpty()) return
        val batch = entries.map { (key, line) ->
            MapSqlParameterSource()
                .addValue("key", key)
                .addValue("promptVersion", promptVersion)
                .addValue("isGrocery", line.isGrocery)
                .addValue("name", line.name)
                .addValue("category", line.category.name)
                .addValue("quantity", line.quantity)
                .addValue("unit", line.unit.name)
        }
        batchJdbc.batchUpdate(
            """
            insert into parse_cache (cache_key, prompt_version, is_grocery, name, category, quantity, unit)
            values (:key, :promptVersion, :isGrocery, :name, :category, :quantity, :unit)
            on conflict (cache_key) do nothing
            """.trimIndent(),
            batch.toTypedArray(),
        )
    }
}
