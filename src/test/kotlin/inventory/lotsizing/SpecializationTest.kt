package inventory.lotsizing

import inventory.lotsizing.models.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertContains

/**
 * @tbl-special-cases made executable.
 *
 * The chapter's claim is that one general model specializes into four by switching
 * assumptions off. The special cases arise from parameter limits rather than from
 * overridden behaviour, so the relation is asserted here by driving the relaxed
 * parameter toward its limit and watching the general answer converge, rather than
 * being encoded as inheritance.
 */
class SpecializationTest {

    private fun base(
        replenishment: Replenishment = Replenishment.Instantaneous,
        shortages: ShortagePolicy = ShortagePolicy.NotPermitted,
    ) = CostParameters(
        demandRate = 1200.0,
        unitCost = 40.0,
        orderCost = 100.0,
        holding = HoldingCost.rate(6.0),
        replenishment = replenishment,
        shortages = shortages,
    )

    /** The tolerance must tighten as the limit is approached, or stalling passes. */
    private fun assertConverges(
        target: Double,
        approach: List<Double>,
        what: String,
    ) {
        var previousError = Double.MAX_VALUE
        approach.forEachIndexed { step, value ->
            val error = abs(value - target) / abs(target)
            assertTrue(error < previousError,
                "$what: convergence stalled at step $step, error $error was not below $previousError")
            previousError = error
        }
        assertTrue(previousError < 1.0e-6,
            "$what: the last error was $previousError, which is not close enough to the limit")
    }

    @Test
    fun `production quantity becomes the order quantity as the rate grows`() {
        val target = EconomicOrderQuantity.optimize(base()).orderQuantity
        val approach = listOf(1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7).map { factor ->
            val p = base(replenishment = Replenishment.atRate(1200.0 * factor, 1200.0))
            EconomicProductionQuantity.optimize(p).orderQuantity
        }
        assertConverges(target, approach, "EPQ to EOQ as p grows")
    }

    @Test
    fun `planned backorders become the order quantity as backordering grows expensive`() {
        val target = EconomicOrderQuantity.optimize(base()).orderQuantity
        val approach = listOf(1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7).map { b ->
            val p = base(shortages = ShortagePolicy.backordered(b))
            PlannedBackorderModel.optimize(p).orderQuantity
        }
        assertConverges(target, approach, "planned backorders to EOQ as b grows")
    }

    @Test
    fun `the general model becomes the production quantity as backordering grows expensive`() {
        val finite = Replenishment.atRate(4000.0, 1200.0)
        val target = EconomicProductionQuantity.optimize(base(replenishment = finite)).orderQuantity
        val approach = listOf(1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7).map { b ->
            val p = base(replenishment = finite, shortages = ShortagePolicy.backordered(b))
            GeneralBackorderModel.optimize(p).orderQuantity
        }
        assertConverges(target, approach, "general to EPQ as b grows")
    }

    @Test
    fun `the general model becomes the planned backorder model as the rate grows`() {
        val shortages = ShortagePolicy.backordered(costPerUnitPerTime = 9.0)
        val target = PlannedBackorderModel.optimize(base(shortages = shortages)).orderQuantity
        val approach = listOf(1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7).map { factor ->
            val p = base(replenishment = Replenishment.atRate(1200.0 * factor, 1200.0),
                         shortages = shortages)
            GeneralBackorderModel.optimize(p).orderQuantity
        }
        assertConverges(target, approach, "general to planned backorders as p grows")
    }

    /**
     * The classical order quantity is reachable from the general model by two routes,
     * through the production model and through the planned backorder model. Both must
     * arrive at the same place, which is a stronger check than either alone.
     */
    @Test
    fun `both routes to the classical order quantity agree`() {
        val direct = EconomicOrderQuantity.optimize(base()).orderQuantity
        val viaProduction = EconomicProductionQuantity
            .optimize(base(replenishment = Replenishment.atRate(1200.0 * 1e7, 1200.0)))
            .orderQuantity
        val viaBackorders = PlannedBackorderModel
            .optimize(base(shortages = ShortagePolicy.backordered(1e7)))
            .orderQuantity
        assertEquals(direct, viaProduction, direct * 1e-6)
        assertEquals(direct, viaBackorders, direct * 1e-6)
    }

