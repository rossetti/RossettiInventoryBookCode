package inventory.multiechelon

import inventory.multiechelon.StoreroomWithDelay.Route
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @sec-batchedmultiechelon-exact measured against Axsäter's exact answer.
 *
 * The running example: the fuse cutout, four storerooms each with one unit a
 * week of Poisson demand and base stock `S_j = 3`, one week's transit, and a hub
 * running `(r_0, Q_0) = (9, 8)` with a three-week lead time. Costs are chapter
 * 8's for the cutout, per week: `h = 28.75/52` at both levels and `b = 287.50/52`
 * at the storerooms.
 */
class AgreementWithAxsaterTest {

    private fun close(got: Double, want: Double, tol: Double, what: String = "") =
        assertTrue(abs(got - want) < tol, "$what expected $want, got $got")

    private val h = 28.75 / 52
    private val b = 287.50 / 52
    private val exact = AxsaterBatchOrdering(OneForOneTwoLevel(1.0, 4, 1.0, 3.0, h, h, b), storeBatch = 1, hubBatches = 8)
    private val hub = BatchedHub(0.0, List(4) { StoreStream(1.0, 1) }, 9, 8, 3.0, RQCosts(0.0, h, b))
    private val delay = HubDelay(hub)
    private val store = StoreroomWithDelay(1.0, 1.0, delay.fineLaw(), RQCosts(0.0, h, b))

    /** `S_j = 3` is `r_j = 2`, `Q_j = 1`. */
    private val rj = 2

    @Test
    fun `the running example is the exact optimum for a hub batch of eight`() {
        var best = Triple(0, 0, Double.MAX_VALUE)
        for (r0 in -4..16) for (s in 0..6) {
            val c = exact.cost(r0, s - 1)
            if (c < best.third) best = Triple(r0, s, c)
        }
        assertEquals(9 to 3, best.first to best.second)
        close(best.third, 7.0194859291, 1e-9, "exact weekly cost")
    }

    @Test
    fun `the hub is exactly a chapter 8 location`() {
        // Axsäter's hub on-hand, by his recursion, against @eq-rq-onhand on a Poisson(12) lead time demand.
        val m = exact.measures(9, rj)
        close(m.hubOnHand, hub.model().expectedOnHand(9, 8), 1e-9, "hub on-hand")
        close(m.hubOnHand, 2.5273098089, 1e-9)
    }

    @Test
    fun `conditioning on the delay is exact for base-stock storerooms`() {
        // The wait a request suffers is fixed by what happened before it, so it is
        // independent of the storeroom's demand afterwards, and averaging the
        // storeroom's measures over the wait's distribution loses nothing.
        val m = exact.measures(9, rj)
        val conditioned = store.model(Route.CONDITIONED)
        close(conditioned.expectedBackorders(rj, 1), m.storeBackorders / 4, 1e-6, "backorders per storeroom")
        close(conditioned.expectedOnHand(rj, 1), m.storeOnHand / 4, 1e-6, "on-hand per storeroom")
        close(m.storeBackorders / 4, 0.0726389473, 1e-9)
        close(m.storeOnHand / 4, 1.8158114951, 1e-9)
    }

    @Test
    fun `the mean delay alone understates the storeroom's backorders, two moments nearly recover them`() {
        close(store.model(Route.MEAN_ONLY).expectedBackorders(rj, 1), 0.0505115100, 1e-9, "mean only")
        close(store.model(Route.TWO_MOMENT).expectedBackorders(rj, 1), 0.0712212229, 1e-9, "two moments")
        // Both carry the exact mean delay, so on-hand less backorders is S_j - lambda (O + E[W]) in every route.
        for (route in Route.values()) {
            val m = store.model(route)
            close(m.expectedOnHand(rj, 1) - m.expectedBackorders(rj, 1), 3.0 - (1.0 + delay.mean()), 1e-6, "$route balance")
        }
    }

    @Test
    fun `a daily grid, as the workbook uses, is within a fifth of a percent`() {
        val daily = StoreroomWithDelay(1.0, 1.0, delay.law(1.0 / 7), RQCosts(0.0, h, b))
        val got = daily.model(Route.CONDITIONED).expectedBackorders(rj, 1)
        assertTrue(abs(got / 0.0726389473 - 1.0) < 0.002, "daily grid gives $got")
    }

    @Test
    fun `one-for-one at both levels, equation (1) is the recursion itself`() {
        val oneForOne = AxsaterBatchOrdering(OneForOneTwoLevel(1.0, 4, 1.0, 3.0, h, h, b), 1, 1)
        for (r0 in 0..14) close(oneForOne.cost(r0, rj), oneForOne.base.cost(r0 + 1, rj + 1), 1e-12)
    }
}
