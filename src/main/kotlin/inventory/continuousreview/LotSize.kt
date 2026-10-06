package inventory.continuousreview

import kotlin.math.abs

/**
 * The distribution of `Y`, the number of units demanded at one demand epoch.
 * @sec-continuousreview-lumpy.
 *
 * The lot sizes are whole units and at least one, and the masses must sum to one.
 * Every other model in Chapter 8 is the special case [ONE], where each epoch
 * demands exactly one unit.
 *
 * @param masses `masses[y] = P{Y = y}` for the lot sizes that can occur
 */
class LotSize(masses: Map<Int, Double>) {

    /** The masses, in increasing order of lot size, with the zero masses dropped. */
    val masses: Map<Int, Double> = masses.filterValues { it > 0.0 }.toSortedMap()

    init {
        require(this.masses.isNotEmpty()) { "a lot size distribution needs at least one lot size" }
        require(masses.keys.all { it >= 1 }) { "a lot size must be at least one unit" }
        require(masses.values.all { it >= 0.0 }) { "lot size probabilities cannot be negative" }
        val total = masses.values.sum()
        require(abs(total - 1.0) < 1.0e-9) { "lot size probabilities must sum to one, sum to $total" }
    }

    /** `E[Y]`. */
    val mean: Double = this.masses.entries.sumOf { (y, p) -> y * p }

    /** `E[Y^2]`, the moment @eq-lumpy-moments needs. */
    val secondMoment: Double = this.masses.entries.sumOf { (y, p) -> y.toDouble() * y * p }

    /** The largest lot that can occur. */
    val largest: Int get() = masses.keys.last()

    /** True when every epoch demands exactly one unit, so the model is the one of every earlier section. */
    val isSingleUnit: Boolean get() = largest == 1

    /** `P{Y >= j}`. */
    fun atLeast(j: Int): Double = masses.entries.sumOf { (y, p) -> if (y >= j) p else 0.0 }

    override fun toString(): String =
        masses.entries.joinToString(prefix = "lots ", separator = ", ") { (y, p) -> "$y w.p. %.4f".format(p) }

    companion object {
        /** Demand one unit at a time. */
        val ONE: LotSize = LotSize(mapOf(1 to 1.0))

        fun of(vararg lots: Pair<Int, Double>): LotSize = LotSize(lots.toMap())
    }
}
