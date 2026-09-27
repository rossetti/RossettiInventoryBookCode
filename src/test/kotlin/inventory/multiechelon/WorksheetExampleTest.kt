package inventory.multiechelon

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The worksheet example of @sec-batchedmultiechelon-build-worked: the hub's batch
 * raised from eight to twelve, then its reorder point lowered to the cheapest.
 */
class WorksheetExampleTest {

    private fun close(got: Double, want: Double, tol: Double = 1e-4, what: String = "") =
        assertTrue(abs(got - want) < tol, "$what expected $want, got $got")

    private val h = 28.75 / 52
    private val b = 287.50 / 52
    private val oneForOne = OneForOneTwoLevel(1.0, 4, 1.0, 3.0, h, h, b)

    private fun hub(r: Int, q: Int) = BatchedHub(0.0, List(4) { StoreStream(1.0, 1) }, r, q, 3.0,
        RQCosts(0.0, h, b))

    @Test
    fun `a batch of twelve at the hub`() {
        val hub = hub(9, 12)
        close(hub.expectedBackorders(), 0.6984, what = "B_0")
        close(hub.readyRate(), 0.7242, what = "ready rate")
        close(hub.littleWait(), 0.1746, what = "mean wait")
        val exact = AxsaterBatchOrdering(oneForOne, 1, 12)
        val costs = listOf(28.2978, 14.1004, 8.4818, 7.7367, 8.9794, 10.8975, 13.0244)
        for ((s, c) in costs.withIndex()) close(exact.cost(9, s - 1), c, what = "cost at S_j = $s")
        close(exact.measures(9, 2).storeBackorders / 4, 0.0567, what = "backorders per storeroom")
    }

    @Test
    fun `a reorder point of seven is the cheapest for a batch of twelve`() {
        val exact = AxsaterBatchOrdering(oneForOne, 1, 12)
        close(exact.cost(7, 2), 7.5379, what = "cost at r_0 = 7")
        val best = (0..14).flatMap { r -> (0..6).map { s -> Triple(r, s, exact.cost(r, s - 1)) } }
            .minBy { it.third }
        assertEquals(7 to 3, best.first to best.second)
        val present = AxsaterBatchOrdering(oneForOne, 1, 8).cost(9, 2)
        close(exact.cost(7, 2) - present, 0.5184, what = "the increase over the present policy")
    }
}
