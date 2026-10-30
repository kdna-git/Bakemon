package creator

/**
 * Entry forms: plain data classes for one entry of each kind, read from the raw JSON and written back into it.
 * Writing changes only the keys whose form value differs from what the original entry reads as, so unknown keys,
 * values the form cannot show and untouched parts stay exactly as they were.
 */
internal object FormSupport {
    /** Sets [key] to [value] in canonical [order]; null removes it; a default value is omitted unless the key exists. */
    fun set(m: MutableMap<String, Any?>, key: String, value: Any?, order: List<String>, default: Any? = Unit) {
        val isDefault = default != Unit && JsonTools.equal(value, default)
        if (value == null || (isDefault && !m.containsKey(key))) m.remove(key) else JsonTools.putOrdered(m, key, value, order)
    }

    fun num(value: Int?): Double? = value?.toDouble()

    /** A number stays a Double, a numeric string becomes a Double, any other string is a formula. */
    fun numOrFormula(value: Any?): Any? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: value
        else -> value
    }

    fun int(value: Any?): Int? = (value as? Number)?.toDouble()?.takeIf { it == Math.rint(it) }?.toInt()

    fun stringMap(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()
}

/** Helpers for durations (turns, a random range, or permanent). */
object Durations {
    /** "randint(min, max)" for a random duration, or the number when both are equal. */
    fun range(min: Int, max: Int): Any = if (min == max) min.toDouble() else "randint($min, $max)"

