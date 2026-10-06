package inventory.simulation

import inventory.continuousreview.LeadTimeDemand
import inventory.periodicreview.DemandOverInterval
import inventory.periodicreview.RSModel
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertTrue

/** Proportions of observed values at or below each threshold, one response per threshold. */
private class AtMost(parent: ModelElement, thresholds: List<Double>, name: String) : ModelElement(parent, name) {
    val responses = thresholds.associateWith { Response(this, "$name<=${it}") }
    fun observe(x: Double) = responses.forEach { (t, r) -> r.value = if (x <= t + 1e-9) 1.0 else 0.0 }
}

/**
 * Each test checks several measures from the same runs, so each interval is built
 * at the Bonferroni level for that many, and the family passes together at 95%.
 */
private fun check(label: String, exact: Double, est: Estimate, failures: MutableList<String>) {
    val a = Agreement(label, exact, est)
    println(a)
    if (!a.covered) failures += label
}

/**
 * The examples of chapter 11 that have an exact column, @sec-simulation-design-agreement.
 * Each test asserts that every interval covers its exact value.
 */
class ExactAgreementTest {

    /** @exm-sim-rs-transformer: (R, S) with R one month and S = 14, against @exm-periodic-transformer. */
    @Test
    fun `the transformer reviewed monthly`() {
        val exact = RSModel(45.0, 220.0, 950.0, 8550.0, 2.0 / 12.0, 1.0 / 12.0,
            DemandOverInterval.poisson(45.0)).evaluate(14)
        val m = Model("TransformerRS")
        val supplier = LeadTimeSupplier(m, ConstantRV(2.0 / 12.0), name = "Supplier")
        val store = PeriodicSimInventory(m, 1.0 / 12.0, 13, 14, filler = supplier, name = "Transformer").apply {
            orderCost = 220.0; holdingCost = 950.0; backorderCost = 8550.0
        }
        CustomerDemand(m, store, ExponentialRV(1.0 / 45.0, streamNum = 1), name = "Crews")
        m.numberOfReplications = 30; m.lengthOfReplication = 1020.0; m.lengthOfReplicationWarmUp = 20.0
        m.simulate()
        val level = Estimate.bonferroni(12)
        val sim = SimulatedPerformance.of(store, level)
        val failures = mutableListOf<String>()
        sim.against(exact).forEach { println(it); if (!it.covered) failures += it.measure }
        check("orders a year", 11.7178, sim.orderFrequency, failures)
        check("undershoot (R, S)", 2.8403, Estimate.of(store.undershootResponse, level), failures)
        check("reviews that order", 1.0 - kotlin.math.exp(-3.75), Estimate.of(store.orderingFractionResponse, level), failures)
        assertTrue(failures.isEmpty(), "not covered: $failures")
    }

    /** @exm-sim-ss-cutout: (s, S) under job lots against @tbl-ss-approx, and (25, 75) against @tbl-lumpy-compare. */
    @Test
    fun `the cutout under job lots`() {
        data class Case(val label: String, val s: Int, val big: Int, val orderUpTo: Boolean,
                        val cost: Double, val ordersAYear: Double?)
        val cases = listOf(
            Case("(s, S) = (36, 56)", 36, 56, true, 3755.69, 38.462),
            Case("(s, S) = (25, 100)", 25, 100, true, 2010.10, 10.554),
            Case("(r, Q) = (25, 75)", 25, 75, false, 2010.22, null),
        )
        val failures = mutableListOf<String>()
        val level = Estimate.bonferroni(11)
        for (c in cases) {
            val m = Model("Cutout_${c.s}_${c.big}")
            val supplier = LeadTimeSupplier(m, ConstantRV(1.0 / 26.0), name = "Supplier")
            val store: SimInventory = if (c.orderUpTo)
                SSSimInventory(m, c.s, c.big, filler = supplier, name = "Cutout")
            else RQSimInventory(m, c.s, c.big, filler = supplier, name = "Cutout")
            store.orderCost = 82.50; store.holdingCost = 28.75; store.backorderCost = 287.50
            CustomerDemand(m, store, ExponentialRV(1.0 / 400.0, streamNum = 1),
                DEmpiricalRV(doubleArrayOf(1.0, 2.0, 4.0), doubleArrayOf(0.4, 0.8, 1.0), streamNum = 2), name = "Crews")
            m.numberOfReplications = 30; m.lengthOfReplication = 117.0; m.lengthOfReplicationWarmUp = 5.0
            m.simulate()
            val sim = SimulatedPerformance.of(store, level)
            println("--- ${c.label}")
            check("${c.label} cost", c.cost, sim.totalCost, failures)
            c.ordersAYear?.let { check("${c.label} orders", it, sim.orderFrequency, failures) }
            if (store is SSSimInventory) check("${c.label} undershoot", 0.8, Estimate.of(store.undershootResponse, level), failures)
            else {
                check("${c.label} ready rate", 0.9060, sim.readyRate, failures)
                check("${c.label} unit fill", 0.8978, sim.unitFillRate, failures)
                check("${c.label} lot fill", 0.8957, sim.lotFillRate, failures)
            }
            println("   ready ${sim.readyRate}  unit ${sim.unitFillRate}  lot ${sim.lotFillRate}")
        }
        assertTrue(failures.isEmpty(), "not covered: $failures")
    }

