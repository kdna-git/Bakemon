package bakemon

import engine.Formula
import engine.TargetSelector
import engine.Trigger
import engine.formulaOrNull
import util.JsonObj
import util.SetupException
import java.io.File

/** What a field covers (README "Fields.json"). */
enum class FieldScope(val id: String) {
    /** The whole battle: every monster of both sides. */
    GLOBAL("global"),
    /** One side: that side's monsters. */
    SIDE("side");

    companion object {
        fun fromId(id: String): FieldScope? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** Whose events a field trigger listens to (the JSON trigger key "for"). */
enum class FieldAudience(val id: String) {
    /** Events about a monster the field covers (default). */
    COVERED("covered"),
    /** Side fields only: events about a monster of the other side ("when an enemy takes damage"). */
    OPPONENTS("opponents"),
    /** Events about any monster on the field. */
    ALL("all");

    companion object {
        fun fromId(id: String): FieldAudience? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** A trigger of a field plus whose events it listens to. Its owner is the monster the event is about. */
class FieldTrigger(val trigger: Trigger, val audience: FieldAudience)

/**
 * The "rules" object of a field: overrides and blocks that apply while the field is active (README "Fields.json").
 * Every member is optional (null / false = no change).
 */
class FieldRules(
    /** Replaces Rules.damage when the attacker is covered (same variables). */
    val damage: Formula? = null,
    /** Replaces Rules.hitChance when the attacker is covered. */
    val hitChance: Formula? = null,
    /** Replaces Rules.critChance when the attacker is covered. */
    val critChance: Formula? = null,
    /** Replaces Rules.critMultiplier when the attacker is covered. */
    val critMultiplier: Formula? = null,
    /** Replaces Rules.actionBar.fill for covered monsters. */
    val actionBarFill: Formula? = null,
    /** speed_order, global fields only: actions of the same priority run slowest first. */
    val reverseSpeed: Boolean = false,
    /** self = a covered monster; true = its held item does nothing. */
    val disableItems: Formula? = null,
    /** self = a covered monster; true = its ability does nothing. */
    val disableAbilities: Formula? = null,
    /** user = the covered monster about to move, move, target (absent at choice time); true = the move cannot be used. */
    val blockMove: Formula? = null,
    val blockMoveMessage: String? = null,
    /** self = the covered monster about to get a status, other = the source, vars status / statusKind; true = not applied. */
    val blockStatus: Formula? = null,
    val blockStatusMessage: String? = null,
    /** self = the covered target, other = the source, vars stat / stages; true = the stage change does not happen. */
    val blockStatStage: Formula? = null,
    val blockStatStageMessage: String? = null,
) {
    /** True if any member is set. */
    val isEmpty: Boolean
        get() = damage == null && hitChance == null && critChance == null && critMultiplier == null && actionBarFill == null &&
            !reverseSpeed && disableItems == null && disableAbilities == null && blockMove == null && blockStatus == null &&
            blockStatStage == null

    companion object {
        val NONE = FieldRules()
    }
}

/** A field from Fields.json: weather, terrain, a screen, an entry hazard... (README "Fields.json"). */
class FieldDef(
    val id: String,
    val name: String,
    /** Short tag for the UI (defaults to the first 4 letters of the name, upper-case). */
    val short: String,
    val description: String,
    val scope: FieldScope,
    /** Exclusive group: setting this field removes the other active fields of the group in the same place. */
    val group: String?,
    /** In turns (README "Fields.json", countdown); null = until removed. */
    val duration: Formula?,
    val stacking: Stacking,
    val maxStacks: Int,
    /** May be picked as a starting field in the battle menu. */
    val selectable: Boolean,
    /** stat id -> multiplier formula for every covered monster (may use `stacks`, `turnsLeft`). */
    val statModifiers: Map<String, Formula>,
    /** Effect target defaults to "self" (the monster the event is about). */
    val triggers: List<FieldTrigger>,
    val rules: FieldRules,
    /** Placeholders {field} {side} {user} {stacks}; null = default engine message, "" = silent. */
    val startMessage: String?,
    val endMessage: String?,
) {
    val isGlobal: Boolean get() = scope == FieldScope.GLOBAL

    override fun toString(): String = name
}

/**
 * A field to set when a battle starts (Rules.json "startFields", or chosen in the battle menu and passed to the
 * engine.Manager constructor).
 */
data class FieldStart(
    /** Field id (Fields.json). */
    val field: String,
    /** Side fields: 0 or 1 for one side, null for both. Ignored for global fields. */
    val side: Int? = null,
    /** Turns (formula with battle variables only); null = the field's own duration (or permanent, see [permanent]). */
    val duration: Formula? = null,
    /** True = never expires (the duration is ignored). Defaults to true when no [duration] is given. */
    val permanent: Boolean = duration == null,
)

/** All fields of a setup (Fields.json). Keeps file order. */
class FieldManager(val fields: Map<String, FieldDef>) {
    operator fun get(id: String): FieldDef? = fields[id]

    /** Throws util.SetupException("Unknown field '<id>'") if missing. */
    fun require(id: String): FieldDef = fields[id] ?: throw SetupException("Unknown field '$id'")

    fun all(): List<FieldDef> = fields.values.toList()

    /** Fields that may be picked as a starting field in the battle menu. */
    fun selectable(): List<FieldDef> = fields.values.filter { it.selectable }

    /** Every group name used by a field. */
    val groups: Set<String> get() = fields.values.mapNotNullTo(LinkedHashSet()) { it.group }

    companion object {
        private val KEYS = setOf(
            "id", "name", "short", "description", "scope", "group", "duration", "stacking", "maxStacks", "selectable",
            "statModifiers", "triggers", "rules", "startMessage", "endMessage",
        )
        val RULE_KEYS = setOf(
            "damage", "hitChance", "critChance", "critMultiplier", "actionBarFill", "reverseSpeed", "disableItems",
            "disableAbilities", "blockMove", "blockMoveMessage", "blockStatus", "blockStatusMessage", "blockStatStage",
            "blockStatStageMessage",
        )

        /**
         * Parses Fields.json (bare array or {"fields": [...]}). Bad entries are reported and skipped.
         * A missing Fields.json is allowed (empty manager).
         */
        fun load(file: File, report: LoadReport): FieldManager = FieldManager(
            LoadSupport.loadList(file, report, "fields", "field", LoadSupport.Missing.SILENT) { parse(it, report) },
        )

        private fun parse(obj: JsonObj, report: LoadReport): FieldDef {
            LoadSupport.warnUnknownKeys(obj, KEYS, report)
            LoadSupport.warnTriggerKeys(obj, report, extraKeys = setOf("for"))
            val id = obj.string("id")
            if (id.equals("all", ignoreCase = true)) LoadSupport.failAt(obj, "id", "'$id' is reserved (removeField uses \"all\" for every field)")
            val name = obj.string("name", id)
            val scopeId = obj.string("scope", FieldScope.GLOBAL.id)
            val scope = FieldScope.fromId(scopeId) ?: LoadSupport.failAt(
                obj, "scope", "unknown scope '$scopeId' (use one of: ${FieldScope.entries.joinToString(", ") { it.id }})",
            )
            val group = obj.stringOrNull("group")?.takeIf { it.isNotBlank() }
            val stackingId = obj.string("stacking", Stacking.IGNORE.id)
            val stacking = Stacking.fromId(stackingId) ?: LoadSupport.failAt(
                obj, "stacking", "unknown stacking '$stackingId' (use one of: ${Stacking.entries.joinToString(", ") { it.id }})",
            )
            val maxStacks = obj.int("maxStacks", 1)
            if (maxStacks < 1) LoadSupport.failAt(obj, "maxStacks", "must be at least 1 (got $maxStacks)")
            if (maxStacks > 1 && stacking != Stacking.STACK) {
                report.warn("${obj.pathOf("maxStacks")}: only used with \"stacking\": \"stack\" (this field uses '${stacking.id}')")
            }
            val duration = obj.formulaOrNull("duration")
            val durationValue = duration?.constant
            if (durationValue != null && durationValue < 0) LoadSupport.failAt(obj, "duration", "must not be negative (got $durationValue)")
            val short = obj.stringOrNull("short")?.takeIf { it.isNotBlank() }
                ?: name.filter { it.isLetterOrDigit() }.take(4).uppercase().ifEmpty { id.take(4).uppercase() }
            val triggers = obj.arrOrEmpty("triggers").objects().map { t ->
                val audienceId = t.string("for", FieldAudience.COVERED.id)
                val audience = FieldAudience.fromId(audienceId) ?: LoadSupport.failAt(
                    t, "for", "unknown value '$audienceId' (use one of: ${FieldAudience.entries.joinToString(", ") { it.id }})",
                )
                if (audience == FieldAudience.OPPONENTS && scope != FieldScope.SIDE) {
                    LoadSupport.failAt(t, "for", "\"opponents\" only works in side fields (a global field has no opponents)")
                }
                FieldTrigger(Trigger.parse(t, TargetSelector.SELF), audience)
            }
            return FieldDef(
                id = id,
                name = name,
                short = short,
                description = obj.string("description", ""),
                scope = scope,
                group = group,
                duration = duration,
                stacking = stacking,
                maxStacks = maxStacks,
                selectable = obj.bool("selectable", false),
                statModifiers = LoadSupport.statModifiers(obj),
                triggers = triggers,
                rules = obj.objOrNull("rules")?.let { parseRules(it, report) } ?: FieldRules.NONE,
                startMessage = obj.stringOrNull("startMessage"),
                endMessage = obj.stringOrNull("endMessage"),
            )
        }

        private fun parseRules(obj: JsonObj, report: LoadReport): FieldRules {
            LoadSupport.warnUnknownKeys(obj, RULE_KEYS, report)
            return FieldRules(
                damage = obj.formulaOrNull("damage"),
                hitChance = obj.formulaOrNull("hitChance"),
                critChance = obj.formulaOrNull("critChance"),
                critMultiplier = obj.formulaOrNull("critMultiplier"),
                actionBarFill = obj.formulaOrNull("actionBarFill"),
                reverseSpeed = obj.bool("reverseSpeed", false),
                disableItems = condition(obj, "disableItems"),
                disableAbilities = condition(obj, "disableAbilities"),
                blockMove = condition(obj, "blockMove"),
                blockMoveMessage = obj.stringOrNull("blockMoveMessage"),
                blockStatus = condition(obj, "blockStatus"),
                blockStatusMessage = obj.stringOrNull("blockStatusMessage"),
                blockStatStage = condition(obj, "blockStatStage"),
                blockStatStageMessage = obj.stringOrNull("blockStatStageMessage"),
            )
        }

        /** A condition formula; a JSON boolean is accepted too (true = always, false = never = no rule). */
        private fun condition(obj: JsonObj, key: String): Formula? = when (val v = obj.raw(key)) {
            is Boolean -> if (v) Formula.of(1.0) else null
            else -> obj.formulaOrNull(key)
        }
    }
}
