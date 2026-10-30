package creator

/** How turns work (Rules.json "turnSystem"). */
enum class TurnStyle(val id: String, val label: String, val help: String) {
    SPEED_ORDER("speed_order", "Pokemon-like rounds", "Everybody picks an action, then actions run by priority and speed."),
    ACTION_BAR("action_bar", "Gacha action bar", "Speed fills an action bar; whoever fills it first acts."),
}

/** A starting list of stats. */
enum class StatPreset(val label: String, val help: String) {
    POKEMON("Pokemon", "HP, Attack, Defense, Sp. Atk, Sp. Def, Speed"),
    GACHA("Gacha", "HP, Attack, Defense, Speed, Crit Rate, Crit Damage, Resistance, Accuracy"),
    SIMPLE("Simple", "HP, Attack, Defense, Speed"),
    CUSTOM("Custom", "your own list of stats"),
}

/** One stat of the template. [grows]: its value grows with the level (otherwise it is the base value, plus stat points). */
data class StatAnswer(val id: String, val name: String, val grows: Boolean = true)

/** How stats grow with the level (Rules.json "statFormulas"). */
enum class Growth(val label: String, val help: String) {
    POKEMON("Pokemon formula", "base, level and stat points like EVs (at level 100 a stat is about 2 x base + 36)"),
    LINEAR("Linear by level", "half the base value at level 0, the full base value at the maximum level"),
    FLAT("Flat", "the base value, whatever the level"),
}

/** How damage is computed (Rules.json "damage"). */
enum class DamagePreset(val label: String, val help: String) {
    POKEMON("Pokemon", "the official formula: level, power, attack / defense, random 0.85-1"),
    GACHA("Gacha", "power x 1000 / (1140 + 3.5 x defense); a move's power is usually a share of the user's Attack (360% of ATK)"),
    SIMPLE("Simple", "power x attack / defense / 2"),
}

/** Move categories (Rules.json "categories"). */
enum class CategoryStyle(val label: String, val help: String) {
    PHYSICAL_SPECIAL("Physical / special / status", "physical moves use Attack and Defense, special moves Sp. Atk and Sp. Def"),
    ATTACK_SUPPORT("Attack / support", "attacks use Attack and Defense, support moves deal no damage"),
}

/** Critical hits. */
enum class CritMode(val label: String, val help: String) {
    NONE("No critical hits", ""),
    FIXED("Fixed chance", "the same chance and multiplier for everybody"),
    FROM_STATS("From stats", "chance = Crit Rate %, multiplier = 1 + Crit Damage %"),
}

/** What limits how often a move can be used. */
enum class MoveLimit(val label: String, val help: String) {
    PP("PP", "each move can be used a number of times per battle"),
    COOLDOWNS("Cooldowns", "after use, a move waits some turns"),
    NONE("Neither", "moves can always be used"),
}

/** Action bar fill rate. */
enum class BarFill(val formula: String, val label: String) {
    GACHA("speed * 0.07", "speed x 0.07 (Summoners War-like)"),
    SPEED("speed", "speed"),
}

/** Stat stages. */
enum class StageStyle(val label: String) {
    POKEMON("Pokemon (-6 to +6)"),
    NONE("No stat stages"),
}

/** Parts of Rules.json the template generates, so only the changed parts are generated again. */
enum class RulePart(val label: String, val keys: List<String>) {
    BASICS("Name, turn system and moves", listOf("name", "description", "turnSystem", "maxMoves", "heldItems")),
    TEAM("Team", listOf("team", "switching")),
    LEVELS("Levels", listOf("level")),
    STATS("Stats", listOf("stats", "hpStat", "speedStat")),
    GROWTH("Stat growth and stat points", listOf("statFormulas", "statPoints")),
    STAGES("Stat stages", listOf("statStages")),
    CATEGORIES("Move categories", listOf("categories")),
    DAMAGE("Damage", listOf("damage", "minDamage")),
    ACCURACY("Accuracy", listOf("hitChance")),
    CRITS("Critical hits", listOf("critChance", "critMultiplier")),
    HIT_TYPES("Glancing and crushing hits", listOf("hitTypes")),
    MOVE_LIMITS("PP, cooldowns and fallback move", listOf("pp", "cooldowns", "fallbackMove")),
    ACTION_BAR("Action bar", listOf("actionBar")),
    TURN_LIMIT("Turn limit", listOf("turnLimit")),
}

