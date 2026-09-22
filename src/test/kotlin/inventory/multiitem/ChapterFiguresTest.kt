package inventory.multiitem

import inventory.multiitem.network.DistributionNetwork
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The figures Chapter 4 prints that the other tests reach only indirectly.
 *
 * `ChapterExamplesTest` asserts the answers: what a policy costs, which multipliers it
 * picks, which regions it pins. This file asserts the numbers the chapter shows a
 * reader on the way there, because a reader checks those with a calculator and the
 * answers are the part that cannot be checked that way.
 */
class ChapterFiguresTest {

    private val week = 1.0 / 52.0

    @Test
    fun `Example 4 point 6, rounding two items to a weekly base period`() {
        val drive = SKU("Drive unit", demandRate = 8000.0, orderCost = 237.0, holdingRate = 5.0)
        val arm = SKU("Control arm", demandRate = 6000.0, orderCost = 300.0, holdingRate = 4.0)
        assertEquals(20000.0, drive.holdingCoefficient, 1e-9)
        assertEquals(12000.0, arm.holdingCoefficient, 1e-9)
        assertEquals(0.10886, drive.preferredInterval, 5e-6)
        assertEquals(0.15811, arm.preferredInterval, 5e-6)
        assertEquals(5.66, drive.preferredInterval / week, 5e-3)
        assertEquals(8.22, arm.preferredInterval / week, 5e-3)

        val rule = PowerOfTwoMultiple(week)
        assertEquals(8, rule.multiplierFor(drive.preferredInterval), "the drive unit rounds out")
        assertEquals(8, rule.multiplierFor(arm.preferredInterval), "the control arm rounds in")

        fun cost(s: SKU, t: Double) = s.orderCost / t + s.holdingCoefficient * t
        val driveFree = cost(drive, drive.preferredInterval)
        val driveRun = cost(drive, rule.implementable(drive.preferredInterval))
        assertEquals(4354.31, driveFree, 5e-3)
        assertEquals(4617.42, driveRun, 5e-3)
        assertEquals(1.4133, rule.implementable(drive.preferredInterval) / drive.preferredInterval, 5e-5)
        assertEquals(6.04, 100.0 * (driveRun / driveFree - 1.0), 5e-3)

        val armFree = cost(arm, arm.preferredInterval)
        val armRun = cost(arm, rule.implementable(arm.preferredInterval))
        assertEquals(0.9730, rule.implementable(arm.preferredInterval) / arm.preferredInterval, 5e-5)
        assertEquals(0.04, 100.0 * (armRun / armFree - 1.0), 5e-3)

        // @eq-pow2-bound's worst case, which the drive unit very nearly attains.
        assertEquals(1.0607, 3.0 / (2.0 * sqrt(2.0)), 5e-5)
    }

    @Test
    fun `Table 4 point 9, the joint schedule's base period is not 52 days`() {
        val family = supplier()
        val tInt = family.bestBasePeriod { IntegerMultiple(it) }
        val tP2 = family.bestBasePeriod { PowerOfTwoMultiple(it) }
        // The two policies do not share a base period, and neither is exactly 52 days.
        assertEquals(0.14214, tInt, 5e-6)
        assertEquals(0.14192, tP2, 5e-6)
        assertEquals(51.88, tInt * 365.0, 5e-3)
        assertEquals(51.80, tP2 * 365.0, 5e-3)
        // The schedule of @tbl-joint-schedule, each row rounded to the nearest day.
        assertEquals(listOf(52L, 52L, 104L, 414L),
            listOf(1, 1, 2, 8).map { Math.round(it * tP2 * 365.0) })
        // Ordering each item alone would give the cycles in the last column of @tbl-joint-data.
        assertEquals(listOf(13L, 37L, 100L, 316L),
            family.portfolio.skus.map { Math.round(it.preferredInterval * 365.0) })
    }

    @Test
    fun `Example 4 point 10, sixteen weeks is the runnable interval for every location`() {
        val network = warehouse()
        val rule = PowerOfTwoMultiple(week)
        val relaxed = network.relaxedIntervals().toList()
        val runnable = relaxed.map { rule.implementable(it) }
        assertEquals(List(7) { 16.0 * week }, runnable)
        assertEquals(0.307692, runnable.first(), 5e-7)
        // Every relaxed interval falls inside the band @eq-pow2-bracket gives to l* = 4.
        val low = 8.0 * sqrt(2.0)
        val high = 16.0 * sqrt(2.0)
        assertEquals(11.3, low, 5e-2)
        assertEquals(22.6, high, 5e-2)
        for (interval in relaxed) {
            val weeks = interval / week
            assertTrue(weeks > low && weeks <= high, "$weeks weeks is outside the band")
        }
    }

    @Test
    fun `Table 4 point 7, the storeroom curve at the multipliers the chapter tabulates`() {
        val curve = ExchangeCurve(storeroom())
        val want = listOf(0.05 to (62474.0 to 3124.0), 0.10 to (44175.0 to 4418.0),
            0.25 to (27939.0 to 6985.0), 0.50 to (19756.0 to 9878.0),
            1.00 to (13970.0 to 13970.0))
        for ((price, expected) in want) {
            val investment = curve.investmentAt(price)
            val ordering = curve.orderingCostAt(price)
            assertEquals(expected.first, investment, 1.0, "investment at $price")
            assertEquals(expected.second, ordering, 1.0, "ordering cost at $price")
            assertEquals(195147265.65, investment * ordering, 1.0,
                "the product is constant along the curve")
            assertEquals(1.0 / price, investment / ordering, 1e-6,
                "the ratio is one over the multiplier")
        }
        // Order handling hours, at $55 an hour.
        assertEquals(listOf(57L, 80L, 127L, 180L, 254L),
            want.map { Math.round(curve.orderingCostAt(it.first) / 55.0) })
    }

    private fun supplier() = OrderFamily(Portfolio(listOf(
        SKU.pricedAt("Drive unit", 8000.0, 20.0, 25.0, 0.25),
        SKU.pricedAt("Gearbox", 800.0, 20.0, 20.0, 0.25),
        SKU.pricedAt("Seal kit", 200.0, 8.0, 15.0, 0.25),
        SKU.pricedAt("Name plate", 40.0, 4.0, 15.0, 0.25),
    )), majorSetupCost = 400.0)

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

    private fun storeroom(): Portfolio {
        val kappa = 55.0
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
            SKU.pricedAt(who.first, who.second, who.third, kappa * hours, 0.25)
        })
    }
}
