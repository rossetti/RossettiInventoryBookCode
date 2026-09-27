package inventory.multiechelon

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The delay at the hub of @sec-batchedmultiechelon-delay: four storerooms
 * one for one, each with one unit a week of Poisson demand, and a hub running
 * `(r_0, Q_0) = (9, 8)` with a three-week lead time, all in weeks.
 */
class HubDelayTest {

    private fun close(got: Double, want: Double, tol: Double = 1e-9, what: String = "") =
        assertTrue(abs(got - want) < tol, "$what expected $want, got $got")

    private val h = 28.75 / 52
    private val b = 287.50 / 52
    private val hub = BatchedHub(0.0, List(4) { StoreStream(1.0, 1) }, 9, 8, 3.0, RQCosts(0.0, h, b))
    private val delay = HubDelay(hub)

    @Test
    fun `the delay at w equal to 0, 1 and 2 weeks, from the Poisson table's means 12, 8 and 4`() {
        close(delay.unitRequestCdf(0.0), 0.6029836213, 1e-9, "P(W = 0)")
        close(delay.unitRequestCdf(1.0), 0.9116752661, 1e-9, "P(W <= 1)")
        close(delay.unitRequestCdf(2.0), 0.9984670949, 1e-9, "P(W <= 2)")
        close(delay.unitRequestCdf(3.0), 1.0, 1e-15, "P(W <= L_0)")
    }

    @Test
    fun `P(W = 0) is the hub's ready rate, since every request is a Poisson unit`() =
        close(delay.unitRequestCdf(0.0), hub.model().readyRate(9, 8), 1e-12)

    @Test
    fun `the integrated tail is Little's law`() {
        close(delay.mean(), hub.littleWait(), 1e-9)
        close(delay.mean(), 0.2568274522, 1e-9)
    }

    @Test
    fun `the second moment is the distributional Little's law, in factorial moments`() {
        // E[B(B-1)] = lambda^2 E[W^2], so Var[W] = (Var[B] - B) / lambda^2 and not Var[B] / lambda^2.
        val m = hub.model()
        val bbar = m.expectedBackorders(9, 8)
        val factorial = m.expectedBackordersSecondMoment(9, 8) - bbar
        val lambda = hub.demandRate
        close(delay.secondMoment(), factorial / (lambda * lambda), 1e-9)
        close(delay.variance(), (m.varianceBackorders(9, 8) - bbar) / (lambda * lambda), 1e-9)
        close(delay.variance(), 0.1825420459, 1e-9)
    }

    @Test
    fun `a gridded delay sums to one and keeps its mean near the exact one`() {
        for (step in listOf(0.5, 1.0 / 7)) {
            val law = delay.law(step)
            close(law.points.sumOf { it.second }, 1.0, 1e-12, "mass at step $step")
            val gridMean = law.points.sumOf { it.first * it.second }
            assertTrue(abs(gridMean - delay.mean()) < step * step, "midpoint grid mean at step $step: $gridMean")
        }
        val fine = delay.fineLaw()
        close(fine.points.sumOf { it.first * it.second }, delay.mean(), 1e-6, "fine grid mean")
    }

    @Test
    fun `a hub that holds plenty never delays anyone`() {
        val rich = HubDelay(BatchedHub(0.0, List(4) { StoreStream(1.0, 1) }, 60, 8, 3.0, RQCosts(0.0, h, b)))
        close(rich.unitRequestCdf(0.0), 1.0, 1e-12)
        close(rich.mean(), 0.0, 1e-12)
    }
}
