package inventory.lotsizing.models

import inventory.lotsizing.*
import kotlin.math.sqrt

/**
 * The classical economic order quantity of @sec-eoq-classic: the whole order arrives at
 * once and no shortage is allowed.
 *
 * This is what the general model of @sec-eoq-general becomes as the replenishment rate
 * and the backorder cost both grow without bound. That relation is recorded by the
 * convergence tests rather than by inheritance, because a limit is not a subtype.
 */
object EconomicOrderQuantity : AbstractLotSizingModel("Economic order quantity") {

    override val assumptions = ModelAssumptions(
        instantaneousReplenishment = true,
        shortagesForbidden = true,
        chargesPerUnitShort = false,
        requiresSchedule = Flat::class,
    )


    override fun choosePolicy(parameters: CostParametersIfc): ChosenPolicy {
        val quantity = orderQuantityFor(
            orderCost = parameters.orderCost,
            demandRate = parameters.demandRate,
            holdingRate = parameters.baseHoldingRate,
        )
        return ChosenPolicy(Policy(quantity), QuantityChoice.ClosedForm)
    }

    /** @eq-eoq, square root of 2 k lambda over h. */
    fun orderQuantityFor(orderCost: Double, demandRate: Double, holdingRate: Double): Double {
        require(orderCost > 0.0) { "An ordering cost must be positive, was $orderCost" }
        require(demandRate > 0.0) { "A demand rate must be positive, was $demandRate" }
        require(holdingRate > 0.0) {
            "A holding rate of $holdingRate makes holding free, so there is no economic " +
                "order quantity. @sec-eoq-classic assumes a positive holding rate."
        }
        return sqrt(2.0 * orderCost * demandRate / holdingRate)
    }
}
