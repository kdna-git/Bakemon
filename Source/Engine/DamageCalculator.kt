package engine

import bakemon.HitTypeDef
import bakemon.MoveDef
import bakemon.Setup

/**
 * Damage math, driven by Rules.json formulas: hit chance, critical hits, hit types (crushing, glancing...), type
 * effectiveness, same-type bonus and the damage formula itself. Ability/item damage modifiers are NOT applied here
 * (the Manager emits modifyDamageDealt / modifyDamageTaken afterwards).
 * An active field that covers the attacker may replace the damage, hitChance, critChance and critMultiplier formulas
 * (its "rules"; the most recently set one wins, README "Fields.json").
 */
class DamageCalculator(private val setup: Setup) {

    data class Result(
        /** Damage before modifyDamage* events: rounded down, at least Rules.minDamage unless [effectiveness] is 0 or [power] is 0. */
        val damage: Int,
        val effectiveness: Double,
        /** False when a crit-cancelling hit type ([HitTypeDef.cancelsCrit]) turned the critical hit into a normal one. */
        val isCrit: Boolean,
        val sameType: Boolean,
        /**
         * The move's power for this hit, evaluated once (the damage formula's `move.power` reads this value).
         * 0 means the hit deals no damage (README: power 0 = no direct damage), even when it is a formula.
         */
        val power: Double = 0.0,
        /** The Rules.hitTypes entry this hit rolled, or null for a normal hit (always null in estimate mode). */
        val hitType: HitTypeDef? = null,
    )

    /** True if the move deals direct damage (has power and a damaging category). */
    fun isDamaging(move: MoveDef): Boolean = setup.isDamaging(move)

    /** Type multiplier of [move] against [target]'s current types. */
    fun effectiveness(move: MoveDef, target: Battler): Double = setup.types.multiplier(move.type, target.types)

    /** Evaluated power of [move] (0 if none) for hit [hitIndex] of [hitCount] on [target]. */
    fun power(
        battle: Battle,
        user: Battler,
        target: Battler,
        move: MoveDef,
        estimate: Boolean = false,
        hitIndex: Int = 1,
        hitCount: Int = 1,
    ): Double {
        val formula = move.power ?: return 0.0
        formula.constant?.let { return it.coerceAtLeast(0.0) }
        // Inside its own power formula, `move.power` reads 0.
        val ctx = BattleExprContext(
            battle, setup, user, target, user, target, move, hitCounters(hitIndex, hitCount), null,
            estimate = estimate, rawStats = false, insidePower = true,
        )
        val value = formula.eval(ctx)
        return if (value > 0.0) value else 0.0
    }

    /**
     * 0..1. 1.0 when move.accuracy is null or the target is the user / an ally.
     * The formula sees `effectiveness` and `sameType` against [target]. In [estimate] mode no dice are rolled
     * (random() reads its midpoint, used by the AI).
     */
    fun hitChance(battle: Battle, user: Battler, target: Battler, move: MoveDef, estimate: Boolean = false): Double {
        if (move.accuracy == null || target === user || target.side === user.side) return 1.0
        val field = battle.ruleOverride(user) { it.hitChance }
        val formula = field?.def?.rules?.hitChance ?: setup.rules.hitChance
        val ctx = battle.context(user, target, move = move, vars = matchup(user, target, move), estimate = estimate, field = field)
        return unit(formula.eval(ctx))
    }

    /** 0..1: Rules.critChance + move.critBonus, clamped (both see `effectiveness` and `sameType`). In [estimate] mode no dice are rolled. */
    fun critChance(battle: Battle, user: Battler, target: Battler, move: MoveDef, estimate: Boolean = false): Double {
        val vars = matchup(user, target, move)
        return critChance(battle, user, target, move, battle.context(user, target, move = move, vars = vars, estimate = estimate), vars, estimate, null)
    }

    /**
     * Rules.critChance (or a field's override, evaluated in its own context with the same [vars]) + move.critBonus
     * (evaluated with [ctx]).
     */
    private fun critChance(
        battle: Battle,
        user: Battler,
        target: Battler,
        move: MoveDef,
        ctx: ExprContext,
        vars: Map<String, Any?>,
        estimate: Boolean,
        power: Double?,
    ): Double {
        val bonus = move.critBonus?.eval(ctx) ?: 0.0
        val field = battle.ruleOverride(user) { it.critChance }
        val chance = if (field == null) setup.rules.critChance.eval(ctx)
        else field.def.rules.critChance!!.eval(battle.context(user, target, move = move, vars = vars, estimate = estimate, power = power, field = field))
        return unit(chance + bonus)
    }

    /** `hitIndex` / `hitCount` of the hit in progress (README "Other variables"). */
    private fun hitCounters(hitIndex: Int, hitCount: Int): Map<String, Any?> =
        mapOf(HIT_INDEX to hitIndex.toDouble(), HIT_COUNT to hitCount.toDouble())

