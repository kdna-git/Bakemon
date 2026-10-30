package creator

import bakemon.FormulaScope
import util.Json

/** What holds a trigger (its effects default to "target" in moves, "self" otherwise: README "Effects"). */
enum class OwnerKind(val label: String, val entryKind: EntryKind) {
    MOVE("move", EntryKind.MOVE),
    ABILITY("ability", EntryKind.ABILITY),
    ITEM("item", EntryKind.ITEM),
    STATUS("status", EntryKind.STATUS),
    FIELD("field", EntryKind.FIELD);

    /** The effect target used when an effect has no "target". */
    val defaultTarget: String get() = if (this == MOVE) "target" else "self"

    /** Statuses and fields have `stacks` / `turnsLeft` in their formulas. */
    val statusContext: Boolean get() = this == STATUS || this == FIELD
}

/** One choice of a [ParamType.Choice]: the stored [value], what the user sees ([label]) and a one-line [help]. */
data class Option(val value: String, val label: String, val help: String = "")

/** The kind of value a [Param] takes. Values are: Double (or a formula String), String, Boolean, List<String>, JSON. */
sealed class ParamType {
    /**
     * A number between [min] and [max] ([integer]: whole numbers only). [percent]: shown as a percentage (25 = 25%).
     * [formula]: the "advanced" choice may give a formula string instead (checked in [scope]). [noRecipient]: the
     * formula goes where `recipient` has no value (a chance, a duration, the value of an effect without a target).
     */
    data class Number(
        val min: Double? = null,
        val max: Double? = null,
        val integer: Boolean = false,
        val percent: Boolean = false,
        val formula: Boolean = false,
        val scope: FormulaScope = FormulaScope.TRIGGER,
        val noRecipient: Boolean = false,
    ) : ParamType()

    /** One value (a String) from [options]; [multiple]: a List<String> of them. */
    data class Choice(val options: List<Option>, val multiple: Boolean = false) : ParamType()

    /** Free text (names, messages, flags). */
    object Text : ParamType()

    /** true / false. */
    object YesNo : ParamType()

    /** A formula typed by the user ("advanced"), checked in [scope] ([noRecipient]: see [Number]). */
    data class Formula(val scope: FormulaScope = FormulaScope.TRIGGER, val noRecipient: Boolean = false) : ParamType()

    /** Raw JSON ("advanced"): a parsed value, or JSON text that is parsed when used. */
    object Json : ParamType()

    /**
     * A list of effects (a List<RecipeUse> of [EffectRecipes]) edited with the same DO editor as a trigger's, e.g. the
     * effects of a delayed effect. [exclude]: effect recipes not offered there (they would do nothing).
     */
    data class Effects(val exclude: Set<String> = emptySet()) : ParamType()
}

/** Groups of recipes, so long lists of choices can be shown in two steps (a group, then a recipe). */
object RecipeGroups {
    const val DAMAGE = "Damage"
    const val HP = "Healing and HP"
    const val STATUS = "Statuses and stats"
    const val MOVES = "Moves and turns"
    const val SWITCH = "Switching"
    const val OTHER = "Fields, items and messages"
    const val COND_HP = "HP"
    const val COND_STATUS = "Statuses and types"
    const val COND_MOVE = "The move and the hit"
    const val COND_BATTLE = "Battle situation"
    const val ADVANCED = "Advanced"

    /** The order groups are shown in. */
    val ORDER = listOf(DAMAGE, HP, STATUS, MOVES, SWITCH, OTHER, COND_HP, COND_STATUS, COND_MOVE, COND_BATTLE, ADVANCED)

