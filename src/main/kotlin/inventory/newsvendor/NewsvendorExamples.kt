package inventory.newsvendor

import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.Poisson
import ksl.utilities.random.rvariable.NegativeBinomialRV
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The examples of @sec-newsvendor-design-usage, in the order the section runs them.
 *
 *     ./gradlew run -PmainClass=inventory.newsvendor.NewsvendorExamplesKt
 */
fun main() {
    fun rule(title: String) { println(); println("### $title") }

    rule("the final buy, against the normal the Newsvendor sheet uses")
    val belt = SinglePeriodCosts(price = 95.0, cost = 50.0, salvage = 12.0)
    val horizon = DemandFitting.fittedHorizon()
    val normal = Normal(horizon.mean(), horizon.variance())
    println(solve(belt, normal))

    rule("the final buy, against the exact negative binomial")
    println(solve(belt, horizon))

    rule("what the choice of family costs")
    val families = listOf(
        "normal" to normal,
        "lognormal" to Lognormal(horizon.mean(), horizon.variance()),
        "gamma" to Gamma(horizon.mean() * horizon.mean() / horizon.variance(), horizon.variance() / horizon.mean()),
        "exact" to horizon,
    )
    for ((name, model) in families) {
        val q = optimalLevel(belt, model).roundToInt().toDouble()
        println("%-10s Q = %4.0f   expected cost under the exact model %9.2f"
            .format(name, q, evaluate(belt, horizon, q).expectedCost))
    }

    rule("the spare transformer")
    val transformer = SinglePeriodCosts(price = 9500.0, cost = 3800.0, salvage = 900.0)
    val failures = Poisson(6.0)
    for (q in 4..8) {
        val e = evaluate(transformer, failures, q.toDouble())
        println("Q %d  F(Q) %.4f  expected shortage %.4f  expected cost %9.2f"
            .format(q, e.probabilityOfLeftovers, e.expectedShortage, e.expectedCost))
    }
    println(solve(transformer, failures))

    for (tier in listOf(100.0, 25.0)) twoTier(belt, horizon, tier)
}

/** The scrap dealer takes the first [tier] belts at 12 and the rest at 4, @sec-newsvendor-simwhen. */
private fun twoTier(belt: SinglePeriodCosts, horizon: ksl.utilities.distributions.NegativeBinomial, tier: Double) {
    println(); println("### the two-tier scrap price: the first %.0f belts at 12, the rest at 4".format(tier))
    val twoTier = { q: Double, d: Double ->
        val left = max(q - d, 0.0)
        belt.price * min(d, q) + 12.0 * min(left, tier) + 4.0 * max(left - tier, 0.0) - belt.cost * q
    }
    val demand = NegativeBinomialRV(horizon.probOfSuccess, horizon.numSuccesses, streamNum = 1)
    // The same profit, summed over the mass function rather than sampled: the check.
    val summed = { q: Double -> (0..3000).sumOf { x -> horizon.pmf(x) * twoTier(q, x.toDouble()) } }
    println("%5s %12s %10s %12s".format("Q", "estimate", "half-width", "summed"))
    var best = 0.0
    var bestProfit = Double.NEGATIVE_INFINITY
    for (q in 420..500 step 10) {
        val stat = estimate(demand, q.toDouble(), 100_000, twoTier)
        println("%5d %12.2f %10.2f %12.2f".format(q, stat.average, stat.halfWidth, summed(q.toDouble())))
        if (stat.average > bestProfit) { bestProfit = stat.average; best = q.toDouble() }
    }
    println("best on the grid: Q = %.0f".format(best))
    val exactBest = (380..520).maxBy { summed(it.toDouble()) }
    println("best by the summed profit, one belt at a time: Q = %d, profit %.2f; at 469 it is %.2f"
        .format(exactBest, summed(exactBest.toDouble()), summed(469.0)))
}
