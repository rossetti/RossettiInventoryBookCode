package inventory.multiechelon

import kotlin.math.max
import kotlin.math.min

/**
 * Exact and approximate costs when the storerooms and the hub both order in
 * batches. @sec-batchedmultiechelon-exact and @sec-batchedmultiechelon-axsater.
 *
 * This is Axsäter (1993), sections 2 and 3. Every storeroom runs `(R_r, Q_r)`
 * and the hub runs `(R_w, Q_w)`, where the hub's reorder point and batch are
 * counted in **storeroom batches**, as the paper counts them. A hub order is
 * `Q_w` sub-batches of `Q_r` units each, and the cost is the average over the
 * units of a hub batch of the one-for-one cost `C(i, k)` of [OneForOneTwoLevel],
 * where `i` is the system demand the unit's sub-batch is released against and
 * `k` the storeroom demand the unit fills. What makes the general case harder is
 * only that `i` is random.
 *
 * - One-for-one storerooms, equation (1): the hub's position is averaged.
 * - One storeroom, equation (2): `i` is fixed at `jQ_r`.
 * - Otherwise, equation (6), which is (3) when `R_w >= -1`: `i` has the law
 *   `p_{i,j}` of the demand that triggers the `j`th storeroom order after a hub
 *   order, and when `R_w < -1` the storeroom's own demands while it waits have
 *   the law `q_{m,j}`.
 *
 * @param base the one-for-one system, which fixes the demand, lead times and costs
 * @param storeBatch `Q_r`, in units
 * @param hubBatches `Q_w`, in storeroom batches
 */
