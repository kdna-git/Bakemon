package creator

/**
 * A trigger made of recipes: WHEN [event] (fields: for [audience]), IF every [conditions] part holds and the [chance]
 * roll succeeds (at most [limit] times per battle, 0 = unlimited), DO [effects] (and log [message]).
 */
data class TriggerSpec(
    val event: String,
    val conditions: List<RecipeUse> = emptyList(),
    /** Percent (number), a formula string, or null = always. */
    val chance: Any? = null,
    val limit: Int = 0,
    val message: String? = null,
    val effects: List<RecipeUse> = emptyList(),
    /** Field triggers only: "covered" (null = default), "opponents" or "all". */
    val audience: String? = null,
) {
    /** True if a condition or an effect is an "advanced" part (a raw formula or raw effect JSON). */
    val usesAdvanced: Boolean get() = conditions.any { it.recipe == "advanced" } || effects.any { it.recipe == "advanced" }
}

/** A trigger as a form shows it: built from recipes, or custom (kept as raw JSON, shown read-only). */
sealed class TriggerForm {
    /** [original] is the JSON it was read from; it is written back as is while the spec still builds the same thing. */
    data class Guided(val spec: TriggerSpec, val original: Map<String, Any?>? = null) : TriggerForm()

    /** A trigger the recipes cannot show: edit it as raw JSON or leave it untouched. */
    data class Custom(val json: Any?) : TriggerForm()
}

/** Builds, reads and describes triggers (README "Triggers"). */
object Triggers {
    private val KEY_ORDER = listOf("on", "for", "condition", "chance", "message", "limit", "effects")

    /** The JSON of [spec]; [ctx] is the owner's context (its event is replaced by the spec's). */
    fun build(spec: TriggerSpec, ctx: RecipeContext): MutableMap<String, Any?> {
        val c = ctx.withEvent(spec.event)
        val m = LinkedHashMap<String, Any?>()
        m["on"] = spec.event
        if (spec.audience != null) m["for"] = spec.audience
        ConditionRecipes.join(spec.conditions, c)?.let { m["condition"] = it }
        if (spec.chance != null) m["chance"] = (spec.chance as? Number)?.toDouble() ?: spec.chance
        if (spec.message != null) m["message"] = spec.message
        if (spec.limit != 0) m["limit"] = spec.limit.toDouble()
        m["effects"] = spec.effects.map { use -> (EffectRecipes.byId(use.recipe) ?: EffectRecipes.all.last()).build(use.values, c) }
        return m
    }

    /** [json] as a form: [TriggerForm.Guided] when the recipes rebuild it exactly, else [TriggerForm.Custom]. */
    fun read(json: Any?, ctx: RecipeContext): TriggerForm {
        @Suppress("UNCHECKED_CAST")
        val m = json as? Map<String, Any?> ?: return TriggerForm.Custom(JsonTools.copy(json))
        val custom = TriggerForm.Custom(JsonTools.copy(m))
        val allowed = if (ctx.owner == OwnerKind.FIELD) KEY_ORDER.toSet() else KEY_ORDER.toSet() - "for"
        if (!m.keys.all { it in allowed }) return custom
        val on = m["on"] as? String ?: return custom
        if (Events.forOwner(ctx.owner).none { it.value == on }) return custom
        val c = ctx.withEvent(on)
        val conditionValue = m["condition"]
        if (conditionValue != null && conditionValue !is String) return custom
        val chance = m["chance"]
        if (chance != null && chance !is Number && chance !is String) return custom
        val limitValue = m["limit"]
        if (limitValue != null && (limitValue !is Number || limitValue.toDouble() != Math.rint(limitValue.toDouble()))) return custom
        val message = m["message"]
        if (message != null && message !is String) return custom
        val audience = m["for"]
        if (audience != null && audience !is String) return custom
        val effects = m["effects"] as? List<*> ?: return custom
        val spec = TriggerSpec(
            event = on,
            conditions = conditionValue?.let { ConditionRecipes.split(it, c) } ?: emptyList(),
            chance = (chance as? Number)?.toDouble() ?: chance,
            limit = limitValue?.toInt() ?: 0,
            message = message,
            effects = effects.map { EffectRecipes.recognise(it, c) },
            audience = audience,
        )
        return if (JsonTools.equal(build(spec, ctx), m, ordered = false)) TriggerForm.Guided(spec, JsonTools.copyMap(m)) else custom
    }

    /** Reads a "triggers" array. */
    fun readAll(list: Any?, ctx: RecipeContext): List<TriggerForm> = (list as? List<*>).orEmpty().map { read(it, ctx) }

