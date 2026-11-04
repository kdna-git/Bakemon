package creator

import bakemon.FormulaScope

/** Trigger events ("WHEN") in plain words, per owner kind (README "Events"). */
object Events {
    /** Events that may involve a move (move conditions make sense there). */
    val MOVE_EVENTS = setOf(
        "beforeMove", "afterMove", "hit", "missed", "modifyDamageDealt", "modifyDamageTaken", "dealtDamage", "damaged", "fainted", "knockedOut",
        "targeted", "attacked", "allyDamaged", "enemyDamaged", "allyUsedMove", "enemyUsedMove",
    )

    /** Events that set `damage`. */
    val DAMAGE_EVENTS = setOf("hit", "modifyDamageDealt", "modifyDamageTaken", "dealtDamage", "damaged", "attacked", "allyDamaged", "enemyDamaged")

    /** Events where `multiplyDamage` works. */
    val DAMAGE_MODIFIERS = setOf("modifyDamageDealt", "modifyDamageTaken")

    /** Events that set `isCrit` and `effectiveness` (and `hitIndex` / `hitCount`). */
    val HIT_EVENTS = setOf("hit", "modifyDamageDealt", "modifyDamageTaken")

    /** Events without an "other" monster (README "Events"). */
    val NO_OTHER = setOf("battleStart", "switchIn", "switchOut", "turnStart", "turnEnd", "statusRemoved")

    /** Events whose other monster is using a move ("the target of that move" = otherTarget exists). */
    val OTHER_USES_MOVE = setOf("targeted", "attacked", "allyUsedMove", "enemyUsedMove")

    /** Events whose other monster is the one that attacked the owner (a counterattack hits it). */
    val ATTACKER_EVENTS = setOf("targeted", "attacked", "damaged")

    /**
     * Not a real event: the WHEN choice "when its HP drops below X%", made of "damaged" + the condition
     * "hp_dropped_below" (+ once per battle). See [whenChoices], [hpDrop], [withHpDrop].
     */
    const val HP_DROP = "hpDropsBelow"

    private val MOVE = listOf(
        Option("hit", "when it hits", "once per hit on each target that was not missed (also for moves that deal no damage)"),
        Option("missed", "when it misses", "for each target it missed"),
        Option("beforeMove", "before it is used", "can cancel the move (\"cancel the action\")"),
        Option("afterMove", "after it was used", "once, after every target"),
        Option("modifyDamageDealt", "when it is about to deal damage", "can multiply the damage (\"multiply the damage\")"),
        Option("knockedOut", "when it knocks out a target", "the target fainted because of it"),
    )

    private val MONSTER = listOf(
        Option("switchIn", "when it enters the battle", "also at the start of the battle"),
        Option("battleStart", "at the start of the battle", "once, for every monster on the field"),
        Option("turnStart", "at the start of its turn", ""),
        Option("turnEnd", "at the end of its turn", "e.g. damage over time, healing"),
        Option("beforeMove", "before it uses a move", "can cancel the move (\"cancel the action\")"),
        Option("afterMove", "after it used a move", ""),
        Option("hit", "when its move hits", "once per hit"),
        Option("missed", "when its move misses", ""),
        Option("modifyDamageDealt", "when it is about to deal damage", "can multiply the damage it deals with a move"),
        Option("modifyDamageTaken", "when it is about to take damage", "can multiply the damage it takes from a move"),
        Option("dealtDamage", "when it deals damage", "after it damaged another monster (move or effect)"),
        Option("damaged", "when it takes damage", "from any source; the other monster is the attacker, if any"),
        Option("targeted", "when it is targeted by a move", "another monster aims a move at it (enemy or ally), before the accuracy check; the other monster is the attacker"),
        Option("attacked", "when it has been attacked", "after another monster's move aimed at it, hit or miss (condition \"the attack landed\"); the other monster is the attacker"),
        Option("healed", "when it is healed", ""),
        Option("statusApplied", "when a status is applied to it", "the variable 'status' is the status id"),
        Option("statusInflicted", "when it applies a status to someone", "it gave a status to another monster (the other monster)"),
        Option("statusRemoved", "when one of its statuses ends", ""),
        Option("fainted", "when it faints", "e.g. revive on death"),
        Option("knockedOut", "when it knocks out a monster", ""),
        Option("allyDamaged", "when an ally takes damage", "the other monster is the damaged ally"),
        Option("enemyDamaged", "when an enemy takes damage", "the other monster is the damaged enemy"),
        Option("allyUsedMove", "when an ally uses a move", "after it; the other monster is the ally, \"the target of that move\" its target"),
        Option("enemyUsedMove", "when an enemy uses a move", "after it; the other monster is the enemy"),
        Option("allyFainted", "when an ally faints", ""),
        Option("enemyFainted", "when an enemy faints", ""),
        Option("revived", "when it is revived", ""),
        Option("allyRevived", "when an ally is revived", ""),
        Option("enemyRevived", "when an enemy is revived", ""),
        Option("fieldSet", "when a field starts", "the variable 'field' is the field id"),
        Option("fieldRemoved", "when a field ends", ""),
        Option("switchOut", "when it is switched out", ""),
    )

    /** A monster event label for a field's triggers (the owner is the covered monster). */
    private fun forField(label: String): String = label.replace("its ", "a covered monster's ").replace("it ", "a covered monster ")
        .replace(" it", " a covered monster").replace("an ally ", "an ally of a covered monster ").replace("an enemy ", "an enemy of a covered monster ")

    /** The events a trigger of [owner] can use, in plain words. */
    fun forOwner(owner: OwnerKind): List<Option> = when (owner) {
        OwnerKind.MOVE -> MOVE
        OwnerKind.FIELD -> MONSTER.map { o -> o.copy(label = forField(o.label)) }
        else -> MONSTER
    }

    /** The WHEN choices of [owner]: its events plus "when its HP drops below X%" ([HP_DROP]) after "when it takes damage". */
    fun whenChoices(owner: OwnerKind): List<Option> {
        val events = forOwner(owner)
        val at = events.indexOfFirst { it.value == "damaged" }
        if (at < 0) return events
        val label = "when its HP drops below a %"
        val drop = Option(HP_DROP, if (owner == OwnerKind.FIELD) forField(label) else label, "the moment it falls under the line (once per battle unless you change \"How often\")")
        return events.take(at + 1) + drop + events.drop(at + 1)
    }

    /** The "its HP just dropped below X%" condition that makes [spec] a "when its HP drops below X%" trigger, or null. */
    fun hpDrop(spec: TriggerSpec): RecipeUse? =
        if (spec.event == "damaged") spec.conditions.firstOrNull { it.recipe == "hp_dropped_below" } else null

    /** [spec] as "when its HP drops below [percent]%": event damaged, that condition first, once per battle if it was unlimited. */
    fun withHpDrop(spec: TriggerSpec, percent: Double): TriggerSpec {
        val was = hpDrop(spec) != null
        val others = spec.conditions.filter { it.recipe != "hp_dropped_below" }
        return spec.copy(
            event = "damaged",
            conditions = listOf(RecipeUse("hp_dropped_below", mapOf("percent" to percent))) + others,
            limit = if (!was && spec.limit == 0) 1 else spec.limit,
        )
    }

    /** WHEN in plain words: the event's label, or "when its HP drops below X%". */
    fun whenText(spec: TriggerSpec, owner: OwnerKind): String {
        val drop = hpDrop(spec) ?: return label(spec.event, owner)
        val percent = (drop.values["percent"] as? Number)?.toDouble()?.let { JsonTools.num(it) + "%" } ?: "a %"
        val text = "when its HP drops below $percent"
        return if (owner == OwnerKind.FIELD) forField(text) else text
    }

    /** Plain words for [event] of [owner] (the id itself if unknown). */
    fun label(event: String, owner: OwnerKind): String = forOwner(owner).firstOrNull { it.value == event }?.label ?: event

    /** Whose events a field trigger listens to ("for"). */
    fun audiences(sideField: Boolean): List<Option> = listOfNotNull(
        Option("covered", "a monster the field covers", "default"),
        if (sideField) Option("opponents", "a monster of the other side", "e.g. \"when an enemy takes damage\"") else null,
        Option("all", "any monster", ""),
    )
}

/** Builds and reads effect objects. */
private object E {
    @Suppress("UNCHECKED_CAST")
    fun read(json: Any?, type: String, allowed: Set<String>): Map<String, Any?>? {
        val m = json as? Map<String, Any?> ?: return null
        if (m["type"] != type) return null
        if (!m.keys.all { it == "type" || it in allowed }) return null
        return m
    }

    fun make(type: String, v: Values, withTarget: Boolean, vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("type" to type)
        if (withTarget && v.has("target")) m["target"] = v.str("target")
        for ((k, x) in pairs) if (x != null) m[k] = x
        return m
    }

    /** The values map of the given keys of [m] that are present (target, chance...). */
    fun pick(m: Map<String, Any?>, vararg keys: String): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (k in keys) if (m[k] != null) out[k] = m[k]
        return out
    }

    /** The monster reference a formula uses for the effect target ([target] value or the owner default). */
    fun ref(v: Values, ctx: RecipeContext): String = v.strOrNull("target") ?: ctx.owner.defaultTarget
    fun ref(m: Map<String, Any?>, ctx: RecipeContext): String = (m["target"] as? String) ?: ctx.owner.defaultTarget

    private val NUM = "(-?\\d+(?:\\.\\d+)?)"
    private const val REF = "(self|other|user|target|recipient)"
    val PERCENT_OF_STAT = Regex("^(self|other|user|target)\\.(\\w+) \\* $NUM$")
    val PERCENT_OF_DAMAGE = Regex("^damage \\* $NUM$")
    private val HP_SHARE = Regex("^$REF\\.(maxHp|hp) \\* $NUM$")
    private val MISSING_SHARE = Regex("^\\($REF\\.maxHp - $REF\\.hp\\) \\* $NUM$")

    fun percentOf(fraction: String): Double = JsonTools.round(fraction.toDouble() * 100)

    /**
     * The monster reference of "each target's own HP": the target itself when it is one monster (self, other, user,
     * target: "target.maxHp * 0.25" as before), else `recipient` (evaluated for each monster of a group).
     */
    fun own(target: String): String = if (target in P.REFS) target else "recipient"

    /** "<ref>.maxHp * 0.25", "<ref>.hp * 0.25" or "(<ref>.maxHp - <ref>.hp) * 0.25" for [base] maxHp / hp / missing. */
    fun share(ref: String, base: String, percent: Double): String = when (base) {
        "missing" -> "($ref.maxHp - $ref.hp) * ${JsonTools.fraction(percent)}"
        else -> "$ref.$base * ${JsonTools.fraction(percent)}"
    }

    /** (base, percent) of a [share] formula of [ref], or null. */
    fun readShare(amount: Any?, ref: String): Pair<String, Double>? {
        val text = amount as? String ?: return null
        HP_SHARE.matchEntire(text)?.let { m ->
            return if (m.groupValues[1] == ref) m.groupValues[2] to percentOf(m.groupValues[3]) else null
        }
        MISSING_SHARE.matchEntire(text)?.let { m ->
            return if (m.groupValues[1] == ref && m.groupValues[2] == ref) "missing" to percentOf(m.groupValues[3]) else null
        }
        return null
    }

    val MAX_HP = Option("maxHp", "its max HP", "each monster's own max HP")
    val CURRENT_HP = Option("hp", "its current HP", "the HP each monster has left now")
    val MISSING_HP = Option("missing", "the HP it has lost", "each monster's max HP minus its current HP")
}

