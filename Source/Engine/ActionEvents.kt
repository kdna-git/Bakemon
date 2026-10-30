package engine

import bakemon.MoveDef

import util.JsonObj
import util.SetupException

/**
 * Every event a trigger can listen to (the JSON "on" field). The owner of a trigger is the monster whose
 * ability / item / status / move-in-use holds it (for a field trigger: the monster the event is about);
 * `other` is described per event (README "Events").
 */
enum class EventType(val id: String, val description: String) {
    BATTLE_START("battleStart", "once, for every monster on the field when the battle begins"),
    SWITCH_IN("switchIn", "the owner enters the field (including at battle start)"),
    SWITCH_OUT("switchOut", "the owner is withdrawn by a switch"),
    TURN_START("turnStart", "the owner's turn begins"),
    TURN_END("turnEnd", "the owner's turn ends"),
    BEFORE_MOVE("beforeMove", "the owner is about to use a move (cancelAction allowed)"),
    AFTER_MOVE("afterMove", "the owner finished using a move"),
    HIT("hit", "the owner's move connected (once per hit)"),
    MISSED("missed", "the owner's move missed a target"),
    MODIFY_DAMAGE_DEALT("modifyDamageDealt", "the owner is about to deal move damage (multiplyDamage allowed)"),
    MODIFY_DAMAGE_TAKEN("modifyDamageTaken", "the owner is about to take move damage (multiplyDamage allowed)"),
    DEALT_DAMAGE("dealtDamage", "the owner damaged another monster"),
    DAMAGED("damaged", "the owner lost HP"),
    HEALED("healed", "the owner recovered HP"),
    STATUS_APPLIED("statusApplied", "a status was applied to the owner"),
    STATUS_REMOVED("statusRemoved", "one of the owner's statuses ended"),
    FAINTED("fainted", "the owner fainted"),
    KNOCKED_OUT("knockedOut", "the owner made another monster faint"),
    ALLY_FAINTED("allyFainted", "an ally of the owner fainted"),
    ENEMY_FAINTED("enemyFainted", "an enemy of the owner fainted"),
    REVIVED("revived", "the owner was revived"),
    ALLY_REVIVED("allyRevived", "an ally of the owner was revived"),
    ENEMY_REVIVED("enemyRevived", "an enemy of the owner was revived"),
    FIELD_SET("fieldSet", "a field started (fires for every monster on the field)"),
    FIELD_REMOVED("fieldRemoved", "a field ended (fires for every monster on the field)"),
    TARGETED("targeted", "the owner is a target of another monster's move (before the accuracy check)"),
    ATTACKED("attacked", "another monster's move that targeted the owner finished (hit, missed or did nothing)"),
    ALLY_DAMAGED("allyDamaged", "an ally of the owner took damage"),
    ENEMY_DAMAGED("enemyDamaged", "an enemy of the owner took damage"),
    ALLY_USED_MOVE("allyUsedMove", "an ally of the owner finished using a move"),
    ENEMY_USED_MOVE("enemyUsedMove", "an enemy of the owner finished using a move"),
    STATUS_INFLICTED("statusInflicted", "the owner applied a status to another monster");

