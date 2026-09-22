package inventory.multiitem

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What intervals the operation can actually run.
 *
 * One concept, three places: per SKU in an order family, per block in a serial chain,
 * per location in a distribution network. @sec-multiitem-joint-problem names the practices and Section
 * 4.5.2 says why one of them is preferred, so the alternatives come from the domain.
 *
 * This subsumes what would otherwise be two abstractions, a coordination policy and a
 * rounding rule. Given the interval a location would prefer, return one it can run: a
 * common cycle, an integer multiple and a power-of-two multiple are three answers to
 * that one question.
 */
interface IntervalRule {
    val name: String
    val basePeriod: Double

    /**
     * The runnable interval nearest the one this SKU or block would prefer.
     *
     * A preferred interval must be a positive, finite number of time units. An
     * infinite one means the caller's holding coefficient was zero, which is a
     * location that adds no value, and no rounding of it is meaningful.
     */
    fun implementable(preferredInterval: Double): Double

    /** Every rule needs a positive base period, because every rule divides by it. */
    fun requireUsable(preferredInterval: Double) {
        require(basePeriod > 0.0) { "$name needs a positive base period, was $basePeriod" }
        require(preferredInterval > 0.0 && preferredInterval.isFinite()) {
            "$name was asked for the runnable interval nearest $preferredInterval, " +
                "which is not a positive finite interval"
        }
    }

    /** The whole number of base periods that interval comes to. */
    fun multiplierFor(preferredInterval: Double): Int =
        max(1, Math.round(implementable(preferredInterval) / basePeriod).toInt())
}

/** No restriction. Every location runs exactly the interval it prefers. */
class AnyInterval(override val basePeriod: Double = 1.0) : IntervalRule {
    init { require(basePeriod > 0.0) { "A base period must be positive, was $basePeriod" } }
    override val name: String = "Any interval"
    override fun implementable(preferredInterval: Double): Double {
        requireUsable(preferredInterval)
        return preferredInterval
    }
}

/** Every location on every order opportunity. The simplest coordinated policy there is. */
class CommonCycle(override val basePeriod: Double) : IntervalRule {
    init { require(basePeriod > 0.0) { "A base period must be positive, was $basePeriod" } }
    override val name: String = "Common cycle"
    override fun implementable(preferredInterval: Double): Double {
        requireUsable(preferredInterval)
        return basePeriod
    }
}

/**
 * Any whole number of base periods. The best a schedule can do, and not one a receiving
 * dock can run without thought: multipliers of three and five coincide only every
 * fifteen base periods.
 *
 * The integer minimizing k/(mT) + g*m*T is the m with m(m-1) <= x <= m(m+1), where x
 * is the square of the preferred interval over the base period. Solving that pair of
 * inequalities for m gives the closed form below.
 */
class IntegerMultiple(override val basePeriod: Double) : IntervalRule {
    init { require(basePeriod > 0.0) { "A base period must be positive, was $basePeriod" } }
    override val name: String = "Integer multiple"
    override fun implementable(preferredInterval: Double): Double {
        requireUsable(preferredInterval)
        val x = preferredInterval / basePeriod
        val m = max(1.0, ceil((-1.0 + sqrt(1.0 + 4.0 * x * x)) / 2.0))
        return m * basePeriod
    }
}

/**
 * Powers of two only, which is what makes a schedule nest and therefore what makes it
 * runnable. @sec-multiitem-joint-bound bounds what the restriction costs at about 6% against the best
 * interval, and about 2% when the base period may also be chosen.
 */
class PowerOfTwoMultiple(override val basePeriod: Double) : IntervalRule {
    init { require(basePeriod > 0.0) { "A base period must be positive, was $basePeriod" } }

    override val name: String = "Power-of-two multiple"

    override fun implementable(preferredInterval: Double): Double =
        (1L shl exponentFor(preferredInterval)) * basePeriod

    /**
     * @eq-pow2-ell, clamped at zero.
     *
     * The clamp at zero is the chapter's first qualification of @sec-multiitem-joint-bound: an item
     * wanting to order more often than the base period allows is stuck at one base
     * period. The cap at [MAXIMUM_EXPONENT] is arithmetic rather than inventory theory.
     * A multiplier of two to the sixty-second overflows the shift that computes it, and
     * an overflowed shift returns a plausible small number rather than an error, so the
     * exponent is refused before it is used.
     */
    fun exponentFor(preferredInterval: Double): Int {
        requireUsable(preferredInterval)
        val x = preferredInterval / (SQRT_TWO * basePeriod)
        if (x <= 1.0) return 0
        val exponent = max(0, ceil(ln(x) / LN_TWO - 1.0E-12).toInt())
        require(exponent <= MAXIMUM_EXPONENT) {
            "An interval of $preferredInterval is two to the $exponent base periods of " +
                "$basePeriod, and this rule rounds to at most two to the $MAXIMUM_EXPONENT. " +
                "A base period that small is almost always the real mistake."
        }
        return exponent
    }

    companion object {
        private val SQRT_TWO = sqrt(2.0)
        private val LN_TWO = ln(2.0)
        /** The largest exponent whose power of two is exactly representable in a Long. */
        const val MAXIMUM_EXPONENT: Int = 62
        /** The worst case of @eq-pow2-bound, three over two root two. */
        val WORST_CASE: Double = 3.0 / (2.0 * sqrt(2.0))
    }
}