    /**
     * Both failure modes of the general closed form, at ordinary parameter values.
     * In each the optimum lies on the boundary where no shortage is planned, which is
     * the production quantity, and the model must report that rather than a NaN.
     */
    @Test
    fun `the general model takes the boundary when the closed form leaves the region`() {
        fun parameters(stockoutCharge: Double) = CostParameters(
            demandRate = 100.0,
            unitCost = 8.0,
            orderCost = 20.0,
            holding = HoldingCost.rate(4.0),
            replenishment = Replenishment.atRate(250.0, 100.0),
            shortages = ShortagePolicy.backordered(
                costPerUnitPerTime = 6.0, costPerUnit = stockoutCharge),
        )
        val boundaryQuantity = EconomicProductionQuantity.optimize(parameters(0.0)).orderQuantity
        assertEquals(40.8248, boundaryQuantity, 0.0005)

        // No stockout charge: the interior optimum of @eq-nopi.
        val noCharge = GeneralBackorderModel.optimize(parameters(0.0))
        assertIs<QuantityChoice.ClosedForm>(noCharge.choice)
        assertEquals(52.7046, noCharge.orderQuantity, 0.0005)
        assertEquals(12.6491, noCharge.maxBackorder, 0.0005)

        // A modest charge still leaves an interior optimum, with a smaller shortage.
        val interior = GeneralBackorderModel.optimize(parameters(0.5))
        assertIs<QuantityChoice.ClosedForm>(interior.choice)
        assertEquals(49.8841, interior.orderQuantity, 0.0005)
        assertEquals(6.9722, interior.maxBackorder, 0.0005)

        // A negative backorder level: the stationary point is outside the region.
        val negativeLevel = GeneralBackorderModel.optimize(parameters(1.0))
        assertIs<QuantityChoice.Boundary>(negativeLevel.choice)
        assertContains(negativeLevel.choice.toString(), "backorder level")
        assertEquals(boundaryQuantity, negativeLevel.orderQuantity, 1e-12)
        assertEquals(0.0, negativeLevel.maxBackorder, 1e-12)

        // A negative radicand: no real root at all. The threshold is about 1.55.
        val noRoot = GeneralBackorderModel.optimize(parameters(2.0))
        assertIs<QuantityChoice.Boundary>(noRoot.choice)
        assertContains(noRoot.choice.toString(), "no interior optimum")
        assertEquals(boundaryQuantity, noRoot.orderQuantity, 1e-12)
        assertTrue(noRoot.orderQuantity.isFinite())
    }

    /**
     * @sec-eoq-classic's arithmetic check. At an optimum of a cost of the form A over Q
     * plus B times Q the two terms are equal, and with planned backorders the holding
     * and backorder terms together take the place of the second.
     */
    @Test
    fun `ordering cost equals the carrying terms at the optimum`() {
        val cases = listOf(
            EconomicOrderQuantity to base(),
            EconomicProductionQuantity to base(replenishment = Replenishment.atRate(4000.0, 1200.0)),
            PlannedBackorderModel to base(shortages = ShortagePolicy.backordered(9.0)),
        )
        for ((model, parameters) in cases) {
            val m = model.optimize(parameters).measures
            assertEquals(m.ordering, m.holding + m.backorder, m.ordering * 1e-9,
                "${model.name}: the balance property of @sec-eoq-classic")
        }
    }

    /** The same property must not hold for the discount models. Guards R8. */
    @Test
    fun `the discount models do not balance`() {
        val parameters = CostParameters(
            demandRate = 8000.0,
            schedule = AllUnits(listOf(PriceLevel(0.0, 10.0), PriceLevel(500.0, 9.0))),
            orderCost = 30.0,
            holding = HoldingCost.carryingCharge(0.30),
        )
        val m = AllUnitsDiscountModel.optimize(parameters).measures
        assertTrue(abs(m.ordering - m.holding) > 1.0,
            "the all-units answer is a boundary solution and is not expected to balance")
    }

    /** @tbl-special-cases as a choice over the two sealed assumption types. */
    @Test
    fun `the model for a parameter set is the one whose assumptions match`() {
        assertTrue(LotSizingModel.forParameters(base()) === EconomicOrderQuantity)
        assertTrue(LotSizingModel.forParameters(
            base(replenishment = Replenishment.atRate(4000.0, 1200.0))) === EconomicProductionQuantity)
        assertTrue(LotSizingModel.forParameters(
            base(shortages = ShortagePolicy.backordered(9.0))) === PlannedBackorderModel)
        assertTrue(LotSizingModel.forParameters(
            base(replenishment = Replenishment.atRate(4000.0, 1200.0),
                 shortages = ShortagePolicy.backordered(9.0))) === GeneralBackorderModel)
    }
}
