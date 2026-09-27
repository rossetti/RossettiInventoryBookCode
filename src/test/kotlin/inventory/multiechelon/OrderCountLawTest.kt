package inventory.multiechelon

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue

/** The order-count law of @sec-batchedmultiechelon-orders, against the identities it must satisfy. */
class OrderCountLawTest {

    private fun close(got: Double, want: Double, tol: Double = 1e-10, what: String = "") =
        assertTrue(abs(got - want) < tol, "$what expected $want, got $got")

    @Test
    fun `ordering one at a time passes the Poisson up unchanged`() {
        val law = OrderCountLaw(2.0, 1)
        val pmf = law.stationary(3.0)
        var p = exp(-6.0)
        for (n in 0..20) {
            close(pmf.getOrElse(n) { 0.0 }, p, 1e-12, "P(N = $n)")
            p *= 6.0 / (n + 1)
        }
    }

    @Test
    fun `the storeroom passes up exactly its customers' demand, on average`() {
        for (q in listOf(1, 3, 5)) for (ell in listOf(0.5, 1.0, 3.0)) {
            val pmf = OrderCountLaw(2.0, q).stationary(ell)
            close(pmf.sum(), 1.0, 1e-12, "mass, Q = $q, l = $ell")
            close(q * pmf.withIndex().sumOf { (n, p) -> n * p }, 2.0 * ell, 1e-10, "Q E[N], Q = $q, l = $ell")
        }
    }

    @Test
    fun `seen from an order, a window holds fewer earlier orders than at a random time`() {
        val law = OrderCountLaw(2.0, 3)
        val atRandom = law.stationary(3.0).withIndex().sumOf { (n, p) -> n * p }
        val fromOrder = law.seenFromOrder(3.0).withIndex().sumOf { (n, p) -> n * p }
        close(law.seenFromOrder(3.0).sum(), 1.0, 1e-12)
        assertTrue(fromOrder < atRandom, "from an order $fromOrder, at random $atRandom")
        // With Q = 1 the two agree, since every demand is an order.
        val one = OrderCountLaw(2.0, 1)
        val a = one.stationary(3.0)
        val c = one.seenFromOrder(3.0)
        for (n in a.indices) close(c.getOrElse(n) { 0.0 }, a[n], 1e-12, "Q = 1, n = $n")
    }
}
