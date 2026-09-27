package inventory.multiechelon

import inventory.continuousreview.LeadTimeDemand
import inventory.continuousreview.RQModel
import inventory.continuousreview.RQOptimizer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A hub and one storeroom, and the other demand the hub serves, described by
 * what is known before either policy is set. @sec-batchedmultiechelon-algorithm.
 *
 * Customers at the storeroom, and the hub's other customers, arrive as Poisson
 * epochs and take lots of random size: `lots[y] = P(Y = y)`, with `lots[0] = 0`.
 * A lot of one unit is `[0, 1]`.
 *
 * @param storeEpochRate epochs per unit time at the storeroom
 * @param storeLots the storeroom customers' lot sizes
 * @param otherEpochRate epochs per unit time of other demand at the hub, zero for none
 * @param otherLots the other customers' lot sizes
 * @param hubLead `L_0`, constant
 * @param transit `O_j`, constant
 * @param hubCosts the hub's order, holding and backorder costs. Its backorder
 *   cost is what the hub's own optimization charges on every unit it owes.
 * @param storeCosts the storeroom's order, holding and backorder costs
 */
class TwoLevelProblem(
    val storeEpochRate: Double,
    val storeLots: DoubleArray,
    val otherEpochRate: Double,
    val otherLots: DoubleArray,
    val hubLead: Double,
    val transit: Double,
    val hubCosts: RQCosts,
    val storeCosts: RQCosts,
) {
    init {
        require(storeEpochRate > 0.0 && otherEpochRate >= 0.0) { "rates must be positive, other demand may be zero" }
        for (lots in listOf(storeLots, otherLots)) {
            require(lots.isNotEmpty() && lots[0] == 0.0 && kotlin.math.abs(lots.sum() - 1.0) < 1e-9) {
                "lots must be at least one unit and their probabilities must sum to one"
            }
        }
        require(hubLead > 0.0 && transit >= 0.0) { "the hub's lead time must be positive" }
    }

    private fun mean(lots: DoubleArray) = lots.withIndex().sumOf { (y, p) -> y * p }
    private fun variance(lots: DoubleArray) = lots.withIndex().sumOf { (y, p) -> y.toDouble() * y * p } - mean(lots).let { it * it }

    val storeLotMean: Double get() = mean(storeLots)
    val storeLotVariance: Double get() = variance(storeLots)
    val otherLotMean: Double get() = mean(otherLots)
    val otherLotVariance: Double get() = variance(otherLots)

    /** `lambda_j`, units per unit time demanded at the storeroom. */
    val storeRate: Double get() = storeEpochRate * storeLotMean

    /** `lambda_o`, units per unit time of other demand at the hub. */
    val otherRate: Double get() = otherEpochRate * otherLotMean

    /** True when every customer takes one unit, which is what the exact routes need. */
    val unitCustomers: Boolean get() = storeLots.size == 2 && (otherEpochRate == 0.0 || otherLots.size == 2)

    companion object {
        val ONE_UNIT = doubleArrayOf(0.0, 1.0)

        /**
         * The backorder cost that makes a fill rate target [gamma] the optimal one:
         * `b = gamma h / (1 - gamma)`, since @eq-basestock-optimal sets the critical
         * ratio `b/(b + h)` equal to the target.
         */
        fun backorderCostForTarget(gamma: Double, holding: Double): Double {
            require(gamma > 0.0 && gamma < 1.0) { "a target must lie strictly between 0 and 1" }
            return gamma * holding / (1.0 - gamma)
        }
    }
}

/** Both locations' policies. */
data class TwoLevelPolicy(val hubReorder: Int, val hubBatch: Int, val storeReorder: Int, val storeBatch: Int) {
    override fun toString() = "hub ($hubReorder, $hubBatch), storeroom ($storeReorder, $storeBatch)"
}

