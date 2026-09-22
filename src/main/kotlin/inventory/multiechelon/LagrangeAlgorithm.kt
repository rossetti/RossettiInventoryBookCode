package inventory.multiechelon

import ksl.utilities.Interval
import ksl.utilities.math.FunctionIfc
import ksl.utilities.rootfinding.BisectionRootFinder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The Lagrangian relaxation of the budget problem, @sec-multiechelon-design-lagrange.
 *
 * Ported from `varimetric.VMLagrangeAlgorithm`. This is the alternative to the
 * marginal analysis of @sec-multiechelon-allocation, and the trade between them
 * is worth stating.
 *
 * Marginal analysis returns the whole curve of backorders against money and
 * never overspends, and it pays for that by enumerating every plan for every
 * item first. The Lagrangian returns one plan for one budget without building a
 * curve: attach a price `theta` to money and the problem separates, so each
 * storeroom reads its level off @eq-lagrange-newsvendor and needs no search. What remains
 * is one scalar to find.
 *
 * The cost falls as `theta` rises, so the budget gap is decreasing and a root is
 * bracketed by any interval whose ends straddle zero.
 */
abstract class LagrangeAlgorithm(
    protected val model: VariMetricModel,
    budget: Double,
    lowerTheta: Double,
    upperTheta: Double,
    numberOfEvaluations: Int = 100,
    thetaTolerance: Double = defaultThetaTolerance,
) {
    var budget: Double = budget
        protected set(value) {
            require(value >= 0.0) { "the budget must be >= 0" }
            field = value
        }

    var numberOfEvaluations: Int = numberOfEvaluations
        set(value) {
            require(value > 0) { "the number of search points must be > 0" }
            field = value
        }

    protected val minTheta: Double = lowerTheta + thetaTolerance
    protected val maxTheta: Double = upperTheta - thetaTolerance

    protected var gapAtMinTheta: Double = 0.0
    protected var gapAtMaxTheta: Double = 0.0

    /** The multiplier the search settled on. */
    var finalTheta: Double = Double.NaN
        protected set

    var convergedWithinSearchLimit: Boolean = false
        protected set

    /** Messages the original writes to a logger, kept where a caller can see them. */
    val warnings = ArrayList<String>()

    init {
        require(lowerTheta > 0.0) { "the minimum multiplier must be > 0" }
        require(upperTheta > lowerTheta) { "the maximum multiplier must exceed the minimum" }
        // The tolerances are absolute, so a narrow interval can be inverted by
        // them, and a maximum of zero prices money at nothing: @eq-lagrange-newsvendor's
        // critical ratio becomes one and the inverse distribution function does
        // not return. The original does not check this.
        require(maxTheta > minTheta && minTheta > 0.0) {
            "the interval ($lowerTheta, $upperTheta) is narrower than the tolerance " +
                "$thetaTolerance, which would leave theta in ($minTheta, $maxTheta)"
        }
    }

    val budgetGap: Double get() = model.totalStockingCost - budget

    val withinBudget: Boolean get() = budgetGap <= 0.0

    /** No storeroom would stock a unit above this, by @eq-lagrange-newsvendor. */
    val recommendedMaxMultiplier: Double get() = model.items.minOf { it.maxLagrangeMultiplier }

    /** The budget gap at a multiplier, which is the function being rooted. */
    protected fun budgetGapAt(theta: Double): Double = stockingCostAt(theta) - budget

    protected fun stockingCostAt(theta: Double): Double {
        for (vi in model.items) optimizeItemAt(vi, theta)
        return model.totalStockingCost
    }

    /**
     * The depot search window, centred on the depot pipeline mean and capped by
     * what the budget could buy of this item alone. The original's rule.
     */
    protected fun optimizeItemAt(item: VMItem, theta: Double): IntArray {
        val ltd = item.leadTimeDemand
        val centre = floor(ltd.mean()).toInt()
        val spread = ceil(2.0 * ltd.standardDeviation()).toInt()
        val affordable = floor(budget / item.unitCost).toInt()
        val upper: Int
        val lower: Int
        if (affordable <= centre) {
            upper = max(1, affordable)
            lower = max(0, upper - spread)
        } else {
            upper = max(1, min(affordable, centre + spread))
            lower = max(0, centre - spread)
        }
        return item.optimizeSubProblemAt(theta, lower, upper)
    }

    protected fun bracketsRoot(): Boolean {
        gapAtMinTheta = budgetGapAt(minTheta)
        gapAtMaxTheta = budgetGapAt(maxTheta)
        return gapAtMinTheta * gapAtMaxTheta <= 0.0
    }

    protected abstract fun search()

    fun optimize(): Boolean {
        val cheapest = model.minUnitCostAcrossItems
        if (budget < cheapest) {
            warnings += "The budget $budget is below the cheapest unit cost $cheapest, " +
                "so no item can be stocked. Setting every level to zero."
            model.clearStockLevels()
            return true
        }
        if (bracketsRoot()) {
            convergedWithinSearchLimit = true
            search()
        } else {
            finalTheta = if (abs(gapAtMinTheta) < abs(gapAtMaxTheta)) minTheta else maxTheta
            budgetGapAt(finalTheta)
            warnings += "No root in the multiplier interval ($minTheta, $maxTheta). " +
                "Gaps at the ends: f($minTheta) = $gapAtMinTheta, " +
                "f($maxTheta) = $gapAtMaxTheta. Taking the end with the smaller " +
                "absolute gap, theta = $finalTheta."
        }
        return withinBudget
    }

    companion object {
        /** The original's constant, kept as the default. See the warning in @sec-multiechelon-design-lagrange. */
        const val defaultThetaTolerance = 0.0001
    }
}

