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
 * Ported from `varimetric.VMBaseItem.setLeadTimeDemand`, which keys off the
 * variance to mean ratio with a band around one:
 *
 * ```
 *   0.9 < c < 1.1   Poisson
 *   c >= 1.1        negative binomial, @eq-varimetric-fit
 *   otherwise       gamma
 * ```
 *
 * The band matters and is not a rounding convenience. @eq-varimetric-fit divides by
 * `1 - p`, so a ratio arbitrarily close to one produces an arbitrarily large
 * number of successes and a fit that is a Poisson in all but arithmetic. The
 * gamma branch covers the under-dispersed case, which @sec-multiechelon-varimetric-fit records that
 * neither Graves nor Sherbrooke could rule out.
 */
fun fitTwoMoments(mean: Double, variance: Double): LossFunctionDistributionIfc {
    require(mean > 0.0) { "the mean must be > 0" }
    require(variance > 0.0) { "the variance must be > 0" }
    val c = variance / mean
    return when {
        c >= 1.1 -> negativeBinomialOn(mean, variance)
        c > 0.9 -> Poisson(mean)
        else -> {
            val scale = variance / mean
            Gamma(mean / scale, scale)
        }
    }
}
