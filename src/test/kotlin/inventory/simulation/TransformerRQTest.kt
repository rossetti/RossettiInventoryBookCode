package inventory.simulation

import inventory.continuousreview.LeadTimeDemand
import inventory.continuousreview.RQModel
import inventory.continuousreview.RQPolicy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * @exm-sim-rq-transformer: the transformer at (8, 7) against the exact measures of
 * @exm-optimize-transformer, with the standard run settings of
 * @sec-simulation-output-runs. Every interval must cover its exact value, the nine
 * intervals being built at the Bonferroni level so that they pass together at 95%.
 */
class TransformerRQTest {

    private val exact = RQModel(
        demandRate = 45.0, orderCost = 220.0, holdingCost = 950.0, backorderCost = 8550.0,
        leadTimeDemand = LeadTimeDemand.poisson(7.5),
    ).evaluate(RQPolicy(8, 7))

    private val simulated: SimulatedPerformance by lazy {
        val m = Model("TransformerRQ")
        val supplier = LeadTimeSupplier(m, ConstantRV(1.0 / 6.0), name = "Supplier")
        val store = RQSimInventory(m, 8, 7, filler = supplier, name = "Transformer").apply {
            orderCost = 220.0; holdingCost = 950.0; backorderCost = 8550.0
        }
        CustomerDemand(m, store, ExponentialRV(1.0 / 45.0, streamNum = 1), name = "Crews")
        m.numberOfReplications = 30
        m.lengthOfReplication = 1020.0
        m.lengthOfReplicationWarmUp = 20.0
        m.simulate()
        SimulatedPerformance.of(store, Estimate.bonferroni(9))
    }

    @Test
    fun `the exact column is chapter 8's`() {
        assertEquals(4.6617, exact.expectedOnHand, 5e-5)
        assertEquals(0.1617, exact.expectedBackorders, 5e-5)
        assertEquals(0.8781, exact.readyRate, 5e-5)
        assertEquals(7225.15, exact.totalCost, 5e-3)
    }

    @Test
    fun `every interval covers its exact value`() {
        val agreements = simulated.against(exact)
        agreements.forEach { println(it) }
        val missed = agreements.filterNot { it.covered }
        assertTrue(missed.isEmpty(), "not covered: $missed")
    }

    @Test
    fun `the ready rate and fill rate agree, by PASTA`() {
        val gap = kotlin.math.abs(simulated.readyRate.average - simulated.unitFillRate.average)
        assertTrue(gap <= simulated.readyRate.halfWidth + simulated.unitFillRate.halfWidth)
    }
}
