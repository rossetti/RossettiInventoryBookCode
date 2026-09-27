package inventory.multiechelon

import inventory.continuousreview.LeadTimeDemand
import inventory.continuousreview.RQModel

/**
 * A storeroom whose lead time is transit plus the wait at the hub, `O_j + W`,
 * with Poisson customers who each want one unit. @sec-batchedmultiechelon-exact-routes.
 *
 * Three ways to turn that random lead time into a lead time demand:
 *
 * - [Route.MEAN_ONLY]: Poisson with mean `lambda (E[O] + E[W])`. Chapter 9's
 *   METRIC move. For a one-for-one storeroom it is what Palm's theorem gives if
 *   each unit's lead time were drawn independently.
 * - [Route.TWO_MOMENT]: a negative binomial with that mean and variance
 *   `lambda E[L] + lambda^2 Var[L]`. Chapter 9's VARI-METRIC move.
 * - [Route.CONDITIONED]: condition on the lead time's value, then average. The
 *   mixture over `(o, w)` of Poisson with mean `lambda (o + w)`. It is chapter
 *   8's random lead time, which treats the lead time as independent of the
 *   demand that falls in it and assumes orders do not overtake one another.
 *
 * None of the three is exact. [AxsaterBatchOrdering] is, where its assumptions
 * hold, and that is what they are measured against.
 *
 * @param lambda the storeroom's demand rate
 * @param transit the transit time's values and probabilities
 * @param delay the wait at the hub, as [HubDelay.law] tabulates it
 * @param costs the storeroom's cost rates
 */
class StoreroomWithDelay(
    val lambda: Double,
    val transit: List<Pair<Double, Double>>,
    val delay: DelayLaw,
    val costs: RQCosts,
) {
    constructor(lambda: Double, transit: Double, delay: DelayLaw, costs: RQCosts) :
        this(lambda, listOf(transit to 1.0), delay, costs)

    init {
        require(lambda > 0.0) { "the demand rate must be positive" }
        require(kotlin.math.abs(transit.sumOf { it.second } - 1.0) < 1.0e-9) { "transit probabilities must sum to one" }
    }

    enum class Route { MEAN_ONLY, TWO_MOMENT, CONDITIONED }

    private val transitMean = transit.sumOf { it.first * it.second }
    private val transitVariance = transit.sumOf { (it.first - transitMean).let { d -> d * d } * it.second }

    /** `E[O + W]`. */
    val meanLead: Double = transitMean + delay.mean

    /** `Var[O + W]`, with transit and wait independent. */
    val leadVariance: Double = transitVariance + delay.variance

    fun leadTimeDemand(route: Route): LeadTimeDemand {
        val mean = lambda * meanLead
        return when (route) {
            Route.MEAN_ONLY -> LeadTimeDemand.poisson(mean)
            Route.TWO_MOMENT -> {
                val variance = mean + lambda * lambda * leadVariance
                if (variance <= mean * (1.0 + 1.0e-12)) LeadTimeDemand.poisson(mean)
                else LeadTimeDemand.negativeBinomial(mean, variance)
            }
            Route.CONDITIONED -> {
                // The mixture's mass function, summed directly: a lead time of zero
                // contributes all its weight at zero demand.
                var masses = DoubleArray(1)
                for ((o, po) in transit) for ((w, pw) in delay.points) {
                    val p = po * pw
                    if (p <= 0.0) continue
                    val part = poissonPmf(lambda * (o + w))
                    if (part.size > masses.size) masses = masses.copyOf(part.size)
                    for (x in part.indices) masses[x] += p * part[x]
                }
                LeadTimeDemand.tabulated(masses, "conditioned on the lead time")
            }
        }
    }

    fun model(route: Route): RQModel =
        RQModel(lambda, costs.orderCost, costs.holdingCost, costs.backorderCost, leadTimeDemand(route))
}
