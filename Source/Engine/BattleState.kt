package engine

import bakemon.AbilityDef
import bakemon.Bakemon
import bakemon.FieldAudience
import bakemon.FieldDef
import bakemon.FieldRules
import bakemon.ItemDef
import bakemon.MoveDef
import bakemon.MoveTarget
import bakemon.Setup
import bakemon.Species
import bakemon.StatusDef
import bakemon.StatusKind
import bakemon.TurnSystem
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.random.Random

/** A move known by a battler, with its runtime counters. */
class MoveSlot(val def: MoveDef, var pp: Int) {
    /**
     * Remaining owner turns before the move can be used again (0 = ready).
     * Using a move sets it to def.cooldown + 1; it decreases by 1 at each of the owner's turnEnd. A `cooldown` effect
     * adds its amount (+1 more for a ready move when it lands after the owner acted in its turn, see Manager.changeCooldowns).
     */
    var cooldown: Int = 0
}

/** A status currently affecting a battler. */
class StatusInstance(
    val def: StatusDef,
    /** Remaining owner turns, or null = permanent until removed. */
    var turnsLeft: Int?,
    var stacks: Int,
    /** Who applied it, if known. */
    val source: Battler?,
    /**
     * True if applied during the owner's turn after the owner's action started (README "Durations"): it has not
     * affected any of the owner's actions yet, so the next countdown does not count it down (then it is cleared).
     */
    var fresh: Boolean,
)

/**
 * An active field (README "Fields.json"): global ([side] null) or on one side. Fields never belong to a monster, so
 * they keep working when their setter faints or leaves the field.
 */
class FieldInstance(
    val def: FieldDef,
    /** The side it covers, or null for a global field (covers both sides). */
    val side: Side?,
    /** Remaining turns, or null = permanent until removed (README "Fields.json", countdown). */
    turnsLeft: Int?,
    stacks: Int,
    /** The monster that set it, if any (start fields have none). */
    val setter: Battler?,
) {
    /** Remaining turns, or null = permanent. */
    var turnsLeft: Int? = turnsLeft
        internal set
    var stacks: Int = stacks
        internal set

    /** The side of the monster that set it (action_bar countdown), or null for start fields. */
    val setterSide: Side? get() = setter?.side

    /** True if it covers [b]: a global field covers everybody, a side field the monsters of its side. */
    fun covers(b: Battler): Boolean = side == null || side === b.side

    /**
     * True if a trigger of this field with [audience] listens to events about [subject], which must be on the field
     * unless [onField] is false (the AI asks about benched monsters as if they were on the field).
     */
    fun hears(audience: FieldAudience, subject: Battler, onField: Boolean = true): Boolean = (!onField || subject.slot >= 0) && when (audience) {
        FieldAudience.COVERED -> covers(subject)
        FieldAudience.OPPONENTS -> side != null && side !== subject.side
        FieldAudience.ALL -> true
    }

    override fun toString(): String = if (side == null) def.name else "${def.name} (${side.name})"
}

/**
 * A charge move a battler is locked into (README "Moves.json", `charge`): its next turns release [move] on [target]
 * without asking its controller. [turnsLeft] = charging turns still to come before the release (0 = it releases next).
 */
class ChargeState(val move: MoveDef, val target: Battler?, var turnsLeft: Int, val invulnerable: Boolean)

/**
 * Where a battler is in its current turn; decides whether a newly applied status is fresh (README "Durations").
 * speed_order: every active monster is [WAITING] from the start of the round until its action starts, then [ACTED]
 * until its countdown at the end of the round. action_bar: only the monster whose turn it is leaves [CLOSED].
 */
enum class TurnPhase {
    /** Not in a turn (between turns, before the first round, or after its countdown): the next countdown counts. */
    CLOSED,
    /** In its turn, before its action: a new status affects that action, so the countdown at the end of the turn counts. */
    WAITING,
    /** In its turn, during or after its action (or it has no action this round): a new status is fresh. */
    ACTED,
}

/**
 * A monster during a battle: its configuration plus mutable battle state.
 * Construct with [Battler.create] (computes stats, resolves ability/item/moves through the setup).
 */
