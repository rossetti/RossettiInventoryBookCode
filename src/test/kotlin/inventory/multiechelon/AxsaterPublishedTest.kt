package inventory.multiechelon

import inventory.multiechelon.AxsaterBatchOrdering.Approximation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 32 problems of Svoronos and Zipkin (1988), as Axsäter (1993) solves them.
 *
 * Every other number in @sec-batchedmultiechelon is measured against
 * [AxsaterBatchOrdering], so it is held here to someone else's published figures:
 * Table I for the problems and the two policies, Table II for the exact and
 * approximate costs at the policies of Deuermeyer and Schwarz, Table III for the
 * same at the policies of Svoronos and Zipkin. Both lead times are one time
 * unit and both holding costs one dollar, as in the paper.
 *
 * The tables print two decimals, so agreement is to half a cent plus rounding.
 */
class AxsaterPublishedTest {

    /** A row of Table I, with the matching rows of Tables II and III. */
    private data class Problem(
        val number: Int,
        val rate: Double, val stores: Int, val shortage: Double, val qStore: Int, val qHub: Int,
        val dsHub: Int, val dsStore: Int, val szHub: Int, val szStore: Int,
        /** Table II: exact, approximations 1, 2 and 3, at the D&S policy. */
        val ds: List<Double>,
        /** Table III: the same at the S&Z policy. */
        val sz: List<Double>,
    )

    private fun same(x: Double) = listOf(x, x, x, x)

    private val problems = listOf(
        Problem(1, 0.1, 4, 20.0, 1, 1, -1, 1, -1, 0, same(7.30), same(4.77)),
        Problem(2, 0.1, 4, 20.0, 1, 4, -2, 1, -1, 0, same(7.85), same(5.45)),
        Problem(3, 0.1, 4, 20.0, 4, 1, 0, 0, -1, 0, listOf(13.32, 13.31, 13.32, 13.32), same(9.62)),
        Problem(4, 0.1, 4, 20.0, 4, 4, -2, 1, -1, -1,
            listOf(17.07, 15.92, 17.33, 16.98), listOf(14.03, 14.01, 14.04, 14.02)),
        Problem(5, 0.1, 4, 5.0, 1, 1, 0, 0, 0, -1, same(4.37), same(3.02)),
        Problem(6, 0.1, 4, 5.0, 1, 4, -1, 0, -1, -1, same(4.91), same(3.82)),
        Problem(7, 0.1, 4, 5.0, 4, 1, -1, 0, -1, -1, same(9.32), same(6.52)),
        Problem(8, 0.1, 4, 5.0, 4, 4, -3, 1, -2, -1,
            listOf(13.52, 12.74, 14.02, 13.70), listOf(11.03, 10.53, 10.99, 10.88)),
        Problem(9, 0.1, 32, 20.0, 1, 1, -1, 1, 3, 0, same(58.41), same(33.80)),
        Problem(10, 0.1, 32, 20.0, 1, 4, -4, 1, 1, 0, same(57.57), same(34.10)),
        Problem(11, 0.1, 32, 20.0, 4, 1, 1, 0, 1, -1,
            listOf(82.59, 82.44, 82.60, 82.59), listOf(68.53, 67.29, 68.62, 68.57)),
        Problem(12, 0.1, 32, 20.0, 4, 4, -1, 0, 0, -1,
            listOf(81.29, 81.13, 81.29, 81.29), listOf(71.08, 69.83, 71.14, 71.10)),
        Problem(13, 0.1, 32, 5.0, 1, 1, 4, 0, 4, -1, same(31.65), same(18.85)),
        Problem(14, 0.1, 32, 5.0, 1, 4, 3, 0, 3, -1, same(32.14), same(19.30)),
        Problem(15, 0.1, 32, 5.0, 4, 1, -1, 0, -1, -1, same(74.56), same(52.16)),
        Problem(16, 0.1, 32, 5.0, 4, 4, -3, 0, -2, -1,
            listOf(74.05, 73.70, 73.94, 73.93), listOf(53.89, 53.44, 53.86, 53.85)),
        Problem(17, 1.0, 4, 20.0, 1, 1, 3, 3, 4, 2, same(13.01), same(12.02)),
        Problem(18, 1.0, 4, 20.0, 1, 4, 1, 3, 2, 2, same(12.97), same(12.38)),
        Problem(19, 1.0, 4, 20.0, 4, 1, 0, 2, 0, 2,
            listOf(16.11, 15.34, 16.38, 16.12), listOf(16.11, 15.34, 16.38, 16.12)),
        Problem(20, 1.0, 4, 20.0, 4, 4, -2, 3, -1, 2,
            listOf(20.04, 19.06, 20.29, 19.98), listOf(18.57, 18.33, 18.71, 18.62)),
        Problem(21, 1.0, 4, 5.0, 1, 1, 3, 2, 3, 1, same(9.17), same(8.13)),
        Problem(22, 1.0, 4, 5.0, 1, 4, 1, 2, 2, 1, same(9.07), same(8.44)),
        Problem(23, 1.0, 4, 5.0, 4, 1, 0, 1, -1, 1, listOf(11.96, 11.41, 12.16, 11.97), same(11.14)),
        Problem(24, 1.0, 4, 5.0, 4, 4, -2, 2, -1, 0,
            listOf(14.69, 14.25, 14.75, 14.63), listOf(13.95, 13.57, 14.18, 14.03)),
        Problem(25, 1.0, 32, 20.0, 1, 1, 34, 2, 31, 2, same(84.90), same(84.42)),
        Problem(26, 1.0, 32, 20.0, 1, 4, 33, 2, 30, 2, same(85.19), same(84.51)),
        Problem(27, 1.0, 32, 20.0, 4, 1, 6, 2, 8, 1,
            listOf(118.12, 116.34, 118.71, 118.64), listOf(111.70, 108.00, 113.26, 113.09)),
        Problem(28, 1.0, 32, 20.0, 4, 4, 5, 2, 6, 1,
            listOf(119.44, 117.86, 120.00, 119.94), listOf(112.74, 108.86, 114.28, 114.11)),
        Problem(29, 1.0, 32, 5.0, 1, 1, 27, 2, 30, 1, same(67.51), same(55.95)),
        Problem(30, 1.0, 32, 5.0, 1, 4, 25, 2, 29, 1, same(67.32), same(56.03)),
        Problem(31, 1.0, 32, 5.0, 4, 1, 6, 1, 7, 0,
            listOf(86.93, 85.57, 87.40, 87.34), listOf(78.72, 75.65, 79.82, 79.69)),
        Problem(32, 1.0, 32, 5.0, 4, 4, 4, 1, 5, 0,
            listOf(86.48, 85.27, 86.89, 86.84), listOf(79.45, 76.76, 80.45, 80.33)),
    )

