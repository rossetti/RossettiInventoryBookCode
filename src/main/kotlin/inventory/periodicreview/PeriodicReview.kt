package inventory.periodicreview

import inventory.continuousreview.LeadTimeDemand
import kotlin.math.sqrt

/**
 * Demand over an interval of arbitrary length, which is what periodic review
 * needs and continuous review does not.
 *
 * @sec-continuousreview-periodic shows that every measure of an (R, S) policy is a functional of
 * the demand over an interval whose length runs from `L` to `R + L` across the
 * review cycle. A single `LeadTimeDemand` fixes one interval, so the model has to
 * be handed a rule for building the distribution over any of them.
 */
fun interface DemandOverInterval {

    /** The distribution of demand over an interval of the given [length]. */
    fun over(length: Double): LeadTimeDemand

    companion object {

        /** Unit demands arriving in a Poisson process at [rate] per unit time. */
        fun poisson(rate: Double): DemandOverInterval {
            require(rate > 0.0) { "the demand rate must be positive" }
            return DemandOverInterval { LeadTimeDemand.poisson(rate * it) }
        }

        /**
         * A stationary demand process described by its first two moments per unit
         * time, with the family chosen ONCE by the rules of @sec-continuousreview-ltd and then
         * held for every interval length.
         *
         * Both moments scale linearly with the interval, which is @eq-basestock-backorders.
         * Notice that it is the VARIANCE that scales linearly and therefore the
         * standard deviation that scales with the square root, which is the point
         * that section makes and the most common place to go wrong.
         *
         * [familyChoiceLength] is the interval whose moments select the family, and
         * it should be the protection interval of the policy being evaluated. It is
         * a parameter rather than a constant because the family MUST NOT be
         * reselected at each length. `LeadTimeDemand.matched` switches to the gamma
         * once the mean passes a threshold, so choosing per length would let an
         * integration that runs from `L` to `R + L` cross a discrete-to-continuous
         * boundary part way through. The integrand would then jump, and the
         * quadrature in `RSModel` would be averaging two different models of the
         * same item. Selecting once and scaling the moments keeps one model.
         */
        fun stationary(
            rate: Double,
            variancePerUnitTime: Double,
            familyChoiceLength: Double,
        ): DemandOverInterval {
            require(rate > 0.0) { "the demand rate must be positive" }
            require(variancePerUnitTime > 0.0) { "the variance rate must be positive" }
            require(familyChoiceLength > 0.0) { "the family choice length must be positive" }
            val reference = LeadTimeDemand.matched(
                rate * familyChoiceLength,
                variancePerUnitTime * familyChoiceLength,
            )
            return when (reference.familyName) {
                "Poisson" -> DemandOverInterval { LeadTimeDemand.poisson(rate * it) }
                "negative binomial" -> DemandOverInterval {
                    LeadTimeDemand.negativeBinomial(rate * it, variancePerUnitTime * it)
                }
                else -> DemandOverInterval {
                    LeadTimeDemand.gamma(rate * it, variancePerUnitTime * it)
                }
            }
        }
    }
}

/** What an (R, S) policy achieves, the periodic counterpart of `RQPerformance`. */
data class RSPerformance(
    val orderUpToLevel: Int,
    val reviewInterval: Double,
    val orderFrequency: Double,
    val expectedBackorders: Double,
    val readyRate: Double,
    val fillRate: Double,
    val expectedOnHand: Double,
    val safetyStock: Double,
    val cycleStock: Double,
    val orderingCostRate: Double,
    val holdingCostRate: Double,
    val backorderCostRate: Double,
) {
    val totalCost: Double get() = orderingCostRate + holdingCostRate + backorderCostRate
}

/**
 * The (R, S) policy of @sec-continuousreview-periodic.
 *
 * The whole model is @eq-periodic-in, `IN = S - D(L + U)` with `U` uniform on
 * `[0, R)`. That is the base-stock model of @sec-continuousreview-basestock with the exposure
 * randomized over TIME, exactly as the (r, Q) model of @sec-continuousreview-batch is the
 * base-stock model with the position randomized over LEVELS. Everything below is
 * one of those two averages applied to a base-stock result.
 *
 * Two measures are closed form and need no quadrature. The order frequency is
 * `1/R` because an order is placed at every review, and the expected on hand is
 * @eq-periodic-onhand because the expectation of a uniform exposure is `lambda(L +
 * R/2)`. The backorder level and the ready rate are time averages over the review
 * cycle and are integrated numerically, by Simpson's rule on [nodes] intervals.
 *
 * Do NOT evaluate the measures at the end of the protection interval instead.
 * That instant is the worst of the cycle rather than a typical one, so
 * `G1` over `R + L` is an upper bound on the backorder level and not an estimate
 * of it; on the transformer of @exm-periodic-transformer it overstates it by a factor of 2.8.
 * [expectedBackordersAtWorst] is provided for the bound, and is named for it.
 */
