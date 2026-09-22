package inventory.lotsizing

/**
 * An unchangeable record of the parameter values that produced a result.
 *
 * Every analysis carries one, so editing the [CostParameters] that produced an
 * analysis does not move the analysis. Because it is itself a [CostParametersIfc],
 * an analysis can be re-analyzed from its own record.
 */
data class ParameterSnapshot(
    override val demandRate: Double,
    override val orderCost: Double,
    override val schedule: PriceSchedule,
    override val holding: HoldingCost,
    override val replenishment: Replenishment,
    override val shortages: ShortagePolicy,
    override val leadTime: Double,
    override val positionCost: Double,
    override val timeUnit: TimeUnit,
) : CostParametersIfc {

    override fun snapshot(): ParameterSnapshot = this

    /** A mutable parameter set with these values, for exploring from this point. */
    fun toMutable(): CostParameters = CostParameters(
        demandRate, schedule, orderCost, holding, replenishment, shortages,
        leadTime, positionCost, timeUnit,
    )
}
