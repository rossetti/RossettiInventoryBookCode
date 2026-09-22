package inventory.dynamiclotsizing

import inventory.lotsizing.models.EconomicOrderQuantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every number Chapter 5 prints, recomputed. */
class ChapterExamplesTest {

    private fun belt() = RequirementsSchedule.constantCosts(
        label = "Mower deck belt",
        requirements = listOf(40.0, 60.0, 120.0, 300.0, 420.0, 260.0,
                              120.0, 40.0, 0.0, 80.0, 180.0, 220.0),
        orderCost = 300.0, unitCost = 50.0, carryingCharge = 0.02,
    )

    private fun halfYear() = RequirementsSchedule.constantCosts(
        label = "Mower deck belt, first half year",
        requirements = listOf(40.0, 60.0, 120.0, 300.0, 420.0, 260.0),
        orderCost = 300.0, unitCost = 50.0, carryingCharge = 0.02,
    )

    @Test
    fun `the item as Section 5 point 1 describes it`() {
        val s = belt()
        assertEquals(12, s.horizon)
        assertEquals(1840.0, s.periods.sumOf { s.requirementIn(it) }, 1e-9)
        assertEquals(153.3333, s.averageRequirement, 1e-4)
        assertEquals(1.00, s.holdingRateIn(1), 1e-12)
        assertEquals(0.6191, s.variabilityCoefficient, 1e-4)
        assertEquals(300.0, s.orderCostIn(1) / s.holdingRateIn(1), 1e-9)
        assertEquals(5, s.forcedOrderPeriod())
    }

    @Test
    fun `the relevant window costs of the six period instance`() {
        val s = halfYear()
        assertEquals(listOf(300.0, 360.0, 600.0, 1500.0, 3180.0, 4480.0),
            (1..6).map { s.relevantWindowCost(1, it) })
        assertEquals(listOf(300.0, 420.0, 1020.0, 2280.0, 3320.0),
            (2..6).map { s.relevantWindowCost(2, it) })
        assertEquals(listOf(300.0, 600.0, 1440.0, 2220.0), (3..6).map { s.relevantWindowCost(3, it) })
        assertEquals(listOf(300.0, 720.0, 1240.0), (4..6).map { s.relevantWindowCost(4, it) })
        assertEquals(listOf(300.0, 560.0), (5..6).map { s.relevantWindowCost(5, it) })
        assertEquals(300.0, s.relevantWindowCost(6, 6), 1e-9)
    }

    @Test
    fun `the recursion on the six period instance`() {
        val s = halfYear()
        val solution = WagnerWhitin.solve(s)
        val purchase = { t: Int -> 50.0 * (1..t).sumOf { s.requirementIn(it) } }
        assertEquals(listOf(0.0, 300.0, 360.0, 600.0, 900.0, 1200.0, 1460.0),
            (0..6).map { solution.valueAt(it) - purchase(it) })
        assertEquals(listOf(1, 1, 1, 4, 5, 5), (1..6).map { solution.orderedFromAt(it) })
        val plan = WagnerWhitin.plan(s)
        assertEquals(1460.0, plan.relevantCost, 1e-9)
        assertEquals(listOf(1, 4, 5), plan.orderPeriods)
        assertEquals(220.0, plan.orderIn(1), 1e-9)
        assertEquals(300.0, plan.orderIn(4), 1e-9)
        assertEquals(680.0, plan.orderIn(5), 1e-9)
    }

    @Test
    fun `the recursion on the twelve period instance`() {
        val s = belt()
        val solution = WagnerWhitin.solve(s)
        val purchase = { t: Int -> 50.0 * (1..t).sumOf { s.requirementIn(it) } }
        assertEquals(
            listOf(0.0, 300.0, 360.0, 600.0, 900.0, 1200.0, 1460.0,
                   1620.0, 1700.0, 1700.0, 2000.0, 2180.0, 2480.0),
            (0..12).map { solution.valueAt(it) - purchase(it) })
        assertEquals(listOf(1, 1, 1, 4, 5, 5, 6, 6, 6, 10, 10, 12),
            (1..12).map { solution.orderedFromAt(it) })
    }

