package inventory.multiechelon

import kotlin.math.exp

/**
 * The exact cost of ordering one for one at both levels: a hub and [stores]
 * identical storerooms, Poisson demand at each storeroom, constant lead times,
 * and first-come-first-served filling at the hub. @sec-batchedmultiechelon-exact-oneforone.
 *
 * This is the recursion of Axsäter (1990) as summarized in the Appendix of
 * Axsäter (1993), equations (A.1) to (A.13), extended there to negative
 * inventory positions. Everything in [AxsaterBatchOrdering] is an average of
 * [cost] over positions, so this class is the one place the arithmetic of the
 * two-level system lives.
 *
 * Notation follows the paper. `sHub` is the hub's inventory position `S_w` and
 * `sStore` a storeroom's `S_r`, both in units. The cost is per unit of time for
 * the whole system: holding at the hub, holding at every storeroom, and a
 * shortage cost per unit backordered per unit of time at the storerooms. The hub
 * carries no shortage cost of its own, because a unit short at the hub costs
 * nothing until a storeroom is short of it.
 *
 * Every term of the recursion is linear in the three cost rates, so [measures]
 * recovers the expected stock levels by evaluating the same recursion with one
 * rate set to one and the others to zero.
 *
 * @param storeRate the demand rate at each storeroom, `lambda_r`
 * @param stores the number of storerooms, `N`
 * @param transit the lead time from the hub to a storeroom, `L_r`
 * @param hubLead the lead time from the supplier to the hub, `L_w`
 * @param storeHolding the holding cost per unit per unit time at a storeroom, `h_r`
 * @param hubHolding the holding cost per unit per unit time at the hub, `h_w`
 * @param shortage the cost per unit backordered per unit time at a storeroom, `beta`
 * @param epsilon the tail probability below which (A.9) is applied, (A.10)
 */
