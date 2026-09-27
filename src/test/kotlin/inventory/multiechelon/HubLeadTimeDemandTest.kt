package inventory.multiechelon

import inventory.continuousreview.LeadTimeDemand
import inventory.multiechelon.StoreroomWithDelay.Route
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @sec-batchedmultiechelon-hubgeneral: the hub's lead time demand when a
 * storeroom batches and other demand reaches the hub, the two-moment recipe, the
 * shortcut to warn against, and the running example built on them.
 *
 * The example: the cutout hub runs `(r_0, Q_0) = (12, 8)` with a three-week lead
 * time and serves two units a week of other demand and one storeroom whose
 * customers take two units a week, ordered three at a time with `r_j = 3` and a
 * week's transit. Costs per week are the cutout's: `h = 28.75/52` and
 * `b = 287.50/52`, the latter on every unit owed to a customer.
 */
class HubLeadTimeDemandTest {

    private fun close(got: Double, want: Double, tol: Double = 1e-9, what: String = "") =
        assertTrue(abs(got - want) < tol, "$what expected $want, got $got")

    private val h = 28.75 / 52
    private val b = 287.50 / 52
    private val costs = RQCosts(0.0, h, b)

    private fun hub(r0: Int = 12) = BatchedHub(2.0, listOf(StoreStream(2.0, 3)), r0, 8, 3.0, costs)

    private fun moments(pmf: DoubleArray): Pair<Double, Double> {
        val m = pmf.withIndex().sumOf { (x, p) -> x * p }
        return m to pmf.withIndex().sumOf { (x, p) -> (x - m) * (x - m) * p }
    }

    @Test
    fun `the convolution sums to one, and its mean and variance are the streams' sums`() {
        for (ell in listOf(1.0, 2.0, 3.0, 5.0)) {
            val total = hub().demandPmf(ell)
            close(total.sum(), 1.0, 1e-12, "mass at $ell")
            val (m, v) = moments(total)
            val parts = hub().streamPmfs(ell).map { moments(it) }
            close(m, parts.sumOf { it.first }, 1e-9, "mean at $ell")
            close(m, 4.0 * ell, 1e-9, "mean is lambda_0 l at $ell")
            close(v, parts.sumOf { it.second }, 1e-8, "variance at $ell")
        }
    }

    @Test
    fun `storerooms that order one at a time leave the hub a Poisson location`() {
        val poissonHub = BatchedHub(0.0, List(4) { StoreStream(1.0, 1) }, 9, 8, 3.0, costs)
        val viaStreams = poissonHub.streamPmfs(3.0).reduce { a, c -> convolve(a, c) }
        val poisson = poissonPmf(12.0)
        for (x in poisson.indices) close(viaStreams.getOrElse(x) { 0.0 }, poisson[x], 1e-12, "x = $x")
    }

    @Test
    fun `the recipe's variance is off by a bounded amount, and by exactly a sixth over long windows`() {
        // The recipe's error lies between -Q^2/12 and Q^2/6 at every window, and for
        // unit Poisson customers the exact extra variance of a long window is
        // (Q^2 - 1)/6, so the recipe's Q^2/6 is then high by exactly 1/6.
        for (q in listOf(2, 3, 5, 8)) for (ell in listOf(0.25, 1.0, 3.0, 10.0, 30.0)) {
            val one = BatchedHub(0.0, listOf(StoreStream(2.0, q)), 0, q, ell, costs)
            val exactVariance = moments(one.demandPmf(ell)).second
            val error = one.recipeMoments(ell).second - exactVariance
            val q2 = q.toDouble() * q
            assertTrue(error >= -q2 / 12 - 1e-9 && error <= q2 / 6 + 1e-9, "Q = $q, l = $ell: error $error")
            if (ell == 30.0) close(error, 1.0 / 6.0, 1e-6, "Q = $q, long window")
        }
    }

