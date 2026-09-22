package inventory.lotsizing

import inventory.lotsizing.models.AllUnitsDiscountModel
import inventory.lotsizing.models.EconomicOrderQuantity
import inventory.lotsizing.models.EconomicProductionQuantity
import inventory.lotsizing.models.GeneralBackorderModel
import inventory.lotsizing.models.IncrementalDiscountModel
import inventory.lotsizing.models.PlannedBackorderModel
import kotlin.reflect.KClass

/**
 * What a model assumes about the system it is applied to.
 *
 * This is the declaration, and [AbstractLotSizingModel.applicability] is the only
 * reader of it. Stating an assumption here and testing for it again by hand would be
 * writing the same rule twice in two forms that can disagree, which is what the six
 * models used to do.
 */
data class ModelAssumptions(
    /** True when the model assumes the whole order arrives at one instant. */
    val instantaneousReplenishment: Boolean,
    /** True when the model assumes shortages are not allowed. */
    val shortagesForbidden: Boolean,
    /** True when the model prices the incident of being short. */
    val chargesPerUnitShort: Boolean,
    /** The kind of price schedule the model requires. */
    val requiresSchedule: KClass<out PriceSchedule> = Flat::class,
) {
    /**
     * Whether a schedule this model does not require can be approximated rather than
     * refused.
     *
     * A model assuming one price can be applied to a discount schedule by using the
     * first level's price, which @sec-eoq-discounts justifies and which the caller asks
     * for. A model built *for* a discount schedule has nothing to do without one, so
     * the asymmetry is in the mathematics rather than in the convenience.
     */
    val approximatesOtherSchedules: Boolean get() = requiresSchedule == Flat::class
}

/** A model's verdict on whether it can be used with a given parameter set. */
sealed interface Applicability {

    /** The model's assumptions match the parameters. */
    data object Applicable : Applicability

    /**
     * The model cannot be used at all: it needs something the parameters do not
     * supply, such as a backorder cost where shortages are forbidden.
     */
    data class NotApplicable(val reason: String) : Applicability

    /**
     * The model's assumptions are stricter than the parameters, so applying it
     * discards something. This is a legitimate thing to do deliberately, which is
     * what an approximating caller asks for, and [ignored] says what would be discarded.
     */
    data class Approximating(val ignored: List<String>) : Applicability
}

/**
 * A rule for choosing a lot sizing policy, plus a declaration of what it assumes.
 *
 * Every implementation is stateless, so two calls with equal arguments return equal
 * results and any number of callers may share one instance.
 */
interface LotSizingModel {

    /** The model's name, as it appears in a report. */
    val name: String

    /** What the model assumes. */
    val assumptions: ModelAssumptions

    /** Whether the model can be used here, and what it would discard if it were. */
    fun applicability(parameters: CostParametersIfc): Applicability

    /** Analyze the cycle a given policy produces. */
    fun evaluate(parameters: CostParametersIfc, policy: Policy): InventoryPolicyAnalysisIfc

    /** Analyze the cycle a given order quantity produces, with no backorders. */
    fun evaluate(parameters: CostParametersIfc, orderQuantity: Double): InventoryPolicyAnalysisIfc =
        evaluate(parameters, Policy(orderQuantity))

    /** Choose the cost-minimizing policy and analyze the cycle it produces. */
    fun optimize(
        parameters: CostParametersIfc,
        rounding: QuantityRounding = QuantityRounding.None,
    ): InventoryPolicyAnalysisIfc

    companion object {
        /**
         * The model whose assumptions match these parameters exactly, which is
         * @tbl-special-cases written as an exhaustive choice over the two sealed assumption
         * types. This is the one place in the code where that table lives.
         */
        fun forParameters(parameters: CostParametersIfc): LotSizingModel {
            val schedule = parameters.schedule
            if (schedule is AllUnits) return AllUnitsDiscountModel
            if (schedule is Incremental) return IncrementalDiscountModel
            return when (parameters.replenishment) {
                is Replenishment.Instantaneous -> when (parameters.shortages) {
                    is ShortagePolicy.NotPermitted -> EconomicOrderQuantity
                    is ShortagePolicy.Backordered -> PlannedBackorderModel
                }
                is Replenishment.AtRate -> when (parameters.shortages) {
                    is ShortagePolicy.NotPermitted -> EconomicProductionQuantity
                    is ShortagePolicy.Backordered -> GeneralBackorderModel
                }
            }
        }
    }
}
