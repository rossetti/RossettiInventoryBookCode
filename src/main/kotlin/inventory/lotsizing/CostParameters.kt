package inventory.lotsizing

/** The time base every rate in a parameter set shares. */
enum class TimeUnit(val label: String) {
    YEAR("year"), MONTH("month"), WEEK("week"), DAY("day")
}

/**
 * How a replenishment arrives. @sec-eoq-assumptions.
 *
 * The two cases are separate types rather than a rate that might be infinite,
 * because the classical order model is the limit as the rate grows without bound
 * and a stand-in for that limit produces silent NaN in the general formulas.
 */
sealed interface Replenishment {

    /**
     * The fraction of an order that survives replenishment to become stock, written
     * (1 - lambda/p) in the text. It is 1 when the whole order arrives at once, and
     * it is the factor that recurs throughout Chapter 3.
     */
    fun survivingFraction(demandRate: Double): Double

    /** The net rate at which net inventory climbs while replenishment is under way. */
    fun buildUpRate(demandRate: Double): Double

    /** The whole order arrives at one instant. */
    data object Instantaneous : Replenishment {
        override fun survivingFraction(demandRate: Double): Double = 1.0
        override fun buildUpRate(demandRate: Double): Double = Double.POSITIVE_INFINITY
    }

    /** The order is produced or delivered continuously at [rate] units per unit time. */
    data class AtRate(val rate: Double) : Replenishment {
        init { require(rate > 0.0) { "A replenishment rate must be positive, was $rate" } }
        override fun survivingFraction(demandRate: Double): Double = 1.0 - demandRate / rate
        override fun buildUpRate(demandRate: Double): Double = rate - demandRate
    }

    companion object {
        /**
         * A finite replenishment rate, checked against the demand rate it must exceed.
         * A rate at or below the demand rate makes the cycle of @sec-eoq-cycle not exist,
         * so it is refused here rather than producing a negative holding rate later.
         */
        fun atRate(rate: Double, demandRate: Double): AtRate {
            require(rate > demandRate) {
                "The replenishment rate must exceed the demand rate, but $rate is not greater than $demandRate"
            }
            return AtRate(rate)
        }
    }
}

/**
 * Whether unsatisfied demand waits, and at what prices. @sec-eoq-nobackorders.
 *
 * Forbidding shortages is [NotPermitted] and is never a zero backorder cost. A zero
 * backorder cost makes shortages free rather than forbidden, and sends the order
 * quantity to infinity. That mistake is not expressible here.
 */
sealed interface ShortagePolicy {

    /** No shortage is allowed. The maximum backorder level is zero. */
    data object NotPermitted : ShortagePolicy

    /**
     * Unsatisfied demand waits.
     *
     * @param costPerUnitPerTime what waiting costs, charged on the average backorder
     *   level. This is b in the text.
     * @param costPerUnit what the incident of being short costs, charged once per
     *   unit backordered. This is pi in the text, and it is zero in the common case.
     */
    data class Backordered(
        val costPerUnitPerTime: Double,
        val costPerUnit: Double = 0.0,
    ) : ShortagePolicy {
        init {
            require(costPerUnitPerTime > 0.0) {
                "A backorder cost of zero makes shortages free rather than forbidden. " +
                    "Use ShortagePolicy.NotPermitted, which is the limit as the cost grows " +
                    "without bound. See @sec-eoq-nobackorders."
            }
            require(costPerUnit >= 0.0) { "A stockout cost cannot be negative, was $costPerUnit" }
        }
    }

    companion object {
        fun backordered(costPerUnitPerTime: Double, costPerUnit: Double = 0.0) =
            Backordered(costPerUnitPerTime, costPerUnit)
    }
}

/** How the cost of holding a unit is supplied: as a charge on value, or directly. */
sealed interface HoldingCost {

    /** A fraction of the unit's value per unit time. This is i in the text. */
    data class CarryingCharge(val rate: Double) : HoldingCost {
        init { require(rate > 0.0) { "A carrying charge must be positive, was $rate" } }
    }

