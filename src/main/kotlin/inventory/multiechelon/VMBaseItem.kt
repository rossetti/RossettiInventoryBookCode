package inventory.multiechelon

import ksl.utilities.distributions.Poisson

/**
 * A storeroom, @sec-multiechelon-onefortone.
 *
 * Ported from `varimetric.VMBaseItem`. The literature and the original call this
 * a *base*; this book says **storeroom**, because *base-stock* is already the
 * name of a policy. The class name is the original's so the two read together.
 *
 * It holds a reference back to its depot, and changing a rate here tells the
 * depot to recompute. That coupling is the original's design and it is the
 * point: @eq-varimetric-mean says a storeroom cannot be described without asking the
 * depot how long it makes people wait.
 */
class VMBaseItem internal constructor(
    private val depot: VMItem,
    val baseNumber: Int,
    demandRate: Double,
    repairTime: Double,
    repairProb: Double,
    shipTime: Double,
    stockLevel: Int,
    cost: Double,
) : BaseStockItem() {

    /**
     * Changing any of the four re-fits this storeroom, and changing a rate or a
     * repair fraction also moves the depot, because @eq-repairable-passup puts both into
     * the depot's demand rate.
     */
    var repairTime: Double = repairTime
        set(value) {
            require(value > 0.0) { "the repair time must be > 0" }
            field = value
            updateLeadTimeDemand()
        }

    var shipTime: Double = shipTime
        set(value) {
            require(value > 0.0) { "the ship time must be > 0" }
            field = value
            updateLeadTimeDemand()
        }

    var repairProb: Double = repairProb
        set(value) {
            require(value in 0.0..1.0) { "the probability must be in [0,1]" }
            field = value
            depot.updateDepotDemandRate()
        }

    /** @eq-varimetric-mean, the mean units in resupply. */
    var expectedNumInResupply: Double = 0.0
        private set

    /** @eq-varimetric-variance, its variance. */
    var varianceNumInResupply: Double = 0.0
        private set

    /** The part of the mean waiting at the depot rather than moving. */
    var expectedNumAtDepot: Double = 0.0
        private set

    init {
        itemNumber = depot.itemNumber
        super.demandRate = demandRate
        this.stockLevel = stockLevel
        unitCost = cost
    }

    /** The original's `setDemandRate`: assign, then tell the depot. */
    fun changeDemandRate(rate: Double) {
        demandRate = rate
        depot.updateDepotDemandRate()
    }

    /** @eq-repairable-passup, the requests this storeroom sends up. */
    val replenishmentDemandRate: Double get() = (1.0 - repairProb) * demandRate

    val expectedNumInBaseRepair: Double get() = repairProb * demandRate * repairTime
    val expectedNumInTransit: Double get() = replenishmentDemandRate * shipTime

    /** The part Palm's theorem covers exactly: local benches plus units in transit. */
    val ownPipeline: Double get() = expectedNumInBaseRepair + expectedNumInTransit

    public override fun updateLeadTimeDemand() {
        expectedNumAtDepot = replenishmentDemandRate * depot.expectedWaitTime
        expectedNumInResupply = ownPipeline + expectedNumAtDepot

        // @eq-varimetric-split, the binomial split of the depot's backorders.
        val share = replenishmentDemandRate / depot.demandRate
        varianceNumInResupply = ownPipeline +
            share * (1.0 - share) * depot.expectedBackOrders +
            share * share * depot.varianceBackOrders

        leadTime = repairProb * repairTime +
            (1.0 - repairProb) * (shipTime + depot.expectedWaitTime)

        leadTimeDemand = if (depot.forcePoissonAtBases) {
            Poisson(expectedNumInResupply)
        } else {
            fitTwoMoments(expectedNumInResupply, varianceNumInResupply)
        }
    }

    /**
     * @eq-lagrange-newsvendor, this storeroom's Lagrangian sub-problem.
     *
     * At a fixed price of money the storeroom is a newsvendor and needs no
     * search: stock up to the critical ratio.
     */
    internal fun optimalLevelAt(theta: Double): Int {
        require(theta > 0.0) {
            "the multiplier must be > 0; at zero the critical ratio is one and " +
                "the optimal level is unbounded"
        }
        val ratio = 1.0 - theta * unitCost
        return if (ratio <= 0.0) 0 else leadTimeDemand.invCDF(ratio).toInt()
    }

    override fun toString(): String = buildString {
        append(super.toString())
        appendLine("  storeroom $baseNumber: repairs ${repairProb} in ${repairTime}, ships in ${shipTime}")
        appendLine("  in resupply        %10.4f mean, %10.4f variance"
            .format(expectedNumInResupply, varianceNumInResupply))
    }
}
