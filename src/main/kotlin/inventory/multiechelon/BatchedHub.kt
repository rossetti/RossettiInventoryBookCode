package inventory.multiechelon

import inventory.continuousreview.LeadTimeDemand
import inventory.continuousreview.RQModel

/** The three cost rates of chapter 8's `(r, Q)` cost, per unit time. */
data class RQCosts(val orderCost: Double, val holdingCost: Double, val backorderCost: Double)

/** A storeroom as the hub sees it: its customers' rate and the quantity it orders. */
data class StoreStream(val lambda: Double, val q: Int) {
    init {
        require(lambda > 0.0) { "a storeroom's demand rate must be positive" }
        require(q >= 1) { "a storeroom's order quantity must be at least one" }
    }
}

/**
 * A hub running `(r_0, Q_0)` that serves storerooms and other demand, all from
 * Poisson customers who each want one unit. @sec-batchedmultiechelon-hub and @sec-batchedmultiechelon-hubgeneral.
 *
 * The hub's demand over a window of length `l` is the other demand plus each
 * storeroom's orders counted in whole batches,
 *
 * ```
 *   D_0(l) = X_o(l) + sum over j of Q_j N_j(l),
 * ```
 *
 * with independent terms, since each storeroom's position is uniform and
 * independent of the others'. Its distribution is a convolution. When every
 * storeroom orders one at a time, `D_0(l)` is Poisson and the hub is exactly the
 * location of @sec-continuousreview-batch.
 *
 * @param otherRate the rate of one-unit demands at the hub from elsewhere
 * @param stores the storerooms the hub supplies
 * @param reorder `r_0`
 * @param batch `Q_0`
 * @param lead `L_0`, the supplier's lead time to the hub
 * @param costs the hub's cost rates
 */
