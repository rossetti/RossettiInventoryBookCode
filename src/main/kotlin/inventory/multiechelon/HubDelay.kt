package inventory.multiechelon

import kotlin.math.abs

/**
 * The distribution of the wait at a hub. @sec-batchedmultiechelon-delay and @sec-batchedmultiechelon-storeroom.
 *
 * The argument is the lead-time shift. A request is complete within `w` exactly
 * when it would be complete at once if the supplier were `w` faster, that is,
 * when the hub's position `L_0 - w` earlier covered all the demand since, the
 * request's own units included. It needs first-come-first-served filling with
 * partial shipments, a constant `L_0`, and `r_0 >= 0`: with a negative reorder
 * point a request can wait for an order placed after it arrived, and the
 * argument fails.
 *
 * Whose wait is set by [store]:
 *
 * - `null`, a one-unit request from a Poisson customer. It sees the hub at a
 *   random time, so the demand before it is the stationary window, and
 * ```
 *     P(W <= w) = average over positions u of P(D_0(L_0 - w) <= u - 1).
 * ```
 * - a storeroom's index, that storeroom's orders of `Q_j`. The order is placed
 *   at a moment its own position has just reached `r_j`, so its own earlier
 *   orders in the window are `floor(D'/Q_j)` ([OrderCountLaw.seenFromOrder]),
 *   while the other storerooms and the other demand are stationary. The order
 *   waits for its last unit, so it needs the position to cover `Q_j` more:
 * ```
 *     P(W_j <= w) = average over u of P(X_o + sum_{i != j} Q_i N_i + Q_j floor(D'/Q_j) <= u - Q_j).
 * ```
 *
 * In both, `u` runs over the hub's positions ([BatchedHub.positions]), which are
 * uniform and independent of the demand that follows them.
 *
 * @param hub the hub
 * @param store the storeroom whose orders wait, or null for a one-unit request
 */
class HubDelay(val hub: BatchedHub, val store: Int? = null) : WaitDistribution {

    init {
        require(hub.reorder >= 0) { "the lead-time shift needs r_0 >= 0, was ${hub.reorder}" }
        require(store == null || store in hub.stores.indices) { "no storeroom $store" }
    }

    private val lead = hub.lead

    override val horizon: Double get() = lead

    /** `P(W <= w)` for the wait this delay describes. */
    override fun cdf(w: Double): Double = if (store == null) unitRequestCdf(w) else orderCdf(store, w)

    /** `P(W <= w)` for a one-unit request from a Poisson customer. */
    fun unitRequestCdf(w: Double): Double {
        require(w >= 0.0) { "a wait cannot be negative" }
        if (w >= lead) return 1.0
        val c = cumulative(hub.demandPmf(lead - w))
        return hub.positions().map { u -> c.at(u - 1) }.average()
    }

    /** `P(W_j <= w)` for an order from storeroom [index], counted until its last unit leaves the hub. */
    fun orderCdf(index: Int, w: Double): Double {
        require(w >= 0.0) { "a wait cannot be negative" }
        if (w >= lead) return 1.0
        val q = hub.stores[index].q
        val c = cumulative(hub.demandPmfBeforeOrderOf(index, lead - w))
        return hub.positions().map { u -> c.at(u - q) }.average()
    }

    /** `E[W]`, the integral of `P(W > w)` over `[0, L_0]`. */
    override fun mean(): Double = integrate { w -> 1.0 - cdf(w) }

    /** `E[W^2]`, the integral of `2w P(W > w)` over `[0, L_0]`. */
    override fun secondMoment(): Double = integrate { w -> 2.0 * w * (1.0 - cdf(w)) }

    /** Adaptive Simpson on `[0, L_0]`. The integrand is smooth there. */
    private fun integrate(tolerance: Double = 1.0e-11, f: (Double) -> Double): Double {
        fun simpson(a: Double, fa: Double, b: Double, fb: Double, fm: Double) = (b - a) / 6.0 * (fa + 4.0 * fm + fb)

        fun recurse(a: Double, fa: Double, b: Double, fb: Double, m: Double, fm: Double,
                    whole: Double, tol: Double, depth: Int): Double {
            val lm = (a + m) / 2.0
            val rm = (m + b) / 2.0
            val flm = f(lm)
            val frm = f(rm)
            val left = simpson(a, fa, m, fm, flm)
            val right = simpson(m, fm, b, fb, frm)
            return if (depth <= 0 || abs(left + right - whole) <= 15.0 * tol) {
                left + right + (left + right - whole) / 15.0
            } else {
                recurse(a, fa, m, fm, lm, flm, left, tol / 2.0, depth - 1) +
                    recurse(m, fm, b, fb, rm, frm, right, tol / 2.0, depth - 1)
            }
        }
        val a = 0.0
        val b = lead
        val m = (a + b) / 2.0
        val fa = f(a)
        val fb = f(b)
        val fm = f(m)
        return recurse(a, fa, b, fb, m, fm, simpson(a, fa, b, fb, fm), tolerance, 40)
    }
}

/**
 * A delay tabulated for conditioning, with its exact mean and variance.
 *
 * @param points pairs of (delay, probability), summing to one
 */
data class DelayLaw(val points: List<Pair<Double, Double>>, val mean: Double, val variance: Double) {
    init {
        require(abs(points.sumOf { it.second } - 1.0) < 1.0e-9) { "delay probabilities must sum to one" }
    }
}
