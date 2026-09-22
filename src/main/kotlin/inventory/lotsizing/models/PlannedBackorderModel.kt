package inventory.lotsizing.models

import inventory.lotsizing.*
import kotlin.math.sqrt

/**
 * The order quantity with planned backorders, @sec-eoq-instant: the whole order arrives
 * at once, shortages are permitted, and nothing is charged for the incident of being
 * short.
 *
 * Cheaper backordering means larger orders: the factor on the ordinary order
 * quantity is greater than one and grows as the backorder cost falls.
 */
object PlannedBackorderModel : AbstractLotSizingModel("Planned backorder model") {

    override val assumptions = ModelAssumptions(
        instantaneousReplenishment = true,
        shortagesForbidden = false,
        chargesPerUnitShort = false,
        requiresSchedule = Flat::class,
    )


    override fun choosePolicy(parameters: CostParametersIfc): ChosenPolicy {
        val shortages = parameters.shortages as ShortagePolicy.Backordered
        val holdingRate = parameters.baseHoldingRate
        val backorderRate = shortages.costPerUnitPerTime
        val quantity = sqrt((holdingRate + backorderRate) / backorderRate) *
            EconomicOrderQuantity.orderQuantityFor(
                parameters.orderCost, parameters.demandRate, holdingRate)
        val backorderLevel = quantity * holdingRate / (holdingRate + backorderRate)
        return ChosenPolicy(Policy(quantity, backorderLevel), QuantityChoice.ClosedForm)
    }
}
