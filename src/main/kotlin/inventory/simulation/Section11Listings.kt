package inventory.simulation

import inventory.continuousreview.LeadTimeDemand
import ksl.modeling.variable.ResponseCIfc
import ksl.observers.ReplicationDataCollector
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import ksl.utilities.statistic.Statistic
import ksl.modeling.supplychain.report.resultsReport
import ksl.modeling.supplychain.spec.SupplyChainBuilder
import ksl.modeling.supplychain.spec.constant
import ksl.modeling.supplychain.spec.exponential
import ksl.modeling.supplychain.spec.supplyChain
import ksl.utilities.io.report.toMarkdown

/**
 * The runs behind the examples of @sec-simulation. Each function builds one
 * example from the classes of @sec-simulation-design-classes, runs it with the
 * settings the example states, and prints what the chapter reports.
 *
 *     ./gradlew run -PmainClass=inventory.simulation.Section11ListingsKt
 */
object Section11 {

    /** The chapter's standard run settings for the transformer, @sec-simulation-output-runs. */
    const val REPLICATIONS = 30
    const val YEARS = 1000.0
    const val WARMUP = 20.0

    /** The transformer's costs, @exm-optimize-transformer. */
    const val K = 220.0
    const val H = 950.0
    const val B = 8550.0

    /** One transformer stock point and the model that runs it. */
    class Run(val model: Model, val store: SimInventory, val supplier: LeadTimeSupplier) {
        val data = ReplicationDataCollector(model)

        fun simulate(replications: Int = REPLICATIONS, length: Double = YEARS + WARMUP, warmUp: Double = WARMUP): Run {
            model.numberOfReplications = replications
            model.lengthOfReplication = length
            model.lengthOfReplicationWarmUp = warmUp
            model.simulate()
            return this
        }

        fun perReplication(response: ResponseCIfc): DoubleArray = data.replicationData(response.name)
    }

    /** The transformer under (r, Q) or (R, s, S), with its demand on stream [demandStream]. */
    fun transformer(
        name: String,
        policy: (Model, LeadTimeSupplier) -> SimInventory,
        leadTime: RVariableIfc = ConstantRV(1.0 / 6.0),
        inSequence: Boolean = false,
        demandStream: Int = 1,
    ): Run {
        val m = Model(name)
        val supplier = LeadTimeSupplier(m, leadTime, inSequence, name = "Supplier")
        val store = policy(m, supplier).apply { orderCost = K; holdingCost = H; backorderCost = B }
        CustomerDemand(m, store, ExponentialRV(1.0 / 45.0, streamNum = demandStream), name = "Crews")
        val run = Run(m, store, supplier)
        run.data.addResponse(store.totalCostResponse)
        return run
    }

    fun line(label: String, e: Estimate) = println("  %-28s %s".format(label, e))

    // ---- @exm-sim-crossing -------------------------------------------------------------------

    /** Erlang with shape 4 and rate 24 a year, two months on average, sd one month. */
    fun erlangLeadTime(stream: Int = 2): RVariableIfc = GammaRV(4.0, 1.0 / 24.0, streamNum = stream)

    fun crossing() {
        println("### orders that overtake one another, (r, Q) = (10, 8), Erlang lead times")
        for (inSequence in listOf(false, true)) {
            val run = transformer("Crossing_$inSequence", { m, s -> RQSimInventory(m, 10, 8, filler = s, name = "Transformer") },
                leadTime = erlangLeadTime(), inSequence = inSequence).simulate()
            val p = SimulatedPerformance.of(run.store)
            println(if (inSequence) " one supply line:" else " independent lead times:")
            line("on hand", p.onHand); line("backorders", p.backorders); line("fill rate", p.unitFillRate)
            line("orders a year", p.orderFrequency); line("total cost", p.totalCost)
            line("on order", p.onOrder)
            line("time outstanding, years", Estimate.of(run.supplier.timeOutstandingResponse))
            line("fraction overtaking", Estimate.of(run.supplier.overtookResponse))
        }
        val palm = transformer("Palm", { m, s -> RQSimInventory(m, 10, 1, filler = s, name = "Transformer") },
            leadTime = erlangLeadTime()).simulate()
        val p = SimulatedPerformance.of(palm.store)
        println(" base stock S = 11, independent Erlang lead times (Palm: on order 7.5, ready rate 0.8622,")
        println("   backorders 0.1616, on hand 3.6616, whatever the lead time distribution):")
        line("on order", p.onOrder); line("ready rate", p.readyRate)
        line("backorders", p.backorders); line("on hand", p.onHand)
    }