    /** (min, max) of a duration written by [range] (a number gives (n, n)); null for permanent or a formula. */
    fun rangeOf(value: Any?): Pair<Int, Int>? {
        if (value is Number) return value.toInt() to value.toInt()
        val m = Regex("^randint\\((\\d+), (\\d+)\\)$").matchEntire(value as? String ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}

/** Helpers for statModifiers (+X% of a stat = multiplier 1 + X / 100). */
object StatMods {
    /** +20 -> 1.2, -50 -> 0.5. */
    fun multiplier(percent: Double): Double = JsonTools.round(1 + percent / 100)

    /** 1.2 -> 20, 0.5 -> -50; null for a formula. */
    fun percent(multiplier: Any?): Double? = (multiplier as? Number)?.let { JsonTools.round((it.toDouble() - 1) * 100) }
}

// ----------------------------------------------------------------------------------------------------------------
// Types

/** A type (Types.json "types"); its chart row is edited with [SetupDraft.setEffectiveness]. */
data class TypeForm(val id: String = "", val name: String = "", val color: String? = null)

/** Reads and writes [TypeForm]s. */
object TypeForms {
    private val ORDER = listOf("id", "name", "color")

    /** Colour names the terminal understands (README Types.json), bright variants included. */
    val COLORS: List<String> = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white", "gray") +
        listOf("Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White").map { "bright$it" }

    /** The form of a Types.json "types" entry. */
    fun read(entry: Map<String, Any?>): TypeForm = TypeForm(entry.str("id") ?: "", entry.str("name") ?: entry.str("id") ?: "", entry.str("color"))

    /** The JSON of [form]; with [base] (the entry it was read from) only the changed keys are rewritten. */
    fun build(form: TypeForm, base: Map<String, Any?>? = null): MutableMap<String, Any?> {
        val m = base?.let { JsonTools.copyMap(it) } ?: LinkedHashMap()
        val old = base?.let { read(it) } ?: TypeForm()
        FormSupport.set(m, "id", form.id, ORDER)
        if (form.name != old.name || base == null) FormSupport.set(m, "name", form.name, ORDER)
        if (form.color != old.color) FormSupport.set(m, "color", form.color, ORDER)
        return m
    }

    /**
     * Chart multiplier choices for a type matchup: strong / weak / immune / neutral, plus the other values already used
     * in the chart (a gacha setup may use 1.3 / 0.7). "custom" means any other number.
     */
    fun multiplierChoices(draft: SetupDraft): List<Option> {
        val standard = listOf(
            Option("2", "strong (x2)"), Option("0.5", "weak (x0.5)"), Option("0", "immune (x0)"), Option("1", "neutral (x1)"),
        )
        val used = draft.typeChart().values.flatMap { it.values }.distinct().sorted()
            .map { JsonTools.num(it) }.filter { v -> standard.none { it.value == v } }
            .map { Option(it, "x$it") }
        return standard + used + Option("custom", "another multiplier")
    }
}

// ----------------------------------------------------------------------------------------------------------------
// Moves

/** A move (Moves.json) as a form. [power] is a [PowerRecipes] use; [triggers] are WHEN/IF/DO triggers. */
data class MoveForm(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    /** Type id, or null for a typeless move. */
    val type: String? = null,
    val category: String = "",
    val power: RecipeUse = RecipeUse("fixed", mapOf("power" to 40.0)),
    /** Percent; null = never misses. */
    val accuracy: Double? = 100.0,
    val priority: Int = 0,
    /** With PP rules (0 = unlimited); null = not written. */
    val pp: Int? = null,
    /** With cooldown rules; null = not written. */
    val cooldown: Int? = null,
    /** A move target id (see [MoveForms.TARGETS]). */
    val target: String = "enemy",
    /** Hits per target: null = 1, a number, or a formula such as "randint(2, 5)" (see [Durations.range]). */
    val hits: Any? = null,
    /** "each": every hit rolls the accuracy (the first miss ends the move on that target); null = one roll ("once"). */
    val hitAccuracy: String? = null,
    /** Extra crit chance (0..1, e.g. 0.125) or a formula; null = none. */
    val critBonus: Any? = null,
    /** Percent of the target's defense the damage formula ignores (a number or a formula); null = none. */
    val ignoreDefense: Any? = null,
    /** The raw "charge" object (see [ChargeForms]); null = not a charge move. */
    val charge: Any? = null,
    val flags: List<String> = emptyList(),
    val triggers: List<TriggerForm> = emptyList(),
)

/** Reads and writes [MoveForm]s. */
object MoveForms {
    /** Canonical key order of a move (README). */
    val ORDER = listOf(
        "id", "name", "description", "type", "category", "power", "accuracy", "priority", "pp", "cooldown", "target",
        "hits", "hitAccuracy", "critBonus", "ignoreDefense", "charge", "flags", "triggers",
    )

    /** Move targets in plain words (README "Moves.json"). */
    val TARGETS = listOf(
        Option("enemy", "one enemy (chosen)"), Option("allEnemies", "every enemy"), Option("randomEnemy", "a random enemy"),
        Option("self", "the user itself"), Option("ally", "one ally (chosen)"), Option("allyOrSelf", "an ally or itself (chosen)"),
        Option("allAllies", "every ally"), Option("team", "itself and every ally"), Option("all", "every other monster"),
        Option("faintedAlly", "a fainted ally (chosen, for revives)"),
        Option("lowestHpAlly", "the weakest of its team (automatic)", "itself or the ally with the lowest HP %, chosen when the move runs"),
        Option("lowestHpEnemy", "the weakest enemy (automatic)", "the enemy with the lowest HP %, chosen when the move runs"),
        Option("randomEnemies", "a random enemy for each hit", "every hit picks a new random enemy (the hits are the total)"),
    )

    /**
     * Turns [form]'s power into "grows with each hit" ([grows] true: a fixed power P becomes P x hit number) or back
     * to a fixed power. Returns null when the power is not a fixed one (or not a growing one) and cannot be changed.
     */
    fun withGrowingPower(form: MoveForm, grows: Boolean): MoveForm? {
        val p = form.power
        return when {
            grows && p.recipe == "per_hit" -> form
            !grows && p.recipe != "per_hit" -> form
            grows && p.recipe == "fixed" -> form.copy(power = RecipeUse("per_hit", mapOf("power" to p.values["power"])))
            !grows -> form.copy(power = RecipeUse("fixed", mapOf("power" to p.values["power"])))
            else -> null
        }
    }

    /** Hits per target: a number when [min] == [max], else a random count "randint(min, max)". */
    fun hits(min: Int, max: Int): Any = Durations.range(min, max)

    /** (min, max) hits of a move's "hits" value (null = 1 hit is (1, 1); null for another formula). */
    fun hitsRange(hits: Any?): Pair<Int, Int>? = if (hits == null) 1 to 1 else Durations.rangeOf(hits)

    /** "High crit chance" adds this to the crit chance. */
    const val HIGH_CRIT_BONUS = 0.125

    /** A new move form with sensible defaults for [ctx] (first damaging category, PP or cooldown per the rules). */
    fun blank(ctx: RecipeContext, name: String = "", id: String = ""): MoveForm = MoveForm(
        id = id, name = name,
        category = ctx.damagingCategories.firstOrNull() ?: ctx.categories.firstOrNull()?.value ?: "",
        power = if (ctx.powerFromStats) RecipeUse("stat_based", mapOf("stat" to (ctx.stats.firstOrNull { it.value == "atk" }?.value ?: ctx.stats.firstOrNull()?.value ?: "atk"), "percent" to 100.0))
        else RecipeUse("fixed", mapOf("power" to 40.0)),
        pp = if (ctx.pp) 20 else null,
        cooldown = if (ctx.cooldowns) 0 else null,
    )

    /** The form of a move entry: its power recipe (a fixed-damage hit trigger is part of it) and its triggers. */
    fun read(entry: Map<String, Any?>, ctx: RecipeContext): MoveForm {
        val c = ctx.copy(owner = OwnerKind.MOVE)
        val triggers = (entry["triggers"] as? List<*>).orEmpty()
        var consumed = 0
        val powerPart = if (entry.containsKey("power")) mapOf("power" to entry["power"]) else emptyMap()
        var power: RecipeUse? = null
        if (!entry.containsKey("power") && triggers.isNotEmpty()) {
            PowerRecipes.byId("fixed_damage")!!.recognise(mapOf("trigger" to triggers[0]), c)?.let {
                power = RecipeUse("fixed_damage", it)
                consumed = 1
            }
        }
        if (power == null) {
            for (r in PowerRecipes.all) {
                r.recognise(powerPart, c)?.let { power = RecipeUse(r.id, it) }
                if (power != null) break
            }
        }
        return MoveForm(
            id = entry.str("id") ?: "",
            name = entry.str("name") ?: entry.str("id") ?: "",
            description = entry.str("description") ?: "",
            type = entry.str("type")?.takeIf { it.isNotBlank() },
            category = entry.str("category") ?: "",
            power = power ?: RecipeUse("formula", mapOf("formula" to entry["power"])),
            accuracy = entry.dbl("accuracy"),
            priority = FormSupport.int(entry["priority"]) ?: 0,
            pp = FormSupport.int(entry["pp"]),
            cooldown = FormSupport.int(entry["cooldown"]),
            target = entry.str("target") ?: "enemy",
            hits = FormSupport.numOrFormula(entry["hits"]),
            hitAccuracy = entry.str("hitAccuracy"),
            critBonus = FormSupport.numOrFormula(entry["critBonus"]),
            ignoreDefense = FormSupport.numOrFormula(entry["ignoreDefense"]),
            charge = JsonTools.copy(entry["charge"]),
            flags = entry.strings("flags") ?: emptyList(),
            triggers = Triggers.readAll(triggers.drop(consumed), c),
        )
    }

    /** The JSON of [form]; with [base] (the entry it was read from) only the changed keys are rewritten. */
    fun build(form: MoveForm, ctx: RecipeContext, base: Map<String, Any?>? = null): MutableMap<String, Any?> {
        val c = ctx.copy(owner = OwnerKind.MOVE)
        val m = base?.let { JsonTools.copyMap(it) } ?: LinkedHashMap()
        val old = base?.let { read(it, c) }
        fun changed(f: (MoveForm) -> Any?) = old == null || f(old) != f(form)
        FormSupport.set(m, "id", form.id, ORDER)
        if (changed { it.name }) FormSupport.set(m, "name", form.name, ORDER)
        if (changed { it.description }) FormSupport.set(m, "description", form.description, ORDER, "")
        if (changed { it.type }) FormSupport.set(m, "type", form.type, ORDER)
        if (changed { it.category }) FormSupport.set(m, "category", form.category, ORDER)
        val powerChanged = changed { it.power }
        val part = (PowerRecipes.byId(form.power.recipe) ?: PowerRecipes.byId("formula")!!).build(form.power.values, c) as Map<*, *>
        if (powerChanged) FormSupport.set(m, "power", part["power"], ORDER)
        if (changed { it.accuracy }) FormSupport.set(m, "accuracy", form.accuracy, ORDER)
        if (changed { it.priority }) FormSupport.set(m, "priority", form.priority.toDouble(), ORDER, 0.0)
        if (changed { it.pp }) FormSupport.set(m, "pp", FormSupport.num(form.pp), ORDER)
        if (changed { it.cooldown }) FormSupport.set(m, "cooldown", FormSupport.num(form.cooldown), ORDER)
        if (changed { it.target }) FormSupport.set(m, "target", form.target, ORDER)
        if (changed { it.hits }) FormSupport.set(m, "hits", FormSupport.numOrFormula(form.hits), ORDER, 1.0)
        if (changed { it.hitAccuracy }) FormSupport.set(m, "hitAccuracy", form.hitAccuracy, ORDER)
        if (changed { it.critBonus }) FormSupport.set(m, "critBonus", FormSupport.numOrFormula(form.critBonus), ORDER)
        if (changed { it.ignoreDefense }) FormSupport.set(m, "ignoreDefense", FormSupport.numOrFormula(form.ignoreDefense), ORDER)
        if (changed { it.charge }) FormSupport.set(m, "charge", JsonTools.copy(form.charge), ORDER)
        if (changed { it.flags }) FormSupport.set(m, "flags", form.flags, ORDER, emptyList<String>())
        if (powerChanged || changed { it.triggers }) {
            val list = ArrayList<Any?>()
            (part["trigger"] as? Map<*, *>)?.let { list += JsonTools.copy(it) }
            list.addAll(Triggers.buildAll(form.triggers, c))
            FormSupport.set(m, "triggers", list, ORDER, emptyList<Any?>())
        }
        return m
    }
}

/**
 * A charge move's "charge" object as a form: it charges for [turns] turns first (the message is logged each time),
 * [invulnerable] = out of reach meanwhile, no charge when [skipIf] (a formula, e.g. "fieldActive('sun')") is true.
 */
data class ChargeForm(val turns: Int = 1, val message: String? = null, val invulnerable: Boolean = false, val skipIf: String? = null)

/** Reads and writes [ChargeForm]s (the move's "charge" object; unknown keys are kept). */
object ChargeForms {
    /** Canonical key order of "charge" (README). */
    val ORDER = listOf("turns", "message", "invulnerable", "skipIf")

    /** The message logged while charging when "message" is not set. */
    const val DEFAULT_MESSAGE = "{user} is charging up!"

    /** The form of a "charge" value, or null if the form cannot show it (a formula for the turns, unknown keys...). */
    fun read(charge: Any?): ChargeForm? {
        val m = charge as? Map<*, *> ?: return null
        if (!m.keys.all { it in ORDER }) return null
        val turns = m["turns"]
        if (turns != null && (turns !is Number || turns.toDouble() != Math.rint(turns.toDouble()) || turns.toDouble() < 1)) return null
        val message = m["message"]
        val invulnerable = m["invulnerable"]
        val skipIf = m["skipIf"]
        if ((message != null && message !is String) || (invulnerable != null && invulnerable !is Boolean) || (skipIf != null && skipIf !is String)) return null
        return ChargeForm(turns?.toInt() ?: 1, message, invulnerable == true, skipIf)
    }

    /** The "charge" object of [form]; with [base] (the value it was read from) only the changed keys are rewritten. */
    fun build(form: ChargeForm, base: Any?): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val m = (base as? Map<String, Any?>)?.let { JsonTools.copyMap(it) } ?: LinkedHashMap()
        val old = read(base) ?: ChargeForm()
        if (form.turns != old.turns || base == null) FormSupport.set(m, "turns", form.turns.toDouble(), ORDER, 1.0)
        if (form.message != old.message) FormSupport.set(m, "message", form.message, ORDER)
        if (form.invulnerable != old.invulnerable) FormSupport.set(m, "invulnerable", form.invulnerable, ORDER, false)
        if (form.skipIf != old.skipIf) FormSupport.set(m, "skipIf", form.skipIf, ORDER)
        return m
    }
}

// ----------------------------------------------------------------------------------------------------------------
// Statuses

/** A status (Statuses.json) as a form. */
data class StatusForm(
    val id: String = "",
    val name: String = "",
    val short: String? = null,
    val description: String = "",
    /** major / volatile / buff / debuff. */
    val kind: String = "debuff",
    /** Owner turns: a number, a formula ("randint(1, 3)", see [Durations]), or null = until removed. */
    val duration: Any? = null,
    /** ignore / refresh / stack. */
    val stacking: String = "ignore",
    val maxStacks: Int = 1,
    val immuneTypes: List<String> = emptyList(),
    /** Stat id -> multiplier (number or formula), see [StatMods]. */
    val statModifiers: Map<String, Any?> = emptyMap(),
    val triggers: List<TriggerForm> = emptyList(),
    val applyMessage: String? = null,
    val removeMessage: String? = null,
)

/** Reads and writes [StatusForm]s. */
object StatusForms {
    /** Canonical key order of a status (README). */
    val ORDER = listOf(
        "id", "name", "short", "description", "kind", "duration", "stacking", "maxStacks", "immuneTypes", "statModifiers",
        "triggers", "applyMessage", "removeMessage",
    )

