package inventory.lotsizing

import inventory.lotsizing.models.*
import kotlin.math.sqrt
import kotlin.test.*

/** @sec-eoq-sensitivity and @sec-costparams-h: what a wrong quantity costs, and what a wrong parameter costs. */
class SensitivityTest {

    private fun item() = CostParameters(
        demandRate = 1500.0,
        unitCost = 40.0,
        orderCost = 120.0,
        holding = HoldingCost.rate(8.8),
        leadTime = 10.0 / 365.0,
    )

    /**
     * @eq-eoq-penalty. The penalty depends only on the ratio of the chosen quantity to
     * the optimal one, and not on the size of the item.
     */
    @Test
    fun `the penalty depends only on the ratio`() {
        val small = item()
        val large = item().also { it.demandRate = 150_000.0; it.orderCost = 4_000.0 }
        for (ratio in listOf(0.25, 0.5, 0.8, 1.25, 2.0, 4.0)) {
            val expected = 0.5 * (ratio + 1.0 / ratio)
            for (parameters in listOf(small, large)) {
                val best = EconomicOrderQuantity.optimize(parameters)
                val chosen = EconomicOrderQuantity.evaluate(parameters, ratio * best.orderQuantity)
                assertEquals(expected, chosen.penaltyAgainst(best).ratio, 1e-12,
                    "ratio $ratio")
            }
        }
    }

    /**
     * @sec-costparams-h. Deciding under a parameter that is wrong by a factor r costs the
     * same as choosing a quantity wrong by the square root of r, because the quantity
     * moves as the square root of the parameter.
     */
    @Test
    fun `deciding under a wrong parameter follows the square root law`() {
        val truth = item()
        for (parameter in listOf(CostParameters::orderCost, CostParameters::demandRate)) {
            for (factor in listOf(0.5, 0.8, 1.25, 2.0)) {
                val believed = truth.snapshot().toMutable()
                parameter.set(believed, parameter.get(truth) * factor)
                val penalty = EconomicOrderQuantity.penaltyOfDecidingUnder(believed, truth)
                val expected = 0.5 * (sqrt(factor) + 1.0 / sqrt(factor))
                assertEquals(expected, penalty.ratio, 1e-10,
                    "${parameter.name} wrong by a factor of $factor")
            }
        }
        // The holding rate moves the quantity the other way, and costs the same.
        for (factor in listOf(0.5, 2.0)) {
            val believed = truth.snapshot().toMutable().also { it.holdingRate = 8.8 * factor }
            val penalty = EconomicOrderQuantity.penaltyOfDecidingUnder(believed, truth)
            assertEquals(0.5 * (sqrt(factor) + 1.0 / sqrt(factor)), penalty.ratio, 1e-10)
        }
    }

    /** The order quantity moves as the square root, so the elasticities are one half. */
    @Test
    fun `the elasticities are one half`() {
        val parameters = item()
        assertEquals(0.5, EconomicOrderQuantity.elasticityOf(parameters, CostParameters::orderCost), 1e-6)
        assertEquals(0.5, EconomicOrderQuantity.elasticityOf(parameters, CostParameters::demandRate), 1e-6)
        assertEquals(-0.5, EconomicOrderQuantity.elasticityOf(parameters, CostParameters::holdingRate), 1e-6)
    }

    /**
     * The two directions do not agree, and it would be a mistake to assume they do.
     * The penalty is symmetric in the ratio and its reciprocal, but a percentage
     * error up and the same percentage down are not reciprocal ratios: overestimating
     * by a quarter is a factor of 1.25 and underestimating by a quarter is 0.75, whose
     * reciprocal is 1.333. So underestimating costs more, and both are carried.
     */
    @Test
    fun `a sensitivity carries both directions, which differ`() {
        val sensitivity = EconomicOrderQuantity
            .sensitivityTo(item(), CostParameters::orderCost, assumedError = 0.25)
        assertEquals(120.0, sensitivity.base, 1e-12)
        assertEquals(0.5, sensitivity.elasticity, 1e-6)

        assertEquals(0.5 * (sqrt(1.25) + 1.0 / sqrt(1.25)), sensitivity.overestimate.ratio, 1e-10)
        assertEquals(0.5 * (sqrt(0.75) + 1.0 / sqrt(0.75)), sensitivity.underestimate.ratio, 1e-10)
        assertEquals(1.006231, sensitivity.overestimate.ratio, 5e-7)
        assertEquals(1.010363, sensitivity.underestimate.ratio, 5e-7)
        assertTrue(sensitivity.underestimate.ratio > sensitivity.overestimate.ratio,
            "understating a parameter by a given percentage costs more than overstating it")
        assertEquals(sensitivity.underestimate.ratio, sensitivity.worstRatio, 0.0)

        // The symmetry that does hold is in the ratio, not in the percentage.
        val believedLower = item().also { it.orderCost = 120.0 * 0.8 }
        assertEquals(sensitivity.overestimate.ratio,
            EconomicOrderQuantity.penaltyOfDecidingUnder(believedLower, item()).ratio, 1e-10,
            "a factor of 0.8 costs what a factor of 1.25 costs, being its reciprocal")
    }

