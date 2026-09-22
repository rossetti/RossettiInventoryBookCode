package inventory.multiechelon

/**
 * Generates every number Chapter 9 prints for the utility's repairables.
 *
 * ```
 *   ./gradlew run -PmainClass=inventory.multiechelon.Section9ListingsKt
 * ```
 *
 * ChapterExamplesTest asserts the same values, so a disagreement fails the
 * build rather than waiting to be noticed here.
 */
private fun days(d: Double) = d / 365.0

private fun utility(depotRepairDays: Double = 60.0, depotStock: Int = 16, baseStock: Int = 4): VMItem {
    val item = VMItem(1, depotStock, 3800.0, days(depotRepairDays))
    repeat(4) { item.addBaseItem(45.0, days(30.0), 0.3, days(10.0), baseStock, 3800.0) }
    item.stockLevel = depotStock
    return item
}

fun main() {
    fun rule(title: String) { println(); println("### $title") }

    rule("@sec-multiechelon-onefortone, the network")
    val d = utility()
    println("  requests per storeroom  %10.4f per year".format(d.baseItems[0].replenishmentDemandRate))
    println("  depot demand rate       %10.4f per year".format(d.demandRate))
    println("  depot pipeline mu_0     %10.4f units".format(d.leadTimeDemand.mean()))
    println("  storeroom own pipeline  %10.4f units".format(
        d.baseItems[0].expectedNumInBaseRepair + d.baseItems[0].expectedNumInTransit))

    rule("@sec-multiechelon-delay, the delay against depot stock")
    println("  %4s %10s %10s %10s".format("S_0", "Bbar_0", "Var[B_0]", "days"))
    for (s0 in listOf(16, 18, 20, 22, 24, 26)) {
        val x = utility(depotStock = s0)
        println("  %4d %10.4f %10.4f %10.2f".format(
            s0, x.expectedBackOrders, x.varianceBackOrders, x.expectedWaitTime * 365.0))
    }

    rule("@sec-multiechelon-metric and @sec-multiechelon-varimetric, the same plan under both models")
    for (metric in listOf(true, false)) {
        val x = utility()
        x.forcePoissonAtBases = metric
        val b = x.baseItems[0]
        println("  %-12s mu_j %8.4f  var %8.4f  Bbar_j %8.4f  Ibar_j %8.4f  system %8.4f".format(
            if (metric) "METRIC" else "VARI-METRIC",
            b.expectedNumInResupply, b.leadTimeDemand.variance(),
            b.expectedBackOrders, b.expectedOnHand, x.totalBaseExpectedBackOrders))
    }

    rule("@exm-worksheet-contract, the repair contract")
    println("  at 60 days: delay %5.2f d, system backorders %.4f".format(
        utility(60.0).expectedWaitTime * 365.0, utility(60.0).totalBaseExpectedBackOrders))
    println("  at 45 days: delay %5.2f d, system backorders %.4f".format(
        utility(45.0).expectedWaitTime * 365.0, utility(45.0).totalBaseExpectedBackOrders))
    val target = utility(45.0).totalBaseExpectedBackOrders
    for (s0 in 20..22) {
        val e = utility(60.0, depotStock = s0).totalBaseExpectedBackOrders
        println("  60 days, S_0 = %2d: system backorders %.4f%s".format(
            s0, e, if (e <= target) "   <= the contract" else ""))
    }

    rule("@sec-multiechelon-allocation, the allocation curve and where it is not convex")
    val blank = utility(depotStock = 0, baseStock = 0)
    val curve = MAFItemData(MAFItemData.createDepotLevels(0, 26, 1), 0.01, 40, blank)
    val kept = curve.scValues.take(curve.totalNumberOfConvexPoints).toSet()
    println("  %5s %10s %6s %10s %8s".format("total", "EBO", "S_0", "reduction", "kept?"))
    for (t in 10..21) {
        println("  %5d %10.4f %6d %10s %8s".format(
            t, curve.alphaHat[t], curve.dStar[t],
            "%.4f".format(curve.alphaHat[t - 1] - curve.alphaHat[t]),
            if (t in kept) "" else "dropped"))
    }

    rule("@sec-multiechelon-allocation-items, two items merged against a budget")
    val model = VariMetricModel()
    val tr = model.addItem(1, 0, 3800.0, days(60.0))
    repeat(4) { tr.addBaseItem(45.0, days(30.0), 0.3, days(10.0), 0, 3800.0) }
    val rg = model.addItem(2, 0, 9500.0, days(90.0))
    repeat(4) { rg.addBaseItem(12.0, days(45.0), 0.2, days(10.0), 0, 9500.0) }
    val alg = MarginalAnalysisAlgorithm(
        model, 250_000.0, 0.01,
        intArrayOf(0, 0), intArrayOf(26, 20), intArrayOf(1, 1), intArrayOf(40, 30))
    alg.optimize()
    println("  budget %,.0f  spent %,.0f".format(250_000.0, alg.totalCost))
    println("  storeroom backorders across both items %.4f".format(alg.totalExpectedBackOrders))
    for (vi in model.items) {
        println("  item %d: depot %d, storerooms %s".format(
            vi.itemNumber, vi.stockLevel, vi.baseItems.map { it.stockLevel }))
    }

    rule("@sec-multiechelon-design-lagrange, the same budget by Lagrangian relaxation")
    for (name in listOf("enumeration", "bisection")) {
        val m2 = VariMetricModel()
        val t2 = m2.addItem(1, 0, 3800.0, days(60.0))
        repeat(4) { t2.addBaseItem(45.0, days(30.0), 0.3, days(10.0), 0, 3800.0) }
        val r2 = m2.addItem(2, 0, 9500.0, days(90.0))
        repeat(4) { r2.addBaseItem(12.0, days(45.0), 0.2, days(10.0), 0, 9500.0) }
        val alg: LagrangeAlgorithm =
            if (name == "enumeration") LagrangeEnumerationAlgorithm(m2, 250_000.0, 200, 1.0e-6, 1.0e-4, 1.0e-9)
            else LagrangeIterativeAlgorithm(m2, 250_000.0, 100, 1.0e-9, 1.0e-6, 1.0e-4, 1.0e-9)
        val feasible = alg.optimize()
        println("  %-12s theta %.6g  spent %,.0f  storeroom backorders %.4f  within budget %s".format(
            name, alg.finalTheta, m2.totalStockingCost, m2.totalBaseExpectedBackOrders, feasible))
        for (vi in m2.items) {
            println("      item %d: depot %d, storerooms %s".format(
                vi.itemNumber, vi.stockLevel, vi.baseItems.map { it.stockLevel }))
        }
    }
}