/** The effect ("DO") recipes. */
object EffectRecipes {
    private val damageEvents: (RecipeContext) -> Boolean = { it.hasDamage && it.event != null && it.event !in Events.DAMAGE_MODIFIERS }

    /** Effects a delayed effect does not offer (nothing is pending when it runs). */
    private val NOT_DELAYED = setOf("multiply_damage", "cancel_action", "delay")

    private fun message(help: String) = P.text("message", "Message", help)

    private fun power(help: String) =
        Param("power", "Power %", help, ParamType.Number(0.0, null, percent = true, formula = true), 100.0, optional = true)

    /**
     * "% of its HP" recipes (damage, heal, shield, setHp, revive): a share of [bases] (max HP, current HP, lost HP) of
     * each monster the effect reaches, each its own: "target.maxHp * 0.25" for one monster, "recipient.maxHp * 0.25"
     * for a group (README "recipient").
     */
    private fun hpShare(
        id: String, type: String, label: String, help: String, bases: List<Option>, default: Double, group: String,
        min: Double = 0.0, max: Double? = 100.0,
        targets: (RecipeContext) -> List<Option> = P::livingTargets,
        withMessage: Boolean = false,
    ) = Recipe(
        id, label, help,
        { ctx ->
            listOfNotNull(
                P.percent("percent", "Percent", "25 = a quarter", default, min = min, max = max),
                if (bases.size > 1) P.choice("base", "Of", "which HP the percent is taken from (each monster its own)", bases) else null,
                P.targetParam(ctx, targets(ctx)),
                if (withMessage) message("replaces the revive line; empty text = silent") else null,
            )
        },
        { v, ctx ->
            val base = if (bases.size > 1) v.str("base") else bases[0].value
            E.make(type, v, true, "amount" to E.share(E.own(E.ref(v, ctx)), base, v.num("percent")), "message" to if (withMessage) v.map["message"]?.toString() else null)
        },
        { j, ctx ->
            val m = E.read(j, type, if (withMessage) setOf("target", "amount", "message") else setOf("target", "amount")) ?: return@Recipe null
            val (base, percent) = E.readShare(m["amount"], E.own(E.ref(m, ctx))) ?: return@Recipe null
            if (bases.none { it.value == base }) return@Recipe null
            E.pick(m, "target", "message").apply {
                put("percent", percent)
                if (bases.size > 1) put("base", base)
            }
        },
        group = group,
    )

    /** "Use a move" presets: a useMove effect with fixed [fixed] keys plus the optional power and message. */
    private fun useMovePreset(id: String, label: String, help: String, fixed: Map<String, String>, available: (RecipeContext) -> Boolean, powerHelp: String) = Recipe(
        id, label, help,
        { _ -> listOf(power(powerHelp), message("shown just before the move; empty = none")) },
        { v, _ ->
            val m = linkedMapOf<String, Any?>("type" to "useMove")
            m.putAll(fixed)
            if (v.has("power")) m["power"] = v.numOrFormula("power")
            if (v.has("message")) m["message"] = v.str("message")
            m
        },
        { j, _ ->
            val m = E.read(j, "useMove", fixed.keys + setOf("power", "message")) ?: return@Recipe null
            if (fixed.any { (k, value) -> m[k] != value }) return@Recipe null
            if (m["message"] != null && m["message"] !is String) return@Recipe null
            E.pick(m, "power", "message")
        },
        available, RecipeGroups.MOVES,
    )

    /** "Steal / copy / pass on statuses": a transferStatus effect. */
    private fun transfer(id: String, label: String, help: String, mode: String?, pass: Boolean) = Recipe(
        id, label, help,
        { ctx ->
            val which = listOf(
                Option("kind:buff", "buffs"), Option("kind:debuff", "debuffs"), Option("kind:major", "the major status"),
                Option("kind:volatile", "volatile statuses"), Option("all", "any status"),
            ) + ctx.statuses
            listOf(
                P.choice("which", "Which statuses", "the kind of status, or one status", which, if (pass) "kind:debuff" else "kind:buff"),
                P.number("count", "How many", "the oldest first; empty = 1, 0 = all of them", null, min = 0.0, integer = true, optional = true),
                if (pass) Param("to", "To whom", "who receives them", ParamType.Choice(P.livingTargets(ctx).filter { it.value != P.ownRef(ctx) }), P.foeStart(ctx) ?: "target")
                else P.targetParam(ctx, P.livingTargets(ctx), P.foeStart(ctx), "From whom", "who loses (or keeps) them"),
                if (pass) null else Param("to", "To whom", "who receives them; empty = itself" + if (ctx.owner == OwnerKind.MOVE) ", the user" else "",
                    ParamType.Choice(P.receivers(ctx)), null, optional = true),
            ).filterNotNull()
        },
        { v, ctx ->
            val which = v.str("which")
            val m = linkedMapOf<String, Any?>("type" to "transferStatus")
            if (pass) m["target"] = P.ownRef(ctx) else if (v.has("target")) m["target"] = v.str("target")
            if (pass || v.has("to")) m["to"] = v.str("to")
            if (which.startsWith("kind:")) m["kind"] = which.removePrefix("kind:") else m["status"] = which
            if (v.has("count")) m["count"] = v.numOrFormula("count")
            if (mode != null) m["mode"] = mode
            m
        },
        { j, ctx ->
            val m = E.read(j, "transferStatus", setOf("target", "to", "status", "kind", "count", "mode")) ?: return@Recipe null
            if (m["mode"] != mode) return@Recipe null
            val status = m["status"] as? String
            val kind = m["kind"] as? String
            if ((status == null) == (kind == null)) return@Recipe null
            if (pass && (m["target"] != P.ownRef(ctx) || m["to"] !is String)) return@Recipe null
            if (m["to"] != null && m["to"] !is String) return@Recipe null
            E.pick(m, "count", "to").apply {
                if (!pass) m["target"]?.let { put("target", it) }
                put("which", status ?: "kind:$kind")
            }
        },
        { true }, RecipeGroups.STATUS,
    )

