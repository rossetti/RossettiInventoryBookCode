package inventory.continuousreview

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which of the two searches of [RQOptimizer] produced an answer. */
enum class SearchMethod {
    /** Algorithm Optimize_rq as @sec-continuousreview-algorithm-steps prints it, one unit of `Q` at a time. */
    UNIT_BATCH,

    /** The warm start of @sec-continuousreview-algorithm-warm followed by a per-unit search. */
    WARM_START,

    /** The warm start followed by a doubling-block search and an exact polish. */
    JUMP_SEARCH,
}

/** An optimal policy together with what it took to find it. */
data class RQSolution(
    val policy: RQPolicy,
    val cost: Double,
    val method: SearchMethod,
    val windowEvaluations: Int,
    val bounds: RQBounds,
) {
    val reorderPoint: Int get() = policy.reorderPoint
    val orderQuantity: Int get() = policy.orderQuantity

    override fun toString(): String =
        "%s costing %.2f, found by %s in %d window evaluations"
            .format(policy, cost, method.name.lowercase().replace('_', ' '), windowEvaluations)
}

/**
 * The optimal integer `(r, Q)` policy. @sec-continuousreview-algorithm.
 *
 * Two routines are here and they return the same answer.
 *
 * [optimizeFromUnitBatch] is Algorithm Optimize_rq exactly as @sec-continuousreview-algorithm-steps
 * prints it: start at `Q = 1` and `r = S* - 1`, add the cheaper of the two
 * neighbouring base-stock costs, and stop when the value being added is no longer
 * below the current average. It is the algorithm to read, and its loop runs once
 * per unit of `Q*`.
 *
 * [optimize] is the same algorithm with the three improvements @sec-continuousreview-algorithm-warm
 * describes, and it is the one to call. It starts from the deterministic
 * reference quantity instead of from one unit, it searches in whichever direction
 * `Q` needs to move rather than only upward, and on an item whose optimal batch
 * is large it takes steps that double. The step size is free because
 * [RQModel.windowTotal] costs the same whatever the batch is.
 *
 * The doubling search is fast and not exact. It commits a large step on one edge
 * of the window, which can build a window skewed to one side and carry `Q` past
 * the optimum to a value that is still cheaper than where it started. A search
 * that only grows `Q` cannot then walk back. [optimize] therefore finishes every
 * doubling search with an exact per-unit search in both directions from the
 * result, which recovers the optimum at a cost proportional to the overshoot.
 * The tests assert that the two routines agree on every item they are given.
 */
