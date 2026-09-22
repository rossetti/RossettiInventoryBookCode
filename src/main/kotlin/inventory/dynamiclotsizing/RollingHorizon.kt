package inventory.dynamiclotsizing

/**
 * @sec-dls-rolling. The horizon does not stand still: a plan is made over N periods, only the
 * first few orders are placed, and then the whole thing is done again with the window
 * shifted forward.
 *
 * This is the one study in the chapter that a worksheet cannot carry, because the answer
 * is not a plan but the cost of a sequence of plans, most of which were discarded. It is
 * also where an optimal algorithm loses its guarantee, since it is optimal for a horizon
 * that keeps moving. Whether it then loses to a heuristic is a question to measure, and
 * @sec-dls-rolling measures it.
 */
class RollingHorizon(
    private val actual: RequirementsSchedule,
    private val planningHorizon: Int,
    private val freeze: Int = 1,
) {
    init {
        require(planningHorizon >= 1) { "A planning horizon needs at least one period" }
        require(freeze in 1..planningHorizon) {
            "The frozen span must lie between one period and the planning horizon"
        }
    }

    /**
     * Run [rule] on a window that advances by [freeze] periods at a time, implementing
     * only the frozen orders, and report what the implemented orders actually cost.
     */
    fun realizedPlan(rule: LotSizingRule): LotSizingPlan {
        val n = actual.horizon
        val placed = DoubleArray(n)
        var period = 1
        while (period <= n) {
            val onHand = carriedInto(placed, period)
            val committedTo = minOf(period + freeze - 1, n)
            val net = netRequirements(period, minOf(period + planningHorizon - 1, n), onHand)
            if (net != null) {
                val plan = rule.plan(net.window)
                for (t in period..committedTo) {
                    val offset = t - net.firstPlanned + 1
                    if (offset in 1..net.window.horizon) placed[t - 1] = plan.orderIn(offset)
                }
            }
            period = committedTo + 1
        }
        return LotSizingPlan(actual, placed.toList())
    }

    /** What is on hand entering [period], given the orders placed so far. */
    private fun carriedInto(placed: DoubleArray, period: Int): Double =
        (1 until period).sumOf { placed[it - 1] - actual.requirementIn(it) }

    /**
     * The window the planner actually solves. Stock already on hand is netted against
     * the nearest requirements, exactly as a requirements record nets it, and planning
     * starts at the first period the stock does not cover.
     */
    private fun netRequirements(first: Int, last: Int, onHand: Double): Window? {
        var remaining = onHand
        var start = first
        val net = mutableListOf<Double>()
        for (t in first..last) {
            val covered = minOf(remaining, actual.requirementIn(t))
            remaining -= covered
            val outstanding = actual.requirementIn(t) - covered
            if (net.isEmpty() && outstanding <= 0.0) { start = t + 1; continue }
            net.add(outstanding)
        }
        if (net.isEmpty() || net.none { it > 0.0 }) return null
        return Window(
            firstPlanned = start,
            window = RequirementsSchedule(
                label = "${actual.label} from period $start",
                requirements = net,
                orderCosts = (start..last).map { actual.orderCostIn(it) },
                unitCosts = (start..last).map { actual.unitCostIn(it) },
                holdingRates = (start..last).map { actual.holdingRateIn(it) },
            ),
        )
    }

    private class Window(val firstPlanned: Int, val window: RequirementsSchedule)
}