    /** Every effect recipe; [recognise] tries them in this order (specific ones first, "advanced" last). */
    val all: List<Recipe> = listOf(
        // ---- Damage ----
        hpShare(
            "damage_max_hp", "damage", "Deal damage: % of its HP",
            "A share of the max HP (or current HP) of each monster it affects, each its own (ignores types and the damage formula).",
            listOf(E.MAX_HP, E.CURRENT_HP), 25.0, RecipeGroups.DAMAGE,
        ),
        Recipe(
            "recoil", "Deal damage: % of the damage just dealt", "Recoil-like: a share of the damage of this event (e.g. 25% to the user).",
            { ctx -> listOf(P.percent("percent", "Percent of the damage", "25 = a quarter of the damage", 25.0), P.targetParam(ctx, default = if (ctx.owner == OwnerKind.MOVE) "user" else "self")) },
            { v, _ -> E.make("damage", v, true, "amount" to "damage * ${JsonTools.fraction(v.num("percent"))}") },
            { j, _ ->
                val m = E.read(j, "damage", setOf("target", "amount")) ?: return@Recipe null
                val match = E.PERCENT_OF_DAMAGE.matchEntire(m["amount"] as? String ?: "") ?: return@Recipe null
                E.pick(m, "target").apply { put("percent", E.percentOf(match.groupValues[1])) }
            },
            damageEvents, RecipeGroups.DAMAGE,
        ),
        Recipe(
            "damage_stat", "Deal damage: % of a stat", "Damage equal to a share of a monster's stat (e.g. 50% of the user's Attack).",
            { ctx ->
                listOf(
                    P.choice("who", "Whose stat", "the monster whose stat is used", P.whoOptions(ctx)),
                    P.choice("stat", "Stat", "the stat (for HP use \"Deal damage: % of its HP\")", ctx.stats, ctx.stageStats.firstOrNull()?.value ?: ctx.stats.firstOrNull()?.value),
                    P.percent("percent", "Percent of the stat", "50 = half of it", 50.0, max = null),
                    P.targetParam(ctx),
                )
            },
            { v, _ -> E.make("damage", v, true, "amount" to "${v.str("who")}.${v.str("stat")} * ${JsonTools.fraction(v.num("percent"))}") },
            { j, ctx ->
                val m = E.read(j, "damage", setOf("target", "amount")) ?: return@Recipe null
                val match = E.PERCENT_OF_STAT.matchEntire(m["amount"] as? String ?: "") ?: return@Recipe null
                if (ctx.stats.none { it.value == match.groupValues[2] }) return@Recipe null
                E.pick(m, "target").apply {
                    put("who", match.groupValues[1]); put("stat", match.groupValues[2]); put("percent", E.percentOf(match.groupValues[3]))
                }
            },
            { it.stats.isNotEmpty() }, RecipeGroups.DAMAGE,
        ),
        Recipe(
            "damage", "Deal damage: an amount", "A fixed amount of damage (or a formula in the advanced choice).",
            { ctx -> listOf(P.number("amount", "Damage", "HP to take away", 10.0, min = 0.0), P.targetParam(ctx)) },
            { v, _ -> E.make("damage", v, true, "amount" to v.numOrFormula("amount")) },
            { j, _ -> E.read(j, "damage", setOf("target", "amount"))?.let { E.pick(it, "target", "amount") }?.takeIf { it.containsKey("amount") } },
            group = RecipeGroups.DAMAGE,
        ),
        Recipe(
            "attack_stat", "Extra strike: power from a stat",
            "Another hit computed with the damage formula, its power a share of its own stat (types, crits and damage changes apply; it never misses).",
            { ctx ->
                listOf(
                    P.choice("stat", "Stat", "its own stat that gives the power", ctx.stats, ctx.stats.firstOrNull { it.value == "atk" }?.value ?: ctx.stats.firstOrNull()?.value),
                    P.percent("percent", "Percent of the stat", "100 = the stat itself", 100.0, max = null),
                    P.targetParam(ctx, default = P.foeStart(ctx)),
                    P.choice("moveType", "Type", "empty = the type of the move in progress, or no type", ctx.types, null, optional = true),
                    P.choice("category", "Category", "empty = the category of the move in progress, or the first damaging one",
                        ctx.categories.filter { it.value in ctx.damagingCategories }, null, optional = true),
                )
            },
            { v, _ ->
                E.make("attack", v, true, "power" to "self.${v.str("stat")} * ${JsonTools.fraction(v.num("percent"))}",
                    "moveType" to v.strOrNull("moveType")?.takeIf { v.has("moveType") }, "category" to v.strOrNull("category")?.takeIf { v.has("category") })
            },
            { j, ctx ->
                val m = E.read(j, "attack", setOf("target", "power", "moveType", "category")) ?: return@Recipe null
                val match = Regex("^self\\.(\\w+) \\* (-?\\d+(?:\\.\\d+)?)$").matchEntire(m["power"] as? String ?: "") ?: return@Recipe null
                if (ctx.stats.none { it.value == match.groupValues[1] }) return@Recipe null
                E.pick(m, "target", "moveType", "category").apply { put("stat", match.groupValues[1]); put("percent", E.percentOf(match.groupValues[2])) }
            },
            { it.stats.isNotEmpty() && it.damagingCategories.isNotEmpty() }, RecipeGroups.DAMAGE,
        ),
        Recipe(
            "attack", "Extra strike: a hit with a power",
            "Another hit computed with the damage formula like a move of that power (types, crits and damage changes apply; it never misses and does not count as a move hit, so it cannot loop).",
            { ctx ->
                listOf(
                    P.number("power", "Power", if (ctx.powerFromStats) "in this setup power is the attack value (e.g. 'Extra strike: power from a stat')" else "like a move's power, e.g. 40", 40.0, min = 0.0),
                    P.targetParam(ctx, default = P.foeStart(ctx)),
                    P.choice("moveType", "Type", "empty = the type of the move in progress, or no type", ctx.types, null, optional = true),
                    P.choice("category", "Category", "empty = the category of the move in progress, or the first damaging one",
                        ctx.categories.filter { it.value in ctx.damagingCategories }, null, optional = true),
                )
            },
            { v, _ ->
                E.make("attack", v, true, "power" to v.numOrFormula("power"),
                    "moveType" to v.strOrNull("moveType")?.takeIf { v.has("moveType") }, "category" to v.strOrNull("category")?.takeIf { v.has("category") })
            },
            { j, _ -> E.read(j, "attack", setOf("target", "power", "moveType", "category"))?.let { E.pick(it, "target", "power", "moveType", "category") }?.takeIf { it.containsKey("power") } },
            { it.damagingCategories.isNotEmpty() }, RecipeGroups.DAMAGE,
        ),
        Recipe(
            "multiply_damage", "Multiply the damage", "x2 doubles the damage, x0.5 halves it, x0 cancels it. Only when damage is about to be dealt or taken.",
            { _ -> listOf(Param("value", "Multiplier", "1.5 = 50% more, 0.5 = half", ParamType.Number(min = 0.0, formula = true, noRecipient = true), 1.5)) },
            { v, _ -> E.make("multiplyDamage", v, false, "value" to v.numOrFormula("value")) },
            { j, _ -> E.read(j, "multiplyDamage", setOf("value"))?.let { E.pick(it, "value") }?.takeIf { it.containsKey("value") } },
            { it.event in Events.DAMAGE_MODIFIERS }, RecipeGroups.DAMAGE,
        ),
        // ---- Healing and HP ----
        hpShare(
            "heal_max_hp", "heal", "Heal: % of its HP",
            "Restores a share of each monster's own max HP (or of the HP it has lost). With a group (its allies, its team...) each one gets its own share.",
            listOf(E.MAX_HP, E.MISSING_HP, E.CURRENT_HP), 25.0, RecipeGroups.HP,
        ),
        Recipe(
            "drain", "Heal: % of the damage just dealt", "Drain-like: recover a share of the damage of this event.",
            { ctx -> listOf(P.percent("percent", "Percent of the damage", "50 = half of it", 50.0), P.targetParam(ctx, default = if (ctx.owner == OwnerKind.MOVE) "user" else "self")) },
            { v, _ -> E.make("heal", v, true, "amount" to "damage * ${JsonTools.fraction(v.num("percent"))}") },
            { j, _ ->
                val m = E.read(j, "heal", setOf("target", "amount")) ?: return@Recipe null
                val match = E.PERCENT_OF_DAMAGE.matchEntire(m["amount"] as? String ?: "") ?: return@Recipe null
                E.pick(m, "target").apply { put("percent", E.percentOf(match.groupValues[1])) }
            },
            damageEvents, RecipeGroups.HP,
        ),
        Recipe(
            "heal", "Heal: an amount", "Restores a fixed amount of HP (or a formula in the advanced choice).",
            { ctx -> listOf(P.number("amount", "HP", "HP to restore", 20.0, min = 0.0), P.targetParam(ctx)) },
            { v, _ -> E.make("heal", v, true, "amount" to v.numOrFormula("amount")) },
            { j, _ -> E.read(j, "heal", setOf("target", "amount"))?.let { E.pick(it, "target", "amount") }?.takeIf { it.containsKey("amount") } },
            group = RecipeGroups.HP,
        ),
        hpShare(
            "shield_max_hp", "shield", "Add a shield: % of its HP", "A shield that absorbs damage first, worth a share of each monster's own max HP.",
            listOf(E.MAX_HP, E.CURRENT_HP), 20.0, RecipeGroups.HP, max = null,
        ),
        Recipe(
            "shield", "Add a shield: an amount", "A shield of a fixed number of points (or a formula).",
            { ctx -> listOf(P.number("amount", "Shield points", "damage it absorbs", 50.0, min = 0.0), P.targetParam(ctx)) },
            { v, _ -> E.make("shield", v, true, "amount" to v.numOrFormula("amount")) },
            { j, _ -> E.read(j, "shield", setOf("target", "amount"))?.let { E.pick(it, "amount", "target") }?.takeIf { it.containsKey("amount") } },
            group = RecipeGroups.HP,
        ),
        hpShare(
            "set_hp", "setHp", "Set HP: % of its HP",
            "Sets each monster's HP to a share of its own max HP (or current HP: 50% of it halves it). Lowering it is damage no shield absorbs.",
            listOf(E.MAX_HP, E.CURRENT_HP), 50.0, RecipeGroups.HP,
        ),
        Recipe(
            "set_hp_amount", "Set HP: an amount", "Sets the HP to a number (e.g. 1), or a formula in the advanced choice (e.g. the user's HP).",
            { ctx -> listOf(P.number("amount", "HP", "the HP it will have (0 makes it faint)", 1.0, min = 0.0), P.targetParam(ctx, default = P.foeStart(ctx))) },
            { v, _ -> E.make("setHp", v, true, "amount" to v.numOrFormula("amount")) },
            { j, _ -> E.read(j, "setHp", setOf("target", "amount"))?.let { E.pick(it, "target", "amount") }?.takeIf { it.containsKey("amount") } },
            group = RecipeGroups.HP,
        ),
        Recipe(
            "balance_hp", "Share HP within a group", "Every monster of the group gets the group's average HP % (e.g. its team evens out its HP).",
            { ctx -> listOf(Param("target", "Group", "who shares their HP", ParamType.Choice(P.groupTargets(ctx)), "team")) },
            { v, _ -> linkedMapOf<String, Any?>("type" to "balanceHp", "target" to v.str("target")) },
            { j, _ -> E.read(j, "balanceHp", setOf("target"))?.takeIf { it["target"] is String }?.let { E.pick(it, "target") } },
            group = RecipeGroups.HP,
        ),
        Recipe(
            "swap_hp", "Swap HP with another monster", "It and the other monster exchange their HP percentages.",
            { ctx ->
                val options = P.livingTargets(ctx).filter { it.value != "self" && it.value != "user" && it.value !in setOf("allies", "team", "enemies", "all") }
                listOf(Param("target", "With whom", "the monster it swaps HP with", ParamType.Choice(options), if (ctx.owner == OwnerKind.MOVE) "target" else P.foeStart(ctx)))
            },
            { v, _ -> linkedMapOf<String, Any?>("type" to "swapHp", "target" to v.str("target")) },
            { j, _ -> E.read(j, "swapHp", setOf("target"))?.takeIf { it["target"] is String }?.let { E.pick(it, "target") } },
            group = RecipeGroups.HP,
        ),
        hpShare(
            "revive", "revive", "Revive: % of max HP", "Brings fainted monsters back with a share of their own max HP.",
            listOf(E.MAX_HP), 50.0, RecipeGroups.HP, min = 1.0, targets = { P.faintedTargets(it, false) }, withMessage = true,
        ),
        Recipe(
            "revive_amount", "Revive: an amount of HP", "Brings fainted monsters back with a fixed amount of HP (or a formula).",
            { ctx -> listOf(P.number("amount", "HP", "the HP it comes back with (at least 1)", 1.0, min = 1.0), P.targetParam(ctx, P.faintedTargets(ctx, false)), P.text("message", "Message", "replaces the revive line; empty text = silent")) },
            { v, _ -> E.make("revive", v, true, "amount" to v.numOrFormula("amount"), "message" to v.map["message"]?.toString()) },
            { j, _ -> E.read(j, "revive", setOf("target", "amount", "message"))?.let { E.pick(it, "amount", "target", "message") }?.takeIf { it.containsKey("amount") } },
            group = RecipeGroups.HP,
        ),
        Recipe(
            "prevent_revive", "Prevent revives", "The target (fainted or not) can no longer be revived in this battle.",
            { ctx -> listOf(P.targetParam(ctx, P.livingTargets(ctx).filter { it.value in P.REFS } + P.faintedTargets(ctx, false).filter { it.value !in P.REFS }), P.text("message", "Message", "replaces the default line; empty text = silent")) },
            { v, _ -> E.make("preventRevive", v, true, "message" to v.map["message"]?.toString()) },
            { j, _ -> E.read(j, "preventRevive", setOf("target", "message"))?.let { E.pick(it, "target", "message") } },
            group = RecipeGroups.HP,
        ),
        // ---- Statuses and stats ----
        Recipe(
            "apply_status", "Apply a status", "Gives a status (burn, a buff...), optionally with a chance and a duration.",
            { ctx ->
                listOf(
                    P.choice("status", "Status", "the status to apply", P.statusOptions(ctx)),
                    P.targetParam(ctx),
                    Param("chance", "Chance %", "empty = always", ParamType.Number(0.0, 100.0, percent = true, formula = true), 100.0, optional = true),
                    P.number("duration", "Duration (turns)", "empty = the status's own duration", null, min = 0.0, integer = true, optional = true),
                    P.number("stacks", "Stacks", "empty = 1", null, min = 1.0, integer = true, optional = true),
                    P.text("message", "Message", "replaces the status's apply message; empty text = silent"),
                )
            },
            { v, _ ->
                E.make(
                    "applyStatus", v, true, "status" to v.str("status"),
                    "chance" to v.map["chance"]?.let { v.numOrFormula("chance") },
                    "duration" to v.map["duration"]?.let { v.numOrFormula("duration") },
                    "stacks" to v.map["stacks"]?.let { v.numOrFormula("stacks") },
                    "message" to v.map["message"]?.toString(),
                )
            },
            { j, _ ->
                val m = E.read(j, "applyStatus", setOf("target", "status", "chance", "duration", "stacks", "message")) ?: return@Recipe null
                if (m["status"] !is String) return@Recipe null
                E.pick(m, "status", "target", "chance", "duration", "stacks", "message")
            },
            { it.statuses.isNotEmpty() }, RecipeGroups.STATUS,
        ),
        Recipe(
            "remove_status", "Remove statuses", "Removes one status, every buff, every debuff, or everything.",
            { ctx ->
                listOf(
                    P.choice("which", "Which", "what to remove", ctx.statuses + listOf(
                        Option("kind:buff", "every buff"), Option("kind:debuff", "every debuff"), Option("kind:major", "the major status"),
                        Option("kind:volatile", "every volatile status"), Option("all", "every status"),
                    )),
                    P.targetParam(ctx),
                )
            },
            { v, _ ->
                val which = v.str("which")
                if (which.startsWith("kind:")) E.make("removeStatus", v, true, "kind" to which.removePrefix("kind:"))
                else E.make("removeStatus", v, true, "status" to which)
            },
            { j, _ ->
                val m = E.read(j, "removeStatus", setOf("target", "status", "kind")) ?: return@Recipe null
                val status = m["status"] as? String
                val kind = m["kind"] as? String
                if ((status == null) == (kind == null)) return@Recipe null
                E.pick(m, "target").apply { put("which", status ?: "kind:$kind") }
            },
            group = RecipeGroups.STATUS,
        ),
        transfer(
            "pass_statuses", "Pass its own statuses on", "Moves its own statuses (e.g. its debuffs) onto another monster; they keep their turns and stacks.",
            null, pass = true,
        ),
        transfer(
            "steal_statuses", "Steal statuses", "Takes statuses (e.g. the target's buffs) from a monster and gives them to itself (or to another one).",
            null, pass = false,
        ),
        transfer(
            "copy_statuses", "Copy statuses", "Gives a copy of a monster's statuses (e.g. its buffs) to itself (or another one); the source keeps them.",
            "copy", pass = false,
        ),
        Recipe(
            "stat_stage", "Raise or lower a stat", "Changes a stat stage (+1 raises, -1 lowers; limited by the rules' stages).",
            { ctx ->
                listOf(
                    P.choice("stat", "Stat", "the stat to change", ctx.stageStats),
                    P.number("stages", "Stages", "+1 raises one stage, -2 lowers two", 1.0, min = -12.0, max = 12.0, integer = true),
                    P.targetParam(ctx),
                )
            },
            { v, _ -> E.make("statStage", v, true, "stat" to v.str("stat"), "stages" to v.numOrFormula("stages")) },
            { j, _ ->
                val m = E.read(j, "statStage", setOf("target", "stat", "stages")) ?: return@Recipe null
                if (m["stat"] !is String || m["stages"] == null) return@Recipe null
                E.pick(m, "stat", "stages", "target")
            },
            { it.stageStats.isNotEmpty() }, RecipeGroups.STATUS,
        ),
        Recipe(
            "copy_stages", "Copy stat changes", "Its stat stages become a copy of another monster's (raises and drops).",
            { ctx ->
                listOf(
                    P.targetParam(ctx, default = P.foeStart(ctx), label = "From whom", help = "whose stat changes are copied"),
                    Param("to", "To whom", "who gets the copy; empty = itself" + if (ctx.owner == OwnerKind.MOVE) ", the user" else "", ParamType.Choice(P.receivers(ctx)), null, optional = true),
                )
            },
            { v, _ -> E.make("copyStages", v, true, "to" to v.strOrNull("to")?.takeIf { v.has("to") }) },
            { j, _ -> E.read(j, "copyStages", setOf("target", "to"))?.takeIf { (it["to"] == null || it["to"] is String) }?.let { E.pick(it, "target", "to") } },
            { it.stageStats.isNotEmpty() }, RecipeGroups.STATUS,
        ),
        // ---- Moves and turns ----
        useMovePreset(
            "counterattack", "Strike back: use its first move on the attacker",
            "A counterattack: right away it uses its first move on the monster that attacked it (no PP spent; it does not use up its turn).",
            mapOf("move" to "first"), { it.owner != OwnerKind.MOVE && it.event in Events.ATTACKER_EVENTS },
            "a damage multiplier for this use: 50 = half damage; empty = 100",
        ),
        useMovePreset(
            "join_attack", "Join the attack: use its first move on the same target",
            "A follow-up: when an ally uses a move, it uses its first move on that move's target right away (no PP spent).",
            mapOf("move" to "first", "moveTarget" to "otherTarget"), { it.event == "allyUsedMove" },
            "a damage multiplier for this use: 50 = half damage; empty = 100",
        ),
        useMovePreset(
            "use_again", "Use this move again",
            "Right after, the same move is used once more on the same target (no PP spent). Limit it with a chance or \"once per battle\".",
            mapOf("move" to "same", "moveTarget" to "target"), { it.event == "afterMove" },
            "a damage multiplier for the second use: 50 = half damage; empty = 100",
        ),
        Recipe(
            "use_move", "Use a move (choose who, which and on whom)",
            "A monster uses a move right away, outside its turn (no PP spent unless you say so): counters, follow-ups, a move chosen by an item...",
            { ctx ->
                val moves = listOf(Option("first", "its first move", "the first move it knows")) +
                    (if (ctx.hasMove) listOf(Option("same", "the same move", "the move of this event, if it knows it (else its first move)")) else emptyList()) +
                    ctx.moves.map { Option(it.value, it.label) }
                val actors = P.livingTargets(ctx).filter { it.value !in setOf("enemies", "all", "randomEnemy", "lowestHpEnemy", "highestHpEnemy") }
                val aims = buildList {
                    add(Option("other", if (ctx.owner == OwnerKind.MOVE) "the move's target" else "the other monster", "the attacker, the target... of the event"))
                    if (ctx.otherUsesMove) add(Option("otherTarget", "the target of that move", "where the other monster's move was aimed"))
                    if (ctx.owner != OwnerKind.MOVE) { add(Option("target", "the event's target", "the target of the move involved")); add(Option("user", "the event's move user", "who used the move involved")) }
                    add(Option("self", "the one using it", "the actor itself"))
                    addAll(P.livingTargets(ctx).filter { it.value in setOf("enemies", "randomEnemy", "lowestHpEnemy", "highestHpEnemy", "allies", "lowestHpAlly", "randomAlly", "team") })
                }
                listOf(
                    Param("actor", "Who uses it", "empty = itself" + if (ctx.owner == OwnerKind.MOVE) ", the user" else "", ParamType.Choice(actors), null, optional = true),
                    Param("move", "Which move", "a move it does not know is used without PP; empty = its first move", ParamType.Choice(moves), "first", optional = true, start = "first"),
                    Param("moveTarget", "On whom", "empty = the event's other monster, e.g. the attacker, else the move's normal target", ParamType.Choice(aims), null, optional = true),
                    power("a damage multiplier for this use: 50 = half damage; empty = 100"),
                    P.yesNo("paid", "Spend PP / cooldown", "yes: it pays like a normal use and fails if it cannot"),
                    message("shown just before the move; empty = none"),
                )
            },
            { v, _ ->
                val m = linkedMapOf<String, Any?>("type" to "useMove")
                for (k in listOf("actor", "move", "moveTarget")) if (v.has(k)) m[k] = v.str(k)
                if (v.has("power")) m["power"] = v.numOrFormula("power")
                if (v.bool("paid")) m["free"] = false
                if (v.has("message")) m["message"] = v.str("message")
                m
            },
            { j, _ ->
                val m = E.read(j, "useMove", setOf("actor", "move", "moveTarget", "power", "free", "message")) ?: return@Recipe null
                if (listOf("actor", "move", "moveTarget", "message").any { m[it] != null && m[it] !is String }) return@Recipe null
                if (m["free"] != null && m["free"] != false) return@Recipe null
                E.pick(m, "actor", "move", "moveTarget", "power", "message").apply { put("paid", m["free"] == false) }
            },
            group = RecipeGroups.MOVES,
        ),
        Recipe(
            "extra_turn", "Give an extra turn", "The monster acts once more (speed_order: at the end of the round; action bar: right away). At most one extra turn at a time.",
            { ctx -> listOf(P.targetParam(ctx, default = if (ctx.owner == OwnerKind.MOVE) "user" else null)) },
            { v, _ -> E.make("extraTurn", v, true) },
            { j, _ -> E.read(j, "extraTurn", setOf("target"))?.let { E.pick(it, "target") } },
            group = RecipeGroups.MOVES,
        ),
        Recipe(
            "delay", "Delayed effect (some turns later)",
            "Runs effects later, at the end of a monster's turn N turns from now: Future Sight (damage the target 2 turns later), Wish (heal next turn)... Nothing happens if that monster faints or leaves first.",
            { ctx ->
                val attach = if (ctx.owner == OwnerKind.MOVE) listOf(Option("target", "the target", "the move's target"), Option("user", "the user", "the monster using the move"))
                else listOf(Option("self", "itself", "the monster that has it"), Option("other", "the other monster", "the other monster of the event"))
                val first = if (ctx.owner == OwnerKind.MOVE) RecipeUse("damage_max_hp", mapOf("percent" to 25.0, "base" to "maxHp"))
                else RecipeUse("heal_max_hp", mapOf("percent" to 25.0, "base" to "maxHp"))
                listOf(
                    P.number("turns", "After how many turns", "counted on that monster's turns: 1 = the end of its current or next turn (Classic-style rounds: the end of this round)", 2.0, min = 1.0, max = 99.0, integer = true, formula = false),
                    Param("attachTo", "Whose turns count", "empty = " + if (ctx.owner == OwnerKind.MOVE) "the target" else "itself", ParamType.Choice(attach), null, optional = true),
                    Param("effects", "Effects", "what happens then (edited like a trigger's DO)", ParamType.Effects(NOT_DELAYED), listOf(first)),
                    message("shown when the effects run; empty = none"),
                )
            },
            { v, ctx ->
                val m = linkedMapOf<String, Any?>("type" to "delay", "turns" to v.num("turns"))
                if (v.has("attachTo")) m["attachTo"] = v.str("attachTo")
                m["effects"] = v.uses("effects").map { use -> (byId(use.recipe) ?: all.last()).build(use.values, ctx) }
                if (v.has("message")) m["message"] = v.str("message")
                m
            },
            { j, ctx ->
                val m = E.read(j, "delay", setOf("turns", "attachTo", "effects", "message")) ?: return@Recipe null
                if (m["turns"] !is Number || (m["attachTo"] != null && m["attachTo"] !is String) || (m["message"] != null && m["message"] !is String)) return@Recipe null
                val effects = m["effects"] as? List<*> ?: return@Recipe null
                E.pick(m, "turns", "attachTo", "message").apply { put("effects", effects.map { recognise(it, ctx) }) }
            },
            group = RecipeGroups.MOVES,
        ),
        Recipe(
            "cancel_action", "Cancel the action", "The move is not used (only before a move).",
            { _ -> listOf(P.text("message", "Message", "shown instead of the move; empty = the default")) },
            { v, _ -> E.make("cancelAction", v, false, "message" to v.map["message"]?.toString()) },
            { j, _ -> E.read(j, "cancelAction", setOf("message"))?.let { E.pick(it, "message") } },
            { it.event == "beforeMove" }, RecipeGroups.MOVES,
        ),
        Recipe(
            "gauge", "Change the action bar", "Adds a share of the action bar (negative pushes it back). Action bar setups only.",
            { ctx -> listOf(P.number("amount", "Percent of the bar", "+30 fills 30% of the bar, -30 pushes it back", 30.0, min = -100.0, max = 100.0), P.targetParam(ctx)) },
            { v, _ -> E.make("gauge", v, true, "amount" to v.numOrFormula("amount")) },
            { j, _ -> E.read(j, "gauge", setOf("target", "amount"))?.let { E.pick(it, "amount", "target") }?.takeIf { it.containsKey("amount") } },
            { it.actionBar }, RecipeGroups.MOVES,
        ),
        Recipe(
            "cooldown", "Change cooldowns", "Adds turns to the cooldown of all the target's moves (negative = reduce).",
            { ctx -> listOf(P.number("amount", "Turns", "+1 delays every move one turn, -1 makes them ready sooner", 1.0, min = -10.0, max = 10.0, integer = true), P.targetParam(ctx)) },
            { v, _ -> E.make("cooldown", v, true, "amount" to v.numOrFormula("amount")) },
            { j, _ -> E.read(j, "cooldown", setOf("target", "amount"))?.let { E.pick(it, "amount", "target") }?.takeIf { it.containsKey("amount") } },
            { it.cooldowns }, RecipeGroups.MOVES,
        ),
        // ---- Switching ----
        Recipe(
            "force_switch", "Drag the target out", "The target is replaced right away by a random monster from its bench (fails if it has none).",
            { ctx -> listOf(P.targetParam(ctx, default = P.foeStart(ctx))) },
            { v, _ -> E.make("forceSwitch", v, true) },
            { j, _ -> E.read(j, "forceSwitch", setOf("target"))?.let { E.pick(it, "target") } },
            group = RecipeGroups.SWITCH,
        ),
        Recipe(
            "switch_out", "Switch out (U-turn)", "It leaves the field right away and its player picks who comes in (put it after the move, in \"after it was used\").",
            { ctx -> listOf(Param("target", "Who", "who switches out; empty = itself" + if (ctx.owner == OwnerKind.MOVE) ", the user" else "", ParamType.Choice(P.livingTargets(ctx)), null, optional = true)) },
            { v, _ -> E.make("switchOut", v, true) },
            { j, _ -> E.read(j, "switchOut", setOf("target"))?.let { E.pick(it, "target") } },
            group = RecipeGroups.SWITCH,
        ),
        // ---- Fields, items and messages ----
        Recipe(
            "set_field", "Start a field", "Starts a field (weather, screen, hazard...).",
            { ctx ->
                listOf(
                    P.choice("field", "Field", "the field to start", P.fieldOptions(ctx)),
                    P.choice("side", "Side", "side fields only: whose side (empty = its own side)", listOf(
                        Option("self", "its own side"), Option("enemy", "the enemy side"), Option("both", "both sides"),
                    ), default = null, optional = true),
                    Param("duration", "Duration (turns)", "empty = the field's own duration", ParamType.Number(min = 0.0, integer = true, formula = true, noRecipient = true), null, optional = true),
                    Param("chance", "Chance %", "empty = always", ParamType.Number(0.0, 100.0, percent = true, formula = true, noRecipient = true), 100.0, optional = true),
                    P.text("message", "Message", "replaces the field's start message; empty text = silent"),
                )
            },
            { v, _ ->
                E.make(
                    "setField", v, false, "field" to v.str("field"), "side" to v.map["side"]?.toString(),
                    "duration" to v.map["duration"]?.let { v.numOrFormula("duration") },
                    "chance" to v.map["chance"]?.let { v.numOrFormula("chance") }, "message" to v.map["message"]?.toString(),
                )
            },
            { j, _ ->
                val m = E.read(j, "setField", setOf("field", "side", "duration", "chance", "message")) ?: return@Recipe null
                if (m["field"] !is String) return@Recipe null
                E.pick(m, "field", "side", "duration", "chance", "message")
            },
            { it.fields.isNotEmpty() }, RecipeGroups.OTHER,
        ),
        Recipe(
            "remove_field", "End fields", "Ends a field, every field of a group, or every field.",
            { ctx ->
                listOf(
                    P.choice("which", "Which", "what to end", P.fieldOptions(ctx) +
                        ctx.fields.mapNotNull { it.group }.distinct().map { Option("group:$it", "every field of the group '$it'") } +
                        Option("all", "every field")),
                    P.choice("side", "Side", "side fields: whose side (empty = both)", listOf(
                        Option("self", "its own side"), Option("enemy", "the enemy side"), Option("both", "both sides"),
                    ), default = null, optional = true),
                    P.text("message", "Message", "replaces the end message; empty text = silent"),
                )
            },
            { v, _ ->
                val which = v.str("which")
                val head = if (which.startsWith("group:")) "group" to which.removePrefix("group:") else "field" to which
                E.make("removeField", v, false, head, "side" to v.map["side"]?.toString(), "message" to v.map["message"]?.toString())
            },
            { j, _ ->
                val m = E.read(j, "removeField", setOf("field", "group", "side", "message")) ?: return@Recipe null
                val field = m["field"] as? String
                val group = m["group"] as? String
                if ((field == null) == (group == null)) return@Recipe null
                E.pick(m, "side", "message").apply { put("which", field ?: "group:$group") }
            },
            { it.fields.isNotEmpty() }, RecipeGroups.OTHER,
        ),
        Recipe(
            "consume_item", "Use up the held item", "Removes the held item for the rest of the battle.",
            { ctx -> listOf(P.targetParam(ctx), P.text("message", "Message", "replaces \"X's item was used up.\"; empty text = silent")) },
            { v, _ -> E.make("consumeItem", v, true, "message" to v.map["message"]?.toString()) },
            { j, _ -> E.read(j, "consumeItem", setOf("target", "message"))?.let { E.pick(it, "target", "message") } },
            group = RecipeGroups.OTHER,
        ),
        Recipe(
            "message", "Show a message", "Prints a line in the battle log ({self}, {other}, {move}... are filled in).",
            { _ -> listOf(P.text("text", "Text", "the message", "{self} did something!", optional = false)) },
            { v, _ -> linkedMapOf<String, Any?>("type" to "message", "text" to v.str("text")) },
            { j, _ -> E.read(j, "message", setOf("text"))?.let { E.pick(it, "text") }?.takeIf { it["text"] is String } },
            group = RecipeGroups.OTHER,
        ),
        Recipe(
            "advanced", "Advanced: raw effect JSON", "Any effect of the README, written as JSON.",
            { _ -> listOf(Param("json", "Effect JSON", "e.g. {\"type\": \"damage\", \"amount\": \"self.maxHp / 8\"}", ParamType.Json, linkedMapOf("type" to "message", "text" to "..."))) },
            { v, _ -> v.json("json") },
            { j, _ -> if (j is Map<*, *>) mapOf("json" to JsonTools.copy(j)) else null },
            group = RecipeGroups.ADVANCED,
        ),
    )

