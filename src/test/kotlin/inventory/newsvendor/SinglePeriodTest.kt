package inventory.newsvendor

import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.Poisson
import ksl.utilities.random.rvariable.NegativeBinomialRV
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The single-period classes against the figures @sec-newsvendor prints. */
class SinglePeriodTest {
    private val belt = SinglePeriodCosts(price = 95.0, cost = 50.0, salvage = 12.0)
    private val horizon = DemandFitting.fittedHorizon()
    private val normal = Normal(horizon.mean(), horizon.variance())
    private val transformer = SinglePeriodCosts(price = 9500.0, cost = 3800.0, salvage = 900.0)

    private fun near(want: Double, got: Double, tol: Double = 0.005) =
        assertTrue(abs(want - got) <= tol, "expected $want, got $got")

    @Test
    fun `the mapping and the ratio`() {
        assertEquals(38.0, belt.overageCost)
        assertEquals(45.0, belt.underageCost)
        near(0.542169, belt.criticalRatio, 5e-7)
        near(85.0 / 123.0, belt.copy(penalty = 40.0).criticalRatio, 1e-12)
    }

    @Test
    fun `the final buy against the normal, exm-newsvendor-normal`() {
        val s = solve(belt, normal)
        near(475.18, s.level)
        near(32.26, s.expectedShortage)
        near(42.07, s.expectedLeftover)
        near(3050.43, s.expectedCost)
        near(17891.01, s.expectedProfit)
        near(355.18, solve(belt, normal, onHand = 120.0).orderQuantity)
        assertEquals(0.0, solve(belt, normal, onHand = 600.0).orderQuantity)
        near(511.58, optimalLevel(belt.copy(penalty = 40.0), normal))
    }

    @Test
    fun `the families, tbl-newsvendor-families`() {
        val chosen = listOf(
            normal, Lognormal(horizon.mean(), horizon.variance()),
            Gamma(horizon.mean() * horizon.mean() / horizon.variance(), horizon.variance() / horizon.mean()), horizon,
        ).map { optimalLevel(belt, it).roundToInt() }
        assertEquals(listOf(475, 466, 469, 469), chosen)
        val costs = chosen.map { evaluate(belt, horizon, it.toDouble()).expectedCost }
        listOf(3061.28, 3057.19, 3055.38, 3055.38).zip(costs).forEach { (w, g) -> near(w, g) }
    }

    @Test
    fun `the transformer, tbl-newsvendor-poisson`() {
        val want = listOf(13404.0, 10155.0, 8288.0, 7802.0, 8501.0)
        for ((i, q) in (4..8).withIndex()) near(want[i], evaluate(transformer, Poisson(6.0), q.toDouble()).expectedCost, 0.5)
        assertEquals(7.0, solve(transformer, Poisson(6.0)).level)
        assertEquals(6.0, optimalLevel(transformer.copy(price = 8150.0), Poisson(6.0)))
    }

    @Test
    fun `the sampled route agrees, tbl-newsvendor-sim`() {
        val demand = NegativeBinomialRV(horizon.probOfSuccess, horizon.numSuccesses, streamNum = 1)
        val printed = mapOf(440 to 17737.18, 460 to 17877.57, 469 to 17893.03, 490 to 17818.04, 510 to 17610.98)
        for ((q, p) in printed) {
            val stat = estimate(belt, demand, q.toDouble(), 100_000)
            near(p, stat.average)
            val exact = evaluate(belt, horizon, q.toDouble()).expectedProfit
            assertTrue(abs(stat.average - exact) <= stat.halfWidth, "Q = $q: $exact outside the interval")
        }
    }

    @Test
    fun `an ill-posed problem is refused, sec-newsvendor-improper`() {
        assertFailsWith<IllegalArgumentException> { belt.copy(salvage = 50.0) }
        assertFailsWith<IllegalArgumentException> { belt.copy(price = 50.0) }
    }

    @Test
    fun `the realized cost and profit differ by the margin on demand, eq-newsvendor-equivalence`() {
        val b = belt.copy(penalty = 40.0)
        for (d in listOf(0.0, 300.0, 475.0, 700.0)) near((b.price - b.cost) * d - b.cost(475.0, d), b.profit(475.0, d), 1e-9)
    }
}