    /** Status kinds in plain words. */
    val KINDS = listOf(
        Option("major", "major", "only one major status at a time (burn, sleep...); kept when switching out"),
        Option("volatile", "volatile", "temporary condition (confusion, flinch...)"),
        Option("buff", "buff", "a good effect"),
        Option("debuff", "debuff", "a bad effect"),
    )

    /** Stacking choices in plain words. */
    val STACKING = listOf(
        Option("ignore", "ignore", "applying it again does nothing"),
        Option("refresh", "refresh", "applying it again restarts its duration"),
        Option("stack", "stack", "applying it again adds a stack (up to the maximum) and restarts its duration"),
    )

    /** The form of a status entry. */
    fun read(entry: Map<String, Any?>, ctx: RecipeContext): StatusForm {
        val c = ctx.copy(owner = OwnerKind.STATUS, entryId = entry.str("id") ?: ctx.entryId)
        return StatusForm(
            id = entry.str("id") ?: "",
            name = entry.str("name") ?: entry.str("id") ?: "",
            short = entry.str("short"),
            description = entry.str("description") ?: "",
            kind = entry.str("kind") ?: "",
            duration = FormSupport.numOrFormula(entry["duration"]),
            stacking = entry.str("stacking") ?: "ignore",
            maxStacks = FormSupport.int(entry["maxStacks"]) ?: 1,
            immuneTypes = entry.strings("immuneTypes") ?: emptyList(),
            statModifiers = FormSupport.stringMap(entry["statModifiers"]).mapValues { FormSupport.numOrFormula(it.value) },
            triggers = Triggers.readAll(entry["triggers"], c),
            applyMessage = entry.str("applyMessage"),
            removeMessage = entry.str("removeMessage"),
        )
    }

