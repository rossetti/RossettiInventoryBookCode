package inventory.multiechelon

import inventory.continuousreview.LeadTimeDemand
import inventory.continuousreview.RQModel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks on the one-for-one recursion that do not depend on anyone's table:
 * limits it must reach, identities it must satisfy, and agreement between the
 * special cases of Axsäter's (6) and the simpler formulas they reduce to.
 */
class OneForOneTwoLevelTest {

    private fun close(got: Double, want: Double, tol: Double = 1e-8) =
        assertTrue(abs(got - want) < tol, "expected $want, got $got")

    private val system = OneForOneTwoLevel(
        storeRate = 1.0, stores = 4, transit = 1.0, hubLead = 3.0,
        storeHolding = 0.5529, hubHolding = 0.5529, shortage = 5.529,
    )

    @Test
    fun `a hub that never runs out leaves each storeroom a chapter 8 base-stock location`() {
        // With S_w far above the hub's lead time demand, the storeroom's lead time
        // is the transit alone, and its cost per unit time is chapter 8's.
        val s = 3
        val store = RQModel(
            demandRate = 1.0, orderCost = 0.0, holdingCost = 0.5529, backorderCost = 5.529,
            leadTimeDemand = LeadTimeDemand.poisson(1.0),
        )
        val perStore = system.storeCostPerItem(s, 200) * system.storeRate
        close(perStore, store.baseStockCost(s), 1e-9)
    }

    @Test
    fun `equation A7 evaluated at S_w equal to zero agrees with equation A8`() {
        // The code uses (A.8) at S_w = 0. (A.7) there is G^0 beta L_w + beta(L_r - S_r/lambda_r),
        // since the S_w term vanishes, and G^0 = 1, so the two branches meet.
        for (sr in -2..0) {
            val viaA7 = system.hubTail(0) * 5.529 * 3.0 + 5.529 * (1.0 - sr / 1.0)
            close(system.storeCostPerItem(sr, 0), viaA7)
        }
    }

    @Test
    fun `the measures reproduce the cost they were split from`() {
        for ((sw, sr) in listOf(0 to 1, 5 to 2, 12 to 3, -2 to 2)) {
            val m = system.measures(sw, sr)
            val recombined = 0.5529 * m.storeOnHand + 0.5529 * m.hubOnHand + 5.529 * m.storeBackorders
            close(recombined, system.cost(sw, sr), 1e-9)
        }
    }

    @Test
    fun `backorders plus on-hand obey the net inventory balance at a storeroom`() {
        // Expected on-hand minus expected backorders is S_r less the expected
        // units on their way to or owed by the hub for one storeroom, which for
        // a hub that never runs out is S_r - lambda_r * L_r.
        val m = system.measures(200, 3)
        close((m.storeOnHand - m.storeBackorders) / 4, 3.0 - 1.0 * 1.0, 1e-9)
    }

    @Test
    fun `equation (6) reduces to equation (1) for one-for-one storerooms`() {
        val batch = AxsaterBatchOrdering(system, storeBatch = 1, hubBatches = 4)
        for (rw in -4..6) {
            for (rr in -1..3) close(batch.general(rw, rr), batch.oneForOneStores(rw, rr), 1e-9)
        }
    }

    @Test
    fun `equation (1) with a hub batch of one is the one-for-one cost`() {
        val batch = AxsaterBatchOrdering(system, storeBatch = 1, hubBatches = 1)
        for (rw in -1..8) close(batch.cost(rw, 2), system.cost(rw + 1, 3), 1e-12)
    }
}
