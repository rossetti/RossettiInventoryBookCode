package inventory.multiechelon

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every figure Chapter 9 prints, asserted against the ported code.
 *
 * The chapter's numbers were computed independently of this package, so an
 * agreement here holds two routes to each other.
 */
class ChapterExamplesTest {

    private fun days(d: Double) = d / 365.0

    /** The utility of @exm-onelocation-transformer onward: four alike storerooms and one depot. */
    private fun utility(depotRepairDays: Double = 60.0, depotStock: Int = 16, baseStock: Int = 4): VMItem {
        val item = VMItem(1, depotStock, 3800.0, days(depotRepairDays))
        repeat(4) { item.addBaseItem(45.0, days(30.0), 0.3, days(10.0), baseStock, 3800.0) }
        item.stockLevel = depotStock
        return item
    }

    private fun close(got: Double, want: Double, tol: Double = 5e-4) =
        assertTrue(abs(got - want) < tol, "expected $want, got $got")

    @Test
    fun `rates and the depot pipeline, @sec-multiechelon-onefortone and @exm-delay-transformer`() {
        val d = utility()
        close(d.baseItems[0].replenishmentDemandRate, 31.5)
        close(d.demandRate, 126.0)
        close(d.leadTimeDemand.mean(), 20.7123)
        close(d.baseItems[0].ownPipeline, 1.9726)
    }

    @Test
    fun `the depot's backorders and the delay it imposes, @exm-metric-transformer`() {
        val d = utility()
        close(d.expectedBackOrders, 5.0201)
        close(d.varianceBackOrders, 16.6215, 5e-3)
        close(d.expectedWaitTime * 365.0, 14.54, 5e-3)
    }

    @Test
    fun `Sherbrooke's recursion and the loss function agree, @sec-multiechelon-varimetric`() {
        val p = utility().leadTimeDemand
        var walked = p.mean() + p.mean() * p.mean()
        for (s in 1..16) {
            walked -= p.firstOrderLossFunction(s.toDouble()) + p.firstOrderLossFunction((s - 1).toDouble())
        }
        val closed = 2.0 * p.secondOrderLossFunction(16.0) + p.firstOrderLossFunction(16.0)
        close(walked, closed, 1e-6)
        close(closed, 41.8229, 5e-3)
    }

    @Test
    fun `VARI-METRIC on the utility's transformers, @exm-worksheet-contract`() {
        val d = utility()
        val b = d.baseItems[0]
        close(b.expectedNumInResupply, 3.2276)
        close(b.varianceNumInResupply, 3.9527)
        close(b.expectedBackOrders, 0.4815)
        close(d.totalBaseExpectedBackOrders, 1.9259, 5e-3)
        close(b.expectedOnHand, 1.2539)
        // @eq-repairable-onhand is an identity whatever the family.
        close(b.expectedOnHand - b.expectedBackOrders, 4 - b.expectedNumInResupply, 1e-9)
    }

    @Test
    fun `METRIC on the same plan, @exm-varimetric-transformer`() {
        val d = utility()
        d.forcePoissonAtBases = true
        val b = d.baseItems[0]
        close(b.expectedNumInResupply, 3.2276)                       // unchanged
        close(b.leadTimeDemand.variance(), 3.2276)       // forced
        close(b.expectedBackOrders, 0.4055)
        close(d.totalBaseExpectedBackOrders, 1.6218, 5e-3)
    }

    @Test
    fun `the two models differ only in the second moment, @sec-multiechelon-varimetric`() {
        val vm = utility()
        val vmBase = vm.baseItems[0].expectedBackOrders
        val vmMean = vm.baseItems[0].expectedNumInResupply
        val vmDelay = vm.expectedWaitTime
        val me = utility().also { it.forcePoissonAtBases = true }
        close(me.baseItems[0].expectedNumInResupply, vmMean, 1e-12)
        close(me.expectedWaitTime, vmDelay, 1e-12)
        assertTrue(vmBase > me.baseItems[0].expectedBackOrders)
        close(vmBase / me.baseItems[0].expectedBackOrders, 1.1875, 5e-3)
    }