    /** The JSON of [form]; with [base] (the entry it was read from) only the changed keys are rewritten. */
    fun build(form: StatusForm, ctx: RecipeContext, base: Map<String, Any?>? = null): MutableMap<String, Any?> {
        val c = ctx.copy(owner = OwnerKind.STATUS, entryId = form.id)
        val m = base?.let { JsonTools.copyMap(it) } ?: LinkedHashMap()
        val old = base?.let { read(it, c) }
        fun changed(f: (StatusForm) -> Any?) = old == null || f(old) != f(form)
        FormSupport.set(m, "id", form.id, ORDER)
        if (changed { it.name }) FormSupport.set(m, "name", form.name, ORDER)
        if (changed { it.short }) FormSupport.set(m, "short", form.short, ORDER)
        if (changed { it.description }) FormSupport.set(m, "description", form.description, ORDER, "")
        if (changed { it.kind }) FormSupport.set(m, "kind", form.kind, ORDER)
        if (changed { it.duration }) FormSupport.set(m, "duration", FormSupport.numOrFormula(form.duration), ORDER)
        if (changed { it.stacking }) FormSupport.set(m, "stacking", form.stacking, ORDER)
        if (changed { it.maxStacks }) FormSupport.set(m, "maxStacks", form.maxStacks.toDouble(), ORDER, 1.0)
        if (changed { it.immuneTypes }) FormSupport.set(m, "immuneTypes", form.immuneTypes, ORDER, emptyList<String>())
        if (changed { it.statModifiers }) FormSupport.set(m, "statModifiers", LinkedHashMap(form.statModifiers.mapValues { FormSupport.numOrFormula(it.value) }), ORDER, emptyMap<String, Any?>())
        if (changed { it.triggers }) FormSupport.set(m, "triggers", Triggers.buildAll(form.triggers, c), ORDER, emptyList<Any?>())
        if (changed { it.applyMessage }) FormSupport.set(m, "applyMessage", form.applyMessage, ORDER)
        if (changed { it.removeMessage }) FormSupport.set(m, "removeMessage", form.removeMessage, ORDER)
        return m
    }
}

// ----------------------------------------------------------------------------------------------------------------
// Abilities and items

/** An ability or an item (Abilities.json / Items.json) as a form. */
data class HolderForm(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    /** Stat id -> multiplier (number or formula), see [StatMods]. */
    val statModifiers: Map<String, Any?> = emptyMap(),
    val triggers: List<TriggerForm> = emptyList(),
)

/** Reads and writes [HolderForm]s (abilities and items). */
object HolderForms {
    /** Canonical key order of an ability or item (README). */
    val ORDER = listOf("id", "name", "description", "statModifiers", "triggers")

