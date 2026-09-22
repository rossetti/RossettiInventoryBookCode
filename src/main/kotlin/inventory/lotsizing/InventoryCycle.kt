package inventory.lotsizing

import kotlin.math.abs

/**
 * The four durations of one cycle, named for what the system is doing during each.
 * @sec-eoq-segments.
 */
data class CycleSegments(
    /** Backorders are cleared, at the net build-up rate. */
    val clearingBackorders: Double,
    /** Stock climbs to its peak, at the net build-up rate. */
    val buildingToPeak: Double,
    /** The peak is consumed, at the demand rate. */
    val depletingPeak: Double,
    /** Backorders accumulate again, at the demand rate. */
    val accumulatingBackorders: Double,
) {
    /** The four durations in cycle order. */
    fun toList(): List<Double> =
        listOf(clearingBackorders, buildingToPeak, depletingPeak, accumulatingBackorders)

    /** Their sum, which must equal the cycle length. */
    val total: Double
        get() = clearingBackorders + buildingToPeak + depletingPeak + accumulatingBackorders
}

/**
 * The geometry a policy produces, derived once and read by everything downstream.
 *
 * The cycle knows the demand rate but no price, which is what lets it implement
 * [ServiceMeasureIfc]: @sec-eoq-measures, "Performance Measures for the Cycle," computes
 * each service measure from exactly this geometry.
 */
class InventoryCycle(
    parameters: CostParametersIfc,
    val policy: Policy,
) : ServiceMeasureIfc {

    val demandRate: Double = parameters.demandRate

    /** The fraction of the order that survives replenishment to become stock. */
    val survivingFraction: Double = parameters.survivingFraction

    /** The peak backorder level, which is the policy's second decision. */
    val maxBackorder: Double = policy.maxBackorder

    /** The peak on-hand level. @eq-peak-inventory. */
    val maxOnHand: Double =
        policy.orderQuantity * parameters.survivingFraction - policy.maxBackorder

    /** The time from one replenishment to the next. @eq-cycle-length. */
    val length: Double = policy.orderQuantity / parameters.demandRate

    /** Orders placed per unit time, the reciprocal of [length]. */
    val orderFrequency: Double = parameters.demandRate / policy.orderQuantity

    /** The time spent replenishing, or null when the order arrives at one instant. */
    val replenishmentTime: Double? = when (val r = parameters.replenishment) {
        is Replenishment.Instantaneous -> null
        is Replenishment.AtRate -> policy.orderQuantity / r.rate
    }

    /** The four durations of @eq-segments. */
    val segments: CycleSegments

    /** The time average of the on-hand level. @eq-avg-inventory. */
    val averageOnHand: Double

    /** The time average of the backorder level. @eq-avg-backorders. */
    val averageBackorder: Double

    init {
        require(maxOnHand >= -TOLERANCE) {
            "A backorder level of ${policy.maxBackorder} exceeds what one order of " +
                "${policy.orderQuantity} can clear, which is " +
                "${policy.orderQuantity * survivingFraction}. That does not describe a cycle."
        }
        val buildUp = parameters.replenishment.buildUpRate(parameters.demandRate)
        segments = CycleSegments(
            clearingBackorders = if (buildUp.isInfinite()) 0.0 else maxBackorder / buildUp,
            buildingToPeak = if (buildUp.isInfinite()) 0.0 else maxOnHand / buildUp,
            depletingPeak = maxOnHand / demandRate,
            accumulatingBackorders = maxBackorder / demandRate,
        )
        val denominator = 2.0 * policy.orderQuantity * survivingFraction
        averageOnHand = maxOnHand * maxOnHand / denominator
        averageBackorder = maxBackorder * maxBackorder / denominator

        // @eq-cycle-length: the segments account for the whole cycle. @sec-eoq-averages's worked
        // example says this check catches most algebra errors immediately.
        checkClose(segments.total, length, "the segments sum to the cycle length")
        // @eq-peak-inventory.
        checkClose(maxOnHand + maxBackorder, policy.orderQuantity * survivingFraction,
            "the peaks account for the surviving part of the order")
        // The averages from the triangle areas agree with the closed forms.
        checkClose(areaAverage(maxOnHand, segments.buildingToPeak + segments.depletingPeak),
            averageOnHand, "the on-hand average from the area agrees with the closed form")
        checkClose(areaAverage(maxBackorder, segments.clearingBackorders + segments.accumulatingBackorders),
            averageBackorder, "the backorder average from the area agrees with the closed form")
    }

    // ---- ServiceMeasureIfc, the closed forms of @sec-eoq-measures -------------------

    override val fractionOutOfStock: Double
        get() = (segments.clearingBackorders + segments.accumulatingBackorders) / length

    override val readyRate: Double get() = 1.0 - fractionOutOfStock

    override val fillRate: Double get() = readyRate

    override val cycleServiceLevel: Double get() = if (maxBackorder <= 0.0) 1.0 else 0.0

    override val unfilledDemandRate: Double get() = demandRate * fractionOutOfStock

    override val averageWait: Double get() = averageBackorder / demandRate

    override fun toString(): String =
        "InventoryCycle(orderQuantity=${policy.orderQuantity}, length=$length, " +
            "maxOnHand=$maxOnHand, maxBackorder=$maxBackorder, " +
            "averageOnHand=$averageOnHand, averageBackorder=$averageBackorder)"

    private fun areaAverage(peak: Double, base: Double): Double = peak * base / 2.0 / length

    private fun checkClose(actual: Double, expected: Double, what: String) {
        val scale = maxOf(abs(expected), 1.0)
        check(abs(actual - expected) <= TOLERANCE * scale) {
            "Cycle identity violated: $what. Expected $expected but computed $actual."
        }
    }

    companion object {
        /** Relative tolerance for the cycle identities. */
        const val TOLERANCE: Double = 1.0e-9
    }
}
