package inventory.newsvendor

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The moment matching procedures of the distributions appendix.
 *
 * The KSL does not implement these, so the appendix is their only statement and
 * this file is the only thing that checks it. Every procedure is asserted two
 * ways: against the figures the appendix prints for its worked lead time demand
 * example, and against the definition of the job, which is that the matched
 * distribution reproduces the mean and variance it was given.
 *
 * The second assertion is what earns its place. The source the appendix was
 * drawn from prints a mixed geometric whose parameters come out greater than
 * one, and the moment check is what exposed it.
 */
class MomentMatchingTest {

    // Demand of 5 units a week with variance 16, lead time 10 weeks with
    // variance 25. Equation C.19 of the appendix.
    private val mu = 50.0
    private val variance = 785.0

    @Test
    fun `the lead time demand moments of the worked example`() {
        val meanDemand = 5.0
        val varDemand = 16.0
        val meanLead = 10.0
        val varLead = 25.0
        assertEquals(mu, meanLead * meanDemand, 1e-12)
        assertEquals(variance, meanLead * varDemand + varLead * meanDemand * meanDemand, 1e-12)
        assertEquals(0.3140, variance / (mu * mu), 5e-5, "squared coefficient of variation")
        assertEquals(15.70, variance / mu, 5e-3, "variance to mean ratio")
    }

    @Test
    fun `the gamma match reproduces both moments`() {
        val scale = variance / mu
        val shape = mu * mu / variance
        assertEquals(15.70, scale, 5e-3)
        assertEquals(3.1847, shape, 5e-5)
        assertEquals(mu, shape * scale, 1e-9, "the mean it was given")
        assertEquals(variance, shape * scale * scale, 1e-9, "the variance it was given")
    }

    /** Algorithm C.1. Returns shape and rate of each component, and the weight. */
    private fun erlangMixture(mean: Double, v: Double): DoubleArray {
        val c2 = v / (mean * mean)
        return if (c2 < 1.0) {
            val k1 = floor(1.0 / c2)
            val k2 = k1 + 1.0
            // Exactly zero when c2 is the reciprocal of an integer, and very
            // slightly negative in floating point, so clamp rather than trust.
            val disc = maxOf(0.0, k2 * (1.0 + c2) - k2 * k2 * c2)
            // At c2 = 1/k the discriminant is zero and this evaluates to one,
            // or a shade above it in floating point, so clamp it too.
            val p1 = minOf(1.0, (k2 * c2 - sqrt(disc)) / (1.0 + c2))
            val lambda = (k2 - p1) / mean
            doubleArrayOf(k1, lambda, k2, lambda, p1)
        } else {
            val lambda1 = (2.0 / mean) * (1.0 + sqrt((c2 - 0.5) / (c2 + 1.0)))
            val lambda2 = 4.0 / mean - lambda1
            val p1 = lambda1 * (lambda2 * mean - 1.0) / (lambda2 - lambda1)
            doubleArrayOf(1.0, lambda1, 1.0, lambda2, p1)
        }
    }

    /** Mean and second raw moment of an Erlang with the given shape and rate. */
    private fun erlangMoments(k: Double, lambda: Double): Pair<Double, Double> {
        val m = k / lambda
        return m to (k / (lambda * lambda) + m * m)
    }

    private fun mixtureMoments(
        first: Pair<Double, Double>, second: Pair<Double, Double>, weight: Double,
    ): Pair<Double, Double> {
        val m = weight * first.first + (1.0 - weight) * second.first
        val raw = weight * first.second + (1.0 - weight) * second.second
        return m to (raw - m * m)
    }

    @Test
    fun `the Erlang mixture matches the worked example and its own targets`() {
        val (k1, l1, k2, l2, p1) = erlangMixture(mu, variance).let {
            Quint(it[0], it[1], it[2], it[3], it[4])
        }
        assertEquals(3.0, k1, 1e-12)
        assertEquals(4.0, k2, 1e-12)
        assertEquals(0.5893, p1, 5e-5)
        assertEquals(0.06821, l1, 5e-6)
        assertEquals(l1, l2, 1e-12, "the low branch gives both components one rate")
        val (m, v) = mixtureMoments(erlangMoments(k1, l1), erlangMoments(k2, l2), p1)
        assertEquals(mu, m, 1e-9)
        assertEquals(variance, v, 1e-7)
    }

    @Test
    fun `the Erlang mixture matches its targets across both branches`() {
        val targets = listOf(
            50.0 to 785.0, 10.0 to 20.0, 100.0 to 400.0, 12.0 to 18.0,   // c2 < 1
            30.0 to 900.0, 5.0 to 50.0, 20.0 to 1200.0,                  // c2 >= 1
        )
        for ((target, targetVar) in targets) {
            val r = erlangMixture(target, targetVar)
            val (m, v) = mixtureMoments(
                erlangMoments(r[0], r[1]), erlangMoments(r[2], r[3]), r[4])
            assertEquals(target, m, 1e-9, "mean for ($target, $targetVar)")
            assertEquals(targetVar, v, 1e-6, "variance for ($target, $targetVar)")
            assertTrue(r[4] in 0.0..1.0, "the weight is a probability for ($target, $targetVar)")
        }
    }

    // Adan's rule uses p as the probability of a FAILURE, the opposite of the
    // convention in Equation C.14, so these means are p/(1-p) and not (1-p)/p.
    private fun geometricMoments(p: Double): Pair<Double, Double> {
        val m = p / (1.0 - p)
        return m to (p / ((1.0 - p) * (1.0 - p)) + m * m)
    }

