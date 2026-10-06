package inventory.simulation

import ksl.modeling.queue.Queue
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.modeling.variable.TWResponseFunction
import ksl.simulation.ModelElement

/**
 * A stock point for one item, the core of @sec-simulation-rq.
 *
 * Adapted from the abstract `Inventory` of the (r, Q) model in *Simulation
 * Modeling using the KSL*. The structure is that of @tbl-sim-events: a demand
 * ([demand], or [fill] when the demand is another stock point's order) and a
 * receipt ([receiveReplenishment]) change the state, and each calls the
 * decision, [checkPosition], which a policy overrides.
 *
 * The state variables of @sec-continuousreview-policy are time-weighted
 * responses, so their averages are the areas of @eq-sim-area divided by time.
 * Unmet demand is backordered and filled first come first served, a lot being
 * filled in part when the shelf holds part of it, or, when [lostSales] is set,
 * the unfilled part departs.
 *
 * Measures collected, per @sec-simulation-output-fill: the lot fill rate (one
 * observation per customer demand), the unit fill rate and the ready rate; and,
 * per replication, the order frequency and the cost terms of @eq-rq-cost, with
 * [shortageCost] charged per unit a customer was not given on arrival. Orders
 * from a lower stock point are kept apart from customers: they are counted in
 * the state variables, which they change, but their waits and whether they were
 * filled on arrival are separate measures, since a hub's own customers and the
 * storerooms it supplies are different questions.
 */
