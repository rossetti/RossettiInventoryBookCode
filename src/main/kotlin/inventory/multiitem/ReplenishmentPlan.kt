package inventory.multiitem

import inventory.lotsizing.CostPenalty

/**
 * How much and how often, for every SKU in a portfolio.
 *
 * One answer type serves all five models of Chapter 4. Quantity and interval are two
 * views of one decision, related by Q = lambda * T, and the models differ only in which
 * one they solve for. If a model turned up whose answer did not fit here, the chapter's
 * claim that every model answers how much and when would be false.
 */
class ReplenishmentPlan(
    val skus: List<SKU>,
    private val quantities: List<Double>,
    /** Zero unless a constraint produced this plan. @sec-multiitem-multiplier proves it reaches zero
     *  exactly when a limit stops binding, so there is no missing case to model. */
    val shadowPrice: Double = 0.0,
) {
    init {
        require(skus.size == quantities.size) {
            "Expected one quantity per SKU, got ${quantities.size} for ${skus.size}"
        }
        require(quantities.all { it > 0.0 }) { "Every order quantity must be positive" }
    }

    fun quantityOf(sku: SKU): Double = quantities[indexOf(sku)]
    fun intervalOf(sku: SKU): Double = sku.intervalFor(quantityOf(sku))
    fun multiplierOf(sku: SKU, basePeriod: Double): Int {
        require(basePeriod > 0.0) { "A base period must be positive, was $basePeriod" }
        return Math.round(intervalOf(sku) / basePeriod).toInt()
    }

    val orderQuantities: List<Double> get() = quantities
    val intervals: List<Double> get() = skus.indices.map { skus[it].intervalFor(quantities[it]) }

    /** Delegates to the measure, which owns the formula while this owns the data. */
    fun measuredBy(measure: AggregateMeasure): Double = measure.measure(this)

    fun penaltyAgainst(reference: ReplenishmentPlan): CostPenalty {
        val mine = measuredBy(RelevantCost)
        val theirs = reference.measuredBy(RelevantCost)
        return CostPenalty(ratio = mine / theirs, difference = mine - theirs)
    }

    /**
     * Whether every replenishment of a slower SKU coincides with one of every faster
     * SKU, which holds when the smaller multipliers divide the larger ones. Section
     * 4.5.2 is the reason powers of two are used at all.
     */
    fun nestsOn(basePeriod: Double): Boolean {
        val ms = skus.map { multiplierOf(it, basePeriod) }.distinct().sorted()
        return ms.all { it >= 1 } && ms.zipWithNext().all { (a, b) -> b % a == 0 }
    }

    private fun indexOf(sku: SKU): Int {
        val at = skus.indexOfFirst { it === sku }
        require(at >= 0) { "${sku.label} is not in this plan" }
        return at
    }

    override fun toString(): String =
        "ReplenishmentPlan(skus=${skus.size}, cost=${measuredBy(RelevantCost)}, " +
            "shadowPrice=$shadowPrice)"
}
