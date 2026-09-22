package inventory.requirementsplanning

import inventory.dynamiclotsizing.LotForLot
import inventory.dynamiclotsizing.LotSizingRule

/**
 * The product structure Chapter 6 runs on, and the master schedule that drives it.
 *
 * Chapter 5 planned one mower deck belt for a distributor. Chapter 6 moves one step up
 * the chain, to the manufacturer who builds the deck the belt goes into, and the belt
 * becomes a component instead of an end item. The master schedule keeps the shape of
 * Chapter 5's demand, because it is the same season driving both.
 *
 * The bearing is used twice, directly by the deck assembly and again inside the spindle
 * assembly, which is what gives low-level coding something to do.
 */
object TurfEquipment {

    val masterSchedule: List<Double> =
        listOf(40.0, 60.0, 120.0, 300.0, 420.0, 260.0, 120.0, 40.0, 0.0, 80.0, 180.0, 220.0)

    /** Costs per item: order cost, unit cost, carrying charge per period. */
    private data class Costs(val k: Double, val c: Double, val i: Double)

    private val costs = mapOf(
        "DA" to Costs(500.0, 180.0, 0.02),
        "B" to Costs(300.0, 50.0, 0.02),      // the belt of Chapter 5, unchanged
        "S" to Costs(250.0, 25.0, 0.03),      // the spindle assembly of chapter 5
        "H" to Costs(150.0, 12.0, 0.02),
        "BR" to Costs(100.0, 4.0, 0.02),
    )
    private val leadTimes = mapOf("DA" to 1, "B" to 2, "S" to 1, "H" to 2, "BR" to 3)
    private val labels = mapOf(
        "DA" to "Mower deck assembly", "B" to "Deck belt", "S" to "Spindle assembly",
        "H" to "Spindle housing", "BR" to "Bearing",
    )

    val usages: List<BillOfMaterial.Usage> = listOf(
        BillOfMaterial.Usage("DA", "B", 1.0),
        BillOfMaterial.Usage("DA", "S", 2.0),
        BillOfMaterial.Usage("DA", "BR", 1.0),
        BillOfMaterial.Usage("S", "H", 1.0),
        BillOfMaterial.Usage("S", "BR", 2.0),
    )

    /** Every item planned with [rule], which is the comparison of @sec-mrpdrp-lotsizing. */
    fun bom(
        rule: LotSizingRule = LotForLot,
        onHand: Map<String, Double> = emptyMap(),
        scheduled: Map<String, List<Double>> = emptyMap(),
    ): BillOfMaterial = BillOfMaterial(
        costs.keys.map { id ->
            val c = costs.getValue(id)
            PlannedItem(
                id = id, label = labels.getValue(id), leadTime = leadTimes.getValue(id),
                policy = PlanTheHorizon(rule),
                onHand = onHand[id] ?: 0.0,
                scheduledReceipts = scheduled[id] ?: emptyList(),
                orderCost = c.k, unitCost = c.c, carryingCharge = c.i,
            )
        },
        usages,
    )

    fun plan(rule: LotSizingRule = LotForLot): RequirementsPlan =
        RequirementsPlan(bom(rule), mapOf("DA" to masterSchedule))
}
