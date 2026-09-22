package inventory.lotsizing.models

import inventory.lotsizing.*

/**
 * Incremental discounting, @sec-eoq-incremental: only the units above each break point earn
 * the lower rate.
 *
 * The cost rate is continuous here, so break points are not candidates. What changes
 * instead is that the amount already paid for the units below a break point behaves
 * exactly like a larger fixed charge, so each level has an effective ordering cost
 * and the ordinary square root formula applies level by level. A level's candidate
 * counts only if it falls inside that level's own interval.
 */
object IncrementalDiscountModel : AbstractLotSizingModel("Incremental discount model") {

    override val assumptions = ModelAssumptions(
        instantaneousReplenishment = true,
        shortagesForbidden = true,
        chargesPerUnitShort = false,
        requiresSchedule = Incremental::class,
    )


    /** The effective ordering cost at a level, @eq-incremental-kj. */
    fun effectiveOrderCost(parameters: CostParametersIfc, levelIndex: Int): Double {
        val schedule = parameters.schedule as Incremental
        return schedule.fixedChargeAt(levelIndex) + parameters.orderCost
    }

    override fun choosePolicy(parameters: CostParametersIfc): ChosenPolicy {
        val schedule = parameters.schedule as Incremental
        val candidates = buildList {
            schedule.levels.forEachIndexed { index, level ->
                val holdingRate =
                    AllUnitsDiscountModel.holdingRateFor(parameters, level.unitCost)
                val quantity = EconomicOrderQuantity.orderQuantityFor(
                    orderCost = effectiveOrderCost(parameters, index),
                    demandRate = parameters.demandRate,
                    holdingRate = holdingRate,
                )
                // Continuous cost, so only a candidate inside its own interval counts.
                if (schedule.isInsideLevel(index, quantity)) add(index to quantity)
            }
        }
        return AllUnitsDiscountModel.chooseAmong(parameters, candidates, schedule)
    }
}