/** The answers to the rule template's questions. The defaults make a Pokemon-like setup (see [forStyle]). */
data class RuleAnswers(
    val name: String = "",
    val description: String = "",
    val turnSystem: TurnStyle = TurnStyle.SPEED_ORDER,
    val teamMin: Int = 1,
    val teamMax: Int = 6,
    val active: Int = 1,
    val switching: Boolean = true,
    val speciesClause: Boolean = true,
    val itemClause: Boolean = false,
    /** false = "no levels" (every monster is level 1). */
    val levels: Boolean = true,
    val levelMin: Int = 1,
    val levelMax: Int = 100,
    val levelDefault: Int = 50,
    val maxMoves: Int = 4,
    val heldItems: Boolean = true,
    val statPreset: StatPreset = StatPreset.POKEMON,
    val stats: List<StatAnswer> = RuleTemplate.presetStats(StatPreset.POKEMON),
    val hpStat: String = "hp",
    val speedStat: String = "spe",
    val growth: Growth = Growth.POKEMON,
    val statPoints: Boolean = true,
    val statPointsTotal: Int = 510,
    val statPointsMax: Int = 252,
    val stages: StageStyle = StageStyle.POKEMON,
    val categories: CategoryStyle = CategoryStyle.PHYSICAL_SPECIAL,
    val attackStat: String = "atk",
    val defenseStat: String = "def",
    val specialAttackStat: String = "spa",
    val specialDefenseStat: String = "spd",
    val damage: DamagePreset = DamagePreset.POKEMON,
    /** Type effectiveness multiplies the damage. */
    val typeEffectiveness: Boolean = true,
    /** Same-type bonus (1 = none). */
    val sameTypeBonus: Double = 1.5,
    val randomSpread: Boolean = true,
    val movesCanMiss: Boolean = true,
    val crits: CritMode = CritMode.FIXED,
    val critChancePercent: Double = 6.25,
    val critMultiplier: Double = 1.5,
    val critRateStat: String = "crate",
    val critDamageStat: String = "cdmg",
    /** Glancing and crushing hits (gacha). */
    val hitTypes: Boolean = false,
    val moveLimit: MoveLimit = MoveLimit.PP,
    /** With PP: the id of the fallback move the template creates (a typeless Struggle), or null for none. */
    val fallbackMove: String? = "struggle",
    val barFill: BarFill = BarFill.GACHA,
    /** Action bars start with a random 0-15% fill (otherwise empty). */
    val barRandomStart: Boolean = false,
    /** 0 = no limit. */
    val turnLimit: Int = 300,
) {
    /** These answers with the stats (and the stat ids used by categories, crits, HP and speed) of [preset]. */
    fun withStatPreset(preset: StatPreset): RuleAnswers {
        if (preset == StatPreset.CUSTOM) return copy(statPreset = preset)
        val pokemon = preset == StatPreset.POKEMON
        return copy(
            statPreset = preset,
            stats = RuleTemplate.presetStats(preset),
            hpStat = "hp",
            speedStat = if (preset == StatPreset.GACHA) "spd" else "spe",
            categories = if (pokemon) CategoryStyle.PHYSICAL_SPECIAL else CategoryStyle.ATTACK_SUPPORT,
            attackStat = "atk",
            defenseStat = "def",
            specialAttackStat = "spa",
            specialDefenseStat = "spd",
            crits = if (preset == StatPreset.GACHA) CritMode.FROM_STATS else if (crits == CritMode.FROM_STATS) CritMode.FIXED else crits,
        )
    }

    companion object {
        /** Sensible defaults for a style: Pokemon-like singles, or a 4v4 gacha setup with cooldowns. */
        fun forStyle(style: TurnStyle): RuleAnswers = when (style) {
            TurnStyle.SPEED_ORDER -> RuleAnswers()
            TurnStyle.ACTION_BAR -> RuleAnswers(
                turnSystem = TurnStyle.ACTION_BAR, teamMin = 1, teamMax = 5, active = 4, switching = false,
                levelMin = 1, levelMax = 40, levelDefault = 40, maxMoves = 3, growth = Growth.LINEAR, statPoints = false,
                stages = StageStyle.NONE, damage = DamagePreset.GACHA, sameTypeBonus = 1.0, movesCanMiss = false,
                hitTypes = true, moveLimit = MoveLimit.COOLDOWNS, fallbackMove = null, turnLimit = 300,
            ).withStatPreset(StatPreset.GACHA)
        }
    }
}

