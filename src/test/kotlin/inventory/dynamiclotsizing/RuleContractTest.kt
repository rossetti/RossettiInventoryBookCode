package inventory.dynamiclotsizing

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Properties every rule must have, checked over many schedules rather than one.
 *
 * These are the claims Chapter 5 makes in prose. A rule that violated one would still
 * produce numbers, and nothing else here would notice.
 */
class RuleContractTest {

    private val rules = listOf(
        LotForLot, AdjustedEconomicOrderQuantity(), PeriodicOrderQuantity(),
        LeastUnitCost, PartPeriodBalancing, SilverMeal, WagnerWhitin,
    )

    private fun schedules(count: Int, seed: Long): List<RequirementsSchedule> {
        val draw = Random(seed)
        val choices = listOf(0.0, 10.0, 20.0, 40.0, 80.0, 120.0, 200.0, 300.0)
        return (1..count).map { n ->
            var requirements: List<Double>
            do {
                requirements = (1..12).map { choices[draw.nextInt(choices.size)] }
            } while (requirements.first() <= 0.0)
            RequirementsSchedule.constantCosts("Trial $n", requirements, 200.0, 50.0, 0.02)
        }
    }

    /** @sec-dls-network: no rule can beat the algorithm, on any instance. */
    @Test
    fun `Wagner-Whitin is never dearer than a heuristic`() {
        schedules(400, 99L).forEach { s ->
            val optimum = WagnerWhitin.plan(s).relevantCost
            rules.forEach { rule ->
                assertTrue(rule.plan(s).relevantCost >= optimum - 1.0E-6,
                    "${rule.name} beat the optimum on ${s.label}")
            }
        }
    }

    /** @sec-dls-properties: every rule here plans an order only into an empty shelf. */
    @Test
    fun `every rule orders only when the shelf is empty`() {
        schedules(200, 7L).forEach { s ->
            rules.forEach { rule ->
                assertTrue(rule.plan(s).ordersOnlyWhenEmpty(),
                    "${rule.name} ordered on top of stock on ${s.label}")
            }
        }
    }

    /** @sec-dls-problem: a plan meets every requirement, and ends the horizon empty. */
    @Test
    fun `every plan is feasible and leaves nothing behind`() {
        schedules(200, 13L).forEach { s ->
            rules.forEach { rule ->
                val plan = rule.plan(s)
                s.periods.forEach { t ->
                    assertTrue(plan.endingInventoryIn(t) >= -1.0E-9,
                        "${rule.name} ran short in period $t")
                }
                assertTrue(plan.endingInventoryIn(s.horizon) <= 1.0E-9,
                    "${rule.name} left stock at the horizon on ${s.label}")
            }
        }
    }

    /** @sec-dls-cost: the purchase term is the same under every plan when the cost is. */
    @Test
    fun `the purchase term does not depend on the plan`() {
        schedules(100, 21L).forEach { s ->
            val purchases = rules.map { it.plan(s).purchaseCost }.distinct()
            assertTrue(purchases.size == 1, "purchase cost varied by plan on ${s.label}")
        }
    }

    /** @sec-dls-properties: the window cost is the relevant cost plus what the units cost. */
    @Test
    fun `the window cost splits into its relevant and purchase parts`() {
        val s = schedules(1, 5L).first()
        s.periods.forEach { t ->
            (t..s.horizon).forEach { u ->
                val purchase = s.unitCostIn(t) * s.requirementOver(t, u)
                assertTrue(kotlin.math.abs(
                    s.windowCost(t, u) - s.relevantWindowCost(t, u) - purchase) < 1.0E-9)
            }
        }
    }

    @Test
    fun `a schedule refuses inputs it cannot use`() {
        assertFailsWith<IllegalArgumentException> {
            RequirementsSchedule.constantCosts("x", listOf(1.0, -1.0), 10.0, 1.0, 0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            RequirementsSchedule("x", listOf(1.0), listOf(1.0, 1.0), listOf(1.0), listOf(1.0))
        }
        val s = RequirementsSchedule.constantCosts("x", listOf(5.0, 5.0), 10.0, 1.0, 0.1)
        assertFailsWith<IllegalArgumentException> { s.requirementOver(2, 1) }
        assertFailsWith<IllegalArgumentException> { s.windowCost(0, 1) }
        assertFailsWith<IllegalArgumentException> { RollingHorizon(s, 2, freeze = 3) }
    }
}
