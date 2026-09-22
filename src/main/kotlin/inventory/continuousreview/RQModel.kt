package inventory.continuousreview

import kotlin.math.max
import kotlin.math.sqrt

/** A policy: order [orderQuantity] units whenever the inventory position reaches [reorderPoint]. */
data class RQPolicy(val reorderPoint: Int, val orderQuantity: Int) {
    init {
        require(orderQuantity >= 1) { "the order quantity must be at least one unit" }
    }

    override fun toString(): String = "(r = $reorderPoint, Q = $orderQuantity)"
}

/** The measures of @sec-continuousreview-measures, evaluated at one policy. */
data class RQPerformance(
    val policy: RQPolicy,
    val orderFrequency: Double,
    val expectedBackorders: Double,
    /** @eq-rq-backorder-second. @sec-continuousreview-batch-variance explains why the mean alone is misleading. */
    val varianceBackorders: Double,
    val readyRate: Double,
    val fillRate: Double,
    val expectedOnHand: Double,
    val orderingCostRate: Double,
    val holdingCostRate: Double,
    val backorderCostRate: Double,
) {
    val totalCost: Double get() = orderingCostRate + holdingCostRate + backorderCostRate

    /**
     * The measures laid out the way @tbl-rq-transformer lays them out.
     *
     * A data class prints its fields at full precision, which is unreadable and
     * implies an accuracy the inputs do not have. Costs carry two decimals, levels
     * carry four, and every line names its unit.
     */
    override fun toString(): String = buildString {
        appendLine("policy $policy")
        appendLine("  order frequency    %10.4f orders per unit time".format(orderFrequency))
        appendLine("  expected backorders%10.4f units".format(expectedBackorders))
        appendLine("  ready rate         %10.4f".format(readyRate))
        appendLine("  fill rate          %10.4f".format(fillRate))
        appendLine("  expected on hand   %10.4f units".format(expectedOnHand))
        appendLine("  ordering           %10.2f per unit time".format(orderingCostRate))
        appendLine("  holding            %10.2f per unit time".format(holdingCostRate))
        appendLine("  backorder          %10.2f per unit time".format(backorderCostRate))
        append("  total              %10.2f per unit time".format(totalCost))
    }
}

/**
 * One item under continuous review with an `(r, Q)` policy. Chapter 8.
 *
 * Holds the four parameters and the lead time demand distribution, and evaluates
 * the measures of @sec-continuousreview-measures from them. Nothing here searches; @sec-continuousreview-algorithm and
 * [RQOptimizer] do that.
 *
 * @param demandRate demand in units per unit time, the `lambda` of the chapter
 * @param orderCost the fixed cost of placing an order, `k`
 * @param holdingCost the cost of holding one unit for one unit of time, `h`
 * @param backorderCost the cost of owing one unit for one unit of time, `b`
 * @param leadTimeDemand the distribution of `D(L)`, @sec-continuousreview-ltd
 */
