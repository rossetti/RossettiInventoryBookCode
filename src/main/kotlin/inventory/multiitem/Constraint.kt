package inventory.multiitem

/**
 * A measure together with the ceiling management has put on it.
 *
 * "At most ten thousand dollars of average investment" is one sentence in the domain, so
 * it is one object. Keeping the measure separate from the ceiling is what lets the same
 * measure serve the constrained problem, the exchange curve and a management report.
 */
class Constraint(val measure: ConstrainableMeasure, val limit: Double) {

    init { require(limit > 0.0) { "A limit must be positive, was $limit" } }

    fun isMetBy(plan: ReplenishmentPlan): Boolean = measure.measure(plan) <= limit

    /** Positive when there is room left, negative when the plan overshoots. */
    fun slackIn(plan: ReplenishmentPlan): Double = limit - measure.measure(plan)

    override fun toString(): String = "Constraint(${measure.name} <= $limit ${measure.unit})"
}
