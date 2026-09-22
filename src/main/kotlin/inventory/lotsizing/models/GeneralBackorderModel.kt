package inventory.lotsizing.models

import inventory.lotsizing.*
import kotlin.math.sqrt

/**
 * The general model of @sec-eoq-general: replenishment at a finite rate, shortages
 * permitted, and both shortage prices charged.
 *
 * Every classical model in @tbl-special-cases is this one with an assumption switched off.
 *
 * The closed form has two failure modes, and both occur at ordinary parameter
 * values rather than only at extremes. When the per-unit stockout charge is large
 * the expression under the inner root goes negative; a little below that, the
 * backorder level it produces goes negative. In each case the stationary point lies
 * outside the feasible region and the optimum is on the boundary, where no shortage
 * is planned. That is the production quantity, and the model returns it with the
 * choice recorded as a boundary solution rather than returning NaN.
 *
 * With a demand rate of 100, a replenishment rate of 250, an ordering cost of 20, a
 * holding rate of 4 and a backorder rate of 6, the backorder level turns negative by
 * a stockout charge of 1 and the root disappears by 1.55. A high enough price on
 * being short means the right answer is never to be short.
 */
object GeneralBackorderModel : AbstractLotSizingModel("General backorder model") {

    override val assumptions = ModelAssumptions(
        instantaneousReplenishment = false,
        shortagesForbidden = false,
        chargesPerUnitShort = true,
        requiresSchedule = Flat::class,
    )


    override fun choosePolicy(parameters: CostParametersIfc): ChosenPolicy {
        val shortages = parameters.shortages as ShortagePolicy.Backordered
        val holdingRate = parameters.baseHoldingRate
        val backorderRate = shortages.costPerUnitPerTime
        val stockoutCharge = shortages.costPerUnit
        val demandRate = parameters.demandRate
        val surviving = parameters.survivingFraction
        val sum = holdingRate + backorderRate

        // @eq-general-tc. The stockout term carries a second factor of the surviving
        // fraction because pi is charged on every unit that arrives to an empty shelf,
        // and the count of those is the peak backorder level divided by that fraction.
        val radicand = 2.0 * parameters.orderCost * demandRate / (holdingRate * surviving) -
            (stockoutCharge * demandRate) * (stockoutCharge * demandRate) /
                (holdingRate * sum * surviving * surviving)

        if (radicand <= 0.0) return boundary(parameters,
            "the per-unit stockout charge of $stockoutCharge leaves no interior optimum, " +
                "so no shortage is planned")

        val quantity = sqrt(sum / backorderRate) * sqrt(radicand)
        // @eq-general-q.
        val backorderLevel = (holdingRate * quantity * surviving - stockoutCharge * demandRate) / sum

        if (backorderLevel <= 0.0) return boundary(parameters,
            "the closed form gives a backorder level of ${"%.4f".format(backorderLevel)}, " +
                "which is outside the feasible region, so the optimum plans no shortage")

        return ChosenPolicy(Policy(quantity, backorderLevel), QuantityChoice.ClosedForm)
    }

    /**
     * The optimum on the boundary where no shortage is planned, which is the
     * production quantity of @sec-eoq-nobackorders.
     */
    private fun boundary(parameters: CostParametersIfc, reason: String): ChosenPolicy =
        ChosenPolicy(
            Policy(EconomicProductionQuantity.orderQuantityFor(parameters)),
            QuantityChoice.Boundary(reason),
        )
}
