package inventory.multiechelon

import java.util.PriorityQueue

/**
 * The hand ledger of a hub and one storeroom, posted by machine so that a hand
 * posting can be checked against it. @sec-batchedmultiechelon-ledger.
 *
 * The conventions are those of @exm-rq-ledger and @sec-multiechelon-ledger-run:
 * one row per event, at the week it occurs, with the state recorded after the
 * event is posted. Within a week the rule is fixed: the hub's receipts are
 * posted first, then the storeroom's, then demands in the order given. Nothing
 * here is random; the demand weeks are a script.
 *
 * Both locations run `(r, Q)`, so a base-stock storeroom is `r = S - 1` and
 * `Q = 1`. Demands are for one unit each. The hub fills its requests first come,
 * first served, and ships part of an order when it has only part of it, so an
 * order is complete when its last unit is shipped. Every shipment from the hub
 * to the storeroom that leaves in the same week travels together and arrives
 * [transit] weeks later.
 *
 * After every event the ledger checks the two identities a hand posting should
 * check: at the hub, position equals on-hand plus on-order less backorders; at
 * the storeroom, position equals on-hand less backorders plus the units in
 * transit and the units the hub still owes it.
 *
 * @param hubReorder `r_0`, in units
 * @param hubBatch `Q_0`, in units
 * @param hubLead `L_0`, in weeks
 * @param storeReorder `r_j`, in units
 * @param storeBatch `Q_j`, in units
 * @param transit `O_j`, in weeks
 * @param hubStart the hub's on-hand at week 0, which is also its position
 * @param storeStart the storeroom's on-hand at week 0, which is also its position
 */
