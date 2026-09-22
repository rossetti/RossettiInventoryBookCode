package inventory.requirementsplanning

import inventory.dynamiclotsizing.AdjustedEconomicOrderQuantity
import inventory.dynamiclotsizing.LeastUnitCost
import inventory.dynamiclotsizing.LotForLot
import inventory.dynamiclotsizing.LotSizingRule
import inventory.dynamiclotsizing.PartPeriodBalancing
import inventory.dynamiclotsizing.PeriodicOrderQuantity
import inventory.dynamiclotsizing.SilverMeal
import inventory.dynamiclotsizing.WagnerWhitin
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every figure Chapter 6 prints, pinned to the cell.
 *
 * The other tests in this package check properties: that a low-level code is what it
 * should be, that gross requirements are the explosion of the level above, that the
 * cost below a level rises with how often the level above orders. Those pass whatever
 * the numbers happen to be. The chapter also prints numbers, and a printed number that
 * nothing asserts is a number that can drift away from the code that is supposed to
 * produce it. That has already happened once in this book, to @tbl-dls-rollingstudy, which went to
 * press disagreeing with the program named as its source.
 *
 * So this file asserts the tables themselves. If a change moves one, the failure names
 * the table to go and edit.
 */
class ChapterFiguresTest {

    private val pad = 5
    private val mps = List(pad) { 0.0 } + TurfEquipment.masterSchedule
    private val scheduled = (pad + 1)..(pad + TurfEquipment.masterSchedule.size)

    private fun variabilityCoefficient(d: List<Double>): Double {
        val mean = d.average()
        return d.sumOf { (it - mean) * (it - mean) } / d.size / (mean * mean)
    }

    /** Only the deck assembly's rule varies. Every component stays on lot-for-lot. */
    private fun onlyTheTopVaries(top: LotSizingRule, componentRule: LotSizingRule? = null):
        BillOfMaterial {
        val base = TurfEquipment.bom()
        return BillOfMaterial(
            base.items.values.map {
                PlannedItem(it.id, it.label, it.leadTime,
                    if (it.id == "DA") PlanTheHorizon(top)
                    else componentRule?.let { r -> PlanTheHorizon(r) } ?: LotForLotPolicy,
                    it.onHand, it.scheduledReceipts, it.safetyStock,
                    it.orderCost, it.unitCost, it.carryingCharge)
            },
            base.usages,
        )
    }

    private val rules = listOf(LotForLot, AdjustedEconomicOrderQuantity(),
        PeriodicOrderQuantity(), LeastUnitCost, PartPeriodBalancing, SilverMeal, WagnerWhitin)

    @Test
    fun `Section 6 point 2, the structure of the deck assembly`() {
        val bom = TurfEquipment.bom()
        assertEquals(listOf("DA"), bom.endItems)
        assertEquals(mapOf("DA" to 0, "B" to 1, "S" to 1, "BR" to 2, "H" to 2),
            bom.lowLevelCodes)
        assertEquals(listOf("DA", "B", "S", "BR", "H"), bom.planningOrder())
    }

    @Test
    fun `Table 6 point 6, the bearing's gross requirements`() {
        val plan = RequirementsPlan(TurfEquipment.bom(LotForLot), mapOf("DA" to mps))
        assertEquals(
            listOf(0, 0, 0, 160, 280, 540, 1320, 1980, 1460, 740, 280, 40, 320, 800, 1060, 220, 0),
            plan.recordFor("BR").grossRequirements.map { it.toInt() })
    }

    @Test
    fun `Section 6 point 4, the explosion costs 14500 in 57 orders and carries nothing`() {
        val plan = RequirementsPlan(TurfEquipment.bom(LotForLot), mapOf("DA" to mps))
        val orders = plan.bom.planningOrder().sumOf { plan.recordFor(it).orderCount }
        assertEquals(57, orders)
        assertEquals(14500.0, plan.records.values.sumOf { it.setupCost }, 1e-9)
        assertEquals(0.0, plan.records.values.sumOf { it.carryingCost }, 1e-9)
        assertEquals(0.0, plan.pastDueReleases, 1e-9)
    }

    @Test
    fun `Table 6 point 4, how far the horizon must lead the first requirement`() {
        val want = mapOf(3 to 520.0, 4 to 160.0, 5 to 0.0, 6 to 0.0)
        for ((lead, past) in want) {
            val plan = RequirementsPlan(TurfEquipment.bom(),
                mapOf("DA" to List(lead) { 0.0 } + TurfEquipment.masterSchedule))
            assertEquals(past, plan.pastDueReleases, 1e-9, "a lead-in of $lead months")
        }
    }

    @Test
    fun `Table 6 point 7, what the end item's rule does to everything below it`() {
        // rule to orders, VC at the deck assembly, cost there, cost below, total.
        val want = listOf(
            Row("Lot-for-lot", 11, 0.6191, 5500.0, 9000.0),
            Row("Adjusted economic order quantity", 7, 0.8459, 5588.0, 5900.0),
            Row("Periodic order quantity", 11, 0.6191, 5500.0, 9000.0),
            Row("Least unit cost", 7, 1.0274, 5588.0, 6100.0),
            Row("Part-period balancing", 7, 1.5463, 5444.0, 6100.0),
            Row("Silver-Meal", 8, 0.9650, 4936.0, 6700.0),
            Row("Wagner-Whitin", 9, 0.6701, 4860.0, 7500.0),
        )
        for ((rule, row) in rules.zip(want)) {
            assertEquals(row.name, rule.name)
            val plan = RequirementsPlan(onlyTheTopVaries(rule), mapOf("DA" to mps))
            val da = plan.recordFor("DA")
            assertEquals(row.orders, da.orderCount, "${row.name}, orders")
            assertEquals(row.vc, variabilityCoefficient(scheduled.map { da.receiptIn(it) }), 1e-4,
                "${row.name}, variability coefficient")
            assertEquals(row.atTop, da.relevantCost, 1e-9, "${row.name}, cost at the deck assembly")
            assertEquals(row.below, plan.relevantCost - da.relevantCost, 1e-9,
                "${row.name}, cost below")
        }
    }