class Battler(
    val setup: Setup,
    val config: Bakemon,
    val species: Species,
    val side: Side,
    /** Position in its team (0-based). */
    val teamIndex: Int,
    val name: String,
    val level: Int,
    var types: List<String>,
    /** Stats from Rules.statFormulas (no stages/modifiers). */
    val rawStats: Map<String, Double>,
    val maxHp: Int,
    var ability: AbilityDef?,
    var item: ItemDef?,
    val moves: List<MoveSlot>,
    /** The display name without the side tag that [name] gets when both sides have a monster with that name. */
    val baseName: String = name,
) {
    var hp: Int = maxHp
    val statuses: MutableList<StatusInstance> = mutableListOf()
    /** stat id -> stage (missing = 0). */
    val stages: MutableMap<String, Int> = mutableMapOf()
    /** Action bar fill in bar points (0..Rules.actionBarSize). */
    var gauge: Double = 0.0
    var shield: Int = 0
    /** Field slot index, or -1 when not on the field. */
    var slot: Int = -1
    /** How many times each trigger fired for this battler (for Trigger.limit). Identity keys. */
    val triggerUses: MutableMap<Trigger, Int> = HashMap()
    /** Where it is in its current turn (set by the Manager; decides whether a new status is fresh). */
    var turnPhase: TurnPhase = TurnPhase.CLOSED
        internal set
    /** How many times it was revived in this battle. */
    var revives: Int = 0
        internal set
    /** Set by the preventRevive effect: it can no longer be revived in this battle. */
    var reviveBlocked: Boolean = false
        internal set
    /** The charge move it is locked into, or null (README "Moves.json", `charge`). */
    var charge: ChargeState? = null
        internal set
    /** True while an extra turn given by the extraTurn effect is waiting to be played. */
    var extraTurnPending: Boolean = false
        internal set
    /** speed_order: the round of its last extra turn (at most one per round); -1 = none yet. */
    internal var extraTurnRound: Int = -1
    /** action_bar: true once it got an extra turn since its last normal turn (at most one in between). */
    internal var extraTurnTaken: Boolean = false

    /** 1 in `m.charging`: it is charging a move. */
    val isCharging: Boolean get() = charge != null
    /** Charging a move that makes it unreachable: other monsters' moves aimed at it miss. */
    val isInvulnerable: Boolean get() = charge?.invulnerable == true

    /** Stage multipliers already evaluated, indexed by stage - Rules.stageMin (NaN = not computed yet). */
    private val stageMultipliers = DoubleArray((setup.rules.stageMax - setup.rules.stageMin + 1).coerceAtLeast(0)) { Double.NaN }

    val isFainted: Boolean get() = hp <= 0
    /** On the field and not fainted. */
    val isActive: Boolean get() = slot >= 0 && !isFainted
    val hpFraction: Double get() = if (maxHp <= 0) 0.0 else hp.toDouble() / maxHp

    /**
     * Effective stat: raw x stage multiplier (Rules.statStages.multiplier, not for the hp stat) x every
     * statModifier of the ability, the held item, each status (status modifiers get `stacks`) and each active field
     * that covers this battler (in the order they were set; they get the field's `stacks` / `turnsLeft`).
     * A suppressed ability or item ([activeAbility], [activeItem]) adds nothing.
     * Modifier formulas are evaluated with `self.<stat>` reading raw stats (no recursion).
     */
    fun stat(id: String): Double {
        var value = rawStat(id)
        if (id != setup.rules.hpStat) value *= stageMultiplier(stage(id))
        activeAbility?.statModifiers?.get(id)?.let { value *= it.eval(modifierContext(null)) }
        activeItem?.statModifiers?.get(id)?.let { value *= it.eval(modifierContext(null)) }
        for (i in statuses.indices) {
            val instance = statuses[i]
            val formula = instance.def.statModifiers[id] ?: continue
            value *= formula.eval(modifierContext(instance))
        }
        val fields = side.battle?.fields
        if (!fields.isNullOrEmpty()) {
            for (i in fields.indices) {
                val field = fields[i]
                val formula = field.def.statModifiers[id] ?: continue
                if (field.covers(this)) value *= formula.eval(fieldContext(field))
            }
        }
        return if (value > 0.0) value else 0.0 // also maps NaN to 0
    }

    /** The ability, or null while an active field's "disableAbilities" rule suppresses it (README "Fields.json"). */
    val activeAbility: AbilityDef? get() = ability?.takeIf { !suppressed(abilities = true) }

    /** The held item, or null while an active field's "disableItems" rule suppresses it (README "Fields.json"). */
    val activeItem: ItemDef? get() = item?.takeIf { !suppressed(abilities = false) }

    /** True while evaluating a disable rule for this battler: inside it, the ability and item count as active. */
    private var checkingSuppression = false

    private fun suppressed(abilities: Boolean): Boolean {
        val fields = side.battle?.fields
        if (fields.isNullOrEmpty() || checkingSuppression) return false
        checkingSuppression = true
        try {
            for (i in fields.indices) {
                val field = fields[i]
                val rule = (if (abilities) field.def.rules.disableAbilities else field.def.rules.disableItems) ?: continue
                if (field.covers(this) && rule.test(fieldContext(field))) return true
            }
            return false
        } finally {
            checkingSuppression = false
        }
    }

    fun rawStat(id: String): Double = rawStats[id] ?: 0.0
    fun stage(id: String): Int = stages[id] ?: 0
    fun hasType(type: String): Boolean = types.any { it.equals(type, ignoreCase = true) }
    fun status(id: String): StatusInstance? = statuses.firstOrNull { it.def.id == id }
    fun hasStatus(id: String): Boolean = status(id) != null

    /** Gauge in percent of the bar (0-100). */
    fun gaugePercent(): Double {
        val size = setup.rules.actionBarSize
        return if (size <= 0.0) 0.0 else gauge * 100.0 / size
    }

    /** Rules.statStages.multiplier for [stage], evaluated once per stage value. */
    internal fun stageMultiplier(stage: Int): Double {
        val index = stage - setup.rules.stageMin
        if (index < 0 || index >= stageMultipliers.size) return computeStageMultiplier(stage)
        val cached = stageMultipliers[index]
        if (!cached.isNaN()) return cached
        return computeStageMultiplier(stage).also { stageMultipliers[index] = it }
    }

    private fun computeStageMultiplier(stage: Int): Double {
        val value = setup.rules.stageMultiplier.eval(MapContext(mapOf("stage" to stage.toDouble()), Random(stage)))
        return if (value.isNaN()) 1.0 else value
    }

    private fun modifierContext(instance: StatusInstance?): ExprContext =
        BattleExprContext(side.battle, setup, this, null, this, null, null, emptyMap(), instance, estimate = false, rawStats = true)

    /** Context of a field's statModifiers and disable rules for this battler (raw stats, the field's stacks). */
    private fun fieldContext(field: FieldInstance): ExprContext =
        BattleExprContext(side.battle, setup, this, null, this, null, null, emptyMap(), null, estimate = false, rawStats = true, field = field)

    override fun toString(): String = name

    companion object {
        /**
         * Builds a battler from a validated configuration. PP starts at each move's max (0 = unlimited).
         * [name] overrides the display name (the Manager uses it to tell apart same-named monsters of both sides).
         */
        fun create(setup: Setup, config: Bakemon, side: Side, teamIndex: Int, name: String? = null): Battler {
            val species = setup.dex.require(config.species)
            val stats = config.stats(setup)
            val maxHp = (stats[setup.rules.hpStat] ?: 1.0).floorToInt().coerceAtLeast(1)
            val ability = config.ability?.let { setup.abilities.require(it) }
            val item = if (setup.rules.heldItems) config.item?.let { setup.items.require(it) } else null
            val moves = config.moves.map { id ->
                val def = setup.moves.require(id)
                MoveSlot(def, def.pp.coerceAtLeast(0))
            }
            return Battler(
                setup = setup,
                config = config,
                species = species,
                side = side,
                teamIndex = teamIndex,
                name = name ?: config.displayName(setup),
                level = config.level,
                types = species.types.toList(),
                rawStats = stats,
                maxHp = maxHp,
                ability = ability,
                item = item,
                moves = moves,
                baseName = config.displayName(setup),
            )
        }
    }
}

