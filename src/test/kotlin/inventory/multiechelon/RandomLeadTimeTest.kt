package inventory.multiechelon

import inventory.continuousreview.LeadTimeDemand
import inventory.multiechelon.StoreroomWithDelay.Route
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A random supplier lead time at the hub of @sec-batchedmultiechelon-leadtime:
 * two, three or five weeks with probabilities 0.4, 0.4 and 0.2, a mean of three.
 */
class RandomLeadTimeTest {

    private fun close(got: Double, want: Double, tol: Double = 1e-9, what: String = "") =
        assertTrue(abs(got - want) < tol, "$what expected $want, got $got")

    private val h = 28.75 / 52
    private val b = 287.50 / 52
    private val costs = RQCosts(0.0, h, b)
    private val hub = BatchedHub(2.0, listOf(StoreStream(2.0, 3)), 12, 8, 3.0, costs)
    private val lead = listOf(2.0 to 0.4, 3.0 to 0.4, 5.0 to 0.2)

    private fun moments(pmf: DoubleArray): Pair<Double, Double> {
        val m = pmf.withIndex().sumOf { (x, p) -> x * p }
        return m to pmf.withIndex().sumOf { (x, p) -> (x - m) * (x - m) * p }
    }

    @Test
    fun `averaged at each value, the variance obeys the law of total variance`() {
        val (m, v) = moments(hub.demandPmf(lead))
        close(m, 12.0, 1e-9)
        // E[Var | L] + Var[E | L], with E[D_0 | L] = 4 L and Var[L] = 1.2.
        val within = lead.sumOf { (ell, p) -> p * moments(hub.demandPmf(ell)).second }
        close(v, within + 16.0 * 1.2, 1e-8)
        close(v, 32.5345566220, 1e-8)
    }

    @Test
    fun `averaging each stream on its own loses exactly the covariance the shared lead time creates`() {
        val right = moments(hub.demandPmf(lead)).second
        val wrong = moments(hub.demandPmfStreamsAveragedSeparately(lead)).second
        // Cov between the two streams is lambda_o lambda_j Var[L] = 2 * 2 * 1.2, counted twice.
        close(right - wrong, 2.0 * 2.0 * 2.0 * 1.2, 1e-8)
    }

    @Test
    fun `a lead time with one value is the constant one`() {
        val one = hub.demandPmf(listOf(3.0 to 1.0))
        val constant = hub.demandPmf(3.0)
        for (x in constant.indices) close(one.getOrElse(x) { 0.0 }, constant[x], 1e-15)
        val mixed = MixedWait.atHub(hub, listOf(3.0 to 1.0), store = 0)
        val single = HubDelay(hub, store = 0)
        for (w in listOf(0.0, 0.7, 1.5, 2.9)) close(mixed.cdf(w), single.cdf(w), 1e-15)
    }

    @Test
    fun `Little's law holds for the averaged wait when every request is one unit`() {
        // Part I's hub: four one-for-one storerooms, so every request is a Poisson unit.
        val poissonHub = BatchedHub(0.0, List(4) { StoreStream(1.0, 1) }, 9, 8, 3.0, costs)
        val ltd = LeadTimeDemand.tabulated(poissonHub.demandPmf(lead))
        close(MixedWait.atHub(poissonHub, lead).mean(), poissonHub.expectedBackorders(ltd) / poissonHub.demandRate, 1e-8)
    }

    @Test
    fun `a storeroom planned on the mean wait under-protects when the supplier varies`() {
        val wait = MixedWait.atHub(hub, lead, store = 0)
        close(wait.mean(), 0.3037449081, 1e-9)
        close(wait.variance(), 0.4395764072, 1e-8)
        val store = StoreroomWithDelay(2.0, 1.0, wait.fineLaw(), costs)
        fun choose(route: Route): Int {
            val m = store.model(route)
            return (-3..12).minBy { h * m.expectedOnHand(it, 3) + b * m.expectedBackorders(it, 3) }
        }
        assertEquals(listOf(3, 4, 4), listOf(Route.MEAN_ONLY, Route.TWO_MOMENT, Route.CONDITIONED).map { choose(it) })
        val truth = store.model(Route.CONDITIONED)
        fun cost(r: Int) = h * truth.expectedOnHand(r, 3) + b * truth.expectedBackorders(r, 3)
        close(cost(3), 2.7771347524, 1e-7, "planned on the mean")
        close(cost(4), 2.7472173017, 1e-7, "planned on the distribution")
        close(truth.expectedBackorders(3, 3), 0.2391346781, 1e-7)
        close(truth.expectedBackorders(4, 3), 0.1433063542, 1e-7)
    }

    @Test
    fun `with a constant supplier the three routes plan the same storeroom`() {
        val store = StoreroomWithDelay(2.0, 1.0, HubDelay(hub, store = 0).fineLaw(), costs)
        val picks = Route.values().map { route ->
            val m = store.model(route)
            (-3..12).minBy { h * m.expectedOnHand(it, 3) + b * m.expectedBackorders(it, 3) }
        }
        assertEquals(listOf(3, 3, 3), picks)
    }
}
