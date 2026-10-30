package engine

import bakemon.StatusKind
import util.JsonObj
import util.SetupException

/** Who an effect applies to (the JSON effect "target"). See README "Effects". */
enum class TargetSelector(val id: String) {
    /** The trigger owner (for move triggers: the move user). */
    SELF("self"),
    /** The other monster of the event. */
    OTHER("other"),
    /** The move user if a move is involved, else self. */
    USER("user"),
    /** The current move target if a move is involved, else other. */
    TARGET("target"),
    /** The owner's active allies (not the owner). */
    ALLIES("allies"),
    /** The owner and its active allies. */
    TEAM("team"),
    /** Every active enemy of the owner. */
    ENEMIES("enemies"),
    /** One random active enemy of the owner. */
    RANDOM_ENEMY("randomEnemy"),
    /** Every active monster. */
    ALL("all"),
    /** Every fainted teammate of the owner that can still be revived (only the revive effects reach fainted monsters). */
    FAINTED_ALLIES("faintedAllies"),
    /** One random fainted teammate of the owner that can still be revived. */
    RANDOM_FAINTED_ALLY("randomFaintedAlly"),
    /** The owner or an active ally with the lowest HP percent (ties: field order). */
    LOWEST_HP_ALLY("lowestHpAlly"),
    /** The active enemy with the lowest HP percent (ties: field order). */
    LOWEST_HP_ENEMY("lowestHpEnemy"),
    /** The active enemy with the highest HP percent (ties: field order). */
    HIGHEST_HP_ENEMY("highestHpEnemy"),
    /** One random active ally of the owner (not the owner; nobody when it is alone). */
    RANDOM_ALLY("randomAlly"),
    /** The target of the other monster's move, when the event's other monster is the move user (e.g. allyUsedMove). */
    OTHER_TARGET("otherTarget");

