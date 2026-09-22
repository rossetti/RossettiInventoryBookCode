package inventory.newsvendor

import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.Poisson
import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.random.rvariable.NegativeBinomialRV
import ksl.utilities.statistic.Statistic
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Generates every figure printed in Chapter 7.
 *
 * Regenerate with `scripts/chapter7-listings.sh` after any change that could move
 * one, and compare the output against the chapter.
 */
fun main() {
    fun rule(title: String) { println(); println("### $title") }

    val s = FinalBuy.sellingPrice
    val c = FinalBuy.unitCost
    val u = FinalBuy.salvageValue
    val co = FinalBuy.overageCost
    val cu = FinalBuy.underageCost
    val ratio = cu / (cu + co)

    rule("7.2 the decision worked by hand")
    println("  co = %.0f - %.0f = %.0f, cu = %.0f - %.0f = %.0f, critical ratio %.6f"
        .format(c, u, co, s, c, cu, ratio))
    val v = FinalBuy.coarseOutcomes
    val p = FinalBuy.coarseProbabilities
    val mean = v.indices.sumOf { v[it] * p[it] }
    println("  sketch mean %.1f belts".format(mean))
    var cum = 0.0
    val cdf = p.map { cum += it; cum }
    println("  %-8s %-8s %-10s %-12s %-12s %-12s".format("from", "to", "P(D>=to)", "gain", "P(D<=from)", "net"))
    for (i in 0 until v.size - 1) {
        val ge = v.indices.filter { v[it] >= v[i + 1] }.sumOf { p[it] }
        val gain = cu * ge * 100.0
        val loss = co * cdf[i] * 100.0
        println("  %-8d %-8d %-10.2f %-12.2f %-12.2f %+12.2f  %s"
            .format(v[i], v[i + 1], ge, gain, cdf[i], gain - loss, if (gain > loss) "raise" else "stop"))
    }
    for (i in v.indices) {
        val profit = v.indices.sumOf { j ->
            p[j] * (s * minOf(v[j], v[i]) + u * maxOf(0, v[i] - v[j]) - c * v[i])
        }
        println("  Q = %4d  expected profit %10.2f   F(Q) %.4f".format(v[i], profit, cdf[i]))
    }

    rule("7.3 the weekly history, and what it is fitted with")
    val history = FinalBuy.weeklyHistory()
    val n = history.size
    val weeklyStats = DemandFitting.summary(history)
    val weeklyMean = weeklyStats.average
    val weeklyVar = weeklyStats.variance
    val sorted = history.sorted()
    println("  n %d  mean %.4f  variance %.4f  sd %.4f  cv %.4f  vmr %.4f"
        .format(n, weeklyMean, weeklyVar, sqrt(weeklyVar), sqrt(weeklyVar) / weeklyMean,
            weeklyVar / weeklyMean))
    println("  min %d  median %.1f  max %d  zero weeks %d"
        .format(sorted.first(), DemandFitting.quartiles(history).median, sorted.last(),
            history.count { it == 0 }))
    println("  PMFModeler:")
    for (res in DemandFitting.fitAll(history)) {
        println(if (res.success) "    ${res.parameters}".replace("\n", "  ")
        else "    FAILED: ${res.message?.trim()}")
    }
    val (pHat, rHat) = DemandFitting.momentEstimates(weeklyMean, weeklyVar)
    println("  method of moments by hand: p %.6f  r %.6f".format(pHat, rHat))
    val weeklyFit = NegativeBinomial(pHat, rHat)
    val nbGof = DemandFitting.goodnessOfFit(history, weeklyFit)
    val poGof = DemandFitting.goodnessOfFit(history, Poisson(weeklyMean))
    println("  chi-squared, negative binomial: statistic %.4f on %d df, p-value %.4f"
        .format(nbGof.chiSquaredTestStatistic, nbGof.chiSquaredTestDOF, nbGof.chiSquaredPValue))
    println("  chi-squared, Poisson:           statistic %.4f on %d df, p-value %.4f"
        .format(poGof.chiSquaredTestStatistic, poGof.chiSquaredTestDOF, poGof.chiSquaredPValue))

    rule("7.7 the final buy, continuous demand")
    val weeks = FinalBuy.weeksRemaining.toDouble()
    val mu = weeks * weeklyMean
    val variance = weeks * weeklyVar
    val sd = sqrt(variance)
    println("  over %.0f weeks: mean %.4f  variance %.4f  sd %.4f  cv %.4f"
        .format(weeks, mu, variance, sd, sd / mu))
    println("  one belt is %.2f%% of the standard deviation".format(100.0 / sd))
    val z = Normal(0.0, 1.0).invCDF(ratio)
    val demand = Normal(mu, variance)
    val q = mu + z * sd
    println("  z %.6f   Q* = %.4f + %.6f(%.4f) = %.4f".format(z, mu, z, sd, q))
    val shortage = demand.firstOrderLossFunction(q)
    val leftover = q - mu + shortage
    val cost = co * leftover + cu * shortage
    println("  expected shortage %.4f  expected leftover %.4f".format(shortage, leftover))
    println("  expected cost %.4f, check (cu+co)G1 + co(Q-mu) = %.4f"
        .format(cost, (cu + co) * shortage + co * (q - mu)))
    println("  expected profit (s-c)mu - cost = %.4f   cash committed %.2f"
        .format((s - c) * mu - cost, c * q))
    // Priced at the mean ROUNDED to whole belts, because that is the order a
    // reader could actually place and the quantity the workbook starts on.
    val roundedMean = mu.roundToInt().toDouble()
    val atMean = (cu + co) * demand.firstOrderLossFunction(roundedMean) + co * (roundedMean - mu)
    println("  cost at Q = %.0f, the mean in whole belts: %.4f, which is %.4f%% above the optimum"
        .format(roundedMean, atMean, 100.0 * (atMean / cost - 1.0)))
    println("  cost at Q = 700: %.2f".format(
        (cu + co) * demand.firstOrderLossFunction(700.0) + co * (700.0 - mu)))
    println("  the normal puts %.3e below zero over the horizon, and %.4f below zero weekly"
        .format(demand.cdf(0.0), Normal(weeklyMean, weeklyVar).cdf(0.0)))
    println("  lognormal Q* %.4f   gamma Q* %.4f"
        .format(Lognormal(mu, variance).invCDF(ratio),
            Gamma(mu * mu / variance, variance / mu).invCDF(ratio)))
    // The sum of independent negative binomials with a common p is negative
    // binomial, so the fitted weekly model gives the horizon EXACTLY. That makes
    // the continuous answer above an approximation to something computable.
    val horizon = NegativeBinomial(pHat, weeks * rHat)
    val qExact = (0..2000).first { horizon.cdf(it.toDouble()) >= ratio }
    println("  exact negative binomial: NB(p=%.6f, r=%.6f), mean %.4f, variance %.4f"
        .format(pHat, weeks * rHat, horizon.mean(), horizon.variance()))
    println("    F(%d) = %.6f is short, F(%d) = %.6f clears, so Q* = %d"
        .format(qExact - 1, horizon.cdf((qExact - 1).toDouble()),
            qExact, horizon.cdf(qExact.toDouble()), qExact))
    // Priced at WHOLE belts. The KSL's discrete loss functions are exact at
    // integer arguments and wrong at fractional ones, and a fractional belt was
    // never orderable anyway. See the note in @sec-newsvendor-continuous.
    for ((label, candidate) in listOf(
        "exact" to qExact,
        "normal" to q.roundToInt(),
        "lognormal" to Lognormal(mu, variance).invCDF(ratio).roundToInt(),
        "gamma" to Gamma(mu * mu / variance, variance / mu).invCDF(ratio).roundToInt())) {
        val g1 = horizon.firstOrderLossFunction(candidate.toDouble())
        val exactCost = (cu + co) * g1 + co * (candidate - horizon.mean())
        println("    %-10s Q = %d costs %.4f under the exact model".format(label, candidate, exactCost))
    }

    rule("7.8 the spare transformer, discrete demand")
    val lambda = 6.0
    val tc = 3800.0
    val tu = 900.0
    val te = 9500.0
    val tco = tc - tu
    val tcu = te - tc
    val tRatio = tcu / (tcu + tco)
    println("  co %.0f  cu %.0f  critical ratio %.6f".format(tco, tcu, tRatio))
    val poisson = Poisson(lambda)
    for (qq in 4..10) {
        val g1 = poisson.firstOrderLossFunction(qq.toDouble())
        println("    Q %2d  F(Q) %.6f  expected shortage %.4f  expected cost %10.2f"
            .format(qq, poisson.cdf(qq.toDouble()), g1, tco * (qq - lambda + g1) + tcu * g1))
    }
    println("  Q* = %d".format((0..50).first { poisson.cdf(it.toDouble()) >= tRatio }))
    println("  normal approximation %.4f, which rounds to %d"
        .format(lambda + Normal(0.0, 1.0).invCDF(tRatio) * sqrt(lambda),
            (lambda + Normal(0.0, 1.0).invCDF(tRatio) * sqrt(lambda)).roundToInt()))
    val cheaper = 8150.0
    val ratio2 = (cheaper - tc) / ((cheaper - tc) + tco)
    println("  at an emergency price of %.0f the ratio is %.4f, exact Q* = %d, normal %.4f rounds to %d"
        .format(cheaper, ratio2, (0..50).first { poisson.cdf(it.toDouble()) >= ratio2 },
            lambda + Normal(0.0, 1.0).invCDF(ratio2) * sqrt(lambda),
            (lambda + Normal(0.0, 1.0).invCDF(ratio2) * sqrt(lambda)).roundToInt()))

    rule("7.9 extensions")
    val penalty = 40.0
    val cuWithPenalty = cu + penalty
    val ratioWithPenalty = cuWithPenalty / (cuWithPenalty + co)
    val qPenalty = mu + Normal(0.0, 1.0).invCDF(ratioWithPenalty) * sd
    println("  a goodwill penalty of %.0f makes cu %.0f, the ratio %.6f and Q* %.4f"
        .format(penalty, cuWithPenalty, ratioWithPenalty, qPenalty))
    val onHand = 120.0
    println("  with %.0f belts already on hand the order is max(0, %.4f - %.0f) = %.4f"
        .format(onHand, q, onHand, maxOf(0.0, q - onHand)))

    rule("7.10 the same answer by simulation")
    // Sample from the FITTED model, not from the normal approximation to it.
    // Demand for the belt is a count, the simulation can sample counts, and
    // doing so removes the truncation at zero that a normal would need.
    val simRV = NegativeBinomialRV(pHat, weeks * rHat, streamNum = 1)
    val exactCost = { qq: Int ->
        (cu + co) * horizon.firstOrderLossFunction(qq.toDouble()) + co * (qq - horizon.mean())
    }
    for (candidate in listOf(440, 460, qExact, 490, 510)) {
        // Reset before each candidate, so every row sees the SAME demands. That
        // makes the comparison across rows a paired one and makes any single row
        // reproducible on its own.
        simRV.resetStartStream()
        val stat = Statistic("profit at %d".format(candidate))
        repeat(100_000) {
            val d = simRV.value
            stat.collect(s * minOf(d, candidate.toDouble()) + u * maxOf(0.0, candidate - d) - c * candidate)
        }
        println("  Q %4d  estimated profit %10.2f  half-width %7.2f  closed form %10.2f".format(
            candidate, stat.average, stat.halfWidth, (s - c) * horizon.mean() - exactCost(candidate)))
    }
}