/** One player's side: its team, field slots and controller. */
class Side(
    /** 0 or 1. */
    val index: Int,
    val name: String,
    val controller: Controller,
    activeSlots: Int,
) {
    val team: MutableList<Battler> = mutableListOf()
    /** Field slots (size = Rules.activePerSide). A slot may hold a fainted battler until it is replaced. */
    val active: Array<Battler?> = arrayOfNulls(activeSlots)

    /** The battle this side belongs to (set by [Battle]'s constructor). */
    internal var battle: Battle? = null

    /** Battlers on the field that are not fainted, in slot order. */
    fun activeBattlers(): List<Battler> = active.filterNotNull().filter { !it.isFainted }

    /** Team members not on the field and not fainted. */
    fun bench(): List<Battler> = team.filter { it.slot < 0 && !it.isFainted }

    /** Everybody fainted. */
    fun isDefeated(): Boolean = team.all { it.isFainted }

    override fun toString(): String = name
}

/**
 * The whole battle state, readable by controllers and UIs. Mutations happen through [Manager].
 */
class Battle(val setup: Setup, val sides: List<Side>, val random: Random) {
    /** Round (speed_order) or turn (action_bar) number; 0 before the first one starts. */
    var turn: Int = 0
    /** The monster whose turn it is (action_bar), or the monster currently acting (speed_order); null between actions. */
    var currentActor: Battler? = null
    var ended: Boolean = false

    val rules get() = setup.rules

    init {
        sides.forEach { it.battle = this }
    }

    fun opponentOf(side: Side): Side = sides[1 - side.index]

    /** Active, non-fainted battlers of both sides (side 0 first, slot order). */
    fun allActive(): List<Battler> = sides.flatMap { it.activeBattlers() }

    /** Active, non-fainted allies of [b] (excluding b). */
    fun allies(b: Battler): List<Battler> = b.side.activeBattlers().filter { it !== b }

    /** Active, non-fainted enemies of [b]. */
    fun enemies(b: Battler): List<Battler> = opponentOf(b.side).activeBattlers()

    /** The monster of [list] with the lowest HP percent (ties: the first in [list], i.e. field order), or null. */
    fun lowestHp(list: List<Battler>): Battler? = list.minByOrNull { it.hpFraction }

    /** The monster of [list] with the highest HP percent (ties: the first in [list]), or null. */
    fun highestHp(list: List<Battler>): Battler? = list.maxByOrNull { it.hpFraction }

    /** Fainted teammates of [b] that can still be revived (on the field or not, team order; never b itself). */
    fun faintedAllies(b: Battler): List<Battler> = b.side.team.filter { it !== b && it.isFainted && !it.reviveBlocked }

    /**
     * Moves [b] can use right now: PP left if Rules.pp, cooldown 0 if Rules.cooldowns, and not blocked by a field's
     * "blockMove" rule (checked without a target, see [isMoveBlocked]).
     */
    fun usableMoves(b: Battler): List<MoveSlot> = b.moves.filter { isUsable(it) && !isMoveBlocked(b, it.def, null) }

    /** True if [slot] has PP left (when Rules.pp) and no cooldown (when Rules.cooldowns). */
    internal fun isUsable(slot: MoveSlot): Boolean =
        (!rules.usePP || slot.def.pp <= 0 || slot.pp > 0) && (!rules.useCooldowns || slot.cooldown <= 0)

    // ---- fields ----------------------------------------------------------------------------------------------

    /** Active fields in the order they were set (README "Fields.json"). Changed only by the Manager. */
    val fields: List<FieldInstance> get() = activeFields

    internal val activeFields: MutableList<FieldInstance> = ArrayList()

    /** Active fields that cover [b] (global fields and the fields of its side), in the order they were set. */
    fun fieldsCovering(b: Battler): List<FieldInstance> =
        if (activeFields.isEmpty()) emptyList() else activeFields.filter { it.covers(b) }

    /** True if field [id] is active globally or on any side. */
    fun isFieldActive(id: String): Boolean = activeFields.any { it.def.id == id }

    /** The active instance of field [id] in one place: [side] null = the global one. */
    fun field(id: String, side: Side? = null): FieldInstance? = activeFields.firstOrNull { it.def.id == id && it.side === side }

