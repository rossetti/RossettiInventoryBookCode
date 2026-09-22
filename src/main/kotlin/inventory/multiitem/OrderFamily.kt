package inventory.multiitem

import kotlin.math.sqrt

/**
 * SKUs bought from one supplier, carried on one truck, or run on one machine setup, so
 * that part of the cost of ordering is paid once however many are on the order.
 *
 * The major setup belongs to the family and not to any SKU in it, which is the whole
 * reason the family is an object rather than a loose list.
 */
class OrderFamily(
    val portfolio: Portfolio,
    val majorSetupCost: Double,
) {
    init { require(majorSetupCost >= 0.0) { "A major setup cost cannot be negative" } }

    private val skus get() = portfolio.skus

    /** Each SKU on its own schedule, paying the major setup every time it orders. */
    val independentCost: Double =
        skus.sumOf { sqrt(2.0 * (majorSetupCost + it.orderCost) * it.holdingRate * it.demandRate) }

    /** @eq-cycle-optimum: every SKU on every opportunity has a closed-form base period. */
    val commonCycleInterval: Double =
        sqrt((majorSetupCost + skus.sumOf { it.orderCost }) / skus.sumOf { it.holdingCoefficient })

    /** @eq-cycle-form at a stated base period and rule. */
    fun costOf(rule: IntervalRule): Double {
        val t = rule.basePeriod
        return majorSetupCost / t + skus.sumOf {
            val interval = rule.implementable(it.preferredInterval)
            it.orderCost / interval + it.holdingCoefficient * interval
        }
    }

    /** The plan a rule produces at its own base period. */
    fun scheduleUnder(rule: IntervalRule): ReplenishmentPlan =
        portfolio.planFor(skus.map { it.quantityFor(rule.implementable(it.preferredInterval)) })

    /**
     * The cheapest base period for a rule built by [ruleAt], searched over a grid and
     * then refined. The multipliers separate once the base period is fixed, so one
     * dimension is enough, and @sec-multiitem-building plots the objective because it kinks wherever
     * a multiplier steps.
     */
    fun bestBasePeriod(gridPoints: Int = 2000, ruleAt: (Double) -> IntervalRule): Double {
        require(gridPoints > 0) { "A grid needs at least one point, was $gridPoints" }
        var low = commonCycleInterval / 32.0
        var high = commonCycleInterval * 4.0
        var best = commonCycleInterval
        var bestCost = Double.MAX_VALUE
        repeat(2) {
            val step = (high - low) / gridPoints
            for (g in 0..gridPoints) {
                val t = low + g * step
                if (t <= 0.0) continue
                val cost = costOf(ruleAt(t))
                if (cost < bestCost) { bestCost = cost; best = t }
            }
            low = maxOf(best - step, step / 1000.0)
            high = best + step
        }
        return best
    }

    override fun toString(): String =
        "OrderFamily(${skus.size} SKUs, majorSetupCost=$majorSetupCost)"
}