    // ---- @exm-sim-ss-cutout -------------------------------------------------------------------

    fun cutoutSS() {
        println("### the cutout in job lots under (s, S)")
        for ((s, big) in listOf(36 to 56, 25 to 100)) {
            val m = Model("CutoutSS_$s")
            val supplier = LeadTimeSupplier(m, ConstantRV(1.0 / 26.0), name = "Supplier")
            val store = SSSimInventory(m, s, big, filler = supplier, name = "Cutout").apply {
                orderCost = 82.50; holdingCost = 28.75; backorderCost = 287.50
            }
            val counts = IntArray(4)
            store.undershootObserver = { u -> if (u < 4) counts[u]++ }
            CustomerDemand(m, store, ExponentialRV(1.0 / 400.0, streamNum = 1),
                DEmpiricalRV(doubleArrayOf(1.0, 2.0, 4.0), doubleArrayOf(0.4, 0.8, 1.0), streamNum = 2), name = "Crews")
            m.numberOfReplications = 30; m.lengthOfReplication = 117.0; m.lengthOfReplicationWarmUp = 5.0
            m.simulate()
            val p = SimulatedPerformance.of(store)
            println(" ($s, $big):")
            line("total cost", p.totalCost); line("orders a year", p.orderFrequency)
            line("mean undershoot", Estimate.of(store.undershootResponse))
            line("mean order size", Estimate.of(store.orderSizeResponse))
            val n = counts.sum().toDouble()
            println("  undershoot 0, 1, 2, 3: %.4f %.4f %.4f %.4f (equilibrium 0.5, 0.3, 0.1, 0.1)".format(
                counts[0] / n, counts[1] / n, counts[2] / n, counts[3] / n))
            line("ready rate", p.readyRate); line("unit fill rate", p.unitFillRate); line("lot fill rate", p.lotFillRate)
        }
    }

    // ---- @exm-sim-lostsales ------------------------------------------------------------------

    fun lostSales() {
        println("### the transformer when demand is lost, pi = 5,700 per unit")
        for ((r, q) in listOf(13 to 6, 8 to 6)) {
            val run = transformer("Lost_$r", { m, s ->
                RQSimInventory(m, r, q, filler = s, name = "Transformer").apply {
                    lostSales = true; backorderCost = 0.0; shortageCost = 5700.0
                }
            }).apply { store.backorderCost = 0.0 }.simulate()
            val p = SimulatedPerformance.of(run.store)
            println(" ($r, $q):")
            line("fill rate", p.unitFillRate)
            line("units lost a year", Estimate.of(run.store.unitsLostPerTimeResponse))
            line("on hand", p.onHand); line("orders a year", p.orderFrequency)
            line("ordering cost", p.orderingCost); line("holding cost", p.holdingCost)
            line("shortage cost", Estimate.of(run.store.shortageCostResponse)); line("total cost", p.totalCost)
        }
    }

    // ---- @exm-sim-periodic-premium -----------------------------------------------------------

    private fun paired(label: String, a: DoubleArray, b: DoubleArray): Statistic {
        val d = Statistic(label)
        for (i in a.indices) d.collect(b[i] - a[i])
        return d
    }