class RQModel(
    val demandRate: Double,
    val orderCost: Double,
    val holdingCost: Double,
    val backorderCost: Double,
    val leadTimeDemand: LeadTimeDemand,
) {
    init {
        require(demandRate > 0.0) { "the demand rate must be positive" }
        require(orderCost >= 0.0) { "the order cost cannot be negative" }
        require(holdingCost > 0.0) { "the holding cost must be positive" }
        require(backorderCost > 0.0) { "the backorder cost must be positive" }
    }

    /** `theta`, the mean of the lead time demand. */
    val theta: Double get() = leadTimeDemand.mean

    /** `w = b/(b+h)`, the ratio @eq-basestock-optimal compares the distribution function against. */
    val criticalRatio: Double get() = backorderCost / (backorderCost + holdingCost)

    /** `k * lambda`, the ordering cost of a policy that orders one unit at a time. */
    val fixedCostRate: Double get() = orderCost * demandRate

    // ----------------------------------------------------------------------
    // @sec-continuousreview-batch, the measures of a general batch size
    // ----------------------------------------------------------------------

    /** @eq-rq-backorders. */
    fun expectedBackorders(r: Int, q: Int): Double {
        requirePolicy(r, q)
        return (leadTimeDemand.lossSecond(r.toDouble()) -
            leadTimeDemand.lossSecond((r + q).toDouble())) / q
    }

    /** @eq-rq-readyrate. */
    fun readyRate(r: Int, q: Int): Double {
        requirePolicy(r, q)
        return 1.0 - (leadTimeDemand.lossFirst(r.toDouble()) -
            leadTimeDemand.lossFirst((r + q).toDouble())) / q
    }

    /**
     * @eq-type2, the fill rate.
     *
     * @eq-fr-equals-rr shows that the fill rate equals the ready rate when demand
     * arrives one unit at a time, which is the case every model in this chapter
     * treats. The two are separate methods because they are separate measures,
     * and an item whose demand arrives in lots has two different numbers here.
     */
    fun fillRate(r: Int, q: Int): Double = readyRate(r, q)

    /** @eq-rq-onhand. */
    fun expectedOnHand(r: Int, q: Int): Double {
        requirePolicy(r, q)
        return expectedPosition(r, q) - theta + expectedBackorders(r, q)
    }

    /**
     * `E[IP]`, and the one place the two families part company.
     *
     * @eq-ip-uniform puts the inventory position uniform on the INTEGERS
     * `r+1` through `r+Q`, whose mean is `r + (Q+1)/2`. That is the exact
     * statement, and it is the one @sec-continuousreview-batch derives and @tbl-rq-transformer prints.
     *
     * When @sec-continuousreview-approx-family chooses a continuous family because the counts are large,
     * the position is taken uniform on the INTERVAL `[r, r+Q]` instead, whose mean
     * is `r + Q/2`, and the sums over the window become integrals. Both are exact
     * within their own convention; they differ by half a unit of stock, which is
     * `h/2` per year, and the chapter warns that the `(Q+1)/2` is the term most
     * often written as `Q/2` by mistake.
     */
    fun expectedPosition(r: Int, q: Int): Double {
        requirePolicy(r, q)
        return if (leadTimeDemand.isDiscrete) r + (q + 1.0) / 2.0 else r + q / 2.0
    }

    /**
     * The SECOND MOMENT of the backorder level, @eq-rq-backorder-second.
     *
     * Averaging Equation C.12 over the band collapses a sum of second order loss
     * functions into a difference of THIRD order ones, by Equation C.13:
     *
     * ```
     *   E[B^2] = (1/Q) { 2[G3(r) - G3(r+Q)] + [G2(r) - G2(r+Q)] }   discrete
     *   E[B^2] = (2/Q)  [G3(r) - G3(r+Q)]                           continuous
     * ```
     *
     * The trailing `G2` difference is the `+ G1(b)` of Equation C.12, which is
     * present only in the discrete case. Notice that it is exactly the mean
     * backorder level, so the discrete form is the continuous one plus `B-bar`.
     *
     * Do NOT compute this by summing `G2` across the band. That is the same
     * quantity only for a discrete lead time demand, where `G2` is itself a tail
     * sum; for a continuous family the band is an interval rather than a set of
     * integers and the sum is not the average. The difference is not small: on a
     * gamma it runs several percent low, and it costs O(Q) rather than O(1).
     */
    fun expectedBackordersSecondMoment(r: Int, q: Int): Double {
        requirePolicy(r, q)
        val third = leadTimeDemand.lossThird(r.toDouble()) -
            leadTimeDemand.lossThird((r + q).toDouble())
        val continuousPart = 2.0 * third / q
        return if (leadTimeDemand.isDiscrete) {
            continuousPart + expectedBackorders(r, q)
        } else {
            continuousPart
        }
    }

    /**
     * The VARIANCE of the backorder level, @eq-rq-backorder-second.
     *
     * @sec-continuousreview-batch-variance makes the point this exists for: the mean backorder level is
     * the average of a quantity that is zero most of the time and occasionally
     * large, so the mean alone describes a state the system is rarely in. On the
     * transformer the standard deviation is nearly five times the mean.
     *
     * Chapter 9 needs it for a second reason. The variance of the customer wait
     * at a hub is this quantity divided by the square of the demand rate, and
     * that wait is what a spoke experiences as part of its own lead time.
     */
    fun varianceBackorders(r: Int, q: Int): Double {
        val second = expectedBackordersSecondMoment(r, q)
        val mean = expectedBackorders(r, q)
        return max(0.0, second - mean * mean)
    }

    /** Orders placed per unit time. */
    fun orderFrequency(q: Int): Double {
        require(q >= 1) { "the order quantity must be at least one unit" }
        return demandRate / q
    }

    /** @eq-rq-cost. */
    fun cost(r: Int, q: Int): Double {
        requirePolicy(r, q)
        return windowTotal(r, q) / q
    }

    fun cost(policy: RQPolicy): Double = cost(policy.reorderPoint, policy.orderQuantity)

    /** Every measure of @sec-continuousreview-measures at one policy. */
    fun evaluate(policy: RQPolicy): RQPerformance {
        val r = policy.reorderPoint
        val q = policy.orderQuantity
        val backorders = expectedBackorders(r, q)
        val onHand = expectedOnHand(r, q)
        val ready = readyRate(r, q)
        return RQPerformance(
            policy = policy,
            orderFrequency = orderFrequency(q),
            expectedBackorders = backorders,
            varianceBackorders = varianceBackorders(r, q),
            readyRate = ready,
            fillRate = ready,
            expectedOnHand = onHand,
            orderingCostRate = orderCost * orderFrequency(q),
            holdingCostRate = holdingCost * onHand,
            backorderCostRate = backorderCost * backorders,
        )
    }

    // ----------------------------------------------------------------------
    // @sec-continuousreview-basestock, the base-stock model
    // ----------------------------------------------------------------------

    /**
     * @eq-basestock-costfn, the cost of a base-stock policy at level [s].
     *
     * This is [cost] at `q = 1` with the ordering term removed, which is the
     * argument of @sec-continuousreview-basestock: one-for-one replenishment orders once per demand
     * whatever the level is, so `k` does not depend on the decision.
     */
    fun baseStockCost(s: Int): Double = windowTotal(s - 1, 1) - fixedCostRate

    /** @eq-basestock-optimal, the smallest `S` whose distribution function reaches the critical ratio. */
    fun optimalBaseStock(): Int = leadTimeDemand.smallestLevelReaching(criticalRatio)

    // ----------------------------------------------------------------------
    // The window, which is what the algorithm of @sec-continuousreview-algorithm actually moves
    // ----------------------------------------------------------------------

    /**
     * `k*lambda + sum of C(s) over the Q values the policy averages`, in TWO loss
     * function evaluations rather than `Q` of them.
     *
     * @eq-rq-cost-average writes the cost of an `(r, Q)` policy as the fixed term plus
     * the average of `Q` consecutive base-stock costs. Summing @eq-rq-onhand over
     * that range gives `Q(r + (Q+1)/2 - theta)` in closed form, and summing
     * @eq-rq-backorders telescopes into the difference of two second order loss
     * functions, so the whole window is
     *
     * ```
     *   k*lambda + h*Q*(E[IP] - theta) + (h + b)*[G2(r) - G2(r+Q)]
     * ```
     *
     * with `E[IP]` from [expectedPosition]. The telescoping is Equation C.10 for a
     * discrete family and the fundamental theorem of calculus for a continuous
     * one, since the second order loss function differentiates to minus the first.
     *
     * The saving matters because the search of @sec-continuousreview-algorithm evaluates a window at
     * every step. Computing it term by term makes each step cost `O(Q)`; this
     * makes it cost `O(1)`, which is what allows the jump search of [RQOptimizer]
     * to take steps of any size for the same price as a step of one.
     */
    fun windowTotal(r: Int, q: Int): Double {
        requirePolicy(r, q)
        val width = q.toDouble()
        val shortage = leadTimeDemand.lossSecond(r.toDouble()) -
            leadTimeDemand.lossSecond((r + q).toDouble())
        return fixedCostRate +
            holdingCost * width * (expectedPosition(r, q) - theta) +
            (holdingCost + backorderCost) * shortage
    }

    /**
     * The same item under a different lead time demand distribution.
     *
     * @sec-continuousreview-updating revises the mean and standard deviation of `D(L)` every
     * forecast period, and a question such as what a shorter lead time is worth
     * changes only this one input. The costs and the demand rate carry over.
     */
    fun withLeadTimeDemand(leadTimeDemand: LeadTimeDemand): RQModel =
        RQModel(demandRate, orderCost, holdingCost, backorderCost, leadTimeDemand)

    /**
     * The same quantity summed term by term, for checking [windowTotal].
     *
     * Only the discrete convention has a term by term form. Under a continuous
     * family the window is an integral and the check is quadrature, which the
     * tests perform instead.
     */
    fun windowTotalBySummation(r: Int, q: Int): Double {
        requirePolicy(r, q)
        require(leadTimeDemand.isDiscrete) {
            "summing the window term by term is a discrete operation, and " +
                "${leadTimeDemand.familyName} lead time demand is continuous"
        }
        var total = fixedCostRate
        for (s in (r + 1)..(r + q)) {
            val backorders = leadTimeDemand.lossFirst(s.toDouble())
            total += holdingCost * (s - theta + backorders) + backorderCost * backorders
        }
        return total
    }

    /**
     * A reorder point may be negative, and the search reaches such policies on
     * items whose backorders are cheap. [LeadTimeDemand.lossFirst] and
     * [LeadTimeDemand.lossSecond] are defined there, and the expected on-hand
     * level falls out as exactly zero, since every unit of stock is owed.
     */
    private fun requirePolicy(r: Int, q: Int) {
        require(q >= 1) { "the order quantity must be at least one unit, was $q" }
    }

    override fun toString(): String =
        ("(r, Q) item: lambda = %.4f, k = %.2f, h = %.2f, b = %.2f, critical ratio %.6f%n  %s")
            .format(demandRate, orderCost, holdingCost, backorderCost, criticalRatio, leadTimeDemand)

    companion object {
        /**
         * An item whose backorder cost is implied by a ready rate target rather
         * than estimated. @eq-implied-backorder.
         *
         * @sec-continuousreview-service-implied is the argument for reading a target back into the cost it
         * implies: a service target is not an escape from `b`, it is a statement
         * about `b` made without saying so. This constructor makes the statement
         * explicit so that the number can be looked at.
         */
        fun fromReadyRateTarget(
            demandRate: Double,
            orderCost: Double,
            holdingCost: Double,
            readyRateTarget: Double,
            leadTimeDemand: LeadTimeDemand,
        ): RQModel {
            require(readyRateTarget > 0.0 && readyRateTarget < 1.0) {
                "a ready rate target must lie strictly between 0 and 1, was $readyRateTarget"
            }
            val implied = readyRateTarget * holdingCost / (1.0 - readyRateTarget)
            return RQModel(demandRate, orderCost, holdingCost, implied, leadTimeDemand)
        }

        /** `h = ic`, the carrying charge of @sec-costparams-h applied to a unit cost. */
        fun holdingCostFrom(carryingCharge: Double, unitCost: Double): Double {
            require(carryingCharge > 0.0) { "the carrying charge must be positive" }
            require(unitCost > 0.0) { "the unit cost must be positive" }
            return carryingCharge * unitCost
        }
    }
}

/**
 * `Q_k` of @eq-deterministic-reference, the deterministic reference quantity `sqrt(2*k*lambda/(h*w))`.
 *
 * @sec-continuousreview-bounds uses it to bound the optimal batch and @sec-continuousreview-algorithm-warm uses it to
 * start the search near the answer instead of at one unit.
 */
fun RQModel.deterministicReferenceQuantity(): Double =
    if (orderCost == 0.0) 1.0
    else sqrt(2.0 * orderCost * demandRate / (holdingCost * criticalRatio))
