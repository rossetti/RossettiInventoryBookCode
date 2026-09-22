package inventory.lotsizing

import inventory.lotsizing.models.EconomicOrderQuantity
import inventory.lotsizing.models.EconomicProductionQuantity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The figures @sec-eoq-design prints that the other tests reach only in part.
 *
 * `WorksheetParityTest` pins each sheet's headline answer and `SensitivityTest` pins
 * the reorder point at the two lead times that bracket a cycle. What neither reaches
 * is the middle of the sweep, where the reorder point is at its smallest, and the two
 * production figures the chapter quotes underneath the report.
 */
class ChapterFiguresTest {

    private fun monthlyItem() = CostParameters(
        demandRate = 100.0, unitCost = 8.0, orderCost = 20.0,
        holding = HoldingCost.carryingCharge(0.25 / 12.0),
        leadTime = 0.25, timeUnit = TimeUnit.MONTH,
    )

    @Test
    fun `Example 3 point 13, the reorder point against the lead time`() {
        val item = monthlyItem()
        val want = listOf(
            0.25 to 25.0, 1.0 to 100.0, 1.6 to 5.080667,
            2.0 to 45.080667, 3.0 to 145.080667, 4.0 to 90.161332,
        )
        for ((lead, point) in want) {
            item.leadTime = lead
            assertEquals(point, EconomicOrderQuantity.optimize(item).reorderPoint, 1e-6,
                "a lead time of $lead months")
        }
        // A lead time just over one cycle gives the SMALLEST reorder point of the six,
        // which is what makes the MOD form of @sec-eoq-design-pipeline worth the argument.
        assertEquals(1.6, want.minByOrNull { it.second }!!.first, 1e-12)
    }

    @Test
    fun `Example 3 point 14, the production sheet underneath the report`() {
        val p = CostParameters(
            demandRate = 2500.0, unitCost = 2.0, orderCost = 50.0,
            holding = HoldingCost.carryingCharge(0.3),
            replenishment = Replenishment.atRate(10_000.0, 2500.0),
        )
        val a = EconomicProductionQuantity.optimize(p)
        assertEquals(0.60, p.holdingRateAt(a.orderQuantity), 1e-12, "the holding rate h")
        assertEquals(745.355992, a.orderQuantity, 1e-6)
        assertEquals(279.508497, a.cycle.averageOnHand, 1e-6, "half the peak, not half the lot")
        assertEquals(559.016994, a.cycle.maxOnHand, 1e-6)
        assertEquals(0.0745356, a.cycle.replenishmentTime!!, 1e-7)
        assertEquals(0.298142, a.cycleTime, 1e-6)
        assertEquals(3.354102, 1.0 / a.cycleTime, 1e-6, "orders per year")
        // The surviving fraction is what separates this model from the classical one.
        assertEquals(0.75, a.cycle.maxOnHand / a.orderQuantity, 1e-12)
    }
}
