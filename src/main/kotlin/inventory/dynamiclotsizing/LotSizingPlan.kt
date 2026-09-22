package inventory.dynamiclotsizing

import inventory.lotsizing.CostPenalty

/**
 * When to order and how much, for one item over one horizon. @sec-dls-cost.
 *
 * The plan holds the order quantities and computes everything else. Ending inventory is
 * implied by the balance of @eq-dls-balance, so storing it would let a plan exist whose
 * inventory no sequence of orders produces.
 */
class LotSizingPlan(
    val schedule: RequirementsSchedule,
    private val quantities: List<Double>,
) {
    init {
        require(quantities.size == schedule.horizon) {
            "Expected one order quantity per period, got ${quantities.size} " +
                "for ${schedule.horizon}"
        }
        require(quantities.all { it >= 0.0 }) { "An order quantity cannot be negative" }
        var onHand = 0.0
        schedule.periods.forEach { t ->
            onHand += quantities[t - 1] - schedule.requirementIn(t)
            require(onHand >= -TOLERANCE) {
                "The plan runs short in period $t, and @sec-dls-problem does not permit a shortage"
            }
        }
    }

    fun orderIn(period: Int): Double = quantities[period - 1]

    /** @eq-dls-balance accumulated: what is left at the end of [period]. */
    fun endingInventoryIn(period: Int): Double =
        (1..period).sumOf { quantities[it - 1] - schedule.requirementIn(it) }

    /** The periods that carry an order, which is all a plan really decides. */
    val orderPeriods: List<Int> get() = schedule.periods.filter { quantities[it - 1] > 0.0 }

    val orderCount: Int get() = orderPeriods.size

    val setupCost: Double get() = orderPeriods.sumOf { schedule.orderCostIn(it) }

    val purchaseCost: Double
        get() = schedule.periods.sumOf { schedule.unitCostIn(it) * quantities[it - 1] }

    val carryingCost: Double
        get() = schedule.periods.sumOf { schedule.holdingRateIn(it) * endingInventoryIn(it) }

    /**
     * Setup plus carrying. This is what the examples report, because @sec-dls-cost shows
     * the purchase term to be the same under every plan while the unit cost is constant.
     */
    val relevantCost: Double get() = setupCost + carryingCost

    /** Every term, including the purchase cost, which matters once the unit cost varies. */
    val totalCost: Double get() = relevantCost + purchaseCost

    /**
     * @sec-dls-properties: does the plan order only into an empty shelf? Every rule here produces
     * such a plan, and the property is what reduces the search to a choice of periods.
     */
    fun ordersOnlyWhenEmpty(): Boolean = orderPeriods.all { t ->
        t == 1 || endingInventoryIn(t - 1) <= TOLERANCE
    }

    fun penaltyAgainst(reference: LotSizingPlan): CostPenalty {
        require(reference.schedule === schedule) {
            "A penalty compares two plans for the same schedule"
        }
        return CostPenalty(
            ratio = relevantCost / reference.relevantCost,
            difference = relevantCost - reference.relevantCost,
        )
    }

    override fun toString(): String =
        "LotSizingPlan(orders=${orderPeriods}, relevantCost=$relevantCost)"

    companion object {
        private const val TOLERANCE = 1.0E-9
    }
}
