package inventory.lotsizing

import inventory.lotsizing.models.*

/**
 * Generates every figure printed in @sec-eoq-design of the book.
 *
 * The section promises parity with the worksheets of @sec-eoq-spreadsheets, so its numbers
 * are produced by running the code rather than typed. Regenerate with
 * `scripts/section-3-11-listings.sh` after any change that could move one.
 */
fun main() {
    fun rule(title: String) { println(); println("### $title") }

    rule("3.11.1 the classic EOQ sheet")
    val sheet = CostParameters(
        demandRate = 100.0,
        unitCost = 8.0,
        orderCost = 20.0,
        holding = HoldingCost.carryingCharge(0.25 / 12.0),
        leadTime = 0.25,
        timeUnit = TimeUnit.MONTH,
    )
    val best = EconomicOrderQuantity.optimize(sheet)
    print((best as InventoryPolicyAnalysis).report())
    println("  holdingRateAt         %.8f".format(sheet.holdingRateAt(best.orderQuantity)))

    rule("3.11.1 the three joining rows")
    for (quantity in listOf(155.0, 150.0, 160.0, 200.0)) {
        val penalty = EconomicOrderQuantity.evaluate(sheet, quantity).penaltyAgainst(best)
        println("  Q = %6.1f   ratio %.8f   difference %+.6f   relative error %.8f"
            .format(quantity, penalty.ratio, penalty.difference, penalty.relativeError))
    }

    rule("3.11.2 the reorder point against the lead time")
    for (lead in listOf(0.25, 1.0, 1.6, 2.0, 3.0, 4.0)) {
        sheet.leadTime = lead
        val analysis = EconomicOrderQuantity.optimize(sheet)
        println("  lead time %4.2f months   reorder point %8.4f   rounded %3d"
            .format(lead, analysis.reorderPoint, analysis.roundedReorderPoint))
    }
    sheet.leadTime = 0.25

    rule("3.11.3 the production sheet")
    val production = CostParameters(
        demandRate = 2500.0,
        unitCost = 2.0,
        orderCost = 50.0,
        holding = HoldingCost.carryingCharge(0.3),
        replenishment = Replenishment.atRate(10_000.0, 2500.0),
    )
    val made = EconomicProductionQuantity.optimize(production)
    println("  holding rate h             %.4f".format(production.baseHoldingRate))
    println("  surviving fraction         %.4f".format(production.survivingFraction))
    println("  effective rate h'          %.4f"
        .format(production.baseHoldingRate * production.survivingFraction))
    print((made as InventoryPolicyAnalysis).report())
    println("  time replenishing          %.7f".format(made.cycle.replenishmentTime!!))
    println("  peak on hand               %.6f".format(made.cycle.maxOnHand))

    rule("3.11.4 the all-units sheet")
    val allUnits = CostParameters(
        demandRate = 600.0,
        orderCost = 8.0,
        schedule = AllUnits(listOf(
            PriceLevel(0.0, 0.30), PriceLevel(500.0, 0.29), PriceLevel(1000.0, 0.28))),
        holding = HoldingCost.carryingCharge(0.2),
    )
    printCandidates(AllUnitsDiscountModel.optimize(allUnits))

    rule("3.11.4 the incremental sheet")
    val schedule = Incremental(listOf(
        PriceLevel(0.0, 0.30), PriceLevel(500.0, 0.29), PriceLevel(1000.0, 0.28)))
    val incremental = CostParameters(
        demandRate = 600.0, orderCost = 8.0, schedule = schedule,
        holding = HoldingCost.carryingCharge(0.2))
    println("  level   break   price   fixed charge   effective order cost")
    schedule.levels.forEachIndexed { index, level ->
        println("  %5d  %6.0f  %6.2f  %13.2f  %21.2f".format(
            index + 1, level.breakPoint, level.unitCost, schedule.fixedChargeAt(index),
            IncrementalDiscountModel.effectiveOrderCost(incremental, index)))
    }
    printCandidates(IncrementalDiscountModel.optimize(incremental))

    rule("3.11.5 what it refuses")
    listOf<Pair<String, () -> Unit>>(
        "a zero backorder cost" to { ShortagePolicy.backordered(0.0); Unit },
        "a rate below the demand rate" to { Replenishment.atRate(80.0, 100.0); Unit },
        "a model that needs a backorder cost" to { PlannedBackorderModel.optimize(sheet); Unit },
    ).forEach { (what, attempt) ->
        try { attempt(); println("  $what: accepted, which is wrong") }
        catch (e: Exception) { println("  $what:"); println("    ${e.message}") }
    }

    rule("3.11.5 rounding to a case quantity")
    val cased = CostParameters(
        demandRate = 1500.0, unitCost = 40.0, orderCost = 120.0,
        holding = HoldingCost.carryingCharge(0.22), leadTime = 10.0 / 365.0)
    val unrounded = EconomicOrderQuantity.optimize(cased)
    val rounded = EconomicOrderQuantity.optimize(cased, QuantityRounding.multipleOf(48.0))
    println("  unconstrained optimum %.4f".format(unrounded.orderQuantity))
    for (quantity in (rounded.choice as QuantityChoice.Rounded).considered) {
        val analysis = EconomicOrderQuantity.evaluate(cased, quantity)
        println("    %6.0f   relevant cost %,9.2f   penalty %.6f".format(
            quantity, analysis.measures.relevantCost, analysis.penaltyAgainst(unrounded).ratio))
    }
    println("  chosen %.0f".format(rounded.orderQuantity))
}

private fun printCandidates(analysis: InventoryPolicyAnalysisIfc) {
    println("  level   break   price    candidate   feasible    total cost   chosen")
    for (c in (analysis.choice as QuantityChoice.AmongCandidates).candidates) {
        println("  %5d  %6.0f  %6.2f  %11.4f  %9s  %12.4f  %5s".format(
            c.levelIndex + 1, c.breakPoint, c.unitCost, c.quantity,
            if (c.feasible) "yes" else "no", c.totalCost, if (c.selected) "<--" else ""))
    }
    println("  order %.4f at a total cost of %.4f"
        .format(analysis.orderQuantity, analysis.measures.totalCost))
}