    /** @exm-sim-metric: the four exact rows of @tbl-sim-metric-exact; the disputed rows are printed. */
    @Test
    fun `the transformers at four storerooms and a depot`() {
        val m = Model("Metric")
        val depot = RQSimInventory(m, 15, 1, initialOnHand = 16,
            filler = LeadTimeSupplier(m, ConstantRV(60.0), name = "DepotRepair"), name = "Depot")
        val stores = (1..4).map { j ->
            val store = RQSimInventory(m, 3, 1, initialOnHand = 4, name = "Store$j")
            store.filler = RoutingFiller(m, listOf(
                LeadTimeSupplier(m, ConstantRV(30.0), name = "Shop$j") to 0.3,
                TransitLink(m, depot, ConstantRV(10.0), name = "Ship$j") to 0.7,
            ), streamNum = 10 + j, name = "Route$j")
            CustomerDemand(m, store, ExponentialRV(365.0 / 45.0, streamNum = j), name = "Failures$j")
            store
        }
        m.numberOfReplications = 30; m.lengthOfReplication = 366_825.0; m.lengthOfReplicationWarmUp = 1_825.0
        m.simulate()
        val failures = mutableListOf<String>()
        val level = Estimate.bonferroni(6)
        check("depot backorders", 5.0201, Estimate.of(depot.backorderedResponse, level), failures)
        check("wait, days", 14.54, Estimate.of(depot.orderWaitResponse, level), failures)
        for (s in stores) {
            val p = SimulatedPerformance.of(s, level)
            check("${s.name} pipeline", 3.2276, p.onOrder, failures)
            println("   ${s.name} backorders ${p.backorders} on hand ${p.onHand}  (METRIC 0.4055, VARI-METRIC 0.4815)")
        }
        assertTrue(failures.isEmpty(), "not covered: $failures")
    }

    /** @exm-sim-batched: chapter 10's running example against @tbl-sim-batched-exact. */
    @Test
    fun `the cutout's hub and four storerooms`() {
        val h = 28.75 / 52.0
        val b = 287.50 / 52.0
        val m = Model("Batched")
        val hub = RQSimInventory(m, 9, 8, filler = LeadTimeSupplier(m, ConstantRV(3.0), name = "Supplier"),
            name = "Hub").apply { holdingCost = h }
        val waits = AtMost(m, listOf(0.0, 1.0, 2.0), "HubWait")
        hub.orderWaitObserver = waits::observe
        val stores = (1..4).map { j ->
            RQSimInventory(m, 2, 1, initialOnHand = 3, filler = TransitLink(m, hub, ConstantRV(1.0), name = "Ship$j"),
                name = "Store$j").apply { holdingCost = h; backorderCost = b }
                .also { CustomerDemand(m, it, ExponentialRV(1.0, streamNum = j), name = "Crews$j") }
        }
        m.numberOfReplications = 30; m.lengthOfReplication = 12_050.0; m.lengthOfReplicationWarmUp = 50.0
        m.simulate()
        val failures = mutableListOf<String>()
        val level = Estimate.bonferroni(11)
        check("hub backorders", 1.0273, Estimate.of(hub.backorderedResponse, level), failures)
        check("hub ready rate", 0.6030, Estimate.of(hub.readyRateResponse, level), failures)
        check("hub on hand", 2.5273, Estimate.of(hub.onHandResponse, level), failures)
        check("mean wait", 0.2568, Estimate.of(hub.orderWaitResponse, level), failures)
        listOf(0.0 to 0.6030, 1.0 to 0.9117, 2.0 to 0.9985).forEach { (w, p) ->
            check("P(W <= $w)", p, Estimate.of(waits.responses.getValue(w), level), failures)
        }
        for (s in stores) check("${s.name} backorders", 0.0726, Estimate.of(s.backorderedResponse, level), failures)
        println("   hub cost ${Estimate.of(hub.totalCostResponse)}; store costs " +
            stores.joinToString { Estimate.of(it.totalCostResponse).toString() } + " (system exact 7.0195)")
        assertTrue(failures.isEmpty(), "not covered: $failures")
    }
}
