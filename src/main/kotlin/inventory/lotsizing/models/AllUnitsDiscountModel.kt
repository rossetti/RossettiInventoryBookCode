package inventory.lotsizing.models

import inventory.lotsizing.*

/**
 * All-units discounting, @sec-eoq-allunits: the whole order is priced at the rate its
 * size earns.
 *
 * The cost rate is discontinuous, dropping to a lower curve at each break point, so
 * the minimum cannot be found by differentiating alone. Each level supplies its own
 * unconstrained quantity; a level whose quantity lies below its own interval
 * contributes its break point instead, because that interval's cost is still falling
 * there. Every candidate is evaluated and the cheapest kept.
 */
object AllUnitsDiscountModel : AbstractLotSizingModel("All-units discount model") {

    override val assumptions = ModelAssumptions(
        instantaneousReplenishment = true,
        shortagesForbidden = true,
        chargesPerUnitShort = false,
        requiresSchedule = AllUnits::class,
    )


    override fun choosePolicy(parameters: CostParametersIfc): ChosenPolicy {
        val schedule = parameters.schedule as AllUnits
        val candidateQuantities = buildList {
            schedule.levels.forEachIndexed { index, level ->
                val holdingRate = holdingRateFor(parameters, level.unitCost)
                val unconstrained = EconomicOrderQuantity.orderQuantityFor(
                    parameters.orderCost, parameters.demandRate, holdingRate)
                when {
                    schedule.isInsideLevel(index, unconstrained) -> add(index to unconstrained)
                    // Below its own interval: the interval's cost is falling up to the
                    // unconstrained point, so its cheapest available quantity is the break point.
                    unconstrained < level.breakPoint && level.breakPoint > 0.0 ->
                        add(index to level.breakPoint)
                    // Above its own interval: the next level's candidate dominates.
                    else -> Unit
                }
            }
        }
        return chooseAmong(parameters, candidateQuantities, schedule)
    }

    internal fun holdingRateFor(parameters: CostParametersIfc, unitCost: Double): Double =
        when (val h = parameters.holding) {
            is HoldingCost.Rate -> h.amount
            is HoldingCost.CarryingCharge -> h.rate * unitCost
        }

    internal fun chooseAmong(
        parameters: CostParametersIfc,
        candidates: List<Pair<Int, Double>>,
        schedule: PriceSchedule,
    ): ChosenPolicy {
        check(candidates.isNotEmpty()) {
            "$name found no admissible quantity for the supplied price schedule"
        }
        val evaluated = candidates.map { (index, quantity) ->
            val analysis = evaluate(parameters, Policy(quantity))
            Triple(index, quantity, analysis)
        }
        // Selected on the total and not on the relevant cost. @sec-performance-cost sets the
        // purchase term aside because it is identical under every policy, and Section
        // 3.8 is where that premise stops holding: once the price depends on the
        // quantity, the purchase term is exactly what the decision moves. Ranking the
        // candidates of @sec-eoq-ss-discounts on relevant cost alone chooses 400 over 500,
        // which is the wrong answer by six dollars a year.
        val bestIndex = evaluated.indices.minBy { evaluated[it].third.measures.totalCost }
        val rows = evaluated.mapIndexed { position, (index, quantity, analysis) ->
            QuantityCandidate(
                levelIndex = index,
                breakPoint = schedule.levels[index].breakPoint,
                unitCost = schedule.levels[index].unitCost,
                quantity = quantity,
                feasible = schedule.isInsideLevel(index, quantity),
                relevantCost = analysis.measures.relevantCost,
                totalCost = analysis.measures.totalCost,
                selected = position == bestIndex,
            )
        }
        return ChosenPolicy(
            Policy(evaluated[bestIndex].second),
            QuantityChoice.AmongCandidates(rows),
        )
    }
}
