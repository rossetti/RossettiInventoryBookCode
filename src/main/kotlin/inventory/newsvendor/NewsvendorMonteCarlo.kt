package inventory.newsvendor

import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.Statistic

/**
 * Monte Carlo evaluation of a newsvendor order quantity.
 *
 * The critical ratio gives the optimal order quantity in closed form, so this
 * program is not how you would *solve* the newsvendor problem. It is how you
 * check a solution, and how you evaluate a quantity the closed form does not
 * cover, a demand distribution without a convenient inverse, a salvage rule
 * that is not linear, a constraint on the order.
 *
 * Profit on a single period, ordering [orderQuantity] units against demand D:
 *
 *     profit = price * min(D, Q) + salvage * max(Q - D, 0) - cost * Q
 *
 * See @sec-newsvendor.
 */
class NewsvendorModel(
    val unitCost: Double,
    val sellingPrice: Double,
    val salvageValue: Double,
    val demandMean: Double,
    val demandStdDev: Double,
    streamNum: Int = 1
) {
    init {
        require(sellingPrice > unitCost) { "selling price must exceed unit cost" }
        require(unitCost > salvageValue) { "unit cost must exceed salvage value" }
        require(demandStdDev > 0.0) { "demand standard deviation must be positive" }
    }

    // NormalRV takes a VARIANCE, not a standard deviation.
    private val demandRV = NormalRV(demandMean, demandStdDev * demandStdDev, streamNum)

    /** Profit for one simulated period at the given order quantity. */
    fun simulateProfit(orderQuantity: Double): Double {
        val demand = maxOf(0.0, demandRV.value)          // truncate: demand cannot be negative
        val sold = minOf(demand, orderQuantity)
        val leftOver = orderQuantity - sold
        return sellingPrice * sold + salvageValue * leftOver - unitCost * orderQuantity
    }

    /** Estimates expected profit at [orderQuantity] over [replications] periods. */
    fun estimateExpectedProfit(orderQuantity: Double, replications: Int = 10_000): Statistic {
        val stat = Statistic("Profit at Q = $orderQuantity")
        repeat(replications) {
            stat.collect(simulateProfit(orderQuantity))
        }
        return stat
    }
}

fun main() {
    // The final buy of the mower deck belt, the normal case of @sec-newsvendor:
    // the belt costs $50, sells for $95 and salvages for $12, and the season's
    // demand is fitted as normal with mean 465.37 and standard deviation 92.64.
    // The critical ratio gives Q* = 475.18 there.
    val model = NewsvendorModel(
        unitCost = 50.0,
        sellingPrice = 95.0,
        salvageValue = 12.0,
        demandMean = 465.37,
        demandStdDev = 92.64
    )

    println("Expected profit by order quantity (10,000 replications each)")
    println("%10s %14s %12s".format("Q", "avg profit", "half-width"))

    var bestQ = 0.0
    var bestProfit = Double.NEGATIVE_INFINITY
    for (q in 400..560 step 20) {
        val stat = model.estimateExpectedProfit(q.toDouble())
        println("%10d %14.2f %12.2f".format(q, stat.average, stat.halfWidth))
        if (stat.average > bestProfit) {
            bestProfit = stat.average
            bestQ = q.toDouble()
        }
    }

    println()
    println("Best quantity on this grid: Q = %.0f, expected profit %.2f".format(bestQ, bestProfit))
    println("Compare against the critical ratio solution -- see @sec-newsvendor.")
}
