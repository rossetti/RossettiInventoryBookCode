package inventory.lotsizing.models

import inventory.lotsizing.*
import kotlin.math.sqrt

/**
 * The economic production quantity of @sec-eoq-nobackorders: replenishment arrives at a
 * finite rate and no shortage is allowed.
 *
 * The model is the order model with a different holding rate. Spreading the receipt
 * over time lowers the average inventory for a given lot, so the effective holding
 * rate is h times the surviving fraction, and the ordinary square root formula is
 * then applied to it.
 */
object EconomicProductionQuantity : AbstractLotSizingModel("Economic production quantity") {

    override val assumptions = ModelAssumptions(
        instantaneousReplenishment = false,
        shortagesForbidden = true,
        chargesPerUnitShort = false,
        requiresSchedule = Flat::class,
    )


    override fun choosePolicy(parameters: CostParametersIfc): ChosenPolicy =
        ChosenPolicy(Policy(orderQuantityFor(parameters)), QuantityChoice.ClosedForm)

    /** @eq-epq, with the effective holding rate of @sec-eoq-nobackorders. */
    fun orderQuantityFor(parameters: CostParametersIfc): Double =
        EconomicOrderQuantity.orderQuantityFor(
            orderCost = parameters.orderCost,
            demandRate = parameters.demandRate,
            holdingRate = parameters.baseHoldingRate * parameters.survivingFraction,
        )
}
