package dev.swapwise.backend.catalog

/**
 * The fixed list of categories the receipt parser may return, and the key carbon factors are stored under.
 *
 * Why a short fixed list: the LLM picks reliably from about 30 labels, while Agribalyse has about 2,500
 * products, far too many to put in a prompt. Each category's factor is the average of the Agribalyse
 * products mapped to it, and the range shown to the user is their min to max (see [CategoryFactor]).
 *
 * Changing this list is an API change for the Android app: add values, never rename or remove them.
 */
enum class FoodCategory(val label: String) {
    BEEF("Beef"),
    LAMB("Lamb"),
    PORK("Pork"),
    POULTRY("Chicken and turkey"),
    FISH("Fish"),
    SEAFOOD("Shrimp and shellfish"),
    EGGS("Eggs"),
    MILK("Dairy milk"),
    PLANT_MILK("Plant-based milk"),
    CHEESE("Cheese"),
    YOGURT("Yogurt"),
    BUTTER("Butter"),
    PLANT_PROTEIN("Tofu, tempeh and meat alternatives"),
    LEGUMES("Beans, lentils and chickpeas"),
    NUTS("Nuts and seeds"),
    RICE("Rice"),
    PASTA("Pasta"),
    BREAD("Bread"),
    CEREAL("Breakfast cereal and oats"),
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
        /** Comma-separated codes for prompts. */
        fun promptValues(): String = entries.joinToString(", ") { it.name }
    }
}

/** Purchase units the parser can return. The app converts them to kg using [CategoryFactor.typicalItemKg]. */
enum class PurchaseUnit { EACH, KG, G, LB, OZ, L, ML }
