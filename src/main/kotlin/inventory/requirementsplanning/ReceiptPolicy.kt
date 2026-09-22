package inventory.requirementsplanning

import inventory.dynamiclotsizing.LotSizingRule
import kotlin.math.max

/**
 * How much to have arrive, given what the record says must arrive. @sec-mrpdrp-lotsizing.
 *
 * The record computes a net requirement for every period, meaning the amount that has to
 * be on hand in that period if nothing extra is carried into it. A policy turns that
 * stream into planned order receipts. Lot-for-lot copies it. Everything else covers runs
 * of it with larger, less frequent receipts.
 *
 * One interface serves both kinds of rule, and it took some finding. A rule like the
 * fixed order quantity decides period by period and looks nowhere; a rule like
 * Silver-Meal has to see the whole stream before it can decide anything. They fit one
 * interface because the net requirement stream is computed BEFORE any of them run, which
 * is what makes it available to a rule that needs all of it.
 */
fun interface ReceiptPolicy {
    /** Planned order receipts, one per period, covering [netRequirements]. */
    fun receiptsFor(netRequirements: List<Double>, item: PlannedItem): List<Double>
}

/** Have exactly what is needed arrive when it is needed. @sec-mrpdrp-lotsizing. */
object LotForLotPolicy : ReceiptPolicy {
    override fun receiptsFor(netRequirements: List<Double>, item: PlannedItem): List<Double> =
        netRequirements

    override fun toString(): String = "Lot-for-lot"
}

/**
 * Order [quantity], or the period's net requirement when that is larger. @sec-mrpdrp-lotsizing.
 *
 * @sec-dls-threeplans excluded this rule, because a quantity unrelated to the
 * requirements cannot finish the horizon empty. Inside an MRP record it is admissible,
 * and widely used, precisely because the record is not required to finish empty: what is
 * left over is on hand at the start of the next planning cycle, which is what the
 * projected-on-hand row is for.
 */
class FixedOrderQuantity(private val quantity: Double) : ReceiptPolicy {
    init { require(quantity > 0.0) { "A fixed order quantity must be positive" } }

    override fun receiptsFor(netRequirements: List<Double>, item: PlannedItem): List<Double> {
        val receipts = MutableList(netRequirements.size) { 0.0 }
        var carried = 0.0
        for (t in netRequirements.indices) {
            val shortfall = netRequirements[t] - carried
            if (shortfall > TOLERANCE) {
                receipts[t] = max(quantity, shortfall)
                carried += receipts[t]
            }
            carried -= netRequirements[t]
            if (carried < 0.0) carried = 0.0
        }
        return receipts
    }

    override fun toString(): String = "Fixed order quantity of %.0f".format(quantity)
}

/**
 * Any of the rules of Chapter 5, applied to the net requirement stream. @sec-mrpdrp-lotsizing.
 *
 * The adapter is three lines and that is the point of it. Silver-Meal, least unit cost,
 * part-period balancing and Wagner-Whitin need no MRP-specific version, because the
 * problem they solve is the one the record has just finished stating.
 */
class PlanTheHorizon(private val rule: LotSizingRule) : ReceiptPolicy {
    override fun receiptsFor(netRequirements: List<Double>, item: PlannedItem): List<Double> {
        val first = netRequirements.indexOfFirst { it > 0.0 }
        if (first < 0) return netRequirements.map { 0.0 }
        // The stream is trimmed to its first positive requirement before the rule sees
        // it. @sec-dls-problem assumes inventory starts at zero and period 1 holds an order,
        // so a rule handed a stream that opens with empty periods places a receipt in
        // the first of them and carries it, which costs a setup and a great deal of
        // holding for nothing. The empty periods here are the lead-in the horizon needs,
        // and they are not part of the problem the rule is being asked to solve.
        val plan = rule.plan(item.scheduleFor(netRequirements.drop(first)))
        return netRequirements.indices.map {
            if (it < first) 0.0 else plan.orderIn(it - first + 1)
        }
    }

    override fun toString(): String = rule.name
}

private const val TOLERANCE = 1.0E-9