/** What [RuleTemplate.recognise] found: the answers that describe a Rules.json and the parts edited by hand. */
data class RuleRecognition(val answers: RuleAnswers, val custom: Set<RulePart>)

/**
 * Builds Rules.json from [RuleAnswers] (every formula is generated, never typed), recognises the answers of an
 * existing Rules.json, and regenerates only some parts of it so hand-edited formulas are kept.
 */
object RuleTemplate {
    /** Canonical key order of Rules.json (README). */
    val KEY_ORDER = listOf(
        "name", "description", "turnSystem", "team", "switching", "level", "maxMoves", "heldItems", "stats", "hpStat",
        "speedStat", "statFormulas", "statPoints", "statStages", "categories", "damage", "hitChance", "critChance",
        "critMultiplier", "hitTypes", "minDamage", "pp", "cooldowns", "fallbackMove", "actionBar", "turnLimit", "startFields",
    )

    const val DEFAULT_STAGE_MULTIPLIER = "stage >= 0 ? (2 + stage) / 2 : 2 / (2 - stage)"

    /** The stats of a preset. */
    fun presetStats(preset: StatPreset): List<StatAnswer> = when (preset) {
        StatPreset.POKEMON, StatPreset.CUSTOM -> listOf(
            StatAnswer("hp", "HP"), StatAnswer("atk", "Attack"), StatAnswer("def", "Defense"),
            StatAnswer("spa", "Sp. Atk"), StatAnswer("spd", "Sp. Def"), StatAnswer("spe", "Speed"),
        )
        StatPreset.GACHA -> listOf(
            StatAnswer("hp", "HP"), StatAnswer("atk", "Attack"), StatAnswer("def", "Defense"), StatAnswer("spd", "Speed", false),
            StatAnswer("crate", "Crit Rate", false), StatAnswer("cdmg", "Crit Damage", false),
            StatAnswer("res", "Resistance", false), StatAnswer("acc", "Accuracy", false),
        )
        StatPreset.SIMPLE -> listOf(StatAnswer("hp", "HP"), StatAnswer("atk", "Attack"), StatAnswer("def", "Defense"), StatAnswer("spe", "Speed"))
    }

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Problems with [a] in plain words (empty = the answers make a valid Rules.json). */
    fun problems(a: RuleAnswers): List<String> {
        val out = mutableListOf<String>()
        val ids = a.stats.map { it.id }
        if (a.stats.isEmpty()) out += "There must be at least one stat."
        a.stats.filter { !IDENTIFIER.matches(it.id) }.forEach { out += "Stat id '${it.id}' must be a simple name (letters, digits and _)." }
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { out += "Stat id '$it' is used twice." }
        if (a.hpStat !in ids) out += "The HP stat '${a.hpStat}' is not one of the stats."
        if (a.speedStat !in ids) out += "The speed stat '${a.speedStat}' is not one of the stats."
        val categoryStats = if (a.categories == CategoryStyle.PHYSICAL_SPECIAL) {
            listOf(a.attackStat, a.defenseStat, a.specialAttackStat, a.specialDefenseStat)
        } else {
            listOf(a.attackStat, a.defenseStat)
        }
        categoryStats.filter { it !in ids }.distinct().forEach { out += "The move categories use the stat '$it', which is not one of the stats." }
        if (a.crits == CritMode.FROM_STATS) {
            listOf(a.critRateStat, a.critDamageStat).filter { it !in ids }.forEach { out += "Critical hits from stats need the stat '$it'." }
        }
        if (a.teamMin < 1) out += "Teams need at least 1 monster."
        if (a.teamMax < a.teamMin) out += "The maximum team size is smaller than the minimum."
        if (a.active < 1) out += "At least 1 monster per side must be on the field."
        if (a.levels) {
            if (a.levelMin < 0) out += "The minimum level must not be negative."
            if (a.levelMax < a.levelMin) out += "The maximum level is smaller than the minimum."
            if (a.levelDefault !in a.levelMin..a.levelMax) out += "The default level must be between the minimum and the maximum."
        }
        if (a.maxMoves < 1) out += "Monsters need at least 1 move slot."
        if (a.statPoints && (a.statPointsTotal < 1 || a.statPointsMax < 1)) out += "Stat points need a total and a maximum per stat of at least 1."
        if (a.critChancePercent < 0 || a.critChancePercent > 100) out += "The critical hit chance must be between 0 and 100%."
        if (a.critMultiplier < 0) out += "The critical hit multiplier must not be negative."
        if (a.sameTypeBonus < 0) out += "The same-type bonus must not be negative."
        if (a.turnLimit < 0) out += "The turn limit must not be negative."
        if (a.fallbackMove != null && a.moveLimit == MoveLimit.PP && !IDENTIFIER.matches(a.fallbackMove)) out += "The fallback move id must be a simple name."
        return out
    }

