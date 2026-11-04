package creator

/**
 * Entry presets: starting points for statuses, abilities, items and fields that the user then tweaks in the form.
 * Each one is a [Recipe] whose JSON is a FRAGMENT of an entry: some of "statModifiers", "triggers", "rules" and
 * "scope". [apply] merges a fragment into an entry (stat modifiers and rules are merged, triggers appended).
 */
object Presets {
    // ------------------------------------------------------------------------------------------------------------
    // Fragment helpers

    private fun trigger(
        on: String,
        condition: String? = null,
        chance: Any? = null,
        message: String? = null,
        limit: Int = 0,
        effects: List<Map<String, Any?>>,
    ): MutableMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("on" to on)
        if (condition != null) m["condition"] = condition
        if (chance != null) m["chance"] = chance
        if (!message.isNullOrEmpty()) m["message"] = message
        if (limit != 0) m["limit"] = limit.toDouble()
        m["effects"] = effects
        return m
    }

    private fun effect(type: String, vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to type).apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } }

    private fun frag(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf<String, Any?>().apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } }

    @Suppress("UNCHECKED_CAST")
    private fun triggersOf(json: Any?): List<Map<String, Any?>> =
        ((json as? Map<*, *>)?.get("triggers") as? List<*>).orEmpty().map { it as? Map<String, Any?> ?: emptyMap() }

    @Suppress("UNCHECKED_CAST")
    private fun effectsOf(trigger: Map<String, Any?>?): List<Map<String, Any?>> =
        (trigger?.get("effects") as? List<*>).orEmpty().map { it as? Map<String, Any?> ?: emptyMap() }

    private fun firstEffect(json: Any?): Map<String, Any?>? = effectsOf(triggersOf(json).firstOrNull()).firstOrNull()

    private fun lit(s: String) = JsonTools.literal(s)

    private val NUM = "(-?\\d+(?:\\.\\d+)?)"
    private val STR = "'((?:[^'\\\\]|\\\\.)*)'"
    private fun unlit(s: String) = s.replace(Regex("\\\\(.)"), "$1")

    /** Percent of "self.maxHp * F" (or null). */
    private fun maxHpPercent(amount: Any?): Double? =
        Regex("^self\\.maxHp \\* $NUM$").matchEntire(amount as? String ?: "")?.let { JsonTools.round(it.groupValues[1].toDouble() * 100) }

    private fun messageParam(default: String) = Param("message", "Message", "logged when it works (empty = no message)", ParamType.Text, default)

    private fun consumableParam() = P.yesNo("consumable", "Used up after it works", "items: it works once, then the item is gone")

    /** Adds "limit": 1 and a consumeItem effect when [consumable]. */
    private fun consumable(t: MutableMap<String, Any?>, consumable: Boolean): MutableMap<String, Any?> {
        if (!consumable) return t
        t["limit"] = 1.0
        t.remove("effects")?.let { t["effects"] = (it as List<*>) + effect("consumeItem", "target" to "self") }
        return t
    }

    private fun isConsumable(json: Any?): Boolean = firstEffect(json) != null &&
        effectsOf(triggersOf(json).firstOrNull()).lastOrNull()?.get("type") == "consumeItem"

    // ------------------------------------------------------------------------------------------------------------
    // Statuses

    /** Status presets (README "Statuses.json"). */
    val statuses: List<Recipe> = listOf(
        Recipe(
            "dot", "Damage over time", "Loses a share of its max HP at the end of each of its turns (poison, burn...).",
            { _ -> listOf(P.percent("percent", "Percent of max HP per turn", "6.25 = 1/16 of its max HP", 6.25), messageParam("{self} is hurt by {status}!")) },
            { v, _ -> frag("triggers" to listOf(trigger("turnEnd", message = v.str("message"), effects = listOf(effect("damage", "target" to "self", "amount" to E2.maxHp(v.num("percent"))))))) },
            { j, _ -> maxHpPercent(firstEffect(j)?.get("amount"))?.let { mapOf("percent" to it, "message" to (triggersOf(j).first()["message"] ?: "")) } },
        ),
        Recipe(
            "skip_turns", "Skip turns", "Cannot move while it lasts (stun, sleep...); optionally wakes up when hit.",
            { _ -> listOf(Param("message", "Message", "shown instead of its move", ParamType.Text, "{self} can't move!"), P.yesNo("wake", "Ends when it takes damage", "sleep-like")) },
            { v, ctx ->
                val list = mutableListOf<Map<String, Any?>>(trigger("beforeMove", effects = listOf(effect("cancelAction", "message" to v.str("message").ifEmpty { null }))))
                if (v.bool("wake")) list += trigger("damaged", condition = "damage > 0", effects = listOf(effect("removeStatus", "target" to "self", "status" to ctx.entryId)))
                frag("triggers" to list)
            },
            { j, _ ->
                val t = triggersOf(j)
                if (t.isEmpty() || t.size > 2) null else mapOf("message" to (firstEffect(j)?.get("message") ?: ""), "wake" to (t.size == 2))
            },
            { it.entryId.isNotEmpty() },
        ),
        Recipe(
            "lose_turn_chance", "Chance to lose the turn", "Each turn, a chance that it cannot move (paralysis-like).",
            { _ -> listOf(P.percent("chance", "Chance to lose the turn (%)", "", 25.0), Param("message", "Message", "shown instead of its move", ParamType.Text, "{self} can't move!")) },
            { v, _ -> frag("triggers" to listOf(trigger("beforeMove", condition = "!cancelled", chance = v.num("chance"), effects = listOf(effect("cancelAction", "message" to v.str("message").ifEmpty { null }))))) },
            { j, _ -> triggersOf(j).firstOrNull()?.let { t -> (t["chance"] as? Number)?.let { mapOf("chance" to it.toDouble(), "message" to (firstEffect(j)?.get("message") ?: "")) } } },
        ),
        Recipe(
            "stat_change", "Change a stat while active", "+X% or -X% to a stat while it has the status.",
            { ctx -> listOf(P.choice("stat", "Stat", "", ctx.stageStats), P.number("percent", "Change (%)", "+50 raises it by half, -50 halves it", -50.0, min = -100.0, formula = false)) },
            { v, _ -> frag("statModifiers" to linkedMapOf<String, Any?>(v.str("stat") to StatMods.multiplier(v.num("percent")))) },
            { j, _ ->
                val mods = (j as? Map<*, *>)?.get("statModifiers") as? Map<*, *>
                val (stat, value) = mods?.entries?.singleOrNull() ?: return@Recipe null
                StatMods.percent(value)?.let { mapOf("stat" to stat.toString(), "percent" to it) }
            },
            { it.stageStats.isNotEmpty() },
        ),
        Recipe(
            "damage_taken", "Takes more or less damage", "Damage it takes from moves is multiplied (x0.5 halves it, x2 doubles it).",
            { _ -> listOf(P.number("value", "Multiplier", "", 0.5, min = 0.0, formula = false)) },
            { v, _ -> frag("triggers" to listOf(trigger("modifyDamageTaken", effects = listOf(effect("multiplyDamage", "value" to v.num("value")))))) },
            { j, _ -> if (triggersOf(j).firstOrNull()?.get("on") == "modifyDamageTaken") (firstEffect(j)?.get("value") as? Number)?.let { mapOf("value" to it.toDouble()) } else null },
        ),
        Recipe(
            "damage_dealt", "Deals more or less damage", "Damage it deals with moves is multiplied (x1.5 = 50% more).",
            { _ -> listOf(P.number("value", "Multiplier", "", 1.5, min = 0.0, formula = false)) },
            { v, _ -> frag("triggers" to listOf(trigger("modifyDamageDealt", effects = listOf(effect("multiplyDamage", "value" to v.num("value")))))) },
            { j, _ -> if (triggersOf(j).firstOrNull()?.get("on") == "modifyDamageDealt") (firstEffect(j)?.get("value") as? Number)?.let { mapOf("value" to it.toDouble()) } else null },
        ),
        Recipe(
            "shield", "Shield", "When it gets this status, it gains a shield worth a share of its max HP.",
            { _ -> listOf(P.percent("percent", "Shield (% of max HP)", "", 20.0, max = null)) },
            { v, ctx -> frag("triggers" to listOf(trigger("statusApplied", condition = "status == ${lit(ctx.entryId)}", effects = listOf(effect("shield", "target" to "self", "amount" to E2.maxHp(v.num("percent"))))))) },
            { j, _ -> maxHpPercent(firstEffect(j)?.get("amount"))?.let { mapOf("percent" to it) } },
            { it.entryId.isNotEmpty() },
        ),
        Recipe(
            "revive_on_death", "Revive on death", "If it faints while it has the status, it comes back with a share of its max HP.",
            { _ -> listOf(P.percent("percent", "HP when revived (% of max HP)", "", 30.0, min = 1.0)) },
            { v, _ -> frag("triggers" to listOf(trigger("fainted", effects = listOf(effect("revive", "target" to "self", "amount" to E2.maxHp(v.num("percent"))))))) },
            { j, _ -> maxHpPercent(firstEffect(j)?.get("amount"))?.let { mapOf("percent" to it) } },
        ),
    )

    // ------------------------------------------------------------------------------------------------------------
    // Abilities and items

    /** Ability and item presets (README "Abilities.json and Items.json"). */
    val holders: List<Recipe> = listOf(
        Recipe(
            "stat_boost", "+X% to a stat", "A stat is multiplied while it has the ability or item.",
            { ctx -> listOf(P.choice("stat", "Stat", "", ctx.stageStats), P.number("percent", "Change (%)", "+20 = 20% more", 20.0, min = -100.0, formula = false)) },
            { v, _ -> frag("statModifiers" to linkedMapOf<String, Any?>(v.str("stat") to StatMods.multiplier(v.num("percent")))) },
            { j, _ ->
                val mods = (j as? Map<*, *>)?.get("statModifiers") as? Map<*, *>
                val (stat, value) = mods?.entries?.singleOrNull() ?: return@Recipe null
                StatMods.percent(value)?.let { mapOf("stat" to stat.toString(), "percent" to it) }
            },
            { it.stageStats.isNotEmpty() },
        ),
        Recipe(
            "type_boost", "Boost moves of a type", "Its moves of a type deal more damage.",
            { ctx -> listOf(P.choice("type", "Type", "", ctx.types), P.percent("percent", "Boost (%)", "50 = 1.5x damage", 50.0, max = null), consumableParam()) },
            { v, _ -> frag("triggers" to listOf(consumable(trigger("modifyDamageDealt", condition = "move.type == ${lit(v.str("type"))}", effects = listOf(effect("multiplyDamage", "value" to StatMods.multiplier(v.num("percent"))))), v.bool("consumable")))) },
            { j, _ ->
                val t = triggersOf(j).firstOrNull() ?: return@Recipe null
                val type = Regex("^move\\.type == $STR$").matchEntire(t["condition"] as? String ?: "")?.groupValues?.get(1) ?: return@Recipe null
                val value = StatMods.percent(firstEffect(j)?.get("value")) ?: return@Recipe null
                mapOf("type" to unlit(type), "percent" to value, "consumable" to isConsumable(j))
            },
            { it.types.isNotEmpty() },
        ),
        Recipe(
            "type_immunity", "Immune to a type", "Moves of a type deal no damage to it.",
            { ctx -> listOf(P.choice("type", "Type", "", ctx.types), messageParam("It doesn't affect {self}!"), consumableParam()) },
            { v, _ -> frag("triggers" to listOf(consumable(trigger("modifyDamageTaken", condition = "move.type == ${lit(v.str("type"))}", message = v.str("message"), effects = listOf(effect("multiplyDamage", "value" to 0.0))), v.bool("consumable")))) },
            { j, _ ->
                val t = triggersOf(j).firstOrNull() ?: return@Recipe null
                val type = Regex("^move\\.type == $STR$").matchEntire(t["condition"] as? String ?: "")?.groupValues?.get(1) ?: return@Recipe null
                mapOf("type" to unlit(type), "message" to (t["message"] ?: ""), "consumable" to isConsumable(j))
            },
            { it.types.isNotEmpty() },
        ),
        Recipe(
            "heal_turn_end", "Heal at the end of each turn", "Recovers a share of its max HP at the end of its turns.",
            { _ -> listOf(P.percent("percent", "Heal (% of max HP)", "6.25 = 1/16", 6.25), messageParam("{self} restored a little HP!"), consumableParam()) },
            { v, _ -> frag("triggers" to listOf(consumable(trigger("turnEnd", condition = "self.hp < self.maxHp", message = v.str("message"), effects = listOf(effect("heal", "target" to "self", "amount" to E2.maxHp(v.num("percent"))))), v.bool("consumable")))) },
            { j, _ -> maxHpPercent(firstEffect(j)?.get("amount"))?.let { mapOf("percent" to it, "message" to (triggersOf(j).first()["message"] ?: ""), "consumable" to isConsumable(j)) } },
        ),
        Recipe(
            "contact_status", "Status to attackers on contact", "A chance to give a status to a monster that hits it with a contact move.",
            { ctx -> listOf(P.choice("status", "Status", "", ctx.statuses), P.percent("chance", "Chance (%)", "", 30.0), consumableParam()) },
            { v, _ -> frag("triggers" to listOf(consumable(trigger("damaged", condition = "hasFlag('contact')", chance = v.num("chance"), effects = listOf(effect("applyStatus", "target" to "other", "status" to v.str("status")))), v.bool("consumable")))) },
            { j, _ ->
                val t = triggersOf(j).firstOrNull() ?: return@Recipe null
                val status = firstEffect(j)?.get("status") as? String ?: return@Recipe null
                (t["chance"] as? Number)?.let { mapOf("status" to status, "chance" to it.toDouble(), "consumable" to isConsumable(j)) }
            },
            { it.statuses.isNotEmpty() },
        ),
        Recipe(
            "survive_lethal", "Survive a lethal hit once", "Once per battle, a hit that would knock it out leaves it with 1 HP.",
            { _ -> listOf(messageParam("{self} endured the hit!"), consumableParam()) },
            { v, _ -> frag("triggers" to listOf(consumable(trigger("modifyDamageTaken", condition = "damage >= self.hp", message = v.str("message"), limit = 1, effects = listOf(effect("multiplyDamage", "value" to "(self.hp - 1) / damage"))), v.bool("consumable")))) },
            { j, _ -> triggersOf(j).firstOrNull()?.let { mapOf("message" to (it["message"] ?: ""), "consumable" to isConsumable(j)) } },
        ),
    )

    // ------------------------------------------------------------------------------------------------------------
    // Fields

    /** Field presets (README "Fields.json"). */
    val fields: List<Recipe> = listOf(
        Recipe(
            "weather", "Weather", "Boosts one type, weakens another, and may hurt monsters without a type each turn.",
            { ctx ->
                listOf(
                    P.choice("boost", "Boosted type", "its moves deal more damage (empty = none)", ctx.types, default = null, optional = true),
                    P.percent("boostPercent", "Boost (%)", "50 = 1.5x", 50.0, max = null),
                    P.choice("weaken", "Weakened type", "its moves deal less damage (empty = none)", ctx.types, default = null, optional = true),
                    P.percent("weakenPercent", "Weakening (%)", "50 = 0.5x", 50.0),
                    P.percent("chip", "Damage each turn (% of max HP)", "0 = none", 0.0),
                    P.choice("safe", "Type not hurt", "monsters of this type take no damage each turn (empty = all are hurt)", ctx.types, default = null, optional = true),
                )
            },
            { v, _ ->
                val list = mutableListOf<Map<String, Any?>>()
                if (v.has("boost")) list += trigger("modifyDamageDealt", condition = "move.type == ${lit(v.str("boost"))}", effects = listOf(effect("multiplyDamage", "value" to StatMods.multiplier(v.num("boostPercent")))))
                if (v.has("weaken")) list += trigger("modifyDamageDealt", condition = "move.type == ${lit(v.str("weaken"))}", effects = listOf(effect("multiplyDamage", "value" to StatMods.multiplier(-v.num("weakenPercent")))))
                if (v.num("chip") > 0) {
                    list += trigger("turnEnd", condition = if (v.has("safe")) "!hasType(self, ${lit(v.str("safe"))})" else null, effects = listOf(effect("damage", "target" to "self", "amount" to E2.maxHp(v.num("chip")))))
                }
                frag("triggers" to list.ifEmpty { null })
            },
            { j, _ ->
                val out = linkedMapOf<String, Any?>("boostPercent" to 50.0, "weakenPercent" to 50.0, "chip" to 0.0)
                val list = triggersOf(j)
                var i = 0
                fun typeOf(t: Map<String, Any?>?) = Regex("^move\\.type == $STR$").matchEntire(t?.get("condition") as? String ?: "")?.groupValues?.get(1)?.let(::unlit)
                fun multiplier(t: Map<String, Any?>?) = StatMods.percent(effectsOf(t).firstOrNull()?.get("value"))
                val first = list.getOrNull(i)
                if (first?.get("on") == "modifyDamageDealt" && (multiplier(first) ?: 0.0) >= 0) {
                    out["boost"] = typeOf(first); out["boostPercent"] = multiplier(first); i++
                }
                val second = list.getOrNull(i)
                if (second?.get("on") == "modifyDamageDealt") {
                    out["weaken"] = typeOf(second); out["weakenPercent"] = -(multiplier(second) ?: 0.0); i++
                }
                val third = list.getOrNull(i)
                if (third?.get("on") == "turnEnd") {
                    out["chip"] = maxHpPercent(effectsOf(third).firstOrNull()?.get("amount"))
                    Regex("^!hasType\\(self, $STR\\)$").matchEntire(third["condition"] as? String ?: "")?.let { out["safe"] = unlit(it.groupValues[1]) }
                }
                out
            },
            { it.types.isNotEmpty() },
        ),
        Recipe(
            "screen", "Screen", "A side field that reduces the damage its monsters take from moves of a category.",
            { ctx -> listOf(P.choice("category", "Category", "", ctx.categories.filter { it.value in ctx.damagingCategories }), P.percent("percent", "Damage reduction (%)", "50 = half damage", 50.0)) },
            { v, _ -> frag("scope" to "side", "triggers" to listOf(trigger("modifyDamageTaken", condition = "move.category == ${lit(v.str("category"))}", effects = listOf(effect("multiplyDamage", "value" to StatMods.multiplier(-v.num("percent"))))))) },
            { j, _ ->
                val t = triggersOf(j).firstOrNull() ?: return@Recipe null
                val category = Regex("^move\\.category == $STR$").matchEntire(t["condition"] as? String ?: "")?.groupValues?.get(1) ?: return@Recipe null
                StatMods.percent(firstEffect(j)?.get("value"))?.let { mapOf("category" to unlit(category), "percent" to -it) }
            },
            { it.damagingCategories.isNotEmpty() },
        ),
        Recipe(
            "speed_boost", "Speed boost on a side", "A side field that multiplies the speed of its monsters.",
            { _ -> listOf(P.number("percent", "Speed change (%)", "+100 doubles it", 100.0, min = -100.0, formula = false)) },
            { v, ctx -> frag("scope" to "side", "statModifiers" to linkedMapOf<String, Any?>(ctx.speedStat to StatMods.multiplier(v.num("percent")))) },
            { j, ctx -> StatMods.percent(((j as? Map<*, *>)?.get("statModifiers") as? Map<*, *>)?.get(ctx.speedStat))?.let { mapOf("percent" to it) } },
        ),
        Recipe(
            "hazard", "Entry hazard", "A side field that hurts every monster that enters that side (optionally scaled by a type's effectiveness).",
            { ctx -> listOf(P.percent("percent", "Damage (% of max HP)", "12.5 = 1/8", 12.5), P.choice("type", "Scale by the effectiveness of", "e.g. rock: weak monsters take more (empty = no scaling)", ctx.types, default = null, optional = true)) },
            { v, _ ->
                val amount = if (v.has("type")) "self.maxHp * typeMultiplier(${lit(v.str("type"))}, self) * ${JsonTools.fraction(v.num("percent"))}" else E2.maxHp(v.num("percent"))
                frag("scope" to "side", "triggers" to listOf(trigger("switchIn", effects = listOf(effect("damage", "target" to "self", "amount" to amount)))))
            },
            { j, _ ->
                val amount = firstEffect(j)?.get("amount") as? String ?: return@Recipe null
                maxHpPercent(amount)?.let { return@Recipe mapOf("percent" to it) }
                val m = Regex("^self\\.maxHp \\* typeMultiplier\\($STR, self\\) \\* $NUM$").matchEntire(amount) ?: return@Recipe null
                mapOf("type" to unlit(m.groupValues[1]), "percent" to JsonTools.round(m.groupValues[2].toDouble() * 100))
            },
        ),
        Recipe(
            "reverse_order", "Reverse turn order", "Slower monsters act first (speed-inversion). Classic-style rounds only.",
            { _ -> emptyList() },
            { _, _ -> frag("rules" to linkedMapOf<String, Any?>("reverseSpeed" to true)) },
            { j, _ -> if (((j as? Map<*, *>)?.get("rules") as? Map<*, *>)?.get("reverseSpeed") == true) emptyMap() else null },
            { !it.actionBar },
        ),
        Recipe(
            "block_statuses", "Block statuses from enemies", "A side field: enemies cannot give its monsters statuses of a kind (Safeguard-like).",
            { _ -> listOf(P.choice("kind", "Kind", "which statuses are blocked", listOf(Option("major", "major statuses"), Option("debuff", "debuffs"), Option("volatile", "volatile statuses"), Option("any", "every status")))) },
            { v, _ ->
                val kind = v.str("kind")
                val rule = if (kind == "any") "!sameSide(self, other)" else "statusKind == ${lit(kind)} && !sameSide(self, other)"
                frag("scope" to "side", "rules" to linkedMapOf<String, Any?>("blockStatus" to rule))
            },
            { j, _ ->
                val rule = ((j as? Map<*, *>)?.get("rules") as? Map<*, *>)?.get("blockStatus") as? String ?: return@Recipe null
                if (rule == "!sameSide(self, other)") return@Recipe mapOf("kind" to "any")
                Regex("^statusKind == $STR && !sameSide\\(self, other\\)$").matchEntire(rule)?.let { mapOf("kind" to unlit(it.groupValues[1])) }
            },
        ),
        Recipe(
            "block_stat_drops", "Block stat drops from enemies", "A side field: enemies cannot lower its monsters' stats (Mist-like).",
            { _ -> emptyList() },
            { _, _ -> frag("scope" to "side", "rules" to linkedMapOf<String, Any?>("blockStatStage" to "stages < 0 && !sameSide(self, other)")) },
            { j, _ -> if (((j as? Map<*, *>)?.get("rules") as? Map<*, *>)?.get("blockStatStage") == "stages < 0 && !sameSide(self, other)") emptyMap() else null },
        ),
        Recipe(
            "block_moves", "Block moves of a category", "Moves of a category cannot be used while the field is active.",
            { ctx -> listOf(P.choice("category", "Category", "", ctx.categories)) },
            { v, _ -> frag("rules" to linkedMapOf<String, Any?>("blockMove" to "move.category == ${lit(v.str("category"))}")) },
            { j, _ ->
                val rule = ((j as? Map<*, *>)?.get("rules") as? Map<*, *>)?.get("blockMove") as? String ?: return@Recipe null
                Regex("^move\\.category == $STR$").matchEntire(rule)?.let { mapOf("category" to unlit(it.groupValues[1])) }
            },
            { it.categories.isNotEmpty() },
        ),
    )

    /** The fragment [recipe] builds from [values] (to pass to [apply]). */
    fun fragment(recipe: Recipe, values: Map<String, Any?>, ctx: RecipeContext): Map<String, Any?> =
        (recipe.build(values, ctx) as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()

    /** The preset of [entry] (its statModifiers, triggers, rules and scope match a preset exactly), or null. */
    fun recognise(entry: Map<String, Any?>, ctx: RecipeContext): RecipeUse? {
        val fragment = entry.filterKeys { it in FRAGMENT_KEYS }.filterValues { v ->
            v != null && v != "global" && !(v is Map<*, *> && v.isEmpty()) && !(v is List<*> && v.isEmpty())
        }
        for (r in forOwner(ctx.owner)) r.recognise(fragment, ctx)?.let { return RecipeUse(r.id, it) }
        return null
    }

    private val FRAGMENT_KEYS = setOf("statModifiers", "triggers", "rules", "scope")

    /** The presets for an owner kind (moves have none: use the move form). */
    fun forOwner(owner: OwnerKind): List<Recipe> = when (owner) {
        OwnerKind.STATUS -> statuses
        OwnerKind.ABILITY, OwnerKind.ITEM -> holders
        OwnerKind.FIELD -> fields
        OwnerKind.MOVE -> emptyList()
    }

    /**
     * Merges [fragment] (a preset's JSON) into [entry]: statModifiers and rules are merged key by key, triggers are
     * appended, other keys (scope) replace the entry's.
     */
    fun apply(entry: MutableMap<String, Any?>, fragment: Map<String, Any?>, order: List<String> = FieldForms.ORDER) {
        for ((key, value) in fragment) {
            when (key) {
                "statModifiers", "rules" -> {
                    val target = JsonTools.objectAt(entry, key, order)
                    (value as Map<*, *>).forEach { (k, v) -> target[k.toString()] = JsonTools.copy(v) }
                }
                "triggers" -> JsonTools.listAt(entry, key, order).addAll((value as List<*>).map { JsonTools.copy(it) })
                else -> JsonTools.putOrdered(entry, key, JsonTools.copy(value), order)
            }
        }
    }
}

/** Formula text helpers shared by presets. */
private object E2 {
    fun maxHp(percent: Double) = "self.maxHp * ${JsonTools.fraction(percent)}"
}
