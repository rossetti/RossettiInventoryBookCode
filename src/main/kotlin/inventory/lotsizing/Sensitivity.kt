package inventory.lotsizing

import inventory.lotsizing.models.EconomicOrderQuantity
import inventory.lotsizing.models.EconomicProductionQuantity
import inventory.lotsizing.models.PlannedBackorderModel
import kotlin.reflect.KMutableProperty1

/** A parameter that can be varied, named by a property reference. */
typealias CostParameter = KMutableProperty1<CostParameters, Double>

/**
 * What an error of a stated size in one cost parameter costs, which is the question
 * @sec-costparams-h asks.
 *
 * Both directions are carried because they differ. The penalty is symmetric in the
 * ratio of believed to true value and its reciprocal, but a percentage error up and
 * the same percentage down are not reciprocal ratios: a quarter too high is a factor
 * of 1.25 and a quarter too low is 0.75, whose reciprocal is 1.333. Understating a
 * parameter therefore costs more than overstating it by the same percentage.
 */
data class ParameterSensitivity(
    val parameter: CostParameter,
    val base: Double,
    val assumedError: Double,
    val overestimate: CostPenalty,
    val underestimate: CostPenalty,
    val elasticity: Double,
) {
    /** The larger of the two penalties, which is how a ranking is ordered. */
    val worstRatio: Double get() = maxOf(overestimate.ratio, underestimate.ratio)

    override fun toString(): String =
        "ParameterSensitivity(${parameter.name}, base=$base, error=±${assumedError * 100}%, " +
            "over=${"%.6f".format(overestimate.ratio)}, " +
            "under=${"%.6f".format(underestimate.ratio)}, elasticity=$elasticity)"
}

/**
 * Optimize at each level of one parameter, holding the rest at [base].
 *
 * The sequence is lazy and single-pass, so a caller who reduces over it never holds
 * more than one analysis at a time, and a caller who wants them all calls `toList`.
 * A working parameter object is created once and mutated per level; it never escapes,
 * and each analysis snapshots its own inputs before the next level's mutation.
 */
fun LotSizingModel.analyzing(
    base: CostParametersIfc,
    parameter: CostParameter,
    levels: DoubleArray,
    rounding: QuantityRounding = QuantityRounding.None,
): Sequence<InventoryPolicyAnalysisIfc> = sequence {
    require(levels.isNotEmpty()) { "A range needs at least one level" }
    val working = base.snapshot().toMutable()
    for (level in levels) {
        parameter.set(working, level)
        yield(optimize(working, rounding))
    }
}

/**
 * Optimize at every combination of the given parameters and their levels, in
 * row-major order over [varied] as supplied.
 */
fun LotSizingModel.analyzing(
    base: CostParametersIfc,
    vararg varied: Pair<CostParameter, DoubleArray>,
): Sequence<InventoryPolicyAnalysisIfc> = sequence {
    require(varied.isNotEmpty()) { "A grid needs at least one parameter" }
    require(varied.map { it.first.name }.toSet().size == varied.size) {
        "A grid cannot vary the same parameter twice"
    }
    val working = base.snapshot().toMutable()
    val counters = IntArray(varied.size)
    while (true) {
        varied.forEachIndexed { axis, (parameter, levels) ->
            parameter.set(working, levels[counters[axis]])
        }
        yield(optimize(working))
        var axis = varied.size - 1
        while (axis >= 0) {
            counters[axis]++
            if (counters[axis] < varied[axis].second.size) break
            counters[axis] = 0
            axis--
        }
        if (axis < 0) break
    }
}

/**
 * Optimize under each named scenario, each of which edits its own copy of [base].
 * A labelled set of analyses is a labelled set, so the result is a map.
 */
fun LotSizingModel.analyzing(
    base: CostParametersIfc,
    scenarios: Map<String, CostParameters.() -> Unit>,
): Map<String, InventoryPolicyAnalysisIfc> =
    scenarios.mapValues { (_, edit) ->
        val working = base.snapshot().toMutable()
        working.edit()
        optimize(working)
    }

/**
 * What it costs to order the quantity you would choose under [believed], when the
 * truth is [actual].
 *
 * Both compared analyses are under [actual], which is what makes them comparable.
 */
fun LotSizingModel.penaltyOfDecidingUnder(
    believed: CostParametersIfc,
    actual: CostParametersIfc,
): CostPenalty {
    val chosen = optimize(believed).policy
    val incurred = evaluate(actual, chosen)
    return incurred.penaltyAgainst(optimize(actual))
}

/** How much an error of [assumedError] in [parameter] costs, in both directions. */
fun LotSizingModel.sensitivityTo(
    base: CostParametersIfc,
    parameter: CostParameter,
    assumedError: Double,
): ParameterSensitivity {
    require(assumedError > 0.0 && assumedError < 1.0) {
        "The assumed error must be a fraction strictly between 0 and 1, was $assumedError"
    }
    val truth = base.snapshot()
    val baseValue = parameter.get(truth.toMutable())

    fun believing(value: Double): CostParametersIfc =
        truth.toMutable().also { parameter.set(it, value) }

    return ParameterSensitivity(
        parameter = parameter,
        base = baseValue,
        assumedError = assumedError,
        overestimate = penaltyOfDecidingUnder(believing(baseValue * (1.0 + assumedError)), truth),
        underestimate = penaltyOfDecidingUnder(believing(baseValue * (1.0 - assumedError)), truth),
        elasticity = elasticityOf(truth, parameter),
    )
}

/** The same for several parameters, ranked by the larger of the two penalties. */
fun LotSizingModel.sensitivities(
    base: CostParametersIfc,
    parameters: List<CostParameter>,
    assumedError: Double,
): List<ParameterSensitivity> =
    parameters.map { sensitivityTo(base, it, assumedError) }
        .sortedWith(compareByDescending<ParameterSensitivity> { it.worstRatio }
            .thenBy { it.parameter.name })

/**
 * The fractional change in the optimal order quantity per fractional change in
 * [parameter], at the base point.
 *
 * For the models whose order quantity is proportional to the square root of
 * k lambda over h, this is exactly plus or minus one half. It is computed by central
 * difference rather than asserted, so it stays honest for a model where it is not.
 */
fun LotSizingModel.elasticityOf(
    base: CostParametersIfc,
    parameter: CostParameter,
    step: Double = 1.0e-4,
): Double {
    val truth = base.snapshot()
    val baseValue = parameter.get(truth.toMutable())
    if (baseValue == 0.0) return Double.NaN
    fun quantityAt(factor: Double): Double {
        val working = truth.toMutable()
        parameter.set(working, baseValue * factor)
        return optimize(working).orderQuantity
    }
    val baseQuantity = optimize(truth).orderQuantity
    val up = quantityAt(1.0 + step)
    val down = quantityAt(1.0 - step)
    return ((up - down) / (2.0 * step)) / baseQuantity
}