    @Test
    fun `Section 6 point 5, choosing the rule on the end item alone costs 7 point 59 percent`() {
        val plans = rules.map { RequirementsPlan(onlyTheTopVaries(it), mapOf("DA" to mps)) }
        val byTop = plans.minByOrNull { it.recordFor("DA").relevantCost }!!
        val best = plans.minOf { it.relevantCost }
        assertEquals(4860.0, byTop.recordFor("DA").relevantCost, 1e-9)
        assertEquals(12360.0, byTop.relevantCost, 1e-9)
        assertEquals(11488.0, best, 1e-9)
        assertEquals(7.59, 100.0 * (byTop.relevantCost / best - 1.0), 5e-3)
    }

    @Test
    fun `Table 6 point 8, the centre's gross requirements are the regions' releases`() {
        val plan = BeltNetwork.plan()
        assertEquals(mapOf("N" to 0, "S" to 0, "W" to 0, "CW" to 1), plan.bom.lowLevelCodes)
        assertEquals(4, BeltNetwork.cumulativeLeadTime)
        assertEquals(
            listOf(0, 0, 10, 50, 80, 180, 360, 360, 210, 80, 30, 20, 120, 190, 150, 0),
            plan.recordFor("CW").grossRequirements.map { it.toInt() })
        assertEquals(0.0, plan.pastDueReleases, 1e-9)
    }

    /** The periods from the centre's first positive requirement to its last, the
     *  window @tbl-mrp-bullwhip measures over. */
    private fun centreSpan(centre: MrpRecord): IntRange {
        val g = (1..centre.horizon).map { centre.gross(it) }
        val first = g.indexOfFirst { it > 0.0 } + 1
        val last = g.indexOfLast { it > 0.0 } + 1
        return first..last
    }

    @Test
    fun `Section 6 point 6, the network alone barely changes what the centre sees`() {
        val plan = BeltNetwork.plan()
        val window = (BeltNetwork.cumulativeLeadTime + 1)..plan.horizon
        val sold = window.map { t -> BeltNetwork.regionDemand.keys.sumOf { plan.recordFor(it).gross(t) } }
        assertEquals(TurfEquipment.masterSchedule, sold)
        assertEquals(0.6191, variabilityCoefficient(sold), 1e-4)
        // The centre over every period in which it has a requirement, 3 through 15.
        val centre = plan.recordFor("CW")
        val span = centreSpan(centre)
        assertEquals(3..15, span)
        println("centre lot-for-lot VC over $span: %.4f".format(variabilityCoefficient(span.map { centre.gross(it) })))
        assertEquals(0.6334, variabilityCoefficient(span.map { centre.gross(it) }), 1e-4)
    }

    @Test
    fun `Table 6 point 9, what the regions' own rule does to the centre`() {
        val want = listOf(
            Bullwhip("Lot-for-lot", 0.6334, regions = 6600.0, centre = 5200.0),
            Bullwhip("Silver-Meal", 1.3715, regions = 3980.0, centre = 3600.0),
            Bullwhip("Part-period balancing", 0.8692, regions = 3860.0, centre = 3200.0),
            Bullwhip("Periodic order quantity", 1.5896, regions = 4310.0, centre = 3200.0),
            Bullwhip("Wagner-Whitin", 1.1403, regions = 3830.0, centre = 3200.0),
        )
        val order = listOf(LotForLot, SilverMeal, PartPeriodBalancing,
            PeriodicOrderQuantity(), WagnerWhitin)
        // Every row is measured over the same window, the periods in which the
        // centre has a requirement under lot-for-lot, 3 through 15. A batching
        // rule that empties one of those periods is charged the zero.
        val span = centreSpan(BeltNetwork.plan().recordFor("CW"))
        assertEquals(3..15, span)
        for (rule in order) {
            val centre = BeltNetwork.plan(regionRule = rule).recordFor("CW")
            println("%-24s VC over %s: %.4f".format(rule.name, span, variabilityCoefficient(span.map { centre.gross(it) })))
        }
        for ((rule, row) in order.zip(want)) {
            assertEquals(row.name, rule.name)
            val plan = BeltNetwork.plan(regionRule = rule)
            val centre = plan.recordFor("CW")
            assertEquals(row.vc, variabilityCoefficient(span.map { centre.gross(it) }), 1e-4,
                "${row.name}, variability coefficient at the centre")
            assertEquals(row.regions, plan.relevantCost - centre.relevantCost, 1e-9,
                "${row.name}, cost at the regions")
            assertEquals(row.centre, centre.relevantCost, 1e-9, "${row.name}, cost at the centre")
            assertEquals(0.0, plan.pastDueReleases, 1e-9, "${row.name}, past due")
        }
    }

    private data class Bullwhip(
        val name: String, val vc: Double, val regions: Double, val centre: Double,
    )

    private data class Row(
        val name: String, val orders: Int, val vc: Double, val atTop: Double, val below: Double,
    )
}
