package inventory.multiitem

import inventory.lotsizing.CostPenalty

/**
 * The study of @sec-multiitem-constrained and @sec-multiitem-multiplier: a portfolio decided against one constraint.
 *
 * The study is separate from the data it studies and from the method it uses, which is
 * what gives the numerical work somewhere to live other than inside a factory method.
 */
class ConstrainedLotSizing(
    val portfolio: Portfolio,
    val constraint: Constraint,
    val method: LotSizingMethod = LagrangianSearch(),
) {
    /** Every SKU at its own economic order quantity. */
    val freePlan: ReplenishmentPlan by lazy { portfolio.freePlan() }

    /** Step 2 of @sec-multiitem-constrained-check: a limit already met changes nothing. */
    val binds: Boolean by lazy { !constraint.isMetBy(freePlan) }

    /** The cheapest plan meeting the limit, or the free plan when it does not bind. */
    fun solve(): ReplenishmentPlan =
        if (!binds) freePlan else method.solve(portfolio, constraint)

    /** What the limit cost against having none. */
    fun priceOfTheConstraint(): CostPenalty = solve().penaltyAgainst(freePlan)

    /** The constrained quantity over the free one, SKU by SKU. @sec-multiitem-multiplier. */
    fun quantityRatios(): List<Double> {
        val held = solve()
        return portfolio.skus.map { held.quantityOf(it) / freePlan.quantityOf(it) }
    }

    override fun toString(): String =
        "ConstrainedLotSizing($constraint, method=${method.name}, binds=$binds)"
}