    companion object {
        fun fromId(id: String): EventType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

        /** Events that a move's own triggers may listen to. */
        val MOVE_EVENTS: Set<EventType> = setOf(BEFORE_MOVE, HIT, MISSED, MODIFY_DAMAGE_DEALT, AFTER_MOVE, KNOCKED_OUT)

        /** The hit counters of the hit in progress (README "Other variables"). */
        private val HIT_COUNTERS = setOf("hitIndex", "hitCount")

        /** Event-specific variables available to trigger formulas (besides the always-available ones). */
        val EVENT_VARIABLES: Map<EventType, Set<String>> = mapOf(
            BEFORE_MOVE to setOf("cancelled"),
            HIT to setOf("damage", "isCrit", "effectiveness", "hitType") + HIT_COUNTERS,
            MODIFY_DAMAGE_DEALT to setOf("damage", "isCrit", "effectiveness", "hitType") + HIT_COUNTERS,
            MODIFY_DAMAGE_TAKEN to setOf("damage", "isCrit", "effectiveness", "hitType") + HIT_COUNTERS,
            DEALT_DAMAGE to setOf("damage"),
            DAMAGED to setOf("damage", "hpBefore"),
            HEALED to setOf("amount"),
            REVIVED to setOf("amount"),
            STATUS_APPLIED to setOf("status"),
            STATUS_REMOVED to setOf("status"),
            FIELD_SET to setOf("field"),
            FIELD_REMOVED to setOf("field"),
            ATTACKED to setOf("damage", "landed"),
            ALLY_DAMAGED to setOf("damage"),
            ENEMY_DAMAGED to setOf("damage"),
            STATUS_INFLICTED to setOf("status"),
        )

        /** Event variables that hold a string (they read as '' where the event does not set them; the others as 0). */
        val STRING_EVENT_VARIABLES: Set<String> = setOf("status", "hitType", "field")
    }
}

/** A JSON trigger: "when [on] happens, if [condition] and [chance], run [effects]". */
class Trigger(
    val on: EventType,
    val condition: Formula?,
    /** Percent (0-100), or null = always. */
    val chance: Formula?,
    /** Logged when the trigger fires (placeholders allowed), or null. */
    val message: String?,
    /** Max fires per battle per owner; 0 = unlimited. */
    val limit: Int,
    val effects: List<Effect>,
) {
    companion object {
        /**
         * Parses one trigger object. [defaultTarget] is the effect target used when an effect omits "target".
         * Throws util.SetupException (with the JSON path) on unknown events, effect types, targets or bad fields.
         */
        fun parse(obj: JsonObj, defaultTarget: TargetSelector): Trigger {
            val onId = obj.string("on")
            val on = EventType.fromId(onId)
                ?: throw SetupException(
                    "${obj.pathOf("on")}: unknown event '$onId' (expected one of: ${EventType.entries.joinToString { it.id }})"
                )
            val limit = obj.int("limit", 0)
            if (limit < 0) throw SetupException("${obj.pathOf("limit")}: must be 0 (unlimited) or more")
            val message = obj.stringOrNull("message")
            val effects = obj.arr("effects").objects().map { Effect.parse(it, defaultTarget) }
            return Trigger(
                on = on,
                condition = obj.formulaOrNull("condition"),
                chance = obj.formulaOrNull("chance"),
                message = message,
                limit = limit,
                effects = effects,
            )
        }

        /** Parses the array under [key] (missing = empty list). */
        fun parseList(obj: JsonObj, key: String, defaultTarget: TargetSelector): List<Trigger> =
            obj.arrOrEmpty(key).objects().map { parse(it, defaultTarget) }
    }
}

/** Where a firing trigger comes from (for messages and for status- or field-specific variables). */
class TriggerSource(
    val kind: Kind,
    /** Display name of the ability / item / status / move / field. */
    val name: String,
    /** The monster that owns the trigger (for a field: the monster the event is about). */
    val owner: Battler,
    /** Set when kind == STATUS (gives `stacks` / `turnsLeft`). */
    val status: StatusInstance? = null,
    /** Set when kind == FIELD (gives `stacks` / `turnsLeft` and `{field}`). */
    val field: FieldInstance? = null,
) {
    enum class Kind { ABILITY, ITEM, STATUS, MOVE, FIELD }
}

/**
 * One occurrence of an event, as seen by one owner. Triggers read it; some effects write to it
 * (cancelAction sets [cancelled], multiplyDamage multiplies [damageMultiplier]).
 */
