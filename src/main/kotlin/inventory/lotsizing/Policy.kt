package inventory.lotsizing

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The two decisions of Chapter 3: how much to order, and how deep to let the
 * shortage run before the replenishment arrives.
 */
data class Policy(
    val orderQuantity: Double,
    val maxBackorder: Double = 0.0,
) {
    init {
        require(orderQuantity > 0.0) { "The order quantity must be positive, was $orderQuantity" }
        require(maxBackorder >= 0.0) { "The maximum backorder level cannot be negative, was $maxBackorder" }
    }
}

/**
 * A restriction on the quantities that may actually be ordered. @sec-eoq-sensitivity observes
 * that no warehouse orders 40.37 of anything.
 */
sealed interface QuantityRounding {

    /** The admissible quantities bracketing [quantity], at most two, both positive. */
    fun admissibleAround(quantity: Double): List<Double>

    /** Any positive quantity is admissible. */
    data object None : QuantityRounding {
        override fun admissibleAround(quantity: Double): List<Double> = listOf(quantity)
    }

    /** Whole units only. */
    data object WholeUnits : QuantityRounding {
        override fun admissibleAround(quantity: Double): List<Double> =
            bracket(quantity, 1.0)
    }

    /** Multiples of [size], for an item supplied in cases. */
    data class MultipleOf(val size: Double) : QuantityRounding {
        init { require(size > 0.0) { "A case size must be positive, was $size" } }
        override fun admissibleAround(quantity: Double): List<Double> = bracket(quantity, size)
    }

    companion object {
        fun multipleOf(size: Double) = MultipleOf(size)

        internal fun bracket(quantity: Double, step: Double): List<Double> {
            val below = floor(quantity / step) * step
            val above = ceil(quantity / step) * step
            return listOf(below, above).filter { it > 0.0 }.distinct()
        }
    }
}
