package inventory.multiitem

import inventory.multiitem.network.DistributionNetwork
import inventory.multiitem.network.SerialChain
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every number Chapter 4 prints, recomputed against the redesigned package. */
class ChapterExamplesTest {

    // ---- @exm-budget, an investment limit on three SKUs --------------------

    private val budgetSkus = listOf(
        SKU.pricedAt("Item 1", 12000.0, 20.0, 50.0, 0.30),
        SKU.pricedAt("Item 2", 25000.0, 10.0, 50.0, 0.30),
        SKU.pricedAt("Item 3", 8000.0, 15.0, 50.0, 0.30),
    )
    private fun budgetPortfolio() = Portfolio(budgetSkus)
    private fun budgetLimit() = Constraint(AverageInvestment, 10000.0)

    @Test
    fun `unconstrained quantities and investment`() {
        val free = budgetPortfolio().freePlan()
        assertEquals(447.2136, free.orderQuantities[0], 1e-4)
        assertEquals(912.8709, free.orderQuantities[1], 1e-4)
        assertEquals(421.6370, free.orderQuantities[2], 1e-4)
        assertEquals(12198.7683, free.measuredBy(AverageInvestment), 1e-3)
        assertEquals(7319.2610, free.measuredBy(RelevantCost), 1e-3)
    }

    @Test
    fun `the budget limit and its price`() {
        val study = ConstrainedLotSizing(budgetPortfolio(), budgetLimit())
        assertTrue(study.binds)
        val held = study.solve()
        assertEquals(0.146430, held.shadowPrice, 1e-6)
        assertEquals(366.6055, held.orderQuantities[0], 1e-4)
        assertEquals(748.3304, held.orderQuantities[1], 1e-4)
        assertEquals(345.6390, held.orderQuantities[2], 1e-4)
        assertEquals(10000.0, held.measuredBy(AverageInvestment), 1e-5)
        assertEquals(7464.2984, held.measuredBy(RelevantCost), 1e-3)
        val price = study.priceOfTheConstraint()
        assertEquals(145.0375, price.difference, 1e-3)
        assertEquals(0.019816, price.relativeError, 1e-6)
    }

    @Test
    fun `holding cost equals the carrying charge on the limit`() {
        val held = ConstrainedLotSizing(budgetPortfolio(), budgetLimit()).solve()
        val holding = budgetSkus.sumOf { it.holdingRate * held.quantityOf(it) / 2.0 }
        assertEquals(0.30 * 10000.0, holding, 1e-5)
    }

    @Test
    fun `a budget rescales every quantity by the same factor`() {
        val study = ConstrainedLotSizing(budgetPortfolio(), budgetLimit())
        val ratios = study.quantityRatios()
        ratios.forEach { assertEquals(ratios.first(), it, 1e-9) }
        assertEquals(sqrt(0.30 / (0.30 + 0.146430)), ratios.first(), 1e-6)
    }

    @Test
    fun `a space limit does not rescale`() {
        val volumes = mapOf(budgetSkus[0] to 0.8, budgetSkus[1] to 0.3, budgetSkus[2] to 1.2)
        val study = ConstrainedLotSizing(budgetPortfolio(), Constraint(SpaceOccupied(volumes), 800.0))
        assertTrue(study.binds)
        assertEquals(2.953823, study.solve().shadowPrice, 1e-5)
        assertEquals(7723.58, study.solve().measuredBy(RelevantCost), 1e-2)
        val ratios = study.quantityRatios()
        assertTrue(abs(ratios[0] - ratios[1]) > 1e-3, "a space limit reallocates, it was $ratios")
    }

    @Test
    fun `the closed form and the search agree`() {
        val searched = ConstrainedLotSizing(budgetPortfolio(), budgetLimit(), LagrangianSearch())
        val scaled = ConstrainedLotSizing(budgetPortfolio(), budgetLimit(), ProportionalScaling(0.30))
        assertEquals(searched.solve().shadowPrice, scaled.solve().shadowPrice, 1e-6)
        assertEquals(searched.solve().measuredBy(RelevantCost),
            scaled.solve().measuredBy(RelevantCost), 1e-6)
    }

