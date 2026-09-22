package inventory.multiitem

import kotlin.math.sqrt

/**
 * The frontier relating what a portfolio has tied up in cycle stock to what it spends
 * placing orders. @sec-multiitem-exchange.
 *
 * This is the constrained problem of @sec-multiitem-constrained asked the other way round: there the
 * limit is given and the price is found, here the price is the axis. The two therefore
 * share the same machinery rather than restating the same first-order condition.
 *
 * The one difference is that no holding cost appears in the objective: the price takes
 * the carrying charge's place rather than adding to it. That is expressed by pricing a
 * portfolio whose SKUs carry no holding rate, so the closed form in [AverageInvestment]
 * is still the only place the quantity is computed.
 */
class ExchangeCurve(private val portfolio: Portfolio) {

    private val skus get() = portfolio.skus
    private val priced = Portfolio(skus.map { it.withoutHoldingCost() })

    /** Sum over SKUs of the square root of order cost times value times demand. */
    val rootSum: Double = skus.sumOf { sqrt(it.orderCost * it.unitValue * it.demandRate) }

    private val weightedSum: Double = skus.sumOf { it.orderCost * it.unitValue * it.demandRate }

    /** Investment times ordering cost, the same at every point. @eq-exchange-product. */
    val constant: Double = rootSum * rootSum / 2.0

    /** The effective number of SKUs, between one and the count. @eq-variety-index. */
    val varietyIndex: Double = rootSum * rootSum / weightedSum

    val aggregateDemandRate: Double = skus.sumOf { it.demandRate }
    val aggregatePurchaseCost: Double = skus.sumOf { it.unitValue * it.demandRate }
    val weightedUnitCost: Double = aggregatePurchaseCost / aggregateDemandRate
    val weightedOrderingCost: Double = weightedSum / aggregatePurchaseCost

    /** The constant by the other route. Computing it both ways is the arithmetic check. */
    val decomposedConstant: Double =
        0.5 * varietyIndex * weightedOrderingCost * aggregateDemandRate * weightedUnitCost

    /** The plan at one operating point. */
    fun at(shadowPrice: Double): ReplenishmentPlan {
        require(shadowPrice > 0.0) { "A price must be positive, was $shadowPrice" }
        val quantities = priced.skus.map { AverageInvestment.quantityAt(it, shadowPrice) }
        return portfolio.planFor(quantities, shadowPrice)
    }

    fun investmentAt(shadowPrice: Double): Double = AverageInvestment.measure(at(shadowPrice))
    fun orderingCostAt(shadowPrice: Double): Double =
        at(shadowPrice).let { p -> skus.sumOf { it.orderCost * it.orderFrequencyAt(p.quantityOf(it)) } }

    /** The curve over a range of prices, without materializing a list. */
    fun over(prices: DoubleArray): Sequence<ReplenishmentPlan> = prices.asSequence().map { at(it) }

    override fun toString(): String =
        "ExchangeCurve(skus=${skus.size}, constant=$constant, varietyIndex=$varietyIndex)"
}
