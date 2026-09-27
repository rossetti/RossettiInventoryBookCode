package inventory.multiechelon

/**
 * A wait at the hub, as the chapter conditions on it: a distribution function
 * on `[0, horizon]`, its first two moments, and a grid for averaging over it.
 */
interface WaitDistribution {

    /** The longest the wait can be. */
    val horizon: Double

    /** `P(W <= w)`. */
    fun cdf(w: Double): Double

    fun mean(): Double

    fun secondMoment(): Double

    fun variance(): Double = secondMoment() - mean().let { it * it }

    /**
     * The wait tabulated every [step] units of time, for conditioning on it: the
     * atom `P(W = 0)` at zero, then the mass `P(w_{k-1} < W <= w_k)` placed at the
     * midpoint of its interval. The midpoint matters. Placing each mass at the
     * right end of its interval overstates the mean wait by about half a step,
     * which on a half-week grid is a third of the mean itself.
     *
     * The mean and variance carried are the exact ones, not the grid's.
     */
    fun law(step: Double): DelayLaw {
        require(step > 0.0) { "the grid step must be positive" }
        val points = ArrayList<Pair<Double, Double>>()
        var previous = cdf(0.0)
        points += 0.0 to previous
        var left = 0.0
        while (left < horizon) {
            val right = minOf(left + step, horizon)
            val now = cdf(right)
            points += (left + right) / 2.0 to (now - previous)
            previous = now
            left = right
        }
        return DelayLaw(points, mean(), variance())
    }

    /**
     * The wait on a grid fine enough that conditioning on it reproduces the
     * continuous wait to about six decimals. The chapter's printed figures
     * condition on this.
     */
    fun fineLaw(): DelayLaw = law(horizon / 3000.0)
}

/**
 * The wait when the supplier's lead time is random: the fixed-lead-time wait at
 * each value, averaged. @sec-batchedmultiechelon-leadtime.
 *
 * With the lead time `L_0` random, the lead-time shift gives
 * `P(W <= w) = E[P(W <= w | L_0)]` provided orders arrive in the sequence placed
 * and the lead times vary independently of demand, which is Zipkin's (1986)
 * description and the condition @sec-continuousreview-batch-leadtime imposes.
 * Its moments are the averages of the conditional ones.
 *
 * @param parts pairs of (probability, the wait at that lead time value)
 */
class MixedWait(val parts: List<Pair<Double, WaitDistribution>>) : WaitDistribution {

    init {
        require(parts.isNotEmpty()) { "a mixture needs at least one part" }
        require(kotlin.math.abs(parts.sumOf { it.first } - 1.0) < 1.0e-9) { "probabilities must sum to one" }
    }

    override val horizon: Double = parts.maxOf { it.second.horizon }

    override fun cdf(w: Double): Double = parts.sumOf { (p, d) -> p * d.cdf(w) }

    override fun mean(): Double = parts.sumOf { (p, d) -> p * d.mean() }

    override fun secondMoment(): Double = parts.sumOf { (p, d) -> p * d.secondMoment() }

    companion object {
        /** The wait at [hub] for [store] (or a one-unit request) when the lead time takes the values in [lead]. */
        fun atHub(hub: BatchedHub, lead: List<Pair<Double, Double>>, store: Int? = null) =
            MixedWait(lead.map { (ell, p) -> p to HubDelay(hub.withLead(ell), store) })
    }
}