    // ------------------------------------------------------------------------------------------------------------
    // Generated formulas

    private fun levelFactor(a: RuleAnswers): String = "level / ${JsonTools.num(2.0 * (if (a.levels) a.levelMax else 1))}"

    /** Formula of a stat that grows with the level. */
    private fun growFormula(a: RuleAnswers, hp: Boolean): String = when (a.growth) {
        Growth.POKEMON -> {
            val inner = if (a.statPoints) "2 * base + 31 + floor(points / 4)" else "2 * base + 31"
            "floor(($inner) * level / 100) + " + if (hp) "level + 10" else "5"
        }
        Growth.LINEAR -> "floor(base * (0.5 + ${levelFactor(a)}))" + if (a.statPoints) " + points" else ""
        Growth.FLAT -> flatFormula(a)
    }

    private fun flatFormula(a: RuleAnswers): String = if (a.statPoints) "base + points" else "base"

    private fun statFormulas(a: RuleAnswers): MutableMap<String, Any?> {
        val each = a.stats.associate { s ->
            s.id to if (s.grows) growFormula(a, s.id == a.hpStat && a.growth == Growth.POKEMON) else flatFormula(a)
        }
        // "default" = the formula most stats use (ties: the growing one).
        val counts = each.values.groupingBy { it }.eachCount()
        val grow = growFormula(a, false)
        val default = counts.maxByOrNull { (f, n) -> n * 2 + if (f == grow) 1 else 0 }?.key ?: grow
        val result = LinkedHashMap<String, Any?>()
        for ((id, f) in each) if (f != default) result[id] = f
        result["default"] = default
        return result
    }

