package inventory.requirementsplanning

import inventory.dynamiclotsizing.SilverMeal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The record of @sec-mrpdrp-record, pinned against four worked records from the literature.
 *
 * Two are Tersine's, one is Silver, Pyke and Peterson's, and one is the example the
 * course notes work through. They disagree about the lot sizing rule and agree about the
 * arithmetic, which is what a check of the arithmetic wants.
 */
class MrpRecordTest {

    private fun item(policy: ReceiptPolicy, onHand: Double, sr: List<Double>, lead: Int) =
        PlannedItem(id = "X", label = "Test item", leadTime = lead,
            policy = policy, onHand = onHand, scheduledReceipts = sr)

    private fun assertRows(
        r: MrpRecord, net: List<Int>, receipts: List<Int>, onHand: List<Int>, releases: List<Int>,
    ) {
        assertEquals(net, r.netRequirements.map { it.toInt() }, "net requirements")
        assertEquals(receipts, r.plannedOrderReceipts.map { it.toInt() }, "planned order receipts")
        assertEquals(onHand, r.projectedOnHand.map { it.toInt() }, "projected on hand")
        assertEquals(releases, r.plannedOrderReleases.map { it.toInt() }, "planned order releases")
    }

    @Test
    fun `Tersine table 8-3, a fixed order quantity of 25`() {
        val r = MrpRecord(
            item(FixedOrderQuantity(25.0), 10.0, listOf(10.0, 25.0), 2),
            listOf(10.0, 15.0, 25.0, 25.0, 30.0, 45.0, 20.0, 30.0),
        )
        assertRows(r,
            net = listOf(0, 0, 5, 5, 10, 30, 20, 25),
            receipts = listOf(0, 0, 25, 25, 25, 30, 25, 25),
            onHand = listOf(10, 20, 20, 20, 15, 0, 5, 0),
            releases = listOf(25, 25, 25, 30, 25, 25, 0, 0))
    }

    @Test
    fun `Tersine example 4, a fixed order quantity of 15`() {
        val r = MrpRecord(
            item(FixedOrderQuantity(15.0), 20.0, listOf(0.0, 20.0), 2),
            listOf(5.0, 10.0, 18.0, 0.0, 10.0, 6.0, 0.0, 14.0),
        )
        assertRows(r,
            net = listOf(0, 0, 0, 0, 3, 0, 0, 8),
            receipts = listOf(0, 0, 0, 0, 15, 0, 0, 15),
            onHand = listOf(15, 25, 7, 7, 12, 6, 6, 7),
            releases = listOf(0, 0, 15, 0, 0, 15, 0, 0))
    }

    @Test
    fun `the course notes example, lot-for-lot`() {
        val r = MrpRecord(
            item(LotForLotPolicy, 10.0, listOf(10.0, 25.0), 2),
            listOf(10.0, 15.0, 25.0, 25.0, 30.0, 45.0, 20.0, 30.0),
        )
        assertRows(r,
            net = listOf(0, 0, 5, 25, 30, 45, 20, 30),
            receipts = listOf(0, 0, 5, 25, 30, 45, 20, 30),
            onHand = listOf(10, 20, 0, 0, 0, 0, 0, 0),
            releases = listOf(5, 25, 30, 45, 20, 30, 0, 0))
    }

    @Test
    fun `Silver Pyke and Peterson table 15 point 5, lot-for-lot`() {
        val r = MrpRecord(
            item(LotForLotPolicy, 10.0, emptyList(), 2),
            listOf(0.0, 0.0, 50.0, 40.0, 20.0, 0.0, 70.0, 10.0, 60.0),
        )
        assertRows(r,
            net = listOf(0, 0, 40, 40, 20, 0, 70, 10, 60),
            receipts = listOf(0, 0, 40, 40, 20, 0, 70, 10, 60),
            onHand = listOf(10, 10, 0, 0, 0, 0, 0, 0, 0),
            releases = listOf(40, 40, 20, 0, 70, 10, 60, 0, 0))
    }

    @Test
    fun `a Chapter 5 rule needs no MRP-specific version`() {
        val gross = listOf(0.0, 0.0, 50.0, 40.0, 20.0, 0.0, 70.0, 10.0, 60.0)
        val master = PlannedItem(id = "X", label = "Test item", leadTime = 2,
            policy = PlanTheHorizon(SilverMeal), onHand = 10.0,
            orderCost = 300.0, unitCost = 50.0, carryingCharge = 0.02)
        val r = MrpRecord(master, gross)
        val direct = SilverMeal.plan(master.scheduleFor(r.netRequirementsBeforeLotSizing))
        assertEquals(direct.orderPeriods.map { direct.orderIn(it) },
            r.plannedOrderReceipts.filter { it > 0.0 })
    }

    @Test
    fun `safety stock raises the first net requirement and is still held at the end`() {
        val buffered = MrpRecord(
            PlannedItem(id = "X", label = "Test item", leadTime = 1,
                policy = LotForLotPolicy, safetyStock = 5.0),
            listOf(10.0, 20.0, 30.0))
        assertEquals(listOf(15.0, 20.0, 30.0), buffered.netRequirements)
        assertEquals(5.0, buffered.projectedOnHand.last(), 1e-9)
    }

    @Test
    fun `a receipt due inside the lead time cannot be released inside the horizon`() {
        val r = MrpRecord(item(LotForLotPolicy, 0.0, emptyList(), 3),
            listOf(40.0, 0.0, 0.0, 60.0))
        assertEquals(40.0, r.pastDueReleases, 1e-9)
        assertEquals(listOf(60.0, 0.0, 0.0, 0.0), r.plannedOrderReleases)
    }

    @Test
    fun `a record refuses a negative gross requirement`() {
        assertFailsWith<IllegalArgumentException> {
            MrpRecord(item(LotForLotPolicy, 0.0, emptyList(), 1), listOf(10.0, -5.0))
        }
    }
}
