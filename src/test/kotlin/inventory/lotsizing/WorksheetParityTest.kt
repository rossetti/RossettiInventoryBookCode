package inventory.lotsizing
import inventory.lotsizing.models.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The four worksheets of @sec-eoq-spreadsheets, reproduced by the code to the sheets' own
 * figures. Parity with the supplied spreadsheets is a promise the book makes, so it
 * is asserted rather than assumed.
 *
 * The all-units case is the one that earns its place. Its three candidates rank one
 * way on relevant cost and another on total cost, so it is the case that catches a
 * model selecting on the wrong objective. The worked example in @sec-eoq-allunits does
 * not: there the two rankings agree.
 */
class WorksheetParityTest {
    @Test fun eoqSheet() {
        val p = CostParameters(demandRate = 100.0, unitCost = 8.0, orderCost = 20.0,
            holding = HoldingCost.carryingCharge(0.25 / 12.0), leadTime = 0.25,
            timeUnit = TimeUnit.MONTH)
        val a = EconomicOrderQuantity.optimize(p)
        println("EOQ sheet: Q*=%.6f T=%.6f I=%.6f OC=%.6f HC=%.6f TC=%.6f r=%.4f rounded=%d"
            .format(a.orderQuantity, a.cycleTime, a.cycle.averageOnHand,
                a.measures.ordering, a.measures.holding, a.measures.totalCost,
                a.reorderPoint, a.roundedOrderQuantity))
        assertEquals(154.919334, a.orderQuantity, 1e-6)
        assertEquals(825.819889, a.measures.totalCost, 1e-6)
        assertEquals(25.0, a.reorderPoint, 1e-9)
    }
    @Test fun poqSheet() {
        val p = CostParameters(demandRate = 2500.0, unitCost = 2.0, orderCost = 50.0,
            holding = HoldingCost.carryingCharge(0.3),
            replenishment = Replenishment.atRate(10_000.0, 2500.0))
        val a = EconomicProductionQuantity.optimize(p)
        println("POQ sheet: Q*=%.6f H=%.6f I=%.6f T=%.7f Tp=%.7f OC=%.6f HC=%.6f"
            .format(a.orderQuantity, a.cycle.maxOnHand, a.cycle.averageOnHand,
                a.cycleTime, a.cycle.replenishmentTime!!, a.measures.ordering, a.measures.holding))
        assertEquals(745.355992, a.orderQuantity, 1e-6)
        assertEquals(559.016994, a.cycle.maxOnHand, 1e-6)
        assertEquals(167.705098, a.measures.ordering, 1e-6)
    }
    @Test fun allUnitsSheet() {
        val p = CostParameters(demandRate = 600.0, orderCost = 8.0,
            schedule = AllUnits(listOf(PriceLevel(0.0, 0.30), PriceLevel(500.0, 0.29),
                PriceLevel(1000.0, 0.28))),
            holding = HoldingCost.carryingCharge(0.2))
        val a = AllUnitsDiscountModel.optimize(p)
        println("All units: Q*=%.4f TC=%.4f".format(a.orderQuantity, a.measures.totalCost))
        (a.choice as QuantityChoice.AmongCandidates).candidates.forEach {
            println("   level %d price %.2f q=%9.4f feasible=%-5s TC=%.4f %s"
                .format(it.levelIndex + 1, it.unitCost, it.quantity, it.feasible,
                    it.totalCost, if (it.selected) "<--" else "")) }
        assertEquals(500.0, a.orderQuantity, 1e-9)
        assertEquals(198.10, a.measures.totalCost, 0.005)

        // The discriminating detail: on relevant cost alone the ranking reverses.
        val candidates = (a.choice as QuantityChoice.AmongCandidates).candidates
        val byTotal = candidates.minBy { it.totalCost }
        assertEquals(500.0, byTotal.quantity, 1e-9)
        assertEquals(204.0, candidates.first { it.quantity == 400.0 }.totalCost, 0.005)
        assertEquals(200.8, candidates.first { it.quantity == 1000.0 }.totalCost, 0.005)
    }
    @Test fun incrementalSheet() {
        val schedule = Incremental(listOf(PriceLevel(0.0, 0.30), PriceLevel(500.0, 0.29),
            PriceLevel(1000.0, 0.28)))
        val p = CostParameters(demandRate = 600.0, orderCost = 8.0,
            schedule = schedule, holding = HoldingCost.carryingCharge(0.2))
        println("Incremental fixed charges: " + (0..2).map { schedule.fixedChargeAt(it) })
        println("  effective order costs:   " +
            (0..2).map { IncrementalDiscountModel.effectiveOrderCost(p, it) })
        val a = IncrementalDiscountModel.optimize(p)
        (a.choice as QuantityChoice.AmongCandidates).candidates.forEach {
            println("   level %d price %.2f q=%9.4f TC=%.4f %s"
                .format(it.levelIndex + 1, it.unitCost, it.quantity, it.totalCost,
                    if (it.selected) "<--" else "")) }
        println("Incremental: Q*=%.4f TC=%.4f".format(a.orderQuantity, a.measures.totalCost))
        assertEquals(listOf(0.0, 5.0, 15.0), (0..2).map { schedule.fixedChargeAt(it) })
        assertEquals(400.0, a.orderQuantity, 1e-9)
        assertEquals(204.00, a.measures.totalCost, 0.005)
    }
}