    /** [ctx] decides the owner: OwnerKind.ABILITY or OwnerKind.ITEM. */
    fun read(entry: Map<String, Any?>, ctx: RecipeContext): HolderForm = HolderForm(
        id = entry.str("id") ?: "",
        name = entry.str("name") ?: entry.str("id") ?: "",
        description = entry.str("description") ?: "",
        statModifiers = FormSupport.stringMap(entry["statModifiers"]).mapValues { FormSupport.numOrFormula(it.value) },
        triggers = Triggers.readAll(entry["triggers"], ctx),
    )

    /** The JSON of [form]; with [base] (the entry it was read from) only the changed keys are rewritten. */
    fun build(form: HolderForm, ctx: RecipeContext, base: Map<String, Any?>? = null): MutableMap<String, Any?> {
        val m = base?.let { JsonTools.copyMap(it) } ?: LinkedHashMap()
        val old = base?.let { read(it, ctx) }
        fun changed(f: (HolderForm) -> Any?) = old == null || f(old) != f(form)
        FormSupport.set(m, "id", form.id, ORDER)
        if (changed { it.name }) FormSupport.set(m, "name", form.name, ORDER)
        if (changed { it.description }) FormSupport.set(m, "description", form.description, ORDER, "")
        if (changed { it.statModifiers }) FormSupport.set(m, "statModifiers", LinkedHashMap(form.statModifiers.mapValues { FormSupport.numOrFormula(it.value) }), ORDER, emptyMap<String, Any?>())
        if (changed { it.triggers }) FormSupport.set(m, "triggers", Triggers.buildAll(form.triggers, ctx), ORDER, emptyList<Any?>())
        return m
    }
}

// ----------------------------------------------------------------------------------------------------------------
// Fields

/** A field (Fields.json) as a form. [rules] is the raw "rules" object (filled by the field presets). */
data class FieldForm(
    val id: String = "",
    val name: String = "",
    val short: String? = null,
    val description: String = "",
    /** global / side. */
    val scope: String = "global",
    val group: String? = null,
    val duration: Any? = null,
    val stacking: String = "ignore",
    val maxStacks: Int = 1,
    val selectable: Boolean = false,
    val statModifiers: Map<String, Any?> = emptyMap(),
    val triggers: List<TriggerForm> = emptyList(),
    val rules: Map<String, Any?> = emptyMap(),
    val startMessage: String? = null,
    val endMessage: String? = null,
)

/** Reads and writes [FieldForm]s. */
object FieldForms {
    /** Canonical key order of a field (README). */
    val ORDER = listOf(
        "id", "name", "short", "description", "scope", "group", "duration", "stacking", "maxStacks", "selectable",
        "statModifiers", "triggers", "rules", "startMessage", "endMessage",
    )

