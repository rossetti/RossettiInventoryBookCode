package inventory.lotsizing

import inventory.lotsizing.models.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every worked example in Chapter 3, asserted against the numbers the text prints.
 * These fail if the book changes, which is the intent.
 */
class ChapterExamplesTest {

    /** @exm-cycle, working one cycle. */
    @Test
    fun `example 3-1 the general cycle`() {
        val parameters = CostParameters(
            demandRate = 100.0,
            unitCost = 8.0,
            orderCost = 20.0,
            holding = HoldingCost.rate(4.0),
            replenishment = Replenishment.atRate(250.0, 100.0),
            shortages = ShortagePolicy.backordered(costPerUnitPerTime = 6.0),
        )
        val analysis = GeneralBackorderModel.evaluate(
            parameters, Policy(orderQuantity = 400.0, maxBackorder = 30.0))
        val cycle = analysis.cycle

        assertEquals(210.0, cycle.maxOnHand, 1e-9, "peak on hand")
        assertEquals(listOf(0.2, 1.4, 2.1, 0.3), cycle.segments.toList().map { round(it, 9) })
        assertEquals(4.0, cycle.length, 1e-9, "cycle length is Q over lambda")
        assertEquals(91.875, cycle.averageOnHand, 1e-9)
        assertEquals(1.875, cycle.averageBackorder, 1e-9)
    }

    /** @exm-cycle-measures, performance of the general cycle. */
    @Test
    fun `example 3-6 performance measures of the general cycle`() {
        val parameters = CostParameters(
            demandRate = 100.0,
            unitCost = 8.0,
            orderCost = 20.0,
            holding = HoldingCost.rate(4.0),
            replenishment = Replenishment.atRate(250.0, 100.0),
            shortages = ShortagePolicy.backordered(costPerUnitPerTime = 6.0),
        )
        val m = GeneralBackorderModel
            .evaluate(parameters, Policy(400.0, 30.0)).measures

        assertEquals(0.25, m.let { 100.0 / 400.0 }, 1e-12, "order frequency")
        assertEquals(1.09, m.turnover, 0.005, "turnover, reported to two decimals")
        assertEquals(0.125, m.fractionOutOfStock, 1e-12)
        assertEquals(0.875, m.readyRate, 1e-12)
        assertEquals(12.5, m.unfilledDemandRate, 1e-12)
        assertEquals(0.01875, m.averageWait, 1e-12)
        assertEquals(6.8, m.averageWait * 365.0, 0.05, "about a week, in days")
        assertEquals(m.readyRate, m.fillRate, 1e-15,
            "equal here only because demand is constant, @sec-eoq-measures")
    }

    /** @exm-eoq, the economic order quantity. */
    @Test
    fun `example 3-2 the economic order quantity`() {
        val best = EconomicOrderQuantity.optimize(eoqExample())

        assertEquals(216.0, eoqExample().baseHoldingRate, 1e-12, "h = i c")
        assertEquals(40.37, best.orderQuantity, 0.005)
        assertEquals(0.1835, best.cycleTime, 0.00005)
        assertEquals(66.98, best.cycleTime * 365.0, 0.005, "cycle length in days")
        assertEquals(272_719.63, best.measures.totalCost, 0.005)
        assertEquals(8_719.63, best.measures.relevantCost, 0.005)
        assertEquals(264_000.0, best.measures.purchase, 1e-9)
        assertEquals(4.22, best.reorderPoint, 0.005, "lead time shorter than a cycle")

        assertEquals(best.measures.ordering, best.measures.holding, 1e-9,
            "at the optimum the two are equal, @sec-eoq-classic")
    }

    /** @exm-eoq-sensitivity, what a wrong order quantity costs. */
    @Test
    fun `example 3-3 sensitivity of the order quantity`() {
        val parameters = eoqExample()
        val best = EconomicOrderQuantity.optimize(parameters)
        val larger = EconomicOrderQuantity.evaluate(parameters, 1.5 * best.orderQuantity)

        assertEquals(13.0 / 12.0, larger.penaltyAgainst(best).ratio, 1e-12)
        assertEquals(0.0833, larger.penaltyAgainst(best).relativeError, 0.00005)
        assertEquals(727.0, larger.penaltyAgainst(best).difference, 1.0,
            "about 727 dollars a year against a total spend of 272,720")

        val half = EconomicOrderQuantity.evaluate(parameters, 0.5 * best.orderQuantity)
        val double = EconomicOrderQuantity.evaluate(parameters, 2.0 * best.orderQuantity)
        assertEquals(1.25, half.penaltyAgainst(best).ratio, 1e-12)
        assertEquals(1.25, double.penaltyAgainst(best).ratio, 1e-12,
            "the penalty is symmetric in the ratio and its reciprocal")
    }

