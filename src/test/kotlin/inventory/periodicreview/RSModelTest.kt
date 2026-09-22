package inventory.periodicreview

import inventory.continuousreview.LeadTimeDemand
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins every number @sec-continuousreview-periodic quotes for the transformer of @exm-periodic-transformer:
 * 45 units a year, a two month lead time, a one month review, k = 220,
 * h = 950, b = 8550.
 */
class RSModelTest {

    private val lambda = 45.0
    private val model = RSModel(
        demandRate = lambda,
        orderCost = 220.0,
        holdingCost = 950.0,
        backorderCost = 8550.0,
        leadTime = 2.0 / 12.0,
        reviewInterval = 1.0 / 12.0,
        demand = DemandOverInterval.poisson(lambda),
    )

    @Test
    fun `the protection interval is the review interval plus the lead time`() {
        assertEquals(0.25, model.protectionInterval, 1.0e-12)
        assertEquals(11.25, model.meanProtectionDemand, 1.0e-12)
        assertEquals(9.375, model.meanExposure, 1.0e-12)
        assertEquals(3.75, lambda * model.reviewInterval, 1.0e-12)
    }

    @Test
    fun `the critical ratio picks the level of @tbl-periodic-transformer`() {
        assertEquals(0.90, model.criticalRatio, 1.0e-12)
        assertEquals(14, model.optimalLevel())
    }

    @Test
    fun `the cycle averaged distribution function reproduces @tbl-periodic-cdf`() {
        val expected = mapOf(12 to 0.8339, 13 to 0.8928, 14 to 0.9340, 15 to 0.9612, 16 to 0.9782)
        for ((level, value) in expected) {
            assertEquals(value, model.timeAveragedCdf(level), 5.0e-5, "G-bar at $level")
        }
    }

    @Test
    fun `demand over the protection interval is the trap of @exm-periodic-transformer`() {
        // Reading the critical ratio off G_tau instead of G-bar returns 16.
        val tau = LeadTimeDemand.poisson(11.25)
        var level = 0
        while (tau.cdf(level.toDouble()) < model.criticalRatio) level++
        assertEquals(16, level)
        val penalty = (model.cost(16) - model.cost(14)) / model.cost(14)
        assertEquals(0.107, penalty, 5.0e-4)
    }

    @Test
    fun `the measures reproduce @tbl-periodic-transformer`() {
        data class Row(val b: Double, val i: Double, val fr: Double, val cost: Double)
        val expected = mapOf(
            12 to Row(0.4233, 3.0483, 0.7549, 9155.15),
            13 to Row(0.2572, 3.8822, 0.8339, 8527.55),
            14 to Row(0.1501, 4.7751, 0.8928, 8459.31),
            15 to Row(0.0841, 5.7091, 0.9340, 8782.45),
            16 to Row(0.0453, 6.6703, 0.9612, 9363.85),
        )
        for ((level, row) in expected) {
            val p = model.evaluate(level)
            assertEquals(row.b, p.expectedBackorders, 5.0e-5, "backorders at $level")
            assertEquals(row.i, p.expectedOnHand, 5.0e-5, "on hand at $level")
            assertEquals(row.fr, p.fillRate, 5.0e-5, "fill rate at $level")
            assertEquals(row.cost, p.totalCost, 5.0e-3, "cost at $level")
        }
    }

    @Test
    fun `the ordering cost is the calendar and not the demand`() {
        assertEquals(12.0, model.orderFrequency, 1.0e-12)
        assertEquals(2640.0, model.evaluate(14).orderingCostRate, 1.0e-9)
    }

    @Test
    fun `the ready rate and the fill rate agree because demand is Poisson`() {
        for (level in 11..17) {
            assertEquals(
                model.readyRate(level), model.fillRate(level), 5.0e-5,
                "PASTA should make these equal at $level",
            )
        }
    }

    @Test
    fun `the end of the protection interval is an upper bound and not an estimate`() {
        val exact = model.expectedBackorders(14)
        val worst = model.expectedBackordersAtWorst(14)
        assertEquals(0.4200, worst, 5.0e-5)
        assertTrue(worst > exact)
        assertEquals(2.80, worst / exact, 5.0e-3)
    }