    @Test
    fun `a limit that does not bind leaves the free plan alone`() {
        val study = ConstrainedLotSizing(budgetPortfolio(), Constraint(AverageInvestment, 50000.0))
        assertTrue(!study.binds)
        assertEquals(0.0, study.solve().shadowPrice, 0.0)
        assertEquals(7319.2610, study.solve().measuredBy(RelevantCost), 1e-3)
    }

    // ---- @exm-frequency, a limit on replenishments ----------------------------

    /** The same demands and values as @exm-budget, but the ordering costs differ. */
    private val dockSkus = listOf(
        SKU.pricedAt("Item 1", 12000.0, 20.0, 50.0, 0.30),
        SKU.pricedAt("Item 2", 25000.0, 10.0, 20.0, 0.30),
        SKU.pricedAt("Item 3", 8000.0, 15.0, 80.0, 0.30),
    )

    @Test
    fun `the dock limit and its price`() {
        val study = ConstrainedLotSizing(Portfolio(dockSkus),
            Constraint(ReplenishmentWorkload, 50.0))
        assertTrue(study.binds)
        assertEquals(85.1341, study.freePlan.measuredBy(ReplenishmentWorkload), 1e-4)
        assertEquals(6815.3324, study.freePlan.measuredBy(RelevantCost), 1e-3)

        val held = study.solve()
        assertEquals(64.3293, held.shadowPrice, 1e-4)
        assertEquals(676.2523, held.orderQuantities[0], 1e-4)
        assertEquals(1185.5328, held.orderQuantities[1], 1e-4)
        assertEquals(716.3594, held.orderQuantities[2], 1e-4)
        assertEquals(50.0, held.measuredBy(ReplenishmentWorkload), 1e-6)
        assertEquals(7621.2652, held.measuredBy(RelevantCost), 1e-3)

        val price = study.priceOfTheConstraint()
        assertEquals(805.9328, price.difference, 1e-3)
        assertEquals(0.118253, price.relativeError, 1e-6)
    }

    @Test
    fun `a limit on replenishments reallocates rather than rescaling`() {
        val ratios = ConstrainedLotSizing(Portfolio(dockSkus),
            Constraint(ReplenishmentWorkload, 50.0)).quantityRatios()
        assertEquals(1.5121, ratios[0], 1e-4)
        assertEquals(2.0534, ratios[1], 1e-4)
        assertEquals(1.3432, ratios[2], 1e-4)
        assertTrue(abs(ratios[1] - ratios[2]) > 0.5,
            "a constant added to unequal ordering costs changes them unequally")
    }

    // ---- @sec-multiitem-constrained-frequency, the effective ordering cost -------------------------

    @Test
    fun `a workload limit adds the price to the ordering cost`() {
        val study = ConstrainedLotSizing(budgetPortfolio(), Constraint(ReplenishmentWorkload, 60.0))
        assertTrue(study.binds)
        val held = study.solve()
        assertEquals(60.0, held.measuredBy(ReplenishmentWorkload), 1e-6)
        val price = held.shadowPrice
        budgetSkus.forEach {
            val expected = sqrt(2.0 * (it.orderCost + price) * it.demandRate / it.holdingRate)
            assertEquals(expected, held.quantityOf(it), 1e-6)
        }
    }

    // ---- @exm-storeroom, the storeroom exchange curve --------------------------