    companion object {
        fun fromId(id: String): TargetSelector? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** Which side a side-scoped field effect acts on, relative to the trigger owner (README "Effects"). */
enum class FieldSide(val id: String) {
    SELF("self"),
    ENEMY("enemy"),
    BOTH("both");

    companion object {
        fun fromId(id: String): FieldSide? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** What transferStatus does with the statuses it moves (README "Effects"). */
enum class TransferMode(val id: String) {
    /** Removed from the source, applied to the receivers. */
    STEAL("steal"),
    /** Applied to the receivers; the source keeps them. */
    COPY("copy");

    companion object {
        fun fromId(id: String): TransferMode? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** The effect catalog. Each JSON effect object becomes one of these (see README "Effects"). */
sealed class Effect {
    abstract val target: TargetSelector

    /** Direct damage, ignoring the damage formula and types. */
    data class Damage(override val target: TargetSelector, val amount: Formula) : Effect()

    data class Heal(override val target: TargetSelector, val amount: Formula) : Effect()

    /**
     * [chance] in percent (null = 100); [duration] overrides the status default; [stacks] default 1.
     * [message] replaces the status's applyMessage for this effect ("" = say nothing).
     */
    data class ApplyStatus(
        override val target: TargetSelector,
        val status: String,
        val chance: Formula?,
        val duration: Formula?,
        val stacks: Formula?,
        val message: String? = null,
    ) : Effect()

    /** Removes by id ([status], "all" = everything) and/or by [kind]. At least one of them is set. */
    data class RemoveStatus(override val target: TargetSelector, val status: String?, val kind: StatusKind?) : Effect()

    data class StatStage(override val target: TargetSelector, val stat: String, val stages: Formula) : Effect()

    /** Only meaningful in modifyDamageDealt / modifyDamageTaken. */
    data class MultiplyDamage(val value: Formula) : Effect() {
        override val target: TargetSelector get() = TargetSelector.SELF
    }

    /** Only meaningful in beforeMove. */
    data class CancelAction(val message: String?) : Effect() {
        override val target: TargetSelector get() = TargetSelector.SELF
    }

    /** Adds [amount] percent of the action bar (negative pushes back). No-op in speed_order. */
    data class Gauge(override val target: TargetSelector, val amount: Formula) : Effect()

    data class Shield(override val target: TargetSelector, val amount: Formula) : Effect()

    /** Adds [amount] to the remaining cooldown of every move of the target (min 0). */
    data class Cooldown(override val target: TargetSelector, val amount: Formula) : Effect()

    /** [message] replaces the engine's "X's item was used up." line ("" = say nothing). */
    data class ConsumeItem(override val target: TargetSelector, val message: String? = null) : Effect()

    data class Message(val text: String) : Effect() {
        override val target: TargetSelector get() = TargetSelector.SELF
    }

    /**
     * Sets field [field] (README "Fields.json"). [side] (side fields only; null = "self") is relative to the trigger
     * owner. [duration] overrides the field default; [stacks] default 1; [chance] in percent (null = 100);
     * [message] replaces the field's startMessage ("" = say nothing).
     */
    data class SetField(
        val field: String,
        val side: FieldSide?,
        val duration: Formula?,
        val stacks: Formula?,
        val chance: Formula?,
        val message: String?,
    ) : Effect() {
        override val target: TargetSelector get() = TargetSelector.SELF
    }

    /**
     * Removes active fields by id ([field], "all" = every field) or by [group]; exactly one of them is set.
     * [side] (null = "both") picks the sides whose side fields are removed; global fields are removed whatever it says.
     * [message] replaces each removed field's endMessage ("" = say nothing).
     */
    data class RemoveField(val field: String?, val group: String?, val side: FieldSide?, val message: String?) : Effect() {
        override val target: TargetSelector get() = TargetSelector.SELF
    }

    /**
     * Brings a fainted [target] back with [amount] HP (README "Revives"). Unlike the other effects it reaches fainted
     * monsters (self, other, user, target, faintedAllies, randomFaintedAlly); living ones are ignored.
     * [message] replaces the engine's "X was revived" line ("" = say nothing).
     */
    data class Revive(override val target: TargetSelector, val amount: Formula, val message: String? = null) : Effect()

    /** The target (fainted or not) can no longer be revived in this battle. [message] as for [Revive]. */
    data class PreventRevive(override val target: TargetSelector, val message: String? = null) : Effect()

    /**
     * Every monster [actor] resolves to uses a move right away, outside its turn (README "Effects", useMove). [move] is
     * [FIRST_MOVE], [SAME_MOVE] or a move id; [moveTarget] (null = "other" when the event has one, else the move's
     * normal target) is resolved from the actor's point of view; [power] is a damage percent (null = 100); [free] =
     * no PP / cooldown spent; [message] is logged first.
     */
    data class UseMove(
        val actor: TargetSelector,
        val move: String,
        val moveTarget: TargetSelector?,
        val power: Formula?,
        val free: Boolean,
        val message: String?,
    ) : Effect() {
        override val target: TargetSelector get() = actor
    }

    /** The target gets another turn (README "Effects", extraTurn). */
    data class ExtraTurn(override val target: TargetSelector) : Effect()

    /**
     * Moves or copies statuses from [target] to [to]: those matching [status] (id or "all") and/or [kind], the oldest
     * [count] of them (null = 1, 0 = all), keeping their duration and stacks.
     */
    data class TransferStatus(
        override val target: TargetSelector,
        val to: TargetSelector,
        val status: String?,
        val kind: StatusKind?,
        val count: Formula?,
        val mode: TransferMode,
    ) : Effect()

    /** The stat stages of every [to] monster become a copy of [target]'s. */
    data class CopyStages(override val target: TargetSelector, val to: TargetSelector) : Effect()

    /** Sets the target's HP to [amount] (0..max HP). */
    data class SetHp(override val target: TargetSelector, val amount: Formula) : Effect()

    /** Every monster [target] resolves to gets the average HP percent of the group. */
    data class BalanceHp(override val target: TargetSelector) : Effect()

    /** The owner and the target swap their HP percentages. */
    data class SwapHp(override val target: TargetSelector) : Effect()

    /** The target is replaced right away by a random benched teammate. */
    data class ForceSwitch(override val target: TargetSelector) : Effect()

    /** The target switches out right away; its controller picks the replacement. */
    data class SwitchOut(override val target: TargetSelector) : Effect()

    /**
     * Runs [effects] later (README "Effects", delay): at the end of the [attachTo] monster's [turns]th turn from now
     * (speed_order: at the end of the [turns]th round, counting the current one), with the roles of this trigger.
     */
    data class Delay(val turns: Formula, val attachTo: TargetSelector, val effects: List<Effect>, val message: String?) : Effect() {
        override val target: TargetSelector get() = attachTo
    }

    /**
     * A damaging strike of the owner against the target, computed with the damage formula ([power]; [type] (JSON
     * "moveType", since "type" is the effect type) and
     * [category] default to the current move's; README "Effects", attack). It fires dealtDamage / damaged, not hit.
     */
    data class Attack(override val target: TargetSelector, val power: Formula, val type: String?, val category: String?) : Effect()

    companion object {
        /** JSON "type" names, in README order. */
        val TYPES: List<String> = listOf(
            "damage", "heal", "applyStatus", "removeStatus", "statStage", "multiplyDamage",
            "cancelAction", "gauge", "shield", "cooldown", "consumeItem", "message", "setField", "removeField",
            "revive", "preventRevive", "useMove", "extraTurn", "transferStatus", "copyStages", "setHp", "balanceHp",
            "swapHp", "forceSwitch", "switchOut", "delay", "attack",
        )

        /** useMove "move": the actor's first move. */
        const val FIRST_MOVE = "first"

        /** useMove "move": the move of the current event when the actor knows it, else its first move. */
        const val SAME_MOVE = "same"

        /**
         * Parses one effect object. [defaultTarget] is used when "target" is omitted.
         * Throws util.SetupException (with the JSON path) on unknown types/targets or missing fields.
         */
        fun parse(obj: JsonObj, defaultTarget: TargetSelector): Effect {
            val typeName = obj.string("type")
            val type = TYPES.firstOrNull { it.equals(typeName, ignoreCase = true) }
                ?: throw SetupException(
                    "${obj.pathOf("type")}: unknown effect type '$typeName' (expected one of: ${TYPES.joinToString()})"
                )
            val target = selector(obj, "target") ?: defaultTarget
            return when (type) {
                "damage" -> Damage(target, obj.formula("amount"))
                "heal" -> Heal(target, obj.formula("amount"))
                "applyStatus" -> ApplyStatus(
                    target = target,
                    status = nonBlank(obj, "status"),
                    chance = obj.formulaOrNull("chance"),
                    duration = obj.formulaOrNull("duration"),
                    stacks = obj.formulaOrNull("stacks"),
                    message = obj.stringOrNull("message"),
                )
                "removeStatus" -> {
                    val status = obj.stringOrNull("status")?.takeIf { it.isNotBlank() }
                    val kind = statusKind(obj)
                    if (status == null && kind == null) {
                        throw SetupException("${obj.path}: removeStatus needs \"status\" (an id or \"all\") and/or \"kind\"")
                    }
                    RemoveStatus(target, status, kind)
                }
                "statStage" -> StatStage(target, nonBlank(obj, "stat"), obj.formula("stages"))
                "multiplyDamage" -> MultiplyDamage(obj.formula("value"))
                "cancelAction" -> CancelAction(obj.stringOrNull("message"))
                "gauge" -> Gauge(target, obj.formula("amount"))
                "shield" -> Shield(target, obj.formula("amount"))
                "cooldown" -> Cooldown(target, obj.formula("amount"))
                "consumeItem" -> ConsumeItem(target, obj.stringOrNull("message"))
                "setField" -> SetField(
                    field = nonBlank(obj, "field"),
                    side = fieldSide(obj),
                    duration = obj.formulaOrNull("duration"),
                    stacks = obj.formulaOrNull("stacks"),
                    chance = obj.formulaOrNull("chance"),
                    message = obj.stringOrNull("message"),
                )
                "removeField" -> {
                    val field = obj.stringOrNull("field")?.takeIf { it.isNotBlank() }
                    val group = obj.stringOrNull("group")?.takeIf { it.isNotBlank() }
                    if ((field == null) == (group == null)) {
                        throw SetupException("${obj.path}: removeField needs either \"field\" (an id or \"all\") or \"group\"")
                    }
                    RemoveField(field, group, fieldSide(obj), obj.stringOrNull("message"))
                }
                "revive" -> Revive(target, obj.formula("amount"), obj.stringOrNull("message"))
                "preventRevive" -> PreventRevive(target, obj.stringOrNull("message"))
                "useMove" -> UseMove(
                    actor = selector(obj, "actor") ?: TargetSelector.SELF,
                    move = obj.stringOrNull("move")?.takeIf { it.isNotBlank() } ?: FIRST_MOVE,
                    moveTarget = selector(obj, "moveTarget"),
                    power = obj.formulaOrNull("power"),
                    free = obj.bool("free", true),
                    message = obj.stringOrNull("message"),
                )
                "extraTurn" -> ExtraTurn(target)
                "transferStatus" -> {
                    val status = obj.stringOrNull("status")?.takeIf { it.isNotBlank() }
                    val kind = statusKind(obj)
                    if (status == null && kind == null) {
                        throw SetupException("${obj.path}: transferStatus needs \"status\" (an id or \"all\") and/or \"kind\"")
                    }
                    val modeId = obj.string("mode", TransferMode.STEAL.id)
                    val mode = TransferMode.fromId(modeId) ?: throw SetupException(
                        "${obj.pathOf("mode")}: unknown mode '$modeId' (expected one of: ${TransferMode.entries.joinToString { it.id }})"
                    )
                    TransferStatus(target, selector(obj, "to") ?: TargetSelector.SELF, status, kind, obj.formulaOrNull("count"), mode)
                }
                "copyStages" -> CopyStages(target, selector(obj, "to") ?: TargetSelector.SELF)
                "setHp" -> SetHp(target, obj.formula("amount"))
                "balanceHp" -> BalanceHp(target)
                "swapHp" -> SwapHp(target)
                "forceSwitch" -> ForceSwitch(target)
                // A U-turn switches its own user out: self by default, wherever the trigger is.
                "switchOut" -> SwitchOut(selector(obj, "target") ?: TargetSelector.SELF)
                "delay" -> Delay(
                    turns = obj.formula("turns"),
                    attachTo = selector(obj, "attachTo") ?: defaultTarget,
                    effects = obj.arr("effects").objects().map { parse(it, defaultTarget) },
                    message = obj.stringOrNull("message"),
                )
                "attack" -> Attack(
                    target = target,
                    power = obj.formula("power"),
                    type = obj.stringOrNull("moveType")?.takeIf { it.isNotBlank() },
                    category = obj.stringOrNull("category")?.takeIf { it.isNotBlank() },
                )
                else -> Message(obj.string("text"))
            }
        }

        /** An optional target selector under [key] (null when absent). */
        private fun selector(obj: JsonObj, key: String): TargetSelector? = obj.stringOrNull(key)?.let { id ->
            TargetSelector.fromId(id) ?: throw SetupException(
                "${obj.pathOf(key)}: unknown target '$id' (expected one of: ${TargetSelector.entries.joinToString { it.id }})"
            )
        }

        private fun statusKind(obj: JsonObj): StatusKind? = obj.stringOrNull("kind")?.let { id ->
            StatusKind.fromId(id) ?: throw SetupException(
                "${obj.pathOf("kind")}: unknown status kind '$id' (expected one of: ${StatusKind.entries.joinToString { it.id }})"
            )
        }

        private fun fieldSide(obj: JsonObj): FieldSide? = obj.stringOrNull("side")?.let { id ->
            FieldSide.fromId(id) ?: throw SetupException(
                "${obj.pathOf("side")}: unknown side '$id' (expected one of: ${FieldSide.entries.joinToString { it.id }})"
            )
        }

        private fun nonBlank(obj: JsonObj, key: String): String {
            val value = obj.string(key)
            if (value.isBlank()) throw SetupException("${obj.pathOf(key)}: must not be empty")
            return value
        }
    }
}

/** Applies effects to the battle through the [Manager]'s operations. */
class EffectRunner(private val manager: Manager) {
    /**
     * Runs [effects] for an event. Formulas are evaluated with [Battle.context] built from [ctx]
     * (self = ctx.subject, other = ctx.other, user/target = move roles or self/other) plus ctx.vars and,
     * for status sources, `stacks`/`turnsLeft`. Each effect is applied to every resolved target that is
     * still active (not fainted), in field order; its formulas see that target as `recipient`.
     */
    fun run(effects: List<Effect>, ctx: EventContext, source: TriggerSource) {
        if (effects.isEmpty()) return
        val expr = formulaContext(ctx, source)
        for (effect in effects) {
            when (effect) {
                is Effect.MultiplyDamage -> ctx.multiplyDamage(effect.value.eval(expr))
                is Effect.CancelAction -> {
                    ctx.cancelled = true
                    ctx.cancelSource = source.kind
                    effect.message?.let { ctx.cancelMessage = manager.format(it, ctx, source) }
                }
                is Effect.Message -> manager.log(messageKind(source), manager.format(effect.text, ctx, source))
                is Effect.SetField -> setField(effect, ctx, source, expr)
                is Effect.RemoveField -> removeField(effect, ctx, source)
                is Effect.Revive -> for (target in resolveTargets(effect.target, ctx, includeFainted = true)) {
                    if (!target.isFainted) continue
                    val text = effect.message?.let { manager.format(it, ctx, source) }
                    manager.revive(target, effect.amount.eval(formulaContext(ctx, source, target)).floorToInt(), ctx.subject, text)
                }
                is Effect.PreventRevive -> for (target in resolveTargets(effect.target, ctx, includeFainted = true)) {
                    manager.preventRevive(target, effect.message?.let { manager.format(it, ctx, source) })
                }
                is Effect.UseMove -> useMove(effect, ctx, source)
                is Effect.BalanceHp -> {
                    val group = resolveTargets(effect.target, ctx).filter { !it.isFainted }
                    manager.balanceHp(group, ctx.subject, moveOf(ctx, source))
                }
                is Effect.Delay -> delay(effect, ctx, source, expr)
                else -> for (target in resolveTargets(effect.target, ctx)) {
                    if (!target.isFainted) apply(effect, target, ctx, source, formulaContext(ctx, source, target))
                }
            }
        }
    }

    /**
     * Resolves a selector from the point of view of [ctx]. Fainted monsters are dropped unless [includeFainted]
     * (only the revive effects use it).
     */
    fun resolveTargets(selector: TargetSelector, ctx: EventContext, includeFainted: Boolean = false): List<Battler> {
        val battle = manager.battle
        val subject = ctx.subject
        val resolved: List<Battler> = when (selector) {
            TargetSelector.SELF -> listOf(subject)
            TargetSelector.OTHER -> listOfNotNull(ctx.other)
            TargetSelector.USER -> listOf(ctx.moveUser ?: subject)
            TargetSelector.TARGET -> listOfNotNull(ctx.moveTarget ?: ctx.other)
            TargetSelector.ALLIES -> battle.allies(subject)
            TargetSelector.TEAM -> subject.side.activeBattlers()
            TargetSelector.ENEMIES -> battle.enemies(subject)
            TargetSelector.RANDOM_ENEMY -> pickOne(battle.enemies(subject))
            TargetSelector.ALL -> battle.allActive()
            TargetSelector.FAINTED_ALLIES -> battle.faintedAllies(subject)
            TargetSelector.RANDOM_FAINTED_ALLY -> pickOne(battle.faintedAllies(subject))
            TargetSelector.LOWEST_HP_ALLY -> listOfNotNull(battle.lowestHp(subject.side.activeBattlers()))
            TargetSelector.LOWEST_HP_ENEMY -> listOfNotNull(battle.lowestHp(battle.enemies(subject)))
            TargetSelector.HIGHEST_HP_ENEMY -> listOfNotNull(battle.highestHp(battle.enemies(subject)))
            TargetSelector.RANDOM_ALLY -> pickOne(battle.allies(subject))
            TargetSelector.OTHER_TARGET -> listOfNotNull(ctx.moveTarget?.takeIf { ctx.moveUser != null && ctx.moveUser === ctx.other })
        }
        if (includeFainted) return resolved
        return if (resolved.all { !it.isFainted }) resolved else resolved.filter { !it.isFainted }
    }

    /** One random entry of [list] (no dice for 0 or 1 entries). */
    private fun pickOne(list: List<Battler>): List<Battler> =
        if (list.size <= 1) list else listOf(list[manager.battle.random.nextInt(list.size)])

    /**
     * The formula context of a trigger: event roles, event variables (read live, so `damage` follows earlier
     * multiplyDamage effects), the power of the hit in progress and, for statuses and fields, stacks/turnsLeft.
     * [recipient] is the monster an effect is being applied to (`recipient`), or null.
     */
    internal fun formulaContext(ctx: EventContext, source: TriggerSource?, recipient: Battler? = null): ExprContext =
        manager.battle.context(
            self = ctx.subject,
            other = ctx.other,
            user = ctx.moveUser ?: ctx.subject,
            target = ctx.moveTarget ?: ctx.other,
            move = ctx.move,
            vars = ctx.vars,
            statusInstance = source?.status,
            power = ctx.power,
            field = source?.field,
            recipient = recipient,
        )

    /** The sides a field effect acts on: global fields -> [null]; side fields -> the sides [side] names from [owner]. */
    private fun fieldPlaces(global: Boolean, side: FieldSide, owner: Battler): List<Side?> {
        if (global) return listOf(null)
        val enemy = manager.battle.opponentOf(owner.side)
        return when (side) {
            FieldSide.SELF -> listOf(owner.side)
            FieldSide.ENEMY -> listOf(enemy)
            FieldSide.BOTH -> listOf(owner.side, enemy)
        }
    }

    private fun setField(effect: Effect.SetField, ctx: EventContext, source: TriggerSource, expr: ExprContext) {
        val def = manager.setup.fields[effect.field] ?: return
        val move = ctx.move
        val statusMove = source.kind == TriggerSource.Kind.MOVE && move != null && !manager.damage.isDamaging(move)
        if (effect.chance != null && !manager.rollPercent(effect.chance.eval(expr))) {
            if (statusMove && manager.claimFieldFailure()) manager.log(LogKind.MISS, FAILED)
            return
        }
        val duration = effect.duration?.eval(expr)?.floorToInt()
        val stacks = effect.stacks?.eval(expr)?.floorToInt()?.coerceAtLeast(1) ?: 1
        var set = false
        for (side in fieldPlaces(def.isGlobal, effect.side ?: FieldSide.SELF, ctx.subject)) {
            val text = effect.message?.let { manager.format(it, ctx, source, fieldPlaceholders(def.name, side)) }
            if (manager.trySetField(def, side, ctx.subject, duration, stacks, text, permanent = false)) set = true
        }
        // Like applyStatus: only a status move says that it did nothing.
        if (!set && statusMove && manager.claimFieldFailure()) manager.log(LogKind.MISS, FAILED)
    }

    private fun removeField(effect: Effect.RemoveField, ctx: EventContext, source: TriggerSource) {
        val places = fieldPlaces(global = false, effect.side ?: FieldSide.BOTH, ctx.subject)
        val matching = manager.battle.fields.filter { instance ->
            val def = instance.def
            val byId = effect.field != null && (effect.field.equals(ALL_FIELDS, ignoreCase = true) || def.id == effect.field)
            val byGroup = effect.group != null && def.group == effect.group
            (byId || byGroup) && (instance.side == null || places.any { it === instance.side })
        }
        for (instance in matching) {
            val text = effect.message?.let { manager.format(it, ctx, source, fieldPlaceholders(instance.def.name, instance.side)) }
            manager.removeField(instance, text, ctx.subject)
        }
    }

    private fun fieldPlaceholders(name: String, side: Side?): Map<String, String> = mapOf("field" to name, "side" to (side?.name ?: ""))

    /** The move whose triggers run, for effects that hurt (their damage carries it), else null. */
    private fun moveOf(ctx: EventContext, source: TriggerSource) = if (source.kind == TriggerSource.Kind.MOVE) ctx.move else null

    private fun apply(effect: Effect, target: Battler, ctx: EventContext, source: TriggerSource, expr: ExprContext) {
        when (effect) {
            is Effect.Damage -> {
                val amount = effect.amount.eval(expr).floorToInt()
                if (amount > 0) manager.dealDamage(target, amount, ctx.subject, moveOf(ctx, source))
            }
            is Effect.Heal -> {
                val amount = effect.amount.eval(expr).floorToInt()
                if (amount > 0) manager.heal(target, amount, ctx.subject)
            }
            is Effect.ApplyStatus -> applyStatus(effect, target, ctx, source, expr)
            is Effect.RemoveStatus -> {
                val matching = target.statuses.filter { instance ->
                    (effect.status == null || effect.status.equals(ALL_STATUSES, ignoreCase = true) || instance.def.id == effect.status) &&
                        (effect.kind == null || instance.def.kind == effect.kind)
                }
                for (instance in matching) manager.removeStatus(target, instance)
            }
            is Effect.StatStage -> manager.changeStage(target, effect.stat, effect.stages.eval(expr).floorToInt(), ctx.subject)
            is Effect.Gauge -> manager.changeGauge(target, effect.amount.eval(expr))
            is Effect.Shield -> {
                val amount = effect.amount.eval(expr).floorToInt()
                if (amount > 0) manager.addShield(target, amount)
            }
            is Effect.Cooldown -> manager.changeCooldowns(target, effect.amount.eval(expr).floorToInt())
            is Effect.ConsumeItem -> {
                val item = target.item
                val text = if (item != null) effect.message?.let { manager.format(it, ctx, source, mapOf("item" to item.name)) } else null
                manager.consumeItem(target, text)
            }
            is Effect.ExtraTurn -> if (!manager.grantExtraTurn(target)) reportFailure(target, ctx, source)
            is Effect.TransferStatus -> transferStatus(effect, target, ctx, source, expr)
            is Effect.CopyStages -> for (to in resolveTargets(effect.to, ctx)) {
                if (to !== target && !to.isFainted) manager.copyStages(target, to)
            }
            is Effect.SetHp -> manager.setHp(target, effect.amount.eval(expr).floorToInt(), ctx.subject, moveOf(ctx, source))
            is Effect.SwapHp -> manager.swapHp(ctx.subject, target)
            is Effect.ForceSwitch -> if (!manager.forceSwitch(target)) reportFailure(target, ctx, source)
            is Effect.SwitchOut -> if (!manager.switchOutNow(target)) reportFailure(target, ctx, source)
            is Effect.Attack -> attack(effect, target, ctx, source, expr)
            is Effect.MultiplyDamage, is Effect.CancelAction, is Effect.Message, is Effect.SetField, is Effect.RemoveField,
            is Effect.Revive, is Effect.PreventRevive, is Effect.UseMove, is Effect.BalanceHp, is Effect.Delay -> Unit // handled in run()
        }
    }

    /** A status move (one that deals no damage) says once per target that an effect did nothing. */
    private fun reportFailure(target: Battler, ctx: EventContext, source: TriggerSource) {
        val move = ctx.move
        if (source.kind == TriggerSource.Kind.MOVE && move != null && !manager.damage.isDamaging(move) && manager.moveFailureLogged.add(target)) {
            manager.log(LogKind.MISS, FAILED)
        }
    }

    private fun applyStatus(effect: Effect.ApplyStatus, target: Battler, ctx: EventContext, source: TriggerSource, expr: ExprContext) {
        val failure: String? = if (effect.chance != null && !manager.rollPercent(effect.chance.eval(expr))) {
            if (manager.moveTargetCount > 1) "${target.name} was not affected." else FAILED
        } else {
            val duration = effect.duration?.eval(expr)?.floorToInt()
            val stacks = effect.stacks?.eval(expr)?.floorToInt()?.coerceAtLeast(1) ?: 1
            val text = effect.message?.let { text ->
                val name = manager.setup.statuses[effect.status]?.name ?: effect.status
                manager.format(text, ctx, source, mapOf("status" to name))
            }
            val fromMove = source.kind == TriggerSource.Kind.MOVE && ctx.move != null
            manager.tryApplyStatus(target, effect.status, ctx.subject, duration, stacks, text, fromMove)
        }
        // A field's blockStatus already logged its own line (or stayed silent on purpose).
        if (failure.isNullOrEmpty()) return
        // Only a status move (one that deals no damage) reports why its status did not stick.
        val move = ctx.move
        if (source.kind == TriggerSource.Kind.MOVE && move != null && !manager.damage.isDamaging(move) && manager.moveFailureLogged.add(target)) {
            manager.log(LogKind.MISS, failure)
        }
    }

    /**
     * useMove: every active actor uses the chosen move right away (README "Effects"). Fails silently when the actor
     * does not have a move to use, cannot pay for it (free = false) or has nobody to aim it at.
     */
    private fun useMove(effect: Effect.UseMove, ctx: EventContext, source: TriggerSource) {
        for (actor in resolveTargets(effect.actor, ctx)) {
            if (!actor.isActive) continue
            val slot: MoveSlot?
            val move: bakemon.MoveDef
            when (effect.move) {
                Effect.FIRST_MOVE, Effect.SAME_MOVE -> {
                    val same = if (effect.move == Effect.SAME_MOVE) ctx.move?.let { m -> actor.moves.firstOrNull { it.def.id == m.id } } else null
                    slot = same ?: actor.moves.firstOrNull() ?: continue
                    move = slot.def
                }
                else -> {
                    slot = actor.moves.firstOrNull { it.def.id == effect.move }
                    move = slot?.def ?: manager.setup.moves[effect.move] ?: continue
                }
            }
            // Paying for it: a move without PP or on cooldown cannot be used.
            if (!effect.free && slot != null && !manager.battle.isUsable(slot)) continue
            // The target, from the actor's point of view (self = the actor; other, user, target = the event's roles).
            val view = EventContext(ctx.type, actor, ctx.other, ctx.move, ctx.moveUser, ctx.moveTarget, ctx.vars, ctx.power)
            // Default: the event's other monster (a counter hits the attacker), or the move's normal target when the
            // event has none. A single-target move that cannot be aimed at the chosen monster fails.
            val picked: Battler? = if (effect.moveTarget != null) {
                resolveTargets(effect.moveTarget, view).firstOrNull() ?: continue
            } else {
                ctx.other?.takeIf { it !== actor }
            }
            if (picked != null && move.target.needsChoice && manager.battle.targetOptions(actor, move).none { it === picked }) continue
            val chosen = picked
            val scale = (effect.power?.eval(formulaContext(ctx, source, actor)) ?: 100.0) / 100.0
            val text = effect.message?.let { manager.format(it, ctx, source) }
            manager.useMoveFromEffect(actor, move, if (effect.free) null else slot, chosen, scale, text)
        }
    }

    /** transferStatus from [from] to the receivers (README "Effects"). */
    private fun transferStatus(effect: Effect.TransferStatus, from: Battler, ctx: EventContext, source: TriggerSource, expr: ExprContext) {
        val receivers = resolveTargets(effect.to, ctx).filter { it !== from }
        if (receivers.isEmpty()) return
        val count = effect.count?.eval(expr)?.floorToInt() ?: 1
        val matching = from.statuses.filter { instance ->
            (effect.status == null || effect.status.equals(ALL_STATUSES, ignoreCase = true) || instance.def.id == effect.status) &&
                (effect.kind == null || instance.def.kind == effect.kind)
        }
        val picked = if (count <= 0) matching else matching.take(count)
        for (instance in picked) {
            if (from.statuses.none { it === instance }) continue
            var given = false
            for (to in receivers) {
                if (to.isFainted) continue
                val failure = manager.tryApplyStatus(to, instance.def.id, ctx.subject, instance.turnsLeft, instance.stacks, exactDuration = true)
                if (failure == null) given = true
            }
            // A stolen status leaves its source only when a receiver got it (an immune receiver steals nothing).
            if (effect.mode == TransferMode.STEAL && given) manager.removeStatus(from, instance)
        }
    }

    /** attack: one strike of the owner computed with the damage formula (README "Effects"). */
    private fun attack(effect: Effect.Attack, target: Battler, ctx: EventContext, source: TriggerSource, expr: ExprContext) {
        val power = effect.power.eval(expr)
        if (power.isNaN() || power <= 0.0) return
        val rules = manager.setup.rules
        val base = ctx.move
        val category = effect.category
            ?: base?.category?.takeIf { rules.categories[it]?.dealsDamage == true }
            ?: rules.categories.values.firstOrNull { it.dealsDamage }?.id
            ?: return
        val strike = bakemon.MoveDef(
            id = base?.id ?: "",
            name = base?.name ?: source.name,
            description = "",
            type = effect.type ?: base?.type,
            category = category,
            power = Formula.of(power),
            accuracy = null,
            priority = 0,
            pp = 0,
            cooldown = 0,
            target = bakemon.MoveTarget.ENEMY,
            hits = Formula.of(1.0),
            critBonus = base?.critBonus,
            flags = base?.flags ?: emptySet(),
            triggers = emptyList(),
            ignoreDefense = base?.ignoreDefense,
        )
        manager.attack(ctx.subject, target, strike)
    }

    /** delay: schedules [Effect.Delay.effects] on the attached monster, with a copy of this trigger's roles. */
    private fun delay(effect: Effect.Delay, ctx: EventContext, source: TriggerSource, expr: ExprContext) {
        val attached = resolveTargets(effect.attachTo, ctx).firstOrNull() ?: return
        if (!attached.isActive) return
        val turns = effect.turns.eval(expr).floorToInt().coerceAtLeast(1)
        val roles = EventContext(ctx.type, ctx.subject, ctx.other, ctx.move, ctx.moveUser, ctx.moveTarget, HashMap(ctx.vars), ctx.power)
        manager.scheduleDelay(attached, turns, effect.effects, roles, source, effect.message)
    }

    private fun messageKind(source: TriggerSource): LogKind = when (source.kind) {
        TriggerSource.Kind.ABILITY -> LogKind.ABILITY
        TriggerSource.Kind.ITEM -> LogKind.ITEM
        TriggerSource.Kind.STATUS -> LogKind.STATUS
        TriggerSource.Kind.MOVE -> LogKind.ACTION
        TriggerSource.Kind.FIELD -> LogKind.FIELD
    }

    private companion object {
        const val ALL_STATUSES = "all"
        const val ALL_FIELDS = "all"
        const val FAILED = "But it failed!"
    }
}