    /** @exm-allunits, an all-units discount. */
    @Test
    fun `example 3-4 an all-units discount`() {
        val parameters = CostParameters(
            demandRate = 8000.0,
            schedule = AllUnits(listOf(PriceLevel(0.0, 10.0), PriceLevel(500.0, 9.0))),
            orderCost = 30.0,
            holding = HoldingCost.carryingCharge(0.30),
        )
        val best = AllUnitsDiscountModel.optimize(parameters)

        assertEquals(500.0, best.orderQuantity, 1e-9)
        assertEquals(73_155.0, best.measures.totalCost, 0.005)

        val candidates = (best.choice as QuantityChoice.AmongCandidates).candidates
        assertEquals(2, candidates.size, "the 400 at ten dollars and the break point at nine")
        val at400 = candidates.first { it.levelIndex == 0 }
        val at500 = candidates.first { it.levelIndex == 1 }
        assertEquals(400.0, at400.quantity, 1e-9)
        assertEquals(81_200.0, at400.totalCost, 0.005)
        assertTrue(at400.feasible, "400 lies in its own interval")
        assertEquals(500.0, at500.quantity, 1e-9)
        assertTrue(at500.selected)
        assertTrue(!at500.feasible || at500.quantity == 500.0)
    }

    /** @exm-incremental, an incremental discount. */
    @Test
    fun `example 3-5 an incremental discount`() {
        val parameters = incrementalExample()
        val schedule = parameters.schedule as Incremental

        assertEquals(listOf(0.0, 1500.0, 4470.0), schedule.filledIntervalCost,
            "the filled-interval costs of @eq-incremental-R")
        assertEquals(listOf(50.0, 65.0, 95.0),
            (0..2).map { IncrementalDiscountModel.effectiveOrderCost(parameters, it) },
            "the effective ordering costs of @eq-incremental-kj, which rise with the level")

        val best = IncrementalDiscountModel.optimize(parameters)
        assertEquals(661.6, best.orderQuantity, 0.05)
        assertEquals(9_501.73, best.measures.totalCost, 0.005)

        val candidates = (best.choice as QuantityChoice.AmongCandidates).candidates
        assertEquals(1, candidates.size,
            "only the middle level's quantity falls inside its own interval")
        assertEquals(1, candidates.single().levelIndex)
    }

    /**
     * The chapter reports the incremental total through the effective-ordering-cost
     * regrouping, where the two terms balance at 294.74 each. The natural
     * decomposition splits it differently and totals the same, which is the check
     * that the regrouping is algebra and not a different model.
     */
    @Test
    fun `the incremental regrouping totals the same as the natural decomposition`() {
        val parameters = incrementalExample()
        val best = IncrementalDiscountModel.optimize(parameters)
        val m = best.measures
        val q = best.orderQuantity

        val effectiveOrderCost = IncrementalDiscountModel.effectiveOrderCost(parameters, 1)
        val level = (parameters.schedule as Incremental).levels[1]
        val effectiveHolding = 0.30 * level.unitCost
        val regroupedOrdering = effectiveOrderCost * parameters.demandRate / q
        val regroupedHolding = effectiveHolding * q / 2.0
        val inert = level.unitCost * parameters.demandRate +
            0.30 * (parameters.schedule as Incremental).fixedChargeAt(1) / 2.0

        assertEquals(294.74, regroupedOrdering, 0.005)
        assertEquals(294.74, regroupedHolding, 0.005)
        assertEquals(m.totalCost, regroupedOrdering + regroupedHolding + inert, 1e-8)
        assertTrue(m.ordering != m.holding,
            "the natural decomposition does not balance, and is not expected to")
    }

    private fun eoqExample() = CostParameters(
        demandRate = 220.0,
        unitCost = 1200.0,
        orderCost = 800.0,
        holding = HoldingCost.carryingCharge(0.18),
        leadTime = 7.0 / 365.0,
    )

    private fun incrementalExample() = CostParameters(
        demandRate = 3000.0,
        schedule = Incremental(listOf(
            PriceLevel(0.0, 3.00), PriceLevel(500.0, 2.97), PriceLevel(1500.0, 2.95))),
        orderCost = 50.0,
        holding = HoldingCost.carryingCharge(0.30),
    )

    private fun round(value: Double, digits: Int): Double {
        val factor = Math.pow(10.0, digits.toDouble())
        return Math.round(value * factor) / factor
    }
}
