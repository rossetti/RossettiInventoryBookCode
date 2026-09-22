package inventory.lotsizing

/**
 * The closed-form performance measures for a deterministic cycle.
 *
 * The cost members are computed here from the cycle and the prices. The service
 * members are not computed here at all: the cycle already implements
 * [ServiceMeasureIfc], so they are delegated to it and exist in exactly one place.
 */
class PerformanceMeasures(
    val cycle: InventoryCycle,
    parameters: CostParametersIfc,
) : PerformanceMeasureIfc, ServiceMeasureIfc by cycle {

    override val timeUnit: TimeUnit = parameters.timeUnit

    private val orderQuantity = cycle.policy.orderQuantity

    override val ordering: Double = parameters.orderCost * cycle.orderFrequency

    override val holding: Double =
        parameters.holdingRateAt(orderQuantity) * cycle.averageOnHand

    override val backorder: Double = when (val s = parameters.shortages) {
        is ShortagePolicy.NotPermitted -> 0.0
        is ShortagePolicy.Backordered -> s.costPerUnitPerTime * cycle.averageBackorder
    }

    /**
     * @eq-total-cost charges pi on the rate at which demand arrives to an empty shelf,
     * so this is exactly that: pi times [unfilledDemandRate].
     *
     * The count of units backordered in a cycle is not the peak backorder level.
     * Demand keeps arriving while the queue drains, so the count is lambda times the
     * length of the shortage, which is the peak divided by the surviving fraction.
     * The two agree only when replenishment is instantaneous. See @sec-eoq-cost.
     */
    override val stockout: Double = when (val s = parameters.shortages) {
        is ShortagePolicy.NotPermitted -> 0.0
        is ShortagePolicy.Backordered -> s.costPerUnit * cycle.unfilledDemandRate
    }

    override val position: Double = parameters.positionCost

    override val purchase: Double = parameters.unitCostAt(orderQuantity) * parameters.demandRate

    override val relevantCost: Double = ordering + holding + backorder + stockout

    override val totalCost: Double = relevantCost + purchase + position

    override val investment: Double =
        parameters.unitCostAt(orderQuantity) * cycle.averageOnHand

    override val costPerUnitDemanded: Double = totalCost / parameters.demandRate

    override val turnover: Double = parameters.demandRate / cycle.averageOnHand

    override fun toString(): String =
        "PerformanceMeasures(relevantCost=$relevantCost, totalCost=$totalCost, " +
            "readyRate=$readyRate, averageWait=$averageWait)"
}
