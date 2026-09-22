package inventory.lotsizing

/**
 * One row of a supplier's price table: the smallest order quantity that earns
 * [unitCost], and that price.
 */
data class PriceLevel(val breakPoint: Double, val unitCost: Double) {
    init {
        require(breakPoint >= 0.0) { "A break point must be non-negative, was $breakPoint" }
        require(unitCost > 0.0) { "A unit cost must be positive, was $unitCost" }
    }
}

/**
 * How the supplier prices an order. See @sec-eoq-discounts of the text.
 *
 * Every schedule answers one question, [purchaseCostOf], and the rest follows from
 * it. A flat schedule charges the same price for every unit; an all-units schedule
 * prices the whole order at the rate its size earns; an incremental schedule prices
 * only the units above each break point at the lower rate.
 */
sealed interface PriceSchedule {

    /** How this kind of schedule is named in the text, for use in a message. */
    val label: String

    /** The price levels, ordered by break point, the first starting at zero. */
    val levels: List<PriceLevel>

    /** What an order of [orderQuantity] units costs to buy, in currency. */
    fun purchaseCostOf(orderQuantity: Double): Double

    /**
     * The average price per unit paid on an order of [orderQuantity] units, which is
     * [purchaseCostOf] divided by the quantity. Under a flat or all-units schedule
     * this is the level's own price; under an incremental schedule it is lower than
     * the marginal price and depends on the quantity, which is the point made in
     * @sec-eoq-incremental.
     */
    fun unitCostAt(orderQuantity: Double): Double =
        purchaseCostOf(orderQuantity) / orderQuantity

    /** The level whose interval contains [orderQuantity]. */
    fun levelFor(orderQuantity: Double): PriceLevel =
        levels.last { orderQuantity >= it.breakPoint }

    /** The index of the level whose interval contains [orderQuantity]. */
    fun levelIndexFor(orderQuantity: Double): Int =
        levels.indexOfLast { orderQuantity >= it.breakPoint }

    /**
     * The smallest quantity that does not earn the price of level [index], or null
     * for the last level, which has no upper limit.
     */
    fun upperLimitOf(index: Int): Double? =
        if (index + 1 < levels.size) levels[index + 1].breakPoint else null

    /** True when [orderQuantity] falls inside the interval of level [index]. */
    fun isInsideLevel(index: Int, orderQuantity: Double): Boolean {
        val upper = upperLimitOf(index)
        return orderQuantity >= levels[index].breakPoint && (upper == null || orderQuantity < upper)
    }

    companion object {
        internal fun validate(levels: List<PriceLevel>) {
            require(levels.isNotEmpty()) { "A price schedule needs at least one level" }
            require(levels.first().breakPoint == 0.0) {
                "The first break point must be 0.0, was ${levels.first().breakPoint}"
            }
            for (i in 1 until levels.size) {
                require(levels[i].breakPoint > levels[i - 1].breakPoint) {
                    "Break points must strictly increase; level $i is ${levels[i].breakPoint}" +
                        " after ${levels[i - 1].breakPoint}"
                }
            }
        }
    }
}

/** A single price for every unit, whatever the order size. */
data class Flat(val unitCost: Double) : PriceSchedule {
    init { require(unitCost > 0.0) { "The unit cost must be positive, was $unitCost" } }
    override val label: String = "a flat"

    override val levels: List<PriceLevel> = listOf(PriceLevel(0.0, unitCost))
    override fun purchaseCostOf(orderQuantity: Double): Double = unitCost * orderQuantity
    override fun unitCostAt(orderQuantity: Double): Double = unitCost
}

/** The whole order is priced at the rate its size earns. @sec-eoq-allunits. */
data class AllUnits(override val levels: List<PriceLevel>) : PriceSchedule {
    init { PriceSchedule.validate(levels) }
    override val label: String = "an all-units"
    override fun purchaseCostOf(orderQuantity: Double): Double =
        levelFor(orderQuantity).unitCost * orderQuantity
    override fun unitCostAt(orderQuantity: Double): Double = levelFor(orderQuantity).unitCost
}

/** Only the units above each break point earn the lower rate. @sec-eoq-incremental. */
data class Incremental(override val levels: List<PriceLevel>) : PriceSchedule {
    init { PriceSchedule.validate(levels) }
    override val label: String = "an incremental"

    /**
     * The cost of filling every interval below [index] completely, written R_j in
     * @eq-incremental-R. Computed once at construction.
     */
    val filledIntervalCost: List<Double> = buildList {
        add(0.0)
        for (i in 1 until levels.size) {
            add(last() + levels[i - 1].unitCost * (levels[i].breakPoint - levels[i - 1].breakPoint))
        }
    }

    override fun purchaseCostOf(orderQuantity: Double): Double {
        val index = levelIndexFor(orderQuantity)
        val level = levels[index]
        return filledIntervalCost[index] + level.unitCost * (orderQuantity - level.breakPoint)
    }

    /**
     * The amount that behaves like an extra fixed charge at level [index], which is
     * R_j minus c_j q_j in @eq-incremental-kj. Adding the ordering cost to it gives the
     * effective ordering cost that turns an incremental problem into an ordinary one.
     */
    fun fixedChargeAt(index: Int): Double {
        require(index in levels.indices) {
            "This schedule has ${levels.size} levels, so $index is not one of them"
        }
        return filledIntervalCost[index] - levels[index].unitCost * levels[index].breakPoint
    }
}
