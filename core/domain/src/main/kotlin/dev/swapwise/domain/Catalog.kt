package dev.swapwise.domain

/**
 * Must match the backend's `FoodCategory` codes exactly: they travel over the API as strings.
 * Unknown codes from a newer backend are mapped to null by [fromCode] instead of crashing old app versions.
 */
enum class FoodCategory(val label: String) {
    BEEF("Beef"),
    LAMB("Lamb"),
    PORK("Pork"),
    POULTRY("Chicken or turkey"),
    FISH("Fish"),
    SEAFOOD("Shrimp and shellfish"),
    EGGS("Eggs"),
    MILK("Dairy milk"),
    PLANT_MILK("Plant-based milk"),
    CHEESE("Cheese"),
    YOGURT("Yogurt"),
    BUTTER("Butter"),
    PLANT_PROTEIN("Tofu or meat alternative"),
    LEGUMES("Beans or lentils"),
    NUTS("Nuts and seeds"),
    RICE("Rice"),
    PASTA("Pasta"),
    BREAD("Bread"),
    CEREAL("Cereal or oats"),
    POTATOES("Potatoes"),
    VEGETABLES("Vegetables"),
    FRUIT("Fruit"),
    COFFEE("Coffee"),
    TEA("Tea"),
    CHOCOLATE("Chocolate"),
    SNACKS("Chips and snacks"),
    SOFT_DRINKS("Soft drinks"),
    JUICE("Juice"),
    OIL("Cooking oil"),
    OTHER_FOOD("Other food"),
    ;

    companion object {
        fun fromCode(code: String?): FoodCategory? = entries.firstOrNull { it.name == code }
    }
}

enum class PurchaseUnit {
    EACH, KG, G, LB, OZ, L, ML;

    companion object {
        fun fromCode(code: String?): PurchaseUnit? = entries.firstOrNull { it.name == code }
    }
}

/** Carbon factor for one category, as served by `GET /api/v1/factors`. */
data class CategoryFactor(
    val category: FoodCategory,
    val kgCo2ePerKg: Double,
    val kgCo2ePerKgLow: Double,
    val kgCo2ePerKgHigh: Double,
    val typicalItemKg: Double?,
)

/** One product line of a parsed receipt. [priceCents] is the line total read from the receipt, if found. */
data class ReceiptItem(
    val lineIndex: Int,
    val name: String,
    val category: FoodCategory,
    val quantity: Double,
    val unit: PurchaseUnit,
    val priceCents: Long?,
)

object UnitConverter {
    private const val KG_PER_LB = 0.45359237
    private const val KG_PER_OZ = 0.028349523125

    /**
     * Weight in kg, or null when it cannot be known (an EACH item in a category without a typical weight).
     * Liquids assume 1 kg per litre, which is close enough for milk, juice and soft drinks.
     */
    fun toKg(quantity: Double, unit: PurchaseUnit, typicalItemKg: Double?): Double? {
        if (!quantity.isFinite() || quantity <= 0.0) return null
        return when (unit) {
            PurchaseUnit.KG, PurchaseUnit.L -> quantity
            PurchaseUnit.G, PurchaseUnit.ML -> quantity / 1000.0
            PurchaseUnit.LB -> quantity * KG_PER_LB
            PurchaseUnit.OZ -> quantity * KG_PER_OZ
            PurchaseUnit.EACH -> typicalItemKg?.let { it * quantity }
        }
    }
}
