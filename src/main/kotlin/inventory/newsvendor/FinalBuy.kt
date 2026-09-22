package inventory.newsvendor

import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.getColumn
import org.jetbrains.kotlinx.dataframe.io.ColType
import org.jetbrains.kotlinx.dataframe.io.readCSV
import java.io.File

/**
 * The mower deck belt of Chapter 5, at the end of its life. Chapter 7.
 *
 * The deck the belt fits has gone out of production, so the seasonal pattern of
 * @tbl-dls-belt is over and what remains is steady replacement demand. The supplier
 * is discontinuing the belt and will accept one last order.
 *
 * The weekly demand history is a FILE, `data/ch7-weekly-demand.csv`, and this
 * object reads it. @sec-newsvendor-fitting treats it the way an analyst treats data that
 * arrived from somewhere else, which is the only honest way to present a
 * distribution fitting exercise: the answer must be recovered from the numbers
 * rather than read off the process that made them.
 *
 * See GenerateWeeklyDemand for where the file came from. Nothing in the chapter
 * refers to it, and nothing here needs it.
 */
object FinalBuy {

    /** The distributor sells the belt at this, pays this, and scraps at this. */
    const val sellingPrice: Double = 95.0
    const val unitCost: Double = 50.0
    const val salvageValue: Double = 12.0

    /** Weeks remaining until the replacement deck is phased in. */
    const val weeksRemaining: Int = 14

    /** Where the history lives, relative to the root of this Gradle project. */
    const val historyFile: String = "data/ch7-weekly-demand.csv"

    /** Overage and underage, @eq-newsvendor-mapping. */
    val overageCost: Double get() = unitCost - salvageValue
    val underageCost: Double get() = sellingPrice - unitCost

    /**
     * The weekly demand history, read from [historyFile].
     *
     * The column types are named rather than inferred, which is the practice
     * Section 2.4.4 of the KSL book follows. A column of counts read as text, or
     * as doubles, fails later and further away.
     */
    fun weeklyHistory(file: File = File(historyFile)): IntArray {
        require(file.exists()) {
            "cannot find ${file.absolutePath}. Run this from the root of the " +
                "code project, where data/ lives."
        }
        val df = DataFrame.readCSV(
            file,
            colTypes = mapOf("week" to ColType.Int, "demand" to ColType.Int)
        )
        return df.getColumn("demand").toList().map { it as Int }.toIntArray()
    }

    /** The five-point sketch of @sec-newsvendor-marginal, in belts. */
    val coarseOutcomes: List<Int> = listOf(300, 400, 500, 600, 700)
    val coarseProbabilities: List<Double> = listOf(0.15, 0.25, 0.30, 0.20, 0.10)
}
