package inventory.newsvendor

import ksl.utilities.random.rvariable.NegativeBinomialRV
import java.io.File

/**
 * Writes data/ch7-weekly-demand.csv. Run once; the file is what the book uses.
 *
 * NOT referred to anywhere in Chapter 7, and deliberately so. @sec-newsvendor-fitting is a
 * distribution fitting exercise, and a reader who has been shown the generating
 * distribution cannot do the exercise. The file is the data; this is only the
 * record of where it came from, kept so that it is not a mystery to the author.
 *
 * The generating model is a negative binomial: the number of failures before
 * the second success in Bernoulli trials with success probability 0.055. Its
 * mean is 34.36 belts a week and its variance to mean ratio is 1/p = 18.18.
 * Stream 16 was chosen, out of the first twenty, as the draw whose sample mean
 * and variance are jointly closest to the generating distribution's, because a
 * section about recovering a distribution from a sample should not open on an
 * unrepresentative sample.
 *
 * Note `resetStartStream`. Naming a stream number selects a stream; it does not
 * rewind one, so two random variables on stream 16 share it.
 */
fun main() {
    val rv = NegativeBinomialRV(probOfSuccess = 0.055, numSuccess = 2.0, streamNum = 16)
    rv.resetStartStream()
    val out = File(FinalBuy.historyFile)
    out.parentFile?.mkdirs()
    out.printWriter().use { w ->
        w.println("week,demand")
        for (week in 1..104) w.println("$week,${rv.value.toInt()}")
    }
    println("wrote ${out.canonicalPath}")
}
