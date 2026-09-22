package inventory.continuousreview

import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.LossFunctionDistributionIfc
import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.distributions.Poisson
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The distribution of demand over a lead time, `D(L)` of @sec-continuousreview-ltd.
 *
 * Every formula in Chapter 8 is a functional of this distribution and of nothing
 * else, so this class is the only place a demand model enters the chapter's code.
 * It wraps a KSL distribution for the parts the KSL already computes, and adds
 * the one part it does not: the loss functions of Appendix C evaluated at a
 * NEGATIVE argument, which the optimizer needs whenever it examines a reorder
 * point below zero.
 *
 * The extension is not the same for the two cases. For `x < 0` every outcome
 * exceeds `x`, so the positive parts drop out of Equation C.9 and
 *
 * ```
 *   G1(x) = mean - x                                  both cases
 *   G2(x) = (1/2)[variance + (mean - x)^2 - (mean - x)]   discrete
 *   G2(x) = (1/2)[variance + (mean - x)^2]                continuous
 * ```
 *
 * The discrete form carries the extra term because Equation C.10 defines the
 * discrete second order loss function with `(X - x - 1)` and not with a square.
 * Using the continuous form on a Poisson lead time demand overstates `G2(-1)` by
 * half the mean, which is an error of a few percent in a cost and of the wrong
 * sign in an optimization.
 */