    private fun damageFormula(a: RuleAnswers): String {
        val factors = mutableListOf(
            when (a.damage) {
                DamagePreset.POKEMON -> "floor(floor(floor(2 * user.level / 5 + 2) * move.power * attack / defense) / 50 + 2) * (targets > 1 ? 0.75 : 1)"
                DamagePreset.GACHA -> "move.power * 1000 / (1140 + 3.5 * defense)"
                DamagePreset.SIMPLE -> "move.power * attack / defense / 2"
            },
        )
        if (a.sameTypeBonus != 1.0) factors += "(sameType ? ${JsonTools.num(a.sameTypeBonus)} : 1)"
        if (a.typeEffectiveness) factors += "effectiveness"
        if (a.crits != CritMode.NONE) factors += "crit"
        if (a.randomSpread) {
            factors += when (a.damage) {
                DamagePreset.POKEMON -> "random(0.85, 1)"
                DamagePreset.GACHA -> "random(0.95, 1.05)"
                DamagePreset.SIMPLE -> "random(0.9, 1.1)"
            }
        }
        return factors.joinToString(" * ")
    }

    private fun hitTypes(): List<Any?> = listOf(
        linkedMapOf("id" to "glancing", "chance" to "effectiveness < 1 ? 50 : 0", "multiplier" to 0.7, "message" to "Glancing hit...", "cancelsCrit" to true),
        linkedMapOf("id" to "crushing", "chance" to "!isCrit && effectiveness > 1 ? 20 : 0", "multiplier" to 1.3, "message" to "Crushing hit!"),
    )

    // ------------------------------------------------------------------------------------------------------------
    // Building

    /** Rules.json for [a]. */
    fun build(a: RuleAnswers): MutableMap<String, Any?> {
        val r = LinkedHashMap<String, Any?>()
        r["name"] = a.name
        if (a.description.isNotEmpty()) r["description"] = a.description
        r["turnSystem"] = a.turnSystem.id
        r["team"] = linkedMapOf<String, Any?>(
            "min" to a.teamMin.toDouble(), "max" to a.teamMax.toDouble(), "active" to a.active.toDouble(),
            "speciesClause" to a.speciesClause, "itemClause" to a.itemClause,
        )
        r["switching"] = a.switching
        r["level"] = if (a.levels) {
            linkedMapOf<String, Any?>("min" to a.levelMin.toDouble(), "max" to a.levelMax.toDouble(), "default" to a.levelDefault.toDouble())
        } else {
            linkedMapOf<String, Any?>("min" to 1.0, "max" to 1.0, "default" to 1.0)
        }
        r["maxMoves"] = a.maxMoves.toDouble()
        r["heldItems"] = a.heldItems
        r["stats"] = a.stats.map { linkedMapOf<String, Any?>("id" to it.id, "name" to it.name) }
        r["hpStat"] = a.hpStat
        r["speedStat"] = a.speedStat
        r["statFormulas"] = statFormulas(a)
        if (a.statPoints) r["statPoints"] = linkedMapOf<String, Any?>("total" to a.statPointsTotal.toDouble(), "maxPerStat" to a.statPointsMax.toDouble())
        r["statStages"] = when (a.stages) {
            StageStyle.POKEMON -> linkedMapOf<String, Any?>("min" to -6.0, "max" to 6.0, "multiplier" to DEFAULT_STAGE_MULTIPLIER)
            StageStyle.NONE -> linkedMapOf<String, Any?>("min" to 0.0, "max" to 0.0, "multiplier" to "1")
        }
        r["categories"] = when (a.categories) {
            CategoryStyle.PHYSICAL_SPECIAL -> linkedMapOf<String, Any?>(
                "physical" to linkedMapOf<String, Any?>("attack" to a.attackStat, "defense" to a.defenseStat),
                "special" to linkedMapOf<String, Any?>("attack" to a.specialAttackStat, "defense" to a.specialDefenseStat),
                "status" to LinkedHashMap<String, Any?>(),
            )
            CategoryStyle.ATTACK_SUPPORT -> linkedMapOf<String, Any?>(
                "attack" to linkedMapOf<String, Any?>("attack" to a.attackStat, "defense" to a.defenseStat),
                "support" to LinkedHashMap<String, Any?>(),
            )
        }
        r["damage"] = damageFormula(a)
        r["hitChance"] = if (a.movesCanMiss) "move.accuracy / 100" else "1"
        when (a.crits) {
            CritMode.NONE -> {
                r["critChance"] = 0.0
                r["critMultiplier"] = 1.0
            }
            CritMode.FIXED -> {
                r["critChance"] = JsonTools.round(a.critChancePercent / 100)
                r["critMultiplier"] = a.critMultiplier
            }
            CritMode.FROM_STATS -> {
                r["critChance"] = "clamp(user.${a.critRateStat} / 100, 0, 1)"
                r["critMultiplier"] = "1 + user.${a.critDamageStat} / 100"
            }
        }
        if (a.hitTypes) r["hitTypes"] = hitTypes()
        r["minDamage"] = 1.0
        r["pp"] = a.moveLimit == MoveLimit.PP
        r["cooldowns"] = a.moveLimit == MoveLimit.COOLDOWNS
        if (a.moveLimit == MoveLimit.PP && a.fallbackMove != null) r["fallbackMove"] = a.fallbackMove
        if (a.turnSystem == TurnStyle.ACTION_BAR) {
            r["actionBar"] = linkedMapOf<String, Any?>("size" to 100.0, "fill" to a.barFill.formula, "start" to if (a.barRandomStart) "random(0, 15)" else "0")
        }
        r["turnLimit"] = a.turnLimit.toDouble()
        return r
    }

