package inventory.continuousreview

/**
 * Generates every number Chapter 8 prints for the storeroom's items.
 *
 * Run it with
 *
 * ```
 *   ./gradlew run -PmainClass=inventory.continuousreview.Section8ListingsKt
 * ```
 *
 * and compare the output against the chapter. ChapterExamplesTest asserts the
 * same values, so a disagreement fails the build rather than waiting to be
 * noticed here.
 */
fun main() {
    fun rule(title: String) { println(); println("### $title") }

    val transformer = RQModel(
        demandRate = 45.0,
        orderCost = 220.0,
        holdingCost = RQModel.holdingCostFrom(carryingCharge = 0.25, unitCost = 3800.0),
        backorderCost = 8550.0,
        leadTimeDemand = LeadTimeDemand.poisson(7.5),
    )

    rule("8.3 lead time demand for the transformer")
    println("  " + transformer.leadTimeDemand)
    println("  a two month lead time at 45 a year gives theta = %.1f units"
        .format(transformer.theta))

    rule("8.4 the base-stock cost column, @tbl-optimize-cs")
    println("  %-4s %-12s".format("s", "C(s)"))
    for (s in 8..16) println("  %-4d %-12.2f".format(s, transformer.baseStockCost(s)))
    println("  S* = %d, the smallest level whose distribution function reaches %.2f"
        .format(transformer.optimalBaseStock(), transformer.criticalRatio))

    rule("8.5 evaluation across reorder points at Q = 5, @tbl-rq-transformer")
    println("  %-4s %-10s %-10s %-10s %-11s %-11s %-11s %-11s"
        .format("r", "Bbar", "FR", "Ibar", "ordering", "holding", "backorder", "total"))
    for (r in 7..11) {
        val p = transformer.evaluate(RQPolicy(r, 5))
        println("  %-4d %-10.4f %-10.4f %-10.4f %-11.2f %-11.2f %-11.2f %-11.2f".format(
            r, p.expectedBackorders, p.fillRate, p.expectedOnHand,
            p.orderingCostRate, p.holdingCostRate, p.backorderCostRate, p.totalCost))
    }

    rule("8.9 what is known before the search runs")
    println("  Q_k = %.4f".format(transformer.deterministicReferenceQuantity()))
    println("  " + transformer.bounds())

    rule("8.10 the optimal policy, both ways")
    val exact = RQOptimizer(transformer).optimizeFromUnitBatch()
    val fast = RQOptimizer(transformer).optimize()
    println("  " + exact)
    println("  " + fast)

    rule("8.10.3 what the warm start and the jump search buy on a larger item")
    val items = listOf(
        "transformer" to transformer,
        "mid volume" to RQModel(3000.0, 150.0, 12.0, 90.0, LeadTimeDemand.poisson(230.77)),
        "high volume" to RQModel(12000.0, 900.0, 3.0, 25.0, LeadTimeDemand.poisson(1500.0)),
    )
    println("  %-14s %-8s %-8s %-14s %-14s %-12s"
        .format("item", "r*", "Q*", "unit batch", "warm start", "method"))
    for ((name, model) in items) {
        val a = RQOptimizer(model).optimizeFromUnitBatch()
        val b = RQOptimizer(model).optimize()
        check(a.policy == b.policy) { "$name: the two searches disagree" }
        println("  %-14s %-8d %-8d %-14d %-14d %-12s".format(
            name, a.reorderPoint, a.orderQuantity,
            a.windowEvaluations, b.windowEvaluations,
            b.method.name.lowercase().replace('_', ' ')))
    }

    rule("8.16 the transformer in code, at both lead times")
    for ((label, lead) in listOf("two months" to 2.0 / 12.0, "one month" to 1.0 / 12.0)) {
        val item = RQModel(
            demandRate = 45.0,
            orderCost = 220.0,
            holdingCost = RQModel.holdingCostFrom(carryingCharge = 0.25, unitCost = 3800.0),
            backorderCost = 8550.0,
            leadTimeDemand = LeadTimeDemand.matched(mean = 45.0 * lead, variance = 45.0 * lead),
        )
        val answer = RQOptimizer(item).optimize()
        println("  lead time $label, theta = %.2f".format(item.theta))
        println("    " + item.bounds())
        println("    " + answer)
        println(item.evaluate(answer.policy).toString().lines().joinToString("\n") { "    $it" })
    }

    rule("8.16 an item the worksheet cannot reach")
    val rod = RQModel(
        demandRate = 2400.0,
        orderCost = 41.25,
        holdingCost = RQModel.holdingCostFrom(carryingCharge = 0.25, unitCost = 14.0),
        backorderCost = 287.50,
        leadTimeDemand = LeadTimeDemand.matched(mean = 2400.0 / 26.0, variance = 2400.0 / 26.0),
    )
    val rodAnswer = RQOptimizer(rod).optimize()
    println("  ground rod, theta = %.2f".format(rod.theta))
    println("    " + rod.bounds())
    println("    " + rodAnswer)
    println("    Algorithm Optimize_rq needs %d steps from a batch of one"
        .format(RQOptimizer(rod).optimizeFromUnitBatch().orderQuantity - 1))

    rule("8.7.6 the backorder cost a ready rate target implies")
    for (target in listOf(0.90, 0.95, 0.98, 0.99)) {
        val model = RQModel.fromReadyRateTarget(
            demandRate = 45.0, orderCost = 220.0, holdingCost = 950.0,
            readyRateTarget = target, leadTimeDemand = LeadTimeDemand.poisson(7.5),
        )
        println("  a ready rate of %.2f implies b = %,.2f per unit per year"
            .format(target, model.backorderCost))
    }
}
