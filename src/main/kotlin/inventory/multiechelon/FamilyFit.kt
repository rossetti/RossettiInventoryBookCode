package inventory.multiechelon

import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.LossFunctionDistributionIfc
import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.distributions.Poisson

/** @eq-varimetric-fit, fitted to two moments. The KSL takes a non-integer `r`. */
fun negativeBinomialOn(mean: Double, variance: Double): NegativeBinomial {
    require(variance > mean) { "a negative binomial needs variance above mean; got $variance and $mean" }
    val p = mean / variance
    return NegativeBinomial(p, (p / (1.0 - p)) * mean)
}

/**
 * Chooses a lead time demand family from two moments, @sec-multiechelon-varimetric-fit.
 *
 * The variance to mean ratio `c` decides it:
 *
 * ```
 *   c > 1     negative binomial, @eq-varimetric-fit
 *   c = 1     Poisson
 *   c < 1     gamma
 * ```
 *
 * "Equal" means within [RATIO_TOLERANCE], which is floating point noise and not a
 * modeling choice. At `s = 0` the pipeline's two moments agree exactly, but they
 * are computed by subtracting large numbers and come out a few ulps apart.
 *
 * The original, `varimetric.VMBaseItem.setLeadTimeDemand`, used a Poisson for any
 * ratio in (0.9, 1.1). That band is not a safe simplification. A ratio of 1.06
 * moves a storeroom's backorders by fourteen percent when its stock level sits in
 * the tail, and the band makes backorders jump where the ratio crosses 1.1, which
 * an allocation search then exploits. Simulation agrees with the negative binomial
 * inside the old band, @sec-multiechelon-varimetric-fit. Near one the negative binomial
 * already converges to the Poisson, so nothing is gained by switching early.
 *
 * The gamma branch covers the under-dispersed case, which @sec-multiechelon-varimetric-fit
 * records that neither Graves nor Sherbrooke could rule out.
 */
fun fitTwoMoments(mean: Double, variance: Double): LossFunctionDistributionIfc {
    require(mean > 0.0) { "the mean must be > 0" }
    require(variance > 0.0) { "the variance must be > 0" }
    val c = variance / mean
    return when {
        c > 1.0 + RATIO_TOLERANCE -> negativeBinomialOn(mean, variance)
        c >= 1.0 - RATIO_TOLERANCE -> Poisson(mean)
        else -> {
            val scale = variance / mean
            Gamma(mean / scale, scale)
        }
    }
}

/** How close to one the variance to mean ratio must be for [fitTwoMoments] to call it one. */
const val RATIO_TOLERANCE: Double = 1e-9
