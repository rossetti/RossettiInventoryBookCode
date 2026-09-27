package inventory.multiechelon

import inventory.multiechelon.TwoLevelIteration.HubRoute
import inventory.multiechelon.TwoLevelIteration.StoreRoute
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two-level iteration of @sec-batchedmultiechelon-algorithm, and @sec-batchedmultiechelon-validate.
 *
 * The hub's backorder cost is set from a hub fill rate target of 0.8, so
 * `b_0 = 0.8 h / 0.2 = 4h`, throughout. Every fixed point is priced against an
 * exact cost: Axsäter's where he applies, the chapter's exact route otherwise.
 */
class TwoLevelIterationTest {

    private fun close(got: Double, want: Double, tol: Double = 1e-8, what: String = "") =
        assertTrue(abs(got - want) < tol, "$what expected $want, got $got")

    private val h = 28.75 / 52
    private val b = 287.50 / 52
    private val b0 = TwoLevelProblem.backorderCostForTarget(0.8, h)

    private val routes = listOf(
        HubRoute.MOMENTS to StoreRoute.MOMENTS,
        HubRoute.EXACT to StoreRoute.MOMENTS,
        HubRoute.EXACT to StoreRoute.CONDITIONED,
    )

    @Test
    fun `a target fill rate sets the hub's backorder cost through the critical ratio`() {
        close(b0, 4.0 * h, 1e-12)
        close(b0 / (b0 + h), 0.8, 1e-12)
    }

    @Test
    fun `the moment route's hub is the recipe of the Part II hub`() {
        val problem = TwoLevelProblem(2.0, TwoLevelProblem.ONE_UNIT, 2.0, TwoLevelProblem.ONE_UNIT, 3.0, 1.0,
            RQCosts(82.5, h, b0), RQCosts(82.5, h, b))
        val (ltd, rate) = TwoLevelIteration(problem).momentHubLeadTimeDemand(3)
        val recipe = BatchedHub(2.0, listOf(StoreStream(2.0, 3)), 12, 8, 3.0, RQCosts(0.0, h, b)).recipeMoments(3.0)
        close(rate, 4.0, 1e-12)
        close(ltd.mean, recipe.first, 1e-12)
        close(ltd.variance, recipe.second, 1e-9)
    }

    // ---- Part I, Axsäter's setting, both batches held ------------------------

    /** Four storerooms one for one: the iteration sees one of them and treats the other three as other demand. */
    private val partOne = TwoLevelProblem(1.0, TwoLevelProblem.ONE_UNIT, 3.0, TwoLevelProblem.ONE_UNIT, 3.0, 1.0,
        RQCosts(0.0, h, b0), RQCosts(0.0, h, b))

    @Test
    fun `Part I, every route settles on the same policy, priced against Axsater`() {
        val exact = AxsaterBatchOrdering(OneForOneTwoLevel(1.0, 4, 1.0, 3.0, h, h, b), 1, 8)
        for ((hub, store) in routes) {
            val result = TwoLevelIteration(partOne, hub, store, holdBatches = true).solve(TwoLevelPolicy(0, 8, 0, 1))
            assertTrue(result.converged, "$hub, $store")
            assertEquals(TwoLevelPolicy(11, 8, 2, 1), result.policy, "$hub, $store")
        }
        // The exact optimum is (9, 3) at 7.0195; the target of 0.8 lands at (11, 3).
        close(exact.cost(11, 2), 7.4337345612, 1e-9, "the fixed point, exactly")
        close(exact.cost(11, 2) / exact.cost(9, 2) - 1.0, 0.0590, 1e-4, "gap to the exact optimum")
    }

    // ---- One batching storeroom, Axsäter's serial case -----------------------

    @Test
    fun `one batching storeroom, priced against Axsater's equation (2)`() {
        val problem = TwoLevelProblem(2.0, TwoLevelProblem.ONE_UNIT, 0.0, TwoLevelProblem.ONE_UNIT, 3.0, 1.0,
            RQCosts(0.0, h, b0), RQCosts(0.0, h, b))
        val exact = AxsaterBatchOrdering(OneForOneTwoLevel(2.0, 1, 1.0, 3.0, h, h, b), 3, 2)
        for ((hub, store) in routes) {
            val result = TwoLevelIteration(problem, hub, store, holdBatches = true).solve(TwoLevelPolicy(0, 6, 0, 3))
            assertTrue(result.converged, "$hub, $store")
            // With only batches of three at the hub its position steps in threes, so a
            // reorder point acts as the multiple of three at or below it.
            val acting = Math.floorDiv(result.policy.hubReorder, 3)
            assertEquals(1 to 3, acting to result.policy.storeReorder, "$hub, $store")
        }
        close(exact.cost(1, 3), 3.5353981804, 1e-9, "the fixed point")
        close(exact.cost(0, 6), 3.4110908554, 1e-9, "the exact optimum, which holds nothing at the hub")
    }

