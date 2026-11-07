package dev.swapwise.backend.catalog

import java.io.Reader

/** One Agribalyse product: its code and its climate-change impact in kg CO2e per kg of product. */
data class AgribalyseProduct(val code: String, val name: String, val kgCo2ePerKg: Double)

/** Our mapping from a [FoodCategory] to the Agribalyse products that represent it. */
data class CategoryMapping(val category: FoodCategory, val typicalItemKg: Double?, val agribalyseCodes: List<String>)

/**
 * The carbon factor served to the app for one category.
 *
 * [kgCo2ePerKg] is the mean of the mapped products; [kgCo2ePerKgLow] and [kgCo2ePerKgHigh] are their
 * min and max. The app shows that range rather than a single number, because a category average can be
 * far from the exact product someone bought.
 *
 * [typicalItemKg] converts "EACH" purchases to kg (for example, one avocado is about 0.2 kg); null when
 * the category is not usually sold by the item.
 */
data class CategoryFactor(
    val category: FoodCategory,
    val kgCo2ePerKg: Double,
    val kgCo2ePerKgLow: Double,
    val kgCo2ePerKgHigh: Double,
    val typicalItemKg: Double?,
    val sourceCodes: List<String>,
)

/** Column names in the Agribalyse synthesis CSV. Configurable because they change between releases. */
data class AgribalyseColumns(
    val code: String = "Code AGB",
    val name: String = "Nom du Produit en Français",
    val climate: String = "Changement climatique",
)

/** Reads the input files and turns them into one factor per mapped category. Pure: no I/O of its own. */
object FactorBuilder {

    fun readAgribalyse(reader: Reader, delimiter: Char, columns: AgribalyseColumns = AgribalyseColumns()): List<AgribalyseProduct> {
        val rows = CsvReader.read(reader, delimiter).iterator()
        require(rows.hasNext()) { "Agribalyse file is empty" }
        val header = rows.next().map { it.trim().removePrefix("﻿") } // drop a UTF-8 byte-order mark
        fun indexOf(column: String): Int = header.indexOf(column).also {
            require(it >= 0) { "Agribalyse file has no '$column' column. Columns found: $header" }
        }
        val code = indexOf(columns.code)
        val name = indexOf(columns.name)
        val climate = indexOf(columns.climate)
        return rows.asSequence()
            .filter { row -> row.any { it.isNotBlank() } }
            .mapIndexed { i, row ->
                require(row.size == header.size) { "Row ${i + 2} has ${row.size} columns, expected ${header.size}" }
                AgribalyseProduct(row[code].trim(), row[name].trim(), parseNumber(row[climate], "row ${i + 2}"))
            }
            .toList()
    }

    /** Mapping file columns: category, typical_item_kg (may be empty), agribalyse_codes (separated by |). */
    fun readMapping(reader: Reader): List<CategoryMapping> {
        val rows = CsvReader.read(reader).iterator()
        require(rows.hasNext()) { "Mapping file is empty" }
        val header = rows.next().map { it.trim() }
        require(header == listOf("category", "typical_item_kg", "agribalyse_codes")) { "Unexpected mapping header: $header" }
        return rows.asSequence()
            .filter { row -> row.any { it.isNotBlank() } }
            .mapIndexed { i, row ->
                require(row.size == 3) { "Mapping row ${i + 2} must have 3 columns" }
                val category = runCatching { FoodCategory.valueOf(row[0].trim()) }
                    .getOrElse { throw IllegalArgumentException("Mapping row ${i + 2}: unknown category '${row[0]}'") }
                CategoryMapping(
                    category = category,
                    typicalItemKg = row[1].trim().takeIf { it.isNotEmpty() }?.let { parseNumber(it, "mapping row ${i + 2}") },
                    agribalyseCodes = row[2].split('|').map { it.trim() }.filter { it.isNotEmpty() },
                )
            }
            .toList()
    }

    /**
     * Joins the mapping with the products. Fails on anything that would silently produce a wrong number:
     * a category mapped twice, or a code that is not in the Agribalyse file. Categories with no codes yet
     * are skipped and reported, so the mapping can be filled in gradually.
     */
    fun build(products: List<AgribalyseProduct>, mappings: List<CategoryMapping>): BuildResult {
        val byCode = products.associateBy { it.code }
        val duplicates = mappings.groupBy { it.category }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Categories mapped more than once: $duplicates" }

        val factors = ArrayList<CategoryFactor>()
        val unmapped = ArrayList<FoodCategory>()
        for (mapping in mappings) {
            if (mapping.agribalyseCodes.isEmpty()) {
                unmapped += mapping.category
                continue
            }
            val matched = mapping.agribalyseCodes.map { code ->
                byCode[code] ?: throw IllegalArgumentException("${mapping.category}: Agribalyse code '$code' not found")
            }
            val values = matched.map { it.kgCo2ePerKg }
            factors += CategoryFactor(
                category = mapping.category,
                kgCo2ePerKg = values.average(),
                kgCo2ePerKgLow = values.min(),
                kgCo2ePerKgHigh = values.max(),
                typicalItemKg = mapping.typicalItemKg,
                sourceCodes = mapping.agribalyseCodes,
            )
        }
        val missing = FoodCategory.entries.toSet() - mappings.map { it.category }.toSet()
        return BuildResult(factors.sortedBy { it.category }, (unmapped + missing).sorted())
    }

    data class BuildResult(val factors: List<CategoryFactor>, val categoriesWithoutFactor: List<FoodCategory>)

    /** Accepts "12.5" and the French "12,5". Rejects negatives and non-numbers with a clear message. */
    private fun parseNumber(raw: String, where: String): Double {
        val text = raw.trim().let { if ('.' !in it) it.replace(',', '.') else it }
        val value = text.toDoubleOrNull() ?: throw IllegalArgumentException("$where: '$raw' is not a number")
        require(value.isFinite() && value >= 0.0) { "$where: value must be a non-negative number, got $raw" }
        return value
    }
}
