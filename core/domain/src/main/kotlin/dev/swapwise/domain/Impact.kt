package dev.swapwise.domain

/** What a later receipt says about an accepted card. */
enum class FollowThrough {
    /** Still inside the window; no evidence either way yet. */
    PENDING,

    /** Every swapped-to category appeared after accepting, and none of them in the window before: a real change. */
    VERIFIED_NEW,

    /** Bought after accepting, but the shopper was already buying it before, so it may not be a change at all. */
    VERIFIED_ALREADY_BOUGHT,

    /** The window ended without the swap showing up on a receipt. */
    NOT_SEEN,
}

data class AcceptedCard(val id: String, val acceptedAtMillis: Long, val targets: Set<FoodCategory>) {
    init {
        require(targets.isNotEmpty()) { "A card swaps to at least one category" }
    }
}

/** The categories bought on one receipt and when it was scanned. */
data class ReceiptCategories(val scannedAtMillis: Long, val categories: Set<FoodCategory>)

/**
 * Checks accepted cards against later receipts.
 *
 * The naive rule ("the new category appeared later") over-counts: someone who already buys chicken every week
 * would "follow through" on beef -> chicken without changing anything. So the same window *before* accepting is
 * checked too, and only a category that is new to the shopper's recent receipts counts as [FollowThrough.VERIFIED_NEW].
 * That is the number reported as verified follow-through. It is still an estimate: the beef might be bought too,
 * and receipts from other stores are invisible.
 */
object FollowThroughChecker {
    const val WINDOW_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    fun evaluate(card: AcceptedCard, receipts: List<ReceiptCategories>, nowMillis: Long): FollowThrough {
        val start = card.acceptedAtMillis
        val after = receipts.filter { it.scannedAtMillis > start && it.scannedAtMillis <= start + WINDOW_MILLIS }
        val boughtAfter = card.targets.all { target -> after.any { target in it.categories } }
        if (!boughtAfter) return if (nowMillis > start + WINDOW_MILLIS) FollowThrough.NOT_SEEN else FollowThrough.PENDING

        val before = receipts.filter { it.scannedAtMillis >= start - WINDOW_MILLIS && it.scannedAtMillis <= start }
        val alreadyBought = card.targets.any { target -> before.any { target in it.categories } }
        return if (alreadyBought) FollowThrough.VERIFIED_ALREADY_BOUGHT else FollowThrough.VERIFIED_NEW
    }
}

enum class CardAnswer { SHOWN, ACCEPTED, DISMISSED }

data class CardOutcome(
    val answer: CardAnswer,
    val kgCo2eSaved: Double,
    val costDeltaCents: Long,
    val followThrough: FollowThrough?,
)

/** The numbers on the Impact screen. Only verified-new swaps count as carbon actually avoided. */
data class ImpactStats(
    val shown: Int,
    val accepted: Int,
    val dismissed: Int,
    val verifiedNew: Int,
    val verifiedAlreadyBought: Int,
    val pending: Int,
    val kgCo2eAccepted: Double,
    val kgCo2eVerified: Double,
    val centsSavedVerified: Long,
) {
    /** Share of answered cards that were accepted; null until something has been answered. */
    val acceptanceRate: Double? get() = (accepted + dismissed).takeIf { it > 0 }?.let { accepted.toDouble() / it }

    /** Share of accepted cards that turned into a real change; null until some card has a final result. */
    val followThroughRate: Double?
        get() = (accepted - pending).takeIf { it > 0 }?.let { verifiedNew.toDouble() / it }

    companion object {
        fun from(outcomes: List<CardOutcome>): ImpactStats {
            val accepted = outcomes.filter { it.answer == CardAnswer.ACCEPTED }
            val verified = accepted.filter { it.followThrough == FollowThrough.VERIFIED_NEW }
            return ImpactStats(
                shown = outcomes.size,
                accepted = accepted.size,
                dismissed = outcomes.count { it.answer == CardAnswer.DISMISSED },
                verifiedNew = verified.size,
                verifiedAlreadyBought = accepted.count { it.followThrough == FollowThrough.VERIFIED_ALREADY_BOUGHT },
                // An accepted card not yet checked counts as pending.
                pending = accepted.count { it.followThrough == null || it.followThrough == FollowThrough.PENDING },
                kgCo2eAccepted = accepted.sumOf { it.kgCo2eSaved },
                kgCo2eVerified = verified.sumOf { it.kgCo2eSaved },
                centsSavedVerified = verified.sumOf { -it.costDeltaCents },
            )
        }
    }
}
