package inventory.simulation

import ksl.modeling.elements.EventGenerator
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Customer demand at a stock point: demands separated by times drawn from
 * [timeBetween], each for a lot drawn from [lotSize]. Exponential times between
 * demands give the Poisson demand of every example in the chapter, and the
 * demand schedules its own successor, as @tbl-sim-events says.
 */
class CustomerDemand(
    parent: ModelElement,
    val target: SimInventory,
    timeBetween: RVariableIfc,
    lotSize: RVariableIfc = ConstantRV(1.0),
    name: String? = null,
) : ModelElement(parent, name) {

    private val myLot = RandomVariable(this, lotSize, "${this.name}:LotSize")

    private val myGenerator = EventGenerator(this, { _ -> target.demand(myLot.value.toInt()) },
        timeBetween, timeBetween, name = "${this.name}:Generator")
}

/**
 * Customer demand at given times, one unit each, so that a hand ledger such as
 * @exm-rq-ledger can be replayed through the model and compared row for row,
 * @exm-sim-ledger.
 */
class ScriptedDemand(
    parent: ModelElement,
    val target: SimInventory,
    val times: List<Double>,
    name: String? = null,
) : ModelElement(parent, name) {

    init {
        require(times.zipWithNext().all { (a, b) -> a <= b }) { "the demand times must be in order" }
        require(times.all { it >= 0.0 }) { "the demand times cannot be negative" }
    }

    override fun initialize() {
        super.initialize()
        for (t in times) schedule(::arrive, t)
    }

    private fun arrive(event: KSLEvent<Nothing>) {
        target.demand(1)
    }
}
