package inventory.continuousreview

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three ways of getting the answer must give the same answer.
 *
 * Algorithm Optimize_rq of @sec-continuousreview-algorithm-steps is exact and slow. The warm start and
 * doubling search of @sec-continuousreview-algorithm-warm are fast, and the doubling search on its own
 * is not exact. A full enumeration is exact and slower than either. This file
 * runs all three on a spread of items and asserts that they agree, which is the
 * only way to be sure the fast route is the same algorithm and not a heuristic.
 */
class OptimizerAgreementTest {

    private fun enumerate(model: RQModel, maxBatch: Int): Pair<RQPolicy, Double> {
        val star = model.optimalBaseStock()
        var best: RQPolicy? = null
        var bestCost = Double.MAX_VALUE
        for (q in 1..maxBatch) {
            // @eq-optimal-structure confines the optimal band to straddle S*, so every
            // optimal r for this q lies in this range. Searching wider changes
            // nothing and is checked below on the smaller items.
            for (r in (star - q)..(star - 1)) {
                val c = model.cost(r, q)
                if (c < bestCost) { bestCost = c; best = RQPolicy(r, q) }
            }
        }
        return Pair(best!!, bestCost)
    }

    /** Items chosen so that the optimal batch runs from a few units to several hundred. */
    private fun items(): List<Pair<String, RQModel>> = listOf(
        "transformer, Poisson" to RQModel(
            45.0, 220.0, 950.0, 8550.0, LeadTimeDemand.poisson(7.5)
        ),
        "connector, Poisson" to RQModel(
            3000.0, 150.0, 12.0, 90.0, LeadTimeDemand.poisson(230.77)
        ),
        "cheap backorders" to RQModel(
            500.0, 40.0, 25.0, 30.0, LeadTimeDemand.poisson(12.0)
        ),
        "lumpy, negative binomial" to RQModel(
            400.0, 82.5, 28.75, 287.5, LeadTimeDemand.negativeBinomial(80.0, 230.0)
        ),
        "gamma lead time demand" to RQModel(
            2000.0, 300.0, 9.0, 60.0, LeadTimeDemand.gamma(400.0, 900.0)
        ),
        "large batch" to RQModel(
            12000.0, 900.0, 3.0, 25.0, LeadTimeDemand.poisson(1500.0)
        ),
        "tiny fixed cost" to RQModel(
            60.0, 1.0, 100.0, 400.0, LeadTimeDemand.poisson(4.0)
        ),
    )

    @Test
    fun `the fast search and Algorithm Optimize_rq agree on every item`() {
        for ((name, model) in items()) {
            val exact = RQOptimizer(model).optimizeFromUnitBatch()
            val fast = RQOptimizer(model).optimize()
            assertEquals(exact.orderQuantity, fast.orderQuantity, "$name: Q")
            assertEquals(exact.reorderPoint, fast.reorderPoint, "$name: r")
            assertEquals(exact.cost, fast.cost, 1e-6, "$name: cost")
        }
    }

    /**
     * An enumeration costs `O(Q^2)` window evaluations, so it is run on the items
     * whose optimal batch is small enough to make that cheap. The large-batch item
     * is covered by the agreement above, and Algorithm Optimize_rq is exact by the
     * argument of @sec-continuousreview-algorithm-idea.
     */
    @Test
    fun `both searches agree with a full enumeration`() {
        var covered = 0
        for ((name, model) in items()) {
            val ceiling = model.bounds().batchUpper
            if (ceiling > 300) continue
            covered += 1
            val exact = RQOptimizer(model).optimizeFromUnitBatch()
            val (policy, cost) = enumerate(model, maxBatch = ceiling + 50)
            assertEquals(cost, exact.cost, 1e-6, "$name: enumerated cost")
            assertEquals(policy.orderQuantity, exact.orderQuantity, "$name: enumerated Q")
            assertEquals(policy.reorderPoint, exact.reorderPoint, "$name: enumerated r")
        }
        assertTrue(covered >= 4, "only $covered items were small enough to enumerate")
    }

    /**
     * The doubling search must actually be used, or this file proves nothing
     * about it. It must also be cheaper than the algorithm it replaces.
     */
    @Test
    fun `the doubling search is used on a large batch and costs less`() {
        val model = items().first { it.first == "large batch" }.second
        val fast = RQOptimizer(model).optimize()
        val exact = RQOptimizer(model).optimizeFromUnitBatch()
        assertEquals(SearchMethod.JUMP_SEARCH, fast.method)
        assertTrue(exact.orderQuantity > 200, "this item should have a large optimal batch")
        assertTrue(
            fast.windowEvaluations < exact.windowEvaluations,
            "the jump search took ${fast.windowEvaluations} evaluations against " +
                "${exact.windowEvaluations}, so it bought nothing"
        )
    }

    /** The closed-form window of @eq-rq-cost-average must equal the sum it replaces. */
    @Test
    fun `the closed-form window equals the term by term sum`() {
        for ((name, model) in items().filter { it.second.leadTimeDemand.isDiscrete }) {
            for (q in intArrayOf(1, 2, 5, 17, 60)) {
                for (r in intArrayOf(-4, -1, 0, 3, 40)) {
                    if (r + q < 0) continue
                    val closed = model.windowTotal(r, q)
                    val summed = model.windowTotalBySummation(r, q)
                    assertTrue(
                        abs(closed - summed) <= 1e-6 * maxOf(1.0, abs(summed)),
                        "$name at r = $r, Q = $q: closed $closed against summed $summed"
                    )
                }
            }
        }
    }

    /**
     * Under a continuous family the window is an integral, so the check is
     * quadrature rather than summation. Simpson's rule on the first order loss
     * function must reproduce the difference of second order loss functions.
     */
    @Test
    fun `the closed-form window equals a quadrature under a continuous family`() {
        val model = items().first { !it.second.leadTimeDemand.isDiscrete }.second
        val d = model.leadTimeDemand
        for (q in intArrayOf(1, 5, 60)) {
            for (r in intArrayOf(0, 200, 400)) {
                val panels = 2000
                val width = q.toDouble() / panels
                var integral = 0.0
                for (i in 0 until panels) {
                    val a = r + i * width
                    val m = a + width / 2.0
                    val bb = a + width
                    integral += width / 6.0 * (d.lossFirst(a) + 4.0 * d.lossFirst(m) + d.lossFirst(bb))
                }
                val telescoped = d.lossSecond(r.toDouble()) - d.lossSecond((r + q).toDouble())
                assertTrue(
                    abs(integral - telescoped) <= 1e-6 * maxOf(1.0, abs(telescoped)),
                    "at r = $r, Q = $q: quadrature $integral against telescoped $telescoped"
                )
                assertEquals(telescoped / q, model.expectedBackorders(r, q), 1e-9)
            }
        }
    }

    /** Searching outside the band of @eq-optimal-structure finds nothing better. */
    @Test
    fun `the optimal band of @eq-optimal-structure loses nothing`() {
        val model = items().first().second
        val best = RQOptimizer(model).optimizeFromUnitBatch()
        var wide = Double.MAX_VALUE
        for (q in 1..120) for (r in -6..60) {
            if (r + q < 0) continue
            wide = minOf(wide, model.cost(r, q))
        }
        assertEquals(wide, best.cost, 1e-9)
    }
}