/**
 * Ported from `varimetric.VMLagrangeEnumerationAlgorithm`.
 *
 * Walks a grid of multipliers and keeps whichever produced the smallest absolute
 * budget gap. Stock levels are integers, so the cost is a step function of
 * `theta` and a sign change can hide between grid points; enumeration cannot
 * step over the best plan the way a bracketing method can.
 */
class LagrangeEnumerationAlgorithm(
    model: VariMetricModel,
    budget: Double,
    numberOfEvaluations: Int,
    lowerTheta: Double,
    upperTheta: Double,
    thetaTolerance: Double = defaultThetaTolerance,
) : LagrangeAlgorithm(model, budget, lowerTheta, upperTheta, numberOfEvaluations, thetaTolerance) {

    override fun search() {
        var smallestGap = Double.MAX_VALUE
        var bestTheta = Double.NaN
        val best = arrayOfNulls<IntArray>(model.numItems)
        val step = (maxTheta - minTheta) / numberOfEvaluations
        var theta = minTheta
        while (theta < maxTheta) {
            val found = model.items.map { optimizeItemAt(it, theta) }
            val gap = abs(budgetGap)
            if (gap < smallestGap) {
                smallestGap = gap
                bestTheta = theta
                found.forEachIndexed { k, levels -> best[k] = levels.copyOf() }
            }
            theta += step
        }
        model.items.forEachIndexed { i, vi -> best[i]?.let { vi.stockLevels = it } }
        finalTheta = bestTheta
    }
}

/**
 * Ported from `varimetric.VMLagrangeIterativeAlgorithm`.
 *
 * The original subclasses the JSL's `BisectionRootFinder` to add one stopping
 * rule, that the budget gap is small enough. The KSL's `BisectionRootFinder` is
 * final, so this uses it directly and converges on the multiplier rather than on
 * the gap. That is a real difference in the stopping rule and not only in the
 * plumbing: set [desiredPrecision] on the scale of the multiplier, which by
 * @eq-lagrange-newsvendor lives in `(0, 1/c)`.
 */
class LagrangeIterativeAlgorithm(
    model: VariMetricModel,
    budget: Double,
    numberOfEvaluations: Int,
    private val desiredPrecision: Double,
    lowerTheta: Double,
    upperTheta: Double,
    thetaTolerance: Double = defaultThetaTolerance,
) : LagrangeAlgorithm(model, budget, lowerTheta, upperTheta, numberOfEvaluations, thetaTolerance) {

    var iterationsExecuted: Int = 0
        private set

    var converged: Boolean = false
        private set

    override fun search() {
        val gap = FunctionIfc { theta -> budgetGapAt(theta) }
        val finder = BisectionRootFinder(
            func = gap,
            interval = Interval(minTheta, maxTheta),
            maxIter = numberOfEvaluations,
            desiredPrec = desiredPrecision,
        )
        finder.evaluate()
        iterationsExecuted = finder.iterationsExecuted
        converged = finder.hasConverged()
        finalTheta = finder.result
        // The finder's last evaluation need not be at its reported root, so put
        // the model back on the multiplier being returned.
        budgetGapAt(finalTheta)
        if (!converged) {
            warnings += "The bisection did not converge in $numberOfEvaluations iterations " +
                "at precision $desiredPrecision. theta = $finalTheta, " +
                "cost = ${model.totalStockingCost}, gap = $budgetGap."
        }
    }
}
