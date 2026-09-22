package inventory.multiitem.network

import inventory.multiitem.SKU
import kotlin.math.sqrt

/** Who supplies whom in a chain: location i draws from location i+1. */
private fun upstream(chain: List<SKU>): Map<SKU, SKU> =
    (0 until chain.size - 1).associate { chain[it] to chain[it + 1] }

/** Consecutive locations that must share one reorder interval. */
class Block(val skus: List<SKU>, val interval: Double)

/**
 * A chain in which location i is supplied by location i+1, the first facing the
 * customer. @sec-multiitem-serial.
 *
 * A location may not replenish more often than the one it supplies, so the intervals
 * must not decrease up the chain. Where the locations' own preferences violate that, the
 * offending ones are merged into a block sharing one interval, and the partition into
 * blocks is the answer.
 */
class SerialChain(chain: List<SKU>) : SupplyNetwork(chain, upstream(chain)) {

    init {
        val rate = chain.first().demandRate
        require(chain.all { it.demandRate == rate }) {
            "Every location in a chain sees the same demand, because a unit consumed at " +
                "the first passed through all the others"
        }
        for (i in 0 until chain.size - 1) {
            require(chain[i].holdingRate >= chain[i + 1].holdingRate) {
                "Value accumulates down the chain, so holding rates cannot rise going upstream"
            }
        }
    }

    /**
     * Algorithm 4.1. Walk up the chain. A location wanting a longer interval than the
     * current block can stand alone. One wanting a shorter interval would break the
     * ordering, so it is absorbed, and absorbing it can drag the block below the one
     * beneath it, which a backward merge repairs.
     *
     * Mutability is the honest representation here, because the algorithm is stated as a
     * sequence of edits to a partition. Threading an immutable partition through a fold
     * would be correct and would no longer read beside the algorithm in the chapter.
     */
    fun blocks(): List<Block> {
        val built = mutableListOf(mutableListOf(skus[0]))
        for (i in 1 until skus.size) {
            val current = built.last()
            if (pooledRatio(current) <= ratio(skus[i])) {
                built.add(mutableListOf(skus[i]))
            } else {
                current.add(skus[i])
                while (built.size > 1) {
                    val upper = built[built.size - 1]
                    val lower = built[built.size - 2]
                    if (pooledRatio(lower) <= pooledRatio(upper)) break
                    lower.addAll(upper)
                    built.removeAt(built.size - 1)
                }
            }
        }
        return built.map { Block(it.toList(), sqrt(pooledRatio(it))) }
    }

    override fun relaxedIntervals(): List<Double> {
        val byBlock = blocks().flatMap { b -> b.skus.map { it to b.interval } }.toMap()
        return skus.map { byBlock.getValue(it) }
    }

    private fun pooledRatio(group: List<SKU>): Double =
        group.sumOf { it.orderCost } / group.sumOf { holdingCoefficient(it) }
}
