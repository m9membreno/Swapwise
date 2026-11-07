package dev.swapwise.feature.swaps

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Formatting kept in one pure file so it is unit-tested and consistent across screens. */
internal object Format {
    // v1 targets US receipts; cents are US cents.
    private val currency: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)

    fun money(cents: Long): String = currency.format(cents / 100.0)

    /** "$1.20 cheaper", "same price" or "$0.40 more". */
    fun priceChange(deltaCents: Long): PriceChange = when {
        deltaCents < 0 -> PriceChange.Cheaper(money(abs(deltaCents)))
        deltaCents > 0 -> PriceChange.MoreExpensive(money(deltaCents))
        else -> PriceChange.Same
    }

    /** Carbon is an estimate, so it is rounded to what the data can support: 1 decimal under 10 kg, whole kg above. */
    fun kg(value: Double): String = if (abs(value) < 10) String.format(Locale.US, "%.1f", value) else String.format(Locale.US, "%.0f", value)

    sealed interface PriceChange {
        data class Cheaper(val amount: String) : PriceChange
        data class MoreExpensive(val amount: String) : PriceChange
        data object Same : PriceChange
    }
}