    /** Currency per unit per unit time, supplied directly. This is h in the text. */
    data class Rate(val amount: Double) : HoldingCost {
        init { require(amount > 0.0) { "A holding cost rate must be positive, was $amount" } }
    }

    companion object {
        fun carryingCharge(rate: Double) = CarryingCharge(rate)
        fun rate(amount: Double) = Rate(amount)
    }
}

/**
 * A read-only view of what is true about an item and its supply process.
 *
 * Everything above the input layer depends on this rather than on the mutable
 * [CostParameters], so nothing downstream can change a parameter set. Both
 * [CostParameters] and [ParameterSnapshot] implement it.
 */
interface CostParametersIfc {
    val demandRate: Double
    val orderCost: Double
    val schedule: PriceSchedule
    val holding: HoldingCost
    val replenishment: Replenishment
    val shortages: ShortagePolicy
    val leadTime: Double
    val positionCost: Double
    val timeUnit: TimeUnit

    /** The price per unit paid on an order of this size. */
    fun unitCostAt(orderQuantity: Double): Double = schedule.unitCostAt(orderQuantity)

    /**
     * The holding cost rate applying at this order size. It is constant when the
     * holding cost was supplied directly, and it depends on the quantity under an
     * incremental discount, where the value held is the average price paid.
     */
    fun holdingRateAt(orderQuantity: Double): Double = when (val h = holding) {
        is HoldingCost.Rate -> h.amount
        is HoldingCost.CarryingCharge -> h.rate * unitCostAt(orderQuantity)
    }

    /** The fraction of an order that survives replenishment to become stock. */
    val survivingFraction: Double
        get() = replenishment.survivingFraction(demandRate)

    /**
     * The price of the schedule's first level. A model that assumes a single price
     * uses this, and for a flat schedule it is the only price there is.
     */
    val baseUnitCost: Double
        get() = schedule.levels.first().unitCost

    /**
     * The holding cost rate implied by [baseUnitCost]. The closed-form models of
     * @sec-eoq-special assume a single holding rate; under a discount schedule they are
     * approximating, and this is the rate they approximate with.
     */
    val baseHoldingRate: Double
        get() = when (val h = holding) {
            is HoldingCost.Rate -> h.amount
            is HoldingCost.CarryingCharge -> h.rate * baseUnitCost
        }

    /** The maximum backorder level this policy permits. */
    val permitsShortages: Boolean
        get() = shortages is ShortagePolicy.Backordered

    /** An immutable record of the present values, for a result to carry. */
    fun snapshot(): ParameterSnapshot = ParameterSnapshot(
        demandRate = demandRate,
        orderCost = orderCost,
        schedule = schedule,
        holding = holding,
        replenishment = replenishment,
        shortages = shortages,
        leadTime = leadTime,
        positionCost = positionCost,
        timeUnit = timeUnit,
    )
}

/**
 * What is true about an item and its supply process, in a form that can be edited.
 *
 * Every property validates on assignment with the same predicate the constructor
 * applies, so there is no window in which an invalid parameter set exists. Editing
 * is how a reader explores: setting [orderCost] and re-optimizing is the whole of a
 * what-if. Results are unaffected, because each carries a [snapshot].
 *
 * Not thread-safe. Use one instance per worker.
 */
