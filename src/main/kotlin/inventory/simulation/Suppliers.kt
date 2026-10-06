package inventory.simulation

import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.UniformRV

/**
 * An outside supplier with unlimited stock that delivers each order after a
 * lead time drawn from [leadTime].
 *
 * With [inSequence] false, each order takes its own lead time and a later order
 * can arrive first. With [inSequence] true, orders queue for the supplier, and an
 * order is received at its own lead time or at the receipt of the order before
 * it, whichever is later, so none overtakes another. @exm-sim-crossing.
 *
 * The time each order is outstanding is recorded, and so is whether it arrived
 * ahead of an order placed before it.
 */
class LeadTimeSupplier(
    parent: ModelElement,
    leadTime: RVariableIfc,
    val inSequence: Boolean = false,
    name: String? = null,
) : ModelElement(parent, name), InventoryFillerIfc {

    private val myLeadTime = RandomVariable(this, leadTime, "${this.name}:LeadTime")
    private val myOutstandingTime = Response(this, "${this.name}:TimeOutstanding")
    private val myOvertook = Response(this, "${this.name}:Overtook")

    val timeOutstandingResponse: ResponseCIfc get() = myOutstandingTime
    /** One observation per order received: 1 if it arrived ahead of an earlier order, else 0. */
    val overtookResponse: ResponseCIfc get() = myOvertook

    private class Shipment(val amount: Int, val receiver: StockReceiverIfc, val placed: Double, val sequence: Long)

    private var nextSequence = 0L
    private var lastDue = 0.0
    private val outstanding = sortedSetOf<Long>()

    override fun initialize() {
        super.initialize()
        nextSequence = 0L
        lastDue = 0.0
        outstanding.clear()
    }

    override fun fill(amount: Int, receiver: StockReceiverIfc) {
        var due = time + myLeadTime.value
        if (inSequence) due = maxOf(due, lastDue)
        lastDue = maxOf(lastDue, due)
        val s = Shipment(amount, receiver, time, nextSequence++)
        outstanding.add(s.sequence)
        schedule(::arrive, due - time, message = s)
    }

    private fun arrive(event: KSLEvent<Shipment>) {
        val s = event.message!!
        myOvertook.value = if (outstanding.first() < s.sequence) 1.0 else 0.0
        outstanding.remove(s.sequence)
        myOutstandingTime.value = time - s.placed
        s.receiver.receive(s.amount)
    }
}

/**
 * Makes a stock point a supplier: an order sent through the link is a demand on
 * [upper], and every unit [upper] ships reaches the orderer after [transit].
 * @sec-simulation-design-nouns.
 */
class TransitLink(
    parent: ModelElement,
    val upper: SimInventory,
    transit: RVariableIfc,
    name: String? = null,
) : ModelElement(parent, name), InventoryFillerIfc {

    private val myTransit = RandomVariable(this, transit, "${this.name}:Transit")

    private class Delivery(val amount: Int, val receiver: StockReceiverIfc)

    override fun fill(amount: Int, receiver: StockReceiverIfc) {
        upper.fill(amount) { shipped -> schedule(::deliver, myTransit, message = Delivery(shipped, receiver)) }
    }

    private fun deliver(event: KSLEvent<Delivery>) {
        val d = event.message!!
        d.receiver.receive(d.amount)
    }
}

/**
 * Sends each order to one of several fillers, chosen at random with the given
 * probabilities, as a storeroom with its own repair shop sends some failures
 * there and the rest to the depot, @exm-sim-metric.
 */
class RoutingFiller(
    parent: ModelElement,
    private val routes: List<Pair<InventoryFillerIfc, Double>>,
    streamNum: Int = 0,
    name: String? = null,
) : ModelElement(parent, name), InventoryFillerIfc {

    init {
        require(routes.isNotEmpty()) { "a routing filler needs at least one route" }
        require(routes.all { it.second >= 0.0 }) { "route probabilities cannot be negative" }
        require(kotlin.math.abs(routes.sumOf { it.second } - 1.0) < 1e-9) { "route probabilities must sum to one" }
    }

    private val myChoice = RandomVariable(this, UniformRV(0.0, 1.0, streamNum), "${this.name}:Route")

    override fun fill(amount: Int, receiver: StockReceiverIfc) {
        val u = myChoice.value
        var cumulative = 0.0
        for ((route, p) in routes) {
            cumulative += p
            if (u < cumulative) { route.fill(amount, receiver); return }
        }
        routes.last().first.fill(amount, receiver)
    }
}
