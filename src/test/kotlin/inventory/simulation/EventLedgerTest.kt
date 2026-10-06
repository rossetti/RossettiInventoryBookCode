package inventory.simulation

import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @exm-sim-ledger: the storeroom ledger of @exm-rq-ledger replayed through the
 * model. Nothing is random once the demand times are given, so the trace must
 * equal @tbl-sim-ledger exactly, row for row, areas included.
 */
class EventLedgerTest {

    private data class Row(val week: Double, val onHand: Int, val backordered: Int, val position: Int,
                           val areaOnHand: Double, val areaBackordered: Double, val timeWithStock: Double)

    /** @tbl-sim-ledger, as printed. */
    private val ledger = listOf(
        Row(1.0, 5, 0, 5, 6.0, 0.0, 1.0),
        Row(2.0, 4, 0, 9, 11.0, 0.0, 2.0),
        Row(3.0, 3, 0, 8, 15.0, 0.0, 3.0),
        Row(5.0, 2, 0, 7, 21.0, 0.0, 5.0),
        Row(6.0, 1, 0, 6, 23.0, 0.0, 6.0),
        Row(7.0, 0, 0, 5, 24.0, 0.0, 7.0),
        Row(8.0, 0, 1, 9, 24.0, 0.0, 7.0),
        Row(9.0, 0, 2, 8, 24.0, 1.0, 7.0),
        Row(10.0, 3, 0, 8, 24.0, 3.0, 7.0),
        Row(11.0, 2, 0, 7, 27.0, 3.0, 8.0),
        Row(13.0, 1, 0, 6, 31.0, 3.0, 10.0),
    )

    private fun run(): List<SimInventory.Snapshot> {
        val m = Model("EventLedger")
        val supplier = LeadTimeSupplier(m, ConstantRV(8.0), name = "Supplier")
        val store = RQSimInventory(m, reorderPoint = 4, orderQuantity = 5, initialOnHand = 6,
            filler = supplier, name = "Transformer")
        ScriptedDemand(m, store, listOf(1.0, 2.0, 3.0, 5.0, 6.0, 7.0, 8.0, 9.0, 11.0, 13.0), name = "Demands")
        val rows = mutableListOf<SimInventory.Snapshot>()
        store.tracer = { rows += it }
        m.numberOfReplications = 1
        m.lengthOfReplication = 13.5          // past the last demand at week 13
        m.simulate()
        return rows
    }

    @Test
    fun `the trace reproduces the event ledger row for row`() {
        val rows = run()
        assertEquals(ledger.size, rows.size, "one row per event")
        for ((want, got) in ledger.zip(rows)) {
            assertEquals(want.week, got.time, 1e-12, "time")
            assertEquals(want.onHand, got.onHand, "I at week ${want.week}")
            assertEquals(want.backordered, got.backordered, "B at week ${want.week}")
            assertEquals(want.position, got.position, "IP at week ${want.week}")
            assertEquals(want.areaOnHand, got.areaOnHand, 1e-9, "A_I at week ${want.week}")
            assertEquals(want.areaBackordered, got.areaBackordered, 1e-9, "A_B at week ${want.week}")
            assertEquals(want.timeWithStock, got.timeWithStock, 1e-9, "A_+ at week ${want.week}")
        }
    }

    @Test
    fun `the events and orders are the ledger's`() {
        val events = run().map { it.event }
        assertEquals("demand, order 5", events[1])
        assertEquals("demand backordered, order 5", events[6])
        assertEquals("receipt of 5", events[8])
    }

    @Test
    fun `the averages over thirteen weeks are those of the example`() {
        val last = run().last()
        assertEquals(31.0 / 13.0, last.areaOnHand / 13.0, 1e-12)
        assertEquals(3.0 / 13.0, last.areaBackordered / 13.0, 1e-12)
        assertEquals(10.0 / 13.0, last.timeWithStock / 13.0, 1e-12)
    }
}
