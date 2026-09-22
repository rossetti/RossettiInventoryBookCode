package inventory.newsvendor

import ksl.utilities.distributions.Binomial
import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.Geometric
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.Poisson
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parity between the loss function tables of the notation appendix and the KSL.
 *
 * The appendix prints closed forms and a table of standard normal values. Those
 * are of no use if the library the book tells a reader to call disagrees with
 * them, so this file computes each one two ways: from the appendix's formula,
 * written out here, and from the KSL's own `firstOrderLossFunction` and
 * `secondOrderLossFunction`. Every expected value below was additionally checked
 * against brute-force summation or quadrature before it was written down, so a
 * failure here means the appendix and the library have parted company rather
 * than that both were copied from the same wrong source.
 *
 * The distributions covered are the ones the appendix tabulates and that the KSL
 * computes correctly today. `Exponential` and `DEmpiricalCDF` also implement the
 * interface and are deliberately absent: their loss functions do not currently
 * satisfy the identities of the appendix's own check, and pinning what they
 * return now would enshrine it.
 */
class LossFunctionParityTest {

    private val tol = 1e-6

    private fun phi(z: Double) = exp(-0.5 * z * z) / sqrt(2.0 * Math.PI)

    @Test
    fun `the standard normal table of the appendix`() {
        // z to phi(z), the complement, and the two loss functions.
        val want = listOf(
            Row(-2.00, 0.0540, 0.9772, 2.0085, 2.4971),
            Row(-1.00, 0.2420, 0.8413, 1.0833, 0.9623),
            Row(0.00, 0.3989, 0.5000, 0.3989, 0.2500),
            Row(0.50, 0.3521, 0.3085, 0.1978, 0.1048),
            Row(1.00, 0.2420, 0.1587, 0.0833, 0.0377),
            Row(1.28, 0.1758, 0.1003, 0.0475, 0.0197),
            Row(1.50, 0.1295, 0.0668, 0.0293, 0.0114),
            Row(1.645, 0.1031, 0.0500, 0.0209, 0.0078),
            Row(2.00, 0.0540, 0.0228, 0.0085, 0.0029),
            Row(2.33, 0.0264, 0.0099, 0.0034, 0.0010),
            Row(3.00, 0.0044, 0.0013, 0.0004, 0.0001),
        )
        val standard = Normal(0.0, 1.0)
        for (row in want) {
            assertEquals(row.density, phi(row.z), 5e-5, "phi at ${row.z}")
            assertEquals(row.complement, standard.complementaryCDF(row.z), 5e-5, "Phi0 at ${row.z}")
            assertEquals(row.first, standard.firstOrderLossFunction(row.z), 5e-5, "Phi1 at ${row.z}")
            assertEquals(row.second, standard.secondOrderLossFunction(row.z), 5e-5, "Phi2 at ${row.z}")
        }
    }

    @Test
    fun `the worked normal example, both ways`() {
        val ltd = Normal(50.0, 144.0)
        assertEquals(0.158655, ltd.complementaryCDF(62.0), tol)
        assertEquals(0.999786, ltd.firstOrderLossFunction(62.0), tol)
        assertEquals(5.424464, ltd.secondOrderLossFunction(62.0), tol)
        // The appendix's second route: G1(b) = H1(b) - b G0(b), with
        // H1(b) = mu Phi0(z) + sigma phi(z) for the normal.
        val z = (62.0 - 50.0) / 12.0
        val h1 = 50.0 * ltd.complementaryCDF(62.0) + 12.0 * phi(z)
        assertEquals(ltd.firstOrderLossFunction(62.0), h1 - 62.0 * ltd.complementaryCDF(62.0), tol)
    }

    @Test
    fun `Poisson matches the closed form of the appendix`() {
        val lambda = 4.0
        val d = Poisson(lambda)
        for (b in listOf(2.0, 4.0, 6.0, 8.0)) {
            val g0 = d.complementaryCDF(b)
            val g = d.pmf(b)
            assertEquals(-(b - lambda) * g0 + lambda * g, d.firstOrderLossFunction(b), tol, "G1 at $b")
            assertEquals(0.5 * (((b - lambda) * (b - lambda) + b) * g0 - lambda * (b - lambda) * g),
                d.secondOrderLossFunction(b), tol, "G2 at $b")
        }
        assertEquals(lambda, d.firstOrderLossFunction(0.0), tol, "G1(0) is the mean")
    }

