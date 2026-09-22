package inventory.lotsizing

/** A policy a model chose, with the record of how it chose it. */
data class ChosenPolicy(val policy: Policy, val choice: QuantityChoice)

/**
 * The pipeline every model shares: from a chosen policy to a finished analysis.
 *
 * A subclass supplies what is specific to it, namely its assumptions, its
 * applicability rule, and its policy rule. The cycle geometry of @sec-eoq-cycle, the
 * cost assembly of @sec-eoq-cost, the service measures of @sec-eoq-measures, and the reorder
 * point of @sec-eoq-leadtime are written once, here.
 */
abstract class AbstractLotSizingModel(override val name: String) : LotSizingModel {

    /** The policy this model chooses for these parameters, and how it chose it. */
    protected abstract fun choosePolicy(parameters: CostParametersIfc): ChosenPolicy

    /**
     * The verdict of @sec-eoq-design, derived from [assumptions] and written once.
     *
     * Each model used to test for its own assumptions by hand, which stated every
     * assumption twice and let the two statements drift: three models ignored a finite
     * replenishment rate and only one of them reported its value, and two ignored a
     * per-unit stockout charge without saying so. Deriving the verdict from the
     * declaration gives the declaration a reader and leaves one message per case.
     *
     * A mismatch is refused outright only when the model would have nothing to do.
     * Everything else is an approximation, and is reported as one rather than refused.
     */
    final override fun applicability(parameters: CostParametersIfc): Applicability {
        val schedule = parameters.schedule
        if (!assumptions.requiresSchedule.isInstance(schedule) &&
            !assumptions.approximatesOtherSchedules
        ) {
            return Applicability.NotApplicable(
                "the model needs ${describe(assumptions.requiresSchedule)} price " +
                    "schedule, and the schedule is ${schedule.label} one"
            )
        }
        val shortages = parameters.shortages
        if (!assumptions.shortagesForbidden && shortages !is ShortagePolicy.Backordered) {
            return Applicability.NotApplicable(
                "the model plans a backorder level, and these parameters forbid shortages"
            )
        }
        val ignored = buildList {
            val replenishment = parameters.replenishment
            if (assumptions.instantaneousReplenishment && replenishment is Replenishment.AtRate) {
                add("the finite replenishment rate ${replenishment.rate}")
            }
            if (assumptions.shortagesForbidden && shortages is ShortagePolicy.Backordered) {
                add("that shortages are permitted")
            }
            if (!assumptions.chargesPerUnitShort &&
                shortages is ShortagePolicy.Backordered && shortages.costPerUnit > 0.0
            ) {
                add("the per-unit stockout charge ${shortages.costPerUnit}")
            }
            if (!assumptions.requiresSchedule.isInstance(schedule)) {
                add("the quantity discount schedule")
            }
        }
        return if (ignored.isEmpty()) Applicability.Applicable
        else Applicability.Approximating(ignored)
    }

    final override fun evaluate(
        parameters: CostParametersIfc,
        policy: Policy,
    ): InventoryPolicyAnalysisIfc {
        if (parameters.shortages is ShortagePolicy.NotPermitted) {
            require(policy.maxBackorder == 0.0) {
                "The parameters forbid shortages, so a policy cannot plan a backorder " +
                    "level of ${policy.maxBackorder}."
            }
        }
        return assemble(parameters, policy, QuantityChoice.Supplied)
    }

    final override fun optimize(
        parameters: CostParametersIfc,
        rounding: QuantityRounding,
    ): InventoryPolicyAnalysisIfc {
        when (val verdict = applicability(parameters)) {
            is Applicability.NotApplicable -> error("$name cannot be used here: ${verdict.reason}")
            else -> Unit
        }
        val chosen = choosePolicy(parameters)
        if (rounding is QuantityRounding.None) {
            return assemble(parameters, chosen.policy, chosen.choice)
        }
        val admissible = rounding.admissibleAround(chosen.policy.orderQuantity)
        require(admissible.isNotEmpty()) {
            "No admissible quantity brackets ${chosen.policy.orderQuantity} under $rounding"
        }
        val best = admissible
            .map { assemble(parameters, rescale(chosen.policy, it, parameters), chosen.choice) }
            .minBy { it.measures.totalCost }
        return best.copy(
            choice = QuantityChoice.Rounded(chosen.policy.orderQuantity, admissible),
        )
    }

    /**
     * The reorder point of @eq-reorder-general. `mod` returns the part of the lead time not
     * already covered by orders outstanding, so one expression serves both the case
     * where the lead time is shorter than a cycle and the case where it is longer.
     */
    protected open fun reorderPoint(
        parameters: CostParametersIfc,
        cycle: InventoryCycle,
        policy: Policy,
    ): Double = parameters.leadTime.mod(cycle.length) * parameters.demandRate - policy.maxBackorder

    /** How a required schedule kind reads in a message. */
    private fun describe(kind: kotlin.reflect.KClass<out PriceSchedule>): String = when (kind) {
        AllUnits::class -> "an all-units"
        Incremental::class -> "an incremental"
        else -> "a flat"
    }

    /** What this model discards when applied to these parameters. */
    protected fun ignoredBy(parameters: CostParametersIfc): List<String> =
        when (val verdict = applicability(parameters)) {
            is Applicability.Approximating -> verdict.ignored
            else -> emptyList()
        }

    private fun assemble(
        parameters: CostParametersIfc,
        policy: Policy,
        choice: QuantityChoice,
    ): InventoryPolicyAnalysis {
        val cycle = InventoryCycle(parameters, policy)
        return InventoryPolicyAnalysis(
            parameters = parameters.snapshot(),
            model = name,
            policy = policy,
            cycle = cycle,
            measures = PerformanceMeasures(cycle, parameters),
            reorderPoint = reorderPoint(parameters, cycle, policy),
            choice = choice,
            approximated = ignoredBy(parameters),
        )
    }

    /**
     * A policy at a different order quantity, with the backorder level rescaled in
     * proportion so that it stays feasible.
     */
    private fun rescale(
        policy: Policy,
        orderQuantity: Double,
        parameters: CostParametersIfc,
    ): Policy {
        if (policy.maxBackorder == 0.0) return Policy(orderQuantity)
        val fraction = policy.maxBackorder / (policy.orderQuantity * parameters.survivingFraction)
        return Policy(orderQuantity, fraction * orderQuantity * parameters.survivingFraction)
    }
}
