package inventory.multiitem

/** The SKUs decided together. The subject of a study, not a study itself. */
class Portfolio(val skus: List<SKU>) {

    init { require(skus.isNotEmpty()) { "A portfolio needs at least one SKU" } }

    val size: Int get() = skus.size

    /** One quantity per SKU, in the order the SKUs were supplied. */
    fun planFor(quantities: List<Double>, shadowPrice: Double = 0.0): ReplenishmentPlan =
        ReplenishmentPlan(skus, quantities, shadowPrice)

    /** Every SKU at its own economic order quantity, ignoring anything shared. */
    fun freePlan(): ReplenishmentPlan = planFor(skus.map { it.economicOrderQuantity })

    /** Every SKU at the quantity a priced measure would have it take. */
    fun planAt(measure: ConstrainableMeasure, shadowPrice: Double): ReplenishmentPlan =
        planFor(skus.map { measure.quantityAt(it, shadowPrice) }, shadowPrice)

    override fun toString(): String = "Portfolio(${skus.size} SKUs)"
}
