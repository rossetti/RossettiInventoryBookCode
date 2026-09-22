package inventory.newsvendor

import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.Poisson
import ksl.utilities.random.rvariable.NegativeBinomialRV
import java.io.File
import ksl.utilities.statistic.Statistic
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every figure Chapter 7 prints, pinned to the cell.
 *
 * The chapter's history is generated from a fixed KSL stream rather than
 * observed, so a reader reproduces it exactly, and that only works while the
 * stream keeps producing the same series. The first test pins the series itself;
 * everything after it depends on those two moments.
 */
class ChapterFiguresTest {

    private val s = FinalBuy.sellingPrice
    private val c = FinalBuy.unitCost
    private val u = FinalBuy.salvageValue
    private val co = FinalBuy.overageCost
    private val cu = FinalBuy.underageCost
    private val ratio = cu / (cu + co)

    private val history = DemandFitting.history()
    private val stats = DemandFitting.summary(history)
    private val weeklyMean = stats.average
    private val weeklyVariance = stats.variance

    @Test
    fun `Section 7 point 1, the costs of the final buy`() {
        assertEquals(38.0, co, 1e-12, "overage, c - u")
        assertEquals(45.0, cu, 1e-12, "underage, s - c")
        assertEquals(0.542169, ratio, 5e-7, "the critical ratio")
    }

    @Test
    fun `Section 7 point 3, the weekly history`() {
        assertEquals(104, history.size)
        assertEquals(33.2404, weeklyMean, 5e-5)
        assertEquals(613.0387, weeklyVariance, 5e-5)
        assertEquals(24.7596, sqrt(weeklyVariance), 5e-5)
        assertEquals(0.7449, sqrt(weeklyVariance) / weeklyMean, 5e-5)
        assertEquals(18.4426, weeklyVariance / weeklyMean, 5e-5, "the variance to mean ratio")
        val sorted = history.sorted()
        assertEquals(0, sorted.first(), "the smallest week")
        assertEquals(113, sorted.last(), "the largest week")
        assertEquals(26.5, DemandFitting.quartiles(history).median, 1e-12, "the median")
        assertEquals(2, history.count { it == 0 }, "weeks with no demand")
        // The claim the section leads with: the median sits well below the mean.
        assertTrue(DemandFitting.quartiles(history).median < weeklyMean - 5.0,
            "the history is right-skewed")
    }

    @Test
    fun `Section 7 point 3, the history is a file and the file is what is read`() {
        // The chapter's data is a file, so the file is what the figures are
        // pinned to. A test that regenerated the series instead would pass while
        // the committed csv drifted away from it, which is the whole failure this
        // guards against.
        val file = File(FinalBuy.historyFile)
        assertTrue(file.exists(), "${file.absolutePath} is missing")
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals("week,demand", lines.first(), "the header the listing names")
        assertEquals(105, lines.size, "a header and 104 weeks")
        // Every row is a week number and a whole belt, in order.
        val rows = lines.drop(1).map { it.split(",") }
        assertEquals((1..104).toList(), rows.map { it[0].toInt() })
        assertEquals(history.toList(), rows.map { it[1].toInt() })
        assertTrue(history.all { it >= 0 }, "demand is a count")
        // And reading it twice gives the same thing, which a generator on a
        // shared random number stream would not.
        assertEquals(history.toList(), FinalBuy.weeklyHistory().toList())
    }

    @Test
    fun `Section 7 point 3, what PMFModeler returns`() {
        val results = DemandFitting.fitAll(history)
        val byType = results.filter { it.success }
            .associateBy { it.parameters!!.rvType.toString() }
        // The negative binomial, which is the family the ratio left standing.
        val nb = byType["NegativeBinomial"]!!.parameters!!
        assertEquals(0.054222, nb.doubleParameter("probOfSuccess"), 5e-7)
        assertEquals(1.905702, nb.doubleParameter("numSuccesses"), 5e-7)
        // The Poisson estimate exists, and is only ever the sample mean.
        assertEquals(weeklyMean, byType["Poisson"]!!.parameters!!.doubleParameter("mean"), 1e-9)
        // The binomial estimator that matches moments refuses, and the message
        // the chapter quotes is the reason.
        val refused = results.filter { !it.success }
        assertEquals(1, refused.size, "exactly one estimator refuses this data")
        assertTrue(refused.single().message!!.contains("Cannot match moments"),
            "the refusal message the chapter prints: ${refused.single().message}")
    }