/**
 * The two-level iteration of @sec-batchedmultiechelon-algorithm: hold one location
 * fixed, solve the other as a chapter 8 location, and alternate.
 *
 * Each pass does five things:
 *
 * 1. the storeroom's lead time is `O_j + W`, with `W` the wait at the hub from
 *    the previous pass, zero on the first;
 * 2. the storeroom's lead time demand is built and its policy optimized;
 * 3. the orders that policy passes up are characterized;
 * 4. the hub's lead time demand is built from them and the other demand, and
 *    its policy optimized;
 * 5. the wait at the hub under that policy is computed for the next pass.
 *
 * It stops when all four integers repeat. Two steps can each be done two ways.
 *
 * [HubRoute.MOMENTS] is the recipe of [BatchedHub.recipe] generalized to job
 * lots: the hub's lead time demand has mean `lambda_0 L_0` and variance the other
 * demand's plus the storeroom customers' own, each compound Poisson, plus
 * `Q_j^2/6` for the rounding to whole batches. A family is fitted to those two
 * moments, and the wait's mean `B_0/lambda_0` and variance
 * `(Var[B_0] - B_0)/lambda_0^2` are read from that model. [HubRoute.EXACT] needs
 * one-unit customers and uses the exact convolution of [BatchedHub] and the
 * storeroom order's own wait from [HubDelay].
 *
 * [StoreRoute.MOMENTS] fits a family to the storeroom's lead time demand moments,
 * `lambda E[L]` and `lambda_e E[L] E[Y^2] + Var[L] lambda^2` for compound Poisson
 * customers over a random lead time (case 3 of @tbl-ltd-cases).
 * [StoreRoute.CONDITIONED] conditions on the wait's distribution, which needs the
 * exact hub route.
 *
 * The hub's backorder cost is not a cost anyone pays; the system's cost has no
 * term for it. It is set from a target fill rate at the hub,
 * [TwoLevelProblem.backorderCostForTarget], and the fixed point depends on it.
 *
 * The result is a fixed point of a heuristic, not a proven optimum. Each location
 * is optimal given the other; nothing shows the pair is the best pair.
 *
 * @param holdBatches keep both order quantities at the starting policy's and
 *   optimize the reorder points only
 */
