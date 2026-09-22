package inventory.continuousreview

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * What is known about the answer before any search runs. @sec-continuousreview-bounds.
 *
 * @param batchLower a lower bound on the optimal order quantity
 * @param batchUpper an upper bound on the optimal order quantity
 * @param baseStockLevel `S*`, @eq-basestock-optimal
 * @param baseStockCost `C(S*)`, the cost of the best one-for-one policy
 * @param costLower the lower bound of @eq-optimal-bounds
 * @param costUpper the upper bound of @eq-optimal-bounds
 */
data class RQBounds(
    val batchLower: Int,
    val batchUpper: Int,
    val baseStockLevel: Int,
    val baseStockCost: Double,
    val costLower: Double,
    val costUpper: Double,
) {
    /** True when [cost] lies inside the interval of @eq-optimal-bounds. */
    fun containsCost(cost: Double, tolerance: Double = 1.0e-9): Boolean =
        cost >= costLower - tolerance && cost <= costUpper + tolerance

    /** True when the policy satisfies the structure of @eq-optimal-structure, `r < S* <= r + Q`. */
    fun hasOptimalStructure(policy: RQPolicy): Boolean =
        policy.reorderPoint < baseStockLevel &&
            baseStockLevel <= policy.reorderPoint + policy.orderQuantity

    override fun toString(): String =
        ("bounds: Q in [%d, %d], S* = %d costing %.2f, C* in [%.2f, %.2f]")
            .format(batchLower, batchUpper, baseStockLevel, baseStockCost, costLower, costUpper)
}

/**
 * The bounds of @sec-continuousreview-bounds, computed in one pass.
 *
 * The batch bounds are the ones Zipkin's Theorem 6.5.1 gives. The lower bound is
 * the deterministic reference quantity of @eq-deterministic-reference, and the width above it
 * is the cost of the best one-for-one policy divided by `h*w`. The cost bounds
 * are @eq-optimal-bounds.
 *
 * Costs one evaluation of the base-stock cost beyond the newsvendor solve, and
 * is worth paying for twice over: [RQOptimizer] uses the batch bounds to stop
 * its jump search from running away, and a reader can use the cost bounds to
 * check any answer, including one produced by other software.
 */
fun RQModel.bounds(): RQBounds {
    val level = optimalBaseStock()
    val levelCost = baseStockCost(level)
    val reference = deterministicReferenceQuantity()

    val lower = maxOf(1, floor(reference + 0.5).toInt())
    val width = levelCost / (holdingCost * criticalRatio)
    val upper = maxOf(lower, lower + floor(width + 0.5).toInt())

    // @eq-optimal-bounds. The lower bound is the deterministic cost of @eq-deterministic-reference,
    // which is what the item would cost if demand were known, and uncertainty can
    // only make things worse. The upper bound adds that and the base-stock
    // reference cost of @eq-basestock-reference in quadrature.
    val ck = sqrt(2.0 * orderCost * demandRate * holdingCost * criticalRatio)
    val cInfinity = sqrt(backorderCost * holdingCost) * leadTimeDemand.stdDev
    val costLower = ck
    val costUpper = sqrt(ck * ck + cInfinity * cInfinity)

    return RQBounds(
        batchLower = lower,
        batchUpper = upper,
        baseStockLevel = level,
        baseStockCost = levelCost,
        costLower = costLower,
        costUpper = costUpper,
    )
}