    @Test
    fun `Equation 7 point 6, the method of moments agrees with the KSL`() {
        val (p, r) = DemandFitting.momentEstimates(weeklyMean, weeklyVariance)
        assertEquals(0.054222, p, 5e-7)
        assertEquals(1.9057, r, 5e-5)
        // The section's claim that the fit restates the characterization: 1/p is
        // the variance to mean ratio, exactly and not approximately.
        assertEquals(weeklyVariance / weeklyMean, 1.0 / p, 1e-9)
        // And the fitted moments return the moments fitted from, which is the
        // check that catches the other convention for p.
        val fitted = NegativeBinomial(p, r)
        assertEquals(weeklyMean, fitted.mean(), 1e-9)
        assertEquals(weeklyVariance, fitted.variance(), 1e-9)
    }

    @Test
    fun `Table 7 point 4, the goodness of fit test`() {
        val (p, r) = DemandFitting.momentEstimates(weeklyMean, weeklyVariance)
        val nb = DemandFitting.goodnessOfFit(history, NegativeBinomial(p, r))
        assertEquals(23.35, nb.chiSquaredTestStatistic, 5e-3)
        assertEquals(17, nb.chiSquaredTestDOF)
        assertEquals(0.1382, nb.chiSquaredPValue, 5e-5)
        assertTrue(nb.chiSquaredPValue >= 0.05, "the negative binomial is not rejected")

        val po = DemandFitting.goodnessOfFit(history, Poisson(weeklyMean))
        assertEquals(571.98, po.chiSquaredTestStatistic, 5e-3)
        assertEquals(16, po.chiSquaredTestDOF)
        assertTrue(po.chiSquaredPValue < 1e-6, "the Poisson is rejected")
        // The chapter's claim that the Poisson is not rejected narrowly.
        assertTrue(po.chiSquaredTestStatistic > 20 * nb.chiSquaredTestStatistic)
    }

    @Test
    fun `Table 7 point 1 and 7 point 2, the sketch and the marginal analysis`() {
        val v = FinalBuy.coarseOutcomes
        val p = FinalBuy.coarseProbabilities
        assertEquals(1.0, p.sum(), 1e-12)
        assertEquals(485.0, v.indices.sumOf { v[it] * p[it] }, 1e-12, "the sketch mean")
        var cum = 0.0
        val cdf = p.map { cum += it; cum }
        assertEquals(listOf(0.15, 0.40, 0.70, 0.90, 1.00), cdf.map { Math.round(it * 100.0) / 100.0 })

        // The four steps of @tbl-newsvendor-marginal, gain minus loss per hundred belts.
        val want = listOf(3255.0, 1180.0, -1310.0, -2970.0)
        for (i in 0 until v.size - 1) {
            val ge = v.indices.filter { v[it] >= v[i + 1] }.sumOf { p[it] }
            val net = cu * ge * 100.0 - co * cdf[i] * 100.0
            assertEquals(want[i], net, 1e-9, "step ${v[i]} to ${v[i + 1]}")
        }
        // The sign changes once, which is what makes stopping correct.
        assertEquals(1, (0 until want.size - 1).count { want[it] > 0 && want[it + 1] < 0 })

        // @tbl-newsvendor-profit, expected profit at each candidate, and its peak.
        val profits = v.map { q ->
            v.indices.sumOf { j -> p[j] * (s * minOf(v[j], q) + u * maxOf(0, q - v[j]) - c * q) }
        }
        assertEquals(listOf(13500.0, 16755.0, 17935.0, 16625.0, 13655.0),
            profits.map { Math.round(it * 100.0) / 100.0 })
        assertEquals(500, v[profits.indices.maxByOrNull { profits[it] }!!], "the best candidate")
        // The stopping rule of @eq-newsvendor-stoprule agrees with both.
        assertEquals(500, v.indices.first { cdf[it] >= ratio }.let { v[it] })
    }

