package inventory.multiitem

/**
 * A way of finding the cheapest plan that meets a constraint.
 *
 * An interface because the domain has alternatives, not because interfaces are good
 * practice. Chapter 4 shows three: search the shadow price until the measure meets its
 * ceiling, optimize the quantities directly, or scale every quantity by a closed factor.
 * The worksheet of @sec-multiitem-building offers the first two as Goal Seek and Solver.
 */
interface LotSizingMethod {
    val name: String
    fun solve(portfolio: Portfolio, constraint: Constraint): ReplenishmentPlan
}

/**
 * Bisection on the shadow price, which is what Goal Seek does on the worksheet.
 *
 * @sec-multiitem-constrained-solve proves the resource consumed is strictly decreasing in the price with
 * exactly one root. That proof is the precondition, and no numerical method can
 * establish it for itself.
 */
class LagrangianSearch(
    private val precision: Double = 1.0E-12,
    private val maximumIterations: Int = 400,
) : LotSizingMethod {

    init {
        require(precision > 0.0) { "A precision must be positive, was $precision" }
        require(maximumIterations > 0) {
            "A search needs at least one iteration, was $maximumIterations"
        }
    }

    override val name: String = "Lagrangian search"

    override fun solve(portfolio: Portfolio, constraint: Constraint): ReplenishmentPlan {
        val measure = constraint.measure
        var lo = 0.0
        var hi = 1.0
        while (constraint.slackIn(portfolio.planAt(measure, hi)) < 0.0) {
            hi *= 2.0
            check(hi < 1.0E12) {
                "No price high enough to meet the ${measure.name} limit of ${constraint.limit}"
            }
        }
        repeat(maximumIterations) {
            val mid = 0.5 * (lo + hi)
            if (hi - lo < precision) return@repeat
            if (constraint.slackIn(portfolio.planAt(measure, mid)) < 0.0) lo = mid else hi = mid
        }
        return portfolio.planAt(measure, 0.5 * (lo + hi))
    }
}

/**
 * The closed form of @sec-multiitem-multiplier, valid only under a budget with h = i*c.
 *
 * Every quantity shrinks by the same factor, so the aggregate investment shrinks by it
 * too, and the price follows from the ratio of the free investment to the limit without
 * any search at all. Included because it is the third way the chapter shows, and
 * because a strategy with one implementation would be speculation.
 */
class ProportionalScaling(private val carryingCharge: Double) : LotSizingMethod {

    init {
        require(carryingCharge > 0.0) {
            "A carrying charge must be positive, was $carryingCharge"
        }
    }

    override val name: String = "Proportional scaling"

    override fun solve(portfolio: Portfolio, constraint: Constraint): ReplenishmentPlan {
        require(constraint.measure === AverageInvestment) {
            "Scaling is a property of a budget constraint, not of ${constraint.measure.name}"
        }
        portfolio.skus.forEach {
            require(kotlin.math.abs(it.holdingRate - carryingCharge * it.unitValue) < 1.0E-9) {
                "${it.label} does not hold at h = i*c, so its quantity does not scale"
            }
        }
        val free = AverageInvestment.measure(portfolio.freePlan())
        val ratio = free / constraint.limit
        val price = carryingCharge * (ratio * ratio - 1.0)
        return portfolio.planAt(AverageInvestment, price)
    }
}