    @Test
    fun `every method on the twelve period instance`() {
        val s = belt()
        val expected = mapOf(
            LotForLot.name to Triple(11, 3300.0, 0.0),
            "Adjusted economic order quantity" to Triple(6, 1800.0, 800.0),
            "Periodic order quantity" to Triple(6, 1800.0, 960.0),
            LeastUnitCost.name to Triple(5, 1500.0, 1540.0),
            PartPeriodBalancing.name to Triple(5, 1500.0, 1100.0),
            SilverMeal.name to Triple(5, 1500.0, 1160.0),
            WagnerWhitin.name to Triple(6, 1800.0, 680.0),
        )
        val rules = listOf(LotForLot, AdjustedEconomicOrderQuantity(), PeriodicOrderQuantity(),
            LeastUnitCost, PartPeriodBalancing, SilverMeal, WagnerWhitin)
        rules.forEach { rule ->
            val plan = rule.plan(s)
            val (orders, setup, carrying) = expected.getValue(rule.name)
            assertEquals(orders, plan.orderCount, rule.name)
            assertEquals(setup, plan.setupCost, 1e-9, rule.name)
            assertEquals(carrying, plan.carryingCost, 1e-9, rule.name)
        }
        assertEquals(2, PeriodicOrderQuantity().timeSupplyFor(s))
    }

    @Test
    fun `a strict fixed order quantity cannot end the horizon empty`() {
        // @sec-dls-threeplans says why the chapter compares an ADJUSTED economic order
        // quantity and not a fixed one. A rule that orders exactly Q, whatever the
        // period boundaries are, finishes holding stock nobody asked it to hold, so it
        // is not competing on the same terms as the plans of @tbl-dls-simple.
        val s = belt()
        val q = EconomicOrderQuantity.orderQuantityFor(
            orderCost = s.orderCostIn(1),
            demandRate = s.averageRequirement,
            holdingRate = s.holdingRateIn(1),
        )
        assertEquals(303.3151, q, 1e-4)

        val placed = DoubleArray(s.horizon)
        var onHand = 0.0
        for (t in s.periods) {
            while (onHand + placed[t - 1] - s.requirementIn(t) < -1e-9) placed[t - 1] += q
            onHand += placed[t - 1] - s.requirementIn(t)
        }
        val plan = LotSizingPlan(s, placed.toList())
        assertEquals(6, plan.orderCount)
        assertEquals(283.2051, plan.endingInventoryIn(s.horizon), 1e-4)
        assertTrue(plan.endingInventoryIn(s.horizon) > 0.0,
            "a fixed quantity leaves stock behind, which @sec-dls-problem assumption 8 forbids")
    }

    @Test
    fun `the penalties of Section 5 point 6`() {
        val s = belt()
        val optimum = WagnerWhitin.plan(s)
        assertEquals(0.3306, LotForLot.plan(s).penaltyAgainst(optimum).relativeError, 1e-4)
        assertEquals(0.0484, AdjustedEconomicOrderQuantity().plan(s).penaltyAgainst(optimum).relativeError, 1e-4)
        assertEquals(0.1129, PeriodicOrderQuantity().plan(s).penaltyAgainst(optimum).relativeError, 1e-4)
        assertEquals(0.2258, LeastUnitCost.plan(s).penaltyAgainst(optimum).relativeError, 1e-4)
        assertEquals(0.0484, PartPeriodBalancing.plan(s).penaltyAgainst(optimum).relativeError, 1e-4)
        assertEquals(0.0726, SilverMeal.plan(s).penaltyAgainst(optimum).relativeError, 1e-4)
    }

    @Test
    fun `Silver-Meal covers the declining run and the dead month in one order`() {
        val s = belt()
        val plan = SilverMeal.plan(s)
        assertEquals(listOf(1, 3, 5, 10, 12), plan.orderPeriods)
        // The order in month 5 carries months 5 through 9, which is the failure mode
        // @sec-dls-heuristics names: a declining run followed by a period with no requirement.
        assertEquals(840.0, plan.orderIn(5), 1e-9)
    }

    @Test
    fun `the period varying instance keeps its purchase cost`() {
        val s = RequirementsSchedule(
            label = "Period-varying costs",
            requirements = listOf(10.0, 2.0, 12.0, 4.0, 14.0),
            orderCosts = List(5) { 40.0 },
            unitCosts = List(5) { 2.0 },
            holdingRates = List(5) { 1.0 },
        )
        val solution = WagnerWhitin.solve(s)
        assertEquals(listOf(0.0, 60.0, 66.0, 114.0, 134.0, 198.0), (0..5).map { solution.valueAt(it) })
        assertEquals(listOf(1, 1, 1, 1, 3), (1..5).map { solution.orderedFromAt(it) })
        val plan = WagnerWhitin.plan(s)
        assertEquals(114.0, plan.relevantCost, 1e-9)
        assertEquals(84.0, plan.purchaseCost, 1e-9)
        assertEquals(198.0, plan.totalCost, 1e-9)
        assertEquals(listOf(1, 3), plan.orderPeriods)
        assertEquals(12.0, plan.orderIn(1), 1e-9)
        assertEquals(30.0, plan.orderIn(3), 1e-9)
    }

