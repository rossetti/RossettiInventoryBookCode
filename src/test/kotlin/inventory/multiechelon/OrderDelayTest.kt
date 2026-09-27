package inventory.multiechelon

import inventory.multiechelon.StoreroomWithDelay.Route
import inventory.multiechelon.TwoLevelLedger.Event.OTHER_DEMAND
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The delay of a storeroom's order, seen from the order, and the storeroom's
 * measures conditioned on it. @sec-batchedmultiechelon-storeroom.
 *
 * The claim these tests hold: with unit Poisson customers, first-come-first-served
 * filling and `r_0 >= 0`, conditioning a batching storeroom's lead time demand on
 * the delay its orders see is exact. It reproduces Axsäter's (2) for one
 * storeroom and his (6) for identical storerooms, wherever both apply.
 */
class OrderDelayTest {

    private fun close(got: Double, want: Double, tol: Double, what: String = "") =
        assertTrue(abs(got - want) < tol, "$what expected $want, got $got")

    /**
     * The system cost per unit time by the chapter's route, in Axsäter's setting:
     * `N` identical storerooms, both lead times one, holding cost one at both levels.
     * [orderView] false uses the delay of a random one-unit request instead.
     */
    private fun conditioned(lambda: Double, n: Int, beta: Double, qr: Int, qw: Int, rw: Int, rr: Int,
                            orderView: Boolean = true): Double {
        val hub = BatchedHub(0.0, List(n) { StoreStream(lambda, qr) }, rw * qr, qw * qr, 1.0, RQCosts(0.0, 1.0, 1.0))
        val delay = if (orderView) HubDelay(hub, store = 0) else HubDelay(hub)
        val store = StoreroomWithDelay(lambda, 1.0, delay.fineLaw(), RQCosts(0.0, 1.0, beta)).model(Route.CONDITIONED)
        val perStore = store.expectedOnHand(rr, qr) + beta * store.expectedBackorders(rr, qr)
        return n * perStore + hub.expectedOnHand()
    }

    private fun axsater(lambda: Double, n: Int, beta: Double, qr: Int, qw: Int, rw: Int, rr: Int) =
        AxsaterBatchOrdering(OneForOneTwoLevel(lambda, n, 1.0, 1.0, 1.0, 1.0, beta), qr, qw).cost(rw, rr)

    @Test
    fun `one storeroom, equation (2), 144 policies`() {
        val failures = ArrayList<String>()
        for (lambda in listOf(1.0, 0.1)) for (qr in listOf(2, 4)) for (qw in 1..3) for (rw in 0..2) for (rr in -1..2) {
            val want = axsater(lambda, 1, 20.0, qr, qw, rw, rr)
            val got = conditioned(lambda, 1, 20.0, qr, qw, rw, rr)
            if (abs(got - want) > 2e-6 * maxOf(1.0, want)) failures += "lambda $lambda, Q_r $qr, Q_w $qw, R_w $rw, R_r $rr: $want vs $got"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `four identical storerooms, equation (6), 96 policies`() {
        val failures = ArrayList<String>()
        for (lambda in listOf(1.0, 0.1)) for (qr in listOf(2, 4)) for (qw in listOf(1, 4)) for (rw in 0..1) for (rr in 0..2) for (beta in listOf(20.0, 5.0)) {
            val want = axsater(lambda, 4, beta, qr, qw, rw, rr)
            val got = conditioned(lambda, 4, beta, qr, qw, rw, rr)
            if (abs(got - want) > 2e-6 * maxOf(1.0, want)) failures += "lambda $lambda, Q_r $qr, Q_w $qw, R_w $rw, R_r $rr, beta $beta: $want vs $got"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the delay of a random unit request is the wrong one for an order`() {
        // The order has just drawn its storeroom's position down to r_j, so fewer of
        // that storeroom's orders sit ahead of it than a random moment would suggest.
        close(axsater(1.0, 1, 20.0, 2, 1, 0, -1), 16.72896, 1e-5, "exact")
        close(conditioned(1.0, 1, 20.0, 2, 1, 0, -1), 16.72896, 1e-5, "seen from the order")
        close(conditioned(1.0, 1, 20.0, 2, 1, 0, -1, orderView = false), 19.01611, 1e-5, "seen from a random unit")
    }

    @Test
    fun `an order of one unit waits as a one-unit request does`() {
        val hub = BatchedHub(2.0, listOf(StoreStream(1.0, 1), StoreStream(1.0, 3)), 4, 6, 3.0, RQCosts(0.0, 1.0, 1.0))
        val order = HubDelay(hub, store = 0)
        val unit = HubDelay(hub)
        for (w in listOf(0.0, 0.5, 1.0, 2.0, 2.9)) close(order.cdf(w), unit.cdf(w), 1e-12, "w = $w")
    }

    @Test
    fun `with only batches at the hub, the position steps in batches`() {
        val hub = BatchedHub(0.0, List(4) { StoreStream(1.0, 4) }, 4, 8, 1.0, RQCosts(0.0, 1.0, 1.0))
        assertEquals(listOf(8, 12), hub.positions())
        assertFailsWith<IllegalArgumentException> {
            BatchedHub(0.0, List(4) { StoreStream(1.0, 4) }, 3, 8, 1.0, RQCosts(0.0, 1.0, 1.0)).positions()
        }
        // Any unit demand fills in the band.
        assertEquals((5..12).toList(), BatchedHub(0.5, List(4) { StoreStream(1.0, 4) }, 4, 8, 1.0, RQCosts(0.0, 1.0, 1.0)).positions())
    }

    // ---- The lead-time shift, checked on the ledgers' own sample paths ----------

    /**
     * For every storeroom order in a posted ledger and every whole number of weeks
     * `w` below `L_0`: the order is complete by `A + w` exactly when the hub's
     * position after the events of week `A - (L_0 - w)` covers the hub demand
     * posted after that week, up to and including the order itself.
     */
    private fun checkShift(ledger: TwoLevelLedger, posted: TwoLevelLedger.Posting) {
        val rows = posted.rows
        val orderRows = rows.withIndex().filter { it.value.storeOrder > 0 }
        assertEquals(orderRows.size, posted.orderWaits.size)
        fun hubUnits(r: TwoLevelLedger.Row) = if (r.event == OTHER_DEMAND) 1 else r.storeOrder
        for ((n, indexed) in orderRows.withIndex()) {
            val (k, row) = indexed
            val done = posted.orderWaits[n].completed
            for (w in 0 until ledger.hubLead) {
                val then = row.week - (ledger.hubLead - w)
                val last = rows.indexOfLast { it.week <= then }
                val position = if (last < 0) ledger.hubStart else rows[last].hubPosition
                val since = (last + 1..k).sumOf { hubUnits(rows[it]) }
                assertEquals(done <= row.week + w, position - since >= 0,
                    "order of week ${row.week}, w = $w: position $position, demand since $since, completed $done")
            }
        }
    }

    @Test
    fun `the lead-time shift holds on ledger A`() {
        val ledger = TwoLevelLedger(1, 4, 3, 1, 1, 1)
        checkShift(ledger, ledger.post(listOf(1, 3, 6, 8, 8, 8, 9, 10, 14)))
    }

    @Test
    fun `the lead-time shift holds on ledger B`() {
        val ledger = TwoLevelLedger(1, 6, 3, 1, 3, 1)
        checkShift(ledger, ledger.post(listOf(1, 3, 5, 7, 8, 9, 9, 10, 11), listOf(2, 4)))
    }
}
