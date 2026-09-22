package inventory.multiitem

import inventory.multiitem.network.DistributionNetwork
import inventory.multiitem.network.SerialChain

/**
 * Generates every figure printed in @sec-multiitem-design of the book.
 *
 * The section shows how the design of @sec-multiitem-design is used to solve the chapter's own
 * examples, so its numbers are produced by running the code rather than typed.
 * Regenerate with `scripts/section-4-8-listings.sh` after any change that could move
 * one.
 */
fun main() {
    fun rule(title: String) { println(); println("### $title") }

    // ---- S1 and S2, @exm-budget --------------------------------------------
    rule("S1 and S2, an investment limit on three SKUs")
    val skus = listOf(
        SKU.pricedAt("Item 1", demandRate = 12000.0, unitValue = 20.0,
            orderCost = 50.0, carryingCharge = 0.30),
        SKU.pricedAt("Item 2", demandRate = 25000.0, unitValue = 10.0,
            orderCost = 50.0, carryingCharge = 0.30),
        SKU.pricedAt("Item 3", demandRate = 8000.0, unitValue = 15.0,
            orderCost = 50.0, carryingCharge = 0.30),
    )
    val storeroom = Portfolio(skus)
    val budget = Constraint(AverageInvestment, limit = 10000.0)
    val study = ConstrainedLotSizing(storeroom, budget)

    println("  binds                 ${study.binds}")
    val free = study.freePlan
    val held = study.solve()
    println("  free investment       %10.4f".format(free.measuredBy(AverageInvestment)))
    println("  free relevant cost    %10.4f".format(free.measuredBy(RelevantCost)))
    println("  shadow price          %10.6f".format(held.shadowPrice))
    skus.forEach {
        println("  %-8s free %9.4f   held %9.4f   ratio %.6f".format(
            it.label, free.quantityOf(it), held.quantityOf(it),
            held.quantityOf(it) / free.quantityOf(it)))
    }
    println("  held investment       %10.4f".format(held.measuredBy(AverageInvestment)))
    println("  held relevant cost    %10.4f".format(held.measuredBy(RelevantCost)))
    val price = study.priceOfTheConstraint()
    println("  the limit cost        %10.4f  (%.4f%%)".format(price.difference,
        price.relativeError * 100.0))

    // @exm-frequency: the same demands and values, but the ordering costs differ and the
    // limit falls on the receiving dock. Only the measure and the ceiling change.
    rule("the same distributor under a limit on replenishments")
    val dockSkus = listOf(
        SKU.pricedAt("Item 1", 12000.0, 20.0, orderCost = 50.0, carryingCharge = 0.30),
        SKU.pricedAt("Item 2", 25000.0, 10.0, orderCost = 20.0, carryingCharge = 0.30),
        SKU.pricedAt("Item 3", 8000.0, 15.0, orderCost = 80.0, carryingCharge = 0.30),
    )
    val dock = ConstrainedLotSizing(Portfolio(dockSkus),
        Constraint(ReplenishmentWorkload, limit = 50.0))
    val dockFree = dock.freePlan
    val rationed = dock.solve()
    println("  free orders per year  %10.4f".format(dockFree.measuredBy(ReplenishmentWorkload)))
    println("  free relevant cost    %10.4f".format(dockFree.measuredBy(RelevantCost)))
    println("  shadow price          %10.4f per order".format(rationed.shadowPrice))
    dockSkus.forEach {
        println("  %-8s k+theta %8.4f   held %9.4f   ratio %.4f".format(
            it.label, it.orderCost + rationed.shadowPrice, rationed.quantityOf(it),
            rationed.quantityOf(it) / dockFree.quantityOf(it)))
    }
    println("  orders per year       %10.4f".format(rationed.measuredBy(ReplenishmentWorkload)))
    println("  relevant cost         %10.4f".format(rationed.measuredBy(RelevantCost)))
    val dockPrice = dock.priceOfTheConstraint()
    println("  the limit cost        %10.4f  (%.4f%%)".format(dockPrice.difference,
        dockPrice.relativeError * 100.0))

    rule("the closed form, where it applies")
    val scaled = ConstrainedLotSizing(storeroom, budget, ProportionalScaling(carryingCharge = 0.30))
    println("  search                %10.8f".format(study.solve().shadowPrice))
    println("  closed form           %10.8f".format(scaled.solve().shadowPrice))

    // ---- S3, @exm-storeroom ---------------------------------------------------
    rule("S3, the utility storeroom exchange curve")
    val utility = Portfolio(listOf(
        Triple("Pad-mount transformer", 45.0, 3800.0) to 4.0,
        Triple("Smart meter", 1200.0, 95.0) to 2.0,
        Triple("Fuse cutout", 800.0, 115.0) to 1.5,
        Triple("Meter socket", 900.0, 48.0) to 1.5,
        Triple("Riser conduit, 4 in", 600.0, 62.0) to 1.5,
        Triple("Splice kit", 1500.0, 28.0) to 1.0,
        Triple("Ground rod", 2400.0, 14.0) to 0.75,
        Triple("Compression connector", 6000.0, 3.40) to 0.5,
        Triple("Warning tape, roll", 1000.0, 2.10) to 0.5,
    ).map { (who, hours) ->
        SKU.pricedAt(who.first, who.second, who.third, 55.0 * hours, 0.25)
    })
    val curve = ExchangeCurve(utility)
    println("  curve constant        %14.2f".format(curve.constant))
    println("  decomposed            %14.2f".format(curve.decomposedConstant))
    println("  variety index         %14.4f".format(curve.varietyIndex))
    println("  weighted order cost   %14.2f".format(curve.weightedOrderingCost))
    for (p in doubleArrayOf(0.10, 0.25, 0.40)) {
        println("  price %.2f   investment %11.2f   ordering %9.2f   product %14.2f".format(
            p, curve.investmentAt(p), curve.orderingCostAt(p),
            curve.investmentAt(p) * curve.orderingCostAt(p)))
    }

    // ---- S4, @exm-joint ---------------------------------------------------
    rule("S4, four SKUs from one supplier")
    val family = OrderFamily(Portfolio(listOf(
        SKU.pricedAt("Drive unit", 8000.0, 20.0, 25.0, 0.25),
        SKU.pricedAt("Gearbox", 800.0, 20.0, 20.0, 0.25),
        SKU.pricedAt("Seal kit", 200.0, 8.0, 15.0, 0.25),
        SKU.pricedAt("Name plate", 40.0, 4.0, 15.0, 0.25),
    )), majorSetupCost = 400.0)

    println("  independent           %10.2f".format(family.independentCost))
    val common = CommonCycle(family.commonCycleInterval)
    println("  common cycle          %10.2f   T = %.5f year (%.1f days)".format(
        family.costOf(common), common.basePeriod, common.basePeriod * 365.0))
    for ((label, ruleAt) in listOf<Pair<String, (Double) -> IntervalRule>>(
        "integer multiple" to { t -> IntegerMultiple(t) },
        "power of two" to { t -> PowerOfTwoMultiple(t) })) {
        val t = family.bestBasePeriod(ruleAt = ruleAt)
        val r = ruleAt(t)
        val ms = family.portfolio.skus.map { r.multiplierFor(it.preferredInterval) }
        println("  %-16s      %10.2f   T = %.5f year (%.3f days)   m = %s".format(
            label, family.costOf(r), t, t * 365.0, ms))
    }
    val best2 = PowerOfTwoMultiple(family.bestBasePeriod { PowerOfTwoMultiple(it) })
    val bestInt = IntegerMultiple(family.bestBasePeriod { IntegerMultiple(it) })
    println("  nesting costs         %10.4f%%".format(
        (family.costOf(best2) / family.costOf(bestInt) - 1.0) * 100.0))
    println("  the schedule nests    ${family.scheduleUnder(best2).nestsOn(best2.basePeriod)}")

    // ---- S5, @exm-serial and @exm-distribution ------------------------------------------
    rule("S5, a five stage chain")
    val chain = SerialChain(listOf(
        SKU("Stage 1", 500.0, 10.0, 0.60),
        SKU("Stage 2", 500.0, 12.0, 0.50),
        SKU("Stage 3", 500.0, 25.0, 0.35),
        SKU("Stage 4", 500.0, 7.0, 0.10),
        SKU("Stage 5", 500.0, 2.0, 0.05),
    ))
    chain.skus.forEach {
        println("  %-8s h %.2f   h' %.2f   alone %.4f year".format(
            it.label, it.holdingRate, chain.echelonRate(it), chain.unconstrainedInterval(it)))
    }
    chain.blocks().forEach {
        println("  block %-22s interval %.4f year".format(
            it.skus.joinToString(", ") { s -> s.label }, it.interval))
    }
    val weekly = PowerOfTwoMultiple(1.0 / 52.0)
    println("  relaxed cost          %10.2f".format(chain.costOf(chain.relaxedIntervals())))
    println("  rounded cost          %10.2f   m = %s".format(
        chain.costOf(chain.plan(weekly)),
        chain.relaxedIntervals().map { weekly.multiplierFor(it) }))

    rule("S5, a central warehouse and six regions")
    val network = DistributionNetwork(
        SKU("Warehouse", 1200.0, 500.0, 10.0),
        listOf(
            SKU("Region 1", 200.0, 100.0, 15.0),
            SKU("Region 2", 200.0, 125.0, 12.0),
            SKU("Region 3", 200.0, 110.0, 20.0),
            SKU("Region 4", 200.0, 150.0, 18.0),
            SKU("Region 5", 200.0, 75.0, 20.0),
            SKU("Region 6", 200.0, 90.0, 17.0),
        ))
    println("  ranked                ${network.ranked().map { it.label }}")
    println("  pinned to the centre  ${network.pinnedSet().map { it.label }}")
    println("  central interval      %10.6f year".format(network.centralInterval()))
    network.skus.zip(network.plan(weekly)).forEach { (sku, interval) ->
        println("  %-10s relaxed %.6f   runnable %.6f   m = %d".format(
            sku.label, network.unconstrainedInterval(sku), interval,
            weekly.multiplierFor(network.relaxedIntervals()[network.skus.indexOf(sku)])))
    }
}
