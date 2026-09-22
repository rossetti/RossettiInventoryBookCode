package inventory.dynamiclotsizing

/**
 * The shortest path through the network of @sec-dls-network, which is the optimal plan.
 *
 * The arc costs are the window costs, and @sec-dls-properties shows they do not depend on any
 * decision, so the schedule computes them once and this is an ordinary shortest-path
 * problem on an acyclic graph. Wagner and Whitin's contribution is the observation that
 * the graph is this one; the recursion below is what that observation buys.
 */
object WagnerWhitin : LotSizingRule {
    override val name: String = "Wagner-Whitin"

    override fun plan(schedule: RequirementsSchedule): LotSizingPlan =
        schedule.planFrom(solve(schedule).orderPeriods)

    /** The value function, the predecessor pointers, and the plan they trace out. */
    fun solve(schedule: RequirementsSchedule): Solution {
        val n = schedule.horizon
        val value = DoubleArray(n + 1)
        val from = IntArray(n + 1)
        for (t in 1..n) {
            var best = Double.MAX_VALUE
            var argument = 1
            for (s in 1..t) {
                val candidate = value[s - 1] + schedule.windowCost(s, t)
                // A tie keeps the earliest period achieving it, which is what scanning s
                // upward with a strict comparison does, and what MATCH does on the
                // worksheet. Both artifacts must report the same S(t).
                if (candidate < best - TOLERANCE) {
                    best = candidate
                    argument = s
                }
            }
            value[t] = best
            from[t] = argument
        }
        val orders = mutableListOf<Int>()
        var t = n
        while (t >= 1) {
            val s = from[t]
            orders.add(s)
            t = s - 1
        }
        return Solution(schedule, value.toList(), from.toList(), orders.reversed())
    }

    /**
     * What the recursion produced. [value] is V(t) indexed from zero, so that V(0) = 0
     * sits at the front, and [orderedFrom] is S(t) with S(0) unused.
     */
    class Solution(
        val schedule: RequirementsSchedule,
        val value: List<Double>,
        val orderedFrom: List<Int>,
        val orderPeriods: List<Int>,
    ) {
        /** V(N), the cost of the best plan over the whole horizon. */
        val optimalCost: Double get() = value.last()

        fun valueAt(period: Int): Double = value[period]
        fun orderedFromAt(period: Int): Int = orderedFrom[period]
    }

    private const val TOLERANCE = 1.0E-9
}