    /**
     * Entries the template adds to a new setup: with PP and a fallback move, a typeless Struggle-like move that
     * never misses and costs the user 1/4 of its max HP.
     */
    fun starterEntries(a: RuleAnswers): Map<EntryKind, List<Map<String, Any?>>> {
        val fallback = a.fallbackMove
        if (a.moveLimit != MoveLimit.PP || fallback == null) return emptyMap()
        val category = if (a.categories == CategoryStyle.PHYSICAL_SPECIAL) "physical" else "attack"
        val power: Any = if (a.damage == DamagePreset.GACHA) "user.${a.attackStat} * 1" else 50.0
        val move = linkedMapOf<String, Any?>(
            "id" to fallback,
            "name" to "Struggle",
            "description" to "Used only when no other move is usable. The user loses 1/4 of its max HP.",
            "category" to category,
            "power" to power,
            "pp" to 0.0,
            "target" to "enemy",
            "triggers" to listOf(linkedMapOf(
                "on" to "hit",
                "condition" to "damage > 0",
                "message" to "{user} is damaged by the recoil!",
                "effects" to listOf(linkedMapOf("type" to "damage", "target" to "user", "amount" to "user.maxHp * 0.25")),
            )),
        )
        return mapOf(EntryKind.MOVE to listOf(move))
    }

    // ------------------------------------------------------------------------------------------------------------
    // Partial regeneration

    /** The parts whose generated JSON differs between [old] and [new] answers. */
    fun changedParts(old: RuleAnswers, new: RuleAnswers): Set<RulePart> {
        val a = build(old)
        val b = build(new)
        return RulePart.entries.filter { part -> part.keys.any { !JsonTools.equal(a[it], b[it], ordered = false) } }.toSet()
    }

    /**
     * [current] with the [parts] regenerated from [answers] (every other key, unknown keys and startFields included,
     * is kept as it is). Keys a part no longer generates are removed.
     */
    fun apply(current: Map<String, Any?>, answers: RuleAnswers, parts: Set<RulePart>): MutableMap<String, Any?> {
        val result = JsonTools.copyMap(current)
        val generated = build(answers)
        for (part in parts) {
            for (key in part.keys) {
                val value = generated[key]
                if (value == null) result.remove(key)
                else if (!JsonTools.equal(result[key], value, ordered = false)) JsonTools.putOrdered(result, key, value, KEY_ORDER)
            }
        }
        return result
    }

