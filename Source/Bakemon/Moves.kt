package bakemon

import engine.Formula
import engine.TargetSelector
import engine.Trigger
import engine.formula
import engine.formulaOrNull
import util.JsonObj
import util.SetupException
import java.io.File

/** Who a move affects. See README "Moves.json". */
enum class MoveTarget(val id: String) {
    /** One enemy, chosen by the controller. */
    ENEMY("enemy"),
    ALL_ENEMIES("allEnemies"),
    RANDOM_ENEMY("randomEnemy"),
    SELF("self"),
    /** One ally (not self), chosen by the controller. */
    ALLY("ally"),
    /** One ally or self, chosen by the controller. */
    ALLY_OR_SELF("allyOrSelf"),
    /** Every active ally, not self. */
    ALL_ALLIES("allAllies"),
    /** Self and every active ally. */
    TEAM("team"),
    /** Every other active monster (allies and enemies). */
    ALL("all"),
    /** One fainted teammate that can still be revived (on the field or not), chosen by the controller. */
    FAINTED_ALLY("faintedAlly"),
    /** The user or an ally, whichever has the lowest HP percent (chosen automatically; ties: field order). */
    LOWEST_HP_ALLY("lowestHpAlly"),
    /** The enemy with the lowest HP percent (chosen automatically; ties: field order). */
    LOWEST_HP_ENEMY("lowestHpEnemy"),
    /** Every hit picks a new random active enemy; `hits` is the total number of hits. */
    RANDOM_ENEMIES("randomEnemies");

    /** True if the controller must pick a single target. */
    val needsChoice: Boolean get() = this == ENEMY || this == ALLY || this == ALLY_OR_SELF || this == FAINTED_ALLY

    /** True if this move is aimed at the opposing side (accuracy applies). */
    val offensive: Boolean get() =
        this == ENEMY || this == ALL_ENEMIES || this == RANDOM_ENEMY || this == ALL || this == LOWEST_HP_ENEMY || this == RANDOM_ENEMIES

