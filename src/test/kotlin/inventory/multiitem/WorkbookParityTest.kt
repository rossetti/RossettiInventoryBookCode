package inventory.multiitem

import inventory.multiitem.network.DistributionNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same instances the shipped workbook carries, against the figures its Check blocks
 * state.
 *
 * Reproducing the workbook rather than only the chapter's own examples is what caught a
 * real objective-function error in Chapter 3, where a ranking came out identical under
 * two different wrong objectives and only the workbook's instance distinguished them.
 */
class WorkbookParityTest {

    // ---- sheet: Constrained ------------------------------------------------

    private val constrainedSkus = listOf(
        SKU.pricedAt("Item 1", 12000.0, 20.0, 50.0, 0.30),
        SKU.pricedAt("Item 2", 25000.0, 10.0, 50.0, 0.30),
        SKU.pricedAt("Item 3", 8000.0, 15.0, 50.0, 0.30),
    )
    private val volumes = mapOf(
        constrainedSkus[0] to 0.8, constrainedSkus[1] to 0.3, constrainedSkus[2] to 1.2)

    @Test
    fun `Constrained sheet, both settings of the constraint type`() {
        val portfolio = Portfolio(constrainedSkus)
        assertEquals(7319.26, portfolio.freePlan().measuredBy(RelevantCost), 5e-3)

        val budget = ConstrainedLotSizing(portfolio, Constraint(AverageInvestment, 10000.0))
        assertEquals(0.146429841, budget.solve().shadowPrice, 1e-8)
        assertEquals(7464.30, budget.solve().measuredBy(RelevantCost), 5e-3)
        val holding = constrainedSkus.sumOf {
            it.holdingRate * budget.solve().quantityOf(it) / 2.0
        }
        assertEquals(3000.00, holding, 1e-4)

        val space = ConstrainedLotSizing(portfolio, Constraint(SpaceOccupied(volumes), 800.0))
        assertEquals(2.953823172, space.solve().shadowPrice, 1e-8)
        assertEquals(7723.58, space.solve().measuredBy(RelevantCost), 5e-3)
    }

    // ---- sheet: ExchangeCurve ----------------------------------------------

    @Test
    fun `ExchangeCurve sheet`() {
        val kappa = 55.0; val gamma = 0.25
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
        val curve = ExchangeCurve(Portfolio(data.map { (who, hours) ->
            SKU.pricedAt(who.first, who.second, who.third, kappa * hours, gamma)
        }))
        assertEquals(19755.87, curve.rootSum, 5e-3)
        assertEquals(195147266.0, curve.constant, 1.0)
        assertEquals(195147266.0, curve.decomposedConstant, 1.0)
        assertEquals(5.6813, curve.varietyIndex, 5e-5)
        assertEquals(27939.02, curve.investmentAt(gamma), 5e-3)
        assertEquals(6984.76, curve.orderingCostAt(gamma), 5e-3)
        assertEquals(4.000, curve.investmentAt(gamma) / curve.orderingCostAt(gamma), 1e-9)
        // the curve block, twelve operating points, product constant down every row
        doubleArrayOf(0.05, 0.08, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40, 0.50, 0.65, 0.80, 1.00)
            .forEach {
                assertEquals(curve.constant, curve.investmentAt(it) * curve.orderingCostAt(it),
                    curve.constant * 1e-9)
            }
        assertEquals(62473.56, curve.investmentAt(0.05), 5e-3)
        assertEquals(13969.51, curve.investmentAt(1.00), 5e-3)
    }

    // ---- sheet: JointReplenishment -----------------------------------------

    @Test
    fun `JointReplenishment sheet`() {
        val family = OrderFamily(Portfolio(listOf(
            SKU.pricedAt("Drive unit", 8000.0, 20.0, 25.0, 0.25),
            SKU.pricedAt("Gearbox", 800.0, 20.0, 20.0, 0.25),
            SKU.pricedAt("Seal kit", 200.0, 8.0, 15.0, 0.25),
            SKU.pricedAt("Name plate", 40.0, 4.0, 15.0, 0.25),
        )), majorSetupCost = 400.0)

        assertEquals(8422.39, family.independentCost, 5e-3)
        assertEquals(0.146209, family.commonCycleInterval, 5e-7)
        assertEquals(6497.54, family.costOf(CommonCycle(family.commonCycleInterval)), 5e-3)

        val tInt = family.bestBasePeriod { IntegerMultiple(it) }
        val tP2 = family.bestBasePeriod { PowerOfTwoMultiple(it) }
        assertEquals(51.882, tInt * 365.0, 5e-3)
        assertEquals(51.800, tP2 * 365.0, 5e-3)
        assertEquals(6402.06, family.costOf(IntegerMultiple(tInt)), 5e-3)
        assertEquals(6403.34, family.costOf(PowerOfTwoMultiple(tP2)), 5e-3)
        val excess = family.costOf(PowerOfTwoMultiple(tP2)) / family.costOf(IntegerMultiple(tInt)) - 1.0
        assertEquals(0.00020, excess, 5e-6)
    }

    // ---- sheet: Distribution ------------------------------------------------

    @Test
    fun `Distribution sheet`() {
        val network = DistributionNetwork(
            SKU("central", 1200.0, 500.0, 10.0),
            listOf(
                SKU("1", 200.0, 100.0, 15.0), SKU("2", 200.0, 125.0, 12.0),
                SKU("3", 200.0, 110.0, 20.0), SKU("4", 200.0, 150.0, 18.0),
                SKU("5", 200.0, 75.0, 20.0), SKU("6", 200.0, 90.0, 17.0),
            ))
        assertEquals(listOf("5", "3", "6", "4", "1", "2"), network.ranked().map { it.label })
        assertEquals(listOf("6", "4", "1", "2"), network.pinnedSet().map { it.label })
        assertEquals(0.343049, network.centralInterval(), 5e-7)
        assertEquals(17.84, network.centralInterval() * 52.0, 5e-3)

        val weeks = { t: Double -> t * 52.0 }
        val relaxed = network.relaxedIntervals()
        assertEquals(14.24, weeks(relaxed[network.skus.indexOfFirst { it.label == "5" }]), 5e-3)
        assertEquals(17.25, weeks(relaxed[network.skus.indexOfFirst { it.label == "3" }]), 5e-3)

        val rule = PowerOfTwoMultiple(1.0 / 52.0)
        network.plan(rule).forEach { assertEquals(16.0, weeks(it), 1e-9) }
        assertTrue(network.pinnedSet().size == 4)
    }
}
