package inventory.multiechelon

import inventory.multiechelon.TwoLevelLedger.Completion
import inventory.multiechelon.TwoLevelLedger.Event
import inventory.multiechelon.TwoLevelLedger.Event.CUSTOMER_DEMAND
import inventory.multiechelon.TwoLevelLedger.Event.HUB_RECEIPT
import inventory.multiechelon.TwoLevelLedger.Event.OTHER_DEMAND
import inventory.multiechelon.TwoLevelLedger.Event.STORE_RECEIPT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two ledgers of @sec-batchedmultiechelon-ledger and @sec-batchedmultiechelon-orders, pinned row by row.
 *
 * Ledger A: the hub batches and the storeroom runs base stock. Its payoff is
 * the week-11 delivery that clears three waiting requests at once.
 * Ledger B: the storeroom batches too, and a little other demand reaches the
 * hub. Its payoff is the week-9 order, filled two thirds at once and completed
 * three weeks later when the hub's delivery brings its last unit.
 */
class TwoLevelLedgerTest {

    /** The columns a hand posting records: week, event, received, then the two locations. */
    private data class Expected(
        val week: Int, val event: Event, val received: Int,
        val ij: Int, val bj: Int, val ipj: Int, val transit: Int,
        val i0: Int, val b0: Int, val o0: Int, val ip0: Int,
    )

    private fun e(week: Int, event: Event, received: Int, ij: Int, bj: Int, ipj: Int, tr: Int,
                  i0: Int, b0: Int, o0: Int, ip0: Int) = Expected(week, event, received, ij, bj, ipj, tr, i0, b0, o0, ip0)

    private fun assertRows(want: List<Expected>, got: List<TwoLevelLedger.Row>) {
        assertEquals(want.size, got.size, "row count")
        for ((k, pair) in want.zip(got).withIndex()) {
            val (w, g) = pair
            val actual = Expected(g.week, g.event, g.received, g.storeOnHand, g.storeBackorders, g.storePosition,
                g.inTransit, g.hubOnHand, g.hubBackorders, g.hubOnOrder, g.hubPosition)
            assertEquals(w, actual, "row ${k + 1}")
        }
    }

    // ---- Ledger A -------------------------------------------------------------

    private val ledgerA = TwoLevelLedger(
        hubReorder = 1, hubBatch = 4, hubLead = 3, storeReorder = 1, storeBatch = 1, transit = 1,
    )

    /** Seven failures on their own weeks and a storm of three in week 8. */
    private val failuresA = listOf(1, 3, 6, 8, 8, 8, 9, 10, 14)

    private val postedA = ledgerA.post(failuresA)

    @Test
    fun `ledger A, every row`() = assertRows(
        listOf(
            e(1, CUSTOMER_DEMAND, 0, 1, 0, 2, 1, 4, 0, 0, 4),
            e(2, STORE_RECEIPT, 1, 2, 0, 2, 0, 4, 0, 0, 4),
            e(3, CUSTOMER_DEMAND, 0, 1, 0, 2, 1, 3, 0, 0, 3),
            e(4, STORE_RECEIPT, 1, 2, 0, 2, 0, 3, 0, 0, 3),
            e(6, CUSTOMER_DEMAND, 0, 1, 0, 2, 1, 2, 0, 0, 2),
            e(7, STORE_RECEIPT, 1, 2, 0, 2, 0, 2, 0, 0, 2),
            e(8, CUSTOMER_DEMAND, 0, 1, 0, 2, 1, 1, 0, 4, 5),
            e(8, CUSTOMER_DEMAND, 0, 0, 0, 2, 2, 0, 0, 4, 4),
            e(8, CUSTOMER_DEMAND, 0, 0, 1, 2, 2, 0, 1, 4, 3),
            e(9, STORE_RECEIPT, 2, 1, 0, 2, 0, 0, 1, 4, 3),
            e(9, CUSTOMER_DEMAND, 0, 0, 0, 2, 0, 0, 2, 4, 2),
            e(10, CUSTOMER_DEMAND, 0, 0, 1, 2, 0, 0, 3, 8, 5),
            e(11, HUB_RECEIPT, 4, 0, 1, 2, 3, 1, 0, 4, 5),
            e(12, STORE_RECEIPT, 3, 2, 0, 2, 0, 1, 0, 4, 5),
            e(13, HUB_RECEIPT, 4, 2, 0, 2, 0, 5, 0, 0, 5),
            e(14, CUSTOMER_DEMAND, 0, 1, 0, 2, 1, 4, 0, 0, 4),
            e(15, STORE_RECEIPT, 1, 2, 0, 2, 0, 4, 0, 0, 4),
        ),
        postedA.rows,
    )

    @Test
    fun `ledger A, one delivery clears three waiting requests`() {
        val week11 = postedA.rows.single { it.week == 11 }
        assertEquals(listOf(Completion(8, 11), Completion(9, 11), Completion(10, 11)), week11.ordersCompleted)
        assertEquals(1, week11.hubOnHand, "the fourth unit goes on the hub's shelf")
        // Three waits of 3, 2 and 1 weeks end at one event.
        assertEquals(listOf(3, 2, 1), week11.ordersCompleted.map { it.wait })
    }

