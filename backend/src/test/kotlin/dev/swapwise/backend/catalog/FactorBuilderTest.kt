package dev.swapwise.backend.catalog

import dev.swapwise.backend.parsing.ParseCacheKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FactorBuilderTest {
    // Illustrative numbers for tests only, not real Agribalyse values.
    private val agribalyse = """
        Code AGB;Nom du Produit en Français;Changement climatique
        B1;"Boeuf, steak haché";30,0
        B2;Boeuf, rôti;20,0
        C1;Poulet, filet;6,0
        O1;Boisson à l'avoine;0,5
    """.trimIndent()

    private val mapping = """
        category,typical_item_kg,agribalyse_codes
        BEEF,,B1|B2
        POULTRY,0.5,C1
        PLANT_MILK,,O1
        FRUIT,0.15,
    """.trimIndent()

    @Test
    fun `reads semicolon CSV with quoted commas and French decimals`() {
        val products = FactorBuilder.readAgribalyse(agribalyse.reader(), ';')
        assertEquals(4, products.size)
        assertEquals("Boeuf, steak haché", products[0].name)
        assertEquals(30.0, products[0].kgCo2ePerKg)
    }

    @Test
    fun `builds mean and min-max range per category`() {
        val result = FactorBuilder.build(
            FactorBuilder.readAgribalyse(agribalyse.reader(), ';'),
            FactorBuilder.readMapping(mapping.reader()),
        )
        val beef = result.factors.single { it.category == FoodCategory.BEEF }
        assertEquals(25.0, beef.kgCo2ePerKg)
        assertEquals(20.0, beef.kgCo2ePerKgLow)
        assertEquals(30.0, beef.kgCo2ePerKgHigh)
        assertEquals(0.5, result.factors.single { it.category == FoodCategory.POULTRY }.typicalItemKg)
        assertTrue(FoodCategory.FRUIT in result.categoriesWithoutFactor, "mapped with no codes yet")
        assertTrue(FoodCategory.LAMB in result.categoriesWithoutFactor, "not in the mapping at all")
    }

    @Test
    fun `fails loudly on a code missing from Agribalyse`() {
        val bad = "category,typical_item_kg,agribalyse_codes\nBEEF,,B1|NOPE\n"
        val error = assertFailsWith<IllegalArgumentException> {
            FactorBuilder.build(FactorBuilder.readAgribalyse(agribalyse.reader(), ';'), FactorBuilder.readMapping(bad.reader()))
        }
        assertTrue("NOPE" in error.message!!)
    }

    @Test
    fun `fails loudly on unknown categories, duplicates and missing columns`() {
        assertFailsWith<IllegalArgumentException> {
            FactorBuilder.readMapping("category,typical_item_kg,agribalyse_codes\nUNICORN,,B1\n".reader())
        }
        assertFailsWith<IllegalArgumentException> {
            FactorBuilder.build(emptyList(), listOf(CategoryMapping(FoodCategory.BEEF, null, emptyList()), CategoryMapping(FoodCategory.BEEF, null, emptyList())))
        }
        assertFailsWith<IllegalArgumentException> { FactorBuilder.readAgribalyse("Code AGB;Other\nB1;x\n".reader(), ';') }
    }

    @Test
    fun `csv reader handles escaped quotes, CRLF and newlines inside quotes`() {
        val rows = CsvReader.read("a,\"b \"\"x\"\"\",c\r\n\"multi\nline\",2,3".reader()).toList()
        assertEquals(listOf(listOf("a", "b \"x\"", "c"), listOf("multi\nline", "2", "3")), rows)
    }

    @Test
    fun `cache key depends on prompt version and line, and is a sha-256 hex string`() {
        val key = ParseCacheKey.of("v1", "ORG BNNA 2LB")
        assertEquals(64, key.length)
        assertEquals(key, ParseCacheKey.of("v1", "ORG BNNA 2LB"))
        assertTrue(key != ParseCacheKey.of("v2", "ORG BNNA 2LB"))
    }
}