    @Test
    fun `Example 7 point 2, the final buy solved`() {
        val weeks = FinalBuy.weeksRemaining.toDouble()
        val mu = weeks * weeklyMean
        val variance = weeks * weeklyVariance
        val sd = sqrt(variance)
        assertEquals(465.3654, mu, 5e-5)
        assertEquals(8582.5424, variance, 5e-5)
        assertEquals(92.6420, sd, 5e-5)
        assertEquals(0.1991, sd / mu, 5e-5)
        // One belt is about 1% of the deviation, which is the chapter's licence
        // to treat a count as continuous here and its refusal to do so weekly.
        assertEquals(1.08, 100.0 / sd, 5e-3)
        assertEquals(4.04, 100.0 / sqrt(weeklyVariance), 5e-3)

        val z = Normal(0.0, 1.0).invCDF(ratio)
        assertEquals(0.1059, z, 5e-5)
        val q = mu + z * sd
        assertEquals(475.1761, q, 5e-5)

        val demand = Normal(mu, variance)
        val shortage = demand.firstOrderLossFunction(q)
        val leftover = q - mu + shortage
        assertEquals(32.2605, shortage, 5e-5, "expected shortage")
        assertEquals(42.0712, leftover, 5e-5, "expected leftover")

        val cost = co * leftover + cu * shortage
        assertEquals(3050.4290, cost, 5e-5)
        // The chapter's arithmetic check, the other form of @eq-newsvendor-leftover.
        assertEquals(cost, (cu + co) * shortage + co * (q - mu), 1e-9)
        assertEquals(17891.0133, (s - c) * mu - cost, 5e-5, "expected profit")
        assertEquals(23758.80, c * q, 5e-3, "cash committed")

        // The optimum is flat: ordering the mean in whole belts costs 0.6% more.
        val atMean = (cu + co) * demand.firstOrderLossFunction(465.0) + co * (465.0 - mu)
        assertEquals(3068.8844, atMean, 5e-5)
        assertEquals(0.6050, 100.0 * (atMean / cost - 1.0), 5e-4)
        // And 700 belts costs nearly three times the optimum.
        val at700 = (cu + co) * demand.firstOrderLossFunction(700.0) + co * (700.0 - mu)
        assertEquals(8930.03, at700, 5e-3)
        assertTrue(at700 / cost > 2.9)

        // The normal's defect is real and did not bite here, and would have weekly.
        assertTrue(demand.cdf(0.0) < 1e-6, "essentially nothing below zero over the horizon")
        assertEquals(0.0897, Normal(weeklyMean, weeklyVariance).cdf(0.0), 5e-5,
            "about 9% below zero on the weekly data")
    }

    @Test
    fun `Table 7 point 5, what the choice of family costs`() {
        val weeks = FinalBuy.weeksRemaining
        val mu = weeks * weeklyMean
        val variance = weeks * weeklyVariance
        val horizon = DemandFitting.fittedHorizon()

        // The aggregation rule: the same p, the numbers of successes added, and
        // therefore the same two moments the continuous families were given.
        assertEquals(26.6798, horizon.numSuccesses, 5e-5)
        assertEquals(mu, horizon.mean(), 1e-9)
        assertEquals(variance, horizon.variance(), 1e-9)

        // The exact answer, and the staircase either side of it.
        val qExact = (0..2000).first { horizon.cdf(it.toDouble()) >= ratio }
        assertEquals(469, qExact)
        assertEquals(0.5392, horizon.cdf(468.0), 5e-5, "short of the ratio")
        assertEquals(0.5434, horizon.cdf(469.0), 5e-5, "and over it")

        val normal = (mu + Normal(0.0, 1.0).invCDF(ratio) * sqrt(variance)).roundToInt()
        val lognormal = Lognormal(mu, variance).invCDF(ratio).roundToInt()
        val gamma = Gamma(mu * mu / variance, variance / mu).invCDF(ratio).roundToInt()
        assertEquals(475, normal)
        assertEquals(466, lognormal)
        assertEquals(469, gamma)

        fun exactCost(q: Int) =
            (cu + co) * horizon.firstOrderLossFunction(q.toDouble()) + co * (q - horizon.mean())
        assertEquals(3055.3834, exactCost(qExact), 5e-5)
        assertEquals(3061.2777, exactCost(normal), 5e-5)
        assertEquals(3057.1934, exactCost(lognormal), 5e-5)
        assertEquals(3055.3834, exactCost(gamma), 5e-5)
        // The chapter's claim, as a bound rather than as four numbers.
        for (q in listOf(normal, lognormal, gamma)) {
            assertTrue(exactCost(q) / exactCost(qExact) - 1.0 < 0.002,
                "choosing family cost more than a fifth of a percent at $q")
        }
        // And the exact answer is the cheapest of the four, which is what makes
        // it the answer rather than one more candidate.
        assertEquals(qExact, listOf(qExact, normal, lognormal, gamma).minByOrNull { exactCost(it) })
    }

    @Test
    fun `The KSL discrete first order loss functions are exact at any argument from R1_7`() {
        // The warning in the discrete newsvendor section. Before R1.7 the KSL
        // returned a negative first order loss between whole units; R1.7 evaluates
        // the definition at any real argument. This test pins that, so that the
        // warning's claim about first order cannot go stale in either direction.
        // The second order loss function is still an approximation between whole
        // units, which is what the warning now says, and is not pinned here
        // because its fractional-argument definition is convention-dependent.
        val horizon = DemandFitting.fittedHorizon()
        fun direct(x: Double): Double {
            var t = 0.0
            for (k in 0..4000) { val e = k - x; if (e > 0.0) t += e * horizon.pmf(k) }
            return t
        }
        for (x in listOf(400.0, 469.0, 469.5, 475.0, 475.1761, 500.0)) {
            assertEquals(direct(x), horizon.firstOrderLossFunction(x), 1e-9, "exact at $x belts")
        }
        val poisson = Poisson(6.0)
        fun directPoisson(x: Double): Double {
            var t = 0.0
            for (k in 0..400) { val e = k - x; if (e > 0.0) t += e * poisson.pmf(k) }
            return t
        }
        for (x in listOf(7.0, 7.5, 8.0)) {
            assertEquals(directPoisson(x), poisson.firstOrderLossFunction(x), 1e-9, "exact at $x")
        }
    }

