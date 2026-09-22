package inventory.requirementsplanning

/**
 * The explosion of @sec-mrpdrp-explosion: every item's record, computed level by level.
 *
 * The master production schedule gives the end items their gross requirements. Every
 * other item's gross requirements are computed, by multiplying each parent's planned
 * order RELEASES by the quantity per and summing over parents. Releases and not
 * receipts, because the parent has to have the component in hand when it starts, and
 * the release is when it starts.
 *
 * Items are planned in low-level-code order, which is what makes the sum over parents
 * safe: by the time an item is planned, every parent of it already has a record.
 */
class RequirementsPlan(
    val bom: BillOfMaterial,
    val masterSchedule: Map<String, List<Double>>,
) {
    init {
        require(masterSchedule.isNotEmpty()) { "A plan needs a master production schedule" }
        masterSchedule.keys.forEach {
            require(it in bom.items) { "$it is scheduled but is not in the bill of material" }
            require(it in bom.endItems) { "$it is scheduled but something else consumes it" }
        }
        require(masterSchedule.values.map { it.size }.distinct().size == 1) {
            "Every scheduled item needs the same horizon"
        }
    }

    val horizon: Int = masterSchedule.values.first().size

    val records: Map<String, MrpRecord> by lazy {
        val done = LinkedHashMap<String, MrpRecord>()
        for (id in bom.planningOrder()) {
            done[id] = MrpRecord(bom.items.getValue(id), grossRequirementsFor(id, done))
        }
        done
    }

    /**
     * An item's gross requirements: what the master schedule says for an end item, and
     * otherwise what its parents' releases imply.
     */
    private fun grossRequirementsFor(id: String, planned: Map<String, MrpRecord>): List<Double> {
        masterSchedule[id]?.let { return it }
        val parents = bom.parentsOf(id)
        require(parents.isNotEmpty()) {
            "${bom.items.getValue(id).label} has no parent and no master schedule"
        }
        return (1..horizon).map { t ->
            parents.sumOf { usage ->
                val parent = planned[usage.parent]
                    ?: error("${usage.parent} was not planned before $id, so the " +
                             "low-level codes are wrong")
                usage.quantity * parent.releaseIn(t)
            }
        }
    }

    fun recordFor(id: String): MrpRecord = records.getValue(id)

    /** Setup plus carrying over every item, which is what @sec-mrpdrp-lotsizing compares. */
    val relevantCost: Double get() = records.values.sumOf { it.relevantCost }

    /** Receipts the lead times push before period 1, summed over items. */
    val pastDueReleases: Double get() = records.values.sumOf { it.pastDueReleases }

    override fun toString(): String =
        "RequirementsPlan(${records.size} records, horizon $horizon, cost %.2f".format(relevantCost) + ")"
}
