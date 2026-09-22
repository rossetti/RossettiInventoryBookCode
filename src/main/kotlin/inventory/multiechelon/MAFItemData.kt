package inventory.multiechelon

/**
 * The efficient curve for one item, @sec-multiechelon-allocation.
 *
 * Ported from `varimetric.MAFItemData`. Three steps, in the original's order.
 *
 * 1. [makeMinBaseBOArray] fills a table of expected storeroom backorders, one
 *    row per total number of units in the system and one column per depot level.
 *    Within a column it spreads units by the rule of @sec-multiechelon-allocation-marginal.
 * 2. [makeAlphaHatArray] takes the best depot level for each total, which is the
 *    row minimum. This is where convexity is lost, @sec-multiechelon-allocation-convexity.
 * 3. [computeScValues] keeps only the points on the lower convex hull, by
 *    repeatedly jumping to whichever later point gives the steepest descent.
 */
class MAFItemData(
    depotLevels: IntArray,
    private val alphaTol: Double,
    private val sMax: Int,
    item: VMItem,
) {
    val itemNumber: Int = item.itemNumber
    val cost: Double = item.unitCost

    private val dLevels = depotLevels.copyOf()
    private val lowerLimit = dLevels.first()
    private val increment = if (dLevels.size > 1) dLevels[1] - dLevels[0] else 1
    private val maxCols = dLevels.size
    private val maxRows = sMax - lowerLimit + 1

    private val minEBBO = Array(maxRows) { DoubleArray(maxCols) { Double.MAX_VALUE } }

    /** The best depot level for each total. */
    val dStar = IntArray(maxRows)

    /** The least storeroom backorders achievable for each total. */
    val alphaHat = DoubleArray(maxRows)

    /** The totals that survive convexification, and their backorders. */
    val scValues = IntArray(maxRows)
    val alphaHatcValues = DoubleArray(maxRows)
    var totalNumberOfConvexPoints: Int = 0
        private set

    internal var lastConvexIndex: Int = 0

    init {
        require(alphaTol > 0.0) { "the tolerance must be > 0" }
        require(sMax > 0) { "the number of rows must be > 0" }
        computeCurve(item)
    }

    fun computeCurve(item: VMItem) {
        makeMinBaseBOArray(item)
        makeAlphaHatArray()
        computeScValues()
    }

    private fun makeMinBaseBOArray(item: VMItem) {
        for (r in minEBBO) r.fill(Double.MAX_VALUE)
        for (i in 0 until maxCols) {
            val d = dLevels[i]
            item.stockLevel = d
            item.clearBaseStockLevels()
            minEBBO[d - lowerLimit][i] = item.totalBaseExpectedBackOrders
            for (s in d + 1..sMax) {
                val bi = item.neediestBase
                bi.stockLevel = bi.stockLevel + 1
                minEBBO[s - lowerLimit][i] = item.totalBaseExpectedBackOrders
            }
        }
    }

    private fun makeAlphaHatArray() {
        for (j in 0 until maxRows) {
            var k = 0
            for (c in 1 until maxCols) if (minEBBO[j][c] < minEBBO[j][k]) k = c
            dStar[j] = k * increment + lowerLimit
            alphaHat[j] = minEBBO[j][k]
        }
    }

    private fun slope(a: Int, b: Int): Double = (alphaHat[a] - alphaHat[b]) / (b - a).toDouble()

    /** The lower convex hull, by the original's steepest-descent scan. */
    private fun computeScValues() {
        var i = 0
        var kIndex = 0
        var kLast = 0
        scValues[0] = lowerLimit
        alphaHatcValues[0] = alphaHat[0]
        totalNumberOfConvexPoints = 1
        while (kLast != maxRows - 1) {
            var slopeMax = 0.0
            for (c in kIndex + 1 until maxRows) {
                val s = slope(kIndex, c)
                if (s >= slopeMax) { slopeMax = s; kLast = c }
            }
            if (kLast <= kIndex) break
            kIndex = kLast
            i++
            scValues[i] = kLast + lowerLimit
            alphaHatcValues[i] = alphaHat[kLast]
            totalNumberOfConvexPoints = i + 1
        }
    }

    /** @eq-delta-value, the delta value of the next step along the hull. */
    internal val nextMarginalBenefit: Double
        get() = if (lastConvexIndex < totalNumberOfConvexPoints - 1) {
            (alphaHatcValues[lastConvexIndex] - alphaHatcValues[lastConvexIndex + 1]) /
                (cost * (scValues[lastConvexIndex + 1] - scValues[lastConvexIndex]).toDouble())
        } else {
            Double.MIN_VALUE
        }

    companion object {
        fun createDepotLevels(lowerLimit: Int, upperLimit: Int, increment: Int = 1): IntArray {
            require(lowerLimit >= 0) { "the minimum depot stock level must be >= 0" }
            require(upperLimit > lowerLimit) { "the maximum must exceed the lower limit" }
            require(increment > 0) { "the search increment must be > 0" }
            require(increment <= upperLimit - lowerLimit) { "the step must be <= the span" }
            return IntArray((upperLimit - lowerLimit) / increment + 1) { lowerLimit + it * increment }
        }
    }
}