    @Test
    fun `Table 7 point 4, the spare transformer`() {
        val lambda = 6.0
        val tco = 3800.0 - 900.0
        val tcu = 9500.0 - 3800.0
        val tRatio = tcu / (tcu + tco)
        assertEquals(2900.0, tco, 1e-12)
        assertEquals(5700.0, tcu, 1e-12)
        assertEquals(0.662791, tRatio, 5e-7)

        val poisson = Poisson(lambda)
        val wantF = listOf(0.2851, 0.4457, 0.6063, 0.7440, 0.8472)
        val wantG1 = listOf(2.2330, 1.5181, 0.9637, 0.5700, 0.3140)
        val wantCost = listOf(13404.0, 10155.0, 8288.0, 7802.0, 8501.0)
        for ((i, q) in (4..8).withIndex()) {
            val g1 = poisson.firstOrderLossFunction(q.toDouble())
            assertEquals(wantF[i], poisson.cdf(q.toDouble()), 5e-5, "F($q)")
            assertEquals(wantG1[i], g1, 5e-5, "G1($q)")
            assertEquals(wantCost[i], tco * (q - lambda + g1) + tcu * g1, 0.5, "cost at $q")
        }
        assertEquals(7, (0..50).first { poisson.cdf(it.toDouble()) >= tRatio }, "Q*")

        // The normal approximation agrees here.
        val approx = lambda + Normal(0.0, 1.0).invCDF(tRatio) * sqrt(lambda)
        assertEquals(7.029, approx, 5e-4)
        assertEquals(7, approx.roundToInt())

        // And disagrees at an emergency price of 8,150, which is the chapter's point.
        val ratio2 = (8150.0 - 3800.0) / ((8150.0 - 3800.0) + tco)
        assertEquals(0.6000, ratio2, 5e-5)
        assertEquals(6, (0..50).first { poisson.cdf(it.toDouble()) >= ratio2 }, "the exact answer")
        val approx2 = lambda + Normal(0.0, 1.0).invCDF(ratio2) * sqrt(lambda)
        assertEquals(6.621, approx2, 5e-4)
        assertEquals(7, approx2.roundToInt(), "the approximation buys one too many")
    }

    @Test
    fun `Section 7 point 9, the extensions`() {
        val mu = FinalBuy.weeksRemaining * weeklyMean
        val sd = sqrt(FinalBuy.weeksRemaining * weeklyVariance)
        val withPenalty = cu + 40.0
        val ratioWithPenalty = withPenalty / (withPenalty + co)
        assertEquals(85.0, withPenalty, 1e-12)
        assertEquals(0.691057, ratioWithPenalty, 5e-7)
        assertEquals(511.5797, mu + Normal(0.0, 1.0).invCDF(ratioWithPenalty) * sd, 5e-5)

        val orderUpTo = mu + Normal(0.0, 1.0).invCDF(ratio) * sd
        assertEquals(355.1761, maxOf(0.0, orderUpTo - 120.0), 5e-5, "with 120 on hand")
        assertEquals(0.0, maxOf(0.0, orderUpTo - 600.0), 1e-12, "with 600 on hand")
    }

    @Test
    fun `Table 7 point 6, the simulation brackets the closed form in every column`() {
        val horizon = DemandFitting.fittedHorizon()
        val rv = NegativeBinomialRV(horizon.probOfSuccess, horizon.numSuccesses, streamNum = 1)
        val wantAverage = mapOf(440 to 17737.18, 460 to 17877.57, 469 to 17893.03,
            490 to 17818.04, 510 to 17610.98)
        val wantHalfWidth = mapOf(440 to 21.02, 460 to 24.68, 469 to 26.31,
            490 to 29.95, 510 to 33.15)
        for (q in listOf(440, 460, 469, 490, 510)) {
            rv.resetStartStream()
            val stat = Statistic("profit at $q")
            repeat(100_000) {
                val d = rv.value
                stat.collect(s * minOf(d, q.toDouble()) + u * maxOf(0.0, q - d) - c * q)
            }
            assertEquals(wantAverage[q]!!, stat.average, 0.01, "the estimate at $q")
            assertEquals(wantHalfWidth[q]!!, stat.halfWidth, 0.01, "its half-width at $q")
            val exact = (s - c) * horizon.mean() -
                ((cu + co) * horizon.firstOrderLossFunction(q.toDouble()) +
                    co * (q - horizon.mean()))
            assertTrue(stat.confidenceInterval.contains(exact),
                "the closed form $exact is outside ${stat.confidenceInterval} at $q")
        }
    }
}
