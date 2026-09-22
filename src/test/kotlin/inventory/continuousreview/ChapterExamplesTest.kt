package inventory.continuousreview

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every number Chapter 8 prints for the transformer, recomputed here.
 *
 * The chapter, the workbook and this code are three statements of the same
 * arithmetic. A disagreement means one of them is wrong, and this file is where
 * that shows up.
 */
class ChapterExamplesTest {

    /** The utility storeroom's transformer, @sec-continuousreview-policy through @sec-continuousreview-algorithm. */
    private val transformer = RQModel(
        demandRate = 45.0,
        orderCost = 220.0,
        holdingCost = RQModel.holdingCostFrom(carryingCharge = 0.25, unitCost = 3800.0),
        backorderCost = 8550.0,
        leadTimeDemand = LeadTimeDemand.poisson(7.5),
    )

    @Test
    fun `the item's parameters are the ones the chapter states`() {
        assertEquals(950.0, transformer.holdingCost, 1e-9)
        assertEquals(0.9, transformer.criticalRatio, 1e-12)
        assertEquals(7.5, transformer.theta, 1e-12)
        assertEquals(sqrt(7.5), transformer.leadTimeDemand.stdDev, 1e-12)
        assertEquals(1.0, transformer.leadTimeDemand.varianceToMeanRatio, 1e-12)
    }

    /** @tbl-optimize-cs, the base-stock cost column. */
    @Test
    fun `the base-stock cost column matches @tbl-optimize-cs`() {
        val expected = mapOf(
            8 to 8654.01, 9 to 6392.69, 10 to 5218.57, 11 to 4859.83, 12 to 5057.03,
            13 to 5601.71, 14 to 6346.84, 15 to 7199.37, 16 to 8105.59,
        )
        for ((s, cost) in expected) {
            assertEquals(cost, transformer.baseStockCost(s), 0.01, "C($s)")
        }
        assertEquals(11, transformer.optimalBaseStock(), "S*")
    }

    /** @tbl-rq-transformer, the evaluation across reorder points at Q = 5. */
    @Test
    fun `the evaluation at Q of five matches @tbl-rq-transformer`() {
        data class Row(val r: Int, val b: Double, val fr: Double, val onHand: Double, val total: Int)
        val rows = listOf(
            Row(7, 0.3854, 0.7492, 2.8854, 8016),
            Row(8, 0.2212, 0.8357, 3.7212, 7406),
            Row(9, 0.1202, 0.8990, 4.6202, 7397),
            Row(10, 0.0619, 0.9417, 5.5619, 7793),
            Row(11, 0.0302, 0.9683, 6.5302, 8442),
        )
        for (row in rows) {
            val p = transformer.evaluate(RQPolicy(row.r, 5))
            assertEquals(row.b, p.expectedBackorders, 5e-5, "backorders at r = ${row.r}")
            assertEquals(row.fr, p.fillRate, 5e-5, "fill rate at r = ${row.r}")
            assertEquals(row.onHand, p.expectedOnHand, 5e-5, "on hand at r = ${row.r}")
            assertEquals(row.total.toDouble(), p.totalCost, 0.5, "total at r = ${row.r}")
            assertEquals(1980.0, p.orderingCostRate, 1e-9)
        }
    }

    /**
     * The check the chapter calls worth performing every time.
     *
     * The on-hand level is `(Q+1)/2 + r - theta + B`, and the term most often
     * written wrongly is the `(Q+1)/2`, which is not `Q/2`.
     */
    @Test
    fun `expected on hand uses the discrete inventory position`() {
        val onHand = transformer.expectedOnHand(9, 5)
        val backorders = transformer.expectedBackorders(9, 5)
        assertEquals((5 + 1) / 2.0 + 9 - 7.5 + backorders, onHand, 1e-12)
        assertEquals(4.6202, onHand, 5e-5)
        assertTrue(
            abs(onHand - (5 / 2.0 + 9 - 7.5 + backorders)) > 0.4,
            "the continuous form would differ by half a unit and must not be used"
        )
    }

    /** @sec-continuousreview-algorithm-steps, @tbl-optimize-trace, and the answer the chapter reaches. */
    @Test
    fun `Algorithm Optimize_rq reaches the policy of @tbl-optimize-trace`() {
        val solution = RQOptimizer(transformer).optimizeFromUnitBatch()
        assertEquals(8, solution.reorderPoint)
        assertEquals(7, solution.orderQuantity)
        assertEquals(7225.15, solution.cost, 0.01)
        assertEquals(SearchMethod.UNIT_BATCH, solution.method)
    }

    /** @sec-continuousreview-algorithm-warm. The warm start reaches the same answer. */
    @Test
    fun `the warm start reaches the same policy`() {
        val solution = RQOptimizer(transformer).optimize()
        assertEquals(8, solution.reorderPoint)
        assertEquals(7, solution.orderQuantity)
        assertEquals(7225.15, solution.cost, 0.01)
    }

    /** @sec-continuousreview-bounds, and the three checks the chapter runs on the answer. */
    @Test
    fun `the bounds of @sec-continuousreview-bounds hold on the transformer`() {
        val bounds = transformer.bounds()
        assertEquals(4.81, transformer.deterministicReferenceQuantity(), 0.005, "Q_k")
        assertEquals(4114.49, bounds.costLower, 0.01, "C_k")
        assertEquals(8823.14, bounds.costUpper, 0.01)
        assertEquals(11, bounds.baseStockLevel)
        assertEquals(4859.83, bounds.baseStockCost, 0.01)

        val optimum = RQOptimizer(transformer).optimize()
        assertTrue(bounds.containsCost(optimum.cost), "the optimum must lie inside the bounds")
        assertTrue(bounds.hasOptimalStructure(optimum.policy), "r* < S* <= r* + Q*")
    }

    /** @sec-continuousreview-service-implied, the backorder cost a ready rate target implies. */
    @Test
    fun `a ready rate target implies the backorder costs of @tbl-implied-backorder`() {
        val expected = mapOf(0.90 to 8550.0, 0.95 to 18050.0, 0.98 to 46550.0, 0.99 to 94050.0)
        for ((target, implied) in expected) {
            val model = RQModel.fromReadyRateTarget(
                demandRate = 45.0, orderCost = 220.0, holdingCost = 950.0,
                readyRateTarget = target, leadTimeDemand = LeadTimeDemand.poisson(7.5),
            )
            assertEquals(implied, model.backorderCost, 1e-6, "implied b at $target")
        }
    }

    /** @sec-continuousreview-batch, the economic order quantity as a starting value. */
    @Test
    fun `the economic order quantity starts the search`() {
        val eoq = sqrt(2.0 * transformer.orderCost * transformer.demandRate / transformer.holdingCost)
        assertEquals(4.57, eoq, 0.005)
    }
}
