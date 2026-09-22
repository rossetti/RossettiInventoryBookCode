package inventory.lotsizing

import inventory.lotsizing.models.*
import kotlin.test.*

/** The error channels of the design, and the invariants that are checked rather than assumed. */
class ContractTest {

    @Test
    fun `a zero backorder cost is refused, and the message names the limit meant`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            ShortagePolicy.backordered(costPerUnitPerTime = 0.0)
        }
        assertContains(thrown.message!!, "NotPermitted")
        assertContains(thrown.message!!, "free rather than forbidden")
    }

    @Test
    fun `a replenishment rate at or below the demand rate is refused`() {
        assertFailsWith<IllegalArgumentException> { Replenishment.atRate(100.0, 100.0) }
        assertFailsWith<IllegalArgumentException> { Replenishment.atRate(99.0, 100.0) }
        assertFailsWith<IllegalArgumentException> {
            CostParameters(
                demandRate = 100.0, unitCost = 8.0, orderCost = 20.0,
                holding = HoldingCost.rate(4.0),
                replenishment = Replenishment.AtRate(80.0),
            )
        }
    }

    @Test
    fun `a rate that becomes infeasible on assignment is refused at the point of assignment`() {
        val parameters = CostParameters(
            demandRate = 100.0, unitCost = 8.0, orderCost = 20.0,
            holding = HoldingCost.rate(4.0),
            replenishment = Replenishment.atRate(250.0, 100.0),
        )
        assertFailsWith<IllegalArgumentException> { parameters.demandRate = 300.0 }
        assertEquals(100.0, parameters.demandRate, "the failed assignment left no damage")
    }

    @Test
    fun `a policy cannot plan a shortage the parameters forbid`() {
        val parameters = CostParameters(
            demandRate = 100.0, unitCost = 8.0, orderCost = 20.0,
            holding = HoldingCost.rate(4.0),
        )
        assertFailsWith<IllegalArgumentException> {
            EconomicOrderQuantity.evaluate(parameters, Policy(100.0, maxBackorder = 5.0))
        }
    }

    @Test
    fun `a backorder level larger than one order can clear is not a cycle`() {
        val parameters = CostParameters(
            demandRate = 100.0, unitCost = 8.0, orderCost = 20.0,
            holding = HoldingCost.rate(4.0),
            replenishment = Replenishment.atRate(250.0, 100.0),
            shortages = ShortagePolicy.backordered(6.0),
        )
        // One order of 400 leaves 240 units of stock, so 300 of backorders cannot clear.
        assertFailsWith<IllegalArgumentException> {
            GeneralBackorderModel.evaluate(parameters, Policy(400.0, 300.0))
        }
    }

    @Test
    fun `a penalty against a different item is refused`() {
        val one = EconomicOrderQuantity.optimize(item(unitCost = 40.0))
        val other = EconomicOrderQuantity.optimize(item(unitCost = 50.0))
        assertFailsWith<IllegalArgumentException> { one.penaltyAgainst(other) }
    }

    @Test
    fun `break points must strictly increase and start at zero`() {
        assertFailsWith<IllegalArgumentException> {
            AllUnits(listOf(PriceLevel(10.0, 5.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            AllUnits(listOf(PriceLevel(0.0, 5.0), PriceLevel(0.0, 4.0)))
        }
    }

    @Test
    fun `a model that needs a backorder cost reports it cannot be used, and does not throw`() {
        val verdict = PlannedBackorderModel.applicability(item())
        assertIs<Applicability.NotApplicable>(verdict)
        assertContains(verdict.reason, "forbid shortages")
        assertFailsWith<IllegalStateException> { PlannedBackorderModel.optimize(item()) }
    }

    /**
     * Applying the order quantity to an item with a finite production rate is a
     * deliberate approximation, so it is reported rather than refused, and the
     * analysis records what was discarded.
     */
    @Test
    fun `a model applied outside its assumptions says what it is ignoring`() {
        val parameters = item(replenishment = Replenishment.atRate(10_000.0, 1500.0))
        val verdict = EconomicOrderQuantity.applicability(parameters)
        assertIs<Applicability.Approximating>(verdict)
        assertEquals(1, verdict.ignored.size)
        assertContains(verdict.ignored.single(), "finite replenishment rate")

        val analysis = EconomicOrderQuantity.optimize(parameters)
        assertContains(analysis.approximated.single(), "finite replenishment rate")
        assertTrue(analysis.orderQuantity < EconomicProductionQuantity.optimize(parameters).orderQuantity,
            "ignoring the finite rate understates the quantity")
    }

    /** Editing the parameters that produced an analysis does not move the analysis. */
    @Test
    fun `an analysis is isolated from later edits to its parameters`() {
        val parameters = item()
        val analysis = EconomicOrderQuantity.optimize(parameters)
        val quantityBefore = analysis.orderQuantity
        val costBefore = analysis.measures.totalCost
        val snapshotBefore = analysis.parameters

        parameters.demandRate = 9_999.0
        parameters.orderCost = 1.0
        parameters.unitCost = 2.0
        parameters.holding = HoldingCost.rate(99.0)
        parameters.leadTime = 0.5
        parameters.shortages = ShortagePolicy.backordered(3.0)

        assertEquals(quantityBefore, analysis.orderQuantity, 0.0)
        assertEquals(costBefore, analysis.measures.totalCost, 0.0)
        assertEquals(snapshotBefore, analysis.parameters)
        assertEquals(1500.0, analysis.parameters.demandRate, 0.0)
    }

    /** Each element of a lazy range snapshots its own inputs before the next mutation. */
    @Test
    fun `snapshots across a range differ from one another`() {
        val levels = doubleArrayOf(80.0, 100.0, 120.0, 140.0)
        val analyses = EconomicOrderQuantity
            .analyzing(item(), CostParameters::orderCost, levels)
            .toList()
        assertEquals(4, analyses.size)
        assertEquals(4, analyses.map { it.parameters.orderCost }.toSet().size,
            "if snapshot() moved after the loop's next mutation these would all be 140")
        assertEquals(levels.toList(), analyses.map { it.parameters.orderCost })
    }

    /**
     * @eq-total-cost charges the stockout cost on the rate at which demand arrives to
     * an empty shelf, so the term must be pi times that rate and nothing else.
     *
     * This is the check that catches the counting error the peak backorder level
     * invites. The peak is a level; the term needs a flow, and demand keeps arriving
     * while the queue drains. The two coincide only when replenishment is
     * instantaneous, which is asserted here as well so the agreement is not mistaken
     * for a general fact.
     */
    @Test
    fun `the stockout cost is the stockout price times the unfilled demand rate`() {
        val random = java.util.Random(20260907L)
        repeat(300) {
            val demand = 10.0 + random.nextDouble() * 2_000.0
            val rate = demand * (1.05 + random.nextDouble() * 20.0)
            val stockoutCharge = random.nextDouble() * 5.0
            val parameters = CostParameters(
                demandRate = demand,
                unitCost = 0.5 + random.nextDouble() * 100.0,
                orderCost = 1.0 + random.nextDouble() * 300.0,
                holding = HoldingCost.rate(0.1 + random.nextDouble() * 10.0),
                replenishment = Replenishment.atRate(rate, demand),
                shortages = ShortagePolicy.backordered(
                    costPerUnitPerTime = 0.1 + random.nextDouble() * 20.0,
                    costPerUnit = stockoutCharge),
            )
            val quantity = 1.0 + random.nextDouble() * 500.0
            val backorder = random.nextDouble() * quantity * parameters.survivingFraction
            val m = GeneralBackorderModel
                .evaluate(parameters, Policy(quantity, backorder)).measures
            assertEquals(stockoutCharge * m.unfilledDemandRate, m.stockout,
                maxOf(m.stockout, 1.0) * 1e-12,
                "the stockout term must be pi times the unfilled demand rate")
        }
    }

    /**
     * The peak backorder level undercounts the units that were short, by exactly the
     * surviving fraction. @exm-cycle has thirty at the peak and fifty that waited.
     */
    @Test
    fun `the peak backorder level is not the count of units short`() {
        val parameters = CostParameters(
            demandRate = 100.0, unitCost = 8.0, orderCost = 20.0,
            holding = HoldingCost.rate(4.0),
            replenishment = Replenishment.atRate(250.0, 100.0),
            shortages = ShortagePolicy.backordered(costPerUnitPerTime = 6.0, costPerUnit = 1.0),
        )
        val analysis = GeneralBackorderModel.evaluate(parameters, Policy(400.0, 30.0))
        val cycle = analysis.cycle

        val unitsShortPerCycle = analysis.measures.unfilledDemandRate * cycle.length
        assertEquals(50.0, unitsShortPerCycle, 1e-9,
            "thirty accumulate over T4 and twenty more arrive while the queue drains over T1")
        assertEquals(cycle.maxBackorder / parameters.survivingFraction, unitsShortPerCycle, 1e-9,
            "the count is the peak divided by the surviving fraction, @eq-units-short")
        assertEquals(30.0, cycle.maxBackorder, 1e-12, "the peak is a level, not a count")

        // The queue closes: production delivered over T1 clears everything that waited.
        val deliveredOverT1 = 250.0 * cycle.segments.clearingBackorders
        val arrivedOverT1 = 100.0 * cycle.segments.clearingBackorders
        assertEquals(50.0, deliveredOverT1, 1e-9)
        assertEquals(20.0, arrivedOverT1, 1e-9)
        assertEquals(0.0, cycle.maxBackorder + arrivedOverT1 - deliveredOverT1, 1e-9)
    }

    /** With instantaneous replenishment the peak and the count do coincide. */
    @Test
    fun `the peak equals the count only when replenishment is instantaneous`() {
        val parameters = CostParameters(
            demandRate = 100.0, unitCost = 8.0, orderCost = 20.0,
            holding = HoldingCost.rate(4.0),
            shortages = ShortagePolicy.backordered(costPerUnitPerTime = 6.0, costPerUnit = 1.0),
        )
        val analysis = PlannedBackorderModel.evaluate(parameters, Policy(400.0, 30.0))
        val unitsShortPerCycle =
            analysis.measures.unfilledDemandRate * analysis.cycle.length
        assertEquals(analysis.cycle.maxBackorder, unitsShortPerCycle, 1e-9,
            "the queue drains instantly, so nothing arrives while it does")
    }

    /** The cycle identities of @sec-eoq-cycle hold over random valid inputs. */
    @Test
    fun `cycle identities hold over random parameters`() {
        val random = java.util.Random(20260907L)
        repeat(500) {
            val demand = 10.0 + random.nextDouble() * 5_000.0
            val rate = demand * (1.05 + random.nextDouble() * 20.0)
            val parameters = CostParameters(
                demandRate = demand,
                unitCost = 0.5 + random.nextDouble() * 500.0,
                orderCost = random.nextDouble() * 500.0,
                holding = HoldingCost.rate(0.01 + random.nextDouble() * 20.0),
                replenishment = Replenishment.atRate(rate, demand),
                shortages = ShortagePolicy.backordered(0.01 + random.nextDouble() * 30.0),
            )
            val quantity = 1.0 + random.nextDouble() * 2_000.0
            val surviving = parameters.survivingFraction
            val backorder = random.nextDouble() * quantity * surviving
            // The constructor checks the identities; reaching here is the assertion.
            val cycle = InventoryCycle(parameters, Policy(quantity, backorder))
            assertEquals(1.0, cycle.readyRate + cycle.fractionOutOfStock, 1e-12)
            assertTrue(cycle.averageOnHand >= 0.0 && cycle.averageBackorder >= 0.0)
        }
    }

    /** Every model agrees with itself: evaluating its own optimum reproduces it. */
    @Test
    fun `evaluate at the optimum agrees with optimize, for every model`() {
        val cases: List<Pair<LotSizingModel, CostParametersIfc>> = listOf(
            EconomicOrderQuantity to item(),
            EconomicProductionQuantity to item(replenishment = Replenishment.atRate(9000.0, 1500.0)),
            PlannedBackorderModel to item(shortages = ShortagePolicy.backordered(7.0)),
            GeneralBackorderModel to item(
                replenishment = Replenishment.atRate(9000.0, 1500.0),
                shortages = ShortagePolicy.backordered(7.0, costPerUnit = 0.5)),
            AllUnitsDiscountModel to discounted(AllUnits(
                listOf(PriceLevel(0.0, 10.0), PriceLevel(500.0, 9.0)))),
            IncrementalDiscountModel to discounted(Incremental(
                listOf(PriceLevel(0.0, 10.0), PriceLevel(500.0, 9.0)))),
        )
        for ((model, parameters) in cases) {
            val best = model.optimize(parameters)
            val again = model.evaluate(parameters, best.policy)
            assertEquals(best.orderQuantity, again.orderQuantity, 1e-12, model.name)
            assertEquals(best.measures.relevantCost, again.measures.relevantCost, 1e-9, model.name)
            assertEquals(best.measures.totalCost, again.measures.totalCost, 1e-9, model.name)
            assertEquals(best.cycle.averageOnHand, again.cycle.averageOnHand, 1e-9, model.name)
        }
    }

    /** The optimum beats a dense grid of alternatives, for every closed-form model. */
    @Test
    fun `the optimum beats a dense grid`() {
        val cases: List<Pair<LotSizingModel, CostParametersIfc>> = listOf(
            EconomicOrderQuantity to item(),
            EconomicProductionQuantity to item(replenishment = Replenishment.atRate(9000.0, 1500.0)),
        )
        for ((model, parameters) in cases) {
            val best = model.optimize(parameters)
            val grid = (1..400).map { best.orderQuantity * (0.2 + it * 0.01) }
            for (quantity in grid) {
                val other = model.evaluate(parameters, quantity)
                assertTrue(other.measures.relevantCost >= best.measures.relevantCost - 1e-9,
                    "${model.name}: $quantity beat the reported optimum")
            }
        }
    }

    private fun item(
        unitCost: Double = 40.0,
        replenishment: Replenishment = Replenishment.Instantaneous,
        shortages: ShortagePolicy = ShortagePolicy.NotPermitted,
    ) = CostParameters(
        demandRate = 1500.0,
        unitCost = unitCost,
        orderCost = 120.0,
        holding = HoldingCost.rate(8.8),
        replenishment = replenishment,
        shortages = shortages,
        leadTime = 10.0 / 365.0,
    )

    private fun discounted(schedule: PriceSchedule) = CostParameters(
        demandRate = 8000.0,
        schedule = schedule,
        orderCost = 30.0,
        holding = HoldingCost.carryingCharge(0.30),
    )
}