    /** The recipes that make sense in [ctx] (owner kind, event, rules). */
    fun available(ctx: RecipeContext): List<Recipe> = all.filter { it.available(ctx) }

    /** The recipe with this id, or null. */
    fun byId(id: String): Recipe? = all.firstOrNull { it.id == id }

    /**
     * The recipe use that builds exactly [json] (the "advanced" raw JSON if no other one does). Recipes that work in
     * [ctx] are tried first (so a useMove in a turnEnd trigger is not read as "strike back").
     */
    fun recognise(json: Any?, ctx: RecipeContext): RecipeUse {
        val (fits, others) = all.filter { it.id != "advanced" }.partition { it.available(ctx) }
        for (r in fits + others) {
            r.recognise(json, ctx)?.let { return RecipeUse(r.id, it) }
        }
        return RecipeUse("advanced", mapOf("json" to JsonTools.copy(json)))
    }
}
/** The condition ("IF") recipes; each builds one part of a trigger condition (parts are joined with &&). */
object ConditionRecipes {
    private val NUM = "(-?\\d+(?:\\.\\d+)?)"
    private val WHO = "(self|other|user|target)"
    private val STR = "'((?:[^'\\\\]|\\\\.)*)'"

    private fun lit(s: String) = JsonTools.literal(s)
    private fun unlit(s: String) = s.replace(Regex("\\\\(.)"), "$1")