    // ------------------------------------------------------------------------------------------------------------
    // Recognition

    /**
     * The answers that describe [rules] (best effort) and the parts that the answers do not generate exactly
     * (hand-edited: regenerating them would replace what is there, so a UI should ask first).
     */
    fun recognise(rules: Map<String, Any?>): RuleRecognition {
        var a = RuleAnswers(
            name = rules.str("name") ?: "",
            description = rules.str("description") ?: "",
            turnSystem = TurnStyle.entries.firstOrNull { it.id.equals(rules.str("turnSystem"), true) } ?: TurnStyle.SPEED_ORDER,
        )
        val team = rules["team"] as? Map<*, *>
        fun int(m: Map<*, *>?, key: String, default: Int) = (m?.get(key) as? Number)?.toInt() ?: default
        a = a.copy(
            teamMin = int(team, "min", 1), teamMax = int(team, "max", 6), active = int(team, "active", 1),
            speciesClause = team?.get("speciesClause") == true, itemClause = team?.get("itemClause") == true,
            switching = rules["switching"] != false,
            maxMoves = int(rules, "maxMoves", 4), heldItems = rules["heldItems"] != false,
        )
        val level = rules["level"] as? Map<*, *>
        val lMin = int(level, "min", 1)
        val lMax = int(level, "max", 100)
        val lDef = int(level, "default", 50)
        a = if (lMin == 1 && lMax == 1 && lDef == 1) a.copy(levels = false, levelMin = 1, levelMax = 100, levelDefault = 50)
        else a.copy(levelMin = lMin, levelMax = lMax, levelDefault = lDef)

        val stats = (rules["stats"] as? List<*>).orEmpty().mapNotNull { s ->
            when (s) {
                is String -> StatAnswer(s, s)
                is Map<*, *> -> (s["id"] as? String)?.let { StatAnswer(it, s["name"] as? String ?: it) }
                else -> null
            }
        }
        a = a.copy(stats = stats, hpStat = rules.str("hpStat") ?: "hp", speedStat = rules.str("speedStat") ?: "spe")
        a = a.copy(statPreset = StatPreset.entries.firstOrNull { p ->
            p != StatPreset.CUSTOM && presetStats(p).map { it.id to it.name } == stats.map { it.id to it.name }
        } ?: StatPreset.CUSTOM)

        val points = rules["statPoints"] as? Map<*, *>
        a = a.copy(
            statPoints = points != null,
            statPointsTotal = int(points, "total", 510),
            statPointsMax = int(points, "maxPerStat", int(points, "total", 252)),
        )
        a = recogniseGrowth(rules, a)

        val stages = rules["statStages"] as? Map<*, *>
        a = a.copy(stages = if (stages != null && int(stages, "min", -6) == 0 && int(stages, "max", 6) == 0) StageStyle.NONE else StageStyle.POKEMON)

        val categories = rules["categories"] as? Map<*, *>
        if (categories != null && categories.keys == setOf("physical", "special", "status")) {
            val ph = categories["physical"] as? Map<*, *>
            val sp = categories["special"] as? Map<*, *>
            a = a.copy(
                categories = CategoryStyle.PHYSICAL_SPECIAL,
                attackStat = ph?.get("attack") as? String ?: "atk", defenseStat = ph?.get("defense") as? String ?: "def",
                specialAttackStat = sp?.get("attack") as? String ?: "spa", specialDefenseStat = sp?.get("defense") as? String ?: "spd",
            )
        } else if (categories != null) {
            val at = categories["attack"] as? Map<*, *>
            a = a.copy(
                categories = CategoryStyle.ATTACK_SUPPORT,
                attackStat = at?.get("attack") as? String ?: "atk", defenseStat = at?.get("defense") as? String ?: "def",
            )
        }

        when (val chance = rules["critChance"]) {
            is String -> Regex("^clamp\\(user\\.(\\w+) / 100, 0, 1\\)$").matchEntire(chance)?.let { m ->
                val dmg = Regex("^1 \\+ user\\.(\\w+) / 100$").matchEntire(rules["critMultiplier"] as? String ?: "")
                a = a.copy(crits = CritMode.FROM_STATS, critRateStat = m.groupValues[1], critDamageStat = dmg?.groupValues?.get(1) ?: "cdmg")
            }
            is Number -> a = if (chance.toDouble() <= 0.0) a.copy(crits = CritMode.NONE)
            else a.copy(crits = CritMode.FIXED, critChancePercent = JsonTools.round(chance.toDouble() * 100),
                critMultiplier = (rules["critMultiplier"] as? Number)?.toDouble() ?: 1.5)
            null -> a = a.copy(crits = CritMode.NONE)
        }
        a = a.copy(hitTypes = rules["hitTypes"] != null)
        a = a.copy(movesCanMiss = !(rules["hitChance"] == "1" || (rules["hitChance"] as? Number)?.toDouble() == 1.0))
        a = recogniseDamage(rules, a)

        val pp = rules["pp"] != false
        val cooldowns = rules["cooldowns"] == true
        a = a.copy(
            moveLimit = if (pp) MoveLimit.PP else if (cooldowns) MoveLimit.COOLDOWNS else MoveLimit.NONE,
            fallbackMove = rules.str("fallbackMove"),
        )
        val bar = rules["actionBar"] as? Map<*, *>
        if (bar != null) {
            a = a.copy(
                barFill = BarFill.entries.firstOrNull { it.formula == bar["fill"] } ?: BarFill.GACHA,
                barRandomStart = bar["start"] == "random(0, 15)",
            )
        }
        a = a.copy(turnLimit = int(rules, "turnLimit", 0))

        val generated = build(a)
        val custom = RulePart.entries.filter { part ->
            part.keys.any { key -> !JsonTools.equal(generated[key], rules[key], ordered = false) }
        }.toSet()
        return RuleRecognition(a, custom)
    }