    /** Builds a "triggers" array (unchanged guided triggers and custom ones are written back as they were). */
    fun buildAll(forms: List<TriggerForm>, ctx: RecipeContext): MutableList<Any?> = forms.mapTo(ArrayList()) { form ->
        when (form) {
            is TriggerForm.Custom -> JsonTools.copy(form.json)
            is TriggerForm.Guided -> {
                val built = build(form.spec, ctx)
                val original = form.original
                if (original != null && JsonTools.equal(built, original, ordered = false)) JsonTools.copy(original) else built
            }
        }
    }

    /** Problems of [spec]: unknown event or recipes, effects that do not fit the event, bad values. */
    fun problems(spec: TriggerSpec, ctx: RecipeContext): List<String> {
        val c = ctx.withEvent(spec.event)
        val out = mutableListOf<String>()
        if (Events.forOwner(ctx.owner).none { it.value == spec.event }) out += "'${spec.event}' is not an event a ${ctx.owner.label} can use."
        if (spec.effects.isEmpty()) out += "The trigger does nothing (add at least one effect)."
        if (spec.limit < 0) out += "The limit must be 0 (unlimited) or more."
        if (spec.audience != null && Events.audiences(ctx.sideField).none { it.value == spec.audience }) out += "Unknown audience '${spec.audience}'."
        if (spec.chance is String) c.formulaProblems(spec.chance, bakemon.FormulaScope.TRIGGER, recipient = false).forEach { out += "Chance: $it" }
        for (use in spec.conditions) {
            val r = ConditionRecipes.byId(use.recipe)
            if (r == null) out += "Unknown condition '${use.recipe}'." else out += r.problems(use.values, c)
        }
        for (use in spec.effects) {
            val r = EffectRecipes.byId(use.recipe)
            when {
                r == null -> out += "Unknown effect '${use.recipe}'."
                !r.available(c) -> out += "\"${r.label}\" does not work ${Events.label(spec.event, ctx.owner)}."
                else -> out += r.problems(use.values, c)
            }
        }
        return out
    }

    /** One line in plain words, e.g. "When it hits, 30% chance: Apply a status (status: burn)". */
    fun describe(form: TriggerForm, ctx: RecipeContext): String = when (form) {
        is TriggerForm.Custom -> "Custom trigger: " + util.Json.stringify(form.json, pretty = false)
        is TriggerForm.Guided -> {
            val s = form.spec
            val c = ctx.withEvent(s.event)
            val parts = mutableListOf(Events.whenText(s, ctx.owner).replaceFirstChar { it.uppercase() })
            val drop = Events.hpDrop(s)
            val conditions = s.conditions.filter { it !== drop }
            if (s.audience != null && s.audience != "covered") parts[0] += " (for ${s.audience})"
            if (conditions.isNotEmpty()) parts += "if " + conditions.joinToString(" and ") { describe(it, ConditionRecipes.byId(it.recipe), c) }
            if (s.chance != null) parts += "${if (s.chance is Number) JsonTools.num(s.chance.toDouble()) + "%" else s.chance} chance"
            if (s.limit == 1) parts += "once per battle" else if (s.limit > 1) parts += "at most ${s.limit} times per battle"
            parts.joinToString(", ") + ": " + s.effects.joinToString("; ") { describe(it, EffectRecipes.byId(it.recipe), c) }
        }
    }

    /** "Label (param: value, ...)" for a recipe use. */
    fun describe(use: RecipeUse, recipe: Recipe?, ctx: RecipeContext): String {
        if (recipe == null) return use.recipe
        val params = recipe.params(ctx)
        val shown = params.mapNotNull { p ->
            val v = use.values[p.id] ?: return@mapNotNull null
            if (v == false) return@mapNotNull null
            val text = when {
                v == true -> "yes"
                v is Number -> JsonTools.num(v.toDouble()) + if ((p.type as? ParamType.Number)?.percent == true) "%" else ""
                p.type is ParamType.Choice -> p.type.options.firstOrNull { it.value == v }?.label ?: v.toString()
                p.type is ParamType.Effects -> "[" + (v as? List<*>).orEmpty().filterIsInstance<RecipeUse>().joinToString("; ") { describe(it, EffectRecipes.byId(it.recipe), ctx) } + "]"
                v is Map<*, *> || v is List<*> -> util.Json.stringify(v, pretty = false)
                else -> v.toString()
            }
            "${p.label.lowercase()}: $text"
        }
        return lowerFirst(recipe.label) + if (shown.isEmpty()) "" else " (${shown.joinToString(", ")})"
    }

    /** "Apply a status" -> "apply a status", but "HP below a %" stays as it is (an abbreviation). */
    internal fun lowerFirst(text: String): String =
        if (text.length > 1 && text[1].isUpperCase()) text else text.replaceFirstChar { it.lowercase() }
}