class BatchedHub(
    val otherRate: Double,
    val stores: List<StoreStream>,
    val reorder: Int,
    val batch: Int,
    val lead: Double,
    val costs: RQCosts,
) {
    init {
        require(otherRate >= 0.0) { "the other demand rate cannot be negative" }
        require(stores.isNotEmpty() || otherRate > 0.0) { "the hub must have some demand" }
        require(batch >= 1) { "the hub's order quantity must be at least one" }
        require(lead > 0.0) { "the hub's lead time must be positive" }
    }

    /** `lambda_0`, the hub's demand rate in units per unit time. */
    val demandRate: Double = otherRate + stores.sumOf { it.lambda }

    /** True when every storeroom orders one at a time, so the hub sees Poisson demand. */
    val seesPoisson: Boolean = stores.all { it.q == 1 }

    /** The same hub with a different supplier lead time. */
    fun withLead(lead: Double) = BatchedHub(otherRate, stores, reorder, batch, lead, costs)

    /**
     * The mass functions of each stream's demand over a window of length [ell]
     * opening at a random time: the other demand first, then each storeroom's
     * orders counted in units.
     */
    fun streamPmfs(ell: Double): List<DoubleArray> {
        require(ell >= 0.0) { "a window cannot have negative length" }
        return listOf(poissonPmf(otherRate * ell)) + stores.map { s ->
            if (s.q == 1) poissonPmf(s.lambda * ell) else OrderCountLaw(s.lambda, s.q).unitsStationary(ell)
        }
    }

    /** The mass function of the hub's demand over a window of length [ell] opening at a random time. */
    fun demandPmf(ell: Double): DoubleArray {
        require(ell >= 0.0) { "a window cannot have negative length" }
        if (seesPoisson) return poissonPmf(demandRate * ell)
        return streamPmfs(ell).reduce { a, b -> convolve(a, b) }
    }

    /**
     * The hub's lead time demand when the supplier's lead time takes the values in
     * [lead] with their probabilities: build the demand at each value, then average.
     * The streams share the lead time, so they are convolved at each value before
     * the average is taken, never after.
     */
    fun demandPmf(lead: List<Pair<Double, Double>>): DoubleArray =
        mixPmfs(lead.map { (ell, p) -> p to demandPmf(ell) })

    /**
     * The wrong way, kept so the chapter can show what it costs: average each
     * stream over the lead time on its own, then convolve as if the streams were
     * counted over independent windows. It keeps each stream's own law and loses
     * the covariance a shared lead time creates.
     */
    fun demandPmfStreamsAveragedSeparately(lead: List<Pair<Double, Double>>): DoubleArray {
        val perValue = lead.map { (ell, p) -> p to streamPmfs(ell) }
        val streams = perValue.first().second.indices.map { s -> mixPmfs(perValue.map { (p, list) -> p to list[s] }) }
        return streams.reduce { a, b -> convolve(a, b) }
    }

    private fun mixPmfs(parts: List<Pair<Double, DoubleArray>>): DoubleArray {
        require(kotlin.math.abs(parts.sumOf { it.first } - 1.0) < 1.0e-9) { "probabilities must sum to one" }
        val out = DoubleArray(parts.maxOf { it.second.size })
        for ((p, pmf) in parts) for (x in pmf.indices) out[x] += p * pmf[x]
        return out
    }

    /**
     * The two-moment recipe: the hub's lead time demand over [ell] matched to its
     * mean and to the variance `lambda_o l + sum_j (lambda_j l + Q_j^2/6)`, a
     * storeroom that orders one at a time contributing `lambda_j l`. The `Q^2/6`
     * is the rounding to whole batches: the average of `x(1-x)` for x uniform on
     * [0, 1], times `Q^2`.
     */
    fun recipe(ell: Double): LeadTimeDemand {
        val (mean, variance) = recipeMoments(ell)
        return LeadTimeDemand.matched(mean, variance)
    }

    /**
     * The recipe's two moments before a family is fitted to them. The fit can
     * discard some of the variance: when the ratio of variance to mean is near one,
     * @sec-continuousreview-ltd-moments chooses the Poisson.
     *
     * For unit Poisson customers the exact extra variance of a long window is
     * `(Q^2 - 1)/6`, so over long windows the recipe is high by exactly `1/6`.
     */
    fun recipeMoments(ell: Double): Pair<Double, Double> {
        val mean = demandRate * ell
        val variance = otherRate * ell + stores.sumOf { s -> s.lambda * ell + if (s.q == 1) 0.0 else s.q * s.q / 6.0 }
        return mean to variance
    }

    /**
     * The shortcut to warn against: each storeroom's orders treated as Poisson
     * arrivals of `Q_j` units at rate `lambda_j / Q_j`, so its term in the variance
     * is `lambda_j Q_j l`, `Q_j` times its customers' own.
     */
    fun compoundPoissonShortcut(ell: Double): LeadTimeDemand {
        val mean = demandRate * ell
        val variance = otherRate * ell + stores.sumOf { s -> s.lambda * s.q * ell }
        return LeadTimeDemand.matched(mean, variance)
    }

    /**
     * The hub's demand over a window of length [ell] that ends at an order from
     * storeroom [index], not counting that order: the other demand and the other
     * storerooms as a random time sees them, and the storeroom's own earlier
     * orders as its order sees them ([OrderCountLaw.seenFromOrder]).
     */
    fun demandPmfBeforeOrderOf(index: Int, ell: Double): DoubleArray {
        require(index in stores.indices) { "no storeroom $index" }
        require(ell >= 0.0) { "a window cannot have negative length" }
        var pmf = poissonPmf(otherRate * ell)
        for ((i, s) in stores.withIndex()) {
            val part = when {
                i == index -> scale(OrderCountLaw(s.lambda, s.q).seenFromOrder(ell), s.q)
                s.q == 1 -> poissonPmf(s.lambda * ell)
                else -> OrderCountLaw(s.lambda, s.q).unitsStationary(ell)
            }
            pmf = convolve(pmf, part)
        }
        return pmf
    }

    /**
     * The values the hub's inventory position takes, each equally likely and
     * independent of the demand that follows.
     *
     * When any demand at the hub comes one unit at a time, the position visits
     * every value from `r_0 + 1` to `r_0 + Q_0` (@sec-continuousreview-batch-position).
     * When the hub's only demand is storeroom orders of a common size `Q`, the
     * position moves in steps of `Q` and visits only the multiples of `Q` in that
     * band, which is why Axsäter counts the hub in storeroom batches. That case
     * needs `Q` to divide both `r_0` and `Q_0`. Batches of different sizes with no
     * unit demand are not covered.
     */
    fun positions(): List<Int> {
        if (otherRate > 0.0 || stores.any { it.q == 1 }) return (reorder + 1..reorder + batch).toList()
        val q = stores.first().q
        require(stores.all { it.q == q }) { "storerooms with different batches and no unit demand are not covered" }
        require(batch % q == 0 && Math.floorMod(reorder, q) == 0) {
            "with only batches of $q at the hub, r_0 and Q_0 must be multiples of $q"
        }
        return (1..batch / q).map { reorder + it * q }
    }

    /** `D_0(L_0)`, the hub's lead time demand: Poisson when [seesPoisson], the exact convolution otherwise. */
    fun leadTimeDemand(): LeadTimeDemand =
        if (seesPoisson) LeadTimeDemand.poisson(demandRate * lead)
        else LeadTimeDemand.tabulated(demandPmf(lead), "hub convolution")

    /**
     * The hub as a chapter 8 location. Its measures agree with the position-averaged
     * ones below whenever [positions] is the full band from `r_0 + 1` to `r_0 + Q_0`.
     */
    fun model(): RQModel =
        RQModel(demandRate, costs.orderCost, costs.holdingCost, costs.backorderCost, leadTimeDemand())

    /**
     * `B_0`, the hub's expected backorders, averaged over [positions], against
     * lead time demand [d]: the hub's own by default, or any of the approximations.
     */
    fun expectedBackorders(d: LeadTimeDemand = leadTimeDemand()): Double =
        positions().map { d.lossFirst(it.toDouble()) }.average()

    /** `I_0`, the hub's expected on-hand, averaged over [positions]. */
    fun expectedOnHand(d: LeadTimeDemand = leadTimeDemand()): Double =
        positions().map { it - d.mean + d.lossFirst(it.toDouble()) }.average()

    /** The hub's ready rate, the chance it has stock at a random time, averaged over [positions]. */
    fun readyRate(d: LeadTimeDemand = leadTimeDemand()): Double =
        positions().map { d.cdf(it - 1.0) }.average()

    /** The hub's holding and backorder cost per unit time at its policy, against lead time demand [d]. */
    fun holdingAndBackorderCost(d: LeadTimeDemand = leadTimeDemand()): Double =
        costs.holdingCost * expectedOnHand(d) + costs.backorderCost * expectedBackorders(d)

    /** The mean wait by Little's law, `B_0 / lambda_0`, averaged over every unit requested. */
    fun littleWait(): Double = expectedBackorders() / demandRate
}
