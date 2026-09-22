package inventory.multiitem

import inventory.lotsizing.models.EconomicOrderQuantity
import inventory.multiitem.network.SerialChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every guard added after the parameter-validation audit.
 *
 * A guard nobody tests is a guard somebody deletes. Each case here is an input that
 * previously produced a plausible wrong number rather than an error.
 */
class ValidationTest {

    private fun sku(h: Double = 1.0) = SKU("x", demandRate = 100.0, orderCost = 10.0, holdingRate = h)

    // ---- interval rules ----------------------------------------------------

    @Test
    fun `a rule refuses a base period that is not positive`() {
        listOf(0.0, -1.0).forEach { t ->
            assertFailsWith<IllegalArgumentException> { PowerOfTwoMultiple(t) }
            assertFailsWith<IllegalArgumentException> { IntegerMultiple(t) }
            assertFailsWith<IllegalArgumentException> { CommonCycle(t) }
            assertFailsWith<IllegalArgumentException> { AnyInterval(t) }
        }
    }

    @Test
    fun `a rule refuses an infinite preferred interval`() {
        val rule = PowerOfTwoMultiple(1.0 / 52.0)
        assertFailsWith<IllegalArgumentException> {
            rule.implementable(Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> { rule.implementable(0.0) }
    }

    /**
     * The shift computing two to the exponent silently wrapped for exponents of 32 and
     * above, so an interval of a billion years came back as 0.3077 rather than as an
     * error. The cap is what stops a wrapped shift looking like an answer.
     */
    @Test
    fun `a rule refuses an exponent its arithmetic cannot represent`() {
        val rule = PowerOfTwoMultiple(1.0 / 52.0)
        assertEquals(36, ceilingExponent(1.0e9, 1.0 / 52.0))
        assertTrue(rule.implementable(1.0e9) > 1.0e9,
            "a rounded interval is never shorter than the one it rounds")

        val tiny = PowerOfTwoMultiple(1.0e-300)
        assertFailsWith<IllegalArgumentException> { tiny.exponentFor(1.0) }
    }

    /** The exponent the chapter's @eq-pow2-ell asks for, computed independently. */
    private fun ceilingExponent(preferred: Double, basePeriod: Double): Int =
        kotlin.math.ceil(
            kotlin.math.ln(preferred / (kotlin.math.sqrt(2.0) * basePeriod)) / kotlin.math.ln(2.0)
        ).toInt()

    // ---- locations that add no value ---------------------------------------

    /**
     * A chain may hold two neighbours at the same rate, which makes the upper one's
     * echelon rate zero. Its interval alone was infinite, and rounding an infinite
     * interval produced a negative one.
     */
    @Test
    fun `a location whose echelon rate is zero has no interval of its own`() {
        val chain = SerialChain(listOf(
            SKU("1", 500.0, 10.0, 0.60),
            SKU("2", 500.0, 0.35, 0.35),
            SKU("3", 500.0, 25.0, 0.35),
        ))
        assertEquals(0.0, chain.echelonRate(chain.skus[1]), 0.0)
        assertFailsWith<IllegalArgumentException> { chain.ratio(chain.skus[1]) }
    }

    // ---- SKU arithmetic ----------------------------------------------------

    @Test
    fun `a SKU refuses quantities and intervals that are not positive`() {
        val s = sku()
        assertFailsWith<IllegalArgumentException> { s.orderFrequencyAt(0.0) }
        assertFailsWith<IllegalArgumentException> { s.intervalFor(0.0) }
        assertFailsWith<IllegalArgumentException> { s.quantityFor(0.0) }
        assertFailsWith<IllegalArgumentException> { s.quantityFor(Double.POSITIVE_INFINITY) }
    }

    // ---- methods -----------------------------------------------------------

    @Test
    fun `a search refuses a precision or an iteration count it cannot use`() {
        assertFailsWith<IllegalArgumentException> { LagrangianSearch(precision = 0.0) }
        assertFailsWith<IllegalArgumentException> { LagrangianSearch(maximumIterations = 0) }
        assertFailsWith<IllegalArgumentException> { ProportionalScaling(carryingCharge = 0.0) }
    }

    @Test
    fun `a base period search needs at least one grid point`() {
        val family = OrderFamily(Portfolio(listOf(sku())), majorSetupCost = 100.0)
        assertFailsWith<IllegalArgumentException> {
            family.bestBasePeriod(gridPoints = 0) { PowerOfTwoMultiple(it) }
        }
    }

    // ---- Chapter 3 ---------------------------------------------------------

    @Test
    fun `the order quantity formula refuses a holding rate of zero`() {
        assertFailsWith<IllegalArgumentException> {
            EconomicOrderQuantity.orderQuantityFor(
                orderCost = 20.0, demandRate = 100.0, holdingRate = 0.0)
        }
    }
}
