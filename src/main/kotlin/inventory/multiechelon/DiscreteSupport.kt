package inventory.multiechelon

import kotlin.math.exp

/**
 * Mass-function arithmetic for the chapter's exact routes. A mass function is a
 * `DoubleArray` whose entry x is the probability of x units; entries past the
 * end are zero. Arrays are cut where the remaining tail falls below [TAIL].
 */
internal const val TAIL = 1.0e-13

/** The Poisson mass function with the given mean, out to a negligible tail. */
internal fun poissonPmf(mean: Double): DoubleArray {
    require(mean >= 0.0) { "a Poisson mean cannot be negative" }
    if (mean == 0.0) return doubleArrayOf(1.0)
    val out = ArrayList<Double>()
    var p = exp(-mean)
    var below = 0.0
    var x = 0
    while (1.0 - below > TAIL || x <= mean) {
        out.add(p)
        below += p
        x += 1
        p *= mean / x
    }
    return out.toDoubleArray()
}

/** The mass function of the sum of two independent counts. */
internal fun convolve(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(a.size + b.size - 1)
    for (i in a.indices) {
        if (a[i] == 0.0) continue
        for (j in b.indices) out[i + j] += a[i] * b[j]
    }
    return out
}

/** The mass function of `step` times a count with mass function [pmf]. */
internal fun scale(pmf: DoubleArray, step: Int): DoubleArray {
    require(step >= 1) { "the step must be at least one" }
    val out = DoubleArray((pmf.size - 1) * step + 1)
    for (n in pmf.indices) out[n * step] = pmf[n]
    return out
}

/** The distribution function read off a mass function: `cdf[x] = P(X <= x)`. */
internal fun cumulative(pmf: DoubleArray): DoubleArray {
    val out = DoubleArray(pmf.size)
    var s = 0.0
    for (x in pmf.indices) { s += pmf[x]; out[x] = s }
    return out
}

/** `P(X <= x)` from a cumulative array, one beyond its end and zero below zero. */
internal fun DoubleArray.at(x: Int): Double = when {
    x < 0 -> 0.0
    x >= size -> 1.0
    else -> this[x]
}
