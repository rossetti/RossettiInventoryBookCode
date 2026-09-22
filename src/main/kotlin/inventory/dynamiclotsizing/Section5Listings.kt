package inventory.dynamiclotsizing

/**
 * Generates every figure printed in Chapter 5.
 *
 * The chapter promises parity with the workbook, so its numbers are produced by running
 * the code rather than typed. Regenerate with `scripts/chapter5-listings.sh` after any
 * change that could move one.
 */
fun main() {
    fun rule(title: String) { println(); println("### $title") }

    val belt = RequirementsSchedule.constantCosts(
        label = "Mower deck belt",
        requirements = listOf(40.0, 60.0, 120.0, 300.0, 420.0, 260.0,
                              120.0, 40.0, 0.0, 80.0, 180.0, 220.0),
        orderCost = 300.0, unitCost = 50.0, carryingCharge = 0.02,
    )
    val quarter = RequirementsSchedule.constantCosts(
        label = "Mower deck belt, first half year",
        requirements = listOf(40.0, 60.0, 120.0, 300.0, 420.0, 260.0),
        orderCost = 300.0, unitCost = 50.0, carryingCharge = 0.02,
    )

    rule("5.1 the item")
    println("  periods              %d".format(belt.horizon))
    println("  total requirement    %.0f belts".format(belt.periods.sumOf { belt.requirementIn(it) }))
    println("  average requirement  %.4f belts per month".format(belt.averageRequirement))
    println("  holding rate         %.2f per belt per month".format(belt.holdingRateIn(1)))
    println("  k / h                %.0f belt-months".format(belt.orderCostIn(1) / belt.holdingRateIn(1)))
    println("  variability coeff.   %.4f".format(belt.variabilityCoefficient))
    println("  forced order period  %s".format(belt.forcedOrderPeriod()))

    rule("5.3 the relevant window costs of the six-period instance")
    print("        "); (1..6).forEach { print("%8d".format(it)) }; println()
    for (t in 1..6) {
        print("   t=%d ".format(t))
        for (u in 1..6) print(if (u >= t) "%8.0f".format(quarter.relevantWindowCost(t, u)) else "       .")
        println()
    }

    rule("5.4 the recursion on the six-period instance")
    val small = WagnerWhitin.solve(quarter)
    println("  V(t) relevant " + (0..6).joinToString(" ") { "%6.0f".format(small.valueAt(it) - 50.0 * (1..it).sumOf { p -> quarter.requirementIn(p) }) })
    println("  S(t) " + (1..6).joinToString(" ") { "%6d".format(small.orderedFromAt(it)) })
    val smallPlan = WagnerWhitin.plan(quarter)
    println("  optimum %.2f, orders %s".format(smallPlan.relevantCost,
        smallPlan.orderPeriods.map { it to quarter.requirementOver(it, lastCovered(smallPlan, it)) }))

    rule("5.4 the recursion on the twelve-period instance")
    val full = WagnerWhitin.solve(belt)
    println("  V(t) relevant " + (0..12).joinToString(" ") { "%6.0f".format(full.valueAt(it) - 50.0 * (1..it).sumOf { p -> belt.requirementIn(p) }) })
    println("  S(t) " + (1..12).joinToString(" ") { "%6d".format(full.orderedFromAt(it)) })

    rule("5.2 and 5.5 and 5.6 every method on the twelve-period instance")
    val optimum = WagnerWhitin.plan(belt)
    val rules = listOf(
        LotForLot, AdjustedEconomicOrderQuantity(), PeriodicOrderQuantity(),
        LeastUnitCost, PartPeriodBalancing, SilverMeal, WagnerWhitin,
    )
    println("  %-32s %6s %9s %9s %9s %9s".format("rule", "orders", "setup", "carrying", "total", "penalty"))
    for (r in rules) {
        val p = r.plan(belt)
        println("  %-32s %6d %9.0f %9.0f %9.0f %8.2f%%".format(
            r.name, p.orderCount, p.setupCost, p.carryingCost, p.relevantCost,
            p.penaltyAgainst(optimum).relativeError * 100.0))
    }
    println("  POQ time supply      %d months".format(PeriodicOrderQuantity().timeSupplyFor(belt)))

    rule("5.5 the plans the rules choose")
    for (r in listOf(SilverMeal, WagnerWhitin)) {
        val p = r.plan(belt)
        println("  %-14s %s".format(r.name,
            p.orderPeriods.map { it to belt.requirementOver(it, lastCovered(p, it)).toInt() }))
    }

    rule("5.7 the rolling horizon on the belt, one month frozen")
    println("  %-8s %12s %12s".format("window", "Silver-Meal", "Wagner-Whitin"))
    for (windowLength in 2..6) {
        val sm = RollingHorizon(belt, windowLength, freeze = 1).realizedPlan(SilverMeal)
        val ww = RollingHorizon(belt, windowLength, freeze = 1).realizedPlan(WagnerWhitin)
        println("  %-8d %12.0f %12.0f".format(windowLength, sm.relevantCost, ww.relevantCost))
    }
    println("  the full-horizon optimum is %.0f".format(optimum.relevantCost))

    rule("5.7 what the planner commits to in month 1, by window length")
    println("  %-8s %14s %14s".format("window", "Silver-Meal", "Wagner-Whitin"))
    for (windowLength in 2..6) {
        val sm = RollingHorizon(belt, windowLength, freeze = 1).realizedPlan(SilverMeal)
        val ww = RollingHorizon(belt, windowLength, freeze = 1).realizedPlan(WagnerWhitin)
        println("  %-8d %14.0f %14.0f".format(windowLength, sm.orderIn(1), ww.orderIn(1)))
    }

    rule("5.7 how often the heuristic wins, over 2000 random schedules")
    val trials = randomSchedules(count = 2000, periods = 12, seed = 20240501L)
    for (windowLength in listOf(3, 4, 6)) {
        var heuristicWins = 0
        var algorithmWins = 0
        var smTotal = 0.0
        var wwTotal = 0.0
        for (trial in trials) {
            val sm = RollingHorizon(trial, windowLength, freeze = 1).realizedPlan(SilverMeal).relevantCost
            val ww = RollingHorizon(trial, windowLength, freeze = 1).realizedPlan(WagnerWhitin).relevantCost
            smTotal += sm
            wwTotal += ww
            if (sm < ww - 1.0E-6) heuristicWins++
            if (ww < sm - 1.0E-6) algorithmWins++
        }
        println("  window %d: Silver-Meal cheaper in %4d, dearer in %4d, tied in %4d; mean cost %.0f against %.0f"
            .format(windowLength, heuristicWins, algorithmWins,
                trials.size - heuristicWins - algorithmWins, smTotal / trials.size, wwTotal / trials.size))
    }

    rule("5.2 the period-varying instance of the exercises")
    val varying = RequirementsSchedule(
        label = "Period-varying costs",
        requirements = listOf(10.0, 2.0, 12.0, 4.0, 14.0),
        orderCosts = List(5) { 40.0 },
        unitCosts = List(5) { 2.0 },
        holdingRates = List(5) { 1.0 },
    )
    val v = WagnerWhitin.solve(varying)
    val vp = WagnerWhitin.plan(varying)
    println("  V(t) " + (0..5).joinToString(" ") { "%6.0f".format(v.valueAt(it)) })
    println("  S(t) " + (1..5).joinToString(" ") { "%6d".format(v.orderedFromAt(it)) })
    println("  relevant %.0f, purchase %.0f, total %.0f, orders %s".format(
        vp.relevantCost, vp.purchaseCost, vp.totalCost, vp.orderPeriods))
}

/**
 * Schedules drawn from a fixed seed, so the experiment of @sec-dls-rolling reproduces. The
 * requirements are drawn from a spread wide enough to make the variability coefficient
 * exceed the threshold of @sec-dls-performance most of the time.
 */
private fun randomSchedules(count: Int, periods: Int, seed: Long): List<RequirementsSchedule> {
    val draw = java.util.Random(seed)
    val choices = listOf(0.0, 10.0, 20.0, 40.0, 80.0, 120.0, 200.0, 300.0)
    return (1..count).map { n ->
        var requirements: List<Double>
        do {
            requirements = (1..periods).map { choices[draw.nextInt(choices.size)] }
        } while (requirements.first() <= 0.0 || requirements.sum() <= 0.0)
        RequirementsSchedule.constantCosts(
            label = "Trial $n", requirements = requirements,
            orderCost = 200.0, unitCost = 50.0, carryingCharge = 0.02,
        )
    }
}

/** The last period an order placed in [order] covers under [plan]. */
private fun lastCovered(plan: LotSizingPlan, order: Int): Int {
    val next = plan.orderPeriods.firstOrNull { it > order }
    return (next ?: (plan.schedule.horizon + 1)) - 1
}
