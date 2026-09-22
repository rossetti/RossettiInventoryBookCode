package inventory.lotsizing

/**
 * What one policy costs relative to another, which @sec-eoq-sensitivity calls the penalty.
 *
 * Computed on the relevant cost of @eq-relevant-cost and never on the total, because the
 * purchase term is common to both and @sec-eoq-classic observes it can be 97% of the
 * total, which would make every penalty look negligible.
 */
data class CostPenalty(
    /** The ratio of this policy's relevant cost to the reference policy's. */
    val ratio: Double,
    /** The difference in relevant cost, in currency per unit time. */
    val difference: Double,
) {
    /** The ratio expressed as an excess over one. */
    val relativeError: Double get() = ratio - 1.0

    override fun toString(): String =
        "CostPenalty(ratio=$ratio, difference=$difference, relativeError=$relativeError)"
}
