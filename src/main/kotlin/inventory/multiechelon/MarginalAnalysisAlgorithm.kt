package inventory.multiechelon

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Algorithm Allocate of @sec-multiechelon-allocation, across items and both levels.
 *
 * Ported from `varimetric.VMFMarginalAnalysisAlgorithm`. Each item's curve is
 * built and convexified once by [MAFItemData]; the loop then repeatedly spends
 * on whichever item offers the largest delta value of @eq-delta-value, and stops
 * when the next step will not fit the budget.
 */
class MarginalAnalysisAlgorithm(
    private val model: VariMetricModel,
    private val budget: Double,
    private val budgetTolerance: Double,
    depotLowerLimits: IntArray? = null,
    depotUpperLimits: IntArray? = null,
    increments: IntArray? = null,
    sMax: IntArray? = null,
) {
    private val items: List<VMItem> = model.items
    private val n = items.size
    private val data: Array<MAFItemData>
    private val marginalBenefits = DoubleArray(n)

    var totalCost: Double = 0.0
        private set
    var totalExpectedBackOrders: Double = 0.0
        private set
    var budgetExceeded: Boolean = false
        private set
    var noConvexPoint: Boolean = false
        private set

    /** One step of the merge: which item, how many units, and at what delta. */
    data class Buy(
        val itemNumber: Int,
        val units: Int,
        val cost: Double,
        val delta: Double,
        val cumulativeCost: Double,
        val totalUnits: Int,
    )

    private val log = ArrayList<Buy>()

    /** The buy sequence, which is the efficient curve of @sec-multiechelon-allocation-items. */
    val buys: List<Buy> get() = log

    init {
        val lo = IntArray(n); val hi = IntArray(n); val inc = IntArray(n); val top = IntArray(n)
        if (depotLowerLimits == null || depotUpperLimits == null || increments == null || sMax == null) {
            // The original's default window: centred on the depot pipeline mean,
            // widened by a multiple of its standard deviation, and capped by what
            // the budget could buy of this item alone.
            items.forEachIndexed { i, vi ->
                val cdf = vi.leadTimeDemand
                val mu = cdf.mean()
                val sd = cdf.standardDeviation()
                val fmu = floor(mu).toInt()
                val spread = ceil(100.0 * sd).toInt()
                val affordable = floor(budget / vi.unitCost).toInt()
                val maxS: Int
                val minS: Int
                if (affordable <= fmu) {
                    maxS = affordable
                    minS = maxOf(0, maxS - spread)
                } else {
                    maxS = minOf(affordable, fmu + spread)
                    minS = maxOf(0, fmu - spread)
                }
                lo[i] = minS; hi[i] = maxS; top[i] = maxS; inc[i] = 1
            }
        } else {
            depotLowerLimits.copyInto(lo); depotUpperLimits.copyInto(hi)
            increments.copyInto(inc); sMax.copyInto(top)
        }
        data = Array(n) { i ->
            MAFItemData(MAFItemData.createDepotLevels(lo[i], hi[i], inc[i]), 0.01, top[i], items[i])
        }
    }

    fun optimize() {
        log.clear()
        totalCost = 0.0
        totalExpectedBackOrders = 0.0
        for (i in 0 until n) {
            totalCost += data[i].cost * data[i].scValues[0]
            totalExpectedBackOrders += data[i].alphaHatcValues[0]
        }
        if (totalCost >= budget + budgetTolerance) {
            budgetExceeded = true
        } else {
            for (i in 0 until n) marginalBenefits[i] = data[i].nextMarginalBenefit
        }
        while (!noConvexPoint && !budgetExceeded) {
            var pick = 0
            for (i in 1 until n) if (marginalBenefits[i] > marginalBenefits[pick]) pick = i
            if (marginalBenefits[pick] > Double.MIN_VALUE) updatePerformance(pick) else noConvexPoint = true
        }
        applyOptimalSolution()
        // The original leaves the rejected step folded into its running totals.
        // Reading them back off the solution removes that and costs nothing.
        totalCost = model.totalStockingCost
        totalExpectedBackOrders = model.totalBaseExpectedBackOrders
    }

    private fun updatePerformance(k: Int) {
        val d = data[k]
        val j = d.lastConvexIndex
        val step = d.cost * (d.scValues[j + 1] - d.scValues[j])
        if (totalCost + step > budget + budgetTolerance) {
            budgetExceeded = true
            return
        }
        totalCost += step
        totalExpectedBackOrders -= (d.alphaHatcValues[j] - d.alphaHatcValues[j + 1])
        log.add(Buy(d.itemNumber, d.scValues[j + 1] - d.scValues[j], step,
            marginalBenefits[k], totalCost, d.scValues[j + 1]))
        d.lastConvexIndex = j + 1
        marginalBenefits[k] = d.nextMarginalBenefit
    }

    /** Replays the chosen totals onto the model, @sec-multiechelon-allocation-marginal spreading again. */
    private fun applyOptimalSolution() {
        items.forEachIndexed { j, vi ->
            val d = data[j]
            val s = d.scValues[d.lastConvexIndex]
            val depot = d.dStar[s - d.scValues[0]]
            vi.stockLevel = depot
            vi.clearBaseStockLevels()
            for (k in depot + 1..s) {
                val bi = vi.neediestBase
                bi.stockLevel = bi.stockLevel + 1
            }
        }
    }
}
