package inventory.lotsizing

import inventory.lotsizing.models.*

/**
 * The worked examples of Chapter 3, run. Each prints the analysis the text derives.
 *
 *     ./gradlew run -PmainClass=inventory.lotsizing.Chapter3ExamplesKt
 */
fun main() {
    println("=".repeat(72))
    println("@exm-cycle and @exm-cycle-measures: one cycle of the general model")
    println("=".repeat(72))
    val general = CostParameters(
        demandRate = 100.0,
        unitCost = 8.0,
        orderCost = 20.0,
        holding = HoldingCost.rate(4.0),
        replenishment = Replenishment.atRate(250.0, 100.0),
        shortages = ShortagePolicy.backordered(costPerUnitPerTime = 6.0),
    )
    val cycle = GeneralBackorderModel.evaluate(general, Policy(400.0, 30.0))
    print((cycle as InventoryPolicyAnalysis).report())
    println("  Segments              ${cycle.cycle.segments.toList().joinToString { "%.2f".format(it) }}")
    println("  They sum to           %.2f, which is Q over lambda".format(cycle.cycle.segments.total))

    println()
    println("=".repeat(72))
    println("@exm-eoq: the economic order quantity")
    println("=".repeat(72))
    val item = CostParameters(
        demandRate = 220.0,
        unitCost = 1200.0,
        orderCost = 800.0,
        holding = HoldingCost.carryingCharge(0.18),
        leadTime = 7.0 / 365.0,
    )
    val best = EconomicOrderQuantity.optimize(item)
    print((best as InventoryPolicyAnalysis).report())

    println()
    println("@exm-eoq-sensitivity: what a wrong order quantity costs")
    for (ratio in listOf(0.5, 0.8, 1.5, 2.0)) {
        val chosen = EconomicOrderQuantity.evaluate(item, ratio * best.orderQuantity)
        val penalty = chosen.penaltyAgainst(best)
        println("  Q at %.0f%% of optimal: relevant cost %,10.2f, penalty %.4f, %,8.2f a year"
            .format(ratio * 100, chosen.measures.relevantCost, penalty.ratio, penalty.difference))
    }


    println()
    println("=".repeat(72))
    println("@exm-allunits: an all-units discount")
    println("=".repeat(72))
    val allUnits = CostParameters(
        demandRate = 8000.0,
        schedule = AllUnits(listOf(PriceLevel(0.0, 10.0), PriceLevel(500.0, 9.0))),
        orderCost = 30.0,
        holding = HoldingCost.carryingCharge(0.30),
    )
    printCandidates(AllUnitsDiscountModel.optimize(allUnits))

    println()
    println("=".repeat(72))
    println("@exm-incremental: an incremental discount")
    println("=".repeat(72))
    val incremental = CostParameters(
        demandRate = 3000.0,
        schedule = Incremental(listOf(
            PriceLevel(0.0, 3.00), PriceLevel(500.0, 2.97), PriceLevel(1500.0, 2.95))),
        orderCost = 50.0,
        holding = HoldingCost.carryingCharge(0.30),
    )
    val schedule = incremental.schedule as Incremental
    println("  Level  break point   price   fixed charge   effective order cost")
    schedule.levels.forEachIndexed { index, level ->
        println("  %5d  %11.0f  %6.2f  %13.2f  %21.2f".format(
            index + 1, level.breakPoint, level.unitCost, schedule.fixedChargeAt(index),
            IncrementalDiscountModel.effectiveOrderCost(incremental, index)))
    }
    println()
    printCandidates(IncrementalDiscountModel.optimize(incremental))

    println()
    println("=".repeat(72))
    println("@sec-costparams-h: what an error in a parameter costs")
    println("=".repeat(72))
    val ranked = EconomicOrderQuantity.sensitivities(
        item,
        listOf(CostParameters::orderCost, CostParameters::demandRate),
        assumedError = 0.25,
    )
    println("  parameter      base     elasticity   too high   too low")
    for (s in ranked) {
        println("  %-12s %8.2f   %+9.3f   %8.6f  %8.6f".format(
            s.parameter.name, s.base, s.elasticity,
            s.overestimate.ratio, s.underestimate.ratio))
    }
    println("  Understating a parameter costs more than overstating it by the same percentage,")
    println("  because a quarter too low is a factor of 0.75 and its reciprocal is 1.333.")

    println()
    println("@sec-eoq-classic: the order quantity against the carrying charge")
    val levels = DoubleArray(9) { 0.10 + it * 0.025 }
    println("  charge   quantity   cycle (yr)   relevant cost")
    EconomicOrderQuantity.analyzing(item, CostParameters::carryingCharge, levels)
        .forEach {
            println("  %6.3f  %9.2f  %11.4f  %14.2f".format(
                (it.parameters.holding as HoldingCost.CarryingCharge).rate,
                it.orderQuantity, it.cycleTime, it.measures.relevantCost))
        }
}

private fun printCandidates(analysis: InventoryPolicyAnalysisIfc) {
    val candidates = (analysis.choice as QuantityChoice.AmongCandidates).candidates
    println("  Level  break point   price   candidate   feasible   total cost   chosen")
    for (c in candidates) {
        println("  %5d  %11.0f  %6.2f  %10.2f  %9s  %11.2f  %7s".format(
            c.levelIndex + 1, c.breakPoint, c.unitCost, c.quantity,
            if (c.feasible) "yes" else "no", c.totalCost, if (c.selected) "<--" else ""))
    }
    println("  Order %.2f at a total cost of %,.2f"
        .format(analysis.orderQuantity, analysis.measures.totalCost))
}
