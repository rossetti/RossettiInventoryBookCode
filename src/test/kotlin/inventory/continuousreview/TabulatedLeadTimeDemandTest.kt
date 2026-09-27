package inventory.continuousreview

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [LeadTimeDemand.tabulated] and [LeadTimeDemand.mixture] must behave exactly as
 * the family-based lead time demands do wherever the two describe the same
 * distribution, since chapter 10 hands them to the same [RQModel].
 */
class TabulatedLeadTimeDemandTest {

    private fun close(got: Double, want: Double, tol: Double = 1e-9, what: String = "") =
        assertTrue(abs(got - want) < tol, "$what expected $want, got $got")

    private fun poissonMasses(mean: Double): DoubleArray {
        val out = ArrayList<Double>()
        var p = exp(-mean)
        var below = 0.0
        var x = 0
        while (1.0 - below > 1e-15 || x <= mean) { out.add(p); below += p; x++; p *= mean / x }
        return out.toDoubleArray()
    }

    @Test
    fun `a tabulated Poisson is the Poisson, loss functions included`() {
        val family = LeadTimeDemand.poisson(7.5)
        val table = LeadTimeDemand.tabulated(poissonMasses(7.5))
        close(table.mean, family.mean, what = "mean")
        close(table.variance, family.variance, what = "variance")
        for (x in -2..30) {
            val d = x.toDouble()
            close(table.cdf(d), family.cdf(d), what = "cdf at $x")
            close(table.lossFirst(d), family.lossFirst(d), what = "G1 at $x")
            close(table.lossSecond(d), family.lossSecond(d), what = "G2 at $x")
            close(table.lossThird(d), family.lossThird(d), 1e-8, "G3 at $x")
        }
        for (p in listOf(0.05, 0.5, 0.9, 0.99)) close(table.inverseCdf(p), family.inverseCdf(p), what = "inverse at $p")
    }

    @Test
    fun `a one-part mixture is its part`() {
        val part = LeadTimeDemand.negativeBinomial(6.0, 11.0)
        val mix = LeadTimeDemand.mixture(listOf(1.0 to part))
        close(mix.mean, part.mean, 1e-9)
        close(mix.variance, part.variance, 1e-8)
        for (x in 0..25) close(mix.lossFirst(x.toDouble()), part.lossFirst(x.toDouble()), 1e-9)
    }

    @Test
    fun `a two-part mixture obeys the law of total variance`() {
        val a = LeadTimeDemand.poisson(4.0)
        val b = LeadTimeDemand.poisson(10.0)
        val mix = LeadTimeDemand.mixture(listOf(0.4 to a, 0.6 to b))
        val mean = 0.4 * 4.0 + 0.6 * 10.0
        close(mix.mean, mean)
        // Var = E[Var | part] + Var[E | part]
        close(mix.variance, 0.4 * 4.0 + 0.6 * 10.0 + 0.4 * 0.6 * 36.0, 1e-9)
    }

    @Test
    fun `an RQModel on a tabulated Poisson prices exactly as on the Poisson`() {
        val family = RQModel(45.0, 220.0, 950.0, 8550.0, LeadTimeDemand.poisson(7.5))
        val table = family.withLeadTimeDemand(LeadTimeDemand.tabulated(poissonMasses(7.5)))
        for ((r, q) in listOf(8 to 7, 4 to 5, -1 to 3)) close(table.cost(r, q), family.cost(r, q), 1e-6, "cost at ($r, $q)")
    }
}
