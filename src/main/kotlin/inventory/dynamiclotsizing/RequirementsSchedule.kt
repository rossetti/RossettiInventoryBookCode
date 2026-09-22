package inventory.dynamiclotsizing

/**
 * What one item requires in each period of a finite horizon, together with what it costs
 * to order and to hold in each of those periods. Chapter 5.
 *
 * Periods are numbered 1 through [horizon], which is how a requirements record is read
 * and how Chapter 6 lays one out. The arrays below are indexed from zero internally and
 * every public method takes a period number.
 *
 * The schedule owns the window cost of @sec-dls-properties. That is deliberate: every rule in
 * this package prices the same windows, so computing them here once means the heuristics
 * and the algorithm are compared on identical numbers rather than on two implementations
 * of one formula.
 */
class RequirementsSchedule(
    val label: String,
    requirements: List<Double>,
    orderCosts: List<Double>,
    unitCosts: List<Double>,
    holdingRates: List<Double>,
) {
    init {
        require(requirements.isNotEmpty()) { "A schedule needs at least one period" }
        require(requirements.all { it >= 0.0 }) { "A requirement cannot be negative" }
        require(requirements.any { it > 0.0 }) { "A schedule with no requirement has no problem in it" }
        listOf(orderCosts to "order costs", unitCosts to "unit costs",
            holdingRates to "holding rates").forEach { (list, what) ->
            require(list.size == requirements.size) {
                "Expected one of the $what per period, got ${list.size} for ${requirements.size}"
            }
            require(list.all { it >= 0.0 }) { "A cost in the $what cannot be negative" }
        }
    }

    private val d = requirements.toDoubleArray()
    private val k = orderCosts.toDoubleArray()
    private val c = unitCosts.toDoubleArray()
    private val h = holdingRates.toDoubleArray()

    /** The number of periods in the planning horizon, written N in the text. */
    val horizon: Int get() = d.size

    /** The periods, 1 through N, as a range to iterate over. */
    val periods: IntRange get() = 1..horizon

    fun requirementIn(period: Int): Double = d[check(period)]
    fun orderCostIn(period: Int): Double = k[check(period)]
    fun unitCostIn(period: Int): Double = c[check(period)]
    fun holdingRateIn(period: Int): Double = h[check(period)]

    /** The requirement of periods [from] through [through], written d[t,u] in the text. */
    fun requirementOver(from: Int, through: Int): Double {
        requireWindow(from, through)
        return (from..through).sumOf { d[it - 1] }
    }

    /**
     * @eq-dls-unitcost: what one unit costs if it is bought in [from] and held until
     * [through]. Holding is charged on what crosses a period boundary, so a unit bought
     * and consumed in the same period is charged the unit cost alone.
     */
    fun unitCostHeld(from: Int, through: Int): Double {
        requireWindow(from, through)
        return c[from - 1] + (from + 1..through).sumOf { h[it - 1] }
    }

    /**
     * The carrying cost a window implies: what is held across a period boundary, priced
     * at the holding rate of the period it is held into.
     */
    fun carryingCostOver(from: Int, through: Int): Double {
        requireWindow(from, through)
        return (from..through).sumOf { s ->
            requirementIn(s) * (from + 1..s).sumOf { holdingRateIn(it) }
        }
    }

    /**
     * Setup plus carrying for a window, which is the cost the heuristics of @sec-dls-heuristics
     * compare. The purchase term is excluded: it is the same under every plan
     * while the unit cost is constant, and a criterion that divides by the length of the
     * window would otherwise be dominated by it.
     */
    fun relevantWindowCost(from: Int, through: Int): Double =
        orderCostIn(from) + carryingCostOver(from, through)

    /**
     * @eq-dls-windowcost, the window cost: order in [from] enough to cover the requirements of
     * periods [from] through [through], and pay for the holding that implies.
     *
     * Memoized because every rule in this package asks for the same O(N^2) values, and
     * because the algorithm of @sec-dls-network depends on the arc costs being fixed before
     * any decision is taken.
     */
    fun windowCost(from: Int, through: Int): Double {
        requireWindow(from, through)
        val key = (from - 1) * horizon + (through - 1)
        cached[key]?.let { return it }
        val value = k[from - 1] + (from..through).sumOf { unitCostHeld(from, it) * d[it - 1] }
        cached[key] = value
        return value
    }

    private val cached = arrayOfNulls<Double>(horizon * horizon)

    /**
     * @sec-dls-properties: a requirement larger than this forces an order in its own period,
     * because carrying it one period already costs more than ordering again. Returns the
     * earliest such period, or null when the demand pattern never gets that large.
     */
    fun forcedOrderPeriod(): Int? = periods.firstOrNull { t ->
        t > 1 && holdingRateIn(t) > 0.0 && requirementIn(t) * holdingRateIn(t) > orderCostIn(t)
    }

    /** The variability coefficient of @sec-dls-performance, the test for whether any of this pays. */
    val variabilityCoefficient: Double
        get() {
            val mean = d.average()
            require(mean > 0.0) { "$label has no demand over the horizon" }
            return d.sumOf { (it - mean) * (it - mean) } / horizon / (mean * mean)
        }

    val averageRequirement: Double get() = d.average()

    /** Every period covered from [from], as one order, is a plan the rules may propose. */
    fun planFrom(orderPeriods: List<Int>): LotSizingPlan {
        require(orderPeriods.isNotEmpty()) { "A plan needs at least one order" }
        require(orderPeriods.first() == 1 || requirementOver(1, orderPeriods.first() - 1) == 0.0) {
            "Inventory starts at zero, so a plan cannot leave a requirement before " +
                "its first order in period ${orderPeriods.first()}"
        }
        require(orderPeriods.zipWithNext().all { (a, b) -> b > a }) {
            "Order periods must increase, got $orderPeriods"
        }
        val quantities = DoubleArray(horizon)
        orderPeriods.forEachIndexed { index, start ->
            val end = if (index + 1 < orderPeriods.size) orderPeriods[index + 1] - 1 else horizon
            quantities[start - 1] = requirementOver(start, end)
        }
        return LotSizingPlan(this, quantities.toList())
    }

    private fun check(period: Int): Int {
        require(period in periods) { "Period $period is outside 1..$horizon" }
        return period - 1
    }

    private fun requireWindow(from: Int, through: Int) {
        require(from in periods) { "Period $from is outside 1..$horizon" }
        require(through in periods) { "Period $through is outside 1..$horizon" }
        require(from <= through) { "A window runs forward, so $from cannot follow $through" }
    }

    override fun toString(): String = "RequirementsSchedule($label, $horizon periods)"

    companion object {
        /**
         * The common case: one order cost, one unit cost and one carrying charge for the
         * whole horizon, with h = ic as in @sec-costparams-h.
         */
        fun constantCosts(
            label: String,
            requirements: List<Double>,
            orderCost: Double,
            unitCost: Double,
            carryingCharge: Double,
        ): RequirementsSchedule = RequirementsSchedule(
            label = label,
            requirements = requirements,
            orderCosts = List(requirements.size) { orderCost },
            unitCosts = List(requirements.size) { unitCost },
            holdingRates = List(requirements.size) { carryingCharge * unitCost },
        )
    }
}
