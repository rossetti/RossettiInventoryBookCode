package inventory.multiitem.network

import inventory.multiitem.SKU
import kotlin.math.sqrt

/**
 * A central warehouse supplying several regions. @sec-multiitem-distribution.
 *
 * The nesting constraint runs the other way from a chain: no region may wait longer than
 * the centre feeding it. Sorting the regions by their own ratio turns the construction
 * into a single scan, which is why the sort is its own method: the algorithm's
 * correctness rests on the ordering, and folding the sort into the scan would hide that.
 */
class DistributionNetwork(val centre: SKU, val regions: List<SKU>) :
    SupplyNetwork(listOf(centre) + regions, regions.associateWith { centre }) {

    init {
        require(regions.isNotEmpty()) { "A distribution network needs at least one region" }
        regions.forEach {
            require(it.holdingRate >= centre.holdingRate) {
                "${it.label} holds below the centre, so it adds no value"
            }
        }
    }

    /** The regions in ascending order of their own ratio. The scan's precondition. */
    fun ranked(): List<SKU> = regions.sortedBy { ratio(it) }

    /**
     * The centre together with the regions that must share its interval. Working down
     * from the region wanting the longest interval, a region joins while leaving it out
     * would be infeasible, and the first that fails stops the scan.
     */
    fun pinnedSet(): List<SKU> {
        val order = ranked()
        var setup = centre.orderCost
        var holding = holdingCoefficient(centre)
        val pinned = mutableListOf<SKU>()
        for (index in order.indices.reversed()) {
            val candidate = order[index]
            if (ratio(candidate) <= setup / holding) break
            pinned.add(candidate)
            setup += candidate.orderCost
            holding += holdingCoefficient(candidate)
        }
        return pinned.reversed()
    }

    /** The interval the centre and everything pinned to it share. */
    fun centralInterval(): Double {
        val pinned = pinnedSet()
        val setup = centre.orderCost + pinned.sumOf { it.orderCost }
        val holding = holdingCoefficient(centre) + pinned.sumOf { holdingCoefficient(it) }
        return sqrt(setup / holding)
    }

    override fun relaxedIntervals(): List<Double> {
        val shared = centralInterval()
        val pinned = pinnedSet().toSet()
        return skus.map { if (it === centre || it in pinned) shared else unconstrainedInterval(it) }
    }
}
