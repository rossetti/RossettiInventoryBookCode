package inventory.multiechelon

/**
 * A set of items, each with a depot and the storerooms it supplies, @sec-multiechelon-allocation.
 *
 * Ported from `varimetric.VariMetricModel`.
 */
class VariMetricModel {

    private val backing = ArrayList<VMItem>()

    val items: List<VMItem> get() = backing
    val numItems: Int get() = backing.size

    fun addItem(itemNumber: Int, stockLevel: Int, cost: Double, leadTime: Double): VMItem =
        VMItem(itemNumber, stockLevel, cost, leadTime).also { addItem(it) }

    fun addItem(item: VMItem) {
        require(item !in backing) { "the item is already in the model" }
        backing.add(item)
    }

    /** @eq-allocation-objective, the objective. Backorders at the storerooms, where the crews are. */
    val totalBaseExpectedBackOrders: Double get() = backing.sumOf { it.totalBaseExpectedBackOrders }

    /**
     * Backorders at the depots alone.
     *
     * **This differs from the original.** `getTotalDepotEBackOrders` there sums
     * `VMItem.getTotalEBackOrders()`, which is a depot plus its storerooms, so
     * the grand total counted every storeroom backorder twice. Its stocking cost
     * sibling sums `getStockingCost()` and does not, which is what makes the
     * other two look like slips rather than intent.
     */
    val totalDepotExpectedBackOrders: Double get() = backing.sumOf { it.expectedBackOrders }

    val totalExpectedBackOrders: Double
        get() = totalBaseExpectedBackOrders + totalDepotExpectedBackOrders

    val totalBaseExpectedOnHand: Double get() = backing.sumOf { it.totalBaseExpectedOnHand }
    val totalDepotExpectedOnHand: Double get() = backing.sumOf { it.expectedOnHand }
    val totalExpectedOnHand: Double get() = totalBaseExpectedOnHand + totalDepotExpectedOnHand

    val totalBaseStockingCost: Double get() = backing.sumOf { it.totalBaseStockingCost }
    val totalDepotStockingCost: Double get() = backing.sumOf { it.stockingCost }
    val totalStockingCost: Double get() = totalBaseStockingCost + totalDepotStockingCost

    val minUnitCostAcrossItems: Double get() = backing.minOf { it.minUnitCostAcrossBases }

    fun clearStockLevels() {
        for (vi in backing) {
            vi.stockLevel = 0
            vi.clearBaseStockLevels()
        }
    }

    override fun toString(): String = buildString {
        appendLine("VARI-METRIC model, $numItems item(s)")
        appendLine("  stocking cost      %,12.2f".format(totalStockingCost))
        appendLine("  storeroom backorders %10.4f".format(totalBaseExpectedBackOrders))
        appendLine("  depot backorders     %10.4f".format(totalDepotExpectedBackOrders))
        for (i in backing) append(i.toString())
    }
}
