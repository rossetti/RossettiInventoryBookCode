package inventory.requirementsplanning

import inventory.dynamiclotsizing.RequirementsSchedule

/**
 * What the planner knows about one item, apart from what it requires. Chapter 6.
 *
 * An item's requirements are not stored here, because for every item but an end item
 * they are computed from the level above and change whenever the level above is
 * replanned. What is stored is the part of the item master that does not move: how long
 * it takes to get, what is already on hand, what is already on order, what it costs, and
 * the rule that decides how much to order.
 */
class PlannedItem(
    val id: String,
    val label: String,
    val leadTime: Int,
    val policy: ReceiptPolicy,
    val onHand: Double = 0.0,
    val scheduledReceipts: List<Double> = emptyList(),
    val safetyStock: Double = 0.0,
    val orderCost: Double = 0.0,
    val unitCost: Double = 0.0,
    val carryingCharge: Double = 0.0,
) {
    init {
        require(id.isNotBlank()) { "An item needs an identifier" }
        require(leadTime >= 0) { "$label has a lead time of $leadTime, which is before it was ordered" }
        require(onHand >= 0.0) { "$label cannot start with negative stock" }
        require(safetyStock >= 0.0) { "$label cannot hold negative safety stock" }
        require(scheduledReceipts.all { it >= 0.0 }) { "$label has a negative scheduled receipt" }
    }

    val holdingRate: Double get() = carryingCharge * unitCost

    /** What is already on order for [period], or zero. */
    fun scheduledReceiptIn(period: Int): Double =
        scheduledReceipts.getOrElse(period - 1) { 0.0 }

    /**
     * The net requirements of this item, as a Chapter 5 requirements schedule.
     *
     * This is the join between the two chapters. @sec-dls-problem
     * took a requirements schedule as given. Chapter 6 computes one, for every item at
     * every level, and hands it back to @sec-dls-heuristics unchanged.
     */
    fun scheduleFor(netRequirements: List<Double>): RequirementsSchedule =
        RequirementsSchedule.constantCosts(
            label = label,
            requirements = netRequirements,
            orderCost = orderCost,
            unitCost = unitCost,
            carryingCharge = carryingCharge,
        )

    override fun toString(): String = "$id ($label, L=$leadTime)"
}
