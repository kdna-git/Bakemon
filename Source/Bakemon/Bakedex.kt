package bakemon

import util.JsonObj
import util.SetupException
import java.io.File

/** A monster species from Bakemons.json: what it is and what it may learn/have. */
class Species(
    val id: String,
    val name: String,
    val types: List<String>,
    /** Base value for every stat id in Rules.stats. */
    val baseStats: Map<String, Double>,
    /** Ability ids it may have; the first one is the default. May be empty. */
    val abilities: List<String>,
    /** Move ids it can learn. */
    val moves: List<String>,
    /** Image path relative to the setup folder (e.g. "Assets/embercub.png"), or null. */
    val image: String?,
    val description: String,
) {
    override fun toString(): String = name
}

/** The monster collection of a setup (Bakemons.json). Keeps file order. */
class Bakedex(val species: Map<String, Species>) {
    operator fun get(id: String): Species? = species[id]

    /** Throws util.SetupException("Unknown bakemon '<id>'") if missing. */
    fun require(id: String): Species = species[id] ?: throw SetupException("Unknown bakemon '$id'")

    /** All species in file order. */
    fun all(): List<Species> = species.values.toList()

    /** Species whose id, name or type contains [query] (case-insensitive). */
    fun search(query: String): List<Species> {
        val q = query.trim()
        if (q.isEmpty()) return all()
        return species.values.filter { sp ->
            sp.id.contains(q, ignoreCase = true) || sp.name.contains(q, ignoreCase = true) ||
                sp.types.any { it.contains(q, ignoreCase = true) }
        }
    }

    companion object {
        private val KEYS = setOf("id", "name", "types", "baseStats", "abilities", "moves", "image", "description")

        /**
         * Parses Bakemons.json (bare array or {"bakemons": [...]}).
         * Bad entries are reported to [report] and skipped; duplicate ids are errors.
         * Cross references (types, moves, abilities, stats) are checked later by Setup.
         */
        fun load(file: File, report: LoadReport): Bakedex = Bakedex(
            LoadSupport.loadList(file, report, "bakemons", "species", LoadSupport.Missing.ERROR) { parse(it, report) },
        )

        private fun parse(obj: JsonObj, report: LoadReport): Species {
            LoadSupport.warnUnknownKeys(obj, KEYS, report)
            val id = obj.string("id")
            val stats = obj.obj("baseStats")
            val baseStats = LinkedHashMap<String, Double>()
            for (stat in stats.keys) {
                val value = stats.double(stat)
                if (value < 0) LoadSupport.failAt(stats, stat, "base stats must not be negative (got $value)")
                baseStats[stat] = value
            }
            return Species(
                id = id,
                name = obj.string("name", id),
                types = obj.strings("types"),
                baseStats = baseStats,
                abilities = obj.strings("abilities"),
                moves = obj.strings("moves"),
                image = obj.stringOrNull("image")?.takeIf { it.isNotBlank() },
                description = obj.string("description", ""),
            )
        }
    }
}