class EventContext(
    val type: EventType,
    /** The owner whose triggers are being evaluated. */
    val subject: Battler,
    /** The other monster of the event (see README "Events"), or null. */
    val other: Battler?,
    /** The move involved, or null. */
    val move: MoveDef?,
    /** The move user when a move is involved (may differ from subject, e.g. in modifyDamageTaken). */
    val moveUser: Battler?,
    /** The current move target when a move is involved. */
    val moveTarget: Battler?,
    /** Event variables: damage, amount, isCrit, effectiveness, status... */
    val vars: MutableMap<String, Any?> = mutableMapOf(),
    /** The power of the move for the hit in progress (hit, modifyDamageDealt, modifyDamageTaken), or null. */
    val power: Double? = null,
    /**
     * modifyDamageDealt / modifyDamageTaken: the `damage` the event started with. After each multiplyDamage,
     * `damage` becomes baseDamage x [damageMultiplier], so later triggers see the damage that is still pending.
     */
    val baseDamage: Double? = null,
) {
    /** True once a cancelAction ran; beforeMove triggers read it as the `cancelled` variable. */
    var cancelled: Boolean = false
        set(value) {
            field = value
            if (type == EventType.BEFORE_MOVE) vars["cancelled"] = if (value) 1.0 else 0.0
        }
    var cancelMessage: String? = null
    /** Who cancelled last (a move cancelled only by its own trigger counts as a failed use, see Manager.useMove). */
    var cancelSource: TriggerSource.Kind? = null
    var damageMultiplier: Double = 1.0

    /** Multiplies the pending damage and updates the `damage` variable for the triggers that run after this one. */
    fun multiplyDamage(factor: Double) {
        damageMultiplier *= factor
        baseDamage?.let { vars["damage"] = it * damageMultiplier }
    }
}

/**
 * Event dispatcher. For each emitted event it evaluates, in order: the triggers of the move in use
 * (only if [includeMoveTriggers] and the event is in MOVE_EVENTS), the subject's ability, its held item (both only
 * while no field suppresses them), then its statuses (oldest first), then the triggers of the active fields that
 * listen to the subject (in the order the fields were set, see [FieldInstance.hears]). For each trigger: skip if the owner is fainted (except for FAINTED, which the Manager
 * emits before clearing the fainted monster's statuses), if the limit is reached, if the condition is false or the
 * chance roll fails; otherwise log its message and run its effects through the manager's EffectRunner.
 * Later triggers see what earlier ones did: `damage` after each multiplyDamage, `cancelled` after a cancelAction.
 * Nested emits deeper than 10 levels are ignored (prevents infinite loops).
 */
class ActionEvents(private val manager: Manager) {
    /** Current nesting level of emit calls. */
    private var depth = 0

    /** The events some trigger of the setup listens to (move, ability, item, status or field triggers). */
    private val heardEvents: Set<EventType> by lazy {
        val setup = manager.setup
        val triggers = setup.moves.all().flatMap { it.triggers } + setup.abilities.all().flatMap { it.triggers } +
            setup.items.all().flatMap { it.triggers } + setup.statuses.all().flatMap { it.triggers } +
            setup.fields.all().flatMap { f -> f.triggers.map { it.trigger } }
        triggers.mapTo(HashSet()) { it.on }
    }

    /** False when no trigger of the setup listens to [type]: emitting it could not change anything (saves time). */
    fun heard(type: EventType): Boolean = type in heardEvents

    /**
     * Emits [type] for [subject] and returns the context (check `cancelled` / `damageMultiplier`).
     * [moveUser]/[moveTarget] default to subject/other when a move is given and they are null.
     * [power] is the move's power for the hit in progress (triggers then read it as `move.power`).
     */
    fun emit(
        type: EventType,
        subject: Battler,
        other: Battler? = null,
        move: MoveDef? = null,
        vars: Map<String, Any?> = emptyMap(),
        includeMoveTriggers: Boolean = false,
        moveUser: Battler? = null,
        moveTarget: Battler? = null,
        power: Double? = null,
    ): EventContext = emitInternal(type, subject, other, move, vars, includeMoveTriggers, moveUser, moveTarget, null, power)

    /**
     * Emits STATUS_REMOVED for [subject]. The [removed] instance (already detached from the battler) also gets
     * its own triggers evaluated, last, so a status can react to its own end (e.g. a delayed explosion).
     */
    internal fun emitStatusRemoved(subject: Battler, removed: StatusInstance): EventContext =
        emitInternal(
            EventType.STATUS_REMOVED, subject, null, null, mapOf("status" to removed.def.id),
            includeMoveTriggers = false, moveUser = null, moveTarget = null, removed = removed, power = null,
        )