    private fun recogniseGrowth(rules: Map<String, Any?>, start: RuleAnswers): RuleAnswers {
        val formulas = rules["statFormulas"] as? Map<*, *> ?: emptyMap<String, Any?>()
        fun effective(stat: String): Any? = formulas[stat] ?: formulas["default"] ?: "base"
        for (growth in Growth.entries) {
            val a = start.copy(growth = growth)
            val flat = flatFormula(a)
            var ok = true
            val stats = a.stats.map { s ->
                val f = effective(s.id)
                val grow = growFormula(a, s.id == a.hpStat && growth == Growth.POKEMON)
                when (f) {
                    grow -> s.copy(grows = true)
                    flat -> s.copy(grows = false)
                    else -> {
                        ok = false
                        s
                    }
                }
            }
            // A stat that "grows" with the flat formula is the same as one that does not: flat means no stat grows.
            if (ok && growth == Growth.FLAT) return a.copy(stats = stats.map { it.copy(grows = true) })
            if (ok && stats.any { it.grows }) return a.copy(stats = stats)
        }
        return start
    }

    private fun recogniseDamage(rules: Map<String, Any?>, start: RuleAnswers): RuleAnswers {
        val formula = rules["damage"] as? String ?: return start
        val stab = Regex("\\(sameType \\? (-?\\d+(?:\\.\\d+)?) : 1\\)").find(formula)?.groupValues?.get(1)?.toDouble() ?: 1.0
        for (preset in DamagePreset.entries) for (eff in listOf(true, false)) for (rnd in listOf(true, false)) {
            val a = start.copy(damage = preset, typeEffectiveness = eff, randomSpread = rnd, sameTypeBonus = stab)
            if (damageFormula(a) == formula) return a
        }
        return start
    }
}