    /** True if the power or damage of [move] can change from one hit to the next (its formulas read hitIndex / hitCount). */
    fun perHitPower(move: MoveDef): Boolean = rulesReadHitCounters || perHitMoves.getOrPut(move) { reads(move.power) || reads(move.ignoreDefense) }

    private fun reads(f: Formula?) = f != null && (HIT_INDEX in f.variables() || HIT_COUNT in f.variables())

    /** The damage or hit type formulas of the rules read hitIndex / hitCount (field overrides are not looked at). */
    private val rulesReadHitCounters: Boolean =
        reads(setup.rules.damage) || setup.rules.hitTypes.any { reads(it.chance) || reads(it.multiplier) }

    private val perHitMoves = java.util.IdentityHashMap<MoveDef, Boolean>()

    /** The variables of the hit chance and crit formulas: the type matchup of [move] from [user] against [target]. */
    private fun matchup(user: Battler, target: Battler, move: MoveDef): HashMap<String, Any?> {
        val vars = HashMap<String, Any?>(12)
        vars["effectiveness"] = effectiveness(move, target)
        vars["sameType"] = if (move.type?.let { user.hasType(it) } == true) 1.0 else 0.0
        return vars
    }

    /**
     * Computes one hit. Rolls the crit with battle.random unless [forceCrit] is given, then the hit type
     * (Rules.hitTypes, in order: the first whose chance roll succeeds; a crit-cancelling one makes the hit non-critical).
     * [targets] = number of targets the move hits. In [estimate] mode no dice are rolled (crit and hit types use their
     * expected multipliers, random() returns midpoints) - used by the AI.
     * The power is evaluated once ([Result.power]); a hit whose power is 0 deals no damage (damage 0, no crit roll).
     * [hitIndex] / [hitCount] are this hit's number and the planned hits on the target (`hitIndex`, `hitCount` in the
     * power, damage and hit type formulas). The move's `ignoreDefense` lowers `defense`.
     */
    fun calculate(
        battle: Battle,
        user: Battler,
        target: Battler,
        move: MoveDef,
        targets: Int,
        forceCrit: Boolean? = null,
        estimate: Boolean = false,
        hitIndex: Int = 1,
        hitCount: Int = 1,
    ): Result {
        val rules = setup.rules
        val effectiveness = effectiveness(move, target)
        val sameType = move.type?.let { user.hasType(it) } ?: false
        val category = rules.categories[move.category]
        val attackStat = category?.attackStat
        val defenseStat = category?.defenseStat
        if (attackStat == null || defenseStat == null || !isDamaging(move)) {
            return Result(0, effectiveness, false, sameType)
        }
        val power = power(battle, user, target, move, estimate, hitIndex, hitCount)
        if (power <= 0.0) return Result(0, effectiveness, false, sameType, 0.0)

        // One variable map for the whole hit: the crit formulas see the matchup, then the crit and hit type are added.
        val vars = matchup(user, target, move)
        vars["targets"] = targets.toDouble()
        vars[HIT_INDEX] = hitIndex.toDouble()
        vars[HIT_COUNT] = hitCount.toDouble()
        val ctx = battle.context(user, target, move = move, vars = vars, estimate = estimate, power = power)
        // Formulas a field replaces are evaluated with that field's stacks / turnsLeft (the variables map is shared).
        fun fieldContext(field: FieldInstance?): ExprContext =
            if (field == null) ctx else battle.context(user, target, move = move, vars = vars, estimate = estimate, power = power, field = field)
        val critField = battle.ruleOverride(user) { it.critMultiplier }
        val critMultiplier = (critField?.def?.rules?.critMultiplier ?: rules.critMultiplier).eval(fieldContext(critField))
        var isCrit: Boolean
        var crit: Double
        // Probability that the hit is critical (estimate mode: the crit chance; otherwise 1 or 0, once decided).
        val critProbability: Double
        when {
            forceCrit != null -> {
                isCrit = forceCrit
                crit = if (forceCrit) critMultiplier else 1.0
                critProbability = if (forceCrit) 1.0 else 0.0
            }
            estimate -> {
                isCrit = false
                critProbability = critChance(battle, user, target, move, ctx, vars, estimate, power)
                crit = 1.0 + critProbability * (critMultiplier - 1.0)
            }
            else -> {
                val chance = critChance(battle, user, target, move, ctx, vars, estimate, power)
                isCrit = chance >= 1.0 || (chance > 0.0 && battle.random.nextDouble() < chance)
                crit = if (isCrit) critMultiplier else 1.0
                critProbability = if (isCrit) 1.0 else 0.0
            }
        }
        vars["crit"] = crit
        vars["isCrit"] = if (isCrit) 1.0 else 0.0

        // Hit types: only for hits that can deal damage (a type immunity deals none anyway).
        var hitType: HitTypeDef? = null
        var hitMultiplier = 1.0
        if (rules.hitTypes.isNotEmpty() && effectiveness > 0.0) {
            if (estimate) {
                val expected = expectedFactor(battle, user, target, move, power, vars, critProbability, critMultiplier)
                // The damage formula below already applies the expected crit multiplier (`crit`): keep only the rest.
                if (crit > 0.0 && crit.isFinite() && expected.isFinite()) hitMultiplier = expected / crit
            } else {
                hitType = rules.hitTypes.firstOrNull { rollPercent(battle, it.chance.eval(ctx)) }
                if (hitType != null) {
                    if (hitType.cancelsCrit && isCrit) {
                        isCrit = false
                        crit = 1.0
                        vars["crit"] = crit
                        vars["isCrit"] = 0.0
                    }
                    // Evaluated once the hit type is decided: it sees the final isCrit / crit.
                    hitMultiplier = multiplier(hitType, ctx)
                }
            }
        }
        vars["hitType"] = hitType?.id ?: ""

        vars["attack"] = user.stat(attackStat)
        vars["defense"] = target.stat(defenseStat)
        move.ignoreDefense?.let { formula ->
            val percent = formula.eval(ctx).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 100.0) }
            // Never below 1 (unless it already was): a damage formula that divides by the defense keeps working.
            val defense = target.stat(defenseStat)
            vars["defense"] = maxOf(defense * (1.0 - percent / 100.0), minOf(defense, 1.0))
        }
        val damageField = battle.ruleOverride(user) { it.damage }
        var raw = (damageField?.def?.rules?.damage ?: rules.damage).eval(fieldContext(damageField))
        // The epsilon absorbs floating-point noise of the product (e.g. 100 x 0.7 must give 70, not 69).
        if (hitMultiplier != 1.0) raw = raw * hitMultiplier + ROUNDING_EPSILON
        val damage = if (effectiveness <= 0.0) 0 else maxOf(raw.floorToInt(), rules.minDamage)
        return Result(damage, effectiveness, isCrit, sameType, power, hitType)
    }

    /**
     * Estimate mode: the expected crit x hit type factor of one hit, without dice. Each crit outcome (critical with
     * probability [critProbability], or not) tries the hit types in order with their chances: p1 x m1 + (1 - p1) x p2 x m2
     * + ... + (what is left) x 1, each times the crit multiplier of that outcome (1 when the hit type cancels the crit).
     * With no hit type that can happen, this is exactly the expected crit multiplier.
     */
    private fun expectedFactor(
        battle: Battle,
        user: Battler,
        target: Battler,
        move: MoveDef,
        power: Double,
        baseVars: Map<String, Any?>,
        critProbability: Double,
        critMultiplier: Double,
    ): Double {
        fun context(critical: Boolean): ExprContext {
            val vars = HashMap(baseVars)
            vars["isCrit"] = if (critical) 1.0 else 0.0
            vars["crit"] = if (critical) critMultiplier else 1.0
            return battle.context(user, target, move = move, vars = vars, estimate = true, power = power)
        }
        fun outcome(critical: Boolean): Double {
            val critFactor = if (critical) critMultiplier else 1.0
            val ctx = context(critical)
            val cancelled by lazy { context(false) }
            var rest = 1.0
            var total = 0.0
            for (type in setup.rules.hitTypes) {
                val p = percent(type.chance.eval(ctx))
                if (p <= 0.0) continue
                val cancels = critical && type.cancelsCrit
                total += rest * p * (if (cancels) 1.0 else critFactor) * multiplier(type, if (cancels) cancelled else ctx)
                rest *= 1.0 - p
                if (rest <= 0.0) break
            }
            return total + rest * critFactor
        }
        var expected = 0.0
        if (critProbability < 1.0) expected += (1.0 - critProbability) * outcome(critical = false)
        if (critProbability > 0.0) expected += critProbability * outcome(critical = true)
        return expected
    }

    /** A hit type's damage multiplier (NaN = 1, never negative). */
    private fun multiplier(type: HitTypeDef, ctx: ExprContext): Double {
        val value = type.multiplier.constant ?: type.multiplier.eval(ctx)
        return if (value.isNaN()) 1.0 else value.coerceAtLeast(0.0)
    }

    /** Clamps to 0..1 (NaN -> 0). */
    private fun unit(value: Double): Double = if (value.isNaN()) 0.0 else value.coerceIn(0.0, 1.0)

    /** A percent chance as 0..1 (NaN -> 0). */
    private fun percent(value: Double): Double = unit(value / 100.0)

    /** Rolls a percent chance with the battle random; no dice for <= 0 (never) or >= 100 (always). */
    private fun rollPercent(battle: Battle, percent: Double): Boolean = when {
        percent.isNaN() || percent <= 0.0 -> false
        percent >= 100.0 -> true
        else -> battle.random.nextDouble() * 100.0 < percent
    }

    private companion object {
        /** Added before rounding a damage scaled by a hit type, so 69.99999999 (floating-point noise) counts as 70. */
        const val ROUNDING_EPSILON = 1e-7
        const val HIT_INDEX = "hitIndex"
        const val HIT_COUNT = "hitCount"
    }
}