    /** The form of a field entry. */
    fun read(entry: Map<String, Any?>, ctx: RecipeContext): FieldForm {
        val c = ctx.copy(owner = OwnerKind.FIELD, sideField = entry.str("scope").equals("side", true), entryId = entry.str("id") ?: "")
        return FieldForm(
            id = entry.str("id") ?: "",
            name = entry.str("name") ?: entry.str("id") ?: "",
            short = entry.str("short"),
            description = entry.str("description") ?: "",
            scope = entry.str("scope") ?: "global",
            group = entry.str("group"),
            duration = FormSupport.numOrFormula(entry["duration"]),
            stacking = entry.str("stacking") ?: "ignore",
            maxStacks = FormSupport.int(entry["maxStacks"]) ?: 1,
            selectable = entry["selectable"] == true,
            statModifiers = FormSupport.stringMap(entry["statModifiers"]).mapValues { FormSupport.numOrFormula(it.value) },
            triggers = Triggers.readAll(entry["triggers"], c),
            rules = FormSupport.stringMap(entry["rules"]),
            startMessage = entry.str("startMessage"),
            endMessage = entry.str("endMessage"),
        )
    }

    /** The JSON of [form]; with [base] (the entry it was read from) only the changed keys are rewritten. */
    fun build(form: FieldForm, ctx: RecipeContext, base: Map<String, Any?>? = null): MutableMap<String, Any?> {
        val c = ctx.copy(owner = OwnerKind.FIELD, sideField = form.scope == "side", entryId = form.id)
        val m = base?.let { JsonTools.copyMap(it) } ?: LinkedHashMap()
        val old = base?.let { read(it, c) }
        fun changed(f: (FieldForm) -> Any?) = old == null || f(old) != f(form)
        FormSupport.set(m, "id", form.id, ORDER)
        if (changed { it.name }) FormSupport.set(m, "name", form.name, ORDER)
        if (changed { it.short }) FormSupport.set(m, "short", form.short, ORDER)
        if (changed { it.description }) FormSupport.set(m, "description", form.description, ORDER, "")
        if (changed { it.scope }) FormSupport.set(m, "scope", form.scope, ORDER, "global")
        if (changed { it.group }) FormSupport.set(m, "group", form.group, ORDER)
        if (changed { it.duration }) FormSupport.set(m, "duration", FormSupport.numOrFormula(form.duration), ORDER)
        if (changed { it.stacking }) FormSupport.set(m, "stacking", form.stacking, ORDER, "ignore")
        if (changed { it.maxStacks }) FormSupport.set(m, "maxStacks", form.maxStacks.toDouble(), ORDER, 1.0)
        if (changed { it.selectable }) FormSupport.set(m, "selectable", form.selectable, ORDER, false)
        if (changed { it.statModifiers }) FormSupport.set(m, "statModifiers", LinkedHashMap(form.statModifiers.mapValues { FormSupport.numOrFormula(it.value) }), ORDER, emptyMap<String, Any?>())
        if (changed { it.triggers }) FormSupport.set(m, "triggers", Triggers.buildAll(form.triggers, c), ORDER, emptyList<Any?>())
        if (changed { it.rules }) FormSupport.set(m, "rules", JsonTools.copy(form.rules), ORDER, emptyMap<String, Any?>())
        if (changed { it.startMessage }) FormSupport.set(m, "startMessage", form.startMessage, ORDER)
        if (changed { it.endMessage }) FormSupport.set(m, "endMessage", form.endMessage, ORDER)
        return m
    }
}

// ----------------------------------------------------------------------------------------------------------------
// Bakemons

/** A bakemon species (Bakemons.json) as a form. */
data class BakemonForm(
    val id: String = "",
    val name: String = "",
    val types: List<String> = emptyList(),
    /** One base value per stat of the rules. */
    val baseStats: Map<String, Double> = emptyMap(),
    /** Abilities it may have (the first is the default). */
    val abilities: List<String> = emptyList(),
    /** Moves it can learn. */
    val moves: List<String> = emptyList(),
    /** Image path relative to the setup folder (missing images are fine); null = none. */
    val image: String? = null,
    val description: String = "",
)

/** Reads and writes [BakemonForm]s. */
object BakemonForms {
    /** Canonical key order of a species (README). */
    val ORDER = listOf("id", "name", "types", "baseStats", "abilities", "moves", "image", "description")