    /**
     * Emits FIELD_REMOVED for [subject]. The [removed] field (already gone from Battle.fields) also gets its own
     * triggers evaluated, last, so a field can react to its own end.
     */
    internal fun emitFieldRemoved(subject: Battler, other: Battler?, removed: FieldInstance): EventContext =
        emitInternal(
            EventType.FIELD_REMOVED, subject, other, null, mapOf("field" to removed.def.id),
            includeMoveTriggers = false, moveUser = null, moveTarget = null, removed = null, power = null, removedField = removed,
        )

    private fun emitInternal(
        type: EventType,
        subject: Battler,
        other: Battler?,
        move: MoveDef?,
        vars: Map<String, Any?>,
        includeMoveTriggers: Boolean,
        moveUser: Battler?,
        moveTarget: Battler?,
        removed: StatusInstance?,
        power: Double?,
        removedField: FieldInstance? = null,
    ): EventContext {
        val modifies = type == EventType.MODIFY_DAMAGE_DEALT || type == EventType.MODIFY_DAMAGE_TAKEN
        val ctx = EventContext(
            type = type,
            subject = subject,
            other = other,
            move = move,
            moveUser = moveUser ?: if (move != null) subject else null,
            moveTarget = moveTarget ?: if (move != null) other else null,
            vars = if (vars.isEmpty()) HashMap(4) else HashMap(vars),
            power = power,
            baseDamage = if (modifies) (vars["damage"] as? Number)?.toDouble() else null,
        )
        if (depth >= MAX_DEPTH) return ctx
        depth++
        try {
            if (includeMoveTriggers && move != null && type in EventType.MOVE_EVENTS) {
                fire(move.triggers, ctx, TriggerSource.Kind.MOVE, move.name, null) { true }
            }
            val ability = subject.activeAbility
            if (ability != null) {
                fire(ability.triggers, ctx, TriggerSource.Kind.ABILITY, ability.name, null) { subject.activeAbility === ability }
            }
            val item = subject.activeItem
            if (item != null) {
                fire(item.triggers, ctx, TriggerSource.Kind.ITEM, item.name, null) { subject.activeItem === item }
            }
            if (subject.statuses.isNotEmpty()) {
                // Snapshot: effects may add or remove statuses while we iterate.
                for (instance in subject.statuses.toList()) {
                    fire(instance.def.triggers, ctx, TriggerSource.Kind.STATUS, instance.def.name, instance) {
                        subject.statuses.any { it === instance }
                    }
                }
            }
            if (removed != null) {
                fire(removed.def.triggers, ctx, TriggerSource.Kind.STATUS, removed.def.name, removed) { true }
            }
            val fields = manager.battle.activeFields
            if (fields.isNotEmpty()) {
                // Snapshot: effects may set or remove fields while we iterate.
                for (field in fields.toList()) {
                    val triggers = fieldTriggers(field, ctx) ?: continue
                    fire(triggers, ctx, TriggerSource.Kind.FIELD, field.def.name, null, field) { fields.any { it === field } }
                }
            }
            if (removedField != null) {
                fieldTriggers(removedField, ctx)?.let { triggers ->
                    fire(triggers, ctx, TriggerSource.Kind.FIELD, removedField.def.name, null, removedField) { true }
                }
            }
        } finally {
            depth--
        }
        return ctx
    }

    /** The triggers of [field] for ctx.type that listen to ctx.subject, or null if there are none. */
    private fun fieldTriggers(field: FieldInstance, ctx: EventContext): List<Trigger>? {
        var result: MutableList<Trigger>? = null
        for (t in field.def.triggers) {
            if (t.trigger.on != ctx.type || !field.hears(t.audience, ctx.subject)) continue
            val list = result ?: ArrayList<Trigger>(2).also { result = it }
            list += t.trigger
        }
        return result
    }