    @Test
    fun `the fitted family can discard the recipe's extra variance`() {
        // Q = 2 over ten weeks: variance to mean ratio 20.667/20, within 5% of one,
        // so @sec-continuousreview-ltd-moments fits the Poisson.
        val one = BatchedHub(0.0, listOf(StoreStream(2.0, 2)), 0, 2, 10.0, costs)
        close(one.recipeMoments(10.0).second, 20.0 + 4.0 / 6.0, 1e-12)
        assertEquals("Poisson", one.recipe(10.0).familyName)
    }

    @Test
    fun `the example's hub, exact against the recipe and the shortcut`() {
        val exact = hub().leadTimeDemand()
        close(exact.mean, 12.0)
        close(exact.variance, 13.3332568007, 1e-8, "exact")
        close(hub().recipe(3.0).variance, 13.5, 1e-12, "recipe")
        close(hub().compoundPoissonShortcut(3.0).variance, 24.0, 1e-12, "shortcut")
    }

    @Test
    fun `the shortcut over-stocks the hub, and the recipe does not`() {
        val exact = hub().leadTimeDemand()
        fun choose(d: LeadTimeDemand) = (0..30).minBy { hub(it).holdingAndBackorderCost(d) }
        val byExact = choose(exact)
        val byRecipe = choose(hub().recipe(3.0))
        val byShortcut = choose(hub().compoundPoissonShortcut(3.0))
        assertEquals(listOf(13, 13, 15), listOf(byExact, byRecipe, byShortcut))
        close(hub(byExact).holdingAndBackorderCost(exact), 4.4054031846, 1e-8, "exact choice")
        close(hub(byShortcut).holdingAndBackorderCost(exact), 4.6533231568, 1e-8, "shortcut choice, true cost")
    }

    @Test
    fun `the example is the system optimum for these batch sizes`() {
        fun system(r0: Int, rj: Int): Double {
            val hb = hub(r0)
            val store = StoreroomWithDelay(2.0, 1.0, HubDelay(hb, 0).fineLaw(), costs).model(Route.CONDITIONED)
            return h * hb.expectedOnHand() + b * 2.0 * HubDelay(hb).mean() +
                h * store.expectedOnHand(rj, 3) + b * store.expectedBackorders(rj, 3)
        }
        var best = Triple(0, 0, Double.MAX_VALUE)
        for (r0 in 8..16) for (rj in 0..6) system(r0, rj).let { if (it < best.third) best = Triple(r0, rj, it) }
        assertEquals(12 to 3, best.first to best.second)
        close(best.third, 5.6674969634, 1e-8, "system cost per week")
    }

    @Test
    fun `the example's delays, a unit request's and the storeroom's order`() {
        val unit = HubDelay(hub())
        val order = HubDelay(hub(), store = 0)
        close(unit.cdf(0.0), hub().readyRate(), 1e-12, "a unit's P(W = 0) is the ready rate")
        close(unit.cdf(0.0), 0.8228072805, 1e-9)
        close(unit.cdf(1.0), 0.9797190455, 1e-9)
        // With batches and units mixed at the hub, B_0/lambda_0 averages every unit
        // requested, a storeroom's as well as a lone customer's, and is not quite the
        // lone customer's wait. The two differ here in the sixth decimal.
        assertTrue(abs(unit.mean() - hub().littleWait()) in 1e-6..1e-4, "unit ${unit.mean()}, Little ${hub().littleWait()}")
        close(order.cdf(0.0), 0.7626330461, 1e-9)
        close(order.cdf(1.0), 0.9668362185, 1e-9)
        close(order.mean(), 0.1263578685, 1e-9)
        close(order.variance(), 0.0894478023, 1e-9)
    }

    @Test
    fun `the example's storeroom by the three routes`() {
        val store = StoreroomWithDelay(2.0, 1.0, HubDelay(hub(), store = 0).fineLaw(), costs)
        close(store.model(Route.CONDITIONED).expectedBackorders(3, 3), 0.0823251865, 1e-7, "conditioned, exact")
        close(store.model(Route.TWO_MOMENT).expectedBackorders(3, 3), 0.0782861323, 1e-7, "two moments")
        close(store.model(Route.MEAN_ONLY).expectedBackorders(3, 3), 0.0558562401, 1e-7, "mean only")
    }
}