    /**
     * The most recently set active field that covers [b] and whose rules have the member [pick] returns, or null.
     * Used for the formula overrides (damage, hitChance, critChance, critMultiplier, actionBarFill).
     */
    internal inline fun ruleOverride(b: Battler, pick: (FieldRules) -> Formula?): FieldInstance? {
        if (activeFields.isEmpty()) return null
        for (i in activeFields.indices.reversed()) {
            val field = activeFields[i]
            if (pick(field.def.rules) != null && field.covers(b)) return field
        }
        return null
    }

    /**
     * The first active field covering [user] whose "blockMove" rule is true for [move] (README "Fields.json"), or null.
     * [target] is the primary target: absent (null) when choosing, known when the move runs. [estimate] = no dice
     * (always so without a target; the AI passes true when it checks a known target while thinking).
     */
    fun moveBlocker(user: Battler, move: MoveDef, target: Battler?, estimate: Boolean = target == null): FieldInstance? {
        if (activeFields.isEmpty()) return null
        for (field in activeFields.toList()) {
            val rule = field.def.rules.blockMove ?: continue
            if (!field.covers(user)) continue
            val ctx = context(user, target, user, target, move, estimate = estimate || target == null, field = field)
            if (rule.test(ctx)) return field
        }
        return null
    }

    /** True if an active field's "blockMove" rule stops [user] from using [move] (see [moveBlocker]). */
    fun isMoveBlocked(user: Battler, move: MoveDef, target: Battler?, estimate: Boolean = target == null): Boolean =
        moveBlocker(user, move, target, estimate) != null

    /**
     * The first active field covering [target] whose "blockStatus" rule stops [status] from [source], or null.
     * [estimate] = no dice (the AI asks this too).
     */
    fun statusBlocker(target: Battler, status: StatusDef, source: Battler?, estimate: Boolean = false): FieldInstance? {
        if (activeFields.isEmpty()) return null
        for (field in activeFields.toList()) {
            val rule = field.def.rules.blockStatus ?: continue
            if (!field.covers(target)) continue
            val vars = mapOf("status" to status.id, "statusKind" to status.kind.id)
            // other / user = the source (also when it is the target itself: sameSide(self, other) is then 1).
            if (rule.test(context(target, source, source, target, vars = vars, estimate = estimate, field = field))) return field
        }
        return null
    }

    /**
     * The first active field covering [target] whose "blockStatStage" rule stops a change of [stages] to [stat] by
     * [source], or null. [estimate] = no dice.
     */
    fun statStageBlocker(target: Battler, stat: String, stages: Int, source: Battler?, estimate: Boolean = false): FieldInstance? {
        if (activeFields.isEmpty()) return null
        for (field in activeFields.toList()) {
            val rule = field.def.rules.blockStatStage ?: continue
            if (!field.covers(target)) continue
            val vars = mapOf("stat" to stat, "stages" to stages.toDouble())
            if (rule.test(context(target, source, source, target, vars = vars, estimate = estimate, field = field))) return field
        }
        return null
    }

    /**
     * Field triggers that listen to events about [subject], with their field, in the order the fields were set:
     * "covered" if the field covers it, "opponents" if it is on the other side of a side field, "all" always.
     */
    fun fieldTriggersFor(subject: Battler): List<Pair<Trigger, FieldInstance>> {
        if (activeFields.isEmpty()) return emptyList()
        val result = ArrayList<Pair<Trigger, FieldInstance>>()
        for (field in activeFields) {
            for (t in field.def.triggers) if (field.hears(t.audience, subject)) result += t.trigger to field
        }
        return result
    }

    /** The Rules.fallbackMove definition, or null. */
    fun fallbackMove(): MoveDef? = rules.fallbackMove?.let { setup.moves[it] }

    /**
     * Legal choices for a move whose target needsChoice (ENEMY -> enemies, ALLY -> allies, ALLY_OR_SELF -> self + allies,
     * FAINTED_ALLY -> [faintedAllies]). For other targets returns the monsters the move would hit.
     */
    fun targetOptions(user: Battler, move: MoveDef): List<Battler> = when (move.target) {
        MoveTarget.ENEMY, MoveTarget.ALL_ENEMIES, MoveTarget.RANDOM_ENEMY, MoveTarget.RANDOM_ENEMIES -> enemies(user)
        MoveTarget.LOWEST_HP_ALLY -> listOfNotNull(lowestHp(user.side.activeBattlers()))
        MoveTarget.LOWEST_HP_ENEMY -> listOfNotNull(lowestHp(enemies(user)))
        MoveTarget.SELF -> listOf(user)
        MoveTarget.ALLY, MoveTarget.ALL_ALLIES -> allies(user)
        MoveTarget.ALLY_OR_SELF -> listOf(user) + allies(user)
        MoveTarget.TEAM -> user.side.activeBattlers()
        MoveTarget.ALL -> allActive().filter { it !== user }
        MoveTarget.FAINTED_ALLY -> faintedAllies(user)
    }

    /**
     * True if [move] has at least one legal target for [user] right now. A usable move without one (an ally move with
     * no ally left, allAllies alone on the field) is not a legal choice: the Manager, the AI and the battle menu treat
     * it like an unusable move (and use Rules.fallbackMove when nothing else is left).
     */
    fun hasTarget(user: Battler, move: MoveDef): Boolean = targetOptions(user, move).isNotEmpty()

