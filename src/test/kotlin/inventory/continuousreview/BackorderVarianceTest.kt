package inventory.continuousreview

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** @eq-rq-backorder-second, against the figures @exm-rq-transformer publishes. */
class BackorderVarianceTest {

    private val transformer = RQModel(
        demandRate = 45.0, orderCost = 220.0,
        holdingCost = 950.0, backorderCost = 8550.0,
        leadTimeDemand = LeadTimeDemand.poisson(7.5),
    )

    @Test
    fun `the transformer figures of @exm-rq-transformer`() {
        // "From @eq-rq-backorder-second, E[B^2] = 0.3533, so Var[B] = 0.3533 - 0.1202^2
        //  = 0.3388 and the standard deviation of the backorder level is 0.582."
        assertEquals(0.1202, transformer.expectedBackorders(9, 5), 5e-5)
        assertEquals(0.3533, transformer.expectedBackordersSecondMoment(9, 5), 5e-5)
        assertEquals(0.3388, transformer.varianceBackorders(9, 5), 5e-5)
        assertEquals(0.582, sqrt(transformer.varianceBackorders(9, 5)), 5e-4)
    }

    @Test
    fun `the standard deviation is nearly five times the mean`() {
        // The reading @sec-continuousreview-batch-variance asks for: the mean is not a typical state.
        val mean = transformer.expectedBackorders(9, 5)
        val sd = sqrt(transformer.varianceBackorders(9, 5))
        assertTrue(sd / mean > 4.5, "ratio was ${sd / mean}")
    }

    /**
     * The discrete second moment against its definition: condition on the
     * position, which is uniform on the integers r+1..r+Q, and average
     * E[((D-j)+)^2] = 2 G2(j) + G1(j), Equation C.12.
     */
    @Test
    fun `discrete second moment matches averaging over the band directly`() {
        val ltd = LeadTimeDemand.poisson(7.5)
        for ((r, q) in listOf(9 to 5, 4 to 5, 12 to 3, 0 to 8, -2 to 6)) {
            var total = 0.0
            for (j in (r + 1)..(r + q)) {
                total += 2.0 * ltd.lossSecond(j.toDouble()) + ltd.lossFirst(j.toDouble())
            }
            val model = RQModel(
                demandRate = 45.0, orderCost = 220.0,
                holdingCost = 950.0, backorderCost = 8550.0, leadTimeDemand = ltd,
            )
            assertEquals(
                total / q, model.expectedBackordersSecondMoment(r, q), 5e-9,
                "second moment at ($r, $q)",
            )
        }
    }

    /**
     * The continuous second moment against its definition: the position is
     * uniform on the INTERVAL (r, r+Q], so the average is an integral, and
     * E[((D-u)+)^2] = 2 G2(u) with no first order term.
     */
    @Test
    fun `continuous second moment matches integrating over the band directly`() {
        val ltd = LeadTimeDemand.gamma(34.6154, 34.6154 * 2.2)
        val model = RQModel(
            demandRate = 900.0, orderCost = 82.5,
            holdingCost = 12.0, backorderCost = 287.5, leadTimeDemand = ltd,
        )
        for ((r, q) in listOf(30 to 116, 25 to 40, 40 to 8)) {
            val n = 4000
            val h = q.toDouble() / n
            var sum = 0.0
            for (i in 0..n) {
                val u = r + i * h
                val w = if (i == 0 || i == n) 1.0 else if (i % 2 == 1) 4.0 else 2.0
                sum += w * 2.0 * ltd.lossSecond(u)
            }
            val exact = sum * h / 3.0 / q
            // 5e-6 relative: Simpson's own error plus the cancellation in the
            // gamma closed form, which is documented below.
            assertEquals(
                exact, model.expectedBackordersSecondMoment(r, q), 5e-6 * exact,
                "second moment at ($r, $q)",
            )
        }
    }

    /**
     * The gamma closed form of Table C.6 is a difference of four terms that
     * cancel heavily far out in the tail, where `x^3` is large and every
     * complementary distribution function is tiny. The true value there is
     * negligible, so it is clamped at zero rather than allowed to go slightly
     * negative on rounding. This test records that as intended behaviour, and
     * checks that the function stays monotone where it matters.
     */
    @Test
    fun `the gamma third order loss is monotone and clamps in the far tail`() {
        val ltd = LeadTimeDemand.gamma(34.6154, 34.6154 * 2.2)
        var previous = Double.MAX_VALUE
        for (x in 0..200) {
            val value = ltd.lossThird(x.toDouble())
            assertTrue(value >= 0.0, "negative G3 at $x")
            assertTrue(value <= previous + 1e-9, "G3 rose at $x")
            previous = value
        }
        assertTrue(ltd.lossThird(200.0) == 0.0, "expected the far tail to clamp")
        assertTrue(ltd.lossThird(60.0) > 0.0, "should still be positive in range")
    }

    @Test
    fun `the variance is never negative at extreme levels`() {
        val ltd = LeadTimeDemand.poisson(7.5)
        val model = RQModel(
            demandRate = 45.0, orderCost = 220.0,
            holdingCost = 950.0, backorderCost = 8550.0, leadTimeDemand = ltd,
        )
        for (r in 25..40) {
            assertTrue(model.varianceBackorders(r, 5) >= 0.0, "negative variance at r=$r")
        }
    }

    @Test
    fun `evaluate carries the variance alongside the mean`() {
        val p = transformer.evaluate(RQPolicy(9, 5))
        assertEquals(transformer.varianceBackorders(9, 5), p.varianceBackorders, 1e-15)
        assertEquals(0.3388, p.varianceBackorders, 5e-5)
    }

    @Test
    fun `the customer wait variance Chapter 9 needs`() {
        // The distributional form of Little's Law under Poisson arrivals gives
        // E[B(B-1)] = lambda^2 E[W^2], so
        //     Var[W] = (Var[B] - B-bar) / lambda^2
        // and NOT Var[B] / lambda^2. @sec-multiechelon-delay-little derives it; a simulation of
        // 18 million demands agrees with it to better than a quarter of a
        // percent on this policy.
        val lambda = 45.0
        val b = transformer.expectedBackorders(9, 5)
        val v = transformer.varianceBackorders(9, 5)
        val waitVar = (v - b) / (lambda * lambda)
        assertEquals((0.3388 - 0.1202) / 2025.0, waitVar, 5e-8)
        // The relation requires Var[B] >= B-bar, which is the over-dispersion a
        // mixed Poisson always has. A violation would mean the assumption behind
        // the relation has failed, so it is worth asserting rather than assuming.
        assertTrue(v > b, "Var[B] must exceed the mean for the relation to hold")
        assertEquals(3.793, kotlin.math.sqrt(waitVar) * 365.0, 5e-3)
    }
}