    companion object {
        fun fromId(id: String): MoveTarget? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** How often a multi-hit move rolls its accuracy against one target (README "Moves.json", `hitAccuracy`). */
enum class HitAccuracy(val id: String) {
    /** One roll per target, before the first hit. */
    ONCE("once"),
    /** Every hit rolls; the first miss ends the move against that target. */
    EACH("each");

    companion object {
        fun fromId(id: String): HitAccuracy? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * A charge move (README "Moves.json", `charge`): the first use only charges for [turns] turns, then the move is
 * released automatically. [message] is logged on each charging turn; [invulnerable] makes the charging monster
 * unreachable by other monsters' moves; [skipIf] true = no charge this time.
 */
class ChargeDef(val turns: Int, val message: String, val invulnerable: Boolean, val skipIf: Formula?) {
    companion object {
        const val DEFAULT_MESSAGE = "{user} is charging up!"
        val KEYS = setOf("turns", "message", "invulnerable", "skipIf")
    }
}

/** A move/skill from Moves.json. */
class MoveDef(
    val id: String,
    val name: String,
    val description: String,
    /** Type id, or null for typeless moves. */
    val type: String?,
    /** Key of Rules.categories. */
    val category: String,
    /** Null when the move deals no direct damage. May be a formula (user/target/move available). */
    val power: Formula?,
    /** Percent; null = never misses. */
    val accuracy: Double?,
    val priority: Int,
    /** 0 = unlimited. */
    val pp: Int,
    /** Owner turns to wait after using it (0 = none). */
    val cooldown: Int,
    val target: MoveTarget,
    /** Number of hits per target (default constant 1). */
    val hits: Formula,
    /** Added to the crit chance (0..1), or null. */
    val critBonus: Formula?,
    val flags: Set<String>,
    /** Move-scoped triggers. Effect target defaults to "target". */
    val triggers: List<Trigger>,
    /** Percent of the target's `defense` the damage formula ignores (0-100), or null. */
    val ignoreDefense: Formula? = null,
    /** One accuracy roll per target (default) or one per hit. */
    val hitAccuracy: HitAccuracy = HitAccuracy.ONCE,
    /** Charge behaviour, or null for a normal move. */
    val charge: ChargeDef? = null,
) {
    override fun toString(): String = name
}

/** All moves of a setup (Moves.json). Keeps file order. */
class MoveManager(val moves: Map<String, MoveDef>) {
    operator fun get(id: String): MoveDef? = moves[id]

    /** Throws util.SetupException("Unknown move '<id>'") if missing. */
    fun require(id: String): MoveDef = moves[id] ?: throw SetupException("Unknown move '$id'")

    fun all(): List<MoveDef> = moves.values.toList()

    companion object {
        private val KEYS = setOf(
            "id", "name", "description", "type", "category", "power", "accuracy", "priority", "pp", "cooldown",
            "target", "hits", "critBonus", "flags", "triggers", "ignoreDefense", "hitAccuracy", "charge",
        )

        /**
         * Parses Moves.json (bare array or {"moves": [...]}). Bad entries are reported and skipped.
         * Triggers are parsed with engine.Trigger.parseList(obj, "triggers", TargetSelector.TARGET).
         */
        fun load(file: File, report: LoadReport): MoveManager = MoveManager(
            LoadSupport.loadList(file, report, "moves", "move", LoadSupport.Missing.ERROR) { parse(it, report) },
        )

        private fun parse(obj: JsonObj, report: LoadReport): MoveDef {
            LoadSupport.warnUnknownKeys(obj, KEYS, report)
            LoadSupport.warnTriggerKeys(obj, report)
            val id = obj.string("id")
            val power = obj.formulaOrNull("power")
            val powerValue = power?.constant
            if (powerValue != null && powerValue < 0) LoadSupport.failAt(obj, "power", "must not be negative (got $powerValue)")
            val accuracy = obj.doubleOrNull("accuracy")
            if (accuracy != null && accuracy < 0) LoadSupport.failAt(obj, "accuracy", "must not be negative (got $accuracy)")
            val pp = obj.int("pp", 0)
            if (pp < 0) LoadSupport.failAt(obj, "pp", "must not be negative (got $pp)")
            val cooldown = obj.int("cooldown", 0)
            if (cooldown < 0) LoadSupport.failAt(obj, "cooldown", "must not be negative (got $cooldown)")
            val targetId = obj.string("target", MoveTarget.ENEMY.id)
            val target = MoveTarget.fromId(targetId) ?: LoadSupport.failAt(
                obj, "target", "unknown target '$targetId' (use one of: ${MoveTarget.entries.joinToString(", ") { it.id }})",
            )
            val hits = obj.formula("hits", 1.0)
            val hitsValue = hits.constant
            if (hitsValue != null && hitsValue < 1) LoadSupport.failAt(obj, "hits", "must be at least 1 (got $hitsValue)")
            val ignoreDefense = obj.formulaOrNull("ignoreDefense")
            val hitAccuracyId = obj.string("hitAccuracy", HitAccuracy.ONCE.id)
            val hitAccuracy = HitAccuracy.fromId(hitAccuracyId) ?: LoadSupport.failAt(
                obj, "hitAccuracy", "unknown value '$hitAccuracyId' (use one of: ${HitAccuracy.entries.joinToString(", ") { it.id }})",
            )
            val charge = obj.objOrNull("charge")?.let { c ->
                LoadSupport.warnUnknownKeys(c, ChargeDef.KEYS, report)
                val turns = c.int("turns", 1)
                if (turns < 1) LoadSupport.failAt(c, "turns", "must be at least 1 (got $turns)")
                ChargeDef(turns, c.string("message", ChargeDef.DEFAULT_MESSAGE), c.bool("invulnerable", false), c.formulaOrNull("skipIf"))
            }
            return MoveDef(
                id = id,
                name = obj.string("name", id),
                description = obj.string("description", ""),
                type = obj.stringOrNull("type")?.takeIf { it.isNotBlank() },
                category = obj.string("category"),
                power = power?.takeUnless { powerValue == 0.0 },
                accuracy = accuracy,
                priority = obj.int("priority", 0),
                pp = pp,
                cooldown = cooldown,
                target = target,
                hits = hits,
                critBonus = obj.formulaOrNull("critBonus"),
                flags = obj.strings("flags").toCollection(LinkedHashSet()),
                triggers = Trigger.parseList(obj, "triggers", TargetSelector.TARGET),
                ignoreDefense = ignoreDefense,
                hitAccuracy = hitAccuracy,
                charge = charge,
            )
        }
    }
}
