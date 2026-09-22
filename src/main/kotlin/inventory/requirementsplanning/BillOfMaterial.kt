package inventory.requirementsplanning

/**
 * What goes into what, and how many. @sec-mrpdrp-bom.
 *
 * A structure, not a tree. The same component can be used by several parents, which is
 * the usual case and the reason [lowLevelCode] exists: an item must be planned once, and
 * only after every parent that consumes it has been planned, or its requirements are
 * netted against stock that a later level is about to claim.
 */
class BillOfMaterial(items: List<PlannedItem>, links: List<Usage>) {

    /** [quantity] of [child] go into one [parent]. */
    data class Usage(val parent: String, val child: String, val quantity: Double)

    val items: Map<String, PlannedItem> = items.associateBy { it.id }
    val usages: List<Usage> = links

    init {
        require(items.isNotEmpty()) { "A bill of material needs at least one item" }
        require(this.items.size == items.size) { "Two items share an identifier" }
        links.forEach {
            require(it.parent in this.items) { "${it.parent} is used but not defined" }
            require(it.child in this.items) { "${it.child} is used but not defined" }
            require(it.quantity > 0.0) { "${it.parent} uses ${it.quantity} of ${it.child}" }
            require(it.parent != it.child) { "${it.parent} cannot contain itself" }
        }
        require(links.distinctBy { it.parent to it.child }.size == links.size) {
            "A parent lists the same child twice; combine the quantities instead"
        }
        computeLowLevelCodes()   // eagerly, so a cycle is reported at construction
    }

    fun childrenOf(id: String): List<Usage> = usages.filter { it.parent == id }
    fun parentsOf(id: String): List<Usage> = usages.filter { it.child == id }

    /** Items nothing else consumes. These are the ones a master schedule speaks about. */
    val endItems: List<String> get() = items.keys.filter { parentsOf(it).isEmpty() }.sorted()

    /**
     * The deepest level at which each item appears, counting an end item as level 0.
     *
     * The deepest, not the shallowest. An item used both directly by the end item and
     * inside a subassembly appears at two levels, and planning it at the shallower one
     * would net its stock before the subassembly had asked for any.
     */
    val lowLevelCodes: Map<String, Int> by lazy { computeLowLevelCodes() }

    private fun computeLowLevelCodes(): Map<String, Int> {
        // The walk starts from the end items, so a structure with none of them never
        // gets walked and a cycle among every item would go unnoticed. Both conditions
        // are checked here rather than inside the walk, because neither is reachable
        // from a node: they are properties of the graph.
        require(endItems.isNotEmpty()) {
            "Every item is consumed by another, so the bill of material has no end item " +
                "and must contain a cycle"
        }
        val code = items.keys.associateWith { 0 }.toMutableMap()
        val reached = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun walk(id: String, depth: Int) {
            require(id !in visiting) {
                "The bill of material contains a cycle through $id"
            }
            reached.add(id)
            if (depth > code.getValue(id)) code[id] = depth
            require(depth <= items.size) { "The bill of material contains a cycle through $id" }
            visiting.add(id)
            childrenOf(id).forEach { walk(it.child, depth + 1) }
            visiting.remove(id)
        }
        endItems.forEach { walk(it, 0) }
        require(reached == items.keys) {
            "${(items.keys - reached).sorted()} cannot be reached from any end item, so " +
                "nothing would ever require them"
        }
        return code
    }

    fun lowLevelCode(id: String): Int = lowLevelCodes.getValue(id)

    /**
     * The order to plan the items in: shallowest first, and an item only after every
     * parent of it. Ties are broken by identifier so a run is reproducible.
     */
    fun planningOrder(): List<String> =
        items.keys.sortedWith(compareBy({ lowLevelCode(it) }, { it }))

    override fun toString(): String =
        "BillOfMaterial(${items.size} items, ${usages.size} usages, ${endItems.size} end items)"
}