    fun premium() {
        println("### what continuous review is worth: (8, 7) against R = 0.120, S = 15")
        for ((label, periodicStream) in listOf("common random numbers" to 1, "independent streams" to 3)) {
            val c = transformer("Cont_$periodicStream", { m, s -> RQSimInventory(m, 8, 7, filler = s, name = "Transformer") }).simulate()
            val p = transformer("Per_$periodicStream", { m, s -> PeriodicSimInventory(m, 0.120, 14, 15, filler = s, name = "Transformer") },
                demandStream = periodicStream).simulate()
            val cc = c.perReplication(c.store.totalCostResponse)
            val cp = p.perReplication(p.store.totalCostResponse)
            val d = paired("Delta", cc, cp)
            println(" $label:")
            line("C_c", Estimate.of(c.store.totalCostResponse)); line("C_p", Estimate.of(p.store.totalCostResponse))
            println("  %-28s %.4f +/- %.4f   variance %.2f".format("Delta = C_p - C_c", d.average, d.halfWidth, d.variance))
            line("fill rate, continuous", Estimate.of(c.store.unitFillRateResponse))
            line("fill rate, periodic", Estimate.of(p.store.unitFillRateResponse))
        }
    }

    // ---- @exm-sim-undershoot -----------------------------------------------------------------

    fun undershoot() {
        println("### the undershoot at review, R = one month, S = 14")
        for (s in listOf(13, 10, 6)) {
            val run = transformer("Under_$s", { m, sup -> PeriodicSimInventory(m, 1.0 / 12.0, s, 14, filler = sup, name = "Transformer") }).simulate()
            val inv = run.store as PeriodicSimInventory
            println(" s = $s:")
            line("mean undershoot", Estimate.of(inv.undershootResponse))
            line("reviews that order", Estimate.of(inv.orderingFractionResponse))
        }
    }

    // ---- @exm-sim-neighborhood ---------------------------------------------------------------

    fun neighborhood() {
        println("### five reorder points at Q = 5, common random numbers")
        val data = linkedMapOf<String, DoubleArray>()
        for (r in 7..11) {
            val run = transformer("Nbhd_$r", { m, s -> RQSimInventory(m, r, 5, filler = s, name = "Transformer") }).simulate()
            data["r = $r"] = run.perReplication(run.store.totalCostResponse)
            line("cost at r = $r", Estimate.of(run.store.totalCostResponse))
        }
        val mca = MultipleComparisonAnalyzer(data, "TotalCost")
        mca.defaultIndifferenceZone = 75.0
        println("  best by average: ${mca.nameOfMinimumAverageOfData}")
        println("  MCB intervals for the minimum, delta = 75:")
        mca.mcbMinIntervalsAsMap(75.0).forEach { (k, v) -> println("    %-8s [%.2f, %.2f]".format(k, v.lowerLimit, v.upperLimit)) }
        val d = mca.pairedDifferenceStatistic("r = 8", "r = 9")!!
        println("  r = 8 less r = 9: %.2f +/- %.2f, sd %.2f".format(d.average, d.halfWidth, d.standardDeviation))
        val t = 2.045
        val needed = kotlin.math.ceil((t * d.standardDeviation / 9.17).let { it * it })
        println("  replications for a half-width of 9.17 on that difference: about %.0f".format(needed))
    }

    // ---- @exm-sim-joblots --------------------------------------------------------------------

    fun jobLots() {
        println("### the cutout in job lots: storeroom (25, 49), hub (120, 92), in years")
        val m = Model("JobLots")
        val lots = { stream: Int -> DEmpiricalRV(doubleArrayOf(1.0, 2.0, 4.0), doubleArrayOf(0.4, 0.8, 1.0), streamNum = stream) }
        val hub = RQSimInventory(m, 120, 92, filler = LeadTimeSupplier(m, ConstantRV(2.0 / 12.0), name = "Supplier"),
            name = "Hub").apply { orderCost = 82.50; holdingCost = 28.75; backorderCost = 115.0 }
        val store = RQSimInventory(m, 25, 49, filler = TransitLink(m, hub, ConstantRV(1.0 / 12.0), name = "Transfer"),
            name = "Storeroom").apply { orderCost = 82.50; holdingCost = 28.75; backorderCost = 287.50 }
        CustomerDemand(m, hub, ExponentialRV(1.0 / 250.0, streamNum = 1), lots(2), name = "HubCrews")
        CustomerDemand(m, store, ExponentialRV(1.0 / 150.0, streamNum = 3), lots(4), name = "StoreCrews")
        m.numberOfReplications = 30; m.lengthOfReplication = 305.0; m.lengthOfReplicationWarmUp = 5.0
        m.simulate()
        for (inv in listOf(store, hub)) {
            val p = SimulatedPerformance.of(inv)
            println(" ${inv.name}:")
            line("ordering", p.orderingCost); line("holding", p.holdingCost); line("backorder", p.backorderCost)
            line("total", p.totalCost); line("orders a year", p.orderFrequency)
            line("ready rate", p.readyRate); line("unit fill rate", p.unitFillRate); line("lot fill rate", p.lotFillRate)
        }
        line("hub customer wait, years", Estimate.of(hub.customerWaitResponse))
        line("storeroom order wait, years", Estimate.of(hub.orderWaitResponse))
        line("orders shipped complete", Estimate.of(hub.orderFilledOnArrivalResponse))
    }