class TwoLevelLedger(
    val hubReorder: Int,
    val hubBatch: Int,
    val hubLead: Int,
    val storeReorder: Int,
    val storeBatch: Int,
    val transit: Int,
    val hubStart: Int = hubReorder + hubBatch,
    val storeStart: Int = storeReorder + storeBatch,
) {
    init {
        require(hubBatch >= 1 && storeBatch >= 1) { "batches must be at least one unit" }
        require(hubLead >= 1 && transit >= 1) { "lead times must be at least one week" }
        require(hubStart > hubReorder && storeStart > storeReorder) {
            "each location must start above its reorder point"
        }
    }

    /** What happened at a row. */
    enum class Event { HUB_RECEIPT, STORE_RECEIPT, CUSTOMER_DEMAND, OTHER_DEMAND }

    /** A request that finished waiting: placed in [placed], completed in [completed]. */
    data class Completion(val placed: Int, val completed: Int) {
        val wait: Int get() = completed - placed
    }

    /** One posted row, the state after the event. */
    data class Row(
        val week: Int,
        val event: Event,
        /** Units received in this event, or zero for a demand. */
        val received: Int,
        val storeOnHand: Int,
        val storeBackorders: Int,
        val storePosition: Int,
        /** Units on their way from the hub to the storeroom. */
        val inTransit: Int,
        /** The size of the order the storeroom placed at this event, or zero. */
        val storeOrder: Int,
        /** Units of the storeroom's order the hub shipped at once, or null when no order was placed. */
        val shippedAtOnce: Int?,
        val hubOnHand: Int,
        val hubBackorders: Int,
        val hubOnOrder: Int,
        val hubPosition: Int,
        /** The size of the order the hub placed at this event, or zero. */
        val hubOrder: Int,
        /** Storeroom orders the hub completed at this event. */
        val ordersCompleted: List<Completion>,
        /** Customers at the storeroom served at this event after waiting. */
        val customersServed: List<Completion>,
    )

    /** The posted ledger and the waits it produced. */
    data class Posting(
        val rows: List<Row>,
        /** Every storeroom order, with the week its last unit left the hub. */
        val orderWaits: List<Completion>,
        /** Every customer demand at the storeroom, with the week it was met. */
        val customerWaits: List<Completion>,
        /** The weeks the storeroom placed orders. */
        val storeOrderWeeks: List<Int>,
    ) {
        /** The integral of the hub's backorder level over the ledger, in unit-weeks. */
        fun hubBackorderWeeks(): Int = area { it.hubBackorders }

        /** The integral of the storeroom's backorder level over the ledger, in unit-weeks. */
        fun storeBackorderWeeks(): Int = area { it.storeBackorders }

        private fun area(level: (Row) -> Int): Int {
            var total = 0
            for (k in 0 until rows.size - 1) total += level(rows[k]) * (rows[k + 1].week - rows[k].week)
            return total
        }
    }

    private data class Scheduled(val week: Int, val priority: Int, val order: Int, val event: Event, val units: Int)

    /** A request at the hub not yet completely filled. */
    private class Request(val placed: Int, var remaining: Int, val fromStore: Boolean)

    /**
     * Posts the ledger for a script of demands.
     *
     * @param customerWeeks the week of each one-unit demand at the storeroom, repeated for several in a week
     * @param otherWeeks the week of each one-unit demand at the hub from elsewhere
     */
    fun post(customerWeeks: List<Int>, otherWeeks: List<Int> = emptyList()): Posting {
        var sequence = 0
        val events = PriorityQueue(compareBy<Scheduled>({ it.week }, { it.priority }, { it.order }))
        customerWeeks.forEach { events.add(Scheduled(it, 2, sequence++, Event.CUSTOMER_DEMAND, 1)) }
        otherWeeks.forEach { events.add(Scheduled(it, 2, sequence++, Event.OTHER_DEMAND, 1)) }

        var hubOnHand = hubStart
        var hubOnOrder = 0
        var hubPosition = hubStart
        var storeOnHand = storeStart
        var storePosition = storeStart
        var inTransit = 0
        val backlog = ArrayDeque<Request>()
        val waitingCustomers = ArrayDeque<Int>()
        val arriving = HashMap<Int, Int>()      // week -> units on the truck arriving then
        val rows = ArrayList<Row>()
        val orderWaits = ArrayList<Completion>()
        val customerWaits = ArrayList<Completion>()
        val storeOrderWeeks = ArrayList<Int>()

        fun shipToStore(week: Int, units: Int) {
            if (units == 0) return
            inTransit += units
            val due = week + transit
            if (due !in arriving) events.add(Scheduled(due, 1, sequence++, Event.STORE_RECEIPT, 0))
            arriving[due] = (arriving[due] ?: 0) + units
        }

        /** A request of [units] at the hub; returns units shipped at once and the hub order placed. */
        fun request(week: Int, units: Int, fromStore: Boolean): Pair<Int, Int> {
            val now = minOf(hubOnHand, units)
            hubOnHand -= now
            if (fromStore) shipToStore(week, now)
            if (units > now) backlog.addLast(Request(week, units - now, fromStore))
            else if (fromStore) orderWaits.add(Completion(week, week))
            hubPosition -= units
            var placed = 0
            while (hubPosition <= hubReorder) {
                hubPosition += hubBatch
                hubOnOrder += hubBatch
                placed += hubBatch
                events.add(Scheduled(week + hubLead, 0, sequence++, Event.HUB_RECEIPT, hubBatch))
            }
            return now to placed
        }

        while (events.isNotEmpty()) {
            val e = events.poll()
            var received = 0
            var storeOrder = 0
            var shippedAtOnce: Int? = null
            var hubOrder = 0
            val completed = ArrayList<Completion>()
            val served = ArrayList<Completion>()
            when (e.event) {
                Event.HUB_RECEIPT -> {
                    received = e.units
                    hubOnHand += e.units
                    hubOnOrder -= e.units
                    while (backlog.isNotEmpty() && hubOnHand > 0) {
                        val r = backlog.first()
                        val s = minOf(hubOnHand, r.remaining)
                        hubOnHand -= s
                        r.remaining -= s
                        if (r.fromStore) shipToStore(e.week, s)
                        if (r.remaining == 0) {
                            backlog.removeFirst()
                            if (r.fromStore) Completion(r.placed, e.week).also { completed += it; orderWaits += it }
                        }
                    }
                }
                Event.STORE_RECEIPT -> {
                    var units = arriving.remove(e.week) ?: 0
                    received = units
                    inTransit -= units
                    while (waitingCustomers.isNotEmpty() && units > 0) {
                        Completion(waitingCustomers.removeFirst(), e.week).also { served += it; customerWaits += it }
                        units -= 1
                    }
                    storeOnHand += units
                }
                Event.CUSTOMER_DEMAND -> {
                    if (storeOnHand > 0) {
                        storeOnHand -= 1
                        customerWaits += Completion(e.week, e.week)
                    } else {
                        waitingCustomers.addLast(e.week)
                    }
                    storePosition -= 1
                    while (storePosition <= storeReorder) {
                        storePosition += storeBatch
                        storeOrder += storeBatch
                    }
                    if (storeOrder > 0) {
                        storeOrderWeeks += e.week
                        val (now, placed) = request(e.week, storeOrder, fromStore = true)
                        shippedAtOnce = now
                        hubOrder = placed
                    }
                }
                Event.OTHER_DEMAND -> hubOrder = request(e.week, e.units, fromStore = false).second
            }
            val hubBackorders = backlog.sumOf { it.remaining }
            val owedToStore = backlog.filter { it.fromStore }.sumOf { it.remaining }
            val row = Row(
                e.week, e.event, received,
                storeOnHand, waitingCustomers.size, storePosition, inTransit, storeOrder, shippedAtOnce,
                hubOnHand, hubBackorders, hubOnOrder, hubPosition, hubOrder, completed, served,
            )
            check(hubPosition == hubOnHand + hubOnOrder - hubBackorders) { "hub position fails at $row" }
            check(storePosition == storeOnHand - waitingCustomers.size + inTransit + owedToStore) {
                "storeroom position fails at $row"
            }
            rows += row
        }
        return Posting(rows, orderWaits.sortedBy { it.placed }, customerWaits.sortedBy { it.placed }, storeOrderWeeks)
    }
}