    private fun model(p: Problem) = AxsaterBatchOrdering(
        OneForOneTwoLevel(p.rate, p.stores, 1.0, 1.0, 1.0, 1.0, p.shortage), p.qStore, p.qHub,
    )

    /** The four figures a table row prints, computed. */
    private fun row(m: AxsaterBatchOrdering, hub: Int, store: Int) = listOf(
        m.cost(hub, store),
        m.approximateCost(Approximation.ONE, hub, store),
        m.approximateCost(Approximation.TWO, hub, store),
        m.approximateCost(Approximation.THREE, hub, store),
    )

    private val columns = listOf("exact", "approximation 1", "approximation 2", "approximation 3")

    /**
     * Seven approximate cells, of 192, where the published figure sits 0.005 to
     * 0.011 below the computed one. Every exact cost, all 64, agrees to rounding.
     *
     * One of the seven is the paper's own inconsistency: problem 4, Table III,
     * prints approximation 1 as 14.01 and approximation 2 as 14.04, and (24) makes
     * approximation 3 a quarter of the first plus three quarters of the second,
     * which is 14.03 however the two were rounded. It prints 14.02. The other six
     * are all in the large problems 27, 28 and 32 and all in the same direction,
     * which suggests the paper truncated a sum there. They are held to a hundredth
     * rather than dropped, so a change that moved them further would still fail.
     */
    private val knownDeviations = setOf(
        Triple(4, "III", 3), Triple(27, "II", 2), Triple(27, "III", 3), Triple(28, "II", 2),
        Triple(28, "III", 2), Triple(28, "III", 3), Triple(32, "II", 1),
    )

    private fun check(label: String, got: List<Double>, want: List<Double>, failures: MutableList<String>) {
        val (number, table) = label.removePrefix("problem ").split(", Table ").let { it[0].toInt() to it[1] }
        for (c in got.indices) {
            val tolerance = if (Triple(number, table, c) in knownDeviations) 0.012 else 0.0051
            if (abs(got[c] - want[c]) > tolerance) {
                failures += "$label ${columns[c]}: published %.2f, computed %.4f".format(want[c], got[c])
            }
        }
    }

    private fun checkAll(stores: Int) {
        val failures = mutableListOf<String>()
        for (p in problems.filter { it.stores == stores }) {
            val m = model(p)
            check("problem ${p.number}, Table II", row(m, p.dsHub, p.dsStore), p.ds, failures)
            check("problem ${p.number}, Table III", row(m, p.szHub, p.szStore), p.sz, failures)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `four storerooms, Tables II and III`() = checkAll(4)

    @Test
    fun `thirty-two storerooms, Tables II and III`() = checkAll(32)
}