    @Test
    fun `the rolling horizon on the belt`() {
        val s = belt()
        val realized = { rule: LotSizingRule, w: Int ->
            RollingHorizon(s, w, freeze = 1).realizedPlan(rule).relevantCost
        }
        assertEquals(2640.0, realized(SilverMeal, 2), 1e-9)
        assertEquals(2640.0, realized(WagnerWhitin, 2), 1e-9)
        assertEquals(2840.0, realized(SilverMeal, 3), 1e-9)
        assertEquals(2480.0, realized(WagnerWhitin, 3), 1e-9)
        assertEquals(2660.0, realized(SilverMeal, 6), 1e-9)
        assertEquals(2480.0, realized(WagnerWhitin, 6), 1e-9)
        // The heuristic's first order does not move with the horizon and the
        // algorithm's does, which is the insulation property of @sec-dls-rolling.
        assertTrue((2..6).map { RollingHorizon(s, it, 1).realizedPlan(SilverMeal).orderIn(1) }
            .distinct().size == 1)
        assertTrue((2..6).map { RollingHorizon(s, it, 1).realizedPlan(WagnerWhitin).orderIn(1) }
            .distinct().size > 1)
    }

    @Test
    fun `Section 5 point 7, what the planner commits to in month 1`() {
        val s = belt()
        assertEquals(List(5) { 100.0 },
            (2..6).map { RollingHorizon(s, it, 1).realizedPlan(SilverMeal).orderIn(1) })
        assertEquals(listOf(100.0, 220.0, 220.0, 220.0, 220.0),
            (2..6).map { RollingHorizon(s, it, 1).realizedPlan(WagnerWhitin).orderIn(1) })
    }

    /**
     * @tbl-dls-rollingstudy, the one figure in this chapter that went to press disagreeing with the
     * program named as its source. It is pinned here because it is the only one nothing
     * else in this file reaches: every other table is an instance, and this one is a
     * study over two thousand of them.
     */
    @Test
    fun `Table 5 point 11, Silver-Meal against the algorithm over 2000 random schedules`() {
        val trials = randomSchedules(count = 2000, periods = 12, seed = 20240501L)
        val want = mapOf(
            3 to Study(heuristic = 360, algorithm = 704, tied = 936, means = 1440.9050 to 1429.8950),
            4 to Study(heuristic = 246, algorithm = 886, tied = 868, means = 1395.7400 to 1375.0750),
            6 to Study(heuristic = 71, algorithm = 1062, tied = 867, means = 1383.1950 to 1350.1400),
        )
        for ((window, row) in want) {
            var heuristicWins = 0
            var algorithmWins = 0
            var smTotal = 0.0
            var wwTotal = 0.0
            for (trial in trials) {
                val sm = RollingHorizon(trial, window, 1).realizedPlan(SilverMeal).relevantCost
                val ww = RollingHorizon(trial, window, 1).realizedPlan(WagnerWhitin).relevantCost
                smTotal += sm
                wwTotal += ww
                if (sm < ww - 1.0E-6) heuristicWins++
                if (ww < sm - 1.0E-6) algorithmWins++
            }
            assertEquals(row.heuristic, heuristicWins, "window $window, Silver-Meal cheaper")
            assertEquals(row.algorithm, algorithmWins, "window $window, Wagner-Whitin cheaper")
            assertEquals(row.tied, trials.size - heuristicWins - algorithmWins, "window $window, tied")
            assertEquals(row.means.first, smTotal / trials.size, 1e-4, "window $window, Silver-Meal mean")
            assertEquals(row.means.second, wwTotal / trials.size, 1e-4, "window $window, algorithm mean")
            // The chapter's claim is the direction, not the count.
            assertTrue(wwTotal < smTotal, "window $window, the algorithm is cheaper on average")
        }
    }

    private data class Study(
        val heuristic: Int, val algorithm: Int, val tied: Int, val means: Pair<Double, Double>,
    )

    /** The generator of @sec-dls-rolling, reproduced here so the study is pinned to a seed. */
    private fun randomSchedules(count: Int, periods: Int, seed: Long): List<RequirementsSchedule> {
        val draw = java.util.Random(seed)
        val choices = listOf(0.0, 10.0, 20.0, 40.0, 80.0, 120.0, 200.0, 300.0)
        return (1..count).map { n ->
            var requirements: List<Double>
            do {
                requirements = (1..periods).map { choices[draw.nextInt(choices.size)] }
            } while (requirements.first() <= 0.0 || requirements.sum() <= 0.0)
            RequirementsSchedule.constantCosts(
                label = "Trial $n", requirements = requirements,
                orderCost = 200.0, unitCost = 50.0, carryingCharge = 0.02,
            )
        }
    }
}
