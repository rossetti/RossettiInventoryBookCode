package inventory.multiechelon

import ksl.utilities.distributions.LossFunctionDistributionIfc
import ksl.utilities.distributions.Poisson

/**
 * A location run one for one, @sec-multiechelon-onelocation.
 *
 * Ported from `varimetric.BaseStockItemAbstract`, written against the JSL in
 * 2007. The arithmetic is the original's; the accessors are Kotlin properties.
 *
 * Everything below reads off one distribution, because @eq-repairable-net says the
 * net inventory is the stock level minus the units in resupply and nothing else
 * enters.
 */
abstract class BaseStockItem {

    var itemNumber: Int = 0
        protected set

    var unitCost: Double = 1.0
        set(value) {
            require(value > 0.0) { "the cost must be > 0" }
            field = value
        }

    open var stockLevel: Int = 0
        set(value) {
            require(value >= 0) { "the level must be >= 0" }
            field = value
        }

    var leadTime: Double = 1.0
        protected set(value) {
            require(value > 0.0) { "the lead time must be > 0" }
            field = value
        }

    var demandRate: Double = 0.0
        protected set(value) {
            require(value >= 0.0) { "the rate must be >= 0" }
            field = value
        }

    /** The lead time demand, or equivalently the units in resupply. */
    var leadTimeDemand: LossFunctionDistributionIfc = Poisson(1.0)
        protected set

    protected abstract fun updateLeadTimeDemand()

    val stockingCost: Double get() = unitCost * stockLevel

    /** @eq-repairable-backorders. */
    val expectedBackOrders: Double get() = leadTimeDemand.firstOrderLossFunction(stockLevel.toDouble())

    /**
     * @eq-depot-backorder-variance.
     *
     * The original writes this as `2*G2 - ebo*(ebo - 1)`, which is
     * `E[B^2] - E[B]^2` with `E[B^2] = 2*G2 + G1` substituted in. @sec-multiechelon-varimetric-depotvar
     * gives Sherbrooke's recursion for the same quantity; a worksheet needs it
     * and this does not, because the second order loss function is already here.
     */
    val varianceBackOrders: Double
        get() {
            val ebo = expectedBackOrders
            val g2 = leadTimeDemand.secondOrderLossFunction(stockLevel.toDouble())
            return 2.0 * g2 - ebo * (ebo - 1.0)
        }

    /** @eq-repairable-onhand. */
    val expectedOnHand: Double get() = stockLevel - leadTimeDemand.mean() + expectedBackOrders

    /** The complement of the ready rate of @eq-repairable-readyrate. */
    val stockoutProbability: Double get() = leadTimeDemand.complementaryCDF(stockLevel - 1.0)

    /** What the next unit is worth in backorders removed, `P{X > S}` of @eq-marginal-value. */
    val nextUnitValue: Double get() = leadTimeDemand.complementaryCDF(stockLevel.toDouble())

    /** @eq-depot-wait, Little's Law. */
    val expectedWaitTime: Double get() = expectedBackOrders / demandRate

    /**
     * The variance of the wait, the distributional form of Little's Law.
     *
     * `(2*G2 - ebo^2)/rate^2` is `(Var[B] - E[B])/lambda^2`. The chapter does not
     * use it; the original computes it and it is kept.
     */
    val varianceWaitTime: Double
        get() {
            val ebo = expectedBackOrders
            val g2 = leadTimeDemand.secondOrderLossFunction(stockLevel.toDouble())
            return (2.0 * g2 - ebo * ebo) / (demandRate * demandRate)
        }

    override fun toString(): String = buildString {
        appendLine("Item $itemNumber, stock $stockLevel at $unitCost each")
        appendLine("  lead time          %10.4f".format(leadTime))
        appendLine("  demand rate        %10.4f".format(demandRate))
        appendLine("  lead time demand   %10.4f mean, %10.4f variance"
            .format(leadTimeDemand.mean(), leadTimeDemand.variance()))
        appendLine("  expected on hand   %10.4f".format(expectedOnHand))
        appendLine("  expected backorders%10.4f".format(expectedBackOrders))
        appendLine("  variance backorders%10.4f".format(varianceBackOrders))
        appendLine("  stockout probability %8.4f".format(stockoutProbability))
        appendLine("  wait time          %10.4f mean, %10.4f variance"
            .format(expectedWaitTime, varianceWaitTime))
    }
}

/** Ported from `varimetric.PoissonBaseStockModel`. A single location, @sec-multiechelon-onelocation. */
class PoissonBaseStockModel(demandRate: Double, leadTime: Double) : BaseStockItem() {
    init {
        this.demandRate = demandRate
        this.leadTime = leadTime
        updateLeadTimeDemand()
    }

    override fun updateLeadTimeDemand() {
        leadTimeDemand = Poisson(demandRate * leadTime)
    }
}

/**
 * Ported from `varimetric.NegBinomialBaseStockModel`.
 *
 * Takes two moments rather than a rate and a time, and fits @eq-varimetric-fit. The
 * original throws when the variance does not exceed the mean and that is kept,
 * because silently substituting a different distribution is worse than a stop.
 */
class NegBinomialBaseStockModel(meanLTD: Double, varLTD: Double, leadTime: Double) : BaseStockItem() {
    init {
        require(meanLTD > 0.0) { "the mean must be > 0" }
        require(varLTD > meanLTD) { "the variance must exceed the mean; got $varLTD and $meanLTD" }
        this.leadTime = leadTime
        this.demandRate = meanLTD / leadTime
        leadTimeDemand = negativeBinomialOn(meanLTD, varLTD)
    }

    override fun updateLeadTimeDemand() = Unit
}