    /** One line about what a group holds. */
    fun help(group: String): String = when (group) {
        DAMAGE -> "damage, recoil, extra strikes, multiply the damage"
        HP -> "heal, drain, shields, set / share / swap HP, revive"
        STATUS -> "apply, remove, steal or copy statuses; raise or lower stats"
        MOVES -> "strike back, use a move, extra turns, delayed effects, action bar, cooldowns"
        SWITCH -> "drag the target out, switch out (U-turn)"
        OTHER -> "start or end fields, use up the item, messages"
        COND_HP -> "HP below / above a %, full HP, HP just dropped"
        COND_STATUS -> "has a status or a type, number of buffs / debuffs"
        COND_MOVE -> "the move's type / category / flag, hit number, critical hit, the attack landed"
        COND_BATTLE -> "already acted, faster, allies standing, charging, ally or enemy, fields"
        ADVANCED -> "write it yourself (a formula or raw JSON)"
        else -> ""
    }
}

/**
 * One input of a recipe. [default] is what a new form starts with; an [optional] parameter may be left empty
 * (absent from the values), which means the README default (e.g. no chance = always, no target = the owner's default).
 * [start]: the value a new use of the recipe starts with for an optional parameter (e.g. recoil hits the user).
 */
data class Param(
    val id: String,
    val label: String,
    val help: String,
    val type: ParamType,
    val default: Any? = null,
    val optional: Boolean = false,
    val start: Any? = null,
)

/** A recipe chosen with its parameter values (e.g. "apply_status" with {status: "burn", chance: 30.0}). */
data class RecipeUse(val recipe: String, val values: Map<String, Any?>)

/** Read access to recipe parameter values with the conversions builders need. */
class Values(val map: Map<String, Any?>, private val params: List<Param> = emptyList()) {
    private fun raw(id: String): Any? = map[id] ?: params.firstOrNull { it.id == id && !it.optional }?.default

    /** True if [id] has a value (optional parameters without one are left out of the JSON). */
    fun has(id: String): Boolean = map[id] != null

