package inventory.newsvendor

import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.distributions.Poisson
import ksl.utilities.distributions.fitting.DiscretePMFGoodnessOfFit
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PMFModeler
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic
import ksl.utilities.toDoubles
import kotlin.math.sqrt

/**
 * @sec-newsvendor-fitting, getting a demand distribution.
 *
 * The steps of the section, one function each, in the order the section works
 * them. The listings the chapter prints are the bodies of these functions.
 *
 * The procedure is the one Chapter 2 and Appendix B of *Simulation Modeling
 * using the KSL* lay out: look at the data before modeling it, hypothesize a
 * family from what you saw, estimate its parameters, and test the fit. What
 * differs here is only that demand for a spare part is a COUNT, so everything
 * is discrete: PMFModeler rather than PDFModeler, an IntegerFrequency or a
 * binned Histogram rather than a continuous histogram, and a chi-squared test
 * rather than a scoring model. The KSL fits fewer discrete families than
 * continuous ones, which is why the section leans on the variance to mean ratio
 * to choose among them.
 */
object DemandFitting {

    /** The history, read from the file. */
    fun history(): IntArray = FinalBuy.weeklyHistory()

    /**
     * Step 1. Summary statistics.
     *
     * `Statistic` carries far more than the chapter prints, including the lag 1
     * correlation and the von Neumann statistic that Step 2 uses to argue the
     * weeks are independent.
     */
    fun summary(data: IntArray): Statistic = Statistic("Weekly demand", data.toDoubles())

    /**
     * Step 1, continued. The quartiles, which `Statistic` does not carry.
     *
     * The median is the one the section reads against the mean. For an even
     * number of observations it is the average of the two middle values and can
     * land on a half, which is why it is a Double and not a count.
     */
    fun quartiles(data: IntArray): BoxPlotSummary =
        BoxPlotSummary(data.toDoubles(), "Weekly demand")

    /**
     * Step 1, continued. The histogram, on bins of ten belts.
     *
     * Break points are given rather than recommended. Twelve bins of ten belts
     * cover 0 through 119, which holds every week including the largest at 113,
     * and ten is the width the Chapter 7 workbook uses. A figure in the book
     * that does not match the spreadsheet shipped beside it is worse than no
     * figure. `Histogram` also accumulates the summary statistics, so this one
     * object answers most of Step 1 by itself.
     */
    fun histogram(data: IntArray): Histogram {
        val h = Histogram(Histogram.createBreakPoints(0.0, 12, 10.0), "Weekly demand")
        h.collect(data.toDoubles())
        return h
    }

    /** Step 3. Every discrete family the KSL knows how to fit, fitted at once. */
    fun fitAll(data: IntArray): List<EstimationResult> {
        val modeler = PMFModeler(data)
        return modeler.estimateParameters(modeler.defaultEstimators)
    }

    /**
     * The method of moments estimates of @eq-newsvendor-negbinfit, written out rather than
     * called, so that the chapter can confirm what `PMFModeler` returned.
     *
     * Returns (p, r) in the success convention: the mean is r(1-p)/p, which is
     * the convention the KSL uses. See the warning in Appendix C.
     */
    fun momentEstimates(mean: Double, variance: Double): Pair<Double, Double> {
        require(variance > mean) { "a negative binomial needs a variance above its mean" }
        return Pair(mean / variance, mean * mean / (variance - mean))
    }

    /** Step 4. The chi-squared test, for a negative binomial. */
    fun goodnessOfFit(data: IntArray, dist: NegativeBinomial): DiscretePMFGoodnessOfFit =
        DiscretePMFGoodnessOfFit(
            data.toDoubles(), dist, numEstimatedParameters = 2,
            breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
        )

    /** And for the Poisson the ratio has already ruled out, to show the contrast. */
    fun goodnessOfFit(data: IntArray, dist: Poisson): DiscretePMFGoodnessOfFit =
        DiscretePMFGoodnessOfFit(
            data.toDoubles(), dist, numEstimatedParameters = 1,
            breakPoints = PMFModeler.makeZeroToInfinityBreakPoints(data.size, dist)
        )

    /** The fitted weekly model, which @sec-newsvendor-continuous aggregates over the horizon. */
    fun fittedWeekly(data: IntArray = history()): NegativeBinomial {
        val s = summary(data)
        val (p, r) = momentEstimates(s.average, s.variance)
        return NegativeBinomial(p, r)
    }

    /**
     * The fitted model over [weeks] weeks.
     *
     * A sum of independent negative binomials that share p is negative binomial
     * with the same p and the number of successes multiplied, so the horizon
     * distribution is exact rather than approximate. @sec-newsvendor-continuous uses this to
     * measure what its normal approximation costs.
     */
    fun fittedHorizon(weeks: Int = FinalBuy.weeksRemaining, data: IntArray = history()): NegativeBinomial {
        val weekly = fittedWeekly(data)
        return NegativeBinomial(weekly.probOfSuccess, weeks * weekly.numSuccesses)
    }
}

fun main() {
    val history = DemandFitting.history()
    val s = DemandFitting.summary(history)
    val b = DemandFitting.quartiles(history)
    println("n %d  mean %.4f  variance %.4f  standard deviation %.4f"
        .format(history.size, s.average, s.variance, sqrt(s.variance)))
    println("coefficient of variation %.4f   variance to mean ratio %.4f"
        .format(sqrt(s.variance) / s.average, s.variance / s.average))
    println("min %.0f  first quartile %.1f  median %.1f  third quartile %.1f  max %.0f"
        .format(b.min, b.firstQuartile, b.median, b.thirdQuartile, b.max))
    println("skewness %.4f   lag 1 correlation %.4f   von Neumann %.4f"
        .format(s.skewness, s.lag1Correlation, s.vonNeumannLag1TestStatistic))
    println("weeks with no demand %d".format(history.count { it == 0 }))

    println()
    println(DemandFitting.histogram(history))

    println()
    for (result in DemandFitting.fitAll(history)) {
        println(if (result.success) "${result.parameters}" else "FAILED: ${result.message}")
    }

    val (p, r) = DemandFitting.momentEstimates(s.average, s.variance)
    println()
    println("p = mean/variance          = %.6f".format(p))
    println("r = mean^2/(variance-mean) = %.6f".format(r))

    println()
    println(DemandFitting.goodnessOfFit(history, NegativeBinomial(p, r)).chiSquaredTestResults())
    println(DemandFitting.goodnessOfFit(history, Poisson(s.average)).chiSquaredTestResults())
}