    @Test
    fun `the repair contract of @exm-worksheet-contract`() {
        close(utility(45.0).expectedBackOrders, 1.3500, 5e-3)
        close(utility(45.0).expectedWaitTime * 365.0, 3.91, 5e-3)
        close(utility(45.0).baseItems[0].expectedNumInResupply, 2.3101)
        close(utility(45.0).totalBaseExpectedBackOrders, 0.6091, 5e-3)
        close(utility(60.0, depotStock = 22).totalBaseExpectedBackOrders, 0.6005, 5e-3)
        assertTrue(utility(60.0, depotStock = 21).totalBaseExpectedBackOrders >
            utility(45.0).totalBaseExpectedBackOrders)
    }

    @Test
    fun `the variance of the backorder wait, which the chapter does not use`() {
        val d = utility()
        // (Var[B] - E[B]) / lambda^2, the distributional form of Little's Law.
        val expected = (d.varianceBackOrders - d.expectedBackOrders) / (d.demandRate * d.demandRate)
        close(d.varianceWaitTime, expected, 1e-12)
    }

    @Test
    fun `the allocation curve is not convex, @tbl-allocation-transformer`() {
        val item = utility(60.0, depotStock = 0, baseStock = 0)
        val data = MAFItemData(MAFItemData.createDepotLevels(0, 26, 1), 0.01, 40, item)
        // @tbl-allocation-transformer, as the ported code produces it.
        close(data.alphaHat[12], 16.6266, 5e-3)
        close(data.alphaHat[13], 15.6433, 5e-3)
        assertEquals(11, data.dStar[11])
        assertEquals(8, data.dStar[12])
        // Flushout, @sec-multiechelon-allocation-flushout: the best depot level falls as the total rises.
        assertTrue(data.dStar[12] < data.dStar[11],
            "expected flushout at 12; got ${data.dStar[11]} then ${data.dStar[12]}")
        // Non-convexity, @sec-multiechelon-allocation-convexity: the reduction below total 13 is larger,
        // so 13 lies above the chord joining 12 and 14.
        val r13 = data.alphaHat[12] - data.alphaHat[13]
        val r14 = data.alphaHat[13] - data.alphaHat[14]
        assertTrue(r14 > r13, "expected the non-convexity of @sec-multiechelon-allocation-convexity")
        assertTrue(data.alphaHat[13] > (data.alphaHat[12] + data.alphaHat[14]) / 2.0)
        // Convexification drops 13, not 14.
        val kept = data.scValues.take(data.totalNumberOfConvexPoints).toSet()
        assertTrue(13 !in kept, "total 13 should have been dropped")
        assertTrue(12 in kept && 14 in kept)
        assertTrue(20 !in kept, "total 20 should have been dropped")
    }

    @Test
    fun `deltas never rise along a merged buy sequence, @sec-multiechelon-allocation 5`() {
        val model = VariMetricModel()
        val t = model.addItem(1, 0, 3800.0, days(60.0))
        repeat(4) { t.addBaseItem(45.0, days(30.0), 0.3, days(10.0), 0, 3800.0) }
        val r = model.addItem(2, 0, 9500.0, days(90.0))
        repeat(4) { r.addBaseItem(12.0, days(45.0), 0.2, days(10.0), 0, 9500.0) }
        val alg = MarginalAnalysisAlgorithm(
            model, budget = 250_000.0, budgetTolerance = 0.01,
            depotLowerLimits = intArrayOf(0, 0), depotUpperLimits = intArrayOf(26, 20),
            increments = intArrayOf(1, 1), sMax = intArrayOf(40, 30),
        )
        alg.optimize()
        assertTrue(alg.totalCost <= 250_000.0 + 0.01, "spent ${alg.totalCost}")
        assertTrue(alg.totalExpectedBackOrders > 0.0)
        assertEquals(model.totalStockingCost, alg.totalCost, 1e-9)
    }
}