class LeadTimeDemand private constructor(
    val distribution: LossFunctionDistributionIfc,
    val familyName: String,
    val mean: Double,
    val variance: Double,
    /** `E[X^3]`, the third RAW moment. Needed only by [lossThird]. */
    val thirdMoment: Double,
    val isDiscrete: Boolean,
) {
    init {
        require(mean > 0.0) { "lead time demand must have a positive mean" }
        require(variance > 0.0) { "lead time demand must have a positive variance" }
    }

    val stdDev: Double get() = sqrt(variance)

    /** The diagnostic of Equation C.1, which selects a discrete family. */
    val varianceToMeanRatio: Double get() = variance / mean

    fun cdf(x: Double): Double = distribution.cdf(x)

    fun complementaryCdf(x: Double): Double = 1.0 - distribution.cdf(x)

    /** The smallest `x` whose distribution function reaches [p]. */
    fun inverseCdf(p: Double): Double {
        require(p > 0.0 && p < 1.0) { "p must lie strictly between 0 and 1, was $p" }
        return distribution.invCDF(p)
    }

    /** First order loss function, extended below zero. Appendix C, Equation C.9. */
    fun lossFirst(x: Double): Double =
        if (x >= 0.0) distribution.firstOrderLossFunction(x) else mean - x

    /** Second order loss function, extended below zero. Appendix C, Equation C.10. */
    fun lossSecond(x: Double): Double {
        if (x >= 0.0) return distribution.secondOrderLossFunction(x)
        val excess = mean - x
        val squared = variance + excess * excess
        return if (isDiscrete) 0.5 * (squared - excess) else 0.5 * squared
    }

    /**
     * Third order loss function, Appendix C, Equation C.11, extended below zero.
     *
     * `Var[B]` of an (r, Q) policy is the one quantity in the book that needs an
     * order above the second: @eq-rq-backorder-second collapses a sum of second order loss
     * functions across the band into a difference of third order ones, by
     * Equation C.13. The KSL supplies loss functions to the second order only, so
     * this is computed here.
     *
     * For `x < 0` every outcome exceeds `x`, so the positive parts drop out of
     * Equation C.12 exactly as they do for the lower orders, and what remains is
     * a third moment about `x`. That is why this class carries [thirdMoment]: the
     * mean and the variance are not enough once the order passes two.
     *
     * The discrete branch is the accumulating loop of Equation C.14 and is O(x).
     * That is the procedure the appendix specifies, and at the stock levels a
     * textbook problem reaches it is not worth replacing.
     */
    fun lossThird(x: Double): Double {
        if (x < 0.0) {
            // Shift to y = X - x with d = -x > 0, then take the third binomial
            // moment (discrete) or the third raw moment (continuous) of y.
            val d = -x
            val m1 = mean + d
            val m2 = (variance + mean * mean) + 2.0 * d * mean + d * d
            val m3 = thirdMoment + 3.0 * d * (variance + mean * mean) +
                3.0 * d * d * mean + d * d * d
            return if (isDiscrete) (m3 - 3.0 * m2 + 2.0 * m1) / 6.0 else m3 / 6.0
        }
        if (isDiscrete) {
            // Equation C.14: G3(b) = G3(0) - sum_{0 < j <= b} G2(j).
            val b = floor(x).toInt()
            var total = (thirdMoment - 3.0 * (variance + mean * mean) + 2.0 * mean) / 6.0
            for (j in 1..b) total -= distribution.secondOrderLossFunction(j.toDouble())
            return max(0.0, total)
        }
        // The gamma row of Table C.6, which is Equation C.15 with the partial
        // expectations of a gamma. Shape rises by one for each factor of u.
        val shape = mean * mean / variance
        val scale = variance / mean
        fun tail(extra: Int): Double = 1.0 - Gamma(shape + extra, scale).cdf(x)
        val a1 = shape
        val a2 = shape * (shape + 1.0)
        val a3 = shape * (shape + 1.0) * (shape + 2.0)
        val value = a3 * scale * scale * scale * tail(3) -
            3.0 * x * a2 * scale * scale * tail(2) +
            3.0 * x * x * a1 * scale * tail(1) -
            x * x * x * tail(0)
        return max(0.0, value / 6.0)
    }

    override fun toString(): String =
        "%s lead time demand, mean %.4f, standard deviation %.4f, VMR %.4f"
            .format(familyName, mean, stdDev, varianceToMeanRatio)

    companion object {

        /** Case 1 of @tbl-ltd-cases with single unit Poisson demand: `D(L)` is Poisson. */
        fun poisson(mean: Double): LeadTimeDemand {
            require(mean > 0.0) { "a Poisson mean must be positive" }
            // E[X^3] = lambda^3 + 3 lambda^2 + lambda
            val third = mean * mean * mean + 3.0 * mean * mean + mean
            return LeadTimeDemand(
                Poisson(mean), "Poisson", mean, mean, third, isDiscrete = true
            )
        }

        /**
         * A negative binomial matched to two moments, Section C.4.2.
         *
         * The KSL uses the success convention, so `p` is a probability of success
         * and the mean is `r(1-p)/p`. See the warning in Appendix C.
         */
        fun negativeBinomial(mean: Double, variance: Double): LeadTimeDemand {
            require(variance > mean) {
                "a negative binomial needs a variance above its mean, " +
                    "had mean $mean and variance $variance"
            }
            val p = mean / variance
            val r = mean * mean / (variance - mean)
            // E[X(X-1)(X-2)] = r(r+1)(r+2) beta^3 with beta = variance/mean - 1,
            // and E[X^3] follows since X(X-1)(X-2) = X^3 - 3X^2 + 2X.
            val beta = variance / mean - 1.0
            val factorial = r * (r + 1.0) * (r + 2.0) * beta * beta * beta
            val third = factorial + 3.0 * (variance + mean * mean) - 2.0 * mean
            return LeadTimeDemand(
                NegativeBinomial(p, r), "negative binomial", mean, variance, third,
                isDiscrete = true
            )
        }

        /** A gamma matched to two moments, @eq-gamma-matching. */
        fun gamma(mean: Double, variance: Double): LeadTimeDemand {
            require(mean > 0.0 && variance > 0.0) {
                "a gamma needs a positive mean and variance"
            }
            val shape = mean * mean / variance
            val scale = variance / mean
            // E[X^3] = alpha(alpha+1)(alpha+2) beta^3
            val third = shape * (shape + 1.0) * (shape + 2.0) * scale * scale * scale
            return LeadTimeDemand(
                Gamma(shape, scale), "gamma", mean, variance, third, isDiscrete = false
            )
        }

        /**
         * Step 2 of @sec-continuousreview-ltd, carried out.
         *
         * Poisson when the variance to mean ratio is near one, negative binomial
         * when it is above one, and the gamma once the mean is large enough that a
         * continuous model is safe. The thresholds are arguments because the choice
         * is a modeling decision and not a finding: @sec-continuousreview-approx-family measures what a
         * different choice costs, and where the answer matters you should carry two
         * families through and report the spread.
         *
         * A ratio below one is under-dispersed relative to the Poisson. No family
         * here fits it, so this throws rather than returning a model that cannot
         * represent the data.
         */
        fun matched(
            mean: Double,
            variance: Double,
            poissonTolerance: Double = 0.05,
            continuousThreshold: Double = 100.0,
        ): LeadTimeDemand {
            require(mean > 0.0) { "lead time demand must have a positive mean" }
            require(variance > 0.0) { "lead time demand must have a positive variance" }
            val ratio = variance / mean
            require(ratio >= 1.0 - poissonTolerance) {
                "variance to mean ratio $ratio is below one. Demand is under-dispersed " +
                    "relative to the Poisson and none of the families of @sec-continuousreview-ltd fits it."
            }
            return when {
                mean >= continuousThreshold -> gamma(mean, variance)
                ratio <= 1.0 + poissonTolerance -> poisson(mean)
                else -> negativeBinomial(mean, variance)
            }
        }
    }
}

/** The smallest whole `S` whose distribution function reaches [ratio]. @eq-basestock-optimal. */
internal fun LeadTimeDemand.smallestLevelReaching(ratio: Double): Int =
    ceil(inverseCdf(ratio) - 1.0e-12).toInt()
