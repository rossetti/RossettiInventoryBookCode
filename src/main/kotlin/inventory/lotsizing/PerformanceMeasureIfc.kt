package inventory.lotsizing

/**
 * The cost measures of @sec-performance-cost, every one a rate in currency per [timeUnit]
 * unless noted.
 *
 * These are the terms of @eq-total-cost together with the three other cost-based
 * measures the same section names.
 */
interface CostMeasureIfc {

    /** k times the order frequency. */
    val ordering: Double

    /** h times the average on-hand level. */
    val holding: Double

    /** b times the average backorder level. Zero where shortages are forbidden. */
    val backorder: Double

    /** pi times the rate at which units are backordered. Zero where pi is zero. */
    val stockout: Double

    /** The stocking position charge, f, incurred for carrying the item at all. */
    val position: Double

    /** c times the demand rate. Identical under every replenishment policy. */
    val purchase: Double

    /**
     * The part of the total that a replenishment decision moves: ordering, holding,
     * backorder, and stockout. This is C of @eq-relevant-cost, and it is what a cost
     * penalty is computed on.
     */
    val relevantCost: Double

    /** Every term. This is TC of @eq-total-cost. */
    val totalCost: Double

    /** The value of the stock held, c times the average on-hand level, in currency. */
    val investment: Double

    /** Total cost divided by the demand rate, which normalizes for volume. */
    val costPerUnitDemanded: Double

    /** The demand rate divided by the average on-hand level, per [timeUnit]. */
    val turnover: Double

    /** The unit every rate above is expressed in. */
    val timeUnit: TimeUnit
}

/**
 * The service measures of @sec-performance-service.
 *
 * Every one of them is computable from the geometry of the cycle alone, without any
 * price, which is why [InventoryCycle] implements this interface directly. A
 * warehouse reports a fill rate without knowing its holding cost.
 */
interface ServiceMeasureIfc {

    /** The fraction of time the system is out of stock. */
    val fractionOutOfStock: Double

    /** The complement of [fractionOutOfStock]. */
    val readyRate: Double

    /**
     * The fraction of demand met from stock. Equal to [readyRate] under the constant
     * demand of Chapter 3, which @sec-eoq-measures is explicit is a consequence of the
     * deterministic assumption rather than a general fact.
     */
    val fillRate: Double

    /** The fraction of cycles that end without a shortage. */
    val cycleServiceLevel: Double

    /** The rate at which demand arrives to an empty shelf, in units per unit time. */
    val unfilledDemandRate: Double

    /** The average time a unit of demand waits, in time units. */
    val averageWait: Double
}

/**
 * Every performance measure of @sec-performance, cost and service alike.
 *
 * @sec-performance has two subsections, "Cost as a Performance Measure" and "Service
 * Measures," so cost is a kind of performance measure rather than a sibling of one.
 * This interface extends both, which means a function needing only service measures
 * can declare [ServiceMeasureIfc] and be handed the whole thing.
 */
interface PerformanceMeasureIfc : CostMeasureIfc, ServiceMeasureIfc