    @Test
    fun `Simpson on three points reproduces the integral`() {
        // @eq-periodic-simpson, the hand rule: three lookups at L, L + R/2 and tau.
        val l = LeadTimeDemand.poisson(7.5).lossFirst(14.0)
        val m = LeadTimeDemand.poisson(9.375).lossFirst(14.0)
        val t = LeadTimeDemand.poisson(11.25).lossFirst(14.0)
        assertEquals(0.0181, l, 5.0e-5)
        assertEquals(0.1156, m, 5.0e-5)
        assertEquals(0.4200, t, 5.0e-5)
        val hand = (l + 4.0 * m + t) / 6.0
        assertEquals(0.1501, hand, 5.0e-5)
        assertTrue(abs(hand - model.expectedBackorders(14)) < 1.0e-5)
    }

    @Test
    fun `the safety stock and cycle stock decompose the on hand`() {
        val p = model.evaluate(14)
        assertEquals(2.75, p.safetyStock, 1.0e-12)
        assertEquals(1.875, p.cycleStock, 1.0e-12)
        // I-bar = ss + lambda R / 2 + B-bar, @eq-periodic-onhand rearranged.
        assertEquals(
            p.expectedOnHand,
            p.safetyStock + p.cycleStock + p.expectedBackorders,
            1.0e-9,
        )
    }

    @Test
    fun `the economic interval is the EOQ as a time supply`() {
        val r = RSModel.economicInterval(220.0, 950.0, lambda)
        assertEquals(0.10145, r, 5.0e-6)
        val eoq = kotlin.math.sqrt(2.0 * 220.0 * lambda / 950.0)
        assertEquals(eoq / lambda, r, 1.0e-12)
        assertEquals(5.28, r * 52.0, 5.0e-3)
    }

    @Test
    fun `the economic interval lands within a percent of the best`() {
        fun bestCost(interval: Double): Double {
            val m = model.atInterval(interval)
            return m.cost(m.optimalLevel())
        }
        val rule = bestCost(RSModel.economicInterval(220.0, 950.0, lambda))
        var best = Double.MAX_VALUE
        var i = 1
        while (i <= 400) { best = minOf(best, bestCost(i * 0.001)); i++ }
        assertEquals(8195.01, best, 5.0e-2)
        assertEquals(8244.89, rule, 5.0e-2)
        assertTrue((rule - best) / best < 0.01, "the EOQ rule should be within a percent")
    }

    @Test
    fun `a vanishing review interval recovers the base stock policy`() {
        // @sec-continuousreview-periodic-protection: R -> 0 collapses the protection interval to L, and the
        // level returns to the S* = 11 of @exm-basestock-transformer.
        assertEquals(11, model.atInterval(1.0e-4).optimalLevel())
    }

    @Test
    fun `the demand family is held across the whole protection interval`() {
        // The meter socket: 900 a year, a two week lead time, a six week review.
        // The exposure runs from a mean of 34.6 up to 138.5, which straddles the
        // threshold at which `LeadTimeDemand.matched` switches to the gamma. The
        // family must be chosen once, or the quadrature averages two models.
        val rate = 900.0
        val supplier = DemandOverInterval.stationary(
            rate = rate,
            variancePerUnitTime = rate,
            familyChoiceLength = 8.0 / 52.0,
        )
        val shortEnd = supplier.over(2.0 / 52.0)
        val longEnd = supplier.over(8.0 / 52.0)
        assertEquals(longEnd.familyName, shortEnd.familyName)
        assertEquals(rate * 2.0 / 52.0, shortEnd.mean, 1.0e-9)
        assertEquals(rate * 8.0 / 52.0, longEnd.mean, 1.0e-9)
    }

    @Test
    fun `undershoot under Poisson demand is half a review interval`() {
        // @eq-periodic-undershoot with D(R) Poisson: E[U] = mu / 2 for every mean.
        val mu = lambda * model.reviewInterval
        val expectedUndershoot = ((mu + mu * mu) - mu) / (2.0 * mu)
        assertEquals(1.875, expectedUndershoot, 1.0e-12)
        assertEquals(model.cycleStock, expectedUndershoot, 1.0e-12)
    }
}