class RQOptimizer(
    val model: RQModel,
    val settings: Settings = Settings(),
) {
    /**
     * @param jumpThreshold above this warm-start batch or bounds width, the
     *   doubling search is used instead of the per-unit one
     * @param maxIterations a cap on each loop, so that a badly posed item fails
     *   with a message rather than running forever
     */
    data class Settings(
        val jumpThreshold: Int = 32,
        val maxIterations: Int = 10_000,
    ) {
        init {
            require(jumpThreshold >= 1) { "the jump threshold must be at least one" }
            require(maxIterations >= 1) { "the iteration cap must be at least one" }
        }
    }

    private var evaluations = 0

    /** Algorithm Optimize_rq of @sec-continuousreview-algorithm-steps, unchanged. */
    fun optimizeFromUnitBatch(): RQSolution {
        evaluations = 0
        val bounds = model.bounds()
        val star = bounds.baseStockLevel

        // Step 0, initialize.
        var q = 1
        var r = star - 1
        var best = model.fixedCostRate + model.baseStockCost(star)

        var iterations = 0
        while (true) {
            if (++iterations > settings.maxIterations) {
                throw IllegalStateException(
                    "Algorithm Optimize_rq passed ${settings.maxIterations} steps at " +
                        "r = $r, Q = $q, C = $best. Check that the cost column is convex, " +
                        "which @sec-continuousreview-algorithm-cautions warns is not automatic."
                )
            }

            // Step 1, the next smallest base-stock cost.
            val below = model.baseStockCost(r)
            val above = model.baseStockCost(r + q + 1)
            val next = min(below, above)
            evaluations += 2

            // Step 2, test for termination.
            if (next >= best) break

            // Step 3, extend the window.
            q += 1
            best -= (best - next) / q
            if (below <= above) r -= 1
        }

        return RQSolution(RQPolicy(r, q), best, SearchMethod.UNIT_BATCH, evaluations, bounds)
    }

    /** The same optimum, reached the way @sec-continuousreview-algorithm-warm describes. */
    fun optimize(): RQSolution {
        evaluations = 0
        val bounds = model.bounds()

        val startBatch = warmStartBatch(bounds)
        val startPoint = warmStartPoint(bounds, startBatch)
        val useJump = startBatch > settings.jumpThreshold ||
            (bounds.batchUpper - bounds.batchLower) > settings.jumpThreshold

        var window = bestWindowAt(startBatch, startPoint)
        var method = SearchMethod.WARM_START

        if (useJump) {
            method = SearchMethod.JUMP_SEARCH
            window = doublingSearch(window, bounds)
        }
        // The exact search runs in both directions in either case. After a
        // doubling search it is the polish that recovers the optimum; after a
        // warm start it is the search itself.
        window = unitSearch(window, bounds)

        return RQSolution(
            window.policy(), window.cost, method, evaluations, bounds
        )
    }

    // ------------------------------------------------------------------
    // The warm start of @sec-continuousreview-algorithm-warm
    // ------------------------------------------------------------------

    private fun warmStartBatch(bounds: RQBounds): Int {
        val reference = model.deterministicReferenceQuantity().roundToInt()
        return reference.coerceIn(bounds.batchLower, bounds.batchUpper).coerceAtLeast(1)
    }

    /**
     * `r` near `S* - beta*Q`, with `beta` at the midpoint of the interval
     * @sec-continuousreview-bounds-structure gives, then pulled inside the band of @eq-optimal-structure.
     */
    private fun warmStartPoint(bounds: RQBounds, batch: Int): Int {
        val w = model.criticalRatio
        val low = min(1.0 - w, 0.5)
        val high = max(1.0 - w, 0.5)
        val beta = (low + high) / 2.0
        val raw = bounds.baseStockLevel - (beta * batch).roundToInt()
        return raw.coerceIn(bounds.baseStockLevel - batch, bounds.baseStockLevel - 1)
    }

    // ------------------------------------------------------------------
    // The two searches
    // ------------------------------------------------------------------

    /**
     * Slide the window at a fixed batch size until no neighbour is cheaper.
     *
     * The step doubles while it keeps improving and halves when it does not, so a
     * seed far from the best reorder point is reached in a number of evaluations
     * that grows with the logarithm of the distance. The loop can only return
     * after a failure at a step of one, which is the exact optimality condition
     * for the convex sequence of @sec-continuousreview-optimization, so the doubling costs no accuracy.
     *
     * `C(s)` rises without bound in both directions, so the slide stops of its own
     * accord and needs no artificial floor on `r`.
     */
    private fun bestWindowAt(batch: Int, seedPoint: Int): Window {
        var current = window(seedPoint, batch)
        var step = 1
        var iterations = 0
        while (true) {
            if (++iterations > settings.maxIterations) {
                throw IllegalStateException(
                    "sliding the window at Q = $batch passed ${settings.maxIterations} steps."
                )
            }
            val left = window(current.point - step, batch)
            val right = window(current.point + step, batch)
            val best = if (left.total <= right.total) left else right
            if (best.total < current.total) {
                current = best
                step *= 2
            } else {
                if (step == 1) return current
                step /= 2
            }
        }
    }

    /** One unit of `Q` at a time, in whichever direction improves, tracking the best seen. */
    private fun unitSearch(start: Window, bounds: RQBounds): Window {
        var best = start
        for (direction in intArrayOf(1, -1)) {
            var current = start
            var iterations = 0
            while (true) {
                if (++iterations > settings.maxIterations) {
                    throw IllegalStateException(
                        "the per-unit search passed ${settings.maxIterations} steps at " +
                            "${current.policy()}."
                    )
                }
                val next = current.batch + direction
                if (next < 1 || next > bounds.batchUpper) break
                val candidate = bestWindowAt(next, current.point)
                if (candidate.cost >= current.cost) break
                current = candidate
                if (current.cost < best.cost) best = current
            }
        }
        return best
    }

    /**
     * Steps that double while they improve and halve when they do not.
     *
     * Reaches the neighbourhood of the optimum in a number of window evaluations
     * that grows with the logarithm of the distance rather than with the distance
     * itself. It is not exact, which is why [optimize] follows it with
     * [unitSearch].
     */
    private fun doublingSearch(start: Window, bounds: RQBounds): Window {
        var best = start
        for (direction in intArrayOf(1, -1)) {
            var current = start
            var step = 1
            var iterations = 0
            while (step >= 1) {
                if (++iterations > settings.maxIterations) {
                    throw IllegalStateException(
                        "the doubling search passed ${settings.maxIterations} steps at " +
                            "${current.policy()}."
                    )
                }
                val next = current.batch + direction * step
                if (next < 1 || next > bounds.batchUpper) {
                    if (step == 1) break
                    step /= 2
                    continue
                }
                val candidate = bestWindowAt(next, current.point)
                if (candidate.cost < current.cost) {
                    current = candidate
                    if (current.cost < best.cost) best = current
                    step *= 2
                } else {
                    if (step == 1) break
                    step /= 2
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------------

    private fun window(point: Int, batch: Int): Window {
        evaluations += 1
        return Window(point, batch, model.windowTotal(point, batch))
    }

    private class Window(val point: Int, val batch: Int, val total: Double) {
        val cost: Double get() = total / batch
        fun policy(): RQPolicy = RQPolicy(point, batch)
    }
}