class CostParameters(
    demandRate: Double,
    schedule: PriceSchedule,
    orderCost: Double,
    holding: HoldingCost,
    replenishment: Replenishment = Replenishment.Instantaneous,
    shortages: ShortagePolicy = ShortagePolicy.NotPermitted,
    leadTime: Double = 0.0,
    positionCost: Double = 0.0,
    override val timeUnit: TimeUnit = TimeUnit.YEAR,
) : CostParametersIfc {

    /** Convenience for the common case of a single price. */
    constructor(
        demandRate: Double,
        unitCost: Double,
        orderCost: Double,
        holding: HoldingCost,
        replenishment: Replenishment = Replenishment.Instantaneous,
        shortages: ShortagePolicy = ShortagePolicy.NotPermitted,
        leadTime: Double = 0.0,
        positionCost: Double = 0.0,
        timeUnit: TimeUnit = TimeUnit.YEAR,
    ) : this(demandRate, Flat(unitCost), orderCost, holding, replenishment, shortages,
             leadTime, positionCost, timeUnit)

    override var demandRate: Double = demandRate
        set(value) {
            require(value > 0.0) { "The demand rate must be positive, was $value" }
            requireConsistent(value, replenishment)
            field = value
        }

    override var schedule: PriceSchedule = schedule

    override var orderCost: Double = orderCost
        set(value) {
            require(value >= 0.0) { "The ordering cost cannot be negative, was $value" }
            field = value
        }

    override var holding: HoldingCost = holding

    override var replenishment: Replenishment = replenishment
        set(value) {
            requireConsistent(demandRate, value)
            field = value
        }

    override var shortages: ShortagePolicy = shortages

    override var leadTime: Double = leadTime
        set(value) {
            require(value >= 0.0) { "The lead time cannot be negative, was $value" }
            field = value
        }

    override var positionCost: Double = positionCost
        set(value) {
            require(value >= 0.0) { "The stocking position cost cannot be negative, was $value" }
            field = value
        }

    /** The carrying charge, when the holding cost was supplied as one. */
    var carryingCharge: Double
        get() = (holding as? HoldingCost.CarryingCharge)?.rate
            ?: error("The holding cost was supplied as a rate, not as a carrying charge")
        set(value) { holding = HoldingCost.CarryingCharge(value) }

    /** The holding cost rate, when it was supplied directly. */
    var holdingRate: Double
        get() = (holding as? HoldingCost.Rate)?.amount
            ?: error("The holding cost was supplied as a carrying charge, not as a rate")
        set(value) { holding = HoldingCost.Rate(value) }

    /** The single unit cost, when the schedule is flat. */
    var unitCost: Double
        get() = (schedule as? Flat)?.unitCost
            ?: error("The price schedule is not flat; read it through unitCostAt(orderQuantity)")
        set(value) { schedule = Flat(value) }

    /** The finite replenishment rate, or null when replenishment is instantaneous. */
    var replenishmentRate: Double?
        get() = (replenishment as? Replenishment.AtRate)?.rate
        set(value) {
            replenishment = if (value == null) Replenishment.Instantaneous
                            else Replenishment.atRate(value, demandRate)
        }

    init {
        require(demandRate > 0.0) { "The demand rate must be positive, was $demandRate" }
        require(orderCost >= 0.0) { "The ordering cost cannot be negative, was $orderCost" }
        require(leadTime >= 0.0) { "The lead time cannot be negative, was $leadTime" }
        require(positionCost >= 0.0) { "The stocking position cost cannot be negative, was $positionCost" }
        requireConsistent(demandRate, replenishment)
    }

    /** An independent parameter set with the same values, which may be edited freely. */
    fun copy(): CostParameters = CostParameters(
        demandRate, schedule, orderCost, holding, replenishment, shortages,
        leadTime, positionCost, timeUnit,
    )

    override fun toString(): String =
        "CostParameters(demandRate=$demandRate per ${timeUnit.label}, schedule=$schedule, " +
            "orderCost=$orderCost, holding=$holding, replenishment=$replenishment, " +
            "shortages=$shortages, leadTime=$leadTime)"

    private companion object {
        fun requireConsistent(demandRate: Double, replenishment: Replenishment) {
            if (replenishment is Replenishment.AtRate) {
                require(replenishment.rate > demandRate) {
                    "The replenishment rate must exceed the demand rate, but " +
                        "${replenishment.rate} is not greater than $demandRate"
                }
            }
        }
    }
}
