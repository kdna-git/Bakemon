package bakemon

import engine.Formula
import engine.formula
import engine.formulaOrNull
import util.JsonObj
import util.SetupException
import java.io.File

/** How turns are scheduled. See README "Turn systems". */
enum class TurnSystem(val id: String) {
    /** Pokemon-like rounds: everybody chooses, then actions run by priority and speed. */
    SPEED_ORDER("speed_order"),
    /** Gacha-like ATB: speed fills an action bar; whoever fills it first acts. */
    ACTION_BAR("action_bar");

    companion object {
        fun fromId(id: String): TurnSystem? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** One stat declared in Rules.json "stats". */
data class StatDef(val id: String, val name: String)

/**
 * A move category from Rules.json "categories".
 * When both stats are set, moves of this category deal damage using those stats as `attack`/`defense`.
 */
data class CategoryDef(val id: String, val attackStat: String?, val defenseStat: String?) {
    val dealsDamage: Boolean get() = attackStat != null && defenseStat != null
}

/**
 * One entry of Rules.json "hitTypes" (README "Hit types"), e.g. a crushing or a glancing hit. For each damaging hit,
 * after the crit roll, the hit types are tried in order and the first whose [chance] roll succeeds is the hit's type.
 */
class HitTypeDef(
    /** A simple name, unique among the hit types; read as `hitType` by the damage formula and hit triggers. */
    val id: String,
    /** Percent (0-100). Vars: like the damage formula, without attack/defense/hitType. */
    val chance: Formula,
    /** Multiplies the damage formula's result (same variables as [chance]). */
    val multiplier: Formula,
    /** Logged with the crit message when the hit deals damage ({user} {target} {move}), or null. */
    val message: String?,
    /** A critical hit of this type is no longer critical. */
    val cancelsCrit: Boolean,
) {
    override fun toString(): String = id
}

/** Rules.json: the battle format of a setup. Field meanings are documented in README "Rules.json". */
class Rules(
    val name: String,
    val description: String,
    val turnSystem: TurnSystem,
    /** "team.min" / "team.max": allowed team sizes. */
    val teamMin: Int,
    val teamMax: Int,
    /** "team.active": monsters on the field per side at the same time. */
    val activePerSide: Int,
    /** "team.speciesClause": no duplicate species in a team. */
    val speciesClause: Boolean,
    /** "team.itemClause": no duplicate held items in a team. */
    val itemClause: Boolean,
    /** Voluntary switching allowed as an action. (Replacing fainted monsters is always allowed.) */
    val switching: Boolean,
    val levelMin: Int,
    val levelMax: Int,
    val levelDefault: Int,
    val maxMoves: Int,
    val heldItems: Boolean,
    val stats: List<StatDef>,
    val hpStat: String,
    val speedStat: String,
    /** Per stat id, plus the "default" key used for stats without their own formula. Vars: base, level, points. */
    val statFormulas: Map<String, Formula>,
    /** "statPoints.total"; 0 when stat points are disabled. */
    val statPointsTotal: Int,
    /** "statPoints.maxPerStat"; 0 when stat points are disabled. */
    val statPointsMax: Int,
    val stageMin: Int,
    val stageMax: Int,
    /** Var: stage. Returns the stat multiplier for that stage. */
    val stageMultiplier: Formula,
    val categories: Map<String, CategoryDef>,
    val damage: Formula,
    val hitChance: Formula,
    val critChance: Formula,
    val critMultiplier: Formula,
    /** "hitTypes": tried in order on every damaging hit (empty = every hit is a normal hit). */
    val hitTypes: List<HitTypeDef> = emptyList(),
    val minDamage: Int,
    val usePP: Boolean,
    val useCooldowns: Boolean,
    /** Move id used when a monster has no usable move, or null (then the monster just waits). */
    val fallbackMove: String?,
    /** "actionBar.size" (default 100). */
    val actionBarSize: Double,
    /** "actionBar.fill" (default "speed"): bar points gained per time unit. Vars: speed, self.* */
    val actionBarFill: Formula,
    /** "actionBar.start" (default 0): initial fill in percent of the bar. Vars: speed, self.* */
    val actionBarStart: Formula,
    /** 0 = unlimited. */
    val turnLimit: Int,
    /** "startFields": fields set when a battle starts, before the leads enter (README "Fields.json"). */
    val startFields: List<FieldStart> = emptyList(),
) {
    val statPointsEnabled: Boolean get() = statPointsTotal > 0

    /** The formula for [statId], falling back to "default". */
    fun statFormula(statId: String): Formula = statFormulas[statId] ?: statFormulas.getValue("default")

    /** Display name of a stat id (the id itself if unknown). */
    fun statName(statId: String): String = stats.firstOrNull { it.id == statId }?.name ?: statId

    companion object {
        /**
         * Parses Rules.json. Problems that still allow loading are added to [report] (errors or warnings);
         * if the file cannot produce a usable Rules object at all, throws util.SetupException.
         * Defaults for optional fields are listed in README "Rules.json".
         */
        fun load(file: File, report: LoadReport): Rules {
            if (!file.isFile) throw SetupException("Rules.json: file not found")
            val root = JsonObj.of(LoadSupport.readJson(file), "Rules.json")
            return RulesParser(root, report).parse()
        }

        /** Like [load] for an already parsed Rules.json object (e.g. one being edited by the setup creator). */
        fun parse(root: JsonObj, report: LoadReport): Rules = RulesParser(root, report).parse()
    }
}

/**
 * Reads Rules.json field by field: a bad field is reported and replaced by its default, so every problem is
 * reported at once. Missing required fields get placeholders (the report then has errors, so the setup won't load).
 */
private class RulesParser(private val root: JsonObj, private val report: LoadReport) {
    private val statIds = LinkedHashSet<String>()

    private inline fun <T> read(default: T, block: () -> T): T = try {
        block()
    } catch (e: SetupException) {
        report.error(e.message ?: "Rules.json: invalid value")
        default
    }

    private fun error(obj: JsonObj, key: String, message: String) = report.error("${obj.pathOf(key)}: $message")

    private fun section(key: String, allowed: Set<String>): JsonObj? =
        read(null) { root.objOrNull(key) }?.also { LoadSupport.warnUnknownKeys(it, allowed, report) }

    private fun statHint(id: String): String =
        LoadSupport.suggest(id, statIds).ifEmpty { " (stats: ${statIds.joinToString(", ")})" }

    fun parse(): Rules {
        LoadSupport.warnUnknownKeys(root, KEYS, report)
        val name = read("") { root.string("name", "") }
        val description = read("") { root.string("description", "") }
        val turnSystem = read(TurnSystem.SPEED_ORDER) {
            val id = root.string("turnSystem")
            TurnSystem.fromId(id)
                ?: LoadSupport.failAt(root, "turnSystem", "unknown turn system '$id' (use \"speed_order\" or \"action_bar\")")
        }

        val team = section("team", setOf("min", "max", "active", "speciesClause", "itemClause"))
        val teamMin = read(1) { team?.int("min", 1) ?: 1 }
        val teamMax = read(6) { team?.int("max", 6) ?: 6 }
        val active = read(1) { team?.int("active", 1) ?: 1 }
        val speciesClause = read(false) { team?.bool("speciesClause", false) ?: false }
        val itemClause = read(false) { team?.bool("itemClause", false) ?: false }
        val teamPath = root.pathOf("team")
        if (teamMin < 1) report.error("$teamPath.min: must be at least 1 (got $teamMin)")
        if (teamMax < teamMin) report.error("$teamPath: max ($teamMax) is smaller than min ($teamMin)")
        if (active < 1) report.error("$teamPath.active: must be at least 1 (got $active)")

        val switching = read(true) { root.bool("switching", true) }

        val level = section("level", setOf("min", "max", "default"))
        val levelMin = read(1) { level?.int("min", 1) ?: 1 }
        val levelMax = read(100) { level?.int("max", 100) ?: 100 }
        val levelDefault = read(50) { level?.int("default", 50) ?: 50 }
        val levelPath = root.pathOf("level")
        if (levelMin < 0) report.error("$levelPath.min: must not be negative (got $levelMin)")
        if (levelMax < levelMin) {
            report.error("$levelPath: max ($levelMax) is smaller than min ($levelMin)")
        } else if (levelDefault !in levelMin..levelMax) {
            report.error("$levelPath.default: $levelDefault is outside min..max ($levelMin-$levelMax)")
        }

        val maxMoves = read(4) { root.int("maxMoves", 4) }
        if (maxMoves < 1) error(root, "maxMoves", "must be at least 1 (got $maxMoves)")
        val heldItems = read(true) { root.bool("heldItems", true) }

        val stats = parseStats()
        val hpStat = read("hp") { root.string("hpStat", "hp") }
        val speedStat = read("spe") { root.string("speedStat", "spe") }
        if (statIds.isNotEmpty()) {
            if (hpStat !in statIds) error(root, "hpStat", "'$hpStat' is not one of the stats${statHint(hpStat)}")
            if (speedStat !in statIds) error(root, "speedStat", "'$speedStat' is not one of the stats${statHint(speedStat)}")
        }

        val statFormulas = LinkedHashMap<String, Formula>()
        val formulas = read(null) { root.objOrNull("statFormulas") }
        if (formulas != null) {
            for (key in formulas.keys) {
                read(Unit) { statFormulas[key] = formulas.formula(key) }
                if (key != "default" && statIds.isNotEmpty() && key !in statIds) {
                    error(formulas, key, "unknown stat '$key'${statHint(key)} (use a stat id or \"default\")")
                }
            }
        }
        if ("default" !in statFormulas) statFormulas["default"] = Formula.parse("base")

        val points = section("statPoints", setOf("total", "maxPerStat"))
        var pointsTotal = 0
        var pointsMax = 0
        if (points != null) {
            pointsTotal = read(0) { points.int("total") }
            pointsMax = read(pointsTotal) { points.int("maxPerStat", pointsTotal) }
            if (pointsTotal < 0) error(points, "total", "must not be negative (got $pointsTotal)")
            if (pointsMax < 0) error(points, "maxPerStat", "must not be negative (got $pointsMax)")
            if (pointsTotal > 0 && pointsMax == 0) report.warn("${points.path}: maxPerStat is 0, so no stat points can be invested")
            if (pointsTotal <= 0) {
                pointsTotal = 0
                pointsMax = 0
            }
        }

        val stages = section("statStages", setOf("min", "max", "multiplier"))
        val stageMin = read(-6) { stages?.int("min", -6) ?: -6 }
        val stageMax = read(6) { stages?.int("max", 6) ?: 6 }
        val stageMultiplier = read(null) { stages?.formulaOrNull("multiplier") } ?: Formula.parse(DEFAULT_STAGE_MULTIPLIER)
        if (stageMin > 0 || stageMax < 0) {
            report.error("${root.pathOf("statStages")}: min ($stageMin) must be <= 0 and max ($stageMax) must be >= 0")
        }

        val categories = parseCategories()
        val damage = read(null) { root.formula("damage") } ?: Formula.of(0.0)
        val hitChance = read(null) { root.formulaOrNull("hitChance") } ?: Formula.parse(DEFAULT_HIT_CHANCE)
        val critChance = read(null) { root.formula("critChance", 0.0) } ?: Formula.of(0.0)
        val critMultiplier = read(null) { root.formula("critMultiplier", 1.5) } ?: Formula.of(1.5)
        val hitTypes = parseHitTypes()
        val minDamage = read(1) { root.int("minDamage", 1) }
        if (minDamage < 0) error(root, "minDamage", "must not be negative (got $minDamage)")
        val usePP = read(true) { root.bool("pp", true) }
        val useCooldowns = read(false) { root.bool("cooldowns", false) }
        val fallbackMove = read(null) { root.stringOrNull("fallbackMove") }?.takeIf { it.isNotBlank() }

        val bar = section("actionBar", setOf("size", "fill", "start"))
        val barSize = read(100.0) { bar?.double("size", 100.0) ?: 100.0 }
        if (barSize <= 0) report.error("${root.pathOf("actionBar")}.size: must be greater than 0 (got $barSize)")
        val barFill = read(null) { bar?.formulaOrNull("fill") } ?: Formula.parse("speed")
        val barStart = read(null) { bar?.formulaOrNull("start") } ?: Formula.of(0.0)

        val turnLimit = read(0) { root.int("turnLimit", 0) }
        if (turnLimit < 0) error(root, "turnLimit", "must not be negative (got $turnLimit)")

        return Rules(
            name = name,
            description = description,
            turnSystem = turnSystem,
            teamMin = teamMin,
            teamMax = teamMax,
            activePerSide = active,
            speciesClause = speciesClause,
            itemClause = itemClause,
            switching = switching,
            levelMin = levelMin,
            levelMax = levelMax,
            levelDefault = levelDefault,
            maxMoves = maxMoves,
            heldItems = heldItems,
            stats = stats,
            hpStat = hpStat,
            speedStat = speedStat,
            statFormulas = statFormulas,
            statPointsTotal = pointsTotal,
            statPointsMax = pointsMax,
            stageMin = stageMin,
            stageMax = stageMax,
            stageMultiplier = stageMultiplier,
            categories = categories,
            damage = damage,
            hitChance = hitChance,
            critChance = critChance,
            critMultiplier = critMultiplier,
            hitTypes = hitTypes,
            minDamage = minDamage,
            usePP = usePP,
            useCooldowns = useCooldowns,
            fallbackMove = fallbackMove,
            actionBarSize = barSize,
            actionBarFill = barFill,
            actionBarStart = barStart,
            turnLimit = turnLimit,
            startFields = parseStartFields(),
        )
    }

    /**
     * "startFields": [{field, side, duration, permanent}]. Field ids are checked by the setup validator (it knows
     * Fields.json); each entry's location is kept in [LoadReport.locations] under "startField:<index>".
     */
    private fun parseStartFields(): List<FieldStart> {
        if (root.isNull("startFields")) return emptyList()
        val arr = read(null) { root.arr("startFields") } ?: return emptyList()
        val result = mutableListOf<FieldStart>()
        for ((i, value) in arr.list.withIndex()) {
            val path = "${arr.path}[$i]"
            result += read(null) {
                val obj = JsonObj.of(value, path)
                LoadSupport.warnUnknownKeys(obj, START_FIELD_KEYS, report)
                val field = obj.string("field")
                if (field.isBlank()) LoadSupport.failAt(obj, "field", "must not be empty")
                val sideId = obj.string("side", "both")
                val side = when (sideId.lowercase()) {
                    "both" -> null
                    "side1" -> 0
                    "side2" -> 1
                    else -> LoadSupport.failAt(obj, "side", "unknown side '$sideId' (use \"both\", \"side1\" or \"side2\")")
                }
                val duration = obj.formulaOrNull("duration")
                val permanent = obj.bool("permanent", duration == null)
                if (permanent && duration != null) report.warn("${obj.pathOf("duration")}: ignored because \"permanent\" is true")
                report.locations["startField:$i"] = path
                FieldStart(field, side, duration, permanent)
            } ?: continue
        }
        return result
    }

    /** "stats": objects {id, name} (a plain string is accepted as both). Ids must be unique formula-friendly names. */
    private fun parseStats(): List<StatDef> {
        val arr = read(null) { root.arr("stats") } ?: return emptyList()
        if (arr.size == 0) report.error("${arr.path}: must list at least one stat")
        val result = mutableListOf<StatDef>()
        for ((i, value) in arr.list.withIndex()) {
            val path = "${arr.path}[$i]"
            val def = when (value) {
                is String -> StatDef(value, value)
                is Map<*, *> -> read(null) {
                    val obj = JsonObj.of(value, path)
                    LoadSupport.warnUnknownKeys(obj, setOf("id", "name"), report)
                    val id = obj.string("id")
                    StatDef(id, obj.string("name", id))
                }
                else -> {
                    report.error("$path: expected an object like {\"id\": \"atk\", \"name\": \"Attack\"}")
                    null
                }
            } ?: continue
            when {
                !IDENTIFIER.matches(def.id) ->
                    report.error("$path: stat id '${def.id}' must be a simple name (letters, digits and _, not starting with a digit)")
                def.id in statIds -> report.error("$path: duplicate stat id '${def.id}'")
                else -> {
                    if (def.id in SHADOWED_FIELDS) {
                        report.warn("$path: stat id '${def.id}' hides the monster field 'm.${def.id}' in formulas (use 'm.raw.${def.id}')")
                    }
                    statIds += def.id
                    result += def
                }
            }
        }
        return result
    }

    /** "categories": name -> {attack, defense}; {} = no damage. */
    private fun parseCategories(): Map<String, CategoryDef> {
        val obj = read(null) { root.obj("categories") } ?: return emptyMap()
        if (obj.keys.isEmpty()) report.error("${obj.path}: must define at least one move category")
        val result = LinkedHashMap<String, CategoryDef>()
        for (key in obj.keys) {
            val category = read(null) { obj.obj(key) } ?: continue
            LoadSupport.warnUnknownKeys(category, setOf("attack", "defense"), report)
            val attack = read(null) { category.stringOrNull("attack") }?.takeIf { it.isNotBlank() }
            val defense = read(null) { category.stringOrNull("defense") }?.takeIf { it.isNotBlank() }
            if (statIds.isNotEmpty()) {
                if (attack != null && attack !in statIds) error(category, "attack", "unknown stat '$attack'${statHint(attack)}")
                if (defense != null && defense !in statIds) error(category, "defense", "unknown stat '$defense'${statHint(defense)}")
            }
            if ((attack == null) != (defense == null)) {
                report.warn("${category.path}: set both \"attack\" and \"defense\" to deal damage (as written, this category deals no damage)")
            }
            result[key] = CategoryDef(key, attack, defense)
        }
        return result
    }

    /**
     * "hitTypes": an ordered array of {id, chance, multiplier, message, cancelsCrit}. Ids are unique simple names
     * (a bad or duplicate entry is reported and skipped). Each entry's location is kept in [LoadReport.locations] under
     * "hitType:<id>" (its formulas are checked by the setup validator, which knows the formula scopes).
     */
    private fun parseHitTypes(): List<HitTypeDef> {
        if (root.isNull("hitTypes")) return emptyList()
        val arr = read(null) { root.arr("hitTypes") } ?: return emptyList()
        val result = mutableListOf<HitTypeDef>()
        val ids = HashSet<String>()
        for ((i, value) in arr.list.withIndex()) {
            val path = "${arr.path}[$i]"
            val raw = read(null) { JsonObj.of(value, path) } ?: continue
            val id = read(null) { raw.string("id") } ?: continue
            when {
                !IDENTIFIER.matches(id) -> {
                    report.error("$path: hit type id '$id' must be a simple name (letters, digits and _, not starting with a digit)")
                    continue
                }
                !ids.add(id) -> {
                    report.error("$path: duplicate hit type id '$id'")
                    continue
                }
            }
            val obj = JsonObj(raw.map, "$path ($id)")
            report.locations["hitType:$id"] = obj.path
            LoadSupport.warnUnknownKeys(obj, HIT_TYPE_KEYS, report)
            if (obj.isNull("chance")) report.warn("${obj.path}: no \"chance\", so this hit type never happens (the default chance is 0)")
            result += HitTypeDef(
                id = id,
                chance = read(null) { obj.formulaOrNull("chance") } ?: Formula.of(0.0),
                multiplier = read(null) { obj.formulaOrNull("multiplier") } ?: Formula.of(1.0),
                message = read(null) { obj.stringOrNull("message") },
                cancelsCrit = read(false) { obj.bool("cancelsCrit", false) },
            )
        }
        return result
    }

    companion object {
        val KEYS = setOf(
            "name", "description", "turnSystem", "team", "switching", "level", "maxMoves", "heldItems", "stats",
            "hpStat", "speedStat", "statFormulas", "statPoints", "statStages", "categories", "damage", "hitChance",
            "critChance", "critMultiplier", "hitTypes", "minDamage", "pp", "cooldowns", "fallbackMove", "actionBar", "turnLimit",
            "startFields",
        )
        val START_FIELD_KEYS = setOf("field", "side", "duration", "permanent")
        val HIT_TYPE_KEYS = setOf("id", "chance", "multiplier", "message", "cancelsCrit")
        const val DEFAULT_STAGE_MULTIPLIER = "stage >= 0 ? (2 + stage) / 2 : 2 / (2 - stage)"
        const val DEFAULT_HIT_CHANCE = "move.accuracy / 100"
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Monster fields that a stat with the same id would hide ("hp" is fine: m.hp is the current HP by design). */
        val SHADOWED_FIELDS = setOf(
            "maxHp", "hpPercent", "level", "gauge", "shield", "fainted", "revives", "reviveBlocked", "id", "name", "raw", "base", "stage",
        )
    }
}
