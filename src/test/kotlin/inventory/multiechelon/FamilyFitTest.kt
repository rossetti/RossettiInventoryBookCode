package inventory.multiechelon

import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.distributions.Poisson
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The family rule of @sec-multiechelon-varimetric-fit, with no band around one. */
class FamilyFitTest {

    private fun days(d: Double) = d / 365.0

    private fun storeroom(depotStock: Int, baseStock: Int = 4): VMBaseItem {
        val item = VMItem(1, depotStock, 3800.0, days(60.0))
        repeat(4) { item.addBaseItem(45.0, days(30.0), 0.3, days(10.0), baseStock, 3800.0) }
        item.stockLevel = depotStock
        return item.baseItems[0]
    }

    private fun close(got: Double, want: Double, tol: Double = 5e-5) =
        assertTrue(abs(got - want) < tol, "expected $want, got $got")

    @Test
    fun `the ratio alone picks the family`() {
        assertIs<NegativeBinomial>(fitTwoMoments(2.0, 2.02))
        assertIs<NegativeBinomial>(fitTwoMoments(2.0, 2.0 * 1.0641))
        assertIs<Poisson>(fitTwoMoments(2.0, 2.0))
        assertIs<Gamma>(fitTwoMoments(2.0, 1.98))
    }

    @Test
    fun `rounding noise at a ratio of one is still a Poisson`() {
        // At an empty depot the two moments agree exactly, but are computed by subtraction.
        val b = storeroom(depotStock = 0)
        assertTrue(abs(b.varianceNumInResupply / b.expectedNumInResupply - 1.0) < RATIO_TOLERANCE)
        assertIs<Poisson>(b.leadTimeDemand)
    }

    @Test
    fun `near one the negative binomial converges to the Poisson, so no band is needed`() {
        val mu = 2.0
        val poisson = Poisson(mu).firstOrderLossFunction(4.0)
        val nb = negativeBinomialOn(mu, mu * (1.0 + 1e-6)).firstOrderLossFunction(4.0)
        close(nb, poisson, 1e-6)
    }

    @Test
    fun `inside the old band the storeroom is fitted with its own variance`() {
        // @tbl-delay-transformer's depot levels where the ratio is 1.0641 and 1.0302.
        // A Poisson there gives METRIC's 0.0964 and 0.0825; simulation gives
        // 0.1101 and 0.0886, which the negative binomial matches.
        val at24 = storeroom(depotStock = 24)
        close(at24.varianceNumInResupply / at24.expectedNumInResupply, 1.0641, 5e-4)
        assertIs<NegativeBinomial>(at24.leadTimeDemand)
        close(at24.expectedBackOrders, 0.1095)
        close(storeroom(depotStock = 26).expectedBackOrders, 0.0883)
        // At S_0 = 8, just inside the old band, the plan the band preferred.
        close(storeroom(depotStock = 8, baseStock = 1).expectedBackOrders * 4.0, 16.6327, 5e-4)
    }

    @Test
    fun `backorders vary smoothly with depot stock`() {
        // The band made storeroom backorders jump where the ratio crossed 1.1.
        // Without it each extra depot unit lowers them, and by a shrinking amount.
        val b = (0..30).map { storeroom(depotStock = it).expectedBackOrders }
        val drops = b.zipWithNext { x, y -> x - y }
        assertTrue(drops.all { it > 0.0 }, "storeroom backorders should fall with depot stock")
        val after = drops.drop(16)
        assertTrue(after.zipWithNext().all { (x, y) -> y < x }, "past the peak ratio the drops should shrink")
    }
}
