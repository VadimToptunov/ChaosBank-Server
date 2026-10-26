package com.vadimtoptunov.chaosbank.chaos

/**
 * One switchable defect, in the shape of the apps' `exercises.json` entries. [id] is used
 * verbatim in headers, sessions and exercises; [requirement] is what correct code does,
 * [task] what a tester is asked to prove.
 */
data class DefectSpec(
    val id: String,
    val title: String,
    val area: String,
    val category: String = "",
    val severity: String = "major",
    val requirement: String = "",
    val task: String = "",
)

/**
 * The defects a server knows, plus named profiles (bundles of defects). Profiles may only
 * name defects in the catalog, so a typo fails at startup rather than silently doing nothing.
 */
class DefectCatalog(specs: List<DefectSpec>, profiles: Map<String, Set<String>> = emptyMap()) {
    val specs: List<DefectSpec> = specs.sortedBy { it.id }
    private val ids: Set<String> = specs.mapTo(HashSet()) { it.id }
    val profiles: Map<String, Set<String>> = mapOf(CLEAN to emptySet<String>()) + profiles

    init {
        require(ids.size == specs.size) { "duplicate defect ids in the catalog" }
        for ((name, members) in this.profiles) {
            val unknown = members - ids
            require(unknown.isEmpty()) { "profile '$name' names unknown defects: $unknown" }
        }
    }

    operator fun contains(id: String) = id in ids

    /** The defect set a profile plus explicit ids resolve to; throws on anything unknown. */
    fun resolve(profile: String?, defects: Collection<String>): Set<String> {
        val fromProfile = profile?.let { this.profiles[it] ?: throw UnknownChaosValue("profile", it) } ?: emptySet()
        defects.firstOrNull { it !in ids }?.let { throw UnknownChaosValue("defect", it) }
        return fromProfile + defects
    }

    companion object {
        const val CLEAN = "clean"
    }
}

class UnknownChaosValue(val kind: String, val value: String) : IllegalArgumentException("unknown $kind '$value'")
