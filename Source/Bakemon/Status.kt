package bakemon

import engine.Formula
import engine.TargetSelector
import engine.Trigger
import engine.formulaOrNull
import util.JsonObj
import util.SetupException
import java.io.File

/** Status families. A monster can have only one MAJOR status at a time. */
enum class StatusKind(val id: String) {
    MAJOR("major"),
    VOLATILE("volatile"),
    BUFF("buff"),
    DEBUFF("debuff");

    companion object {
        fun fromId(id: String): StatusKind? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** What happens when a status is applied to a monster that already has it. */
enum class Stacking(val id: String) {
    /** Nothing happens (the application fails). */
    IGNORE("ignore"),
    /** The duration is reset (the status keeps 1 stack). */
    REFRESH("refresh"),
    /** Stacks are added (up to maxStacks) and the duration is reset. */
    STACK("stack");

    companion object {
        fun fromId(id: String): Stacking? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** A status effect / buff / debuff from Statuses.json. */
class StatusDef(
    val id: String,
    val name: String,
    /** Short tag for the UI, e.g. "BRN" (defaults to the first 3 letters of the name, upper-case). */
    val short: String,
    val description: String,
    val kind: StatusKind,
    /** In owner turns; null = until removed. */
    val duration: Formula?,
    val stacking: Stacking,
    val maxStacks: Int,
    val immuneTypes: Set<String>,
    /** stat id -> multiplier formula (may use `stacks`). */
    val statModifiers: Map<String, Formula>,
    /** Effect target defaults to "self". */
    val triggers: List<Trigger>,
    /** May contain {target}; null = default engine message. */
    val applyMessage: String?,
    val removeMessage: String?,
) {
    override fun toString(): String = name
}

/** All statuses of a setup (Statuses.json). Keeps file order. */
class StatusManager(val statuses: Map<String, StatusDef>) {
    operator fun get(id: String): StatusDef? = statuses[id]

    /** Throws util.SetupException("Unknown status '<id>'") if missing. */
    fun require(id: String): StatusDef = statuses[id] ?: throw SetupException("Unknown status '$id'")

    fun all(): List<StatusDef> = statuses.values.toList()

    companion object {
        private val KEYS = setOf(
            "id", "name", "short", "description", "kind", "duration", "stacking", "maxStacks", "immuneTypes",
            "statModifiers", "triggers", "applyMessage", "removeMessage",
        )

        /**
         * Parses Statuses.json (bare array or {"statuses": [...]}). Bad entries are reported and skipped.
         * Triggers are parsed with engine.Trigger.parseList(obj, "triggers", TargetSelector.SELF).
         * A missing Statuses.json is allowed (empty manager).
         */
        fun load(file: File, report: LoadReport): StatusManager = StatusManager(
            LoadSupport.loadList(file, report, "statuses", "status", LoadSupport.Missing.SILENT) { parse(it, report) },
        )

        private fun parse(obj: JsonObj, report: LoadReport): StatusDef {
            LoadSupport.warnUnknownKeys(obj, KEYS, report)
            LoadSupport.warnTriggerKeys(obj, report)
            val id = obj.string("id")
            if (id.equals("all", ignoreCase = true)) LoadSupport.failAt(obj, "id", "'$id' is reserved (removeStatus uses \"all\" for every status)")
            val name = obj.string("name", id)
            val kindId = obj.string("kind")
            val kind = StatusKind.fromId(kindId) ?: LoadSupport.failAt(
                obj, "kind", "unknown kind '$kindId' (use one of: ${StatusKind.entries.joinToString(", ") { it.id }})",
            )
            val stackingId = obj.string("stacking", Stacking.IGNORE.id)
            val stacking = Stacking.fromId(stackingId) ?: LoadSupport.failAt(
                obj, "stacking", "unknown stacking '$stackingId' (use one of: ${Stacking.entries.joinToString(", ") { it.id }})",
            )
            val maxStacks = obj.int("maxStacks", 1)
            if (maxStacks < 1) LoadSupport.failAt(obj, "maxStacks", "must be at least 1 (got $maxStacks)")
            if (maxStacks > 1 && stacking != Stacking.STACK) {
                report.warn("${obj.pathOf("maxStacks")}: only used with \"stacking\": \"stack\" (this status uses '${stacking.id}')")
            }
            val duration = obj.formulaOrNull("duration")
            val durationValue = duration?.constant
            if (durationValue != null && durationValue < 0) LoadSupport.failAt(obj, "duration", "must not be negative (got $durationValue)")
            val short = obj.stringOrNull("short")?.takeIf { it.isNotBlank() }
                ?: name.filter { it.isLetterOrDigit() }.take(3).uppercase().ifEmpty { id.take(3).uppercase() }
            return StatusDef(
                id = id,
                name = name,
                short = short,
                description = obj.string("description", ""),
                kind = kind,
                duration = duration,
                stacking = stacking,
                maxStacks = maxStacks,
                immuneTypes = obj.strings("immuneTypes").toCollection(LinkedHashSet()),
                statModifiers = LoadSupport.statModifiers(obj),
                triggers = Trigger.parseList(obj, "triggers", TargetSelector.SELF),
                applyMessage = obj.stringOrNull("applyMessage"),
                removeMessage = obj.stringOrNull("removeMessage"),
            )
        }
    }
}
