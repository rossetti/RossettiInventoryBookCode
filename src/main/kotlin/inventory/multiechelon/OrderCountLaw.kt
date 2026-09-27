package inventory.multiechelon

/**
 * How many orders a storeroom running `(r, Q)` against unit Poisson demand
 * places in a window of time. @sec-batchedmultiechelon-orders.
 *
 * The storeroom orders each time its customers' demand carries its position down
 * through `r`. Let `u`, in 1..Q, be how far the position sits above `r` when the
 * window opens. The count is at least n exactly when the window's demand D is at
 * least `u + (n-1)Q`. With `u` equally likely to take each value, which is where
 * the position sits in steady state (@sec-continuousreview-batch-position),
 *
 * ```
 *   P(N >= n) = (1/Q) * sum over y from (n-1)Q+1 to nQ of P(D >= y)
 * ```
 *
 * which is an average of Q consecutive entries of the Poisson table.
 *
 * A window that **ends** at one of the storeroom's own orders is different: the
 * position is known to have just reached `r`. The demand strictly before the
 * order in the window is still Poisson, by independent increments, and the
 * earlier orders it holds number `floor(D'/Q)`. That is [seenFromOrder].
 *
 * @param lambda the storeroom's demand rate
 * @param q the storeroom's order quantity, `Q`
 */
class OrderCountLaw(val lambda: Double, val q: Int) {

    init {
        require(lambda > 0.0) { "the demand rate must be positive" }
        require(q >= 1) { "the order quantity must be at least one" }
    }

    /** The mass function of N, the orders placed in a window of length [ell] opening at a random time. */
    fun stationary(ell: Double): DoubleArray {
        val demand = cumulative(poissonPmf(lambda * ell))
        val maxN = demand.size / q + 2
        // atLeast[n] = P(N >= n) = (1/Q) sum_{y=(n-1)Q+1}^{nQ} P(D >= y), with P(D >= y) = 1 - P(D <= y-1).
        val atLeast = DoubleArray(maxN + 2)
        atLeast[0] = 1.0
        for (n in 1..maxN + 1) {
            var s = 0.0
            for (y in (n - 1) * q + 1..n * q) s += 1.0 - demand.at(y - 1)
            atLeast[n] = s / q
        }
        return trim(DoubleArray(maxN + 1) { n -> atLeast[n] - atLeast[n + 1] })
    }

    /** The mass function of the storeroom's earlier orders in a window of length [ell] that ends at one of its orders. */
    fun seenFromOrder(ell: Double): DoubleArray {
        val demand = cumulative(poissonPmf(lambda * ell))
        val maxN = demand.size / q + 1
        return trim(DoubleArray(maxN + 1) { n -> demand.at(n * q + q - 1) - demand.at(n * q - 1) })
    }

    /** The mass function of the units ordered, `Q` times the count. */
    fun unitsStationary(ell: Double): DoubleArray = scale(stationary(ell), q)

    private fun trim(pmf: DoubleArray): DoubleArray {
        var last = pmf.lastIndex
        while (last > 0 && pmf[last] < TAIL * 1e-3) last--
        return pmf.copyOf(last + 1)
    }
}