class TwoLevelIteration(
    val problem: TwoLevelProblem,
    val hubRoute: HubRoute = HubRoute.MOMENTS,
    val storeRoute: StoreRoute = StoreRoute.MOMENTS,
    val holdBatches: Boolean = false,
    val maxPasses: Int = 50,
) {
    enum class HubRoute { MOMENTS, EXACT }
    enum class StoreRoute { MOMENTS, CONDITIONED }

    init {
        if (hubRoute == HubRoute.EXACT || storeRoute == StoreRoute.CONDITIONED) {
            require(problem.unitCustomers) { "the exact routes need customers who each take one unit" }
        }
        require(storeRoute == StoreRoute.MOMENTS || hubRoute == HubRoute.EXACT) {
            "conditioning on the wait needs its distribution, which only the exact hub route gives"
        }
    }

    /** One pass of the iteration, as the chapter's trace table prints it. */
    data class PassRecord(
        val pass: Int,
        val policy: TwoLevelPolicy,
        val storeCost: Double,
        val hubCost: Double,
        val meanWait: Double,
        val sdWait: Double,
    )

    data class Result(val passes: List<PassRecord>, val converged: Boolean) {
        val policy: TwoLevelPolicy get() = passes.last().policy
    }

    /** The wait the storeroom's orders see at the hub. */
    private class Wait(val mean: Double, val variance: Double, val law: DelayLaw?)

    private val p = problem

    /** The starting batches when none are given: each location's EOQ. */
    fun eoqStart(): TwoLevelPolicy {
        fun eoq(c: RQCosts, rate: Double) = max(1, sqrt(2.0 * c.orderCost * rate / c.holdingCost).roundToInt())
        return TwoLevelPolicy(0, eoq(p.hubCosts, p.storeRate + p.otherRate), 0, eoq(p.storeCosts, p.storeRate))
    }

    /**
     * Runs the iteration from [start]. The first pass uses a wait at the hub of
     * mean [startWaitMean] and variance [startWaitVariance], zero by default, which
     * is the planner who ignores the hub. A route that conditions on the wait's
     * distribution starts from a wait fixed at the mean.
     */
    fun solve(start: TwoLevelPolicy = eoqStart(), startWaitMean: Double = 0.0, startWaitVariance: Double = 0.0): Result {
        require(startWaitMean >= 0.0 && startWaitVariance >= 0.0) { "a wait's mean and variance cannot be negative" }
        var wait = Wait(startWaitMean, startWaitVariance, DelayLaw(listOf(startWaitMean to 1.0), startWaitMean, 0.0))
        var previous: TwoLevelPolicy? = null
        var qj = start.storeBatch
        var q0 = start.hubBatch
        val passes = ArrayList<PassRecord>()
        for (pass in 1..maxPasses) {
            // Steps 1 and 2: the storeroom, against the wait from the last pass.
            val storeModel = storeModel(wait)
            val (rj, qjNew) = optimize(storeModel, if (holdBatches) qj else null)
            qj = qjNew
            val storeCost = storeModel.cost(rj, qj)
            // Steps 3 and 4: the hub, against the orders the storeroom now places.
            val hubStep = hubStep(qj, if (holdBatches) q0 else null)
            q0 = hubStep.batch
            val policy = TwoLevelPolicy(hubStep.reorder, q0, rj, qj)
            // Step 5: the wait under the hub's new policy.
            wait = hubStep.wait
            passes += PassRecord(pass, policy, storeCost, hubStep.cost, wait.mean, sqrt(max(0.0, wait.variance)))
            if (policy == previous) return Result(passes, true)
            previous = policy
        }
        return Result(passes, false)
    }

    // ---- The storeroom -------------------------------------------------------

    private fun storeModel(wait: Wait): RQModel {
        val c = p.storeCosts
        val ltd = when (storeRoute) {
            StoreRoute.CONDITIONED ->
                StoreroomWithDelay(p.storeRate, p.transit, wait.law!!, c).leadTimeDemand(StoreroomWithDelay.Route.CONDITIONED)
            StoreRoute.MOMENTS -> {
                val meanLead = p.transit + wait.mean
                val secondLot = p.storeLotVariance + p.storeLotMean * p.storeLotMean
                val m = p.storeRate * meanLead
                val v = p.storeEpochRate * meanLead * secondLot + wait.variance * p.storeRate * p.storeRate
                LeadTimeDemand.matched(m, v)
            }
        }
        return RQModel(p.storeRate, c.orderCost, c.holdingCost, c.backorderCost, ltd)
    }

    /** The best `(r, Q)`, or the best `r` for a held `Q`, which the cost is convex in. */
    private fun optimize(model: RQModel, heldBatch: Int?): Pair<Int, Int> {
        if (heldBatch == null) return RQOptimizer(model).optimize().policy.let { it.reorderPoint to it.orderQuantity }
        var r = -heldBatch
        var cost = model.cost(r, heldBatch)
        while (true) {
            val next = model.cost(r + 1, heldBatch)
            if (next >= cost) return r to heldBatch
            r += 1
            cost = next
        }
    }

    // ---- The hub -------------------------------------------------------------

    private class HubStep(val reorder: Int, val batch: Int, val cost: Double, val wait: Wait)

    private fun hubStep(qj: Int, heldBatch: Int?): HubStep = when (hubRoute) {
        HubRoute.MOMENTS -> momentHub(qj, heldBatch)
        HubRoute.EXACT -> exactHub(qj, heldBatch)
    }

    /**
     * The hub's lead time demand by the recipe: mean `lambda_0 L_0`, variance
     * `lambda_e,o L_0 E[Y_o^2] + lambda_e,j L_0 E[Y_j^2] + Q_j^2/6`, with a family
     * fitted by @sec-continuousreview-ltd-moments. Returns the model's lead time
     * demand and the hub's demand rate.
     */
    fun momentHubLeadTimeDemand(qj: Int): Pair<LeadTimeDemand, Double> {
        val rate = p.storeRate + p.otherRate
        val otherSecond = p.otherLotVariance + p.otherLotMean * p.otherLotMean
        val storeSecond = p.storeLotVariance + p.storeLotMean * p.storeLotMean
        val mean = rate * p.hubLead
        val variance = p.otherEpochRate * p.hubLead * otherSecond +
            p.storeEpochRate * p.hubLead * storeSecond + if (qj > 1) qj.toDouble() * qj / 6.0 else 0.0
        return LeadTimeDemand.matched(mean, variance) to rate
    }

    private fun momentHub(qj: Int, heldBatch: Int?): HubStep {
        val c = p.hubCosts
        val (ltd, rate) = momentHubLeadTimeDemand(qj)
        val model = RQModel(rate, c.orderCost, c.holdingCost, c.backorderCost, ltd)
        val (r0, q0) = optimize(model, heldBatch)
        val b = model.expectedBackorders(r0, q0)
        val varW = (model.varianceBackorders(r0, q0) - b) / (rate * rate)
        check(varW >= -1e-12) {
            "Var[B_0] < B_0 at the hub's policy ($r0, $q0): the arrivals are not Poisson enough for the " +
                "distributional Little's law, and the wait's variance cannot be taken from it"
        }
        return HubStep(r0, q0, model.cost(r0, q0), Wait(b / rate, max(0.0, varW), null))
    }

    private fun exactHub(qj: Int, heldBatch: Int?): HubStep {
        val c = p.hubCosts
        val streams = listOf(StoreStream(p.storeRate, qj))
        val template = BatchedHub(p.otherRate, streams, 0, 1, p.hubLead, c)
        val ltd = template.leadTimeDemand()
        val rate = template.demandRate
        // With only the storeroom's batches at the hub, positions step in batches.
        val step = if (p.otherRate == 0.0 && qj > 1) qj else 1
        fun cost(r0: Int, q0: Int): Double {
            val hub = BatchedHub(p.otherRate, streams, r0, q0, p.hubLead, c)
            return c.orderCost * rate / q0 + c.holdingCost * hub.expectedOnHand(ltd) + c.backorderCost * hub.expectedBackorders(ltd)
        }
        fun bestReorder(q0: Int): Pair<Int, Double> {
            var r = 0
            var best = cost(r, q0)
            while (true) {
                val next = cost(r + step, q0)
                if (next >= best) return r to best
                r += step
                best = next
            }
        }
        val (r0, q0, hubCost) = if (heldBatch != null) {
            bestReorder(heldBatch).let { Triple(it.first, heldBatch, it.second) }
        } else {
            var best = Triple(0, step, Double.MAX_VALUE)
            var worse = 0
            var q = step
            while (worse < 6) {
                val (r, cq) = bestReorder(q)
                if (cq < best.third) { best = Triple(r, q, cq); worse = 0 } else worse += 1
                q += step
            }
            best
        }
        val delay = HubDelay(BatchedHub(p.otherRate, streams, r0, q0, p.hubLead, c), store = 0)
        val law = delay.fineLaw()
        return HubStep(r0, q0, hubCost, Wait(law.mean, law.variance, law))
    }

    companion object {
        /**
         * The exact cost per unit time of a policy when every customer takes one
         * unit: the hub's ordering and holding, the other customers' waiting at
         * [otherBackorderCost] per unit per unit time, and the storeroom's
         * ordering, holding and backorders, conditioned on its orders' wait.
         */
        fun exactSystemCost(problem: TwoLevelProblem, policy: TwoLevelPolicy, otherBackorderCost: Double): Double {
            require(problem.unitCustomers) { "the exact cost needs customers who each take one unit" }
            val h = problem.hubCosts
            val s = problem.storeCosts
            val hub = BatchedHub(problem.otherRate, listOf(StoreStream(problem.storeRate, policy.storeBatch)),
                policy.hubReorder, policy.hubBatch, problem.hubLead, h)
            val otherWait = if (problem.otherRate > 0.0) HubDelay(hub).mean() else 0.0
            val store = StoreroomWithDelay(problem.storeRate, problem.transit, HubDelay(hub, store = 0).fineLaw(), s)
                .model(StoreroomWithDelay.Route.CONDITIONED)
            return h.orderCost * hub.demandRate / policy.hubBatch + h.holdingCost * hub.expectedOnHand() +
                otherBackorderCost * problem.otherRate * otherWait +
                store.cost(policy.storeReorder, policy.storeBatch)
        }
    }
}
