package inventory.requirementsplanning

import inventory.dynamiclotsizing.LotForLot
import inventory.dynamiclotsizing.LotSizingRule

/**
 * The distributor of Chapter 5, seen as a network instead of a single stock point.
 * @sec-mrpdrp-drp.
 *
 * A distribution network is a bill of material and the code says so: a warehouse's
 * gross requirements are the sum over the locations it supplies of one times their
 * planned order releases, which is the explosion of @sec-mrpdrp-explosion with every quantity per
 * equal to one. The graph means something different and has the same shape, so
 * [BillOfMaterial] and [RequirementsPlan] carry it unchanged.
 *
 * What is a parent in a bill of material is a CUSTOMER here, and what is a child is the
 * warehouse that supplies it. The arrow points the way the requirement travels, which is
 * upstream in both cases.
 */
object BeltNetwork {

    /** The belt demand of Chapter 5, split across three regions. */
    val regionDemand: Map<String, List<Double>> = mapOf(
        "N" to listOf(20.0, 30.0, 60.0, 140.0, 180.0, 110.0, 50.0, 20.0, 0.0, 40.0, 80.0, 100.0),
        "S" to listOf(10.0, 20.0, 40.0, 100.0, 160.0, 100.0, 50.0, 10.0, 0.0, 20.0, 60.0, 70.0),
        "W" to listOf(10.0, 10.0, 20.0, 60.0, 80.0, 50.0, 20.0, 10.0, 0.0, 20.0, 40.0, 50.0),
    )

    private val transit = mapOf("N" to 1, "S" to 2, "W" to 1, "CW" to 2)
    private val labels = mapOf(
        "N" to "Northern region", "S" to "Southern region",
        "W" to "Western region", "CW" to "Central warehouse",
    )

    /** The cumulative lead time through the deepest branch: a region plus the centre. */
    val cumulativeLeadTime: Int = transit.getValue("S") + transit.getValue("CW")

    fun network(regionRule: LotSizingRule = LotForLot, centreRule: LotSizingRule = LotForLot):
        BillOfMaterial = BillOfMaterial(
        (regionDemand.keys + "CW").map { id ->
            PlannedItem(
                id = id, label = labels.getValue(id), leadTime = transit.getValue(id),
                policy = PlanTheHorizon(if (id == "CW") centreRule else regionRule),
                orderCost = if (id == "CW") 400.0 else 200.0,
                unitCost = 50.0, carryingCharge = 0.02,
            )
        },
        regionDemand.keys.map { BillOfMaterial.Usage(it, "CW", 1.0) },
    )

    fun plan(regionRule: LotSizingRule = LotForLot, centreRule: LotSizingRule = LotForLot):
        RequirementsPlan {
        val pad = cumulativeLeadTime
        return RequirementsPlan(network(regionRule, centreRule),
            regionDemand.mapValues { List(pad) { 0.0 } + it.value })
    }
}
