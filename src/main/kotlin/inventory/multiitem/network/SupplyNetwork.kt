package inventory.multiitem.network

import inventory.multiitem.IntervalRule
import inventory.multiitem.SKU
import kotlin.math.sqrt

/**
 * SKUs that replenish one another. @sec-multiitem-echelon.
 *
 * The topology is one map: which SKU supplies which. A SKU with no supplier draws from
 * outside the system. That one map serves both structures, because a serial chain and a
 * distribution network differ in its shape and not in its kind.
 *
 * The echelon rate is a difference between neighbours, so it belongs here and not on a
 * SKU. A SKU that knew its supplier would be the topology written down twice.
 */
abstract class SupplyNetwork(
    val skus: List<SKU>,
    private val suppliedBy: Map<SKU, SKU>,
) {
    init {
        require(skus.size >= 2) { "A network needs at least two locations" }
        suppliedBy.forEach { (from, to) ->
            require(skus.any { it === from } && skus.any { it === to }) {
                "${from.label} or ${to.label} is not in this network"
            }
        }
    }

    /** @eq-echelon-holding: the value this location adds, not the value accumulated through it. */
    fun echelonRate(sku: SKU): Double =
        sku.holdingRate - (suppliedBy[sku]?.holdingRate ?: 0.0)

    fun holdingCoefficient(sku: SKU): Double = 0.5 * sku.demandRate * echelonRate(sku)

    /** The interval this location would choose if nothing constrained it. */
    fun unconstrainedInterval(sku: SKU): Double = sqrt(sku.orderCost / holdingCoefficient(sku))

    /**
     * The order cost over the holding coefficient, whose square root is the interval a
     * location would choose alone. A location whose echelon rate is zero adds no value,
     * so it has no interval of its own and belongs merged into a neighbour rather than
     * decided separately.
     */
    fun ratio(sku: SKU): Double {
        val g = holdingCoefficient(sku)
        require(g > 0.0) {
            "${sku.label} holds at the same rate as the location supplying it, so its " +
                "echelon rate is zero and it has no interval of its own"
        }
        return sku.orderCost / g
    }

    /** The intervals that solve the relaxed problem, before any rounding. */
    abstract fun relaxedIntervals(): List<Double>

    fun costOf(intervals: List<Double>): Double =
        skus.indices.sumOf { i ->
            skus[i].orderCost / intervals[i] + holdingCoefficient(skus[i]) * intervals[i]
        }

    /** The relaxed intervals made runnable, one rule applied to every location. */
    fun plan(rule: IntervalRule): List<Double> = relaxedIntervals().map { rule.implementable(it) }
}
