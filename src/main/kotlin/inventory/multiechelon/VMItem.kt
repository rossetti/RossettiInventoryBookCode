package inventory.multiechelon

import ksl.utilities.distributions.Poisson

/**
 * One item at the depot, together with the storerooms it supplies, @sec-multiechelon-onefortone.
 *
 * Ported from `varimetric.VMItem`. The depot's own pipeline is Poisson by
 * Theorem 9.1 with no qualification, which is why this class never consults the
 * family fit its storerooms use.
 *
 * The object graph is the original's and is mutable on purpose. @sec-multiechelon-allocation
 * moves stock levels thousands of times while searching, and rebuilding the
 * network on every move would be the wrong shape for that.
 */
class VMItem(
    itemNumber: Int,
    stockLevel: Int,
    cost: Double,
    leadTime: Double,
) : BaseStockItem() {

    private val bases = ArrayList<VMBaseItem>()

    /**
     * **An addition to the original, which implements VARI-METRIC only.**
     *
     * Forces every storeroom onto a Poisson at its own mean, which is METRIC,
     * @sec-multiechelon-metric. It exists so that the chapter's two models come from one
     * object rather than from two implementations.
     */
    var forcePoissonAtBases: Boolean = false
        set(value) {
            field = value
            updateBaseLeadTimeDemands()
        }

    init {
        this.itemNumber = itemNumber
        this.leadTime = leadTime
        unitCost = cost
        super.stockLevel = stockLevel
    }

    /** Setting the depot's level moves every storeroom, @eq-varimetric-mean. */
    override var stockLevel: Int
        get() = super.stockLevel
        set(value) {
            super.stockLevel = value
            updateBaseLeadTimeDemands()
        }

    val baseItems: List<VMBaseItem> get() = bases
    val numBases: Int get() = bases.size

    fun addBaseItem(
        demandRate: Double, repairTime: Double, repairProb: Double,
        shipTime: Double, stockLevel: Int, cost: Double,
    ): VMBaseItem = addBaseItem(bases.size + 1, demandRate, repairTime, repairProb,
        shipTime, stockLevel, cost)

    fun addBaseItem(
        baseNumber: Int, demandRate: Double, repairTime: Double, repairProb: Double,
        shipTime: Double, stockLevel: Int, cost: Double,
    ): VMBaseItem {
        val bi = VMBaseItem(this, baseNumber, demandRate, repairTime, repairProb,
            shipTime, stockLevel, cost)
        bases.add(bi)
        updateDepotDemandRate()
        return bi
    }

    /** @eq-repairable-depotrate, the superposition of the storerooms' thinned streams. */
    internal fun updateDepotDemandRate() {
        demandRate = bases.sumOf { it.replenishmentDemandRate }
        updateLeadTimeDemand()
        updateBaseLeadTimeDemands()
    }

    /** @eq-depot-pipeline, Palm's theorem at the depot. */
    public override fun updateLeadTimeDemand() {
        leadTimeDemand = Poisson(demandRate * leadTime)
    }

    private fun updateBaseLeadTimeDemands() {
        for (b in bases) b.updateLeadTimeDemand()
    }

    /** @eq-allocation-objective, the objective: backorders where the crews are. */
    val totalBaseExpectedBackOrders: Double get() = bases.sumOf { it.expectedBackOrders }
    val totalBaseExpectedOnHand: Double get() = bases.sumOf { it.expectedOnHand }
    val totalBaseStockingCost: Double get() = bases.sumOf { it.stockingCost }

    val totalExpectedBackOrders: Double get() = expectedBackOrders + totalBaseExpectedBackOrders
    val totalExpectedOnHand: Double get() = expectedOnHand + totalBaseExpectedOnHand
    val totalStockingCost: Double get() = stockingCost + totalBaseStockingCost

    /** Depot level first, then one per storeroom. */
    var stockLevels: IntArray
        get() = IntArray(bases.size + 1).also { a ->
            a[0] = stockLevel
            bases.forEachIndexed { i, b -> a[i + 1] = b.stockLevel }
        }
        set(levels) {
            require(levels.size == bases.size + 1) {
                "expected ${bases.size + 1} levels, got ${levels.size}"
            }
            stockLevel = levels[0]
            bases.forEachIndexed { i, b -> b.stockLevel = levels[i + 1] }
        }

    fun clearBaseStockLevels() { for (b in bases) b.stockLevel = 0 }

    /**
     * The storeroom the next unit goes to: the one whose next unit removes the
     * most backorders, `P{X > S}` of @eq-marginal-value. The original ranked on
     * the stockout probability `P{X >= S}`, one index off, which agrees on
     * identical storerooms and need not agree in general; the exact rule is what
     * makes the inner search of @sec-multiechelon-design-searches exact.
     */
    val neediestBase: VMBaseItem get() = bases.maxBy { it.nextUnitValue }

    val minUnitCostAcrossBases: Double get() = bases.minOf { it.unitCost }
    val maxUnitCostAcrossBases: Double get() = bases.maxOf { it.unitCost }

    /** The largest multiplier at which any storeroom would still stock a unit. */
    val maxLagrangeMultiplier: Double get() = 1.0 / maxUnitCostAcrossBases

    internal fun optimizeBasesAt(theta: Double) {
        for (bi in bases) bi.stockLevel = bi.optimalLevelAt(theta)
    }

    /**
     * The item sub-problem of the Lagrangian relaxation, @sec-multiechelon-design-lagrange.
     *
     * At a fixed multiplier the storerooms separate and each reads its level off
     * @eq-lagrange-newsvendor. The depot does not separate, because moving it moves every
     * storeroom, so this enumerates the depot over a window and keeps whichever
     * level minimises
     *
     * ```
     *   w(theta) = total storeroom backorders + theta * total stocking cost
     * ```
     */
    internal fun optimizeSubProblemAt(theta: Double, minS: Int, maxS: Int, step: Int = 1): IntArray {
        require(theta > 0.0) { "the multiplier must be > 0" }
        require(minS >= 0) { "the minimum depot stock level must be >= 0" }
        require(maxS > minS) { "the maximum depot stock level must exceed the minimum" }
        require(step in 1..(maxS - minS)) { "the step must be in 1..${maxS - minS}" }

        var best = IntArray(numBases + 1)
        var wStar = Double.MAX_VALUE
        var rho = minS
        while (rho <= maxS) {
            stockLevel = rho
            optimizeBasesAt(theta)
            val w = totalBaseExpectedBackOrders + theta * totalStockingCost
            if (w < wStar) {
                wStar = w
                best = stockLevels
            }
            rho += step
        }
        stockLevels = best
        return best
    }

    companion object {
        /** Ported from the original's `newInstance`, used by the search. */
        fun copyOf(original: VMItem): VMItem {
            val clone = VMItem(original.itemNumber, original.stockLevel,
                original.unitCost, original.leadTime)
            for (bi in original.bases) {
                clone.addBaseItem(bi.baseNumber, bi.demandRate, bi.repairTime,
                    bi.repairProb, bi.shipTime, bi.stockLevel, bi.unitCost)
            }
            return clone
        }
    }

    override fun toString(): String = buildString {
        appendLine("Depot for item $itemNumber, $numBases storerooms")
        append(super.toString())
        appendLine("  storeroom totals   %10.4f backorders, %10.4f on hand, %,.2f stocked"
            .format(totalBaseExpectedBackOrders, totalBaseExpectedOnHand, totalBaseStockingCost))
        for (bi in bases) append(bi.toString())
    }
}
