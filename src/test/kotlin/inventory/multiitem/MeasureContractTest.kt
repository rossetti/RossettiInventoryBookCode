package inventory.multiitem

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract every ConstrainableMeasure has to keep.
 *
 * A wrong sign in a marginal would otherwise be silent: the solver would converge on
 * something, and only the published figures would catch it. These check the marginal
 * against a numerical derivative of the measure it claims to differentiate, and the
 * closed forms against the general default they are supposed to agree with.
 */
class MeasureContractTest {

    private val skus = listOf(
        SKU.pricedAt("A", 12000.0, 20.0, 50.0, 0.30),
        SKU.pricedAt("B", 25000.0, 10.0, 50.0, 0.30),
        SKU.pricedAt("C", 8000.0, 15.0, 50.0, 0.30),
    )
    private val portfolio = Portfolio(skus)
    private val volumes = mapOf(skus[0] to 0.8, skus[1] to 0.3, skus[2] to 1.2)

    private fun measures(): List<ConstrainableMeasure> =
        listOf(AverageInvestment, SpaceOccupied(volumes), ReplenishmentWorkload)

    /** A measure delegating everything but quantityAt, so the interface default runs. */
    private class Generic(private val real: ConstrainableMeasure) : ConstrainableMeasure {
        override val name: String get() = real.name
        override val unit: String get() = real.unit
        override fun measure(plan: ReplenishmentPlan): Double = real.measure(plan)
        override fun marginalAt(sku: SKU, orderQuantity: Double): Double =
            real.marginalAt(sku, orderQuantity)
    }

    @Test
    fun `every marginal agrees with a numerical derivative of its measure`() {
        val base = portfolio.freePlan().orderQuantities
        for (measure in measures()) {
            for (j in skus.indices) {
                val q = base[j]
                val step = q * 1.0E-6
                val up = portfolio.planFor(base.toMutableList().also { it[j] = q + step })
                val down = portfolio.planFor(base.toMutableList().also { it[j] = q - step })
                val numeric = (measure.measure(up) - measure.measure(down)) / (2.0 * step)
                assertEquals(numeric, measure.marginalAt(skus[j], q), abs(numeric) * 1e-5 + 1e-9,
                    "${measure.name} marginal at ${skus[j].label}")
            }
        }
    }

    @Test
    fun `every closed form agrees with the general default`() {
        for (measure in measures()) {
            val generic = Generic(measure)
            for (price in doubleArrayOf(0.01, 0.1463, 1.0, 2.9538, 25.0)) {
                for (sku in skus) {
                    val closed = measure.quantityAt(sku, price)
                    val general = generic.quantityAt(sku, price)
                    assertEquals(closed, general, closed * 1e-6,
                        "${measure.name} at price $price for ${sku.label}")
                }
            }
        }
    }

    @Test
    fun `the workload marginal is negative and the others are not`() {
        val q = 400.0
        assertTrue(ReplenishmentWorkload.marginalAt(skus[0], q) < 0.0)
        assertTrue(AverageInvestment.marginalAt(skus[0], q) > 0.0)
        assertTrue(SpaceOccupied(volumes).marginalAt(skus[0], q) > 0.0)
    }

    @Test
    fun `a measure solved by the general default still meets its limit`() {
        val generic = Generic(SpaceOccupied(volumes))
        val held = ConstrainedLotSizing(portfolio, Constraint(generic, 800.0)).solve()
        assertEquals(800.0, generic.measure(held), 1e-4)
    }

    @Test
    fun `power of two nests and arbitrary integers need not`() {
        val family = OrderFamily(portfolio, majorSetupCost = 400.0)
        val t = family.commonCycleInterval / 4.0
        assertTrue(family.scheduleUnder(PowerOfTwoMultiple(t)).nestsOn(t))
        assertTrue(PowerOfTwoMultiple(t).multiplierFor(0.9) and
            (PowerOfTwoMultiple(t).multiplierFor(0.9) - 1) == 0,
            "a power-of-two multiplier must be a power of two")
    }

    @Test
    fun `the power of two bound of equation 4 33 holds where the exponent is free`() {
        val base = 0.05
        val rule = PowerOfTwoMultiple(base)
        // @sec-multiitem-joint-bound states the bound for a non-negative exponent, which requires the
        // preferred interval to be at least the base period over root two. Below that a
        // location wants to order faster than the base period allows, it is clamped at
        // an exponent of zero, and the base period rather than the rounding governs.
        val floor = base / kotlin.math.sqrt(2.0)
        for (preferred in generateSequence(floor) { it * 1.07 }.takeWhile { it < 3.0 }) {
            val k = 40.0; val g = k / (preferred * preferred)
            val cost = { t: Double -> k / t + g * t }
            val excess = cost(rule.implementable(preferred)) / cost(preferred)
            assertTrue(excess <= PowerOfTwoMultiple.WORST_CASE + 1e-9,
                "excess $excess exceeded the bound at preferred $preferred")
        }
    }

    @Test
    fun `below the base period the exponent clamps at zero`() {
        val rule = PowerOfTwoMultiple(0.05)
        listOf(0.006, 0.01, 0.02, 0.03).forEach {
            assertEquals(0, rule.exponentFor(it), "preferred $it")
            assertEquals(0.05, rule.implementable(it), 1e-12)
        }
    }
}