    private fun regexRecipe(
        id: String, label: String, help: String,
        params: (RecipeContext) -> List<Param>,
        build: (Values) -> String,
        regex: Regex,
        read: (MatchResult) -> Map<String, Any?>,
        available: (RecipeContext) -> Boolean = { true },
        group: String,
    ) = Recipe(id, label, help, params, { v, _ -> build(v) }, { j, _ ->
        (j as? String)?.let { regex.matchEntire(it.trim()) }?.let(read)
    }, available, group)

    /** The other monster of a "who" choice: self <-> other, user <-> target. */
    private fun partner(who: String): String = when (who) {
        "self" -> "other"
        "other" -> "self"
        "user" -> "target"
        else -> "user"
    }

    private val COMPARE = listOf(Option(">=", "at least"), Option("<=", "at most"), Option("==", "exactly"))

    private fun count(id: String, label: String, help: String, default: Double) =
        P.number(id, label, help, default, min = 0.0, max = 99.0, integer = true, formula = false)

    /** Every condition recipe ("advanced" last). */
    val all: List<Recipe> = listOf(
        // ---- HP ----
        regexRecipe(
            "hp_below", "HP below a %", "The monster's HP is below a share of its max HP.",
            { ctx -> listOf(P.choice("who", "Whose HP", "", P.whoOptions(ctx)), P.percent("percent", "Below (%)", "50 = below half HP", 50.0)) },
            { v -> "${v.str("who")}.hpPercent < ${JsonTools.num(v.num("percent"))}" },
            Regex("^$WHO\\.hpPercent < $NUM$"), { m -> mapOf("who" to m.groupValues[1], "percent" to m.groupValues[2].toDouble()) },
            group = RecipeGroups.COND_HP,
        ),
        regexRecipe(
            "hp_above", "HP above a %", "The monster's HP is above a share of its max HP.",
            { ctx -> listOf(P.choice("who", "Whose HP", "", P.whoOptions(ctx)), P.percent("percent", "Above (%)", "50 = above half HP", 50.0)) },
            { v -> "${v.str("who")}.hpPercent > ${JsonTools.num(v.num("percent"))}" },
            Regex("^$WHO\\.hpPercent > $NUM$"), { m -> mapOf("who" to m.groupValues[1], "percent" to m.groupValues[2].toDouble()) },
            group = RecipeGroups.COND_HP,
        ),
        regexRecipe(
            "full_hp", "HP is full", "The monster has all its HP.",
            { ctx -> listOf(P.choice("who", "Whose HP", "", P.whoOptions(ctx))) },
            { v -> "${v.str("who")}.hp == ${v.str("who")}.maxHp" },
            Regex("^$WHO\\.hp == (\\w+)\\.maxHp$"), { m -> if (m.groupValues[1] == m.groupValues[2]) mapOf("who" to m.groupValues[1]) else emptyMap() },
            group = RecipeGroups.COND_HP,
        ),
        regexRecipe(
            "not_full_hp", "HP is not full", "The monster has lost some HP (e.g. before healing).",
            { ctx -> listOf(P.choice("who", "Whose HP", "", P.whoOptions(ctx))) },
            { v -> "${v.str("who")}.hp < ${v.str("who")}.maxHp" },
            Regex("^$WHO\\.hp < (\\w+)\\.maxHp$"), { m -> if (m.groupValues[1] == m.groupValues[2]) mapOf("who" to m.groupValues[1]) else emptyMap() },
            group = RecipeGroups.COND_HP,
        ),
        regexRecipe(
            "hp_dropped_below", "Its HP just dropped below a %", "This damage took its HP from above the line to below it (so it works once per crossing).",
            { _ -> listOf(P.percent("percent", "Below (%)", "50 = it just fell under half HP", 50.0, min = 1.0)) },
            { v -> JsonTools.num(v.num("percent")).let { "self.hpPercent < $it && hpBefore * 100 / self.maxHp >= $it" } },
            Regex("^self\\.hpPercent < $NUM && hpBefore \\* 100 / self\\.maxHp >= $NUM$"), { m -> mapOf("percent" to m.groupValues[1].toDouble()) },
            { it.event == "damaged" }, RecipeGroups.COND_HP,
        ),
        // ---- Statuses and types ----
        regexRecipe(
            "has_status", "Has a status", "The monster has this status.",
            { ctx -> listOf(P.choice("who", "Who", "", P.whoOptions(ctx)), P.choice("status", "Status", "", ctx.statuses)) },
            { v -> "hasStatus(${v.str("who")}, ${lit(v.str("status"))})" },
            Regex("^hasStatus\\($WHO, $STR\\)$"), { m -> mapOf("who" to m.groupValues[1], "status" to unlit(m.groupValues[2])) },
            { it.statuses.isNotEmpty() }, RecipeGroups.COND_STATUS,
        ),
        regexRecipe(
            "lacks_status", "Does not have a status", "The monster does not have this status.",
            { ctx -> listOf(P.choice("who", "Who", "", P.whoOptions(ctx)), P.choice("status", "Status", "", ctx.statuses)) },
            { v -> "!hasStatus(${v.str("who")}, ${lit(v.str("status"))})" },
            Regex("^!hasStatus\\($WHO, $STR\\)$"), { m -> mapOf("who" to m.groupValues[1], "status" to unlit(m.groupValues[2])) },
            { it.statuses.isNotEmpty() }, RecipeGroups.COND_STATUS,
        ),
        regexRecipe(
            "status_count", "Number of buffs / debuffs", "How many statuses the monster has (each status counts once, whatever its stacks).",
            { ctx ->
                listOf(
                    P.choice("who", "Who", "", P.whoOptions(ctx)),
                    P.choice("kind", "Which statuses", "", listOf(
                        Option("buff", "buffs"), Option("debuff", "debuffs"), Option("major", "major statuses"),
                        Option("volatile", "volatile statuses"), Option("any", "any status"),
                    )),
                    P.choice("compare", "Compared how", "", COMPARE),
                    count("count", "Number", "e.g. at least 2 buffs; at most 0 = none", 1.0),
                )
            },
            { v ->
                val kind = v.str("kind")
                val call = if (kind == "any") "statusCount(${v.str("who")})" else "statusCount(${v.str("who")}, ${lit(kind)})"
                "$call ${v.str("compare")} ${JsonTools.num(v.num("count"))}"
            },
            Regex("^statusCount\\($WHO(?:, '(buff|debuff|major|volatile)')?\\) (>=|<=|==) (\\d+)$"),
            { m -> mapOf("who" to m.groupValues[1], "kind" to m.groupValues[2].ifEmpty { "any" }, "compare" to m.groupValues[3], "count" to m.groupValues[4].toDouble()) },
            group = RecipeGroups.COND_STATUS,
        ),
        regexRecipe(
            "has_type", "Has a type", "The monster has this type.",
            { ctx -> listOf(P.choice("who", "Who", "", P.whoOptions(ctx)), P.choice("type", "Type", "", ctx.types)) },
            { v -> "hasType(${v.str("who")}, ${lit(v.str("type"))})" },
            Regex("^hasType\\($WHO, $STR\\)$"), { m -> mapOf("who" to m.groupValues[1], "type" to unlit(m.groupValues[2])) },
            { it.types.isNotEmpty() }, RecipeGroups.COND_STATUS,
        ),
        regexRecipe(
            "lacks_type", "Does not have a type", "The monster does not have this type.",
            { ctx -> listOf(P.choice("who", "Who", "", P.whoOptions(ctx)), P.choice("type", "Type", "", ctx.types)) },
            { v -> "!hasType(${v.str("who")}, ${lit(v.str("type"))})" },
            Regex("^!hasType\\($WHO, $STR\\)$"), { m -> mapOf("who" to m.groupValues[1], "type" to unlit(m.groupValues[2])) },
            { it.types.isNotEmpty() }, RecipeGroups.COND_STATUS,
        ),
        regexRecipe(
            "status_is", "The status is", "In status events: the status that was applied, given or that ended.",
            { ctx -> listOf(P.choice("status", "Status", "", ctx.statuses)) },
            { v -> "status == ${lit(v.str("status"))}" },
            Regex("^status == $STR$"), { m -> mapOf("status" to unlit(m.groupValues[1])) },
            { it.event == "statusApplied" || it.event == "statusRemoved" || it.event == "statusInflicted" }, RecipeGroups.COND_STATUS,
        ),
        // ---- The move and the hit ----
        regexRecipe(
            "move_type", "The move is of a type", "The move involved has this type.",
            { ctx -> listOf(P.choice("type", "Type", "", ctx.types)) },
            { v -> "move.type == ${lit(v.str("type"))}" },
            Regex("^move\\.type == $STR$"), { m -> mapOf("type" to unlit(m.groupValues[1])) },
            { it.hasMove && it.types.isNotEmpty() }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "move_category", "The move is of a category", "The move involved has this category (physical, special...).",
            { ctx -> listOf(P.choice("category", "Category", "", ctx.categories)) },
            { v -> "move.category == ${lit(v.str("category"))}" },
            Regex("^move\\.category == $STR$"), { m -> mapOf("category" to unlit(m.groupValues[1])) },
            { it.hasMove && it.categories.isNotEmpty() }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "move_flag", "The move has a flag", "The move involved has this flag (e.g. contact).",
            { ctx -> listOf(Param("flag", "Flag", "e.g. contact (flags used here: ${ctx.flags.joinToString(", ")})", ParamType.Text, "contact")) },
            { v -> "hasFlag(${lit(v.str("flag"))})" },
            Regex("^hasFlag\\($STR\\)$"), { m -> mapOf("flag" to unlit(m.groupValues[1])) },
            { it.hasMove }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "move_power_at_most", "The move's power is at most", "Weak moves only (e.g. a Technician-like boost).",
            { _ -> listOf(P.number("power", "Power at most", "", 60.0, min = 0.0, formula = false)) },
            { v -> "move.power <= ${JsonTools.num(v.num("power"))}" },
            Regex("^move\\.power <= $NUM$"), { m -> mapOf("power" to m.groupValues[1].toDouble()) },
            { it.hasMove }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "dealt_damage", "It did damage", "The hit or effect dealt some damage (not 0).",
            { _ -> emptyList() }, { _ -> "damage > 0" }, Regex("^damage > 0$"), { _ -> emptyMap() },
            { it.hasDamage }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "is_crit", "It is a critical hit", "",
            { _ -> emptyList() }, { _ -> "isCrit" }, Regex("^isCrit$"), { _ -> emptyMap() },
            { it.event in Events.HIT_EVENTS }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "super_effective", "The move is super effective", "The type multiplier is above 1.",
            { _ -> emptyList() }, { _ -> "effectiveness > 1" }, Regex("^effectiveness > 1$"), { _ -> emptyMap() },
            { it.event in Events.HIT_EVENTS }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "hit_number", "It is hit number N", "Only on that hit of a multi-hit move (e.g. the 3rd hit stuns).",
            { _ -> listOf(count("number", "Hit number", "1 = the first hit", 2.0)) },
            { v -> "hitIndex == ${JsonTools.num(v.num("number"))}" },
            Regex("^hitIndex == (\\d+)$"), { m -> mapOf("number" to m.groupValues[1].toDouble()) },
            { it.event in Events.HIT_EVENTS }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "hit_at_least", "It is hit number N or later", "From that hit on (e.g. from the 2nd hit).",
            { _ -> listOf(count("number", "From hit number", "2 = every hit but the first", 2.0)) },
            { v -> "hitIndex >= ${JsonTools.num(v.num("number"))}" },
            Regex("^hitIndex >= (\\d+)$"), { m -> mapOf("number" to m.groupValues[1].toDouble()) },
            { it.event in Events.HIT_EVENTS }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "last_hit", "It is the last hit", "Only on the last hit of the move on this target.",
            { _ -> emptyList() }, { _ -> "hitIndex == hitCount" }, Regex("^hitIndex == hitCount$"), { _ -> emptyMap() },
            { it.event in Events.HIT_EVENTS }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "landed", "The attack landed", "At least one hit of the move landed on it.",
            { _ -> emptyList() }, { _ -> "landed" }, Regex("^landed$"), { _ -> emptyMap() },
            { it.event == "attacked" }, RecipeGroups.COND_MOVE,
        ),
        regexRecipe(
            "not_landed", "The attack missed it", "No hit landed (a miss, out of reach, an immunity).",
            { _ -> emptyList() }, { _ -> "!landed" }, Regex("^!landed$"), { _ -> emptyMap() },
            { it.event == "attacked" }, RecipeGroups.COND_MOVE,
        ),
        // ---- Battle situation ----
        regexRecipe(
            "other_is_enemy", "The other monster is an enemy", "E.g. only strike back at enemies (an ally's healing move also targets it).",
            { _ -> emptyList() }, { _ -> "!sameSide(self, other)" }, Regex("^!sameSide\\(self, other\\)$"), { _ -> emptyMap() },
            { it.owner != OwnerKind.MOVE && it.hasOther }, RecipeGroups.COND_BATTLE,
        ),
        regexRecipe(
            "other_is_ally", "The other monster is an ally", "",
            { _ -> emptyList() }, { _ -> "sameSide(self, other)" }, Regex("^sameSide\\(self, other\\)$"), { _ -> emptyMap() },
            { it.owner != OwnerKind.MOVE && it.hasOther }, RecipeGroups.COND_BATTLE,
        ),
        regexRecipe(
            "acted", "Has already acted", "Classic-style rounds: it already acted this round; action bar: it is the monster whose turn it is.",
            { ctx -> listOf(P.choice("who", "Who", "", P.whoOptions(ctx)), P.choice("state", "Which", "", listOf(Option("already", "already acted"), Option("not", "has not acted yet")))) },
            { v -> (if (v.str("state") == "not") "!" else "") + "${v.str("who")}.acted" },
            Regex("^(!?)$WHO\\.acted$"), { m -> mapOf("who" to m.groupValues[2], "state" to if (m.groupValues[1] == "!") "not" else "already") },
            group = RecipeGroups.COND_BATTLE,
        ),
        Recipe(
            "faster", "Is faster than the other", "Its speed is higher than the other monster's (e.g. the user is faster than the target).",
            { ctx -> listOf(P.choice("who", "Who is faster", "", P.whoOptions(ctx))) },
            { v, ctx -> "${v.str("who")}.${ctx.speedStat} > ${partner(v.str("who"))}.${ctx.speedStat}" },
            { j, ctx ->
                val m = Regex("^$WHO\\.(\\w+) > $WHO\\.(\\w+)$").matchEntire((j as? String)?.trim() ?: "") ?: return@Recipe null
                if (m.groupValues[2] != ctx.speedStat || m.groupValues[4] != ctx.speedStat || m.groupValues[3] != partner(m.groupValues[1])) return@Recipe null
                mapOf("who" to m.groupValues[1])
            },
            group = RecipeGroups.COND_BATTLE,
        ),
        regexRecipe(
            "allies_count", "Number of allies or enemies standing", "Active, non-fainted monsters on the field (its allies: not counting itself).",
            { _ ->
                listOf(
                    P.choice("side", "Whose", "", listOf(Option("allies", "its allies"), Option("enemies", "enemies"))),
                    P.choice("compare", "Compared how", "", COMPARE),
                    count("count", "Number", "e.g. at least 1 ally; at most 0 = it is alone", 1.0),
                )
            },
            { v -> "${v.str("side")} ${v.str("compare")} ${JsonTools.num(v.num("count"))}" },
            Regex("^(allies|enemies) (>=|<=|==) (\\d+)$"), { m -> mapOf("side" to m.groupValues[1], "compare" to m.groupValues[2], "count" to m.groupValues[3].toDouble()) },
            group = RecipeGroups.COND_BATTLE,
        ),
        regexRecipe(
            "charging", "Is charging a move", "It is charging a charge move (between the charging turn and the release).",
            { ctx -> listOf(P.choice("who", "Who", "", P.whoOptions(ctx))) },
            { v -> "${v.str("who")}.charging" },
            Regex("^$WHO\\.charging$"), { m -> mapOf("who" to m.groupValues[1]) },
            group = RecipeGroups.COND_BATTLE,
        ),
        regexRecipe(
            "field_active", "A field is active", "The field is active (globally or on any side).",
            { ctx -> listOf(P.choice("field", "Field", "", P.fieldOptions(ctx))) },
            { v -> "fieldActive(${lit(v.str("field"))})" },
            Regex("^fieldActive\\($STR\\)$"), { m -> mapOf("field" to unlit(m.groupValues[1])) },
            { it.fields.isNotEmpty() }, RecipeGroups.COND_BATTLE,
        ),
        Recipe(
            "advanced", "Advanced: a formula", "Any condition written as a formula (it must be true).",
            { _ -> listOf(Param("formula", "Condition", "e.g. self.hpPercent < 50 && turn > 3", ParamType.Formula(noRecipient = true), "true")) },
            { v, _ -> v.str("formula") },
            { j, _ -> (j as? String)?.let { mapOf("formula" to it) } },
            group = RecipeGroups.ADVANCED,
        ),
    )

