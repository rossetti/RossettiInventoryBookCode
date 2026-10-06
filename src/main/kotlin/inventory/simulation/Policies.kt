package inventory.simulation

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * The (r, Q) policy, @sec-continuousreview-policy, as a stock point.
 *
 * The decision of @tbl-sim-events orders the fewest whole batches that lift the
 * position above r, `floor((r - IP)/Q) + 1` of them. With demand one unit at a
 * time that is always one batch; under job lots it can be more.
 */
class RQSimInventory(
    parent: ModelElement,
    reorderPoint: Int,
    orderQuantity: Int,
    initialOnHand: Int = reorderPoint + orderQuantity,
    filler: InventoryFillerIfc? = null,
    name: String? = null,
) : SimInventory(parent, initialOnHand, filler, name) {

    init {
        require(orderQuantity > 0) { "the order quantity must be positive" }
        require(reorderPoint >= -orderQuantity) { "the reorder point must be at least -Q" }
    }

    val reorderPoint: Int = reorderPoint
    val orderQuantity: Int = orderQuantity

    override fun checkPosition() {
        if (inventoryPosition <= reorderPoint) {
            val batches = (reorderPoint - inventoryPosition) / orderQuantity + 1
            placeOrder(batches * orderQuantity)
        }
    }
}

/**
 * The (s, S) policy, @sec-continuousreview-sS: when the position reaches s or
 * below, order up to S. Records the undershoot of @eq-undershoot at every order.
 */
class SSSimInventory(
    parent: ModelElement,
    reorderPoint: Int,
    orderUpToLevel: Int,
    initialOnHand: Int = orderUpToLevel,
    filler: InventoryFillerIfc? = null,
    name: String? = null,
) : SimInventory(parent, initialOnHand, filler, name) {

    init {
        require(orderUpToLevel > reorderPoint) { "S must exceed s" }
    }

    val reorderPoint: Int = reorderPoint
    val orderUpToLevel: Int = orderUpToLevel

    private val myUndershoot = Response(this, "${this.name}:Undershoot")
    val undershootResponse: ResponseCIfc get() = myUndershoot

    /** Observes each undershoot as it is recorded, when set. For the distribution of @eq-undershoot-dist. */
    var undershootObserver: ((Int) -> Unit)? = null

    override fun checkPosition() {
        val ip = inventoryPosition
        if (ip <= reorderPoint) {
            val u = reorderPoint - ip
            myUndershoot.value = u.toDouble()
            undershootObserver?.invoke(u)
            placeOrder(orderUpToLevel - ip)
        }
    }
}

/**
 * The (R, s, S) policy, @sec-continuousreview-periodic-rss, and (R, S) as the
 * case s = S - 1. The only change from continuous review is that the decision
 * belongs to a third event, the review, which the policy schedules every R
 * time units from [firstReview]; a demand takes no decision.
 * @sec-simulation-periodic.
 *
 * Reviews and orders are counted separately, because a review that finds the
 * position above s orders nothing, @eq-periodic-orderfreq.
 */
class PeriodicSimInventory(
    parent: ModelElement,
    reviewInterval: Double,
    reorderPoint: Int,
    orderUpToLevel: Int,
    initialOnHand: Int = orderUpToLevel,
    filler: InventoryFillerIfc? = null,
    val firstReview: Double = 0.0,
    name: String? = null,
) : SimInventory(parent, initialOnHand, filler, name) {

    init {
        require(reviewInterval > 0.0) { "the review interval must be positive" }
        require(orderUpToLevel > reorderPoint) { "S must exceed s" }
        require(firstReview >= 0.0) { "the first review cannot be before time 0" }
    }

    val reviewInterval: Double = reviewInterval
    val reorderPoint: Int = reorderPoint
    val orderUpToLevel: Int = orderUpToLevel

    private val myReviews = Counter(this, "${this.name}:Reviews")
    private val myReviewsOrdering = Counter(this, "${this.name}:ReviewsThatOrder")
    private val myUndershoot = Response(this, "${this.name}:Undershoot")
    private val myOrderingFraction = Response(this, "${this.name}:FractionOfReviewsThatOrder")

    val reviewsCounter: CounterCIfc get() = myReviews
    val undershootResponse: ResponseCIfc get() = myUndershoot
    val orderingFractionResponse: ResponseCIfc get() = myOrderingFraction

    /** A demand takes no decision under periodic review. */
    override fun checkPosition() {}

    override fun initialize() {
        super.initialize()
        schedule(::review, firstReview)
    }

    private fun review(event: KSLEvent<Nothing>) {
        myReviews.increment()
        val ip = inventoryPosition
        if (ip <= reorderPoint) {
            myReviewsOrdering.increment()
            myUndershoot.value = (reorderPoint - ip).toDouble()
            placeOrder(orderUpToLevel - ip)
        }
        trace("review")
        schedule(::review, reviewInterval)
    }

    override fun replicationEnded() {
        super.replicationEnded()
        if (myReviews.value > 0.0) myOrderingFraction.value = myReviewsOrdering.value / myReviews.value
    }
}