    private fun storeroom(): Portfolio {
        val kappa = 55.0
        val gamma = 0.25
        val data = listOf(
            Triple("Pad-mount transformer", 45.0, 3800.0) to 4.0,
            Triple("Smart meter", 1200.0, 95.0) to 2.0,
            Triple("Fuse cutout", 800.0, 115.0) to 1.5,
            Triple("Meter socket", 900.0, 48.0) to 1.5,
            Triple("Riser conduit, 4 in", 600.0, 62.0) to 1.5,
            Triple("Splice kit", 1500.0, 28.0) to 1.0,
            Triple("Ground rod", 2400.0, 14.0) to 0.75,
            Triple("Compression connector", 6000.0, 3.40) to 0.5,
            Triple("Warning tape, roll", 1000.0, 2.10) to 0.5,
        )
        return Portfolio(data.map { (who, hours) ->
            SKU.pricedAt(who.first, who.second, who.third, kappa * hours, gamma)
        })
    }

    @Test
    fun `curve constant variety index and decomposition`() {
        val curve = ExchangeCurve(storeroom())
        assertEquals(19755.87, curve.rootSum, 1e-2)
        assertEquals(1.951e8, curve.constant, 1e5)
        assertEquals(5.6813, curve.varietyIndex, 1e-4)
        assertEquals(14445.0, curve.aggregateDemandRate, 1e-6)
        assertEquals(555500.0, curve.aggregatePurchaseCost, 1e-6)
        assertEquals(38.46, curve.weightedUnitCost, 1e-2)
        assertEquals(123.67, curve.weightedOrderingCost, 1e-2)
        assertEquals(curve.constant, curve.decomposedConstant, 1e-3)
    }

    @Test
    fun `the operating point at the firms own carrying charge`() {
        val curve = ExchangeCurve(storeroom())
        assertEquals(27939.0, curve.investmentAt(0.25), 1.0)
        assertEquals(6985.0, curve.orderingCostAt(0.25), 1.0)
        assertEquals(4.0, curve.investmentAt(0.25) / curve.orderingCostAt(0.25), 1e-6)
    }

    @Test
    fun `the product is the same everywhere on the curve`() {
        val curve = ExchangeCurve(storeroom())
        for (price in doubleArrayOf(0.05, 0.10, 0.25, 0.50, 1.00)) {
            assertEquals(curve.constant, curve.investmentAt(price) * curve.orderingCostAt(price),
                curve.constant * 1e-9)
        }
    }

    // ---- @exm-joint, four SKUs from one supplier ---------------------------

    private fun supplier() = OrderFamily(Portfolio(listOf(
        SKU.pricedAt("Drive unit", 8000.0, 20.0, 25.0, 0.25),
        SKU.pricedAt("Gearbox", 800.0, 20.0, 20.0, 0.25),
        SKU.pricedAt("Seal kit", 200.0, 8.0, 15.0, 0.25),
        SKU.pricedAt("Name plate", 40.0, 4.0, 15.0, 0.25),
    )), majorSetupCost = 400.0)

    @Test
    fun `independent common cycle integer ratio and powers of two`() {
        val family = supplier()
        assertEquals(8422.39, family.independentCost, 1e-2)

        assertEquals(0.14621, family.commonCycleInterval, 1e-5)
        assertEquals(6497.54, family.costOf(CommonCycle(family.commonCycleInterval)), 1e-2)

        val tInt = family.bestBasePeriod { IntegerMultiple(it) }
        val integerRule = IntegerMultiple(tInt)
        assertEquals(6402.06, family.costOf(integerRule), 0.05)
        assertEquals(listOf(1, 1, 2, 6),
            family.portfolio.skus.map { integerRule.multiplierFor(it.preferredInterval) })

        val tP2 = family.bestBasePeriod { PowerOfTwoMultiple(it) }
        val pow2Rule = PowerOfTwoMultiple(tP2)
        assertEquals(6403.34, family.costOf(pow2Rule), 0.05)
        assertEquals(listOf(1, 1, 2, 8),
            family.portfolio.skus.map { pow2Rule.multiplierFor(it.preferredInterval) })
        assertTrue(family.scheduleUnder(pow2Rule).nestsOn(tP2))

        val excess = family.costOf(pow2Rule) / family.costOf(integerRule) - 1.0
        assertTrue(abs(excess - 0.00020) < 5e-5, "power-of-two excess was $excess")
    }

