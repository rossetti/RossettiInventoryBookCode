package inventory.simulation

/**
 * Something that receives stock: a stock point awaiting a replenishment, or a
 * transit link carrying a shipment onward. @sec-simulation-design-nouns.
 */
fun interface StockReceiverIfc {
    /** [amount] units arrive now. */
    fun receive(amount: Int)
}

/**
 * Something that fills orders, the one thing a supplier must do.
 *
 * Adapted from the interface of the same name in the (r, Q) model of
 * *Simulation Modeling using the KSL*, where a supplier was told only the
 * quantity and delivered to the one inventory it knew. Here the order carries
 * its [StockReceiverIfc], so one supplier can serve several stock points, and a
 * stock point can be another's supplier. That is what lets a two-level system be
 * assembled from the single-location classes, @sec-simulation-design-nouns.
 */
interface InventoryFillerIfc {
    /** An order for [amount] units, to be delivered, eventually and perhaps in parts, to [receiver]. */
    fun fill(amount: Int, receiver: StockReceiverIfc)
}