    @Test
    fun `a ranking puts the parameter that matters most first`() {
        val ranked = EconomicOrderQuantity.sensitivities(
            item(),
            listOf(CostParameters::orderCost, CostParameters::demandRate, CostParameters::holdingRate),
            assumedError = 0.30,
        )
        assertEquals(3, ranked.size)
        assertTrue(ranked[0].worstRatio >= ranked[1].worstRatio)
        assertTrue(ranked[1].worstRatio >= ranked[2].worstRatio)
        // All three move the quantity by the same elasticity, so the ranking is a tie
        // broken by name. That is itself worth knowing about this model.
        assertEquals(ranked.map { it.worstRatio }.distinct().size, 1,
            "for the classical model every parameter carries the same penalty")
    }

    /** Analyzing over a range is lazy: nothing is computed until an element is pulled. */
    @Test
    fun `a range is lazy and invokes the model once per element taken`() {
        val counter = CountingModel(EconomicOrderQuantity)
        val levels = DoubleArray(10_000) { 50.0 + it * 0.1 }
        val sequence = counter.analyzing(item(), CostParameters::orderCost, levels)
        assertEquals(0, counter.optimizeCalls, "constructing the range computes nothing")

        val first = sequence.first()
        assertEquals(1, counter.optimizeCalls, "taking one element computes one analysis")
        assertEquals(50.0, first.parameters.orderCost, 1e-12)

        val cheapest = counter.analyzing(item(), CostParameters::orderCost, levels)
            .minBy { it.measures.relevantCost }
        assertTrue(counter.optimizeCalls > 1)
        assertEquals(50.0, cheapest.parameters.orderCost, 1e-12,
            "the cheapest is at the smallest ordering cost, which is the first level")
    }

    @Test
    fun `a grid crosses every combination in row-major order`() {
        val analyses = EconomicOrderQuantity.analyzing(
            item(),
            CostParameters::orderCost to doubleArrayOf(100.0, 200.0),
            CostParameters::demandRate to doubleArrayOf(1000.0, 2000.0, 3000.0),
        ).toList()
        assertEquals(6, analyses.size)
        assertEquals(
            listOf(100.0 to 1000.0, 100.0 to 2000.0, 100.0 to 3000.0,
                   200.0 to 1000.0, 200.0 to 2000.0, 200.0 to 3000.0),
            analyses.map { it.parameters.orderCost to it.parameters.demandRate },
        )
    }

    @Test
    fun `named scenarios come back labelled`() {
        val results = EconomicOrderQuantity.analyzing(
            item(),
            mapOf(
                "renegotiated freight" to { orderCost = 60.0 },
                "offshore supplier" to { unitCost = 36.0; leadTime = 45.0 / 365.0 },
            ),
        )
        assertEquals(setOf("renegotiated freight", "offshore supplier"), results.keys)
        assertTrue(results.getValue("renegotiated freight").orderQuantity <
            EconomicOrderQuantity.optimize(item()).orderQuantity,
            "a smaller ordering cost means smaller, more frequent orders")
    }

    /**
     * @sec-eoq-leadtime. A lead time longer than a cycle means orders are already
     * outstanding, and the reorder point counts only the part not covered by them.
     */
    @Test
    fun `the reorder point handles a lead time longer than a cycle`() {
        val monthly = CostParameters(
            demandRate = 100.0,
            unitCost = 8.0,
            orderCost = 20.0,
            holding = HoldingCost.carryingCharge(0.25 / 12.0),
            leadTime = 0.25,
            timeUnit = TimeUnit.MONTH,
        )
        val shortLead = EconomicOrderQuantity.optimize(monthly)
        assertEquals(154.919334, shortLead.orderQuantity, 1e-6)
        assertEquals(1.549193, shortLead.cycleTime, 1e-6)
        assertEquals(25.0, shortLead.reorderPoint, 1e-9)

        monthly.leadTime = 4.0
        val longLead = EconomicOrderQuantity.optimize(monthly)
        assertEquals(90.161332, longLead.reorderPoint, 1e-6,
            "two orders are outstanding, so only the remainder needs covering")
        assertTrue(longLead.reorderPoint < longLead.orderQuantity,
            "a reorder point above the order quantity would not be a position to order at")

        monthly.leadTime = 2.0
        assertEquals(45.080666, EconomicOrderQuantity.optimize(monthly).reorderPoint, 1e-6,
            "the reorder point is not monotone in the lead time")
    }

    /** A model that counts how often it was asked to optimize. */
    private class CountingModel(private val delegate: LotSizingModel) : LotSizingModel by delegate {
        var optimizeCalls = 0
            private set

        override fun optimize(
            parameters: CostParametersIfc,
            rounding: QuantityRounding,
        ): InventoryPolicyAnalysisIfc {
            optimizeCalls++
            return delegate.optimize(parameters, rounding)
        }
    }
}