abstract class SimInventory(
    parent: ModelElement,
    initialOnHand: Int,
    filler: InventoryFillerIfc?,
    name: String? = null,
) : ModelElement(parent, name), InventoryFillerIfc {

    init {
        require(initialOnHand >= 0) { "the initial on-hand must be >= 0" }
    }

    /** Where this stock point's orders go. Settable so that two stock points can be wired together. */
    var filler: InventoryFillerIfc? = filler

    /** Whether unmet demand departs rather than waits, @sec-simulation-beyond-lostsales. */
    var lostSales: Boolean = false
        set(value) {
            require(model.isNotRunning) { "lost sales cannot be switched while the model runs" }
            field = value
        }

    /** k, dollars per order placed. */
    var orderCost: Double = 0.0
    /** h, dollars per unit per unit time on hand. */
    var holdingCost: Double = 0.0
    /** b, dollars per unit per unit time owed. */
    var backorderCost: Double = 0.0
    /** pi, dollars per unit not filled on arrival, whether backordered or lost. */
    var shortageCost: Double = 0.0

    // ---- the state variables, @eq-sim-area ------------------------------------------------

    private val myOnHand = TWResponse(this, "${this.name}:OnHand", initialOnHand.toDouble())
    private val myOnOrder = TWResponse(this, "${this.name}:OnOrder")
    private val myBackordered = TWResponse(this, "${this.name}:Backordered")
    private val myStockIndicator = TWResponseFunction({ x -> if (x > 0.0) 1.0 else 0.0 },
        myOnHand, "${this.name}:ReadyRate")

    var initialOnHand: Int
        get() = myOnHand.initialValue.toInt()
        set(value) {
            require(model.isNotRunning) { "the initial on-hand cannot change while the model runs" }
            require(value >= 0) { "the initial on-hand must be >= 0" }
            myOnHand.initialValue = value.toDouble()
        }

    val onHand: Int get() = myOnHand.value.toInt()
    val onOrder: Int get() = myOnOrder.value.toInt()
    val backordered: Int get() = myBackordered.value.toInt()

    /** IP = I + IO - B. */
    val inventoryPosition: Int get() = onHand + onOrder - backordered

    val onHandResponse: TWResponseCIfc get() = myOnHand
    val onOrderResponse: TWResponseCIfc get() = myOnOrder
    val backorderedResponse: TWResponseCIfc get() = myBackordered
    val readyRateResponse: TWResponseCIfc get() = myStockIndicator

    // ---- observation-based measures, @sec-simulation-output-fill ---------------------------

    private val myLotFill = Response(this, "${this.name}:LotFillRate")
    private val myUnitsDemanded = Counter(this, "${this.name}:UnitsDemanded")
    private val myUnitsFilled = Counter(this, "${this.name}:UnitsFilledOnArrival")
    private val myUnitsShort = Counter(this, "${this.name}:UnitsShort")
    private val myUnitsLost = Counter(this, "${this.name}:UnitsLost")
    private val myUnitFill = Response(this, "${this.name}:UnitFillRate")
    private val myCustomerWait = Response(this, "${this.name}:CustomerWait")
    private val myOrderWait = Response(this, "${this.name}:OrderWait")
    private val myOrderFilledOnArrival = Response(this, "${this.name}:OrdersFilledOnArrival")
    private val myOrders = Counter(this, "${this.name}:Orders")
    private val myOrderSize = Response(this, "${this.name}:OrderSize")
    private val myOrderFrequency = Response(this, "${this.name}:OrderFrequency")
    private val myLostRate = Response(this, "${this.name}:UnitsLostPerTime")

    val lotFillRateResponse: ResponseCIfc get() = myLotFill
    val unitFillRateResponse: ResponseCIfc get() = myUnitFill
    /** The time from a customer demand's arrival until it is filled complete, zero when filled at once. */
    val customerWaitResponse: ResponseCIfc get() = myCustomerWait
    /** The time from a lower stock point's order arriving until its last unit ships, zero when shipped at once. */
    val orderWaitResponse: ResponseCIfc get() = myOrderWait
    /** One observation per order from below: 1 if shipped complete on arrival, else 0. */
    val orderFilledOnArrivalResponse: ResponseCIfc get() = myOrderFilledOnArrival
    val ordersCounter: CounterCIfc get() = myOrders
    val orderSizeResponse: ResponseCIfc get() = myOrderSize
    val orderFrequencyResponse: ResponseCIfc get() = myOrderFrequency
    val unitsLostPerTimeResponse: ResponseCIfc get() = myLostRate

    // ---- the cost terms, @eq-rq-cost --------------------------------------------------------

    private val myOrderingCost = Response(this, "${this.name}:OrderingCost")
    private val myHoldingCostRate = Response(this, "${this.name}:HoldingCost")
    private val myBackorderCostRate = Response(this, "${this.name}:BackorderCost")
    private val myShortageCostRate = Response(this, "${this.name}:ShortageCost")
    private val myTotalCost = Response(this, "${this.name}:TotalCost")

    val orderingCostResponse: ResponseCIfc get() = myOrderingCost
    val holdingCostResponse: ResponseCIfc get() = myHoldingCostRate
    val backorderCostResponse: ResponseCIfc get() = myBackorderCostRate
    val shortageCostResponse: ResponseCIfc get() = myShortageCostRate
    val totalCostResponse: ResponseCIfc get() = myTotalCost

    // ---- the backorder queue ----------------------------------------------------------------

    /** A demand still owed: how much, who gets it, and when it arrived. */
    private inner class Owed(var needed: Int, val receiver: StockReceiverIfc?, val arrived: Double) : QObject()

    private val myOwed: Queue<Owed> = Queue(this, "${this.name}:Owed")

    /** Number of demands waiting, and how long each waited, as the KSL queue reports them. */
    val owedQueue: Queue<*> get() = myOwed

    // ---- a trace, for checking the model against a hand ledger ------------------------------

    /** The state after an event, with the areas of @eq-sim-area through the event's time. */
    data class Snapshot(
        val time: Double, val event: String,
        val onHand: Int, val backordered: Int, val position: Int,
        val areaOnHand: Double, val areaBackordered: Double, val timeWithStock: Double,
    )

    /** Observes the wait of each order from below as it is recorded, when set. For the wait's distribution. */
    var orderWaitObserver: ((Double) -> Unit)? = null

    private fun recordWait(w: Double, fromBelow: Boolean) {
        if (fromBelow) {
            myOrderWait.value = w
            orderWaitObserver?.invoke(w)
        } else {
            myCustomerWait.value = w
        }
    }

    /** Called after every event with the state it left, when set. @exm-sim-ledger uses it. */
    var tracer: ((Snapshot) -> Unit)? = null

    private var orderNote = ""
    private var lastRequestFilled = true

    protected fun trace(event: String) {
        val note = orderNote
        orderNote = ""
        tracer?.invoke(
            Snapshot(time, event + note, onHand, backordered, inventoryPosition,
                myOnHand.withinReplicationWeightedSum, myBackordered.withinReplicationWeightedSum,
                myStockIndicator.withinReplicationWeightedSum)
        )
    }

    // ---- the events -------------------------------------------------------------------------

    /** A customer demand for [amount] units, the demand event of @tbl-sim-events. */
    fun demand(amount: Int) {
        request(amount, null)
        trace(if (lastRequestFilled) "demand" else if (lostSales) "demand lost" else "demand backordered")
    }

    /** Another stock point's order, treated as a demand whose units are shipped to [receiver]. */
    override fun fill(amount: Int, receiver: StockReceiverIfc) {
        request(amount, receiver)
        trace("order from below")
    }

    private fun request(amount: Int, receiver: StockReceiverIfc?) {
        require(amount > 0) { "a demand must be for at least one unit" }
        val fromBelow = receiver != null
        val now = minOf(onHand, amount)
        if (now > 0) {
            myOnHand.decrement(now.toDouble())
            receiver?.receive(now)
        }
        val rest = amount - now
        if (fromBelow) {
            myOrderFilledOnArrival.value = if (rest == 0) 1.0 else 0.0
        } else {
            myUnitsDemanded.increment(amount.toDouble())
            if (now > 0) myUnitsFilled.increment(now.toDouble())
            myLotFill.value = if (rest == 0) 1.0 else 0.0
        }
        lastRequestFilled = rest == 0
        if (rest == 0) {
            recordWait(0.0, fromBelow)
        } else {
            if (!fromBelow) myUnitsShort.increment(rest.toDouble())
            if (lostSales) {
                if (!fromBelow) myUnitsLost.increment(rest.toDouble())
            } else {
                myBackordered.increment(rest.toDouble())
                myOwed.enqueue(Owed(rest, receiver, time))
            }
        }
        checkPosition()
    }

    /** The receipt event of @tbl-sim-events: units move from on order to on hand, then fill what is owed. */
    fun receiveReplenishment(amount: Int) {
        myOnOrder.decrement(amount.toDouble())
        myOnHand.increment(amount.toDouble())
        fillOwed()
        checkPosition()
        trace("receipt of $amount")
    }

    private fun fillOwed() {
        while (myOwed.isNotEmpty && onHand > 0) {
            val owed = myOwed.peekNext()!!
            val k = minOf(onHand, owed.needed)
            myOnHand.decrement(k.toDouble())
            myBackordered.decrement(k.toDouble())
            owed.needed -= k
            owed.receiver?.receive(k)
            if (owed.needed == 0) {
                myOwed.removeNext()
                recordWait(time - owed.arrived, owed.receiver != null)
            }
        }
    }

    /** The decision of @tbl-sim-events. A policy overrides it; periodic review leaves it empty. */
    protected abstract fun checkPosition()

    /** Places an order for [amount] units with [filler]. */
    protected fun placeOrder(amount: Int) {
        require(amount > 0) { "an order must be for at least one unit" }
        val supplier = checkNotNull(filler) { "${this.name} has no filler to order from" }
        myOnOrder.increment(amount.toDouble())
        myOrders.increment()
        myOrderSize.value = amount.toDouble()
        orderNote += ", order $amount"
        supplier.fill(amount, ::receiveReplenishment)
    }

    override fun initialize() {
        super.initialize()
        checkPosition()
    }

    override fun replicationEnded() {
        val observed = time - model.lengthOfReplicationWarmUp
        if (myUnitsDemanded.value > 0.0) myUnitFill.value = myUnitsFilled.value / myUnitsDemanded.value
        myOrderFrequency.value = myOrders.value / observed
        myLostRate.value = myUnitsLost.value / observed
        myOrderingCost.value = orderCost * myOrderFrequency.value
        myHoldingCostRate.value = holdingCost * myOnHand.withinReplicationStatistic.weightedAverage
        myBackorderCostRate.value = backorderCost * myBackordered.withinReplicationStatistic.weightedAverage
        myShortageCostRate.value = shortageCost * myUnitsShort.value / observed
        myTotalCost.value = myOrderingCost.value + myHoldingCostRate.value +
            myBackorderCostRate.value + myShortageCostRate.value
    }
}
