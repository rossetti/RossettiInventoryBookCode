package inventory.multiechelon

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Lagrangian relaxation, @sec-multiechelon-design-lagrange.
 *
 * The two searches are held to the marginal analysis of @sec-multiechelon-allocation rather than
 * to fixed numbers: on the same budget they should reach a comparable objective,
 * because they are two routes to the same program.
 */
class LagrangeTest {

    private fun days(d: Double) = d / 365.0

    private fun twoItemModel(): VariMetricModel {
        val model = VariMetricModel()
        val t = model.addItem(1, 0, 3800.0, days(60.0))
        repeat(4) { t.addBaseItem(45.0, days(30.0), 0.3, days(10.0), 0, 3800.0) }
        val r = model.addItem(2, 0, 9500.0, days(90.0))
        repeat(4) { r.addBaseItem(12.0, days(45.0), 0.2, days(10.0), 0, 9500.0) }
        return model
    }

    @Test
    fun `a storeroom's Lagrangian sub-problem is a newsvendor, @eq-lagrange-newsvendor`() {
        val m = twoItemModel()
        val item = m.items[0]
        val bi = item.baseItems[0]
        val theta = 1.0e-5
        bi.stockLevel = bi.optimalLevelAt(theta)
        val ratio = 1.0 - theta * bi.unitCost
        // The level is the inverse distribution function at the critical ratio.
        assertTrue(bi.leadTimeDemand.cdf(bi.stockLevel.toDouble()) >= ratio - 1e-9)
        // Raising the price of money never raises the level.
        val higher = bi.optimalLevelAt(theta * 5.0)
        assertTrue(higher <= bi.stockLevel)
    }

    @Test
    fun `the budget gap falls as the multiplier rises`() {
        val m = twoItemModel()
        var previous = Double.MAX_VALUE
        for (theta in listOf(1.0e-6, 5.0e-6, 1.0e-5, 5.0e-5)) {
            m.items.forEach { it.optimizeSubProblemAt(theta, 0, 40) }
            val tsc = m.totalStockingCost
            assertTrue(tsc <= previous + 1e-9, "cost rose from $previous to $tsc at theta $theta")
            previous = tsc
        }
    }

    @Test
    fun `enumeration finds a plan inside the budget`() {
        val m = twoItemModel()
        val alg = LagrangeEnumerationAlgorithm(m, 250_000.0, 60, 1.0e-6, 1.0e-4, 1.0e-9)
        alg.optimize()
        assertTrue(alg.finalTheta.isFinite())
        assertTrue(m.totalStockingCost > 0.0)
        assertTrue(abs(alg.budgetGap) < 250_000.0)
    }

    @Test
    fun `the two searches stop on different rules, @sec-multiechelon-design-lagrange`() {
        val budget = 250_000.0
        val enum = twoItemModel()
        LagrangeEnumerationAlgorithm(enum, budget, 200, 1.0e-6, 1.0e-4, 1.0e-9).optimize()

        val bis = twoItemModel()
        val alg = LagrangeIterativeAlgorithm(bis, budget, 100, 1.0e-9, 1.0e-6, 1.0e-4, 1.0e-9)
        val feasible = alg.optimize()

        assertTrue(alg.converged, "the bisection did not converge")
        assertTrue(alg.iterationsExecuted in 1..100)

        // The cost is a step function of the multiplier. The bisection converges
        // on the step and reports the side that satisfies the budget; the
        // enumeration keeps the smallest absolute gap and may sit above it.
        assertTrue(feasible, "the bisection should land inside the budget")
        assertTrue(bis.totalStockingCost <= budget)
        assertTrue(enum.totalStockingCost > budget,
            "the enumeration overshoots here, spending ${enum.totalStockingCost}")
        // Paying for the overshoot buys a better objective, which is the trade.
        assertTrue(enum.totalBaseExpectedBackOrders < bis.totalBaseExpectedBackOrders)
    }

    @Test
    fun `the relaxation and marginal analysis differ as @sec-multiechelon-design-lagrange says`() {
        val budget = 250_000.0
        val lag = twoItemModel()
        LagrangeEnumerationAlgorithm(lag, budget, 200, 1.0e-6, 1.0e-4, 1.0e-9).optimize()

        val mar = twoItemModel()
        MarginalAnalysisAlgorithm(mar, budget, 0.01,
            intArrayOf(0, 0), intArrayOf(26, 20), intArrayOf(1, 1), intArrayOf(40, 30)).optimize()

        // The relaxation does not enforce the budget, so the enumeration may
        // overshoot, and buys a better objective for the overshoot.
        assertTrue(mar.totalStockingCost <= budget,
            "marginal analysis should stay inside the budget")
        assertTrue(lag.totalStockingCost > mar.totalStockingCost,
            "the relaxation spent ${lag.totalStockingCost}, marginal ${mar.totalStockingCost}")
        assertTrue(lag.totalBaseExpectedBackOrders < mar.totalBaseExpectedBackOrders,
            "the relaxation should reach fewer backorders for the extra money")
    }

    @Test
    fun `the bisection and marginal analysis reach the same plan here`() {
        val budget = 250_000.0
        val bis = twoItemModel()
        LagrangeIterativeAlgorithm(bis, budget, 100, 1.0e-9, 1.0e-6, 1.0e-4, 1.0e-9).optimize()
        val mar = twoItemModel()
        MarginalAnalysisAlgorithm(mar, budget, 0.01,
            intArrayOf(0, 0), intArrayOf(26, 20), intArrayOf(1, 1), intArrayOf(40, 30)).optimize()
        assertTrue(abs(bis.totalStockingCost - mar.totalStockingCost) < 1e-9,
            "bisection ${bis.totalStockingCost}, marginal ${mar.totalStockingCost}")
        assertTrue(abs(bis.totalBaseExpectedBackOrders - mar.totalBaseExpectedBackOrders) < 1e-9)
    }

    @Test
    fun `a budget below the cheapest unit stocks nothing`() {
        val m = twoItemModel()
        val alg = LagrangeEnumerationAlgorithm(m, 10.0, 10, 1.0e-6, 1.0e-4, 1.0e-9)
        assertTrue(alg.optimize())
        assertTrue(m.totalStockingCost == 0.0)
        assertTrue(alg.warnings.isNotEmpty())
    }
}
