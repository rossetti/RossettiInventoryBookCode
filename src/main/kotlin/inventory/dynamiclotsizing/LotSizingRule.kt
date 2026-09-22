package inventory.dynamiclotsizing

import inventory.lotsizing.models.EconomicOrderQuantity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A way of turning a requirements schedule into a plan. @sec-dls-cost onward.
 *
 * An interface because the domain offers alternatives, and because @sec-dls-performance ranks
 * them. Wagner-Whitin implements it alongside the heuristics. An optimal algorithm is a
 * different kind of thing from a stopping rule, and the interface says only that both
 * answer the same question, which is all the ranking needs.
 */
interface LotSizingRule {
    val name: String
    fun plan(schedule: RequirementsSchedule): LotSizingPlan
}

/** Order exactly what each period needs. @sec-dls-cost. */
object LotForLot : LotSizingRule {
    override val name: String = "Lot-for-lot"
    override fun plan(schedule: RequirementsSchedule): LotSizingPlan =
        schedule.planFrom(schedule.periods.filter { schedule.requirementIn(it) > 0.0 })
}

/**
 * Three of the heuristics of @sec-dls-heuristics are one procedure. Each extends a window from
 * the current order period until a rule says stop, and they differ in the rule alone.
 *
 * A subclass supplies [scoreOf], a figure of merit that the procedure walks until it
 * stops improving. Returning null says the window cannot be scored yet, which is how a
 * run of periods with no requirement is stepped over.
 */
abstract class WindowRule(override val name: String) : LotSizingRule {

    /** The figure of merit for covering [from] through [through], or null to skip. */
    protected abstract fun scoreOf(
        schedule: RequirementsSchedule,
        from: Int,
        through: Int,
    ): Double?

    final override fun plan(schedule: RequirementsSchedule): LotSizingPlan {
        val orders = mutableListOf<Int>()
        var from = 1
        while (from <= schedule.horizon) {
            orders.add(from)
            from = 1 + lastPeriodCovered(schedule, from)
        }
        return schedule.planFrom(orders)
    }

    /** How far a replenishment placed in [from] should reach. */
    fun lastPeriodCovered(schedule: RequirementsSchedule, from: Int): Int {
        var best = from
        var previous: Double? = null
        for (through in from..schedule.horizon) {
            val score = scoreOf(schedule, from, through) ?: continue
            if (previous != null && score > previous + TOLERANCE) break
            previous = score
            best = through
        }
        return best
    }

    protected companion object {
        const val TOLERANCE = 1.0E-9
    }
}

/** Silver-Meal: the least cost per period. @sec-dls-heuristics. */
object SilverMeal : WindowRule("Silver-Meal") {
    override fun scoreOf(schedule: RequirementsSchedule, from: Int, through: Int): Double =
        schedule.relevantWindowCost(from, through) / (through - from + 1)
}

/** Least unit cost: the least cost per unit. @sec-dls-heuristics. */
object LeastUnitCost : WindowRule("Least unit cost") {
    override fun scoreOf(schedule: RequirementsSchedule, from: Int, through: Int): Double? {
        val units = schedule.requirementOver(from, through)
        return if (units <= 0.0) null else schedule.relevantWindowCost(from, through) / units
    }
}

/**
 * Part-period balancing: extend until the carrying cost of the window is as close as it
 * can get to the order cost. @sec-dls-heuristics.
 *
 * This is the modified rule, which keeps whichever of the last two periods comes closer
 * to the order cost. The original takes the largest window whose carrying is at or under
 * it, and so never overshoots. @sec-dls-heuristics gives both.
 *
 * It does not fit [WindowRule] because it does not walk a score to a minimum. It walks a
 * gap to its closest approach, and the period it keeps may be the one before the test
 * fails rather than the one that failed.
 */
object PartPeriodBalancing : LotSizingRule {
    override val name: String = "Part-period balancing"

    override fun plan(schedule: RequirementsSchedule): LotSizingPlan {
        val orders = mutableListOf<Int>()
        var from = 1
        while (from <= schedule.horizon) {
            orders.add(from)
            from = 1 + lastPeriodCovered(schedule, from)
        }
        return schedule.planFrom(orders)
    }

    fun lastPeriodCovered(schedule: RequirementsSchedule, from: Int): Int {
        val setup = schedule.orderCostIn(from)
        var best = from
        for (through in from + 1..schedule.horizon) {
            val carried = carryingOver(schedule, from, through)
            if (carried > setup) {
                val below = carryingOver(schedule, from, through - 1)
                best = if (abs(carried - setup) < abs(below - setup)) through else through - 1
                return best
            }
            best = through
        }
        return best
    }

    private fun carryingOver(schedule: RequirementsSchedule, from: Int, through: Int): Double =
        schedule.carryingCostOver(from, through)
}

/**
 * Chapter 3's order quantity applied to the average requirement, then adjusted to the
 * whole number of periods whose requirement comes closest to it. @sec-dls-cost.
 *
 * ADJUSTED, not fixed. A fixed economic order quantity rule orders exactly Q whatever
 * the period boundaries are, which on the belt leaves 283 belts on hand at the end of
 * the horizon and so cannot meet the assumption that the horizon ends empty. This rule
 * covers whole periods, which is what makes it comparable with everything else in
 * @sec-dls-performance.
 */
class AdjustedEconomicOrderQuantity : LotSizingRule {
    override val name: String = "Adjusted economic order quantity"

    override fun plan(schedule: RequirementsSchedule): LotSizingPlan {
        val quantity = EconomicOrderQuantity.orderQuantityFor(
            orderCost = schedule.orderCostIn(1),
            demandRate = schedule.averageRequirement,
            holdingRate = schedule.holdingRateIn(1),
        )
        val orders = mutableListOf<Int>()
        var from = 1
        while (from <= schedule.horizon) {
            orders.add(from)
            var best = from
            var closest = Double.MAX_VALUE
            for (through in from..schedule.horizon) {
                val covered = schedule.requirementOver(from, through)
                if (covered <= 0.0) continue
                val gap = abs(covered - quantity)
                if (gap < closest) { closest = gap; best = through }
            }
            from = best + 1
        }
        return schedule.planFrom(orders)
    }
}

/**
 * The order quantity expressed as a time supply, rounded to a whole number of periods,
 * and then used as a fixed cycle. @sec-dls-cost.
 */
class PeriodicOrderQuantity : LotSizingRule {
    override val name: String = "Periodic order quantity"

    fun timeSupplyFor(schedule: RequirementsSchedule): Int {
        val quantity = EconomicOrderQuantity.orderQuantityFor(
            orderCost = schedule.orderCostIn(1),
            demandRate = schedule.averageRequirement,
            holdingRate = schedule.holdingRateIn(1),
        )
        return max(1, (quantity / schedule.averageRequirement).roundToInt())
    }

    override fun plan(schedule: RequirementsSchedule): LotSizingPlan {
        val supply = timeSupplyFor(schedule)
        val orders = (1..schedule.horizon step supply).toList()
        return schedule.planFrom(orders)
    }
}