    @Test
    fun `Geometric matches the closed form of the appendix`() {
        val p = 0.3
        val beta = (1.0 - p) / p
        val d = Geometric(p)
        for (b in listOf(0.0, 1.0, 2.0, 5.0, 8.0)) {
            assertEquals(beta * Math.pow(1.0 - p, b), d.firstOrderLossFunction(b), tol, "G1 at $b")
            assertEquals(beta * beta * Math.pow(1.0 - p, b), d.secondOrderLossFunction(b), tol, "G2 at $b")
        }
    }

    @Test
    fun `NegativeBinomial matches the closed form of the appendix`() {
        val p = 0.4
        val r = 3.0
        val beta = (1.0 - p) / p
        val d = NegativeBinomial(p, r)
        for (b in listOf(0.0, 1.0, 2.0, 4.0, 6.0, 10.0)) {
            val g0 = d.complementaryCDF(b)
            val g = d.pmf(b)
            assertEquals(-(b - r * beta) * g0 + (b + r) * beta * g,
                d.firstOrderLossFunction(b), tol, "G1 at $b")
            assertEquals(0.5 * ((r * (r + 1) * beta * beta - 2 * r * beta * b + b * (b + 1)) * g0
                + ((r + 1) * beta - b) * (b + r) * beta * g),
                d.secondOrderLossFunction(b), tol, "G2 at $b")
        }
        assertEquals(r * beta, d.firstOrderLossFunction(0.0), tol, "G1(0) is the mean")
    }

    @Test
    fun `Binomial agrees with the direct sums`() {
        val n = 10
        val p = 0.35
        val d = Binomial(p, n)
        for (b in listOf(0.0, 2.0, 4.0, 6.0)) {
            var first = 0.0
            var second = 0.0
            for (x in 0..n) {
                if (x > b) {
                    first += (x - b) * d.pmf(x.toDouble())
                    second += (x - b) * (x - b - 1) * d.pmf(x.toDouble())
                }
            }
            assertEquals(first, d.firstOrderLossFunction(b), tol, "G1 at $b")
            assertEquals(0.5 * second, d.secondOrderLossFunction(b), tol, "G2 at $b")
        }
        assertEquals(n * p, d.firstOrderLossFunction(0.0), tol, "G1(0) is the mean")
    }

    @Test
    fun `Gamma matches the closed form of the appendix`() {
        val shape = 2.7
        val scale = 3.0
        val d = Gamma(shape, scale)
        for (b in listOf(2.0, 5.0, 8.1, 15.0, 25.0)) {
            val g0 = d.complementaryCDF(b)
            val g = d.pdf(b)
            assertEquals(scale * ((shape - b / scale) * g0 + b * g),
                d.firstOrderLossFunction(b), 1e-5, "G1 at $b")
            assertEquals(0.5 * scale * scale * (((shape - b / scale) * (shape - b / scale) + shape) * g0
                + (shape - b / scale + 1.0) * b * g),
                d.secondOrderLossFunction(b), 1e-5, "G2 at $b")
        }
    }

    @Test
    fun `Lognormal matches the appendix, which corrects its source`() {
        // Parameterized by the mean and variance of X itself.
        val mean = 60.0
        val variance = 900.0
        val d = Lognormal(mean, variance)
        val s2 = Math.log(variance / (mean * mean) + 1.0)
        val s = sqrt(s2)
        val mu = Math.log(mean) - s2 / 2.0
        val standard = Normal(0.0, 1.0)
        for (b in listOf(10.0, 30.0, 60.0, 90.0, 150.0)) {
            val z = (Math.log(b) - mu) / s
            val g0 = standard.complementaryCDF(z)
            assertEquals(mean * standard.cdf(s - z) - b * g0,
                d.firstOrderLossFunction(b), 1e-5, "G1 at $b")
            // The full second order form. The source document prints only the
            // H2 term, which is too large by roughly a factor of three near the
            // mean, so this assertion is what keeps the appendix's correction.
            val full = 0.5 * ((variance + mean * mean) * standard.cdf(2.0 * s - z)
                - 2.0 * b * mean * standard.cdf(s - z) + b * b * g0)
            assertEquals(full, d.secondOrderLossFunction(b), 1e-5, "G2 at $b")
            assertTrue(d.secondOrderLossFunction(b) >= 0.0, "G2 is non-negative at $b")
        }
    }

    private data class Row(
        val z: Double, val density: Double, val complement: Double,
        val first: Double, val second: Double,
    )
}