    /** The recipes that make sense in [ctx]. */
    fun available(ctx: RecipeContext): List<Recipe> = all.filter { it.available(ctx) }

    /** The recipe with this id, or null. */
    fun byId(id: String): Recipe? = all.firstOrNull { it.id == id }

    /** Joins condition parts with " && " (an advanced part with a top-level || or ?: gets brackets). */
    fun join(parts: List<RecipeUse>, ctx: RecipeContext): String? {
        if (parts.isEmpty()) return null
        val texts = parts.map { use -> (byId(use.recipe) ?: all.last()).build(use.values, ctx).toString() }
        if (texts.size == 1) return texts[0]
        return texts.joinToString(" && ") { if (topLevel(it, "||") || topLevel(it, "?")) "($it)" else it }
    }

    /** The parts of a condition: recognised pieces joined with &&, or one advanced part when they would not rebuild it exactly. */
    fun split(condition: String, ctx: RecipeContext): List<RecipeUse> {
        val whole = listOf(RecipeUse("advanced", mapOf("formula" to condition)))
        if (topLevel(condition, "||") || topLevel(condition, "?")) return recogniseOne(condition, ctx)?.let { listOf(it) } ?: whole
        val pieces = splitTopLevel(condition)
        val uses = mutableListOf<RecipeUse>()
        var i = 0
        while (i < pieces.size) {
            // A recipe whose formula has a top-level && ("its HP just dropped below") spans two pieces.
            val pair = if (i + 1 < pieces.size) recogniseOne(pieces[i] + " && " + pieces[i + 1], ctx) else null
            if (pair != null) {
                uses += pair
                i += 2
                continue
            }
            uses += recogniseOne(pieces[i], ctx) ?: RecipeUse("advanced", mapOf("formula" to unwrap(pieces[i])))
            i++
        }
        return if (join(uses, ctx) == condition) uses else whole
    }

