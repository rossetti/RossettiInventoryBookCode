package inventory.requirementsplanning

import inventory.dynamiclotsizing.AdjustedEconomicOrderQuantity
import inventory.dynamiclotsizing.LotForLot
import inventory.dynamiclotsizing.SilverMeal
import inventory.dynamiclotsizing.WagnerWhitin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BillOfMaterialTest {

    private fun item(id: String) = PlannedItem(id, id, 1, LotForLotPolicy)

    @Test
    fun `an item used at two levels takes the deeper code`() {
        val bom = TurfEquipment.bom()
        assertEquals(0, bom.lowLevelCode("DA"))
        assertEquals(1, bom.lowLevelCode("B"))
        assertEquals(1, bom.lowLevelCode("S"))
        assertEquals(2, bom.lowLevelCode("H"))
        // The bearing is used directly by the deck assembly, which would put it at
        // level 1, and again inside the spindle assembly, which puts it at level 2.
        assertEquals(2, bom.lowLevelCode("BR"))
        assertEquals(listOf("DA"), bom.endItems)
    }

    @Test
    fun `the planning order puts every parent before every child`() {
        val bom = TurfEquipment.bom()
        val order = bom.planningOrder()
        for (usage in bom.usages) {
            assertTrue(order.indexOf(usage.parent) < order.indexOf(usage.child),
                "${usage.parent} must be planned before ${usage.child}")
        }
    }

    @Test
    fun `a cycle is refused at construction`() {
        assertFailsWith<IllegalArgumentException> {
            BillOfMaterial(listOf(item("A"), item("B")), listOf(
                BillOfMaterial.Usage("A", "B", 1.0),
                BillOfMaterial.Usage("B", "A", 1.0)))
        }
    }

    @Test
    fun `a parent cannot list the same child twice`() {
        assertFailsWith<IllegalArgumentException> {
            BillOfMaterial(listOf(item("A"), item("B")), listOf(
                BillOfMaterial.Usage("A", "B", 1.0),
                BillOfMaterial.Usage("A", "B", 2.0)))
        }
    }

    @Test
    fun `the horizon must lead the first requirement by the cumulative lead time`() {
        // DA is one month, the spindle assembly one, the bearing three, so the deepest
        // branch is five. Four is not enough and five is.
        fun pastDue(pad: Int) = RequirementsPlan(TurfEquipment.bom(),
            mapOf("DA" to List(pad) { 0.0 } + TurfEquipment.masterSchedule)).pastDueReleases
        assertEquals(520.0, pastDue(3), 1e-9)
        assertEquals(160.0, pastDue(4), 1e-9)
        assertEquals(0.0, pastDue(5), 1e-9)
        assertEquals(0.0, pastDue(6), 1e-9)
    }

    @Test
    fun `a component's gross requirements are its parents' releases times the quantity per`() {
        val mps = List(5) { 0.0 } + TurfEquipment.masterSchedule
        val plan = RequirementsPlan(TurfEquipment.bom(LotForLot), mapOf("DA" to mps))
        val da = plan.recordFor("DA")
        val s = plan.recordFor("S")
        val bearing = plan.recordFor("BR")
        for (t in 1..plan.horizon) {
            assertEquals(2.0 * da.releaseIn(t), s.gross(t), 1e-9, "spindle assembly in $t")
            // The bearing has two parents, at two different levels.
            assertEquals(1.0 * da.releaseIn(t) + 2.0 * s.releaseIn(t), bearing.gross(t), 1e-9,
                "bearing in $t")
        }
    }

    @Test
    fun `lot-for-lot at the end item passes its schedule through unchanged`() {
        val mps = List(5) { 0.0 } + TurfEquipment.masterSchedule
        val plan = RequirementsPlan(TurfEquipment.bom(LotForLot), mapOf("DA" to mps))
        val da = plan.recordFor("DA")
        assertEquals(mps, (1..plan.horizon).map { da.receiptIn(it) })
    }

    @Test
    fun `the rule that is best at the end item is not the rule that is best overall`() {
        // @sec-mrpdrp-lotsizing. Wagner-Whitin is optimal AT the deck assembly, by construction.
        // Silver-Meal gives up 76 dollars there and saves 800 below it.
        val mps = List(5) { 0.0 } + TurfEquipment.masterSchedule
        fun totals(top: inventory.dynamiclotsizing.LotSizingRule): Pair<Double, Double> {
            val base = TurfEquipment.bom(LotForLot)
            val bom = BillOfMaterial(base.items.values.map {
                PlannedItem(it.id, it.label, it.leadTime,
                    if (it.id == "DA") PlanTheHorizon(top) else LotForLotPolicy,
                    it.onHand, it.scheduledReceipts, it.safetyStock,
                    it.orderCost, it.unitCost, it.carryingCharge)
            }, base.usages)
            val p = RequirementsPlan(bom, mapOf("DA" to mps))
            return p.recordFor("DA").relevantCost to p.relevantCost
        }
        val (wwTop, wwAll) = totals(WagnerWhitin)
        val (bestTop, bestAll) = totals(AdjustedEconomicOrderQuantity())
        assertEquals(4860.0, wwTop, 1e-9)
        assertEquals(5588.0, bestTop, 1e-9)
        assertTrue(wwTop < bestTop, "Wagner-Whitin is optimal at the deck assembly")
        assertEquals(12360.0, wwAll, 1e-9)
        assertEquals(11488.0, bestAll, 1e-9)
        assertTrue(bestAll < wwAll, "and it is not optimal for the product")
        assertEquals(7.59, 100.0 * (wwAll / bestAll - 1.0), 0.01)
    }

    @Test
    fun `the cost below a level rises with how often the level above orders`() {
        // @sec-mrpdrp-lotsizing's mechanism. Every release a parent makes is a requirement for
        // its components, so the components order more often too.
        val mps = List(5) { 0.0 } + TurfEquipment.masterSchedule
        val seen = sortedMapOf<Int, Double>()
        for (top in listOf(LotForLot, AdjustedEconomicOrderQuantity(), SilverMeal, WagnerWhitin)) {
            val base = TurfEquipment.bom(LotForLot)
            val bom = BillOfMaterial(base.items.values.map {
                PlannedItem(it.id, it.label, it.leadTime,
                    if (it.id == "DA") PlanTheHorizon(top) else LotForLotPolicy,
                    it.onHand, it.scheduledReceipts, it.safetyStock,
                    it.orderCost, it.unitCost, it.carryingCharge)
            }, base.usages)
            val p = RequirementsPlan(bom, mapOf("DA" to mps))
            val da = p.recordFor("DA")
            seen[da.orderCount] = p.relevantCost - da.relevantCost
        }
        assertEquals(listOf(7, 8, 9, 11), seen.keys.toList())
        assertEquals(listOf(5900.0, 6700.0, 7500.0, 9000.0), seen.values.toList())
        assertTrue(seen.values.zipWithNext().all { (a, b) -> a < b },
            "cost below the end item must rise with the end item's order count")
    }
}
