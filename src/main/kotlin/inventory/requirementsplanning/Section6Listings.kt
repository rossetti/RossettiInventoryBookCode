package inventory.requirementsplanning

import inventory.dynamiclotsizing.AdjustedEconomicOrderQuantity
import inventory.dynamiclotsizing.LotForLot
import inventory.dynamiclotsizing.LotSizingRule
import inventory.dynamiclotsizing.LeastUnitCost
import inventory.dynamiclotsizing.PartPeriodBalancing
import inventory.dynamiclotsizing.PeriodicOrderQuantity
import inventory.dynamiclotsizing.SilverMeal
import inventory.dynamiclotsizing.WagnerWhitin

/**
 * Generates every figure printed in Chapter 6.
 *
 * Regenerate with `scripts/chapter6-listings.sh` after any change that could move one.
 */
private const val PAD = 5                       // the cumulative lead time through BR

private fun variabilityCoefficient(d: List<Double>): Double {
    val mean = d.average()
    return d.sumOf { (it - mean) * (it - mean) } / d.size / (mean * mean)
}

private fun masterSchedule() = List(PAD) { 0.0 } + TurfEquipment.masterSchedule
private fun scheduledMonths() = (PAD + 1)..(PAD + TurfEquipment.masterSchedule.size)

/** Every component on lot-for-lot, so only the end item's rule varies. @sec-mrpdrp-lotsizing. */
private fun onlyTheTopVaries(top: LotSizingRule): BillOfMaterial {
    val base = TurfEquipment.bom(LotForLot)
    return BillOfMaterial(
        base.items.values.map {
            PlannedItem(it.id, it.label, it.leadTime,
                if (it.id in base.endItems) PlanTheHorizon(top) else LotForLotPolicy,
                it.onHand, it.scheduledReceipts, it.safetyStock,
                it.orderCost, it.unitCost, it.carryingCharge)
        },
        base.usages,
    )
}

fun main() {
    fun rule(title: String) { println(); println("### $title") }
    val mps = masterSchedule()

    rule("6.2 the product structure")
    val bom = TurfEquipment.bom()
    println("  end items        %s".format(bom.endItems))
    println("  low-level codes  %s".format(bom.lowLevelCodes.toSortedMap()))
    println("  planning order   %s".format(bom.planningOrder()))
    for (u in bom.usages) println("    %-3s uses %.0f of %-3s".format(u.parent, u.quantity, u.child))

    rule("6.3 how far the horizon must lead the first requirement")
    for (lead in 3..6) {
        val p = RequirementsPlan(TurfEquipment.bom(),
            mapOf("DA" to List(lead) { 0.0 } + TurfEquipment.masterSchedule))
        println("  %d empty months in front, horizon %2d: %.0f units past due"
            .format(lead, lead + TurfEquipment.masterSchedule.size, p.pastDueReleases))
    }

    rule("6.4 the explosion, every item on lot-for-lot")
    val plan = RequirementsPlan(TurfEquipment.bom(LotForLot), mapOf("DA" to mps))
    for (id in plan.bom.planningOrder()) {
        val r = plan.recordFor(id)
        println("  %-3s llc %d  L=%d".format(id, plan.bom.lowLevelCode(id), r.item.leadTime))
        println("      gross    %s".format(r.grossRequirements.map { it.toInt() }))
        println("      releases %s".format(r.plannedOrderReleases.map { it.toInt() }))
    }
    println("  total relevant cost %.2f, past due %.0f".format(plan.relevantCost, plan.pastDueReleases))

    rule("6.5 what the end item's rule does to everything below it")
    println("  Every component stays on lot-for-lot. Only the deck assembly's rule changes.")
    println("  %-32s %6s %8s %9s %9s %9s".format(
        "rule on the deck assembly", "orders", "VC", "at DA", "below", "total"))
    val rules = listOf(LotForLot, AdjustedEconomicOrderQuantity(), PeriodicOrderQuantity(),
        LeastUnitCost, PartPeriodBalancing, SilverMeal, WagnerWhitin)
    var bestTop = Double.MAX_VALUE; var bestTopTotal = 0.0; var bestTotal = Double.MAX_VALUE
    for (r in rules) {
        val p = RequirementsPlan(onlyTheTopVaries(r), mapOf("DA" to mps))
        val da = p.recordFor("DA")
        val vc = variabilityCoefficient(scheduledMonths().map { da.receiptIn(it) })
        println("  %-32s %6d %8.4f %9.2f %9.2f %9.2f".format(
            r.name, da.orderCount, vc, da.relevantCost, p.relevantCost - da.relevantCost,
            p.relevantCost))
        if (da.relevantCost < bestTop) { bestTop = da.relevantCost; bestTopTotal = p.relevantCost }
        if (p.relevantCost < bestTotal) bestTotal = p.relevantCost
    }
    println("  the master schedule's own VC is %.4f".format(
        variabilityCoefficient(TurfEquipment.masterSchedule)))
    println("  choosing the rule on the end item alone costs %.2f, or %.2f%%"
        .format(bestTopTotal - bestTotal, 100.0 * (bestTopTotal / bestTotal - 1.0)))

    drpListing()
}

/** @sec-mrpdrp-drp, run separately so the MRP listing stays readable. */
fun drpListing() {
    println(); println("### 6.6 the distribution network")
    val bom = BeltNetwork.network()
    println("  low-level codes %s   planning order %s".format(
        bom.lowLevelCodes.toSortedMap(), bom.planningOrder()))
    println("  cumulative lead time %d months".format(BeltNetwork.cumulativeLeadTime))
    val p = BeltNetwork.plan()
    for (id in bom.planningOrder()) {
        val r = p.recordFor(id)
        println("  %-3s L=%d  gross    %s".format(id, r.item.leadTime,
            r.grossRequirements.map { it.toInt() }))
        println("           releases %s".format(r.plannedOrderReleases.map { it.toInt() }))
    }
    val regions = BeltNetwork.regionDemand.keys
    val total = (1..p.horizon).map { t -> regions.sumOf { p.recordFor(it).gross(t) } }
    val cw = (1..p.horizon).map { t -> p.recordFor("CW").gross(t) }
    fun vc(d: List<Double>): Double {
        val m = d.average(); return d.sumOf { (it - m) * (it - m) } / d.size / (m * m)
    }
    val w = (BeltNetwork.cumulativeLeadTime + 1)..p.horizon
    println("  VC of what the regions SELL      %.4f".format(vc(w.map { total[it - 1] })))
    println("  VC of what the warehouse SEES    %.4f".format(vc(w.map { cw[it - 1] })))
    println("  past due %.0f, total relevant cost %.2f".format(p.pastDueReleases, p.relevantCost))

    println()
    println("  What the regions' own rule does to the warehouse:")
    println("  %-32s %9s %9s %9s %9s".format(
        "rule at every region", "VC at CW", "regions", "centre", "total"))
    for (r in listOf(inventory.dynamiclotsizing.LotForLot,
                     inventory.dynamiclotsizing.SilverMeal,
                     inventory.dynamiclotsizing.PartPeriodBalancing,
                     inventory.dynamiclotsizing.PeriodicOrderQuantity(),
                     inventory.dynamiclotsizing.WagnerWhitin)) {
        val q = BeltNetwork.plan(regionRule = r)
        val seen = (1..q.horizon).map { t -> q.recordFor("CW").gross(t) }
        val centre = q.recordFor("CW").relevantCost
        println("  %-32s %9.4f %9.2f %9.2f %9.2f".format(
            r.name, vc(w.map { seen[it - 1] }), q.relevantCost - centre, centre, q.relevantCost))
    }
}