class AxsaterBatchOrdering(
    val base: OneForOneTwoLevel,
    val storeBatch: Int,
    val hubBatches: Int,
) {
    init {
        require(storeBatch >= 1) { "the storeroom batch must be at least one unit" }
        require(hubBatches >= 1) { "the hub batch must be at least one storeroom batch" }
    }

    private val n = base.stores
    private val q = storeBatch

    /** The three approximations of Axsäter's section 3.2, equations (21) to (24). */
    enum class Approximation { ONE, TWO, THREE }

    /**
     * The exact cost per unit time. [hubReorder] is `R_w` in storeroom batches and
     * [storeReorder] is `R_r` in units. The paper shows optimal policies satisfy
     * `R_w >= -Q_w` and `R_r >= -Q_r`, and the formulas are stated on that range.
     */
    fun cost(hubReorder: Int, storeReorder: Int): Double {
        requireRange(hubReorder, storeReorder)
        return when {
            q == 1 -> oneForOneStores(hubReorder, storeReorder)
            n == 1 -> serial(hubReorder, storeReorder)
            else -> general(hubReorder, storeReorder)
        }
    }

    /**
     * The expected stock levels at a policy, by the linearity [OneForOneTwoLevel.measures]
     * relies on: the exact cost with one rate set to one and the others to zero.
     * Storeroom figures are totals over every storeroom.
     */
    fun measures(hubReorder: Int, storeReorder: Int): TwoLevelMeasures {
        fun with(h: Double, hw: Double, b: Double) =
            AxsaterBatchOrdering(base.withCosts(h, hw, b), storeBatch, hubBatches).cost(hubReorder, storeReorder)
        return TwoLevelMeasures(
            storeOnHand = with(1.0, 0.0, 0.0),
            storeBackorders = with(0.0, 0.0, 1.0),
            hubOnHand = with(0.0, 1.0, 0.0),
        )
    }

    /** Equation (1): storerooms one for one, so `S_r = R_r + 1` and the hub's position is averaged. */
    fun oneForOneStores(hubReorder: Int, storeReorder: Int): Double {
        require(q == 1) { "equation (1) needs one-for-one storerooms" }
        var sum = 0.0
        for (j in hubReorder + 1..hubReorder + hubBatches) sum += base.cost(j, storeReorder + 1)
        return sum / hubBatches
    }

    /** Equation (2): one storeroom, so the `j`th sub-batch is released at system demand `jQ_r`. */
    fun serial(hubReorder: Int, storeReorder: Int): Double {
        require(n == 1) { "equation (2) needs a single storeroom" }
        return fixedRelease(hubReorder, storeReorder)
    }

    /** The double sum of (2), which is also approximation 1 for any number of storerooms, (22). */
    private fun fixedRelease(hubReorder: Int, storeReorder: Int): Double {
        var sum = 0.0
        for (j in hubReorder + 1..hubReorder + hubBatches) {
            for (k in storeReorder + 1..storeReorder + q) sum += base.cost(j * q, k)
        }
        return sum / (hubBatches * q)
    }

    /** Equation (6), which reduces to (3) when `R_w >= -1`. Needs two or more storerooms. */
    fun general(hubReorder: Int, storeReorder: Int): Double {
        require(n >= 2) { "equation (6) needs at least two storerooms" }
        requireRange(hubReorder, storeReorder)
        val t = Tables(hubReorder)
        var sum = 0.0
        // Sub-batches for storeroom orders placed before the hub order, (6) first term.
        for (j in max(1, -hubReorder - hubBatches)..-hubReorder - 1) {
            for (k in storeReorder + 1..storeReorder + q) {
                for (m in 0..j * q) {
                    val w = t.qm(m, j)
                    if (w != 0.0) sum += w * base.cost(0, k - m)
                }
            }
        }
        // Sub-batches for storeroom orders at or after the hub order, (6) second term and (3).
        for (j in max(0, hubReorder + 1)..hubReorder + hubBatches) {
            for (k in storeReorder + 1..storeReorder + q) {
                for (i in 0..t.upper(j)) {
                    val w = t.p[i][j]
                    if (w != 0.0) sum += w * base.cost(i, k)
                }
            }
        }
        return sum / (hubBatches * q)
    }

    /** The approximate cost per unit time, Axsäter's section 3.2. */
    fun approximateCost(which: Approximation, hubReorder: Int, storeReorder: Int): Double {
        requireRange(hubReorder, storeReorder)
        return when (which) {
            Approximation.ONE -> fixedRelease(hubReorder, storeReorder)
            Approximation.TWO -> negativeBinomialRelease(hubReorder, storeReorder)
            Approximation.THREE ->
                fixedRelease(hubReorder, storeReorder) / n +
                    (n - 1.0) / n * negativeBinomialRelease(hubReorder, storeReorder)
        }
    }

    /**
     * Approximation 2, (23): each system demand triggers a storeroom order with
     * chance `1/Q_r`, so the demand triggering the `j`th order is negative
     * binomial. Inserting the binomial `q` of (20) into (6) gives (3) with
     * `p_{i,j} = p_{-i,-j}` for the sub-batches of orders already placed, which
     * is a release at the negative hub position `-i`.
     */
    private fun negativeBinomialRelease(hubReorder: Int, storeReorder: Int): Double {
        var sum = 0.0
        for (j in hubReorder + 1..hubReorder + hubBatches) {
            val a = kotlin.math.abs(j)
            val sign = if (j < 0) -1 else 1
            for (k in storeReorder + 1..storeReorder + q) {
                if (a == 0) {
                    sum += base.cost(0, k)
                    continue
                }
                // P(i) = C(i-1, a-1) (1/Q)^a ((Q-1)/Q)^(i-a), i >= a, summed until negligible.
                var i = a
                var pr = Math.pow(1.0 / q, a.toDouble())
                var mass = 0.0
                while (mass < 1.0 - 1.0e-13 && i < a + 20_000) {
                    sum += pr * base.cost(sign * i, k)
                    mass += pr
                    // C(i, a-1) / C(i-1, a-1) = i / (i - a + 1)
                    pr *= (i.toDouble() / (i - a + 1)) * ((q - 1.0) / q)
                    i += 1
                    if (q == 1) break
                }
            }
        }
        return sum / (hubBatches * q)
    }

    private fun requireRange(hubReorder: Int, storeReorder: Int) {
        require(hubReorder >= -hubBatches) { "R_w must be at least -Q_w, was $hubReorder" }
        require(storeReorder >= -q) { "R_r must be at least -Q_r, was $storeReorder" }
    }

    /**
     * The probabilities of Axsäter's section 3.1 for one value of `R_w`: `y^n_{i,j}` of (7)
     * and (8), `x^N_{i,j}` of (13) and (14), `p_{i,j}` of (16), `p^{N-1}_{i,j}` of
     * (17), `p^1_{i,j}` of (18), and `q_{m,j}` of (19). Rows are system demands
     * `i`, columns are storeroom orders `j`.
     */
    private inner class Tables(hubReorder: Int) {
        val jMax = max(-hubReorder - 1, hubReorder + hubBatches)
        val iMax = upper(jMax + 1)

        /** `I^u_j` of (5): the most demands that can pass before the `j`th order. */
        fun upper(j: Int) = (n - 1) * (q - 1) + j * q

        /** Binomial mass of m successes in i trials with success chance [s]. */
        private val binomialCache = HashMap<Double, Array<DoubleArray>>()
        fun binomial(i: Int, m: Int, s: Double): Double {
            val rows = binomialCache.getOrPut(s) {
                Array(iMax + 1) { DoubleArray(0) }.also { table ->
                    table[0] = doubleArrayOf(1.0)
                    for (r in 1..iMax) {
                        val prev = table[r - 1]
                        table[r] = DoubleArray(r + 1) { c ->
                            (if (c < r) prev[c] * (1.0 - s) else 0.0) + (if (c > 0) prev[c - 1] * s else 0.0)
                        }
                    }
                }
            }
            return rows[i][m]
        }

        /** `y^1` of (7): one storeroom whose position is uniform above `R_r`. */
        private val y1 = Array(iMax + 1) { i ->
            DoubleArray(jMax + 2) { j ->
                when {
                    i == 0 -> if (j == 0) 1.0 else 0.0
                    // i = (j-1)Q + k with k in 1..Q gives j orders with chance k/Q.
                    j >= 1 && i in (j - 1) * q + 1..j * q -> (i - (j - 1) * q).toDouble() / q
                    // i = jQ + k with k in 1..Q-1 gives j orders with chance 1 - k/Q.
                    i in j * q + 1..j * q + q - 1 -> 1.0 - (i - j * q).toDouble() / q
                    else -> 0.0
                }
            }
        }

        /** `x^1` of (13): the ordering storeroom, just reset to `R_r + Q_r`, orders every `Q_r` demands. */
        private fun x1(i: Int, j: Int) = if (i / q == j) 1.0 else 0.0

        /** `y^{N-1}` by (8): i demands split binomially between one more storeroom and the rest. */
        val yOthers: Array<DoubleArray> = run {
            var yk = y1
            for (k in 1..n - 2) {
                val s = 1.0 / (k + 1)
                val prev = yk
                yk = Array(iMax + 1) { i ->
                    DoubleArray(jMax + 2) { j ->
                        var total = 0.0
                        for (m in 0..i) {
                            val b = binomial(i, m, s)
                            if (b == 0.0) continue
                            for (nn in 0..j) {
                                val a = y1[m][nn]
                                if (a != 0.0) total += a * prev[i - m][j - nn] * b
                            }
                        }
                        total
                    }
                }
            }
            yk
        }

        /** `x^N` by (14): the ordering storeroom and the N - 1 others together. */
        private val xAll = Array(iMax + 1) { i ->
            DoubleArray(jMax + 2) { j ->
                var total = 0.0
                for (m in 0..i) {
                    val b = binomial(i, m, 1.0 / n)
                    if (b == 0.0) continue
                    val nn = m / q
                    if (nn <= j) total += x1(m, nn) * yOthers[i - m][j - nn] * b
                }
                total
            }
        }

        /** `p_{i,j}` by (16): the `i`th demand after the hub order triggers the `j`th storeroom order. */
        val p: Array<DoubleArray> = triggers(xAll)

        /** `p^{N-1}_{i,j}` by (17): the same, counting only the storerooms that did not order. */
        private val pOthers: Array<DoubleArray> = triggers(yOthers)

        /** The recursion shared by (16) and (17): `p_{i,j+1} = p_{i,j} - x_{i,j} + x_{i-1,j}`. */
        private fun triggers(x: Array<DoubleArray>): Array<DoubleArray> {
            val out = Array(iMax + 1) { DoubleArray(jMax + 2) }
            out[0][0] = 1.0
            for (j in 0..jMax) {
                for (i in 1..iMax) out[i][j + 1] = out[i][j] - x[i][j] + x[i - 1][j]
            }
            return out
        }

        /** `p^1_{m,n}` of (18). */
        private fun p1(m: Int, nn: Int) = if (m == nn * q) 1.0 else 0.0

        /**
         * `q_{m,j}` of (19): the chance the ordering storeroom sees m demands of its
         * own while it waits for the `j`th subsequent storeroom order.
         */
        fun qm(m: Int, j: Int): Double {
            var total = 0.0
            val own = m / q
            if (own > j) return 0.0
            for (i in max(1, m)..min(upper(j), iMax)) {
                val b = binomial(i, m, 1.0 / n)
                if (b == 0.0) continue
                val atOrdering = m.toDouble() / i * yOthers[i - m][j - own] * p1(m, own)
                val atOthers = (i - m).toDouble() / i * pOthers[i - m][j - own]
                total += b * (atOrdering + atOthers)
            }
            return total
        }
    }
}