    /** True if Rules.switching and [b] has a benched teammate to switch to. */
    fun canSwitch(b: Battler): Boolean = rules.switching && switchOptions(b).isNotEmpty()

    /**
     * Benched teammates [b] could switch to. While a speed_order round is being planned, a teammate that an ally
     * already chose to switch in this round is not offered again.
     */
    fun switchOptions(b: Battler): List<Battler> {
        val bench = b.side.bench()
        return if (claimedSwitchIns.isEmpty()) bench else bench.filter { it !in claimedSwitchIns }
    }

    /** Benched monsters already picked by a planned switch of the round being planned (speed_order). */
    internal val claimedSwitchIns: MutableSet<Battler> = HashSet()

    /**
     * action_bar only: predicts who acts next ([count] entries, a battler may repeat) from current gauges
     * and fill rates, without changing state. Empty list for speed_order.
     */
    fun turnOrderPreview(count: Int): List<Battler> {
        if (rules.turnSystem != TurnSystem.ACTION_BAR || count <= 0) return emptyList()
        val actives = allActive()
        if (actives.isEmpty()) return emptyList()
        val gauges = DoubleArray(actives.size) { actives[it].gauge }
        // Estimate mode: a preview must never consume the battle's random numbers.
        val rates = DoubleArray(actives.size) { fillRate(actives[it], estimate = true) }
        val result = ArrayList<Battler>(count)
        repeat(count) {
            val next = selectNext(actives, gauges, rates, null)
            if (next < 0) return result
            advanceGauges(gauges, rates, timeToFull(gauges[next], rates[next]))
            gauges[next] = 0.0
            result += actives[next]
        }
        return result
    }

    /** Action bar points [b] gains per time unit (Rules.actionBar.fill, at least 0). */
    fun fillRate(b: Battler): Double = fillRate(b, estimate = false)

    private fun fillRate(b: Battler, estimate: Boolean): Double {
        val field = ruleOverride(b) { it.actionBarFill }
        val formula = field?.def?.rules?.actionBarFill ?: rules.actionBarFill
        val value = formula.constant
            ?: formula.eval(context(b, vars = mapOf("speed" to b.stat(rules.speedStat)), estimate = estimate, field = field))
        return if (value > 0.0) value else 0.0
    }

    /** Time units until a gauge at [gauge] filling at [rate] is full (0 if already full, infinite if it never fills). */
    internal fun timeToFull(gauge: Double, rate: Double): Double {
        val missing = rules.actionBarSize - gauge
        return when {
            missing <= GAUGE_EPSILON -> 0.0
            rate <= 0.0 -> Double.POSITIVE_INFINITY
            else -> missing / rate
        }
    }

    /** Adds rate x [time] to every gauge, capped at the bar size. */
    internal fun advanceGauges(gauges: DoubleArray, rates: DoubleArray, time: Double) {
        if (time <= 0.0) return
        val size = rules.actionBarSize
        for (i in gauges.indices) gauges[i] = (gauges[i] + rates[i] * time).coerceAtMost(size)
    }

    /**
     * Index of the battler that fills its bar first, or -1 if nobody ever will.
     * Ties: higher gauge (before advancing), then higher effective speed, then [random] (field order if null).
     */
    internal fun selectNext(actives: List<Battler>, gauges: DoubleArray, rates: DoubleArray, random: Random?): Int {
        var best = Double.POSITIVE_INFINITY
        for (i in actives.indices) best = minOf(best, timeToFull(gauges[i], rates[i]))
        if (best == Double.POSITIVE_INFINITY) return -1
        val tied = actives.indices.filter { timeToFull(gauges[it], rates[it]) <= best + TIME_EPSILON }
        if (tied.size == 1) return tied[0]
        val maxGauge = tied.maxOf { gauges[it] }
        val byGauge = tied.filter { gauges[it] >= maxGauge - GAUGE_EPSILON }
        if (byGauge.size == 1) return byGauge[0]
        val speeds = byGauge.associateWith { actives[it].stat(rules.speedStat) }
        val maxSpeed = speeds.values.max()
        val bySpeed = byGauge.filter { speeds.getValue(it) >= maxSpeed }
        if (bySpeed.size == 1 || random == null) return bySpeed[0]
        return bySpeed[random.nextInt(bySpeed.size)]
    }

    /**
     * Builds the formula context described in README "Formulas".
     * [user]/[target] default to [self]/[other]. [statusInstance] adds `stacks`/`turnsLeft`.
     * [rawStats] = true makes `<m>.<stat>` return raw stats (used while computing stat modifiers).
     * [power] is the power of [move] already evaluated for the hit in progress (`move.power` then reads it).
     * [field] adds that field's `stacks`/`turnsLeft` (when there is no [statusInstance]).
     * [recipient] is the monster an effect is being applied to (README "Formulas", `recipient`; effect values only).
     */
    fun context(
        self: Battler?,
        other: Battler? = null,
        user: Battler? = self,
        target: Battler? = other,
        move: MoveDef? = null,
        vars: Map<String, Any?> = emptyMap(),
        statusInstance: StatusInstance? = null,
        estimate: Boolean = false,
        rawStats: Boolean = false,
        power: Double? = null,
        field: FieldInstance? = null,
        recipient: Battler? = null,
    ): ExprContext = BattleExprContext(
        this, setup, self, other, user, target, move, vars, statusInstance, estimate, rawStats, fixedPower = power, field = field,
        recipient = recipient,
    )

