package inventory.requirementsplanning

import kotlin.math.max

/**
 * One item's MRP record over a horizon: the six rows of @sec-mrpdrp-record.
 *
 * Periods are numbered 1 through [horizon] on the outside, as everywhere else in this
 * book. The record is laid out with periods across the COLUMNS when it is printed, which
 * is the universal format for a requirements record and the reverse of Chapter 5's
 * worksheets. Nothing in the computation depends on that.
 *
 * The record is built in two sweeps and the reason is @sec-mrpdrp-lotsizing. The first sweep asks
 * what would have to arrive in each period if nothing were carried beyond what is
 * already on hand or on order. That is the net requirement stream, and it is the one
 * thing here that does not depend on the lot sizing rule. The policy then covers it. The
 * second sweep works out what the policy's receipts actually leave on hand, which is
 * what the printed net requirement row shows, and it is usually not the first sweep's
 * answer.
 */
class MrpRecord(
    val item: PlannedItem,
    val grossRequirements: List<Double>,
) {
    init {
        require(grossRequirements.isNotEmpty()) { "A record needs at least one period" }
        require(grossRequirements.all { it >= 0.0 }) {
            "${item.label} has a negative gross requirement"
        }
    }

    val horizon: Int get() = grossRequirements.size
    val periods: IntRange get() = 1..horizon

    /**
     * What must be on hand in each period if nothing beyond the opening stock and the
     * scheduled receipts is carried into it. This is what the policy is asked to cover,
     * and by @sec-mrpdrp-lotsizing it is a Chapter 5 requirements schedule.
     */
    val netRequirementsBeforeLotSizing: List<Double> by lazy {
        var onHand = item.onHand
        periods.map { t ->
            val net = max(gross(t) + item.safetyStock - item.scheduledReceiptIn(t) - onHand, 0.0)
            onHand = onHand + item.scheduledReceiptIn(t) + net - gross(t)
            net
        }
    }

    /** What the policy decided should arrive, period by period. */
    val plannedOrderReceipts: List<Double> by lazy {
        item.policy.receiptsFor(netRequirementsBeforeLotSizing, item).also {
            require(it.size == horizon) {
                "${item.policy} returned ${it.size} receipts for a horizon of $horizon"
            }
        }
    }

    private val sweep: Pair<List<Double>, List<Double>> by lazy {
        var onHand = item.onHand
        val net = mutableListOf<Double>()
        val poh = mutableListOf<Double>()
        for (t in periods) {
            net.add(max(gross(t) + item.safetyStock - item.scheduledReceiptIn(t) - onHand, 0.0))
            onHand = onHand + item.scheduledReceiptIn(t) + plannedOrderReceipts[t - 1] - gross(t)
            poh.add(onHand)
        }
        net to poh
    }

    /** The printed net requirement row, which reflects what the policy chose to carry. */
    val netRequirements: List<Double> get() = sweep.first

    /** The stock expected at the END of each period. This is @eq-dls-balance with receipts. */
    val projectedOnHand: List<Double> get() = sweep.second

    /**
     * When each planned receipt has to be ORDERED, which is the record's output and the
     * next level's input. A receipt due before the lead time has elapsed cannot be
     * ordered inside the horizon, and @sec-mrpdrp-record calls that a past-due release.
     */
    val plannedOrderReleases: List<Double> by lazy {
        val releases = MutableList(horizon) { 0.0 }
        for (t in periods) {
            val due = plannedOrderReceipts[t - 1]
            if (due > 0.0 && t - item.leadTime >= 1) releases[t - item.leadTime - 1] += due
        }
        releases
    }

    /** Receipts the lead time puts before period 1, which no plan inside the horizon can place. */
    val pastDueReleases: Double by lazy {
        periods.sumOf { t ->
            if (plannedOrderReceipts[t - 1] > 0.0 && t - item.leadTime < 1)
                plannedOrderReceipts[t - 1] else 0.0
        }
    }

    fun gross(period: Int): Double = grossRequirements[check(period)]
    fun releaseIn(period: Int): Double = plannedOrderReleases[check(period)]
    fun receiptIn(period: Int): Double = plannedOrderReceipts[check(period)]
    fun onHandAt(period: Int): Double = projectedOnHand[check(period)]

    val orderCount: Int get() = plannedOrderReceipts.count { it > 0.0 }
    val setupCost: Double get() = orderCount * item.orderCost
    val carryingCost: Double get() = projectedOnHand.sumOf { it * item.holdingRate }
    val relevantCost: Double get() = setupCost + carryingCost

    private fun check(period: Int): Int {
        require(period in periods) { "Period $period is outside 1..$horizon" }
        return period - 1
    }

    override fun toString(): String =
        "MrpRecord(${item.id}, ${item.policy}, releases=$plannedOrderReleases)"
}