    private fun negBinomialMoments(r: Double, p: Double): Pair<Double, Double> {
        val m = r * p / (1.0 - p)
        return m to (r * p / ((1.0 - p) * (1.0 - p)) + m * m)
    }

    private fun binomialMoments(n: Double, p: Double): Pair<Double, Double> {
        val m = n * p
        return m to (n * p * (1.0 - p) + m * m)
    }

    /** Returns the matched mean and variance, and the family that was selected. */
    private fun adan(mean: Double, v: Double): Triple<String, Double, Double> {
        val a = v / (mean * mean) - 1.0 / mean
        return when {
            a < -1.0 -> Triple("Gamma", mean, v)
            kotlin.math.abs(a) < 1e-12 -> Triple("Poisson", mean, mean)
            a < 0.0 -> {
                val n = floor(-1.0 / a)
                val q = (1.0 + a * (n + 1.0) + sqrt(maxOf(0.0, -a * n * (n + 1.0) - n))) / (1.0 + a)
                val p = mean / (n + 1.0 - q)
                val (m, vv) = mixtureMoments(binomialMoments(n, p), binomialMoments(n + 1.0, p), q)
                Triple("MixBin", m, vv)
            }
            a < 1.0 -> {
                val r = floor(1.0 / a)
                val q = (a * (r + 1.0) - sqrt(maxOf(0.0, (r + 1.0) * (1.0 - a * r)))) / (1.0 + a)
                val p = mean / (r + 1.0 - q + mean)
                val (m, vv) = mixtureMoments(
                    negBinomialMoments(r, p), negBinomialMoments(r + 1.0, p), q)
                Triple("MixNB", m, vv)
            }
            else -> {
                val q = 1.0 / (1.0 + a + sqrt(maxOf(0.0, a * a - 1.0)))
                // Equation C.24 as the appendix prints it. The source document
                // has mu/(2 + mu q), which returns values above one.
                val p1 = mean / (2.0 * q + mean)
                val p2 = mean / (2.0 * (1.0 - q) + mean)
                val (m, vv) = mixtureMoments(geometricMoments(p1), geometricMoments(p2), q)
                Triple("MixGeom", m, vv)
            }
        }
    }

    @Test
    fun `Adan's rule on the worked example selects the mixed negative binomial`() {
        val a = variance / (mu * mu) - 1.0 / mu
        assertEquals(0.2940, a, 5e-5)
        val r = floor(1.0 / a)
        val q = (a * (r + 1.0) - sqrt((r + 1.0) * (1.0 - a * r))) / (1.0 + a)
        val p = mu / (r + 1.0 - q + mu)
        assertEquals(3.0, r, 1e-12)
        assertEquals(0.37788, q, 5e-6)
        assertEquals(0.93245, p, 5e-6)
        val (family, m, v) = adan(mu, variance)
        assertEquals("MixNB", family)
        assertEquals(mu, m, 1e-9)
        assertEquals(variance, v, 1e-7)
    }

    @Test
    fun `Adan's rule reproduces its targets over the whole range of a`() {
        // A spread wide enough to reach every branch of Table C.3.
        var cases = 0
        var seed = 20260913L
        fun next(): Double {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 11).toDouble() / (1L shl 53).toDouble())
        }
        val seen = mutableSetOf<String>()
        repeat(3000) {
            val m = 0.3 + next() * 79.7
            val v = m * (0.05 + next() * 39.95)
            val (family, got, gotVar) = adan(m, v)
            seen.add(family)
            if (family == "Gamma" || family == "Poisson") return@repeat
            cases++
            assertEquals(m, got, 1e-7 * maxOf(1.0, m), "$family mean for ($m, $v)")
            assertEquals(v, gotVar, 1e-6 * maxOf(1.0, v), "$family variance for ($m, $v)")
        }
        assertTrue(cases > 1000, "expected many mixture cases, got $cases")
        assertTrue("MixNB" in seen && "MixGeom" in seen && "MixBin" in seen,
            "the sweep should reach every mixture branch, reached $seen")
    }

    @Test
    fun `the normal puts this much mass below zero`() {
        // Table C.2 of the appendix.
        val want = listOf(0.2 to 0.0000, 0.4 to 0.0062, 0.6 to 0.0478, 1.0 to 0.1587, 1.5 to 0.2525)
        for ((cv, p) in want) {
            assertEquals(p, standardNormalCdf(-1.0 / cv), 5e-5, "P(X < 0) at cv $cv")
        }
    }

    private fun standardNormalCdf(z: Double): Double {
        // Abramowitz and Stegun 7.1.26 is not accurate enough here; use erfc.
        return 0.5 * erfc(-z / sqrt(2.0))
    }

    private fun erfc(x: Double): Double {
        val t = 1.0 / (1.0 + 0.5 * kotlin.math.abs(x))
        val y = t * exp(
            -x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 +
                t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 +
                    t * (-0.82215223 + t * 0.17087277))))))))
        )
        return if (x >= 0.0) y else 2.0 - y
    }

    private data class Quint(
        val a: Double, val b: Double, val c: Double, val d: Double, val e: Double,
    )

    private operator fun Quint.component1() = a
    private operator fun Quint.component2() = b
    private operator fun Quint.component3() = c
    private operator fun Quint.component4() = d
    private operator fun Quint.component5() = e
}
