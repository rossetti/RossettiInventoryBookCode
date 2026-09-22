package inventory.multiitem

import kotlin.math.sqrt

/**
 * A stock keeping unit: one item held at one location, and the thing that gets
 * replenished.
 *
 * The same entity serves both halves of Chapter 4. @sec-multiitem-constrained to 4.5 decide many SKUs
 * at one location; @sec-multiitem-echelon decides one item across many locations. Same entity,
 * different topology, which is why there is no separate class for a stage in a chain.
 *
 * A SKU knows no other object. One that knew its portfolio could not be evaluated inside
 * a sweep without dragging the portfolio along.
 */
class SKU(
    val label: String,
    val demandRate: Double,
    val orderCost: Double,
    val holdingRate: Double,
    val unitValue: Double = 0.0,
) {
    init {
        require(label.isNotBlank()) { "A SKU needs a label" }
        require(demandRate > 0.0) { "A demand rate must be positive, was $demandRate" }
        require(orderCost >= 0.0) { "An order cost cannot be negative, was $orderCost" }
        require(holdingRate >= 0.0) { "A holding rate cannot be negative, was $holdingRate" }
        require(unitValue >= 0.0) { "A unit value cannot be negative, was $unitValue" }
    }

    /** @eq-multiitem-objective, this SKU alone, ignoring anything it shares. */
    val economicOrderQuantity: Double
        get() {
            require(holdingRate > 0.0) {
                "$label has no holding cost, so it has no unconstrained order quantity"
            }
            return sqrt(2.0 * orderCost * demandRate / holdingRate)
        }

    /** Ordering plus holding, per unit time, at a quantity someone else chose. */
    fun costAt(orderQuantity: Double): Double {
        require(orderQuantity > 0.0) { "An order quantity must be positive" }
        return orderCost * demandRate / orderQuantity + holdingRate * orderQuantity / 2.0
    }

    /** The derivative of [costAt], which is what a stationarity condition needs. */
    fun marginalCostAt(orderQuantity: Double): Double =
        -orderCost * demandRate / (orderQuantity * orderQuantity) + holdingRate / 2.0

    /** Half the demand rate times the holding rate: the coefficient in the interval form. */
    val holdingCoefficient: Double get() = holdingRate * demandRate / 2.0

    /** The interval this SKU would choose paying only its own ordering cost. */
    val preferredInterval: Double
        get() {
            require(holdingCoefficient > 0.0) { "$label has no holding cost" }
            return sqrt(orderCost / holdingCoefficient)
        }

    fun quantityFor(interval: Double): Double {
        require(interval > 0.0 && interval.isFinite()) {
            "A reorder interval must be positive and finite, was $interval"
        }
        return demandRate * interval
    }

    fun intervalFor(orderQuantity: Double): Double {
        require(orderQuantity > 0.0) { "An order quantity must be positive, was $orderQuantity" }
        return orderQuantity / demandRate
    }

    fun orderFrequencyAt(orderQuantity: Double): Double {
        require(orderQuantity > 0.0) { "An order quantity must be positive, was $orderQuantity" }
        return demandRate / orderQuantity
    }

    /** The same SKU with its holding cost removed. Used by the exchange curve. */
    fun withoutHoldingCost(): SKU = SKU(label, demandRate, orderCost, 0.0, unitValue)

    override fun toString(): String = "SKU($label)"

    companion object {
        /** A SKU whose holding rate is a carrying charge on its value, h = i*c. */
        fun pricedAt(
            label: String,
            demandRate: Double,
            unitValue: Double,
            orderCost: Double,
            carryingCharge: Double,
        ): SKU = SKU(label, demandRate, orderCost, carryingCharge * unitValue, unitValue)
    }
}