    // ---- @exm-sim-package --------------------------------------------------------------------

    /** Chapter 10's running example built with the KSL's supply chain package, in weeks. */
    fun cutoutNetworkSpec() = supplyChain("CutoutNetwork") {
        transportStrategy = perIHPTimeBased
        val cutout = item("Cutout", leadTime = constant(3.0), unitCost = 115.0)
        holdingPoint("Hub") {
            attachedToExternalSupplier()
            inventory(cutout) { sQ(s = 9, Q = 8, initialOnHand = 17) }
            tier(count = 4, namePrefix = "Store", transportTime = constant(1.0)) {
                inventory(cutout) { sQ(s = 2, Q = 1, initialOnHand = 3) }
                demand(cutout, exponential(mean = 1.0, stream = autoStream()))
            }
        }
    }

    fun supplyChainPackage() {
        println("### chapter 10's running example in ksl.modeling.supplychain")
        val m = Model("CutoutPackage")
        val result = SupplyChainBuilder.build(m, cutoutNetworkSpec())
        m.numberOfReplications = 30; m.lengthOfReplication = 12_050.0; m.lengthOfReplicationWarmUp = 50.0
        m.simulate()
        println(result.network.resultsReport().toMarkdown())
        for (r in m.responses) {
            val n = r.name
            if (listOf("On Hand", "BackLogged", "w/o Stock", "Fill Rate", "Wait").any { n.contains(it) } &&
                (n.contains("Hub") || n.contains("Store1"))) {
                line(n.takeLast(60), Estimate.of(r))
            }
        }
    }

    // ---- @sec-simulation-output: warm-up, pilot, relative half-widths --------------------------

    fun outputAnalysis() {
        println("### pilot of 10 replications at (8, 7), and relative half-widths")
        val pilot = transformer("Pilot", { m, s -> RQSimInventory(m, 8, 7, filler = s, name = "Transformer") }).simulate(replications = 10)
        val c = pilot.store.totalCostResponse.acrossReplicationStatistic
        println("  cost: s0 %.2f, hw0 %.2f; replications for hw* = 72: %.1f".format(
            c.standardDeviation, c.halfWidth, 10.0 * (c.halfWidth / 72.0) * (c.halfWidth / 72.0)))
        val short = transformer("Short", { m, s -> RQSimInventory(m, 8, 7, filler = s, name = "Transformer") })
            .simulate(replications = 10, length = 120.0)
        val cs = short.store.totalCostResponse.acrossReplicationStatistic
        println("  T = 100 years: s0 %.2f, hw0 %.2f".format(cs.standardDeviation, cs.halfWidth))
        for ((r, q) in listOf(8 to 7, 11 to 5)) {
            val run = transformer("Rel_$r", { m, s -> RQSimInventory(m, r, q, filler = s, name = "Transformer") }).simulate()
            val p = SimulatedPerformance.of(run.store)
            println(" ($r, $q) relative half-widths: on hand %.4f, backorders %.4f, fill rate %.4f, cost %.4f".format(
                p.onHand.relativeHalfWidth, p.backorders.relativeHalfWidth, p.unitFillRate.relativeHalfWidth,
                p.totalCost.relativeHalfWidth))
        }
    }
}

fun main() {
    Section11.outputAnalysis()
    Section11.cutoutSS()
    Section11.crossing()
    Section11.lostSales()
    Section11.premium()
    Section11.undershoot()
    Section11.neighborhood()
    Section11.jobLots()
    Section11.supplyChainPackage()
}
