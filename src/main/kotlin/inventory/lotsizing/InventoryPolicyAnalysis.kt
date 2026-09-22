package inventory.lotsizing

import kotlin.math.abs

/** One candidate a model considered while choosing among a price schedule's levels. */
data class QuantityCandidate(
    val levelIndex: Int,
    val breakPoint: Double,
    val unitCost: Double,
    val quantity: Double,
    val feasible: Boolean,
    val relevantCost: Double,
    val totalCost: Double,
    val selected: Boolean,
)

/** How a model arrived at the order quantity it reported. */
sealed interface QuantityChoice {

    /** The caller supplied it. */
    data object Supplied : QuantityChoice

    /** A closed form produced an interior optimum. */
    data object ClosedForm : QuantityChoice

    /**
     * The closed form's stationary point lay outside the feasible region, so the
     * optimum is on the boundary. [reason] says which boundary and why.
     */
    data class Boundary(val reason: String) : QuantityChoice

    /** Chosen by evaluating a candidate set, which is retained here. */
    data class AmongCandidates(val candidates: List<QuantityCandidate>) : QuantityChoice

    /**
     * The unconstrained optimum was [unrounded], and the admissible quantities
     * bracketing it were evaluated.
     */
    data class Rounded(val unrounded: Double, val considered: List<Double>) : QuantityChoice
}

/**
 * Everything the pipeline produced for one policy, with the inputs that produced it.
 *
 * Models are declared to return this interface rather than the implementation, so a
 * simulated estimate can implement it and be compared against an analytical result
 * member by member.
 */
interface InventoryPolicyAnalysisIfc {
    val parameters: ParameterSnapshot
    val model: String
    val policy: Policy
    val cycle: InventoryCycle
    val measures: PerformanceMeasureIfc
    val reorderPoint: Double
    val choice: QuantityChoice

    /** What the model ignored, empty unless it was applied outside its assumptions. */
    val approximated: List<String>

    /** The order quantity, one hop rather than through the policy. */
    val orderQuantity: Double get() = policy.orderQuantity

    /** The maximum backorder level, one hop. */
    val maxBackorder: Double get() = policy.maxBackorder

    /** The time between orders, one hop. */
    val cycleTime: Double get() = cycle.length

    /** Orders per unit time, one hop. */
    val orderFrequency: Double get() = cycle.orderFrequency

    /** The order quantity rounded up to a whole unit. */
    val roundedOrderQuantity: Int get() = kotlin.math.ceil(orderQuantity).toInt()

    /** The reorder point rounded up to a whole unit. */
    val roundedReorderPoint: Int get() = kotlin.math.ceil(reorderPoint).toInt()

    /**
     * The penalty of this policy against [reference], normally the optimum.
     * @eq-eoq-penalty.
     *
     * Computed on the relevant cost, which is the right basis when the two policies
     * pay the same price per unit. Under a quantity discount they do not, and a
     * penalty between two candidates at different prices should be read on the total
     * instead.
     */
    fun penaltyAgainst(reference: InventoryPolicyAnalysisIfc): CostPenalty {
        require(parameters == reference.parameters) {
            "A penalty compares two policies for the same item. These analyses carry " +
                "different parameters, so their costs are not comparable."
        }
        val mine = measures.relevantCost
        val theirs = reference.measures.relevantCost
        return CostPenalty(ratio = mine / theirs, difference = mine - theirs)
    }
}

/** The analytical implementation of [InventoryPolicyAnalysisIfc]. */
data class InventoryPolicyAnalysis(
    override val parameters: ParameterSnapshot,
    override val model: String,
    override val policy: Policy,
    override val cycle: InventoryCycle,
    override val measures: PerformanceMeasures,
    override val reorderPoint: Double,
    override val choice: QuantityChoice,
    override val approximated: List<String> = emptyList(),
) : InventoryPolicyAnalysisIfc {

    init {
        check(abs(measures.totalCost -
            (measures.relevantCost + measures.purchase + measures.position)) <=
            1.0e-9 * maxOf(abs(measures.totalCost), 1.0)) {
            "The total cost does not equal the relevant cost plus the inert terms."
        }
        check(abs(measures.readyRate - (1.0 - measures.fractionOutOfStock)) <= 1.0e-12) {
            "The ready rate does not complement the fraction of time out of stock."
        }
    }

    /** A labelled block laid out like a worked example, for reading at a console. */
    fun report(): String = buildString {
        val unit = parameters.timeUnit.label
        appendLine(model)
        appendLine("  Order quantity        %,.4f units  (%d rounded up)"
            .format(orderQuantity, roundedOrderQuantity))
        if (maxBackorder > 0.0) {
            appendLine("  Max backorder         %,.4f units".format(maxBackorder))
        }
        appendLine("  Cycle length          %,.4f %s".format(cycleTime, unit))
        appendLine("  Order frequency       %,.4f orders per %s".format(orderFrequency, unit))
        appendLine("  Average on hand       %,.4f units".format(cycle.averageOnHand))
        if (maxBackorder > 0.0) {
            appendLine("  Average backorder     %,.4f units".format(cycle.averageBackorder))
        }
        appendLine("  Reorder point         %,.4f units  (%d rounded up)"
            .format(reorderPoint, roundedReorderPoint))
        appendLine("  Ordering cost         %,.2f per %s".format(measures.ordering, unit))
        appendLine("  Holding cost          %,.2f per %s".format(measures.holding, unit))
        if (measures.backorder > 0.0) {
            appendLine("  Backorder cost        %,.2f per %s".format(measures.backorder, unit))
        }
        if (measures.stockout > 0.0) {
            appendLine("  Stockout cost         %,.2f per %s".format(measures.stockout, unit))
        }
        appendLine("  Relevant cost         %,.2f per %s".format(measures.relevantCost, unit))
        appendLine("  Purchase cost         %,.2f per %s".format(measures.purchase, unit))
        appendLine("  Total cost            %,.2f per %s".format(measures.totalCost, unit))
        appendLine("  Ready rate            %.4f".format(measures.readyRate))
        if (measures.averageWait > 0.0) {
            appendLine("  Average wait          %,.5f %s".format(measures.averageWait, unit))
        }
        appendLine("  Chosen by             $choice")
        if (approximated.isNotEmpty()) {
            appendLine("  Ignored               ${approximated.joinToString("; ")}")
        }
    }
}