    private companion object {
        const val GAUGE_EPSILON = 1e-9
        const val TIME_EPSILON = 1e-9
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Formula context

/** Floors to an Int (NaN -> 0, clamped to the Int range). */
internal fun Double.floorToInt(): Int = when {
    isNaN() -> 0
    this >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
    this <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
    else -> floor(this).toInt()
}

/** The battle functions of the formula language (README "Formulas") and how many arguments each takes. */
object BattleFunctions {
    val ARITIES: Map<String, Set<Int>> = linkedMapOf(
        "hasType" to setOf(2),
        "hasStatus" to setOf(2),
        "statusStacks" to setOf(2),
        "hasAbility" to setOf(2),
        "hasItem" to setOf(1, 2),
        "hasFlag" to setOf(1),
        "fieldActive" to setOf(1),
        "sideField" to setOf(2),
        "fieldStacks" to setOf(1),
        "sideFieldStacks" to setOf(2),
        "fieldTurns" to setOf(1),
        "typeMultiplier" to setOf(2),
        "sameSide" to setOf(2),
        "statusCount" to setOf(1, 2),
    )

    /** Functions whose argument at the given index is a field id (checked when it is a string literal). */
    val FIELD_ID_ARGUMENTS: Map<String, Int> = mapOf(
        "fieldActive" to 0, "sideField" to 1, "fieldStacks" to 0, "sideFieldStacks" to 1, "fieldTurns" to 0,
    )

    /** Field functions that read a global field ([FIELD_ID_ARGUMENTS] minus the side ones). */
    val GLOBAL_FIELD_FUNCTIONS: Set<String> = setOf("fieldStacks", "fieldTurns")

    /** Field functions that read a side field. */
    val SIDE_FIELD_FUNCTIONS: Set<String> = setOf("sideField", "sideFieldStacks")

    /** "hasType() takes 2 arguments but got 1" (also used by setup validation). */
    fun arityProblem(name: String, got: Int): String {
        val counts = ARITIES[name]?.sorted() ?: return "unknown function '$name'"
        val expected = counts.joinToString(" or ")
        val s = if (counts.size == 1 && counts[0] == 1) "" else "s"
        return "$name() takes $expected argument$s but got $got"
    }
}

/** What a monster reference evaluates to when the monster is absent (functions treat it as "no monster"). */
internal object AbsentMonster {
    override fun toString(): String = ""
}

/** A pre-parsed variable name (parsed once per distinct name, then cached). */
private class VarKey(val kind: Kind, val ref: Int = -1, val field: Field? = null, val sub: String? = null) {
    enum class Kind { TURN, ALLIES, ENEMIES, STACKS, TURNS_LEFT, REF, MONSTER, MOVE, OTHER }

    enum class Field {
        HP, MAX_HP, HP_PERCENT, LEVEL, GAUGE, SHIELD, FAINTED, REVIVES, REVIVE_BLOCKED, ACTED, CHARGING, ID, NAME, STAT, RAW, BASE, STAGE,
        MOVE_ID, MOVE_NAME, MOVE_TYPE, MOVE_CATEGORY, MOVE_POWER, MOVE_ACCURACY, MOVE_PRIORITY, MOVE_PP, MOVE_COOLDOWN,
    }

    companion object {
        private val cache = ConcurrentHashMap<String, VarKey>()
        private val OTHER = VarKey(Kind.OTHER)

        fun of(name: String): VarKey = cache[name] ?: parse(name).also { cache[name] = it }

        private fun refIndex(name: String): Int = when (name) {
            "self" -> 0
            "other" -> 1
            "user" -> 2
            "target" -> 3
            "recipient" -> 4
            else -> -1
        }

        private fun parse(name: String): VarKey {
            when (name) {
                "turn" -> return VarKey(Kind.TURN)
                "allies" -> return VarKey(Kind.ALLIES)
                "enemies" -> return VarKey(Kind.ENEMIES)
                "stacks" -> return VarKey(Kind.STACKS)
                "turnsLeft" -> return VarKey(Kind.TURNS_LEFT)
            }
            refIndex(name).let { if (it >= 0) return VarKey(Kind.REF, it) }
            val parts = name.split('.')
            if (parts.size < 2 || parts.any { it.isEmpty() }) return OTHER
            val ref = refIndex(parts[0])
            if (ref >= 0) {
                if (parts.size == 2) {
                    val field = when (parts[1]) {
                        "hp" -> Field.HP
                        "maxHp" -> Field.MAX_HP
                        "hpPercent" -> Field.HP_PERCENT
                        "level" -> Field.LEVEL
                        "gauge" -> Field.GAUGE
                        "shield" -> Field.SHIELD
                        "fainted" -> Field.FAINTED
                        "revives" -> Field.REVIVES
                        "reviveBlocked" -> Field.REVIVE_BLOCKED
                        "acted" -> Field.ACTED
                        "charging" -> Field.CHARGING
                        "id" -> Field.ID
                        "name" -> Field.NAME
                        else -> Field.STAT
                    }
                    return VarKey(Kind.MONSTER, ref, field, if (field == Field.STAT) parts[1] else null)
                }
                if (parts.size == 3) {
                    val field = when (parts[1]) {
                        "raw" -> Field.RAW
                        "base" -> Field.BASE
                        "stage" -> Field.STAGE
                        else -> return OTHER
                    }
                    return VarKey(Kind.MONSTER, ref, field, parts[2])
                }
                return OTHER
            }
            if (parts[0] == "move" && parts.size == 2) {
                val field = when (parts[1]) {
                    "id" -> Field.MOVE_ID
                    "name" -> Field.MOVE_NAME
                    "type" -> Field.MOVE_TYPE
                    "category" -> Field.MOVE_CATEGORY
                    "power" -> Field.MOVE_POWER
                    "accuracy" -> Field.MOVE_ACCURACY
                    "priority" -> Field.MOVE_PRIORITY
                    "pp" -> Field.MOVE_PP
                    "cooldown" -> Field.MOVE_COOLDOWN
                    else -> return OTHER
                }
                return VarKey(Kind.MOVE, field = field)
            }
            return OTHER
        }
    }
}

/**
 * The battle formula context (README "Formulas"). Everything is read live from the battle state, so one
 * instance stays valid while effects change HP, statuses, etc.
 */
internal class BattleExprContext(
    private val battle: Battle?,
    private val setup: Setup,
    private val self: Battler?,
    private val other: Battler?,
    private val user: Battler?,
    private val target: Battler?,
    private val move: MoveDef?,
    private val vars: Map<String, Any?>,
    private val statusInstance: StatusInstance?,
    override val estimate: Boolean,
    private val rawStats: Boolean,
    /** True while the move's own power formula is evaluated: `move.power` then reads 0 (no recursion). */
    private val insidePower: Boolean = false,
    /** The power of [move] already evaluated for the hit in progress (`move.power` reads it), or null. */
    private val fixedPower: Double? = null,
    /** The field whose formula or trigger is evaluated: `stacks` / `turnsLeft` read it (after [statusInstance]). */
    private val field: FieldInstance? = null,
    /** The monster the effect being evaluated is applied to (`recipient`), or null. */
    private val recipient: Battler? = null,
) : ExprContext {

    override val random: Random get() = battle?.random ?: Random.Default

    override fun variable(name: String): Any? {
        val key = VarKey.of(name)
        return when (key.kind) {
            VarKey.Kind.TURN -> (battle?.turn ?: 0).toDouble()
            VarKey.Kind.ALLIES -> countAllies().toDouble()
            VarKey.Kind.ENEMIES -> countEnemies().toDouble()
            VarKey.Kind.STACKS -> statusInstance?.stacks?.toDouble() ?: field?.stacks?.toDouble() ?: varValue(name) ?: 0.0
            VarKey.Kind.TURNS_LEFT -> statusInstance?.let { (it.turnsLeft ?: 0).coerceAtLeast(0).toDouble() }
                ?: field?.let { (it.turnsLeft ?: 0).coerceAtLeast(0).toDouble() } ?: varValue(name) ?: 0.0
            VarKey.Kind.REF -> monster(key.ref) ?: AbsentMonster
            VarKey.Kind.MONSTER -> monsterValue(monster(key.ref), key)
            VarKey.Kind.MOVE -> moveValue(key.field!!)
            VarKey.Kind.OTHER -> varValue(name)
        }
    }

    /** Battle functions; their arities ([BattleFunctions.ARITIES]) are also checked when a setup loads. */
    override fun function(name: String, args: List<Any?>): Any? {
        val arities = BattleFunctions.ARITIES[name] ?: return null
        if (args.size !in arities) throw ExpressionException(BattleFunctions.arityProblem(name, args.size))
        return when (name) {
            "hasType" -> bool(monsterArg(args[0])?.hasType(text(args[1])) == true)
            "hasStatus" -> bool(monsterArg(args[0])?.hasStatus(text(args[1])) == true)
            "statusStacks" -> (monsterArg(args[0])?.status(text(args[1]))?.stacks ?: 0).toDouble()
            "hasAbility" -> {
                val ability = monsterArg(args[0])?.activeAbility
                bool(ability != null && ability.id == text(args[1]))
            }
            "hasItem" -> {
                val item = monsterArg(args[0])?.activeItem
                bool(item != null && (args.size == 1 || item.id == text(args[1])))
            }
            "hasFlag" -> bool(move?.flags?.contains(text(args[0])) == true)
            "fieldActive" -> bool(battle?.isFieldActive(text(args[0])) == true)
            "sideField" -> bool(sideField(args[0], args[1]) != null)
            "fieldStacks" -> (battle?.field(text(args[0]))?.stacks ?: 0).toDouble()
            "sideFieldStacks" -> (sideField(args[0], args[1])?.stacks ?: 0).toDouble()
            "fieldTurns" -> (battle?.field(text(args[0]))?.turnsLeft ?: 0).coerceAtLeast(0).toDouble()
            "typeMultiplier" -> monsterArg(args[1])?.let { setup.types.multiplier(text(args[0]), it.types) } ?: 1.0
            "sameSide" -> {
                val a = monsterArg(args[0])
                val b = monsterArg(args[1])
                bool(a != null && b != null && a.side === b.side)
            }
            "statusCount" -> {
                val m = monsterArg(args[0])
                val kind = if (args.size > 1) StatusKind.fromId(text(args[1])) ?: return 0.0 else null
                (m?.statuses?.count { kind == null || it.def.kind == kind } ?: 0).toDouble()
            }
            else -> null
        }
    }

    /** The side field [id] on the side of monster [m], or null. */
    private fun sideField(m: Any?, id: Any?): FieldInstance? {
        val monster = monsterArg(m) ?: return null
        return battle?.field(text(id), monster.side)
    }

    private fun monster(ref: Int): Battler? = when (ref) {
        0 -> self
        1 -> other
        2 -> user
        3 -> target
        else -> recipient
    }

    private fun countAllies(): Int {
        val me = self ?: return 0
        return me.side.active.count { it != null && it !== me && !it.isFainted }
    }

    private fun countEnemies(): Int {
        val me = self ?: return 0
        val b = battle ?: return 0
        return b.opponentOf(me.side).active.count { it != null && !it.isFainted }
    }

    private fun isStat(id: String?): Boolean = id != null && setup.rules.stats.any { it.id == id }

    private fun monsterValue(m: Battler?, key: VarKey): Any? {
        val field = key.field!!
        if (m == null) {
            return when (field) {
                VarKey.Field.ID, VarKey.Field.NAME -> ""
                VarKey.Field.STAT, VarKey.Field.RAW, VarKey.Field.BASE, VarKey.Field.STAGE -> if (isStat(key.sub)) 0.0 else null
                else -> 0.0
            }
        }
        return when (field) {
            VarKey.Field.HP -> m.hp.toDouble()
            VarKey.Field.MAX_HP -> m.maxHp.toDouble()
            VarKey.Field.HP_PERCENT -> if (m.maxHp <= 0) 0.0 else m.hp * 100.0 / m.maxHp
            VarKey.Field.LEVEL -> m.level.toDouble()
            VarKey.Field.GAUGE -> m.gaugePercent()
            VarKey.Field.SHIELD -> m.shield.toDouble()
            VarKey.Field.FAINTED -> bool(m.isFainted)
            VarKey.Field.REVIVES -> m.revives.toDouble()
            VarKey.Field.REVIVE_BLOCKED -> bool(m.reviveBlocked)
            VarKey.Field.ACTED -> bool(acted(m))
            VarKey.Field.CHARGING -> bool(m.isCharging)
            VarKey.Field.ID -> m.species.id
            VarKey.Field.NAME -> m.name
            VarKey.Field.STAT -> {
                val id = key.sub!!
                when {
                    m.rawStats.containsKey(id) -> if (rawStats) m.rawStat(id) else m.stat(id)
                    isStat(id) -> 0.0
                    else -> null
                }
            }
            VarKey.Field.RAW -> m.rawStats[key.sub] ?: if (isStat(key.sub)) 0.0 else null
            VarKey.Field.BASE -> m.species.baseStats[key.sub] ?: if (isStat(key.sub)) 0.0 else null
            VarKey.Field.STAGE -> if (isStat(key.sub)) m.stage(key.sub!!).toDouble() else null
            else -> null
        }
    }

    /** `m.acted`: speed_order = it already acted this round; action_bar = it is the monster whose turn it is. */
    private fun acted(m: Battler): Boolean =
        if (setup.rules.turnSystem == TurnSystem.ACTION_BAR) battle?.currentActor === m else m.turnPhase == TurnPhase.ACTED

    private fun moveValue(field: VarKey.Field): Any? {
        val m = move
        if (m == null) {
            return when (field) {
                VarKey.Field.MOVE_ID, VarKey.Field.MOVE_NAME, VarKey.Field.MOVE_TYPE, VarKey.Field.MOVE_CATEGORY -> ""
                else -> 0.0
            }
        }
        return when (field) {
            VarKey.Field.MOVE_ID -> m.id
            VarKey.Field.MOVE_NAME -> m.name
            VarKey.Field.MOVE_TYPE -> m.type ?: ""
            VarKey.Field.MOVE_CATEGORY -> m.category
            VarKey.Field.MOVE_POWER -> movePower(m)
            VarKey.Field.MOVE_ACCURACY -> m.accuracy ?: 0.0
            VarKey.Field.MOVE_PRIORITY -> m.priority.toDouble()
            VarKey.Field.MOVE_PP -> m.pp.toDouble()
            VarKey.Field.MOVE_COOLDOWN -> m.cooldown.toDouble()
            else -> null
        }
    }

    /**
     * `move.power`: the power of the hit in progress when known, else the power formula evaluated like the damage
     * calculation does (README: in moves, `self`/`user` is the move user and `other`/`target` the target), whoever
     * owns the trigger that reads it.
     */
    private fun movePower(m: MoveDef): Double {
        val formula = m.power ?: return 0.0
        formula.constant?.let { return it.coerceAtLeast(0.0) }
        if (fixedPower != null && m === move) return fixedPower.coerceAtLeast(0.0)
        if (insidePower) return 0.0
        val moveUser = user ?: self
        val moveTarget = target ?: other
        val ctx = BattleExprContext(
            battle, setup, moveUser, moveTarget, moveUser, moveTarget, m, emptyMap(), null,
            estimate = estimate, rawStats = false, insidePower = true,
        )
        return formula.eval(ctx).let { if (it > 0.0) it else 0.0 }
    }

    private fun varValue(name: String): Any? {
        if (!vars.containsKey(name)) return ABSENT_EVENT_VARIABLES[name]
        return when (val v = vars[name]) {
            null -> 0.0
            is Double -> v
            is Number -> v.toDouble()
            is Boolean -> bool(v)
            else -> v
        }
    }

    private fun monsterArg(arg: Any?): Battler? = arg as? Battler

    private fun text(arg: Any?): String = when (arg) {
        null -> ""
        is String -> arg
        is Double -> if (arg == floor(arg) && !arg.isInfinite()) arg.toLong().toString() else arg.toString()
        else -> arg.toString()
    }

    private fun bool(value: Boolean): Double = if (value) 1.0 else 0.0

    private companion object {
        /** Event variables read as 0 / '' in events that do not provide them (README "Other variables"). */
        val ABSENT_EVENT_VARIABLES: Map<String, Any> =
            EventType.EVENT_VARIABLES.values.flatten().associateWith { if (it in EventType.STRING_EVENT_VARIABLES) "" else 0.0 }
    }
}
