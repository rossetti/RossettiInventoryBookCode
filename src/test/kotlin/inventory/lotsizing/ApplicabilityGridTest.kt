package inventory.lotsizing

import inventory.lotsizing.models.*
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * The verdict of every model against every combination of the three assumptions plus a
 * schedule, printed so the derived rule can be read against the six it replaced.
 */
class ApplicabilityGridTest {

    private val models = listOf(
        EconomicOrderQuantity, EconomicProductionQuantity, PlannedBackorderModel,
        GeneralBackorderModel, AllUnitsDiscountModel, IncrementalDiscountModel,
    )

    private fun parameters(
        replenishment: Replenishment,
        shortages: ShortagePolicy,
        schedule: PriceSchedule,
    ) = CostParameters(
        demandRate = 1000.0, schedule = schedule, orderCost = 20.0,
        holding = HoldingCost.rate(2.0),
        replenishment = replenishment, shortages = shortages,
    )

    @Test
    fun `the grid of verdicts`() {
        val schedules = listOf<Pair<String, PriceSchedule>>(
            "flat" to Flat(5.0),
            "all-units" to AllUnits(listOf(PriceLevel(0.0, 5.0), PriceLevel(100.0, 4.5))),
            "incremental" to Incremental(listOf(PriceLevel(0.0, 5.0), PriceLevel(100.0, 4.5))),
        )
        val replenishments = listOf<Pair<String, Replenishment>>(
            "instant" to Replenishment.Instantaneous,
            "at rate" to Replenishment.atRate(4000.0, 1000.0),
        )
        val shortagePolicies = listOf<Pair<String, ShortagePolicy>>(
            "forbidden" to ShortagePolicy.NotPermitted,
            "backordered" to ShortagePolicy.Backordered(3.0),
            "backordered + pi" to ShortagePolicy.Backordered(3.0, 1.5),
        )
        for (model in models) {
            println("## ${model.name}")
            for ((sn, sch) in schedules) for ((rn, rep) in replenishments)
                for ((pn, pol) in shortagePolicies) {
                    val v = model.applicability(parameters(rep, pol, sch))
                    val text = when (v) {
                        is Applicability.Applicable -> "APPLICABLE"
                        is Applicability.NotApplicable -> "REFUSED   ${v.reason}"
                        is Applicability.Approximating -> "APPROX    ${v.ignored}"
                    }
                    println("   %-12s %-8s %-17s %s".format(sn, rn, pn, text))
                }
        }
    }

    /** The exactly-matching combination is Applicable for every model. */
    @Test
    fun `each model is applicable on the parameters it was written for`() {
        val exact = mapOf<LotSizingModel, CostParametersIfc>(
            EconomicOrderQuantity to parameters(
                Replenishment.Instantaneous, ShortagePolicy.NotPermitted, Flat(5.0)),
            EconomicProductionQuantity to parameters(
                Replenishment.atRate(4000.0, 1000.0), ShortagePolicy.NotPermitted, Flat(5.0)),
            PlannedBackorderModel to parameters(
                Replenishment.Instantaneous, ShortagePolicy.Backordered(3.0), Flat(5.0)),
            GeneralBackorderModel to parameters(
                Replenishment.atRate(4000.0, 1000.0), ShortagePolicy.Backordered(3.0, 1.5), Flat(5.0)),
            AllUnitsDiscountModel to parameters(
                Replenishment.Instantaneous, ShortagePolicy.NotPermitted,
                AllUnits(listOf(PriceLevel(0.0, 5.0), PriceLevel(100.0, 4.5)))),
            IncrementalDiscountModel to parameters(
                Replenishment.Instantaneous, ShortagePolicy.NotPermitted,
                Incremental(listOf(PriceLevel(0.0, 5.0), PriceLevel(100.0, 4.5)))),
        )
        exact.forEach { (model, p) ->
            assertIs<Applicability.Applicable>(model.applicability(p), model.name)
        }
    }
}