    @Test
    fun `ledger A, the storeroom's crews wait because the hub did`() {
        assertEquals(listOf(Completion(8, 9), Completion(10, 12)), postedA.customerWaits.filter { it.wait > 0 })
        assertEquals(3, postedA.storeBackorderWeeks())
    }

    @Test
    fun `ledger A, the wait two ways`() {
        // Following requests: nine requests, waits summing to 6 weeks.
        val waits = postedA.orderWaits.map { it.wait }
        assertEquals(listOf(0, 0, 0, 0, 0, 3, 2, 1, 0), waits)
        val byRequest = waits.sum().toDouble() / waits.size
        // Little's law: backorder-weeks over the ledger, divided by requests.
        val byLittle = postedA.hubBackorderWeeks().toDouble() / failuresA.size
        assertEquals(6, postedA.hubBackorderWeeks())
        assertEquals(2.0 / 3.0, byRequest, 1e-12)
        assertEquals(byRequest, byLittle, 1e-12)
    }

    @Test
    fun `ledger A, a request arriving with the position high waits nothing`() {
        val first = postedA.rows.first()
        assertTrue(first.hubPosition > ledgerA.hubReorder + 2)
        assertEquals(Completion(1, 1), postedA.orderWaits.first())
    }

    // ---- Ledger B -------------------------------------------------------------

    private val ledgerB = TwoLevelLedger(
        hubReorder = 1, hubBatch = 6, hubLead = 3, storeReorder = 1, storeBatch = 3, transit = 1,
    )

    private val customersB = listOf(1, 3, 5, 7, 8, 9, 9, 10, 11)
    private val otherB = listOf(2, 4)

    private val postedB = ledgerB.post(customersB, otherB)

    @Test
    fun `ledger B, every row`() = assertRows(
        listOf(
            e(1, CUSTOMER_DEMAND, 0, 3, 0, 3, 0, 7, 0, 0, 7),
            e(2, OTHER_DEMAND, 0, 3, 0, 3, 0, 6, 0, 0, 6),
            e(3, CUSTOMER_DEMAND, 0, 2, 0, 2, 0, 6, 0, 0, 6),
            e(4, OTHER_DEMAND, 0, 2, 0, 2, 0, 5, 0, 0, 5),
            e(5, CUSTOMER_DEMAND, 0, 1, 0, 4, 3, 2, 0, 0, 2),
            e(6, STORE_RECEIPT, 3, 4, 0, 4, 0, 2, 0, 0, 2),
            e(7, CUSTOMER_DEMAND, 0, 3, 0, 3, 0, 2, 0, 0, 2),
            e(8, CUSTOMER_DEMAND, 0, 2, 0, 2, 0, 2, 0, 0, 2),
            e(9, CUSTOMER_DEMAND, 0, 1, 0, 4, 2, 0, 1, 6, 5),
            e(9, CUSTOMER_DEMAND, 0, 0, 0, 3, 2, 0, 1, 6, 5),
            e(10, STORE_RECEIPT, 2, 2, 0, 3, 0, 0, 1, 6, 5),
            e(10, CUSTOMER_DEMAND, 0, 1, 0, 2, 0, 0, 1, 6, 5),
            e(11, CUSTOMER_DEMAND, 0, 0, 0, 4, 0, 0, 4, 6, 2),
            e(12, HUB_RECEIPT, 6, 0, 0, 4, 4, 2, 0, 0, 2),
            e(13, STORE_RECEIPT, 4, 4, 0, 4, 0, 2, 0, 0, 2),
        ),
        postedB.rows,
    )

    @Test
    fun `ledger B, the hub sees orders of three and not the storeroom's customers`() {
        val atHub = postedB.rows.filter { it.storeOrder > 0 }
        assertEquals(listOf(5, 9, 11), atHub.map { it.week })
        assertTrue(atHub.all { it.storeOrder == 3 })
        // A customer demand that places no storeroom order leaves the hub's position where it was.
        val rows = postedB.rows
        for (k in 1 until rows.size) {
            val r = rows[k]
            if (r.event == CUSTOMER_DEMAND && r.storeOrder == 0) {
                assertEquals(rows[k - 1].hubPosition, r.hubPosition, "week ${r.week}")
            }
        }
    }

    @Test
    fun `ledger B, an order waits for its last unit`() {
        val week9 = postedB.rows.first { it.week == 9 }
        assertEquals(3, week9.storeOrder)
        assertEquals(2, week9.shippedAtOnce, "two of the three units leave at once")
        assertEquals(-1 + ledgerB.hubBatch, week9.hubPosition, "the batch carries the hub's position to -1 before it reorders")
        assertEquals(listOf(Completion(5, 5), Completion(9, 12), Completion(11, 12)), postedB.orderWaits)
        assertEquals(listOf(Completion(9, 12), Completion(11, 12)), postedB.rows.single { it.week == 12 }.ordersCompleted)
    }

    @Test
    fun `ledger B, storeroom orders counted in a three-week window`() {
        assertEquals(listOf(5, 9, 11), postedB.storeOrderWeeks)
        assertEquals(2, postedB.storeOrderWeeks.count { it in 9..11 })
        assertEquals(0, postedB.storeOrderWeeks.count { it in 6..8 })
    }
}