    private fun recogniseOne(text: String, ctx: RecipeContext): RecipeUse? {
        for (r in all) {
            if (r.id == "advanced") continue
            r.recognise(text, ctx)?.let { return RecipeUse(r.id, it) }
        }
        return null
    }

    /** "(a || b)" -> "a || b" when the brackets wrap a part that [join] would bracket again. */
    private fun unwrap(text: String): String {
        if (!text.startsWith("(") || !text.endsWith(")")) return text
        val inner = text.substring(1, text.length - 1)
        // The brackets must belong together: the inner text never closes more than it opens.
        if (scan(inner) { _, depth -> depth < 0 } != null || !balanced(inner)) return text
        return if (topLevel(inner, "||") || topLevel(inner, "?")) inner else text
    }

    private fun balanced(text: String): Boolean {
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(' -> depth++
                ')' -> if (--depth < 0) return false
            }
            i++
        }
        return depth == 0
    }

    /** True if [op] appears outside brackets and quotes. */
    private fun topLevel(text: String, op: String): Boolean = scan(text) { i, depth -> depth == 0 && text.startsWith(op, i) } != null

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var i = 0
        while (true) {
            val at = scan(text, i) { j, depth -> depth == 0 && text.startsWith("&&", j) } ?: break
            parts += text.substring(start, at).trim()
            start = at + 2
            i = at + 2
        }
        parts += text.substring(start).trim()
        return parts
    }

    /** The first index from [from] where [test] holds (skipping string literals), or null. */
    private fun scan(text: String, from: Int = 0, test: (Int, Int) -> Boolean): Int? {
        var depth = 0
        var i = from
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(' -> depth++
                ')' -> depth--
                else -> if (test(i, depth)) return i
            }
            i++
        }
        return null
    }
}

/**
 * Move power ("how strong") recipes. Their JSON is a small object: {"power": ...} (no "power" = no direct damage),
 * plus "trigger" for fixed damage (a hit trigger that deals the damage).
 */
object PowerRecipes {
    private val NUM = "(-?\\d+(?:\\.\\d+)?)"
    private val STR = "'((?:[^'\\\\]|\\\\.)*)'"

    private fun power(json: Any?): Any? = (json as? Map<*, *>)?.takeIf { it.keys.all { k -> k == "power" } }?.get("power")

