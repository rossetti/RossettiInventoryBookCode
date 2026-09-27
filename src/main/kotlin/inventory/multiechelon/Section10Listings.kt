package inventory.multiechelon

import inventory.multiechelon.StoreroomWithDelay.Route
import inventory.multiechelon.TwoLevelIteration.HubRoute
import inventory.multiechelon.TwoLevelIteration.StoreRoute

/**
 * Generates every listing @sec-batchedmultiechelon-design prints.
 *
 * ```
 *   ./gradlew run -PmainClass=inventory.multiechelon.Section10ListingsKt
 * ```
 *
 * The tests of this package assert the same values, so a disagreement fails the
 * build rather than waiting to be noticed here.
 */
fun main() {
    fun rule(title: String) { println(); println("### $title") }
    val h = 28.75 / 52
    val b = 287.50 / 52
    val costs = RQCosts(orderCost = 0.0, holdingCost = h, backorderCost = b)

    rule("the hub of Part I and the wait it imposes")
    val hub = BatchedHub(
        otherRate = 0.0,
        stores = List(4) { StoreStream(lambda = 1.0, q = 1) },
        reorder = 9, batch = 8, lead = 3.0, costs = costs,
    )
    val delay = HubDelay(hub)
    println("  ready rate %.4f, backorders %.4f, Little %.4f weeks".format(
        hub.readyRate(), hub.expectedBackorders(), hub.littleWait()))
    for (w in listOf(0.0, 1.0, 2.0)) println("  P(W <= %.0f) = %.4f".format(w, delay.cdf(w)))
    println("  E[W] %.4f, Var[W] %.4f".format(delay.mean(), delay.variance()))

    rule("Axsater's exact cost, and the storeroom three ways")
    val oneForOne = OneForOneTwoLevel(storeRate = 1.0, stores = 4, transit = 1.0, hubLead = 3.0,
        storeHolding = h, hubHolding = h, shortage = b)
    val exact = AxsaterBatchOrdering(oneForOne, storeBatch = 1, hubBatches = 8)
    println("  exact cost %.4f a week, backorders per storeroom %.4f".format(
        exact.cost(hubReorder = 9, storeReorder = 2), exact.measures(9, 2).storeBackorders / 4))
    val store = StoreroomWithDelay(lambda = 1.0, transit = 1.0, delay = delay.fineLaw(), costs = costs)
    for (route in Route.values()) {
        println("  %-12s backorders %.4f".format(route, store.model(route).expectedBackorders(2, 1)))
    }

    rule("Part II: the order-count law and the hub with other demand")
    val law = OrderCountLaw(lambda = 2.0, q = 3)
    println("  at a random moment: " + law.stationary(3.0).take(5).joinToString { "%.4f".format(it) })
    println("  seen from an order: " + law.seenFromOrder(3.0).take(5).joinToString { "%.4f".format(it) })
    val hub2 = BatchedHub(otherRate = 2.0, stores = listOf(StoreStream(2.0, 3)),
        reorder = 12, batch = 8, lead = 3.0, costs = costs)
    println("  variance: exact %.4f, recipe %.4f, shortcut %.4f".format(
        hub2.leadTimeDemand().variance, hub2.recipe(3.0).variance, hub2.compoundPoissonShortcut(3.0).variance))
    val order = HubDelay(hub2, store = 0)
    println("  the order's wait: P(W = 0) %.4f, mean %.4f".format(order.cdf(0.0), order.mean()))
    val store2 = StoreroomWithDelay(2.0, 1.0, order.fineLaw(), costs)
    println("  storeroom backorders, conditioned: %.4f".format(
        store2.model(Route.CONDITIONED).expectedBackorders(3, 3)))

    rule("Part III: the two-level iteration")
    val problem = TwoLevelProblem(
        storeEpochRate = 2.0, storeLots = TwoLevelProblem.ONE_UNIT,
        otherEpochRate = 2.0, otherLots = TwoLevelProblem.ONE_UNIT,
        hubLead = 3.0, transit = 1.0,
        hubCosts = RQCosts(82.5, h, TwoLevelProblem.backorderCostForTarget(0.8, h)),
        storeCosts = RQCosts(82.5, h, b),
    )
    for ((hr, sr) in listOf(HubRoute.MOMENTS to StoreRoute.MOMENTS, HubRoute.EXACT to StoreRoute.CONDITIONED)) {
        val result = TwoLevelIteration(problem, hr, sr).solve()
        println("  %s route, %d passes:".format(hr.name.lowercase(), result.passes.size))
        for (p in result.passes) println("    %d  %s  E[W] %.4f".format(p.pass, p.policy, p.meanWait))
        println("    priced exactly: %.4f a week".format(TwoLevelIteration.exactSystemCost(problem, result.policy, b)))
    }
}
