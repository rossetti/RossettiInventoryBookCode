package inventory.newsvendor

import ksl.utilities.distributions.NegativeBinomial
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.plotting.PMFComparisonPlot
import ksl.utilities.toDoubles
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Saves the four figures @sec-newsvendor-fitting prints.
 *
 *     ./gradlew run -PmainClass=inventory.newsvendor.DemandFiguresKt --args="<dir>"
 *
 * The output directory is an argument and defaults to the working directory, so
 * that this project does not need to know where the book keeps its figures. The
 * book's script passes the path; see scripts/chapter7-figures.sh.
 *
 * A reader following @sec-newsvendor-fitting does not need any of this. Every plot below
 * also answers `showInBrowser()`, which is what the chapter's listings use and
 * what Section 2.4.4 of *Simulation Modeling using the KSL* demonstrates.
 */
fun main(args: Array<String>) {
    val dir: Path = Paths.get(if (args.isNotEmpty()) args[0] else ".")
    val data = DemandFitting.history()

    val h = DemandFitting.histogram(data)
    val hp = h.histogramPlot()
    hp.title = "Weekly demand for the mower deck belt, 104 weeks"
    hp.xLabel = "Belts per week"
    hp.yLabel = "Proportion of weeks"
    save(hp.saveToFile("ch7-demand-histogram", dir))

    val op = ObservationsPlot(data.toDoubles())
    op.title = "Weekly demand in the order it occurred"
    op.xLabel = "Week"
    op.yLabel = "Belts"
    save(op.saveToFile("ch7-demand-observations", dir))

    val acf = ACFPlot(data.toDoubles())
    acf.title = "Autocorrelation of weekly demand"
    save(acf.saveToFile("ch7-demand-acf", dir))

    val s = DemandFitting.summary(data)
    val (p, r) = DemandFitting.momentEstimates(s.average, s.variance)
    val pmf = PMFComparisonPlot(data, NegativeBinomial(p, r))
    pmf.title = "Fitted negative binomial against the 104 weeks"
    save(pmf.saveToFile("ch7-demand-pmf", dir))
}

private fun save(f: java.io.File) =
    println("  %-34s %7d bytes".format(f.name, f.length()))