    // ---- @exm-serial, the five stage chain ----------------------------------

    private fun chain() = SerialChain(listOf(
        SKU("1", 500.0, 10.0, 0.60),
        SKU("2", 500.0, 12.0, 0.50),
        SKU("3", 500.0, 25.0, 0.35),
        SKU("4", 500.0, 7.0, 0.10),
        SKU("5", 500.0, 2.0, 0.05),
    ))

    @Test
    fun `echelon rates blocks and rounded intervals`() {
        val chain = chain()
        assertEquals(listOf(0.10, 0.15, 0.25, 0.05, 0.05),
            chain.skus.map { Math.round(chain.echelonRate(it) * 100) / 100.0 })
        listOf(25.0, 37.5, 62.5, 12.5, 12.5).zip(chain.skus).forEach { (want, sku) ->
            assertEquals(want, chain.holdingCoefficient(sku), 1e-9)
        }

        val blocks = chain.blocks()
        assertEquals(2, blocks.size)
        assertEquals(listOf("1", "2"), blocks[0].skus.map { it.label })
        assertEquals(listOf("3", "4", "5"), blocks[1].skus.map { it.label })
        assertEquals(0.5933, blocks[0].interval, 1e-4)
        assertEquals(0.6234, blocks[1].interval, 1e-4)

        assertEquals(183.25, chain.costOf(chain.relaxedIntervals()), 1e-2)
        val rule = PowerOfTwoMultiple(1.0 / 52.0)
        assertEquals(List(5) { 32 }, chain.relaxedIntervals().map { rule.multiplierFor(it) })
        assertEquals(183.31, chain.costOf(chain.plan(rule)), 1e-2)
    }

    @Test
    fun `the stage by stage intervals are infeasible`() {
        val chain = chain()
        val alone = chain.skus.map { chain.unconstrainedInterval(it) }
        assertTrue(alone.zipWithNext().any { (a, b) -> b < a },
            "the chapter says these are not nondecreasing, but they were $alone")
    }

    // ---- @exm-distribution, a central warehouse and six regions --------------------

    private fun warehouse() = DistributionNetwork(
        SKU("0", 1200.0, 500.0, 10.0),
        listOf(
            SKU("1", 200.0, 100.0, 15.0),
            SKU("2", 200.0, 125.0, 12.0),
            SKU("3", 200.0, 110.0, 20.0),
            SKU("4", 200.0, 150.0, 18.0),
            SKU("5", 200.0, 75.0, 20.0),
            SKU("6", 200.0, 90.0, 17.0),
        ))

    @Test
    fun `ranking pinned set central interval and rounding`() {
        val network = warehouse()
        assertEquals(listOf("5", "3", "6", "4", "1", "2"), network.ranked().map { it.label })
        assertEquals(listOf("6", "4", "1", "2"), network.pinnedSet().map { it.label })
        assertEquals(0.34304945, network.centralInterval(), 1e-8)

        val rule = PowerOfTwoMultiple(1.0 / 52.0)
        assertEquals(List(7) { 16 }, network.relaxedIntervals().map { rule.multiplierFor(it) })
    }

    @Test
    fun `the regions left alone keep their own intervals`() {
        val network = warehouse()
        val pinned = network.pinnedSet().map { it.label }.toSet()
        assertTrue("5" !in pinned && "3" !in pinned)
        val relaxed = network.relaxedIntervals()
        assertEquals(0.273861, relaxed[network.skus.indexOfFirst { it.label == "5" }], 1e-6)
        assertEquals(0.331662, relaxed[network.skus.indexOfFirst { it.label == "3" }], 1e-6)
    }
}
