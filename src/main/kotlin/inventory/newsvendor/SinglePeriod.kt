package inventory.newsvendor

import ksl.utilities.distributions.DiscreteDistributionIfc
import ksl.utilities.distributions.DistributionFunctionIfc
import ksl.utilities.distributions.LossFunctionDistributionIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistic.Statistic
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The prices of a single-period item, and the overage and underage costs they
 * imply, @eq-newsvendor-mapping. @sec-newsvendor-design-nouns calls this the cost structure.
 *
 * [price] is what a unit is worth at the moment it is needed. For the belt that is
 * the selling price; for the spare transformer of @exm-newsvendor-transformer it is
 * the emergency purchase price, because that is what a missing unit costs to replace.
 * [penalty] is the stockout penalty of @sec-newsvendor-penalty.
 *
 * A problem with no finite, positive answer is refused here, at construction, rather
 * than allowed to return an absurd quantity later, @sec-newsvendor-improper.
 */
data class SinglePeriodCosts(
    val price: Double,
    val cost: Double,
    val salvage: Double,
    val penalty: Double = 0.0,
) {
    /** The loss on a unit left over, `c - u`. */
    val overageCost: Double get() = cost - salvage

    /** The loss on a unit of demand not met, `s - c + pi`. */
    val underageCost: Double get() = price - cost + penalty

    /** `cu / (cu + co)`, @eq-newsvendor-criticalratio. */
    val criticalRatio: Double get() = underageCost / (underageCost + overageCost)

    init {
        require(overageCost > 0.0) {
            "the overage cost c - u = ${cost - salvage} is not positive, so the order is " +
                "unbounded; a leftover unit must lose something, @sec-newsvendor-improper"
        }
        require(price - cost + penalty > 0.0) {
            "the underage cost s - c + pi = ${price - cost + penalty} is not positive, so " +
                "the order is zero; a shortage must cost something, @sec-newsvendor-improper"
        }
    }

    /** @eq-newsvendor-cost for one realized demand [d] against [q] units available. */
    fun cost(q: Double, d: Double): Double = overageCost * max(q - d, 0.0) + underageCost * max(d - q, 0.0)

    /** @eq-newsvendor-profit for one realized demand, less any penalty on the shortage. */
    fun profit(q: Double, d: Double): Double =
        price * min(d, q) + salvage * max(q - d, 0.0) - cost * q - penalty * max(d - q, 0.0)
}

/**
 * A quantity and what it implies, for any quantity and not only the best one,
 * because @tbl-newsvendor-poisson and @tbl-newsvendor-sim both need a grid.
 *
 * [level] is the stock available for the period, on hand plus ordered, the
 * order-up-to level of @eq-newsvendor-orderupto.
 */
data class NewsvendorSolution(
    val costs: SinglePeriodCosts,
    val level: Double,
    val onHand: Double,
    val probabilityOfLeftovers: Double,
    val expectedShortage: Double,
    val expectedLeftover: Double,
    val expectedCost: Double,
    val expectedProfit: Double,
) {
    /** What to order, `(S - I)+`. */
    val orderQuantity: Double get() = max(level - onHand, 0.0)

    override fun toString(): String = buildString {
        appendLine("order %.2f units, to a level of %.2f with %.2f on hand".format(orderQuantity, level, onHand))
        appendLine("  critical ratio        %.6f   F(level) %.6f".format(costs.criticalRatio, probabilityOfLeftovers))
        appendLine("  expected shortage    %10.4f units".format(expectedShortage))
        appendLine("  expected leftover    %10.4f units".format(expectedLeftover))
        appendLine("  expected cost        %10.2f".format(expectedCost))
        append("  expected profit      %10.2f".format(expectedProfit))
    }
}

/**
 * The level that the critical ratio selects, @eq-newsvendor-criticalratio.
 *
 * For a discrete model it is the rule of @eq-newsvendor-discreterule, the smallest
 * whole unit whose distribution function reaches the ratio, found by walking from
 * the library's quantile so as not to depend on how that quantile breaks ties.
 */
fun optimalLevel(costs: SinglePeriodCosts, demand: DistributionFunctionIfc): Double {
    val ratio = costs.criticalRatio
    if (demand !is DiscreteDistributionIfc) return demand.invCDF(ratio)
    var x = max(floor(demand.invCDF(ratio)), 0.0)
    while (x > 0.0 && demand.cdf(x - 1.0) >= ratio) x -= 1.0
    while (demand.cdf(x) < ratio) x += 1.0
    return x
}

/**
 * The expected consequences of having [level] units available, from
 * @eq-newsvendor-shortage, @eq-newsvendor-leftover and @eq-newsvendor-expectedcost.
 * The profit is @eq-newsvendor-equivalence.
 */
fun evaluate(
    costs: SinglePeriodCosts,
    demand: LossFunctionDistributionIfc,
    level: Double,
    onHand: Double = 0.0,
): NewsvendorSolution {
    val shortage = demand.firstOrderLossFunction(level)
    val leftover = shortage + level - demand.mean()
    val expectedCost = costs.overageCost * leftover + costs.underageCost * shortage
    return NewsvendorSolution(
        costs, level, onHand, demand.cdf(level), shortage, leftover, expectedCost,
        (costs.price - costs.cost) * demand.mean() - expectedCost,
    )
}

/**
 * The exact route: the optimal level, and what it implies. Stock already on hand
 * moves the order and not the level, @eq-newsvendor-orderupto, unless there is
 * already more than the level, in which case nothing is ordered.
 */
fun solve(costs: SinglePeriodCosts, demand: LossFunctionDistributionIfc, onHand: Double = 0.0): NewsvendorSolution =
    evaluate(costs, demand, max(optimalLevel(costs, demand), onHand), onHand)

/**
 * The sampled route, @sec-newsvendor-simulation: [replications] demands from
 * [demand], each priced by [profit] at [q] units available.
 *
 * The stream is reset first, so every quantity estimated with the same [demand]
 * faces the same demands, and the comparison across quantities is a paired one.
 */
fun estimate(
    demand: RVariableIfc,
    q: Double,
    replications: Int,
    profit: (q: Double, d: Double) -> Double,
): Statistic {
    val stat = Statistic("profit at %.2f".format(q))
    demand.resetStartStream()
    repeat(replications) { stat.collect(profit(q, demand.value)) }
    return stat
}

/** The sampled route priced by the same cost structure the exact route uses. */
fun estimate(costs: SinglePeriodCosts, demand: RVariableIfc, q: Double, replications: Int): Statistic =
    estimate(demand, q, replications, costs::profit)