    /** A new form: every stat of the rules at [base], image Assets/<id>.png. */
    fun blank(draft: SetupDraft, name: String, base: Double = 50.0): BakemonForm {
        val id = draft.idFromName(EntryKind.BAKEMON, name)
        val stats = RecipeContext.of(draft, OwnerKind.ABILITY).stats.associate { it.value to base }
        return BakemonForm(id = id, name = name, baseStats = stats, image = "Assets/$id.png")
    }

    /** The form of a Bakemons.json entry. */
    fun read(entry: Map<String, Any?>): BakemonForm = BakemonForm(
        id = entry.str("id") ?: "",
        name = entry.str("name") ?: entry.str("id") ?: "",
        types = entry.strings("types") ?: emptyList(),
        baseStats = FormSupport.stringMap(entry["baseStats"]).mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }.toMap(),
        abilities = entry.strings("abilities") ?: emptyList(),
        moves = entry.strings("moves") ?: emptyList(),
        image = entry.str("image"),
        description = entry.str("description") ?: "",
    )

    /** The JSON of [form]; with [base] (the entry it was read from) only the changed keys are rewritten. */
    fun build(form: BakemonForm, base: Map<String, Any?>? = null): MutableMap<String, Any?> {
        val m = base?.let { JsonTools.copyMap(it) } ?: LinkedHashMap()
        val old = base?.let { read(it) }
        fun changed(f: (BakemonForm) -> Any?) = old == null || f(old) != f(form)
        FormSupport.set(m, "id", form.id, ORDER)
        if (changed { it.name }) FormSupport.set(m, "name", form.name, ORDER)
        if (changed { it.types }) FormSupport.set(m, "types", form.types, ORDER)
        if (changed { it.baseStats }) FormSupport.set(m, "baseStats", LinkedHashMap(form.baseStats), ORDER)
        if (changed { it.abilities }) FormSupport.set(m, "abilities", form.abilities, ORDER, emptyList<String>())
        if (changed { it.moves }) FormSupport.set(m, "moves", form.moves, ORDER)
        if (changed { it.image }) FormSupport.set(m, "image", form.image, ORDER)
        if (changed { it.description }) FormSupport.set(m, "description", form.description, ORDER, "")
        return m
    }

    /** Moves of [draft] whose type is one of [types] (the "all moves of its types" shortcut). */
    fun movesOfTypes(draft: SetupDraft, types: List<String>): List<String> =
        draft.entries(EntryKind.MOVE).filter { it.str("type") in types }.mapNotNull { it.str("id") }

    /** Moves of [draft] without a type (the "all moves without a type" shortcut). */
    fun typelessMoves(draft: SetupDraft): List<String> =
        draft.entries(EntryKind.MOVE).filter { it.str("type").isNullOrBlank() }.mapNotNull { it.str("id") }
}
