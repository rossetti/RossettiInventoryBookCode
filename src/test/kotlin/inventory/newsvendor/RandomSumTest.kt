package inventory.newsvendor

import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.PoissonRV
import ksl.utilities.statistic.Statistic
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The random sum results of the probability appendix, checked by simulation.
 *
 * Equation B.5 and Equation B.7 are derived in the appendix by conditioning, so
 * a second derivation would prove nothing about whether they are right. What
 * proves it is sampling the sum and comparing. Both worked examples the appendix
 * prints are reproduced here, and each is also checked against a confidence
 * interval so the test fails on a real disagreement rather than on sampling
 * noise.
 */
class RandomSumTest {

    /** The appendix's lead time demand example: 5 a week, variance 16, over 10 weeks, variance 25. */
    @Test
    fun `lead time demand moments, and by simulation`() {
        val meanDemand = 5.0
        val varDemand = 16.0
        val meanLead = 10.0
        val varLead = 25.0

        val mean = meanLead * meanDemand
        val variance = meanLead * varDemand + varLead * meanDemand * meanDemand
        assertEquals(50.0, mean, 1e-12)
        assertEquals(785.0, variance, 1e-12)
        assertEquals(160.0, meanLead * varDemand, 1e-12, "the demand term")
        assertEquals(625.0, varLead * meanDemand * meanDemand, 1e-12, "the lead time term")
        assertEquals(28.0179, sqrt(variance), 5e-5)
        assertEquals(0.7962, varLead * meanDemand * meanDemand / variance, 5e-5,
            "the share of the variance the supplier contributes")

        // Two-point distributions carrying exactly those moments: demand is 1 or
        // 9 with equal probability, the lead time 5 or 15 weeks.
        val demand = DEmpiricalRV(doubleArrayOf(1.0, 9.0), doubleArrayOf(0.5, 1.0), streamNum = 11)
        val lead = DEmpiricalRV(doubleArrayOf(5.0, 15.0), doubleArrayOf(0.5, 1.0), streamNum = 12)
        assertEquals(meanDemand, 0.5 * 1.0 + 0.5 * 9.0, 1e-12)
        assertEquals(varDemand, 0.5 * 1.0 + 0.5 * 81.0 - meanDemand * meanDemand, 1e-12)

        val stat = Statistic("D(L)")
        repeat(400_000) {
            val periods = lead.value.toInt()
            var total = 0.0
            repeat(periods) { total += demand.value }
            stat.collect(total)
        }
        assertWithin(mean, stat, "mean of D(L)")
        // The variance has no half-width, so allow a wider relative tolerance.
        assertEquals(variance, stat.variance, 0.02 * variance, "variance of D(L)")
    }

    /** The compound Poisson example: three occurrences, each averaging 5 with variance 16. */
    @Test
    fun `the compound Poisson variance collapses to the second moment`() {
        val rate = 3.0
        val meanSize = 5.0
        val varSize = 16.0
        val secondMoment = varSize + meanSize * meanSize
        assertEquals(41.0, secondMoment, 1e-12)

        val general = rate * varSize + rate * meanSize * meanSize
        val collapsed = rate * secondMoment
        assertEquals(123.0, general, 1e-12, "the general form of Equation B.7")
        assertEquals(general, collapsed, 1e-12, "the collapse of Equation B.9")

        val count = PoissonRV(rate, streamNum = 13)
        val size = DEmpiricalRV(doubleArrayOf(1.0, 9.0), doubleArrayOf(0.5, 1.0), streamNum = 14)
        val stat = Statistic("compound")
        repeat(400_000) {
            val n = count.value.toInt()
            var total = 0.0
            repeat(n) { total += size.value }
            stat.collect(total)
        }
        assertWithin(rate * meanSize, stat, "mean of the compound sum")
        assertEquals(general, stat.variance, 0.02 * general, "variance of the compound sum")

        // The appendix's claim about the variance to mean ratio.
        assertEquals(secondMoment / meanSize, general / (rate * meanSize), 1e-12)
        assertTrue(secondMoment / meanSize > 1.0, "batched demand is overdispersed")
    }

    private fun assertWithin(expected: Double, stat: Statistic, what: String) {
        val interval = stat.confidenceInterval
        assertTrue(interval.contains(expected),
            "$what: $expected is outside $interval after ${stat.count.toInt()} samples")
    }
}