    /** Evaluates every trigger of [triggers] that listens to ctx.type; stops as soon as [stillHeld] is false. */
    private inline fun fire(
        triggers: List<Trigger>,
        ctx: EventContext,
        kind: TriggerSource.Kind,
        name: String,
        status: StatusInstance?,
        field: FieldInstance? = null,
        stillHeld: () -> Boolean,
    ) {
        if (triggers.isEmpty()) return
        var source: TriggerSource? = null
        var eventCtx = ctx
        var exprCtx: ExprContext? = null
        for (trigger in triggers) {
            if (trigger.on != ctx.type) continue
            val owner = ctx.subject
            if (owner.isFainted && ctx.type != EventType.FAINTED) return
            if (!stillHeld()) return
            if (trigger.limit > 0 && (owner.triggerUses[trigger] ?: 0) >= trigger.limit) continue
            if (source == null) {
                source = TriggerSource(kind, name, owner, status, field)
                eventCtx = when {
                    status != null -> sourceView(ctx, status.source)
                    field != null -> sourceView(ctx, field.setter)
                    else -> ctx
                }
                exprCtx = manager.effects.formulaContext(eventCtx, source)
            }
            val expr = exprCtx!!
            if (trigger.condition != null && !trigger.condition.test(expr)) continue
            if (trigger.chance != null && !manager.rollPercent(trigger.chance.eval(expr))) continue
            owner.triggerUses[trigger] = (owner.triggerUses[trigger] ?: 0) + 1
            if (eventCtx !== ctx) copyState(ctx, eventCtx)
            trigger.message?.let { manager.log(logKind(kind), manager.format(it, eventCtx, source)) }
            manager.effects.run(trigger.effects, eventCtx, source)
            if (eventCtx !== ctx) copyState(eventCtx, ctx)
        }
    }

    /**
     * In a status's triggers, `other` is the monster that applied the status (in a field's triggers: the monster that
     * set the field), if still active, when the event has no other monster. Returns a view of [ctx] with that
     * substitution, or [ctx] itself when nothing changes.
     */
    private fun sourceView(ctx: EventContext, source: Battler?): EventContext {
        if (source == null || ctx.other != null) return ctx
        if (!source.isActive || source === ctx.subject) return ctx
        return EventContext(ctx.type, ctx.subject, source, ctx.move, ctx.moveUser, ctx.moveTarget, ctx.vars, ctx.power, ctx.baseDamage)
    }

    private fun copyState(from: EventContext, to: EventContext) {
        to.cancelled = from.cancelled
        to.cancelMessage = from.cancelMessage
        to.cancelSource = from.cancelSource
        to.damageMultiplier = from.damageMultiplier
    }

    private companion object {
        const val MAX_DEPTH = 10

        fun logKind(kind: TriggerSource.Kind): LogKind = when (kind) {
            TriggerSource.Kind.ABILITY -> LogKind.ABILITY
            TriggerSource.Kind.ITEM -> LogKind.ITEM
            TriggerSource.Kind.STATUS -> LogKind.STATUS
            TriggerSource.Kind.MOVE -> LogKind.ACTION
            TriggerSource.Kind.FIELD -> LogKind.FIELD
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Battle log (what the UI prints)

/** What a log line is about (the UI colours lines by kind). FIELD: fields starting and ending, their triggers and blocks. */
enum class LogKind { INFO, TURN, ACTION, DAMAGE, HEAL, STATUS, STAT, ABILITY, ITEM, MISS, EFFECTIVE, CRIT, FAINT, SWITCH, RESULT, FIELD }

data class LogEntry(val kind: LogKind, val text: String)

/** Receives what happens during a battle. All methods are optional. */
interface BattleObserver {
    fun onLog(entry: LogEntry) {}

    /** Before actions are chosen: speed_order -> once per round with actor = null; action_bar -> the acting monster. */
    fun onTurnStart(battle: Battle, actor: Battler?) {}

    fun onBattleEnd(battle: Battle, result: BattleResult) {}
}
