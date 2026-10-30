package engine

import bakemon.FieldDef
import bakemon.HitAccuracy
import bakemon.MoveDef
import bakemon.MoveTarget
import bakemon.Stacking
import bakemon.StatusDef
import bakemon.StatusKind
import bakemon.TurnSystem
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** AI strength. */
enum class AiLevel(val id: String) {
    /** Random legal moves and targets. */
    EASY("easy"),
    /** Picks the move with the best expected damage (DamageCalculator estimate mode), likes finishing blows,
     *  sometimes uses status/support moves when they make sense. */
    NORMAL("normal"),
    /** NORMAL + switches out of very bad type matchups when switching is allowed, avoids re-applying
     *  statuses the target already has, and prefers healing/support when low. */
    HARD("hard");

    companion object {
        fun fromId(id: String): AiLevel? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** Computer-controlled side. Only reads the battle state; never mutates it. */
class AiController(override val name: String = "AI", val level: AiLevel = AiLevel.NORMAL) : Controller {
    /** Monsters this controller just switched in: they act at least once before being switched out again. */
    private val freshSwitchIns: MutableSet<Battler> = Collections.newSetFromMap(WeakHashMap())

    override fun chooseAction(battle: Battle, actor: Battler): Action {
        val justArrived = freshSwitchIns.remove(actor)
        val action = safely({ randomAction(battle, actor) }) {
            if (level == AiLevel.EASY) randomAction(battle, actor)
            else Planner(battle, actor, level).decide(allowSwitch = !justArrived)
        }
        if (action is Action.Switch) freshSwitchIns += action.replacement
        return action
    }

    override fun chooseReplacement(battle: Battle, side: Side, slot: Int, options: List<Battler>): Battler {
        if (options.size == 1) return options[0]
        return safely({ options[battle.random.nextInt(options.size)] }) {
            if (level == AiLevel.EASY) options[battle.random.nextInt(options.size)]
            else bestReplacement(battle, side, options)
        }
    }

    /**
     * NORMAL/HARD: the option with the best matchup = highest expected damage against the current enemies
     * (HARD also subtracts how hard the enemies hit it back); ties go to the healthiest option.
     */
    private fun bestReplacement(battle: Battle, side: Side, options: List<Battler>): Battler {
        val foes = battle.opponentOf(side)
        val enemies = foes.activeBattlers().ifEmpty { foes.team.filter { !it.isFainted } }
        val scored = options.map { option ->
            val planner = Planner(battle, option, level)
            var score = attempt(0.0) { planner.offense(enemies) }
            if (level == AiLevel.HARD) score -= 0.5 * attempt(0.0) { planner.danger(enemies) }
            option to (score * 1000).roundToLong()
        }
        return scored.maxWith(compareBy<Pair<Battler, Long>>({ it.second }, { it.first.hpFraction }, { it.first.hp })).first
    }
}

// -----------------------------------------------------------------------------------------------------------------
// Tuning. Every value is in "enemy HP bars": 1.0 = taking a full HP bar from an enemy.

/** Extra value for knocking out an enemy (NORMAL; HARD adds the target's threat on top). */
private const val KO_BONUS = 0.8
/** Recoil counts less than damage dealt ("small penalty"). */
private const val SELF_DAMAGE_WEIGHT = 0.6
/** Extra penalty when an effect would make the user faint. */
private const val SELF_FAINT_PENALTY = 0.5
/**
 * A x2 change of a stat that matters, per turn it lasts, in turns of its holder. Stat stages last about
 * [ASSUMED_TURNS] turns; their multiplier comes from Rules.statStages.multiplier.
 */
private const val STAT_WEIGHT = 0.25
/** A x2 damage multiplier from a status for one turn. */
private const val DAMAGE_MOD_VALUE = 0.3
/** How many owner turns a permanent status is assumed to matter for. */
private const val ASSUMED_TURNS = 4.0
/** Statuses are worth a fraction of their raw estimate (the fight may end before they pay off). */
private const val STATUS_SCALE = 0.45
/** A shield equal to the holder's max HP. */
private const val SHIELD_VALUE = 0.6
/** A revive-on-death effect held by a living monster (it may never be needed), per fraction of max HP restored. */
private const val REVIVE_INSURANCE = 0.3
/** Making a monster unrevivable (only matters if its side can revive). */
private const val PREVENT_REVIVE_VALUE = 0.05
/** Adding cooldown to every move of a monster, per turn added (x the value of its turn): it may lose turns. */
private const val COOLDOWN_LOCK = 0.5
/** Each turn of cooldown removed from a move (x the value of its owner's turn). */
private const val COOLDOWN_REFRESH = 0.25
/** Floor for the value of one turn of a monster that has damaging moves. */
private const val MIN_TURN_VALUE = 0.1
/** Value of one turn of a monster without damaging moves. */
private const val SUPPORT_TURN_VALUE = 0.2
/** Losing a held item. */
private const val ITEM_VALUE = 0.05
/** Fields are worth a fraction of their raw estimate (like statuses: the fight may end before they pay off). */
private const val FIELD_SCALE = 0.45
/** A hazard's effect on one benched monster counts this much (it may never come in, or come in several times). */
private const val HAZARD_SCALE = 0.5
/** Reversing the turn order for [ASSUMED_TURNS] turns when one side is clearly slower. */
private const val REVERSE_SPEED_VALUE = 0.3
/** HARD leaves a bad type matchup only if it cannot take more than this fraction of an enemy's remaining HP. */
private const val LOW_DAMAGE = 0.35
/** "Walled": no option takes more than this fraction of an enemy's remaining HP... */
private const val WALLED_DAMAGE = 0.05
/** ...and no option is worth more than this. */
private const val WALLED_VALUE = 0.1
/** A teammate must deal at least this much (offense score) to be worth switching to when walled. */
private const val WALLED_SWITCH_OFFENSE = 0.15
/** HARD never switches out a monster below this HP fraction, nor switches in one. */
private const val MIN_SWITCH_HP = 0.3
/** A delayed effect is worth this much less per turn it waits (the fight may change meanwhile). */
private const val DELAY_DISCOUNT = 0.85
/** Forcing a monster off the field, plus [STAGE_SWITCH_VALUE] per positive stat stage it loses. */
private const val FORCE_SWITCH_VALUE = 0.05
private const val STAGE_SWITCH_VALUE = 0.1
/** An invulnerable charge avoids this fraction of the enemies' best hit. */
private const val DODGE_VALUE = 0.25

// -----------------------------------------------------------------------------------------------------------------
// Helpers shared by every level

/** One legal way to use a move. [target] is set only when the move needsChoice. */
private class Option(val move: MoveDef, val slot: MoveSlot?, val target: Battler?) {
    fun toAction(): Action = Action.UseMove(move, slot, target)
}

/** Runs [block]; on any failure (except running out of memory) returns [fallback]'s result instead. */
private inline fun <T> safely(fallback: () -> T, block: () -> T): T =
    try {
        block()
    } catch (e: Throwable) {
        if (e is OutOfMemoryError) throw e
        fallback()
    }

private inline fun <T> attempt(default: T, block: () -> T): T = safely({ default }, block)

private fun finite(x: Double, default: Double): Double = if (x.isFinite()) x else default

/**
 * Every legal (move, target) pair: usable moves that have a legal target (one entry per target when the move
 * needsChoice), or the fallback move when no usable move has a legal target. Empty = the actor can only pass.
 * Options that a field's blockMove rule will stop against their primary target are dropped, unless nothing else is left.
 */
private fun legalOptions(battle: Battle, actor: Battler): List<Option> {
    val result = mutableListOf<Option>()
    fun add(move: MoveDef, slot: MoveSlot?) {
        // Like the Manager (Battle.hasTarget): a move with nobody to affect (allAllies alone on the field) is not an option.
        val targets = battle.targetOptions(actor, move)
        if (move.target.needsChoice) targets.forEach { result += Option(move, slot, it) }
        else if (targets.isNotEmpty()) result += Option(move, slot, null)
    }
    for (slot in battle.usableMoves(actor)) add(slot.def, slot)
    if (result.isEmpty()) battle.fallbackMove()?.let { add(it, null) }
    if (battle.fields.isEmpty()) return result
    return result.filter { !blockedAtExecution(battle, actor, it) }.ifEmpty { result }
}

/**
 * True if a field's blockMove rule will stop [option] once its primary target is known (the choice-time check in
 * Battle.usableMoves cannot see target-dependent rules). A randomEnemy move counts as blocked only when it is blocked
 * against every candidate. Estimate mode: no dice.
 */
private fun blockedAtExecution(battle: Battle, actor: Battler, option: Option): Boolean = safely({ false }) {
    val move = option.move
    val candidates = when {
        option.target != null -> listOf(option.target)
        move.target == MoveTarget.RANDOM_ENEMY -> battle.enemies(actor)
        else -> listOfNotNull(battle.targetOptions(actor, move).firstOrNull())
    }
    candidates.isNotEmpty() && candidates.all { battle.isMoveBlocked(actor, move, it, estimate = true) }
}

/**
 * [base] with `recipient` = [recipient]: the AI's estimates of an effect's values per target (like the EffectRunner,
 * which evaluates them with that target as `recipient`). Everything else reads [base].
 */
private class RecipientContext(private val base: ExprContext, private val recipient: Battler, private val battle: Battle) : ExprContext {
    /** `self` = the recipient: reads `recipient.<field>` as `self.<field>` (created the first time it is needed). */
    private var view: ExprContext? = null
    override val random get() = base.random
    override val estimate: Boolean get() = base.estimate

    override fun variable(name: String): Any? = when {
        name == RECIPIENT -> recipient
        name.startsWith(RECIPIENT_PREFIX) -> {
            val v = view ?: battle.context(recipient, estimate = base.estimate).also { view = it }
            v.variable("self." + name.substring(RECIPIENT_PREFIX.length))
        }
        else -> base.variable(name)
    }

    override fun function(name: String, args: List<Any?>): Any? = base.function(name, args)

    private companion object {
        const val RECIPIENT = "recipient"
        const val RECIPIENT_PREFIX = "recipient."
    }
}

/** EASY (and the last resort of the other levels): a random usable move, then a random legal target. */
private fun randomAction(battle: Battle, actor: Battler): Action = safely({ Action.Pass }) {
    val options = legalOptions(battle, actor)
    if (options.isEmpty()) return@safely Action.Pass
    val moves = options.map { it.move }.distinct()
    val move = moves[battle.random.nextInt(moves.size)]
    val forMove = options.filter { it.move === move }
    forMove[battle.random.nextInt(forMove.size)].toAction()
}

// -----------------------------------------------------------------------------------------------------------------
// NORMAL / HARD

private class Scored(
    val option: Option,
    /** Overall value for the AI's side. */
    val value: Double,
    /** Best hit-chance-weighted fraction of an enemy's remaining HP this option takes (0..1). */
    val damage: Double,
    /** Likely to knock an enemy out. */
    val ko: Boolean,
)

/** Expected result of one move against one target ([perHit] already includes damage modifiers). */
private class Hit(val perHit: Double, val hits: Double, val effectiveness: Double, val chance: Double) {
    val total: Double get() = perHit * hits
}

/**
 * Scores the options of one [actor] from its side's point of view. Reads the battle only through public API;
 * formulas are evaluated in estimate mode, so no dice are rolled while thinking.
 */
private class Planner(val battle: Battle, val actor: Battler, val level: AiLevel) {
    private val setup = battle.setup
    private val rules = setup.rules
    private val calc = DamageCalculator(setup)
    private val hard = level == AiLevel.HARD
    private val actionBar = rules.turnSystem == TurnSystem.ACTION_BAR
    private val threatCache = HashMap<Battler, Double>()
    private val turnValueCache = HashMap<Battler, Double>()

    fun decide(allowSwitch: Boolean): Action {
        val options = legalOptions(battle, actor)
        val scored = options.map { option -> safely({ Scored(option, 0.0, 0.0, false) }) { score(option) } }
        if (hard && allowSwitch) {
            attempt(null) { switchTarget(scored) }?.let { return Action.Switch(it) }
        }
        if (scored.isEmpty()) return Action.Pass
        // A little randomness: any option close to the best one may be picked.
        val best = scored.maxOf { it.value }
        val band = max(abs(best) * (if (hard) 0.05 else 0.10), 1e-6)
        val top = scored.filter { it.value >= best - band }
        return top[battle.random.nextInt(top.size)].option.toAction()
    }

    // ---- move scoring ------------------------------------------------------------------------------------------

    private fun score(option: Option): Scored {
        val move = option.move
        val enemies = battle.enemies(actor)
        // Who the move affects, with a weight (randomEnemy splits its value between the candidates).
        val random = move.target == MoveTarget.RANDOM_ENEMY || move.target == MoveTarget.RANDOM_ENEMIES
        val affected: List<Pair<Battler, Double>> = when {
            move.target.needsChoice -> listOfNotNull(option.target?.let { it to 1.0 })
            random -> enemies.map { it to 1.0 / enemies.size }
            else -> battle.targetOptions(actor, move).map { it to 1.0 }
        }
        val targetCount = if (random) 1 else max(1, affected.size)
        val primary = option.target ?: affected.firstOrNull()?.first
        val damaging = calc.isDamaging(move)

        var value = 0.0
        var bestDamage = 0.0
        var ko = false
        for ((target, weight) in affected) {
            val friend = target.side === actor.side
            val hit = expectedHit(actor, target, move, targetCount)
            val total = hit.total
            val effectiveHp = max(1.0, (target.hp + target.shield).toDouble())
            val kills = total > 0.0 && total >= effectiveHp
            var v = 0.0
            if (total > 0.0) {
                val taken = min(total, effectiveHp) / max(1, target.maxHp)
                val koValue = if (kills) koBonus(target) else 0.0
                if (friend) {
                    v -= taken + koValue
                } else {
                    // HARD focuses the enemies that hit hardest.
                    v += taken * (if (hard) 0.75 + 0.5 * threat(target) else 1.0) + koValue
                    bestDamage = max(bestDamage, hit.chance * min(total / effectiveHp, 1.0))
                    if (kills && hit.chance >= 0.7) ko = true
                }
            }
            // Like the Manager: type immunity only stops damaging moves; a status move still fires `hit`
            // (with effectiveness 1), so its effects count even against (or by) an immune type.
            if (!damaging || hit.effectiveness > 0.0) {
                val effectiveness = if (damaging) hit.effectiveness else 1.0
                // Like the Manager: `hit` sees the damage the hit really does (not beyond the remaining HP + shield).
                val vars = mapOf("damage" to min(hit.perHit, effectiveHp), "isCrit" to 0.0, "effectiveness" to effectiveness)
                val doomed = kills && !friend
                v += triggerValue(move, EventType.HIT, target, if (damaging) hit.hits else 1.0, vars, doomed)
                if (doomed) v += triggerValue(move, EventType.KNOCKED_OUT, target, 1.0, emptyMap(), true)
            }
            value += weight * hit.chance * v
            if (hit.chance < 1.0) {
                value += weight * (1.0 - hit.chance) * triggerValue(move, EventType.MISSED, target, 1.0, emptyMap(), false)
            }
        }
        value += triggerValue(move, EventType.AFTER_MOVE, primary, 1.0, emptyMap(), false)
        value *= 1.0 - cancelChance(move, primary)
        // A charge move pays off turns later (and dodges the enemies' hits meanwhile if it is invulnerable).
        val charge = move.charge
        if (charge != null && attempt(true) { charge.skipIf?.let { num(it, battle.context(actor, primary, move = move, estimate = true), 0.0) == 0.0 } ?: true }) {
            value /= 1.0 + charge.turns
            if (charge.invulnerable) value += DODGE_VALUE * attempt(0.0) { danger(enemies) }
        }
        return Scored(option, finite(value, 0.0), bestDamage, ko)
    }

    /**
     * Expected damage of [move] from [user] on [target] (0 for non-damaging moves or immunity). A target charging out
     * of reach cannot be hit (in speed_order only when it releases after [user] acts, approximated by speed).
     */
    private fun expectedHit(user: Battler, target: Battler, move: MoveDef, targets: Int): Hit {
        var chance = finite(attempt(1.0) { calc.hitChance(battle, user, target, move, estimate = true) }, 1.0).coerceIn(0.0, 1.0)
        if (target !== user && target.isInvulnerable && (actionBar || user.stat(rules.speedStat) >= target.stat(rules.speedStat))) chance = 0.0
        val effectiveness = finite(attempt(1.0) { calc.effectiveness(move, target) }, 1.0)
        if (effectiveness <= 0.0 || !calc.isDamaging(move)) return Hit(0.0, 1.0, max(effectiveness, 0.0), chance)
        val ctx = battle.context(user, target, move = move, estimate = true)
        var hits = num(move.hits, ctx, 1.0).coerceAtLeast(1.0)
        val perHit = if (calc.perHitPower(move)) {
            // Power that changes with hitIndex / hitCount: the average over the planned hits.
            val n = max(1, hits.roundToInt())
            var sum = 0.0
            for (i in 1..n) {
                val r = calc.calculate(battle, user, target, move, targets, estimate = true, hitIndex = i, hitCount = n)
                val base = r.damage.toDouble()
                sum += base * damageModifiers(user, target, move, base, effectiveness, r.power)
            }
            sum / n
        } else {
            val r = calc.calculate(battle, user, target, move, targets, estimate = true)
            val base = r.damage.toDouble()
            base * damageModifiers(user, target, move, base, effectiveness, r.power)
        }
        // Every hit rolls its accuracy: the hits after a miss are lost.
        if (move.hitAccuracy == HitAccuracy.EACH && chance < 1.0 && hits > 1.0) {
            var expected = 0.0
            var p = 1.0
            repeat(max(1, hits.roundToInt())) {
                expected += p
                p *= chance
            }
            hits = expected
        }
        return Hit(perHit, hits, effectiveness, chance)
    }

    /** Expected multiplyDamage factor from modifyDamageDealt (move, user) and modifyDamageTaken (target) triggers. */
    private fun damageModifiers(user: Battler, target: Battler, move: MoveDef, damage: Double, effectiveness: Double, power: Double): Double {
        var multiplier = 1.0
        fun collect(owner: Battler, other: Battler, event: EventType, withMove: Boolean) {
            for (ref in triggersOf(owner, if (withMove) move else null)) {
                val trigger = ref.trigger
                if (trigger.on != event || trigger.effects.none { it is Effect.MultiplyDamage }) continue
                // Like the Manager: `damage` is the damage still pending after the multipliers applied so far.
                val vars = mapOf("damage" to damage * multiplier, "isCrit" to 0.0, "effectiveness" to effectiveness)
                val ctx = battle.context(owner, other, user, target, move, vars, ref.status, estimate = true, power = power, field = ref.field)
                val p = probability(trigger, owner, ctx)
                if (p <= 0.0) continue
                for (effect in trigger.effects) {
                    if (effect is Effect.MultiplyDamage) multiplier *= 1.0 + p * (num(effect.value, ctx, 1.0).coerceAtLeast(0.0) - 1.0)
                }
            }
        }
        collect(user, target, EventType.MODIFY_DAMAGE_DEALT, withMove = true)
        collect(target, user, EventType.MODIFY_DAMAGE_TAKEN, withMove = false)
        return finite(multiplier, 1.0).coerceAtLeast(0.0)
    }

    /** A trigger that fires for some owner, with the status or field that holds it (for `stacks` / `turnsLeft`). */
    private class TriggerRef(val trigger: Trigger, val status: StatusInstance?, val field: FieldInstance?)

    /**
     * Triggers that fire for [owner]: the move in use, its ability, its item (unless a field suppresses them), its
     * statuses, then the active fields that listen to it (also for a benched [owner], as if it were on the field).
     */
    private fun triggersOf(owner: Battler, move: MoveDef?): List<TriggerRef> {
        val list = mutableListOf<TriggerRef>()
        move?.triggers?.forEach { list += TriggerRef(it, null, null) }
        owner.activeAbility?.triggers?.forEach { list += TriggerRef(it, null, null) }
        owner.activeItem?.triggers?.forEach { list += TriggerRef(it, null, null) }
        for (instance in owner.statuses) instance.def.triggers.forEach { list += TriggerRef(it, instance, null) }
        for (field in battle.fields) {
            for (t in field.def.triggers) if (field.hears(t.audience, owner, onField = false)) list += TriggerRef(t.trigger, null, field)
        }
        return list
    }

    /** Probability that [trigger] fires: limit, condition (estimate mode: chance(p) reads as p/100) and chance. */
    private fun probability(trigger: Trigger, owner: Battler, ctx: ExprContext): Double {
        if (trigger.limit > 0 && (owner.triggerUses[trigger] ?: 0) >= trigger.limit) return 0.0
        val condition = trigger.condition?.let { formula ->
            val v = attempt(Double.NaN) { formula.eval(ctx) }
            when {
                v.isNaN() -> 0.5
                v == 0.0 -> 0.0
                v > 0.0 && v < 1.0 -> v
                else -> 1.0
            }
        } ?: 1.0
        val chance = trigger.chance?.let { (num(it, ctx, 100.0) / 100.0).coerceIn(0.0, 1.0) } ?: 1.0
        return condition * chance
    }

    /** Chance that the move's own beforeMove triggers cancel it. */
    private fun cancelChance(move: MoveDef, primary: Battler?): Double {
        var keep = 1.0
        for (trigger in move.triggers) {
            if (trigger.on != EventType.BEFORE_MOVE || trigger.effects.none { it is Effect.CancelAction }) continue
            val ctx = battle.context(actor, primary, actor, primary, move, estimate = true)
            keep *= 1.0 - probability(trigger, actor, ctx)
        }
        return (1.0 - keep).coerceIn(0.0, 1.0)
    }

    /** Value of the move's triggers for [event] against [target], fired [n] times. */
    private fun triggerValue(
        move: MoveDef,
        event: EventType,
        target: Battler?,
        n: Double,
        vars: Map<String, Any?>,
        doomed: Boolean,
    ): Double {
        var sum = 0.0
        for (trigger in move.triggers) {
            if (trigger.on != event) continue
            sum += attempt(0.0) {
                val ctx = battle.context(actor, target, actor, target, move, vars, estimate = true)
                val p = probability(trigger, actor, ctx)
                var s = 0.0
                if (p > 0.0) {
                    for (effect in trigger.effects) {
                        for ((b, w) in resolve(effect.target, actor, target, reachesFainted(effect))) {
                            // Effects on a monster that this move knocks out are wasted.
                            if (doomed && b === target) continue
                            s += w * attempt(0.0) { effectValue(effect, b, ctx, p, n, actor, actor, 0) }
                        }
                    }
                }
                s
            }
        }
        return sum
    }

    /**
     * Resolves an effect selector from [owner]'s point of view; [other] is the event's other monster.
     * Fainted monsters are only kept for the revive effects ([includeFainted]), like the EffectRunner.
     */
    private fun resolve(
        selector: TargetSelector,
        owner: Battler,
        other: Battler?,
        includeFainted: Boolean = false,
    ): List<Pair<Battler, Double>> =
        when (selector) {
            TargetSelector.SELF, TargetSelector.USER -> listOf(owner to 1.0)
            TargetSelector.OTHER, TargetSelector.TARGET ->
                if (other != null && (includeFainted || !other.isFainted)) listOf(other to 1.0) else emptyList()
            TargetSelector.ALLIES -> battle.allies(owner).map { it to 1.0 }
            TargetSelector.TEAM -> (listOf(owner) + battle.allies(owner)).map { it to 1.0 }
            TargetSelector.ENEMIES -> battle.enemies(owner).map { it to 1.0 }
            TargetSelector.RANDOM_ENEMY -> battle.enemies(owner).let { list -> list.map { it to 1.0 / list.size } }
            TargetSelector.ALL -> battle.allActive().map { it to 1.0 }
            TargetSelector.FAINTED_ALLIES -> if (includeFainted) battle.faintedAllies(owner).map { it to 1.0 } else emptyList()
            TargetSelector.RANDOM_FAINTED_ALLY ->
                if (includeFainted) battle.faintedAllies(owner).let { list -> list.map { it to 1.0 / list.size } } else emptyList()
            TargetSelector.LOWEST_HP_ALLY -> listOfNotNull(battle.lowestHp(listOf(owner) + battle.allies(owner))?.let { it to 1.0 })
            TargetSelector.LOWEST_HP_ENEMY -> listOfNotNull(battle.lowestHp(battle.enemies(owner))?.let { it to 1.0 })
            TargetSelector.HIGHEST_HP_ENEMY -> listOfNotNull(battle.highestHp(battle.enemies(owner))?.let { it to 1.0 })
            TargetSelector.RANDOM_ALLY -> battle.allies(owner).let { list -> list.map { it to 1.0 / list.size } }
            // In the AI's estimates the event's other monster is never the user of a move.
            TargetSelector.OTHER_TARGET -> emptyList()
        }

    private fun reachesFainted(effect: Effect): Boolean = effect is Effect.Revive || effect is Effect.PreventRevive

    // ---- effect valuation --------------------------------------------------------------------------------------

    /**
     * Value for [perspective]'s side of [effect] hitting [b], with probability [p] per occurrence and [n]
     * occurrences. [depth] 0 = an effect of the move being considered; 1+ = inside a status estimate.
     */
    private fun effectValue(
        effect: Effect,
        b: Battler,
        triggerCtx: ExprContext,
        p: Double,
        n: Double,
        perspective: Battler,
        source: Battler?,
        depth: Int,
    ): Double {
        // Like the EffectRunner: the effect's values see b as `recipient`.
        val ctx: ExprContext = RecipientContext(triggerCtx, b, battle)
        val side = if (b.side === perspective.side) 1.0 else -1.0
        val maxHp = max(1, b.maxHp).toDouble()
        return when (effect) {
            is Effect.Damage -> {
                val amount = num(effect.amount, ctx, 0.0).coerceAtLeast(0.0)
                if (amount <= 0.0) return 0.0
                val effectiveHp = (b.hp + b.shield).toDouble()
                val taken = min(amount * n * p, effectiveHp) / maxHp
                val lethal = depth == 0 && amount * n >= effectiveHp
                if (side > 0) {
                    val recoil = depth == 0 && b === actor
                    val faint = if (lethal) (if (recoil) SELF_FAINT_PENALTY else KO_BONUS) * min(1.0, p) else 0.0
                    -(taken * (if (recoil) SELF_DAMAGE_WEIGHT else 1.0) + faint)
                } else {
                    taken + if (lethal) koBonus(b) * min(1.0, p) else 0.0
                }
            }
            is Effect.Heal -> {
                val amount = num(effect.amount, ctx, 0.0).coerceAtLeast(0.0) * n
                // Healing over time (inside a status) is mostly wasted on a healthy holder.
                if (depth > 0) return side * min(amount, maxHp * 0.5) / maxHp * p * (0.3 + 0.4 * (1.0 - b.hpFraction))
                val missing = (b.maxHp - b.hp).toDouble()
                if (missing <= 0.0 || amount <= 0.0) 0.0
                else side * min(amount, missing) / maxHp * p * (if (side > 0) healUrgency(b) else 1.0)
            }
            is Effect.ApplyStatus -> applyStatusValue(effect, b, ctx, p, n, perspective, source, depth)
            is Effect.RemoveStatus -> {
                if (depth >= 2) return 0.0
                b.statuses.filter { instance ->
                    (effect.status == null || effect.status.equals("all", ignoreCase = true) || instance.def.id == effect.status) &&
                        (effect.kind == null || instance.def.kind == effect.kind)
                }.sumOf { instance ->
                    -side * p * attempt(0.0) {
                        statusImpact(instance.def, b, instance.source, instance.stacks, instance.turnsLeft?.toDouble(), depth + 1)
                    }
                }
            }
            is Effect.StatStage -> {
                if (effect.stat == rules.hpStat) return 0.0
                val stages = (num(effect.stages, ctx, 0.0) * n).roundToInt()
                val current = b.stage(effect.stat)
                val delta = (current + stages).coerceIn(rules.stageMin, rules.stageMax) - current
                if (delta == 0) return 0.0
                // A field that blocks this change (a mist) makes it worthless.
                if (attempt(null) { battle.statStageBlocker(b, effect.stat, stages, source, estimate = true) } != null) return 0.0
                // Boosting past +2 (or dropping below -2) gives less and less.
                val diminish = when {
                    delta > 0 && current >= 4 -> 0.15
                    delta > 0 && current >= 2 -> 0.4
                    delta < 0 && current <= -4 -> 0.15
                    delta < 0 && current <= -2 -> 0.4
                    else -> 1.0
                }
                val change = log2(stageMultiplier(current + delta) / stageMultiplier(current))
                side * change * STAT_WEIGHT * ASSUMED_TURNS * turnValue(b) * statRelevance(effect.stat, b) * diminish *
                    (0.3 + 0.7 * b.hpFraction) * p
            }
            is Effect.Gauge -> {
                if (!actionBar) return 0.0
                val amount = num(effect.amount, ctx, 0.0) * n * p
                val current = finite(attempt(0.0) { b.gaugePercent() }, 0.0)
                val delta = (current + amount).coerceIn(0.0, 100.0) - current
                // A full bar is one more turn of b.
                side * delta / 100.0 * turnValue(b)
            }
            is Effect.Shield -> {
                val amount = num(effect.amount, ctx, 0.0).coerceAtLeast(0.0) * n * p
                side * min(amount, maxHp) / maxHp * SHIELD_VALUE
            }
            is Effect.Cooldown -> {
                if (!rules.useCooldowns) return 0.0
                val amount = num(effect.amount, ctx, 0.0) * n
                val forHolder = if (amount > 0.0) -min(amount, 2.0) * COOLDOWN_LOCK * turnValue(b)
                // Only one move is used per turn: refreshing several moves is not worth more than -amount turns.
                else min(b.moves.sumOf { min(it.cooldown.toDouble(), -amount) }, -amount) * COOLDOWN_REFRESH * turnValue(b)
                side * forHolder * p
            }
            is Effect.ConsumeItem -> if (b.item == null) 0.0 else -side * ITEM_VALUE * p
            is Effect.SetField -> setFieldValue(effect, ctx, p, perspective, depth)
            is Effect.RemoveField -> removeFieldValue(effect, p, perspective, depth)
            is Effect.Revive -> {
                if (b.reviveBlocked) return 0.0
                val restored = min(num(effect.amount, ctx, 0.0).coerceAtLeast(1.0), maxHp) / maxHp
                // Bringing a fallen monster back undoes a knockout; inside a status (a revive-on-death buff) it is
                // insurance that may never pay off.
                if (b.isFainted) side * KO_BONUS * (0.5 + restored) * min(1.0, p * n)
                else side * REVIVE_INSURANCE * restored * p
            }
            is Effect.PreventRevive -> if (b.reviveBlocked) 0.0 else -side * PREVENT_REVIVE_VALUE * p
            is Effect.UseMove -> useMoveValue(effect, b, ctx, p, n, side, depth)
            // Another turn of b, unless it cannot get one now (see Manager.grantExtraTurn).
            is Effect.ExtraTurn -> {
                val refused = b.extraTurnPending || if (actionBar) b.extraTurnTaken else b.extraTurnRound >= battle.turn
                if (refused) 0.0 else side * turnValue(b) * min(1.0, p * n)
            }
            is Effect.TransferStatus -> transferValue(effect, b, ctx, p, perspective, depth)
            is Effect.CopyStages -> resolve(effect.to, perspective, null).sumOf { (to, w) ->
                if (to === b) 0.0 else w * stagesValue(to, b.stages, perspective) * p
            }
            is Effect.SetHp -> {
                val amount = num(effect.amount, ctx, b.hp.toDouble()).coerceIn(0.0, maxHp)
                hpChangeValue(b, amount, side, p, depth)
            }
            is Effect.BalanceHp -> {
                val group = resolve(effect.target, perspective, null).map { it.first }.filter { !it.isFainted }
                if (group.size < 2) 0.0 else hpChangeValue(b, group.sumOf { it.hpFraction } / group.size * maxHp, side, p, depth)
            }
            is Effect.SwapHp -> {
                // The owner (perspective) gets b's HP percent, b gets the owner's.
                if (b === perspective) return 0.0
                hpChangeValue(b, perspective.hpFraction * maxHp, side, p, depth) + (b.hpFraction - perspective.hpFraction) * p
            }
            is Effect.ForceSwitch -> if (b.side.bench().isEmpty()) 0.0 else -side * switchLoss(b) * p
            is Effect.SwitchOut -> {
                if (b.side.bench().isEmpty()) 0.0
                else if (side > 0) (FORCE_SWITCH_VALUE + (if (hard) 0.3 * attempt(0.0) { Planner(battle, b, level).danger(battle.enemies(b)) } else 0.0) -
                    (switchLoss(b) - FORCE_SWITCH_VALUE)) * p
                else -switchLoss(b) * p
            }
            is Effect.Delay -> {
                if (depth >= 2) return 0.0
                val turns = num(effect.turns, ctx, 1.0).coerceAtLeast(1.0)
                val discount = DELAY_DISCOUNT.pow(turns)
                effect.effects.sumOf { inner ->
                    resolve(inner.target, perspective, b, reachesFainted(inner)).sumOf { (x, w) ->
                        w * attempt(0.0) { effectValue(inner, x, ctx, p, n, perspective, source, depth + 1) }
                    }
                } * discount
            }
            is Effect.Attack -> attackValue(effect, b, ctx, p, n, perspective, side, depth)
            is Effect.MultiplyDamage, is Effect.CancelAction, is Effect.Message -> 0.0
        }
    }

    /** The value of [b]'s HP becoming [amount] for its side ([side]), like damage or healing. */
    private fun hpChangeValue(b: Battler, amount: Double, side: Double, p: Double, depth: Int): Double {
        val maxHp = max(1, b.maxHp).toDouble()
        val delta = (amount - b.hp) / maxHp
        if (delta == 0.0) return 0.0
        var v = delta * p * (if (delta > 0 && side > 0) healUrgency(b) else 1.0)
        // Setting an enemy's HP to 0 knocks it out.
        if (amount <= 0.0 && depth == 0) v -= (if (side > 0) SELF_FAINT_PENALTY else koBonus(b)) * min(1.0, p)
        return side * v
    }

    /** What [b] loses when it leaves the field: its positive stat stages (plus a little for the lost tempo). */
    private fun switchLoss(b: Battler): Double = FORCE_SWITCH_VALUE + STAGE_SWITCH_VALUE * b.stages.values.sumOf { max(it, 0) }

    /** Value for [perspective]'s side of [to]'s stat stages becoming [stages]. */
    private fun stagesValue(to: Battler, stages: Map<String, Int>, perspective: Battler): Double {
        val sign = if (to.side === perspective.side) 1.0 else -1.0
        var total = 0.0
        for (stat in (stages.keys + to.stages.keys).distinct()) {
            if (stat == rules.hpStat) continue
            val change = log2(stageMultiplier(stages[stat] ?: 0) / stageMultiplier(to.stage(stat)))
            total += change * STAT_WEIGHT * ASSUMED_TURNS * turnValue(to) * statRelevance(stat, to)
        }
        return sign * finite(total, 0.0)
    }

    /** transferStatus from [from]: the statuses it loses (steal) and those the receivers get. */
    private fun transferValue(effect: Effect.TransferStatus, from: Battler, ctx: ExprContext, p: Double, perspective: Battler, depth: Int): Double {
        if (depth >= 2) return 0.0
        val count = num(effect.count, ctx, 1.0).toInt()
        val matching = from.statuses.filter { instance ->
            (effect.status == null || effect.status.equals("all", ignoreCase = true) || instance.def.id == effect.status) &&
                (effect.kind == null || instance.def.kind == effect.kind)
        }.let { if (count <= 0) it else it.take(count) }
        if (matching.isEmpty()) return 0.0
        val receivers = resolve(effect.to, perspective, null).filter { it.first !== from }
        var total = 0.0
        for (instance in matching) {
            val turns = instance.turnsLeft?.toDouble()
            var given = false
            for ((to, w) in receivers) {
                if (instance.def.immuneTypes.any { to.hasType(it) }) continue
                given = true
                val sign = if (to.side === perspective.side) 1.0 else -1.0
                total += w * sign * attempt(0.0) { statusImpact(instance.def, to, perspective, instance.stacks, turns, depth + 1) }
            }
            if (effect.mode == TransferMode.STEAL && given) {
                val sign = if (from.side === perspective.side) 1.0 else -1.0
                total -= sign * attempt(0.0) { statusImpact(instance.def, from, instance.source, instance.stacks, turns, depth + 1) }
            }
        }
        return finite(total, 0.0) * p
    }

    /** useMove: roughly what the move of [actor] is worth (its best expected hit, scaled by its power percent). */
    private fun useMoveValue(effect: Effect.UseMove, actor: Battler, ctx: ExprContext, p: Double, n: Double, side: Double, depth: Int): Double {
        if (depth >= 1 || !actor.isActive) return 0.0
        val move = when (effect.move) {
            Effect.FIRST_MOVE, Effect.SAME_MOVE -> actor.moves.firstOrNull()?.def
            else -> actor.moves.firstOrNull { it.def.id == effect.move }?.def ?: setup.moves[effect.move]
        } ?: return 0.0
        val scale = num(effect.power, ctx, 100.0).coerceAtLeast(0.0) / 100.0
        if (!calc.isDamaging(move)) return side * SUPPORT_TURN_VALUE * 0.5 * p * n
        var best = 0.0
        for (foe in battle.enemies(actor)) {
            val hit = attempt(null) { expectedHit(actor, foe, move, 1) } ?: continue
            best = max(best, hit.chance * min(hit.total * scale, (foe.hp + foe.shield).toDouble()) / max(1, foe.maxHp))
        }
        return side * best * p * n
    }

    /** attack: an expected strike of the owner ([perspective]) against [b], like a damage effect. */
    private fun attackValue(effect: Effect.Attack, b: Battler, ctx: ExprContext, p: Double, n: Double, perspective: Battler, side: Double, depth: Int): Double {
        val power = num(effect.power, ctx, 0.0)
        if (power <= 0.0) return 0.0
        val category = effect.category ?: rules.categories.values.firstOrNull { it.dealsDamage }?.id ?: return 0.0
        val strike = MoveDef(
            "", "", "", effect.type, category, Formula.of(power), null, 0, 0, 0, MoveTarget.ENEMY, Formula.of(1.0), null, emptySet(), emptyList(),
        )
        val damage = attempt(0.0) { calc.calculate(battle, perspective, b, strike, 1, estimate = true).damage.toDouble() }
        if (damage <= 0.0) return 0.0
        val effectiveHp = (b.hp + b.shield).toDouble()
        val taken = min(damage * n * p, effectiveHp) / max(1, b.maxHp)
        val lethal = depth == 0 && damage * n >= effectiveHp
        return if (side > 0) -(taken + if (lethal) KO_BONUS * min(1.0, p) else 0.0)
        else taken + if (lethal) koBonus(b) * min(1.0, p) else 0.0
    }

    // ---- fields --------------------------------------------------------------------------------------------------

    /** The places a field effect acts on, from [owner]'s point of view (null = global), like the EffectRunner. */
    private fun fieldPlaces(def: FieldDef?, side: FieldSide, owner: Battler): List<Side?> {
        if (def != null && def.isGlobal) return listOf(null)
        val enemy = battle.opponentOf(owner.side)
        return when (side) {
            FieldSide.SELF -> listOf(owner.side)
            FieldSide.ENEMY -> listOf(enemy)
            FieldSide.BOTH -> listOf(owner.side, enemy)
        }
    }

    /**
     * Value for [perspective]'s side of a setField effect: the field's impact where it lands (minus the impact of the
     * fields of its group it replaces). Nothing when it is active and cannot stack further; a new layer is worth what
     * it adds.
     */
    private fun setFieldValue(effect: Effect.SetField, ctx: ExprContext, p: Double, perspective: Battler, depth: Int): Double {
        if (depth >= 2) return 0.0
        val def = setup.fields[effect.field] ?: return 0.0
        val chance = effect.chance?.let { (num(it, ctx, 100.0) / 100.0).coerceIn(0.0, 1.0) } ?: 1.0
        val probability = (p * chance).coerceIn(0.0, 1.0)
        if (probability <= 0.0) return 0.0
        val stacks = effect.stacks?.let { num(it, ctx, 1.0).roundToInt() }?.coerceAtLeast(1) ?: 1
        val duration = effect.duration?.let { num(it, ctx, ASSUMED_TURNS).roundToInt() }
            ?: def.duration?.let { num(it, attempt(null) { battle.context(perspective, estimate = true) }, ASSUMED_TURNS).roundToInt() }
        var total = 0.0
        for (place in fieldPlaces(def, effect.side ?: FieldSide.SELF, perspective)) {
            val existing = battle.field(def.id, place)
            total += if (existing != null) {
                when (def.stacking) {
                    Stacking.IGNORE -> 0.0
                    Stacking.REFRESH -> {
                        val left = existing.turnsLeft
                        if (hard && left != null && left <= 1) 0.5 * fieldImpact(def, place, existing.stacks, duration, perspective, depth + 1) else 0.0
                    }
                    Stacking.STACK -> {
                        val next = min(existing.stacks + stacks, def.maxStacks)
                        if (next <= existing.stacks) 0.0
                        else fieldImpact(def, place, next, duration, perspective, depth + 1) -
                            fieldImpact(def, place, existing.stacks, duration, perspective, depth + 1)
                    }
                }
            } else {
                var v = fieldImpact(def, place, min(stacks, def.maxStacks), duration, perspective, depth + 1)
                val group = def.group
                if (group != null) {
                    for (other in battle.fields) {
                        if (other.def.group == group && other.side === place) {
                            v -= fieldImpact(other.def, place, other.stacks, other.turnsLeft, perspective, depth + 1)
                        }
                    }
                }
                v
            }
        }
        return finite(total, 0.0) * FIELD_SCALE * probability
    }

    /** Value for [perspective]'s side of a removeField effect: minus the impact of every field it would remove. */
    private fun removeFieldValue(effect: Effect.RemoveField, p: Double, perspective: Battler, depth: Int): Double {
        if (depth >= 2 || p <= 0.0) return 0.0
        val places = fieldPlaces(null, effect.side ?: FieldSide.BOTH, perspective)
        var total = 0.0
        for (instance in battle.fields) {
            val def = instance.def
            val byId = effect.field != null && (effect.field.equals("all", ignoreCase = true) || def.id == effect.field)
            val byGroup = effect.group != null && def.group == effect.group
            if (!(byId || byGroup) || (instance.side != null && places.none { it === instance.side })) continue
            total -= fieldImpact(def, instance.side, instance.stacks, instance.turnsLeft, perspective, depth + 1)
        }
        return finite(total, 0.0) * FIELD_SCALE * min(1.0, p)
    }

    /**
     * Rough value for [perspective]'s side of field [def] being active in [place] (null = global) with [stacks] for
     * [turnsLeft] turns (null = permanent): its stat modifiers and the triggers it gives the monsters on the field
     * (per turn), its switchIn triggers for the benched monsters (hazards), and reverseSpeed. Positive = good for
     * [perspective]'s side. Rule overrides and blocks are not estimated.
     */
    private fun fieldImpact(def: FieldDef, place: Side?, stacks: Int, turnsLeft: Int?, perspective: Battler, depth: Int): Double {
        if (depth > 2) return 0.0
        val turns = turnsLeft?.let { min(max(it.toDouble(), 0.5), ASSUMED_TURNS) } ?: ASSUMED_TURNS
        val probe = FieldInstance(def, place, turnsLeft, stacks, null)
        var total = 0.0
        for (side in battle.sides) {
            val sign = if (side === perspective.side) 1.0 else -1.0
            for (b in side.activeBattlers()) {
                var v = 0.0
                if (probe.covers(b)) {
                    for ((stat, formula) in def.statModifiers) {
                        val mctx = attempt(null) { battle.context(b, estimate = true, rawStats = true, field = probe) }
                        val multiplier = num(formula, mctx, 1.0).coerceAtLeast(0.05)
                        v += log2(multiplier) * STAT_WEIGHT * turnValue(b) * statRelevance(stat, b) * turns
                    }
                }
                for (ft in def.triggers) {
                    if (ft.trigger.on == EventType.SWITCH_IN || !probe.hears(ft.audience, b, onField = false)) continue
                    v += attempt(0.0) { fieldTriggerValue(ft.trigger, b, probe, turns, depth) }
                }
                total += sign * v
            }
            // Hazards: what a switchIn trigger does to each benched monster when it comes in.
            for (b in side.bench()) {
                for (ft in def.triggers) {
                    if (ft.trigger.on != EventType.SWITCH_IN || !probe.hears(ft.audience, b, onField = false)) continue
                    total += sign * HAZARD_SCALE * attempt(0.0) { fieldTriggerValue(ft.trigger, b, probe, 1.0, depth) }
                }
            }
        }
        if (def.rules.reverseSpeed && place == null && !actionBar) {
            val mine = perspective.side.activeBattlers().map { it.stat(rules.speedStat) }.average()
            val theirs = battle.opponentOf(perspective.side).activeBattlers().map { it.stat(rules.speedStat) }.average()
            if (mine.isFinite() && theirs.isFinite() && mine != theirs) {
                total += (if (mine < theirs) 1.0 else -1.0) * REVERSE_SPEED_VALUE * turns / ASSUMED_TURNS
            }
        }
        return finite(total, 0.0)
    }

    /**
     * Value for [b]'s side of field trigger [trigger] firing for [b] over [turns] turns (for switchIn: once).
     * Damage modifiers are averaged over the moves they can apply to; other effects use [effectValue].
     */
    private fun fieldTriggerValue(trigger: Trigger, b: Battler, probe: FieldInstance, turns: Double, depth: Int): Double {
        val foes = battle.opponentOf(b.side).activeBattlers()
        var total = 0.0
        when (trigger.on) {
            EventType.MODIFY_DAMAGE_DEALT, EventType.MODIFY_DAMAGE_TAKEN -> {
                val dealt = trigger.on == EventType.MODIFY_DAMAGE_DEALT
                // (attacker, defender, move) pairs the modifier may apply to.
                val moves: List<Triple<Battler?, Battler?, MoveDef?>> = if (dealt) {
                    b.moves.filter { calc.isDamaging(it.def) }.map { Triple(b, foes.firstOrNull(), it.def) }
                } else {
                    foes.flatMap { foe -> foe.moves.filter { calc.isDamaging(it.def) }.map { Triple(foe, b, it.def) } }
                }
                // Nobody has a damaging move right now: judge the modifier once, without a move.
                val pairs = moves.ifEmpty { listOf(if (dealt) Triple(b, foes.firstOrNull(), null) else Triple(foes.firstOrNull(), b, null)) }
                var sum = 0.0
                for ((attacker, defender, move) in pairs) {
                    val other = if (dealt) defender else attacker
                    val vars = mapOf("damage" to 0.0, "isCrit" to 0.0, "effectiveness" to 1.0, "hitType" to "")
                    val ctx = battle.context(b, other, attacker, defender, move, vars, estimate = true, field = probe)
                    val p = probability(trigger, b, ctx)
                    if (p <= 0.0) continue
                    for (effect in trigger.effects) {
                        if (effect !is Effect.MultiplyDamage) continue
                        val v = num(effect.value, ctx, 1.0)
                        sum += (if (dealt) v - 1.0 else 1.0 - v) * DAMAGE_MOD_VALUE * p
                    }
                }
                total += sum / pairs.size * turns
            }
            else -> {
                val rate = if (trigger.on == EventType.SWITCH_IN) 1.0 else eventRate(trigger.on)
                if (rate <= 0.0) return 0.0
                val ctx = battle.context(b, null, estimate = true, field = probe)
                val p = probability(trigger, b, ctx)
                if (p <= 0.0) return 0.0
                val n = rate * turns
                for (effect in trigger.effects) {
                    total += when (effect) {
                        is Effect.CancelAction -> if (trigger.on == EventType.BEFORE_MOVE) -turnValue(b) * p * n else 0.0
                        is Effect.MultiplyDamage, is Effect.SetField, is Effect.RemoveField -> 0.0
                        else -> resolve(effect.target, b, null).sumOf { (x, w) ->
                            w * attempt(0.0) { effectValue(effect, x, ctx, p, n, b, b, depth) }
                        }
                    }
                }
            }
        }
        return total
    }

    private fun applyStatusValue(
        effect: Effect.ApplyStatus,
        b: Battler,
        ctx: ExprContext,
        p: Double,
        n: Double,
        perspective: Battler,
        source: Battler?,
        depth: Int,
    ): Double {
        if (depth >= 2) return 0.0
        val def = setup.statuses[effect.status] ?: return 0.0
        // A field that blocks it (safeguard, a terrain...) makes it worthless.
        if (attempt(null) { battle.statusBlocker(b, def, source ?: perspective, estimate = true) } != null) return 0.0
        if (def.immuneTypes.any { b.hasType(it) }) return 0.0
        if (def.kind == StatusKind.MAJOR && b.statuses.any { it.def.kind == StatusKind.MAJOR && it.def !== def }) return 0.0
        val chance = effect.chance?.let { (num(it, ctx, 100.0) / 100.0).coerceIn(0.0, 1.0) } ?: 1.0
        val once = (p * chance).coerceIn(0.0, 1.0)
        val probability = 1.0 - (1.0 - once).pow(max(n, 1.0))
        if (probability <= 0.0) return 0.0

        val existing = b.status(def.id)
        val factor = if (existing == null) 1.0 else when (def.stacking) {
            Stacking.IGNORE -> 0.0
            Stacking.REFRESH -> refreshFactor(existing)
            Stacking.STACK -> if (existing.stacks < def.maxStacks) 0.7 else refreshFactor(existing)
        }
        if (factor <= 0.0) return 0.0

        val stacks = effect.stacks?.let { num(it, ctx, 1.0).roundToInt() }?.coerceAtLeast(1) ?: 1
        val duration = effect.duration?.let { num(it, ctx, ASSUMED_TURNS) }
        var impact = statusImpact(def, b, source, stacks, duration, depth + 1) * factor
        // A status matters little on a monster that is about to go down.
        impact *= 0.3 + 0.7 * b.hpFraction
        if (impact < 0.0 && b.side !== perspective.side) {
            // Harming an enemy is less urgent than attacking when we are low ourselves.
            impact *= 0.5 + 0.5 * perspective.hpFraction
            if (hard && b.statuses.any { it.def !== def && it.def.kind != StatusKind.BUFF }) impact *= 0.6
        }
        return (if (b.side === perspective.side) 1.0 else -1.0) * impact * probability
    }

    /** Value of re-applying a REFRESH status the target already has: only HARD bothers, when it is about to expire. */
    private fun refreshFactor(instance: StatusInstance): Double {
        val left = instance.turnsLeft ?: return 0.0
        return if (hard && left <= 1) 0.5 else 0.0
    }

    /**
     * Rough value of status [def] for its holder's side (negative = harmful to the holder): stat modifiers,
     * its triggers' effects per turn (damage, heal, lost turns...) over its duration, and a prior by kind.
     */
    private fun statusImpact(def: StatusDef, holder: Battler, source: Battler?, stacks: Int, durationOverride: Double?, depth: Int): Double {
        val duration = durationOverride
            ?: def.duration?.let { num(it, attempt(null) { battle.context(holder, source, estimate = true) }, ASSUMED_TURNS) }
        val turns = when {
            duration == null -> ASSUMED_TURNS
            duration <= 0.0 -> 0.5
            else -> min(duration, ASSUMED_TURNS)
        }
        val instance = StatusInstance(def, if (duration == null) null else ceil(turns).toInt(), stacks, source, fresh = true)
        var total = 0.0
        if (def.statModifiers.isNotEmpty()) {
            val ctx = attempt(null) { battle.context(holder, source, statusInstance = instance, estimate = true, rawStats = true) }
            for ((stat, formula) in def.statModifiers) {
                val multiplier = num(formula, ctx, 1.0).coerceAtLeast(0.05)
                total += log2(multiplier) * STAT_WEIGHT * turnValue(holder) * statRelevance(stat, holder) * turns
            }
        }
        if (def.triggers.isNotEmpty()) {
            val ctx = battle.context(holder, source, statusInstance = instance, estimate = true)
            for (trigger in def.triggers) {
                val rate = eventRate(trigger.on)
                if (rate <= 0.0) continue
                val p = probability(trigger, holder, ctx)
                if (p <= 0.0) continue
                val n = if (trigger.on == EventType.STATUS_APPLIED) 1.0 else rate * turns
                for (effect in trigger.effects) {
                    total += when (effect) {
                        is Effect.CancelAction -> if (trigger.on == EventType.BEFORE_MOVE) -turnValue(holder) * p * n else 0.0
                        is Effect.MultiplyDamage -> {
                            val v = num(effect.value, ctx, 1.0)
                            when (trigger.on) {
                                EventType.MODIFY_DAMAGE_DEALT -> (v - 1.0) * DAMAGE_MOD_VALUE * p * n
                                EventType.MODIFY_DAMAGE_TAKEN -> (1.0 - v) * DAMAGE_MOD_VALUE * p * n
                                else -> 0.0
                            }
                        }
                        else -> resolve(effect.target, holder, source).sumOf { (b, w) ->
                            w * attempt(0.0) { effectValue(effect, b, ctx, p, n, holder, holder, depth) }
                        }
                    }
                }
            }
        }
        // A prior by kind, mostly for statuses whose effect cannot be measured here (others check hasStatus...).
        val prior = when (def.kind) {
            StatusKind.BUFF -> 0.08
            StatusKind.DEBUFF -> -0.08
            StatusKind.MAJOR -> -0.12
            StatusKind.VOLATILE -> -0.06
        }
        total = finite(total, 0.0) + if (abs(total) < 0.05) prior else prior * 0.25
        return total * STATUS_SCALE
    }

    /** Rules.statStages.multiplier at [stage] (Pokemon-like default if the formula fails). */
    private fun stageMultiplier(stage: Int): Double {
        val fallback = if (stage >= 0) (2.0 + stage) / 2.0 else 2.0 / (2.0 - stage)
        val value = attempt(fallback) {
            rules.stageMultiplier.eval(MapContext(mapOf("stage" to stage.toDouble()), battle.random, estimate = true))
        }
        return if (value.isFinite() && value > 0.0) value else fallback
    }

    /**
     * Rough value of one turn of [b] for its side, in enemy HP bars: its best expected hit against the
     * current opponents (at least [MIN_TURN_VALUE]; [SUPPORT_TURN_VALUE] without damaging moves).
     */
    private fun turnValue(b: Battler): Double = turnValueCache.getOrPut(b) {
        attempt(SUPPORT_TURN_VALUE) {
            val foes = battle.opponentOf(b.side).activeBattlers()
            var best = 0.0
            var damaging = false
            for (slot in b.moves) {
                if (!calc.isDamaging(slot.def)) continue
                damaging = true
                for (foe in foes) {
                    val hit = attempt(null) { expectedHit(b, foe, slot.def, 1) } ?: continue
                    best = max(best, hit.chance * min(hit.total, (foe.hp + foe.shield).toDouble()) / max(1, foe.maxHp))
                }
            }
            if (damaging) max(best, MIN_TURN_VALUE) else SUPPORT_TURN_VALUE
        }
    }

    /** How often (per owner turn) a status trigger on [event] is expected to fire. */
    private fun eventRate(event: EventType): Double = when (event) {
        EventType.TURN_START, EventType.TURN_END, EventType.BEFORE_MOVE, EventType.AFTER_MOVE, EventType.STATUS_APPLIED -> 1.0
        EventType.HIT, EventType.DEALT_DAMAGE, EventType.MODIFY_DAMAGE_DEALT,
        EventType.DAMAGED, EventType.MODIFY_DAMAGE_TAKEN, EventType.TARGETED, EventType.ATTACKED -> 0.7
        EventType.BATTLE_START, EventType.SWITCH_IN, EventType.SWITCH_OUT -> 0.0
        else -> 0.25
    }

    /** How much [stat] matters to [holder] (0..1): its attacking stat, a stat its enemies hit, speed... */
    private fun statRelevance(stat: String, holder: Battler): Double {
        if (stat == rules.hpStat) return 0.0
        if (stat == rules.speedStat) return if (actionBar) 1.0 else 0.6
        val categories = rules.categories.values
        var relevance = -1.0
        if (categories.any { it.attackStat == stat }) {
            val uses = holder.moves.any { calc.isDamaging(it.def) && rules.categories[it.def.category]?.attackStat == stat }
            relevance = if (uses) 1.0 else 0.15
        }
        if (categories.any { it.defenseStat == stat }) {
            val foes = battle.opponentOf(holder.side).let { s -> s.activeBattlers().ifEmpty { s.team.filter { !it.isFainted } } }
            val hit = foes.any { foe ->
                foe.moves.any { calc.isDamaging(it.def) && rules.categories[it.def.category]?.defenseStat == stat }
            }
            relevance = max(relevance, if (hit) 1.0 else 0.25)
        }
        return if (relevance < 0.0) 0.3 else relevance
    }

    private fun healUrgency(b: Battler): Double {
        val f = b.hpFraction
        return when {
            f < 0.25 -> if (hard) 1.7 else 1.5
            f < 0.5 -> if (hard) 1.3 else 1.2
            f < 0.75 -> 0.5
            else -> 0.25
        }
    }

    /** KO bonus; HARD values knocking out dangerous enemies more. */
    private fun koBonus(target: Battler): Double = if (hard) 1.0 + 0.5 * threat(target) else KO_BONUS

    /** How hard [enemy] can hit the AI's active monsters (fraction of their remaining HP, 0..1). */
    private fun threat(enemy: Battler): Double = threatCache.getOrPut(enemy) {
        attempt(0.0) { bestHitFraction(enemy, battle.enemies(enemy)) }
    }

    /** Best hit-chance-weighted fraction of a defender's remaining HP that one move of [attacker] takes. */
    private fun bestHitFraction(attacker: Battler, defenders: List<Battler>): Double {
        var best = 0.0
        for (slot in attacker.moves) {
            if (!calc.isDamaging(slot.def)) continue
            for (defender in defenders) {
                val hit = attempt(null) { expectedHit(attacker, defender, slot.def, 1) } ?: continue
                val hp = max(1.0, (defender.hp + defender.shield).toDouble())
                best = max(best, hit.chance * min(hit.total / hp, 1.0))
            }
        }
        return best
    }

    // ---- switching and replacements ----------------------------------------------------------------------------

    /**
     * HARD: a benched teammate to switch to, or null to stay. Never below [MIN_SWITCH_HP] (a nearly fainted
     * monster is worth more as a last attack than saved) and never when a KO is likely. Two reasons to leave:
     *  - severe type disadvantage: an enemy has a damaging move at x2 or more against the actor, the actor cannot
     *    do much damage, and a teammate resists better (takes at most neutral damage);
     *  - walled: nothing the actor can do is useful (immunities...) and a teammate that is not hit harder can deal
     *    real damage.
     */
    private fun switchTarget(scored: List<Scored>): Battler? {
        if (actor.hpFraction < MIN_SWITCH_HP || !battle.canSwitch(actor)) return null
        val enemies = battle.enemies(actor)
        if (enemies.isEmpty() || scored.any { it.ko }) return null
        val bestDamage = scored.maxOfOrNull { it.damage } ?: 0.0
        val danger = typeThreat(enemies, actor)
        val severe = danger >= 2.0 && bestDamage < LOW_DAMAGE
        val walled = bestDamage < WALLED_DAMAGE && (scored.maxOfOrNull { it.value } ?: 0.0) < WALLED_VALUE
        if (!severe && !walled) return null

        val options = battle.switchOptions(actor).filter { it.hpFraction >= MIN_SWITCH_HP }
        val threat = options.associateWith { typeThreat(enemies, it) }
        val offense = options.associateWith { attempt(0.0) { Planner(battle, it, level).offense(enemies) } }
        if (severe) {
            options.filter { threat.getValue(it) < danger && threat.getValue(it) <= 1.0 }
                .minWithOrNull(compareBy({ threat.getValue(it) }, { -offense.getValue(it) }, { -it.hpFraction }))
                ?.let { return it }
        }
        if (walled) {
            return options.filter { offense.getValue(it) >= WALLED_SWITCH_OFFENSE && threat.getValue(it) <= max(danger, 1.0) }
                .maxWithOrNull(compareBy({ offense.getValue(it) }, { -threat.getValue(it) }, { it.hpFraction }))
        }
        return null
    }

    /** Highest type multiplier of the enemies' damaging moves against [b]'s types (1.0 if they have none). */
    private fun typeThreat(enemies: List<Battler>, b: Battler): Double {
        var worst = 0.0
        var any = false
        for (enemy in enemies) {
            for (slot in enemy.moves) {
                if (!calc.isDamaging(slot.def)) continue
                any = true
                worst = max(worst, setup.types.multiplier(slot.def.type, b.types))
            }
        }
        return if (any) worst else 1.0
    }

    /** Best expected damage of the actor's usable moves against [enemies] (fraction of max HP, + a KO bonus). */
    fun offense(enemies: List<Battler>): Double {
        val moves = attempt(actor.moves) { battle.usableMoves(actor) }.ifEmpty { actor.moves }
        var best = 0.0
        for (slot in moves) {
            if (!calc.isDamaging(slot.def)) continue
            for (enemy in enemies) {
                if (attempt(false) { battle.isMoveBlocked(actor, slot.def, enemy, estimate = true) }) continue
                val hit = attempt(null) { expectedHit(actor, enemy, slot.def, 1) } ?: continue
                if (hit.total <= 0.0) continue
                val hp = max(1.0, (enemy.hp + enemy.shield).toDouble())
                val value = hit.chance * (min(hit.total, hp) / max(1, enemy.maxHp) + if (hit.total >= hp) 0.3 else 0.0)
                best = max(best, value)
            }
        }
        return best
    }

    /** How hard [enemies] can hit the actor (best fraction of its remaining HP, 0..1). */
    fun danger(enemies: List<Battler>): Double = enemies.maxOfOrNull { bestHitFraction(it, listOf(actor)) } ?: 0.0

    // ---- formulas ----------------------------------------------------------------------------------------------

    private fun num(formula: Formula?, ctx: ExprContext?, default: Double): Double {
        if (formula == null || ctx == null) return default
        return attempt(default) { finite(formula.eval(ctx), default) }
    }
}