    fun num(id: String): Double = when (val v = raw(id)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    fun int(id: String): Int = Math.round(num(id)).toInt()

    /** A number (Double), or a formula String for an "advanced" value; a numeric string becomes a number. */
    fun numOrFormula(id: String): Any? = when (val v = raw(id)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: v.trim()
        else -> v
    }

    fun str(id: String): String = raw(id)?.toString() ?: ""

    fun strOrNull(id: String): String? = raw(id)?.toString()

    fun bool(id: String): Boolean = raw(id) == true

    fun list(id: String): List<String> = (raw(id) as? List<*>)?.map { it.toString() } ?: emptyList()

    /** A JSON value; a String is parsed as JSON text. */
    fun json(id: String): Any? = when (val v = raw(id)) {
        is String -> Json.parse(v, "json")
        else -> JsonTools.copy(v)
    }

    /** A list of recipe uses ([ParamType.Effects]). */
    fun uses(id: String): List<RecipeUse> = (raw(id) as? List<*>)?.filterIsInstance<RecipeUse>() ?: emptyList()
}

/**
 * A formula-free builder: an [id], a plain-language [label] and [help], typed parameters ([params], which depend on
 * the context: e.g. the statuses of the setup, or the sensible effect targets of the owner), a builder that turns
 * values into ordinary JSON, and a reader that turns JSON back into values. [recognise] only accepts JSON that the
 * builder gives back exactly, so reading and writing never lose anything.
 */
class Recipe(
    val id: String,
    val label: String,
    val help: String,
    private val paramsOf: (RecipeContext) -> List<Param>,
    private val builder: (Values, RecipeContext) -> Any?,
    private val reader: (Any?, RecipeContext) -> Map<String, Any?>?,
    private val availableIn: (RecipeContext) -> Boolean = { true },
    /** The [RecipeGroups] group it is listed under ("" = none). */
    val group: String = "",
) {
    /** The parameters to ask for in [ctx]. */
    fun params(ctx: RecipeContext): List<Param> = paramsOf(ctx)

    /** True if this recipe makes sense in [ctx] (owner kind, event, rules). */
    fun available(ctx: RecipeContext): Boolean = availableIn(ctx)

    /** The starting values of a new use: the defaults of the non-optional parameters and the starts of the optional ones. */
    fun defaults(ctx: RecipeContext): Map<String, Any?> = params(ctx).mapNotNull { p ->
        val value = if (p.optional) p.start else p.default
        value?.let { p.id to it }
    }.toMap()

    /** The JSON for [values] (missing required values take their defaults; missing optional ones are left out). */
    fun build(values: Map<String, Any?>, ctx: RecipeContext): Any? = builder(Values(values, params(ctx)), ctx)

    /** The values of [json] if it is exactly what this recipe builds, else null. */
    fun recognise(json: Any?, ctx: RecipeContext): Map<String, Any?>? {
        val values = try {
            reader(json, ctx)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val rebuilt = try {
            build(values, ctx)
        } catch (e: RuntimeException) {
            return null
        }
        return if (JsonTools.equal(rebuilt, json, ordered = false)) values else null
    }

    /** Problems with [values] in plain words (missing values, out of range, formulas that do not fit). */
    fun problems(values: Map<String, Any?>, ctx: RecipeContext): List<String> = checkParams(params(ctx), values, ctx)

    override fun toString(): String = id
}

/** Checks parameter values: required ones present, numbers in range, formulas valid for their place. */
fun checkParams(params: List<Param>, values: Map<String, Any?>, ctx: RecipeContext): List<String> {
    val problems = mutableListOf<String>()
    for (p in params) {
        val v = values[p.id]
        if (v == null || (v is String && v.isBlank() && p.type !is ParamType.Text)) {
            if (!p.optional && p.default == null) problems += "${p.label}: a value is needed."
            continue
        }
        when (val t = p.type) {
            is ParamType.Number -> {
                val number = (v as? Number)?.toDouble() ?: (v as? String)?.trim()?.toDoubleOrNull()
                if (number == null) {
                    if (!t.formula) {
                        problems += "${p.label}: enter a number."
                    } else {
                        ctx.formulaProblems(v.toString(), t.scope, !t.noRecipient).forEach { problems += "${p.label}: $it" }
                    }
                } else {
                    if (t.min != null && number < t.min) problems += "${p.label}: must be at least ${JsonTools.num(t.min)}."
                    if (t.max != null && number > t.max) problems += "${p.label}: must be at most ${JsonTools.num(t.max)}."
                    if (t.integer && number != Math.rint(number)) problems += "${p.label}: must be a whole number."
                }
            }
            is ParamType.Formula -> ctx.formulaProblems(v.toString(), t.scope, !t.noRecipient).forEach { problems += "${p.label}: $it" }
            is ParamType.Effects -> {
                val uses = (v as? List<*>)?.filterIsInstance<RecipeUse>() ?: emptyList()
                if (uses.isEmpty()) problems += "${p.label}: add at least one effect."
                for ((i, use) in uses.withIndex()) {
                    val r = EffectRecipes.byId(use.recipe)
                    val where = "${p.label} ${i + 1}"
                    when {
                        r == null -> problems += "$where: unknown effect '${use.recipe}'."
                        use.recipe in t.exclude -> problems += "$where: \"${r.label}\" does nothing here."
                        !r.available(ctx) -> problems += "$where: \"${r.label}\" does not work here."
                        else -> r.problems(use.values, ctx).forEach { problems += "$where: $it" }
                    }
                }
            }
            is ParamType.Choice -> {
                val chosen = if (t.multiple) (v as? List<*>)?.map { it.toString() } ?: listOf(v.toString()) else listOf(v.toString())
                val known = t.options.map { it.value }.toSet()
                chosen.filter { it !in known }.forEach { problems += "${p.label}: '$it' is not one of the choices." }
            }
            is ParamType.Json -> if (v is String) {
                try {
                    Json.parse(v, "json")
                } catch (e: Exception) {
                    problems += "${p.label}: ${e.message}"
                }
            }
            ParamType.Text, ParamType.YesNo -> {}
        }
    }
    return problems
}

/** A field as recipes see it. */
data class FieldInfo(val id: String, val name: String, val side: Boolean, val group: String?)

/**
 * What recipes need to know about the setup and the place they are used: the owner kind, the trigger event, the
 * stats, types, statuses, fields, move categories and flags of the setup, and the rules that change which recipes
 * make sense (action bar, PP, cooldowns). Build one with [SetupDraft.recipeContext] (or directly in tests).
 */
data class RecipeContext(
    val owner: OwnerKind,
    /** The trigger event ("on") the recipe is used in, if any. */
    val event: String? = null,
    val stats: List<Option> = emptyList(),
    val hpStat: String = "hp",
    val speedStat: String = "spe",
    val types: List<Option> = emptyList(),
    /** Statuses: value = id, label = name, help = kind. */
    val statuses: List<Option> = emptyList(),
    val fields: List<FieldInfo> = emptyList(),
    val categories: List<Option> = emptyList(),
    /** Categories that deal damage. */
    val damagingCategories: Set<String> = emptySet(),
    /** Move flags used in the setup (plus "contact"). */
    val flags: List<String> = listOf("contact"),
    val actionBar: Boolean = false,
    val pp: Boolean = true,
    val cooldowns: Boolean = false,
    /** True when the rules use the Gacha damage template (move power is the attacking value, e.g. "user.atk * 3.6"). */
    val powerFromStats: Boolean = false,
    /** Id of the entry being edited (statuses refer to themselves, e.g. "wake up when hit" removes this status). */
    val entryId: String = "",
    /** For field triggers: true if the field is a side field ("for": "opponents" is only offered then). */
    val sideField: Boolean = false,
    /** Used to check "advanced" formulas; null = only the syntax is checked. */
    val draft: SetupDraft? = null,
    /** Moves of the setup: value = id, label = name (for "use a move"). */
    val moves: List<Option> = emptyList(),
) {
    /** This context for another trigger event. */
    fun withEvent(event: String?): RecipeContext = copy(event = event)

    /** Stats that stat stages can change (every stat but HP). */
    val stageStats: List<Option> get() = stats.filter { it.value != hpStat }

    /** True if the event sets `damage` (damage-based effects and conditions). */
    val hasDamage: Boolean get() = event in Events.DAMAGE_EVENTS

    /** True if a move is involved in the event (move conditions). */
    val hasMove: Boolean get() = owner == OwnerKind.MOVE || event in Events.MOVE_EVENTS

    /** True if the event (may) have an "other" monster (always in move triggers: the target). */
    val hasOther: Boolean get() = owner == OwnerKind.MOVE || event == null || event !in Events.NO_OTHER

    /** True if the event's other monster is a move user, so "the target of that move" (otherTarget) exists. */
    val otherUsesMove: Boolean get() = owner != OwnerKind.MOVE && event in Events.OTHER_USES_MOVE

    /**
     * Problems of a formula typed by the user for [scope] (only syntax without a draft). [recipient] false: the
     * formula goes where `recipient` has no value (conditions, chances, durations...).
     */
    fun formulaProblems(text: String, scope: FormulaScope, recipient: Boolean = true): List<String> {
        val d = draft
        if (d != null) return d.checkFormula(text, scope, owner.statusContext && scope != FormulaScope.MOVE, event, recipient)
        return try {
            engine.Formula.parse(text)
            emptyList()
        } catch (e: Exception) {
            listOf(e.message ?: "invalid formula")
        }
    }

    companion object {
        /** The context of [draft] for [owner] and [event]. */
        fun of(draft: SetupDraft, owner: OwnerKind, event: String? = null, entryId: String = ""): RecipeContext {
            val rules = draft.rules()
            val stats = (rules["stats"] as? List<*>).orEmpty().mapNotNull { s ->
                when (s) {
                    is String -> Option(s, s)
                    is Map<*, *> -> (s["id"] as? String)?.let { Option(it, (s["name"] as? String) ?: it) }
                    else -> null
                }
            }
            val categories = (rules["categories"] as? Map<*, *>).orEmpty()
            val statuses = draft.entries(EntryKind.STATUS).mapNotNull { e ->
                e.str("id")?.let { Option(it, e.str("name") ?: it, e.str("kind") ?: "") }
            }
            val fields = draft.entries(EntryKind.FIELD).mapNotNull { e ->
                e.str("id")?.let { FieldInfo(it, e.str("name") ?: it, e.str("scope").equals("side", true), e.str("group")) }
            }
            val flags = (listOf("contact") + draft.entries(EntryKind.MOVE).flatMap { it.strings("flags") ?: emptyList() }).distinct()
            val recognition = RuleTemplate.recognise(rules)
            val entry = if (entryId.isNotEmpty() && owner == OwnerKind.FIELD) draft.entry(EntryKind.FIELD, entryId) else null
            return RecipeContext(
                owner = owner,
                event = event,
                stats = stats,
                hpStat = rules.str("hpStat") ?: "hp",
                speedStat = rules.str("speedStat") ?: "spe",
                types = draft.infos(EntryKind.TYPE).map { Option(it.id, it.name) },
                statuses = statuses,
                fields = fields,
                categories = categories.keys.map { Option(it.toString(), it.toString()) },
                damagingCategories = categories.filter { (_, v) -> (v as? Map<*, *>)?.let { it["attack"] != null && it["defense"] != null } == true }
                    .keys.map { it.toString() }.toSet(),
                flags = flags,
                actionBar = rules.str("turnSystem").equals("action_bar", true),
                pp = rules["pp"] != false,
                cooldowns = rules["cooldowns"] == true,
                powerFromStats = recognition.answers.damage == DamagePreset.GACHA && RulePart.DAMAGE !in recognition.custom,
                entryId = entryId,
                sideField = entry?.str("scope").equals("side", true),
                draft = draft,
                moves = draft.infos(EntryKind.MOVE).map { Option(it.id, it.name) },
            )
        }
    }
}

/** Shared parameter helpers for the recipe catalogs. */
internal object P {
    fun percent(id: String, label: String, help: String, default: Double, min: Double = 0.0, max: Double? = 100.0) =
        Param(id, label, help, ParamType.Number(min = min, max = max, percent = true), default)

    fun number(id: String, label: String, help: String, default: Double?, min: Double? = null, max: Double? = null, integer: Boolean = false, formula: Boolean = true, optional: Boolean = false, scope: FormulaScope = FormulaScope.TRIGGER) =
        Param(id, label, help, ParamType.Number(min, max, integer, false, formula, scope), default, optional)

    fun choice(id: String, label: String, help: String, options: List<Option>, default: String? = options.firstOrNull()?.value, optional: Boolean = false) =
        Param(id, label, help, ParamType.Choice(options), default, optional)

    fun text(id: String, label: String, help: String, default: String? = null, optional: Boolean = true) =
        Param(id, label, help, ParamType.Text, default, optional)

    fun yesNo(id: String, label: String, help: String, default: Boolean = false) = Param(id, label, help, ParamType.YesNo, default)

    /** Effect target choices for effects on living monsters (only the sensible ones for the owner and event). */
    fun livingTargets(ctx: RecipeContext): List<Option> = if (ctx.owner == OwnerKind.MOVE) listOf(
        Option("target", "the target", "the monster the move is used on (default)"),
        Option("user", "the user", "the monster using the move"),
        Option("allies", "the user's allies", "every active ally of the user, not the user"),
        Option("team", "the user's team", "the user and its active allies"),
        Option("lowestHpAlly", "the weakest of the user's team", "the user or its ally with the lowest HP %"),
        Option("randomAlly", "a random ally", "one random ally of the user (not the user; nobody if it is alone)"),
        Option("enemies", "every enemy", "every active enemy of the user"),
        Option("randomEnemy", "a random enemy", "one random active enemy"),
        Option("lowestHpEnemy", "the weakest enemy", "the enemy with the lowest HP %"),
        Option("highestHpEnemy", "the healthiest enemy", "the enemy with the highest HP %"),
        Option("all", "every monster", "every active monster"),
    ) else listOfNotNull(
        Option("self", "itself", "the monster that has it (default)"),
        Option("other", "the other monster", "the other monster of the event: the attacker, the target, the one that applied it..."),
        if (ctx.otherUsesMove) Option("otherTarget", "the target of that move", "the monster the other monster's move was aimed at (e.g. to join an ally's attack)") else null,
        Option("allies", "its allies", "every active ally, not itself"),
        Option("team", "its team", "itself and its active allies"),
        Option("lowestHpAlly", "the weakest of its team", "itself or its ally with the lowest HP %"),
        Option("randomAlly", "a random ally", "one random ally (not itself; nobody if it is alone)"),
        Option("enemies", "every enemy", "every active enemy"),
        Option("randomEnemy", "a random enemy", "one random active enemy"),
        Option("lowestHpEnemy", "the weakest enemy", "the enemy with the lowest HP %"),
        Option("highestHpEnemy", "the healthiest enemy", "the enemy with the highest HP %"),
        Option("all", "every monster", "every active monster"),
    )

    /** Living targets with the owner first ("user" in moves): who receives something taken from another monster. */
    fun receivers(ctx: RecipeContext): List<Option> = livingTargets(ctx).let { all ->
        val own = all.filter { it.value == ownRef(ctx) }
        own + (all - own.toSet())
    }

    /** Targets that select a group of monsters (for effects that share something within a group). */
    fun groupTargets(ctx: RecipeContext): List<Option> =
        livingTargets(ctx).filter { it.value in setOf("allies", "team", "enemies", "all") }

    /** The reference of the trigger's owner: "user" in a move (its self), "self" elsewhere. */
    fun ownRef(ctx: RecipeContext): String = if (ctx.owner == OwnerKind.MOVE) "user" else "self"

    /**
     * A sensible first target for effects aimed at an opponent: null (= the move's target) in moves, the other monster
     * of the event when there is one, else a random enemy.
     */
    fun foeStart(ctx: RecipeContext): String? = when {
        ctx.owner == OwnerKind.MOVE -> null
        ctx.hasOther -> "other"
        else -> "randomEnemy"
    }

    /** Targets that are one monster, usable in formulas as `<ref>.maxHp`. */
    fun singleTargets(ctx: RecipeContext): List<Option> = livingTargets(ctx).filter { it.value in REFS }

    /** Targets a revive can reach (README "Revives"). */
    fun faintedTargets(ctx: RecipeContext, singleOnly: Boolean): List<Option> {
        val single = if (ctx.owner == OwnerKind.MOVE) listOf(
            Option("target", "the chosen fainted ally", "with a \"faintedAlly\" move target (default)"),
            Option("user", "the user", "the monster using the move"),
        ) else listOf(
            Option("self", "itself", "e.g. in a \"when it faints\" trigger: revive on death (default)"),
            Option("other", "the other monster", "the other monster of the event (e.g. the fainted ally)"),
        )
        if (singleOnly) return single
        return single + listOf(
            Option("faintedAllies", "every fainted ally", "every fainted teammate that can still be revived"),
            Option("randomFaintedAlly", "a random fainted ally", "one fainted teammate that can still be revived"),
        )
    }

    /** Monsters a condition can look at. */
    fun whoOptions(ctx: RecipeContext): List<Option> = if (ctx.owner == OwnerKind.MOVE) listOf(
        Option("target", "the target"), Option("user", "the user"),
    ) else listOf(
        Option("self", "itself"), Option("other", "the other monster (attacker, target...)"),
    )

    val REFS = setOf("self", "other", "user", "target")

    /** The optional "target" parameter; a new use starts with [default] (null = empty, the owner's default target). */
    fun targetParam(ctx: RecipeContext, options: List<Option> = livingTargets(ctx), default: String? = null, label: String = "Who", help: String = "Who it affects") =
        Param("target", label, "$help. Empty = ${options.firstOrNull { it.value == ctx.owner.defaultTarget }?.label ?: ctx.owner.defaultTarget}.",
            ParamType.Choice(options), default, optional = true, start = default)

    fun statusOptions(ctx: RecipeContext) = ctx.statuses
    fun typeOptions(ctx: RecipeContext) = ctx.types
    fun fieldOptions(ctx: RecipeContext) = ctx.fields.map { Option(it.id, it.name, if (it.side) "side field" else "global field") }
}
