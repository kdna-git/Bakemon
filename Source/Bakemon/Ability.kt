package bakemon

import engine.Formula
import engine.TargetSelector
import engine.Trigger
import util.JsonObj
import util.SetupException
import java.io.File

/** A passive ability from Abilities.json. */
class AbilityDef(
    val id: String,
    val name: String,
    val description: String,
    /** stat id -> multiplier formula, applied while the owner has this ability. */
    val statModifiers: Map<String, Formula>,
    /** Effect target defaults to "self". */
    val triggers: List<Trigger>,
) {
    override fun toString(): String = name
}

/** All abilities of a setup (Abilities.json). Keeps file order. */
class AbilityManager(val abilities: Map<String, AbilityDef>) {
    operator fun get(id: String): AbilityDef? = abilities[id]

    /** Throws util.SetupException("Unknown ability '<id>'") if missing. */
    fun require(id: String): AbilityDef = abilities[id] ?: throw SetupException("Unknown ability '$id'")

    fun all(): List<AbilityDef> = abilities.values.toList()

    companion object {
        private val KEYS = setOf("id", "name", "description", "statModifiers", "triggers")

        /**
         * Parses Abilities.json (bare array or {"abilities": [...]}). Bad entries are reported and skipped.
         * Triggers are parsed with engine.Trigger.parseList(obj, "triggers", TargetSelector.SELF).
         */
        fun load(file: File, report: LoadReport): AbilityManager = AbilityManager(
            LoadSupport.loadList(file, report, "abilities", "ability", LoadSupport.Missing.WARN) { parse(it, report) },
        )

        private fun parse(obj: JsonObj, report: LoadReport): AbilityDef {
            LoadSupport.warnUnknownKeys(obj, KEYS, report)
            LoadSupport.warnTriggerKeys(obj, report)
            val id = obj.string("id")
            return AbilityDef(
                id = id,
                name = obj.string("name", id),
                description = obj.string("description", ""),
                statModifiers = LoadSupport.statModifiers(obj),
                triggers = Trigger.parseList(obj, "triggers", TargetSelector.SELF),
            )
        }
    }
}
