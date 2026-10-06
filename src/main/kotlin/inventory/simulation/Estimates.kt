package inventory.simulation

import inventory.continuousreview.RQPerformance
import inventory.periodicreview.RSPerformance
import ksl.modeling.variable.ResponseCIfc
import kotlin.math.abs

/**
 * A simulated measure: the average across replications, the half-width of its
 * confidence interval, @eq-sim-halfwidth, and the number of replications. The
 * level is 95% unless one is given.
 */
data class Estimate(val average: Double, val halfWidth: Double, val count: Int) {

    /** Whether the interval covers [value]. */
    fun covers(value: Double): Boolean = abs(value - average) <= halfWidth

    /** The half-width relative to the average, @sec-simulation-output-rare. */
    val relativeHalfWidth: Double get() = if (average == 0.0) Double.NaN else halfWidth / abs(average)

    override fun toString(): String = "%.4f +/- %.4f".format(average, halfWidth)

    companion object {
        /** The across-replication estimate of a KSL response after a run, at confidence [level]. */
        fun of(response: ResponseCIfc, level: Double = 0.95): Estimate {
            val s = response.acrossReplicationStatistic
            return Estimate(s.average, s.halfWidth(level), s.count.toInt())
        }

        /**
         * The Bonferroni level for checking [measures] intervals at once, so that a
         * correct model passes all of them together with probability at least
         * [familyLevel]. Each interval is built at `1 - (1 - familyLevel)/measures`.
         */
        fun bonferroni(measures: Int, familyLevel: Double = 0.95): Double {
            require(measures >= 1) { "at least one measure" }
            return 1.0 - (1.0 - familyLevel) / measures
        }
    }
}

/** One measure set beside its exact value, and whether the simulated interval covers it. */
data class Agreement(val measure: String, val exact: Double, val simulated: Estimate) {
    val covered: Boolean get() = simulated.covers(exact)

    override fun toString(): String =
        "%-20s exact %12.4f   simulated %12.4f +/- %-10.4f %s".format(
            measure, exact, simulated.average, simulated.halfWidth, if (covered) "covered" else "NOT COVERED")
}

/**
 * The simulated measures of one stock point, @sec-simulation-design-agreement,
 * read after a run and set beside the exact model of the same policy.
 */
data class SimulatedPerformance(
    val onHand: Estimate,
    val backorders: Estimate,
    val onOrder: Estimate,
    val readyRate: Estimate,
    val lotFillRate: Estimate,
    val unitFillRate: Estimate,
    val orderFrequency: Estimate,
    val orderingCost: Estimate,
    val holdingCost: Estimate,
    val backorderCost: Estimate,
    val totalCost: Estimate,
) {
    /** The comparison with chapter 8's exact (r, Q) measures, @exm-sim-rq-transformer. */
    fun against(exact: RQPerformance): List<Agreement> = listOf(
        Agreement("on hand", exact.expectedOnHand, onHand),
        Agreement("backorders", exact.expectedBackorders, backorders),
        Agreement("ready rate", exact.readyRate, readyRate),
        Agreement("fill rate", exact.fillRate, unitFillRate),
        Agreement("order frequency", exact.orderFrequency, orderFrequency),
        Agreement("ordering cost", exact.orderingCostRate, orderingCost),
        Agreement("holding cost", exact.holdingCostRate, holdingCost),
        Agreement("backorder cost", exact.backorderCostRate, backorderCost),
        Agreement("total cost", exact.totalCost, totalCost),
    )

    /** The comparison with chapter 8's exact (R, S) measures, @exm-sim-rs-transformer. */
    fun against(exact: RSPerformance): List<Agreement> = listOf(
        Agreement("on hand", exact.expectedOnHand, onHand),
        Agreement("backorders", exact.expectedBackorders, backorders),
        Agreement("ready rate", exact.readyRate, readyRate),
        Agreement("fill rate", exact.fillRate, unitFillRate),
        Agreement("order frequency", exact.orderFrequency, orderFrequency),
        Agreement("ordering cost", exact.orderingCostRate, orderingCost),
        Agreement("holding cost", exact.holdingCostRate, holdingCost),
        Agreement("backorder cost", exact.backorderCostRate, backorderCost),
        Agreement("total cost", exact.totalCost, totalCost),
    )

    companion object {
        fun of(inventory: SimInventory, level: Double = 0.95): SimulatedPerformance = SimulatedPerformance(
            onHand = Estimate.of(inventory.onHandResponse, level),
            backorders = Estimate.of(inventory.backorderedResponse, level),
            onOrder = Estimate.of(inventory.onOrderResponse, level),
            readyRate = Estimate.of(inventory.readyRateResponse, level),
            lotFillRate = Estimate.of(inventory.lotFillRateResponse, level),
            unitFillRate = Estimate.of(inventory.unitFillRateResponse, level),
            orderFrequency = Estimate.of(inventory.orderFrequencyResponse, level),
            orderingCost = Estimate.of(inventory.orderingCostResponse, level),
            holdingCost = Estimate.of(inventory.holdingCostResponse, level),
            backorderCost = Estimate.of(inventory.backorderCostResponse, level),
            totalCost = Estimate.of(inventory.totalCostResponse, level),
        )
    }
}