    /** Every power recipe ("formula" last: it accepts any power). */
    val all: List<Recipe> = listOf(
        Recipe(
            "fixed", "Fixed power", "The move's power (the damage formula turns it into damage).",
            { ctx -> listOf(P.number("power", "Power", if (ctx.powerFromStats) "in this setup power is the attack value (see 'Power from a stat')" else "e.g. 40 weak, 80 strong, 120 very strong", 40.0, min = 1.0, formula = false)) },
            { v, _ -> mapOf("power" to v.num("power")) },
            { j, _ -> (power(j) as? Number)?.toDouble()?.takeIf { it > 0 }?.let { mapOf("power" to it) } },
        ),
        Recipe(
            "none", "No direct damage", "The move only does what its effects do (status moves, fixed-damage moves...).",
            { _ -> emptyList() },
            { _, _ -> emptyMap<String, Any?>() },
            { j, _ -> if (j is Map<*, *> && (j.isEmpty() || (j.keys == setOf("power") && (j["power"] as? Number)?.toDouble() == 0.0))) emptyMap() else null },
        ),
        Recipe(
            "stat_based", "Power from a stat", "Power equals a share of the user's stat (action bar style: 360% of Attack).",
            { ctx -> listOf(P.choice("stat", "Stat", "the user's stat", ctx.stats, default = ctx.stats.firstOrNull { it.value == "atk" }?.value ?: ctx.stats.firstOrNull()?.value), P.percent("percent", "Percent of the stat", "360 = 3.6 times the stat", 100.0, max = null)) },
            { v, _ -> mapOf("power" to "user.${v.str("stat")} * ${JsonTools.fraction(v.num("percent"))}") },
            { j, ctx ->
                val m = Regex("^user\\.(\\w+) \\* $NUM$").matchEntire(power(j) as? String ?: "") ?: return@Recipe null
                if (ctx.stats.none { it.value == m.groupValues[1] }) return@Recipe null
                mapOf("stat" to m.groupValues[1], "percent" to JsonTools.round(m.groupValues[2].toDouble() * 100))
            },
            { it.stats.isNotEmpty() },
        ),
        Recipe(
            "target_low_hp", "Doubles if the target is below X% HP", "Stronger against weakened targets.",
            { _ -> listOf(P.number("power", "Power", "normal power", 65.0, min = 1.0, formula = false), P.percent("percent", "Target HP below (%)", "50 = below half HP", 50.0)) },
            { v, _ -> mapOf("power" to "target.hpPercent < ${JsonTools.num(v.num("percent"))} ? ${JsonTools.num(v.num("power") * 2)} : ${JsonTools.num(v.num("power"))}") },
            { j, _ ->
                val m = Regex("^target\\.hpPercent < $NUM \\? $NUM : $NUM$").matchEntire(power(j) as? String ?: "") ?: return@Recipe null
                mapOf("power" to m.groupValues[3].toDouble(), "percent" to m.groupValues[1].toDouble())
            },
        ),
        Recipe(
            "user_low_hp", "Stronger when the user has low HP", "Power grows from a minimum (full HP) to a maximum (almost no HP).",
            { _ -> listOf(P.number("min", "Power at full HP", "", 20.0, min = 0.0, formula = false), P.number("max", "Power near 0 HP", "", 200.0, min = 1.0, formula = false)) },
            { v, _ -> mapOf("power" to "${JsonTools.num(v.num("min"))} + ${JsonTools.num(v.num("max") - v.num("min"))} * (1 - user.hpPercent / 100)") },
            { j, _ ->
                val m = Regex("^$NUM \\+ $NUM \\* \\(1 - user\\.hpPercent / 100\\)$").matchEntire(power(j) as? String ?: "") ?: return@Recipe null
                val min = m.groupValues[1].toDouble()
                mapOf("min" to min, "max" to JsonTools.round(min + m.groupValues[2].toDouble()))
            },
        ),
        Recipe(
            "target_status", "Doubles if the target has a status", "Stronger against targets with this status.",
            { ctx -> listOf(P.number("power", "Power", "normal power", 65.0, min = 1.0, formula = false), P.choice("status", "Status", "", ctx.statuses)) },
            { v, _ -> mapOf("power" to "hasStatus(target, ${JsonTools.literal(v.str("status"))}) ? ${JsonTools.num(v.num("power") * 2)} : ${JsonTools.num(v.num("power"))}") },
            { j, _ ->
                val m = Regex("^hasStatus\\(target, $STR\\) \\? $NUM : $NUM$").matchEntire(power(j) as? String ?: "") ?: return@Recipe null
                mapOf("power" to m.groupValues[3].toDouble(), "status" to m.groupValues[1].replace(Regex("\\\\(.)"), "$1"))
            },
            { it.statuses.isNotEmpty() },
        ),
        Recipe(
            "fixed_damage", "Fixed damage", "Always deals the same damage (or the user's level), whatever the stats and types.",
            { _ -> listOf(P.yesNo("level", "Damage = the user's level", "yes: as much damage as the user's level"), P.number("amount", "Damage", "used when not the level", 40.0, min = 1.0, formula = false)) },
            { v, _ ->
                val amount: Any = if (v.bool("level")) "user.level" else v.num("amount")
                mapOf("trigger" to linkedMapOf("on" to "hit", "effects" to listOf(linkedMapOf("type" to "damage", "target" to "target", "amount" to amount))))
            },
            { j, _ ->
                val m = j as? Map<*, *> ?: return@Recipe null
                if (m.keys != setOf("trigger")) return@Recipe null
                val effect = (((m["trigger"] as? Map<*, *>)?.get("effects") as? List<*>)?.singleOrNull() as? Map<*, *>) ?: return@Recipe null
                when (val a = effect["amount"]) {
                    "user.level" -> mapOf("level" to true, "amount" to 40.0)
                    is Number -> mapOf("level" to false, "amount" to a.toDouble())
                    else -> null
                }
            },
        ),
        Recipe(
            "per_hit", "Grows with each hit", "The first hit has this power, the second twice as much, the third three times... (Triple Kick).",
            { _ -> listOf(P.number("power", "Power of the first hit", "e.g. 20: then 40, 60...", 20.0, min = 1.0, formula = false)) },
            { v, _ -> mapOf("power" to "${JsonTools.num(v.num("power"))} * hitIndex") },
            { j, _ -> Regex("^$NUM \\* hitIndex$").matchEntire(power(j) as? String ?: "")?.let { mapOf("power" to it.groupValues[1].toDouble()) } },
        ),
        Recipe(
            "per_status", "Stronger per buff (or debuff) on the target", "Power grows with the number of buffs (or debuffs) the target has.",
            { _ ->
                listOf(
                    P.number("power", "Power with none", "", 40.0, min = 0.0, formula = false),
                    P.number("bonus", "Extra power for each one", "e.g. 20", 20.0, min = 0.0, formula = false),
                    P.choice("kind", "Counting", "", listOf(Option("buff", "its buffs"), Option("debuff", "its debuffs"), Option("any", "any status"))),
                )
            },
            { v, _ ->
                val kind = v.str("kind")
                val call = if (kind == "any") "statusCount(target)" else "statusCount(target, ${JsonTools.literal(kind)})"
                mapOf("power" to "${JsonTools.num(v.num("power"))} + ${JsonTools.num(v.num("bonus"))} * $call")
            },
            { j, _ ->
                val m = Regex("^$NUM \\+ $NUM \\* statusCount\\(target(?:, '(buff|debuff)')?\\)$").matchEntire(power(j) as? String ?: "") ?: return@Recipe null
                mapOf("power" to m.groupValues[1].toDouble(), "bonus" to m.groupValues[2].toDouble(), "kind" to m.groupValues[3].ifEmpty { "any" })
            },
        ),
        Recipe(
            "per_ally", "Stronger per ally standing", "Power grows with the number of allies of the user still on the field.",
            { _ -> listOf(P.number("power", "Power when alone", "", 40.0, min = 0.0, formula = false), P.number("bonus", "Extra power for each ally", "e.g. 20", 20.0, min = 0.0, formula = false)) },
            { v, _ -> mapOf("power" to "${JsonTools.num(v.num("power"))} + ${JsonTools.num(v.num("bonus"))} * allies") },
            { j, _ ->
                val m = Regex("^$NUM \\+ $NUM \\* allies$").matchEntire(power(j) as? String ?: "") ?: return@Recipe null
                mapOf("power" to m.groupValues[1].toDouble(), "bonus" to m.groupValues[2].toDouble())
            },
        ),
        Recipe(
            "speed_ratio", "Stronger when faster than the target", "Power = this value times the user's speed divided by the target's, up to a maximum.",
            { _ -> listOf(P.number("power", "Power at equal speed", "twice as fast = twice this", 60.0, min = 1.0, formula = false), P.number("max", "Maximum power", "", 150.0, min = 1.0, formula = false)) },
            { v, ctx -> mapOf("power" to "min(${JsonTools.num(v.num("max"))}, ${JsonTools.num(v.num("power"))} * user.${ctx.speedStat} / target.${ctx.speedStat})") },
            { j, ctx ->
                val m = Regex("^min\\($NUM, $NUM \\* user\\.(\\w+) / target\\.(\\w+)\\)$").matchEntire(power(j) as? String ?: "") ?: return@Recipe null
                if (m.groupValues[3] != ctx.speedStat || m.groupValues[4] != ctx.speedStat) return@Recipe null
                mapOf("max" to m.groupValues[1].toDouble(), "power" to m.groupValues[2].toDouble())
            },
        ),
        Recipe(
            "lost_hp", "Grows with the HP the user lost", "Power = a base plus a share of the HP points the user has lost.",
            { _ -> listOf(P.number("power", "Power at full HP", "", 40.0, min = 0.0, formula = false), P.percent("percent", "Plus % of the lost HP", "50 = half of the HP points it lost", 50.0, max = null)) },
            { v, _ -> mapOf("power" to "${JsonTools.num(v.num("power"))} + (user.maxHp - user.hp) * ${JsonTools.fraction(v.num("percent"))}") },
            { j, _ ->
                val m = Regex("^$NUM \\+ \\(user\\.maxHp - user\\.hp\\) \\* $NUM$").matchEntire(power(j) as? String ?: "") ?: return@Recipe null
                mapOf("power" to m.groupValues[1].toDouble(), "percent" to JsonTools.round(m.groupValues[2].toDouble() * 100))
            },
        ),
        Recipe(
            "target_acted", "Doubles if the target already acted", "Stronger against a target that moved before the user (Payback-like).",
            { _ -> listOf(P.number("power", "Power", "normal power", 50.0, min = 1.0, formula = false)) },
            { v, _ -> mapOf("power" to "target.acted ? ${JsonTools.num(v.num("power") * 2)} : ${JsonTools.num(v.num("power"))}") },
            { j, _ -> Regex("^target\\.acted \\? $NUM : $NUM$").matchEntire(power(j) as? String ?: "")?.let { mapOf("power" to it.groupValues[2].toDouble()) } },
        ),
        Recipe(
            "formula", "Advanced: a power formula", "Any power written as a formula (user, target and move are available).",
            { _ -> listOf(Param("formula", "Power formula", "e.g. user.spe > target.spe ? 80 : 40", ParamType.Formula(FormulaScope.MOVE), "user.level > 50 ? 60 : 40")) },
            { v, _ -> mapOf("power" to v.numOrFormula("formula")) },
            { j, _ -> power(j)?.let { mapOf("formula" to it) } },
        ),
    )

    /** The recipe with this id, or null. */
    fun byId(id: String): Recipe? = all.firstOrNull { it.id == id }

    /** The recipes that make sense in [ctx]. */
    fun available(ctx: RecipeContext): List<Recipe> = all.filter { it.available(ctx) }
}