    // ---- Part II, batches free ----------------------------------------------

    private val partTwo = TwoLevelProblem(2.0, TwoLevelProblem.ONE_UNIT, 2.0, TwoLevelProblem.ONE_UNIT, 3.0, 1.0,
        RQCosts(82.5, h, b0), RQCosts(82.5, h, b))

    @Test
    fun `Part II, the moment route's trace`() {
        val result = TwoLevelIteration(partTwo).solve()
        assertTrue(result.converged)
        val want = listOf(
            TwoLevelPolicy(2, 47, -1, 26) to Pair(13.0870931953, 0.5634012681),
            TwoLevelPolicy(2, 48, 0, 29) to Pair(14.3506388353, 0.6166241227),
            TwoLevelPolicy(2, 48, 0, 29) to Pair(14.6524049840, 0.6166241227),
        )
        assertEquals(want.size, result.passes.size)
        for ((k, pass) in result.passes.withIndex()) {
            assertEquals(want[k].first, pass.policy, "pass ${k + 1}")
            close(pass.storeCost, want[k].second.first, 1e-8, "pass ${k + 1} storeroom cost")
            close(pass.meanWait, want[k].second.second, 1e-8, "pass ${k + 1} mean wait")
        }
        close(result.passes.last().hubCost, 21.7102667417, 1e-8, "hub cost")
        close(result.passes.last().sdWait, 1.8033403561, 1e-8, "wait's standard deviation")
    }

    @Test
    fun `Part II, each route's fixed point priced exactly`() {
        val byMoments = TwoLevelIteration(partTwo).solve().policy
        val byExact = TwoLevelIteration(partTwo, HubRoute.EXACT, StoreRoute.CONDITIONED).solve().policy
        assertEquals(TwoLevelPolicy(2, 48, 3, 28), byExact)
        close(TwoLevelIteration.exactSystemCost(partTwo, byMoments, b), 34.3462451283, 1e-7, "moment route")
        close(TwoLevelIteration.exactSystemCost(partTwo, byExact, b), 33.5369516914, 1e-7, "exact route")
    }

    @Test
    fun `Part II, the best policy the exact cost can find nearby`() {
        // A local minimum of the exact cost over single steps in each of the four integers.
        val best = TwoLevelPolicy(2, 43, 4, 26)
        val at = TwoLevelIteration.exactSystemCost(partTwo, best, b)
        close(at, 33.3954627036, 1e-7)
        for (d in listOf(-1, 1)) {
            for (neighbor in listOf(
                best.copy(hubReorder = best.hubReorder + d), best.copy(hubBatch = best.hubBatch + d),
                best.copy(storeReorder = best.storeReorder + d), best.copy(storeBatch = best.storeBatch + d),
            )) assertTrue(TwoLevelIteration.exactSystemCost(partTwo, neighbor, b) >= at, "$neighbor")
        }
    }

    // ---- The cutout in job lots, 10.14 ---------------------------------------

    @Test
    fun `the cutout in job lots converges in three passes`() {
        val lots = doubleArrayOf(0.0, 0.4, 0.4, 0.0, 0.2)
        val problem = TwoLevelProblem(150.0, lots, 250.0, lots, 2.0 / 12, 1.0 / 12,
            RQCosts(82.5, 28.75, TwoLevelProblem.backorderCostForTarget(0.8, 28.75)), RQCosts(82.5, 28.75, 287.5))
        val result = TwoLevelIteration(problem).solve()
        assertTrue(result.converged)
        assertEquals(
            listOf(TwoLevelPolicy(120, 92, 22, 48), TwoLevelPolicy(120, 92, 25, 49), TwoLevelPolicy(120, 92, 25, 49)),
            result.passes.map { it.policy },
        )
        val last = result.passes.last()
        close(last.storeCost, 1371.2605612246, 1e-6, "storeroom cost a year")
        close(last.hubCost, 2279.6894685700, 1e-6, "hub cost a year")
        close(last.meanWait, 0.0054185348, 1e-9, "mean wait, years")
    }

    @Test
    fun `the fixed point does not depend on where the iteration starts`() {
        val fromEoq = TwoLevelIteration(partTwo).solve().policy
        for (start in listOf(TwoLevelPolicy(0, 10, 0, 5), TwoLevelPolicy(20, 80, 10, 60))) {
            assertEquals(fromEoq, TwoLevelIteration(partTwo).solve(start).policy, "from $start")
        }
    }
}
