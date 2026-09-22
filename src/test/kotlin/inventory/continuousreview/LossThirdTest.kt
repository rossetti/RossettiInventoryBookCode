package inventory.continuousreview

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The third order loss function of Appendix C, checked against its definition
 * rather than against itself: direct summation for the discrete families and
 * direct quadrature for the gamma.
 */
class LossThirdTest {

    private fun poissonPmf(k: Int, mean: Double): Double =
        exp(-mean + k * ln(mean) - lnFactorial(k))

    private fun lnFactorial(k: Int): Double {
        var total = 0.0
        for (i in 2..k) total += ln(i.toDouble())
        return total
    }

    /** Equation C.11 for a discrete X: (1/6) E[(X-b)+ (X-b-1)+ (X-b-2)+]. */
    private fun poissonLossThirdDirect(mean: Double, b: Double, terms: Int = 600): Double {
        var total = 0.0
        for (x in 0 until terms) {
            val a = x - b
            if (a > 0) {
                val f2 = if (a - 1.0 > 0) a - 1.0 else 0.0
                val f3 = if (a - 2.0 > 0) a - 2.0 else 0.0
                total += a * f2 * f3 * poissonPmf(x, mean)
            }
        }
        return total / 6.0
    }

    /** Simpson's rule on (1/6) integral of (u-x)^3 g(u) du, for the gamma. */
    private fun gammaLossThirdDirect(
        mean: Double, variance: Double, x: Double, n: Int = 40000,
    ): Double {
        val shape = mean * mean / variance
        val scale = variance / mean
        fun pdf(u: Double): Double =
            if (u <= 0.0) 0.0
            else exp((shape - 1.0) * ln(u) - u / scale - lnGamma(shape) - shape * ln(scale))
        val lo = if (x > 0.0) x else 0.0
        val hi = mean + 16.0 * Math.sqrt(variance)
        val h = (hi - lo) / n
        var sum = 0.0
        for (i in 0..n) {
            val u = lo + i * h
            val w = if (i == 0 || i == n) 1.0 else if (i % 2 == 1) 4.0 else 2.0
            val d = u - x
            sum += w * d * d * d * pdf(u)
        }
        return sum * h / 3.0 / 6.0
    }

    private fun lnGamma(z: Double): Double {
        val c = doubleArrayOf(
            76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5,
        )
        var y = z
        val tmp = z + 5.5 - (z + 0.5) * ln(z + 5.5)
        var ser = 1.000000000190015
        for (j in 0..5) { y += 1.0; ser += c[j] / y }
        return -tmp + ln(2.5066282746310005 * ser / z)
    }

    @Test
    fun `Poisson third order loss matches direct summation, above and below zero`() {
        val ltd = LeadTimeDemand.poisson(7.5)
        for (b in -4..20) {
            assertEquals(
                poissonLossThirdDirect(7.5, b.toDouble()), ltd.lossThird(b.toDouble()), 5e-9,
                "G3 at $b",
            )
        }
    }

    @Test
    fun `negative binomial third order loss matches direct summation`() {
        // mean 7.5, variance 21.5625, the case 3 transformer of @exm-ltd-transformer.
        val ltd = LeadTimeDemand.negativeBinomial(7.5, 21.5625)
        // Direct summation using the class's own mass function, which is KSL's.
        fun direct(b: Double): Double {
            var total = 0.0
            for (x in 0 until 2000) {
                val a = x - b
                if (a > 0) {
                    val f2 = if (a - 1.0 > 0) a - 1.0 else 0.0
                    val f3 = if (a - 2.0 > 0) a - 2.0 else 0.0
                    val pmf = ltd.cdf(x.toDouble()) - ltd.cdf(x - 1.0)
                    total += a * f2 * f3 * pmf
                }
            }
            return total / 6.0
        }
        for (b in -3..25) {
            assertEquals(direct(b.toDouble()), ltd.lossThird(b.toDouble()), 5e-7, "G3 at $b")
        }
    }

    @Test
    fun `gamma third order loss matches direct quadrature, above and below zero`() {
        val mean = 34.6154
        val variance = mean * 2.2
        val ltd = LeadTimeDemand.gamma(mean, variance)
        for (x in listOf(-5.0, -1.0, 0.0, 10.0, 25.0, 30.0, 40.0, 60.0)) {
            val want = gammaLossThirdDirect(mean, variance, x)
            assertEquals(want, ltd.lossThird(x), 5e-6 * maxOf(1.0, abs(want)), "G3 at $x")
        }
    }

    @Test
    fun `the telescoping identity of Equation C-13 holds for a discrete family`() {
        val ltd = LeadTimeDemand.poisson(7.5)
        for ((b, c) in listOf(3 to 12, 0 to 5, 9 to 14, -2 to 6)) {
            var sum = 0.0
            for (j in (b + 1)..c) sum += ltd.lossSecond(j.toDouble())
            assertEquals(
                sum, ltd.lossThird(b.toDouble()) - ltd.lossThird(c.toDouble()), 5e-9,
                "sum of G2 over ($b, $c] against the G3 difference",
            )
        }
    }

    @Test
    fun `for the gamma the identity is an integral, not a sum`() {
        // @sec-continuousreview-batch-variance's warning, made executable: on a continuous family the
        // band is an interval, so summing G2 over the integers in it is NOT the
        // average. It runs low by several percent.
        val ltd = LeadTimeDemand.gamma(34.6154, 34.6154 * 2.2)
        val r = 30
        val q = 116
        val closed = 2.0 * (ltd.lossThird(r.toDouble()) - ltd.lossThird((r + q).toDouble())) / q
        var sum = 0.0
        for (j in (r + 1)..(r + q)) sum += ltd.lossSecond(j.toDouble())
        val bySum = 2.0 * sum / q
        assertTrue(bySum < closed, "the integer sum should understate")
        val error = (bySum - closed) / closed
        assertTrue(error < -0.05, "expected the sum to run several percent low, was $error")
    }
}