class OneForOneTwoLevel(
    val storeRate: Double,
    val stores: Int,
    val transit: Double,
    val hubLead: Double,
    val storeHolding: Double,
    val hubHolding: Double,
    val shortage: Double,
    val epsilon: Double = 1.0e-12,
) {
    init {
        require(storeRate > 0.0) { "the storeroom demand rate must be positive" }
        require(stores >= 1) { "there must be at least one storeroom" }
        require(transit >= 0.0 && hubLead >= 0.0) { "lead times cannot be negative" }
        require(storeHolding >= 0.0 && hubHolding >= 0.0 && shortage >= 0.0) {
            "cost rates cannot be negative"
        }
    }

    /** `lambda_w`, the demand rate the hub sees: every storeroom demand, one for one. */
    val hubRate: Double = storeRate * stores

    /** `G^n` of (A.4) for n = 0, 1, ..., the chance of n or more hub demands in `L_w`. */
    private val tail: DoubleArray

    /** `S-bar_w` of (A.10), above which (A.9) replaces the recursion. */
    val highestHubPosition: Int

    init {
        val mean = hubRate * hubLead
        val masses = ArrayList<Double>()
        var p = exp(-mean)
        var below = 0.0
        var n = 0
        // Walk the Poisson mass up until the tail beyond n is below epsilon.
        while (1.0 - below >= epsilon || n <= mean) {
            masses.add(p)
            below += p
            n += 1
            p *= mean / n
        }
        val g = DoubleArray(masses.size + 1)
        var above = 1.0
        for (k in masses.indices) {
            g[k] = above
            above -= masses[k]
        }
        g[masses.size] = maxOf(above, 0.0)
        tail = g
        highestHubPosition = g.indexOfFirst { it < epsilon }.let { if (it < 0) g.lastIndex else it }
    }

    /** `G^n`, (A.4). One for n at or below zero, since zero or more demands is certain. */
    fun hubTail(n: Int): Double = when {
        n <= 0 -> 1.0
        n < tail.size -> tail[n]
        else -> 0.0
    }

    /** `pi^{S_r}` of (A.1) and (A.2): the cost per item at a storeroom whose supplier never runs out. */
    fun storeCostPerItem(sStore: Int): Double {
        val lateness = shortage * (transit - sStore / storeRate)
        if (sStore <= 0) return lateness
        val a = storeRate * transit
        var term = exp(-a)
        var sum = 0.0
        for (k in 0 until sStore) {
            sum += (sStore - k) * term
            term *= a / (k + 1)
        }
        return (storeHolding + shortage) / storeRate * sum + lateness
    }

    /** `gamma(S_w)` of (A.5) and (A.6): the hub's holding cost per item. */
    fun hubCostPerItem(sHub: Int): Double {
        if (sHub <= 0) return 0.0
        return hubHolding * sHub / hubRate * (1.0 - hubTail(sHub + 1)) -
            hubHolding * hubLead * (1.0 - hubTail(sHub))
    }

    /**
     * Columns of `Pi^{S_r}(S_w)` for `S_r > 0`, one per storeroom position, each
     * walked down from [highestHubPosition]. Entry t of column `S_r` is the value
     * at `S_w = highestHubPosition - t`. Columns grow downward on demand.
     */
    private val columns = HashMap<Int, ArrayList<Double>>()

    /** `Pi^{S_r}(S_w)`: the expected holding and shortage cost per item at a storeroom. */
    fun storeCostPerItem(sStore: Int, sHub: Int): Double {
        if (sStore <= 0) return closedFormNonPositive(sStore, sHub)
        if (sHub >= highestHubPosition) return storeCostPerItem(sStore) // (A.9)
        val column = column(sStore, sHub)
        return column[highestHubPosition - sHub]
    }

    /** (A.7) and (A.8), which need no recursion because the storeroom never holds stock. */
    private fun closedFormNonPositive(sStore: Int, sHub: Int): Double {
        val atStore = shortage * (transit - sStore / storeRate)
        return if (sHub > 0) {
            hubTail(sHub) * shortage * hubLead -
                hubTail(sHub + 1) * shortage * sHub / hubRate + atStore
        } else {
            shortage * (hubLead + transit - sHub / hubRate - sStore / storeRate)
        }
    }

    /** Extends column `sStore` down to `sHub` by (A.11) and (A.12). */
    private fun column(sStore: Int, sHub: Int): ArrayList<Double> {
        val col = columns.getOrPut(sStore) { arrayListOf(storeCostPerItem(sStore)) }
        val share = storeRate / hubRate
        val step = storeCostPerItem(sStore) - storeCostPerItem(sStore - 1)
        while (highestHubPosition - (col.size - 1) > sHub) {
            val s = highestHubPosition - (col.size - 1) // the S_w of the last entry
            val below = storeCostPerItem(sStore - 1, s)
            var next = share * below + (1.0 - share) * col.last()
            if (s > 0) next += share * (1.0 - hubTail(s)) * step // (A.11); absent in (A.12)
            col.add(next)
        }
        return col
    }

    /** `mu^{S_r}(S_w)` of (A.3): the total cost per item. */
    fun costPerItem(sHub: Int, sStore: Int): Double =
        storeCostPerItem(sStore, sHub) + hubCostPerItem(sHub)

    /** `C(S_w, S_r)` of (A.13): the total cost per unit time. */
    fun cost(sHub: Int, sStore: Int): Double = hubRate * costPerItem(sHub, sStore)

    /** The same system with different cost rates, for [measures]. */
    fun withCosts(storeHolding: Double, hubHolding: Double, shortage: Double) =
        OneForOneTwoLevel(storeRate, stores, transit, hubLead, storeHolding, hubHolding, shortage, epsilon)

    private val onHandAtStores by lazy { withCosts(1.0, 0.0, 0.0) }
    private val onHandAtHub by lazy { withCosts(0.0, 1.0, 0.0) }
    private val backordersAtStores by lazy { withCosts(0.0, 0.0, 1.0) }

    /**
     * The expected stock levels, by linearity: the cost with one rate set to one
     * and the others to zero is the expected level that rate multiplies.
     */
    fun measures(sHub: Int, sStore: Int) = TwoLevelMeasures(
        storeOnHand = onHandAtStores.cost(sHub, sStore),
        storeBackorders = backordersAtStores.cost(sHub, sStore),
        hubOnHand = onHandAtHub.cost(sHub, sStore),
    )
}

/**
 * Expected stock levels of a two-level system. The storeroom figures are totals
 * over every storeroom; divide by the number of storerooms for one of them.
 */
data class TwoLevelMeasures(
    val storeOnHand: Double,
    val storeBackorders: Double,
    val hubOnHand: Double,
)
