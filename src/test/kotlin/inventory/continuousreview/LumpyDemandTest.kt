package inventory.continuousreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * @exm-lumpy-cutout and @tbl-lumpy-compare: the fuse cutout drawn in job lots,
 * against the same 800 units a year drawn one at a time.
 *
 * The point of the file is the last row of the table. Under lots the ready rate
 * and the unit fill rate are different numbers, and the second is the lower.
 */
class LumpyDemandTest {

    private val lots = LotSize.of(1 to 0.40, 2 to 0.40, 4 to 0.20)
    private val epochsPerYear = 400.0
    private val leadTime = 1.0 / 26.0

    private val lumpy = RQModel(
        demandRate = epochsPerYear * lots.mean,
        orderCost = 82.50,
        holdingCost = 28.75,
        backorderCost = 287.50,
        leadTimeDemand = LeadTimeDemand.compoundPoisson(epochsPerYear * leadTime, lots),
        lotSize = lots,
    )

    private val oneAtATime = RQModel(
        demandRate = 800.0,
        orderCost = 82.50,
        holdingCost = 28.75,
        backorderCost = 287.50,
        leadTimeDemand = LeadTimeDemand.poisson(800.0 * leadTime),
    )

    @Test
    fun `the lot size and the lead time demand match @eq-lumpy-moments`() {
        assertEquals(2.0, lots.mean, 1e-12)
        assertEquals(5.2, lots.secondMoment, 1e-12)
        assertEquals(30.7692, lumpy.theta, 5e-5)
        assertEquals(80.0, lumpy.leadTimeDemand.variance, 1e-6)
    }

    @Test
    fun `the lumpy column of @tbl-lumpy-compare`() {
        assertEquals(43, lumpy.optimalBaseStock())
        val best = RQOptimizer(lumpy).optimize()
        assertEquals(RQPolicy(25, 75), best.policy)
        assertEquals(2010.22, best.cost, 0.005)
        val p = lumpy.evaluate(best.policy)
        assertEquals(0.9060, p.readyRate, 5e-5)
        assertEquals(0.8978, p.fillRate, 5e-5)
        assertEquals(0.8957, lumpy.lotFillRate(25, 75), 5e-5)
    }

    /** The four terms @exm-lumpy-cutout sums by hand. */
    @Test
    fun `the terms of @eq-lumpy-fillrate at the optimum`() {
        val d = lumpy.leadTimeDemand
        fun atLeast(j: Int) = (26..100).sumOf { s -> d.cdf((s - j).toDouble()) } / 75
        assertEquals(0.9060, atLeast(1), 5e-5)
        assertEquals(0.8960, atLeast(2), 5e-5)
        assertEquals(0.8855, atLeast(3), 5e-5)
        assertEquals(0.8746, atLeast(4), 5e-5)
        assertEquals(lumpy.readyRate(25, 75), atLeast(1), 1e-12)
        val byHand = (1.0 * atLeast(1) + 0.6 * atLeast(2) + 0.2 * atLeast(3) + 0.2 * atLeast(4)) / 2.0
        assertEquals(byHand, lumpy.fillRate(25, 75), 1e-12)
        val whole = 0.4 * atLeast(1) + 0.4 * atLeast(2) + 0.2 * atLeast(4)
        assertEquals(whole, lumpy.lotFillRate(25, 75), 1e-12)
    }

    @Test
    fun `the one-at-a-time column of @tbl-lumpy-compare`() {
        assertEquals(38, oneAtATime.optimalBaseStock())
        val best = RQOptimizer(oneAtATime).optimize()
        assertEquals(RQPolicy(24, 73), best.policy)
        assertEquals(1921.64, best.cost, 0.005)
        val p = oneAtATime.evaluate(best.policy)
        assertEquals(0.9038, p.readyRate, 5e-5)
        assertEquals(p.readyRate, p.fillRate, 0.0, "@eq-fr-equals-rr under single-unit Poisson demand")
    }

    /**
     * The fill rate as the expectation it is defined to be, computed by
     * conditioning on the position and on the lead time demand directly rather
     * than through the loss functions.
     */
    @Test
    fun `the unit fill rate agrees with direct conditioning`() {
        val d = lumpy.leadTimeDemand
        val (r, q) = 25 to 75
        var filled = 0.0
        for (position in (r + 1)..(r + q)) {
            for (demand in 0..position) {
                val mass = d.cdf(demand.toDouble()) - d.cdf(demand - 1.0)
                val onHand = position - demand
                for ((y, p) in lots.masses) filled += mass * p * minOf(y, onHand) / q
            }
        }
        assertEquals(filled / lots.mean, lumpy.fillRate(r, q), 1e-10)
    }

    @Test
    fun `lots of one give back the ready rate on every policy`() {
        val single = RQModel(800.0, 82.50, 28.75, 287.50, LeadTimeDemand.compoundPoisson(800.0 * leadTime, LotSize.ONE))
        for (r in listOf(-3, 0, 10, 24, 40)) {
            assertEquals(single.readyRate(r, 73), single.fillRate(r, 73), 1e-12)
        }
        // The general sum stops at j = 1 under lots of one, so it is the ready rate term for term.
        val forced = RQModel(800.0, 82.50, 28.75, 287.50, single.leadTimeDemand, LotSize.of(1 to 1.0))
        assertEquals(forced.readyRate(24, 73), forced.fillRate(24, 73), 1e-15)
    }

    @Test
    fun `the fill rate falls below the ready rate whenever lots exceed one`() {
        for (r in listOf(0, 15, 25, 35)) {
            assertTrue(lumpy.fillRate(r, 75) < lumpy.readyRate(r, 75), "at r = $r")
        }
    }

    @Test
    fun `lots need a discrete lead time demand`() {
        assertFailsWith<IllegalArgumentException> {
            RQModel(800.0, 82.50, 28.75, 287.50, LeadTimeDemand.gamma(30.77, 80.0), lots)
        }
    }
}