class RSModel(
    val demandRate: Double,
    val orderCost: Double,
    val holdingCost: Double,
    val backorderCost: Double,
    val leadTime: Double,
    val reviewInterval: Double,
    val demand: DemandOverInterval,
    val nodes: Int = 128,
) {
    init {
        require(demandRate > 0.0) { "the demand rate must be positive" }
        require(orderCost >= 0.0) { "the ordering cost cannot be negative" }
        require(holdingCost > 0.0) { "the holding cost must be positive" }
        require(backorderCost > 0.0) { "the backorder cost must be positive" }
        require(leadTime >= 0.0) { "the lead time cannot be negative" }
        require(reviewInterval > 0.0) { "the review interval must be positive" }
        require(nodes >= 2 && nodes % 2 == 0) { "Simpson's rule needs an even node count" }
    }

    /** The protection interval, `tau = R + L`, @eq-protection-interval. */
    val protectionInterval: Double get() = reviewInterval + leadTime

    /** Expected demand over the protection interval. */
    val meanProtectionDemand: Double get() = demandRate * protectionInterval

    /** The mean exposure of @eq-periodic-onhand, `lambda(L + R/2)`. */
    val meanExposure: Double get() = demandRate * (leadTime + reviewInterval / 2.0)

    /** @eq-periodic-orderfreq. An order is placed at every review, whatever the demand. */
    val orderFrequency: Double get() = 1.0 / reviewInterval

    /** The newsvendor ratio of @eq-basestock-optimal, unchanged by the review interval. */
    val criticalRatio: Double get() = backorderCost / (backorderCost + holdingCost)

    /** Simpson's rule for the time average of [f] over one review cycle. */
    private fun cycleAverage(f: (LeadTimeDemand) -> Double): Double {
        val h = reviewInterval / nodes
        var sum = 0.0
        for (i in 0..nodes) {
            val weight = if (i == 0 || i == nodes) 1.0 else if (i % 2 == 1) 4.0 else 2.0
            sum += weight * f(demand.over(leadTime + i * h))
        }
        return sum * h / 3.0 / reviewInterval
    }

    /** `G1-bar` of @eq-periodic-timeaverage, the cycle-averaged first order loss function. */
    fun timeAveragedLossFirst(level: Int): Double =
        cycleAverage { it.lossFirst(level.toDouble()) }

    /** `G-bar` of @eq-periodic-timeaverage, the cycle-averaged distribution function. */
    fun timeAveragedCdf(level: Int): Double = cycleAverage { it.cdf(level.toDouble()) }

    /** @eq-periodic-backorders. */
    fun expectedBackorders(level: Int): Double = timeAveragedLossFirst(level)

    /**
     * The backorder level at the END of the protection interval, which is an
     * UPPER BOUND on [expectedBackorders] and is the right quantity for a cycle
     * service level. See the class comment before using it for anything else.
     */
    fun expectedBackordersAtWorst(level: Int): Double =
        demand.over(protectionInterval).lossFirst(level.toDouble())

    /** @eq-periodic-onhand. Exact, and needing no quadrature. */
    fun expectedOnHand(level: Int): Double =
        level - meanExposure + expectedBackorders(level)

    /** Safety stock, the level in excess of demand over the protection interval. */
    fun safetyStock(level: Int): Double = level - meanProtectionDemand

    /** The cycle stock the calendar creates, `lambda R / 2` of @eq-periodic-onhand. */
    val cycleStock: Double get() = demandRate * reviewInterval / 2.0

    /** @eq-periodic-readyrate. */
    fun readyRate(level: Int): Double = timeAveragedCdf(level - 1)

    /**
     * @eq-periodic-fillrate, derived from the shortage per cycle rather than from a time
     * average, so it agrees with [readyRate] only when PASTA applies.
     */
    fun fillRate(level: Int): Double {
        val short = demand.over(protectionInterval).lossFirst(level.toDouble()) -
            demand.over(leadTime).lossFirst(level.toDouble())
        return 1.0 - short / (demandRate * reviewInterval)
    }

    /** @eq-periodic-cost. */
    fun cost(level: Int): Double =
        orderCost * orderFrequency +
            holdingCost * expectedOnHand(level) +
            backorderCost * expectedBackorders(level)

    /**
     * @eq-periodic-optimal, the critical ratio applied to the CYCLE-AVERAGED
     * distribution function and not to demand over the full protection interval.
     * Using the latter answers a different question and returns a level that is
     * too high; @exm-periodic-transformer prices the difference.
     */
    fun optimalLevel(): Int {
        var level = 0
        while (timeAveragedCdf(level) < criticalRatio) {
            level++
            check(level <= MAX_LEVEL) { "no level below $MAX_LEVEL reaches the critical ratio" }
        }
        return level
    }

    fun evaluate(level: Int): RSPerformance {
        val backorders = expectedBackorders(level)
        val onHand = expectedOnHand(level)
        return RSPerformance(
            orderUpToLevel = level,
            reviewInterval = reviewInterval,
            orderFrequency = orderFrequency,
            expectedBackorders = backorders,
            readyRate = readyRate(level),
            fillRate = fillRate(level),
            expectedOnHand = onHand,
            safetyStock = safetyStock(level),
            cycleStock = cycleStock,
            orderingCostRate = orderCost * orderFrequency,
            holdingCostRate = holdingCost * onHand,
            backorderCostRate = backorderCost * backorders,
        )
    }

    /** The same model at a different review interval, for scanning over `R`. */
    fun atInterval(interval: Double): RSModel = RSModel(
        demandRate, orderCost, holdingCost, backorderCost,
        leadTime, interval, demand, nodes,
    )

    companion object {
        private const val MAX_LEVEL = 100_000

        /**
         * @eq-periodic-optimal-interval, the review interval that balances the fixed charge
         * against the cycle stock it creates. It is the economic order quantity
         * expressed as a time supply, and it ignores shortages entirely, which
         * @tbl-periodic-interval shows costs under a percent on the chapter's own item.
         */
        fun economicInterval(
            orderCost: Double,
            holdingCost: Double,
            demandRate: Double,
        ): Double {
            require(orderCost > 0.0 && holdingCost > 0.0 && demandRate > 0.0) {
                "the economic interval needs positive cost and demand parameters"
            }
            return sqrt(2.0 * orderCost / (holdingCost * demandRate))
        }
    }
}
