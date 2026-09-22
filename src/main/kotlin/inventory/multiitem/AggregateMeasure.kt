package inventory.multiitem

import kotlin.math.sqrt

/**
 * A number computed over a whole plan: what a management report shows.
 *
 * This is the abstraction the chapter's own list asks for. @sec-multiitem-interact names four things
 * an organization limits, and they are not four problems. They are four *measures* of
 * one plan, each with a ceiling on it.
 *
 * Deliberately not sealed. Sealing buys exhaustiveness, and exhaustiveness is worth
 * having only when something dispatches on the kind. Nothing here does: a solver asks a
 * measure for a number and never asks which measure it is holding. A `when` over these
 * would be a coefficient in disguise, and its appearance would mean the design failed.
 */
interface AggregateMeasure {
    val name: String
    val unit: String

    fun measure(plan: ReplenishmentPlan): Double
}

/**
 * A measure a limit can be put on.
 *
 * Separate from [AggregateMeasure] because the objective is measured constantly and
 * constrained never, so making [RelevantCost] report a marginal would force a
 * meaningless implementation.
 */
interface ConstrainableMeasure : AggregateMeasure {

    /** How much this measure changes per unit of order quantity at one SKU. */
    fun marginalAt(sku: SKU, orderQuantity: Double): Double

    /**
     * The quantity a SKU takes when this measure is priced at [shadowPrice].
     *
     * [marginalAt] is the primitive and this is derived from it. The stationarity
     * condition for one SKU is
     *
     *     dC/dQ + price * dM/dQ = 0
     *
     * so any measure that can report a marginal works with no further code. A measure
     * with a closed form overrides this; the default is what keeps a new measure to two
     * methods.
     */
    fun quantityAt(sku: SKU, shadowPrice: Double): Double {
        val f = { q: Double -> sku.marginalCostAt(q) + shadowPrice * marginalAt(sku, q) }
        var lo = 1.0E-9
        var hi = 1.0
        while (f(hi) < 0.0) {
            hi *= 2.0
            check(hi < 1.0E12) { "No stationary quantity found for ${sku.label}" }
        }
        repeat(200) {
            val mid = 0.5 * (lo + hi)
            if (f(mid) < 0.0) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }
}

/** The objective of every model in the chapter: ordering plus holding. */
object RelevantCost : AggregateMeasure {
    override val name: String = "Relevant cost"
    override val unit: String = "dollars per unit time"
    override fun measure(plan: ReplenishmentPlan): Double =
        plan.skus.sumOf { it.costAt(plan.quantityOf(it)) }
}

/** Money tied up in cycle stock. Half an order is on hand on average. */
object AverageInvestment : ConstrainableMeasure {
    override val name: String = "Average investment"
    override val unit: String = "dollars"
    override fun measure(plan: ReplenishmentPlan): Double =
        plan.skus.sumOf { it.unitValue * plan.quantityOf(it) / 2.0 }
    override fun marginalAt(sku: SKU, orderQuantity: Double): Double = sku.unitValue / 2.0
    override fun quantityAt(sku: SKU, shadowPrice: Double): Double =
        sqrt(2.0 * sku.orderCost * sku.demandRate / (sku.holdingRate + shadowPrice * sku.unitValue))
}

/**
 * Space the stock occupies. A whole order arrives at once, so a unit consumes its full
 * volume rather than half of it.
 *
 * The volume per unit lives here and not on the SKU. That is the closure test: a weight
 * limit would otherwise add a field to SKU, and a handling limit another, so the SKU
 * would grow with the number of constraints. It is also why the measures are a
 * hierarchy rather than one class holding two functions, since this kind knows
 * something the others do not.
 */
class SpaceOccupied(private val volumePerUnit: Map<SKU, Double>) : ConstrainableMeasure {
    override val name: String = "Space occupied"
    override val unit: String = "cubic feet"
    override fun measure(plan: ReplenishmentPlan): Double =
        plan.skus.sumOf { volumeOf(it) * plan.quantityOf(it) }
    override fun marginalAt(sku: SKU, orderQuantity: Double): Double = volumeOf(sku)
    override fun quantityAt(sku: SKU, shadowPrice: Double): Double =
        sqrt(2.0 * sku.orderCost * sku.demandRate /
            (sku.holdingRate + 2.0 * shadowPrice * volumeOf(sku)))

    private fun volumeOf(sku: SKU): Double = requireNotNull(volumePerUnit[sku]) {
        "${sku.label} has no volume, so it cannot be measured against a space limit"
    }
}

/**
 * Replenishments per unit time, which is what a receiving department's capacity limits.
 *
 * This is the kind that no coefficient can describe. Consumption falls as the quantity
 * rises, so the marginal is negative and depends on the quantity, and the shadow price
 * joins the *ordering* cost rather than the holding rate. @sec-multiitem-constrained-frequency derives it, and
 * an interface that could only report a coefficient could not express it.
 */
object ReplenishmentWorkload : ConstrainableMeasure {
    override val name: String = "Replenishment workload"
    override val unit: String = "orders per unit time"
    override fun measure(plan: ReplenishmentPlan): Double =
        plan.skus.sumOf { it.orderFrequencyAt(plan.quantityOf(it)) }
    override fun marginalAt(sku: SKU, orderQuantity: Double): Double =
        -sku.demandRate / (orderQuantity * orderQuantity)
    override fun quantityAt(sku: SKU, shadowPrice: Double): Double =
        sqrt(2.0 * (sku.orderCost + shadowPrice) * sku.demandRate / sku.holdingRate)
}
