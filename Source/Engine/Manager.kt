package engine

import bakemon.ChargeDef
import bakemon.FieldDef
import bakemon.FieldStart
import bakemon.HitAccuracy
import bakemon.MoveDef
import bakemon.MoveTarget
import bakemon.Setup
import bakemon.Stacking
import bakemon.StatusDef
import bakemon.StatusKind
import bakemon.Team
import bakemon.TurnSystem
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.random.Random

/** How a battle ended. [winner] is the side index, or null for a draw. */
data class BattleResult(val winner: Int?, val turns: Int, val reason: String)

/**
 * Runs a battle from start to finish: builds the sides from two validated teams, sets the start fields
 * (Rules.startFields, then [startFields]), sends out the leads (switchIn + battleStart), then loops the configured
 * turn system until one side is defeated or the turn limit is reached. It is the only class that mutates the
 * [Battle]; effects call its operations below.
 *
 * Events that happen to several monsters at once run fastest first (effective speed, random ties), never in side
 * order: the leads' switchIn and battleStart, the switchIn of replacements sent in together, and the speed_order
 * turnStart / turnEnd passes.
 *
 * speed_order round: turn++ -> observer.onTurnStart(null) -> turnStart for every active monster -> check end ->
 *   every active monster's controller chooses (a benched teammate already picked by an ally's switch is not an
 *   option) -> sort (switches first, then move priority, then effective speed, random ties) -> execute in order
 *   (skip fainted actors; a planned move made unusable since fails; a planned switch that became impossible is
 *   replaced by the first legal action, run at its own place in the order; retarget single-enemy moves if the
 *   target fainted; check end after each action) -> extra turns (extraTurn effect: each monster that got one chooses
 *   and acts once more, in the order they got them) -> turnEnd + status/cooldown countdown for every active monster
 *   -> delayed effects countdown -> field countdown -> check end (both sides wiped out = draw) -> replace fainted
 *   monsters. While a global field with "reverseSpeed" is active when the actions are sorted, the slower go first.
 *
 * action_bar turn: the monster that got an extra turn acts first (no time passes); otherwise advance time until
 *   someone's gauge is full (ties: higher gauge, then speed, then random) -> turn++ -> reset its gauge to 0 ->
 *   observer.onTurnStart(actor) -> turnStart -> choose & execute -> turnEnd + countdown for the actor -> its delayed
 *   effects -> field countdown (fields set by the actor's side and start fields) -> replace fainted monsters
 *   immediately -> check end.
 *
 * A monster locked into a charge move ([Battler.charge]) is not asked for an action: it continues the charge.
 *
 * Replacing fainted monsters: every side chooses for all its empty slots before anything is sent in (a side never
 * sees the other side's pick), then all of them enter, then their switchIn triggers run.
 *
 * Status freshness follows [Battler.turnPhase] (README "Durations"): a status applied to a monster during its
 * turn but after its action started is fresh and skips that turn's countdown; any other status counts it.
 * A `cooldown` effect follows the same rule (see [changeCooldowns]).
 *
 * Controllers' answers are validated; illegal ones (including a move without any legal target) are replaced by the
 * first legal action. Any monster with no usable move (or whose usable moves all lack a legal target, see
 * [Battle.hasTarget]) uses Rules.fallbackMove (or passes).
 */
class Manager(
    val setup: Setup,
    teams: List<Team>,
    controllers: List<Controller>,
    val observer: BattleObserver = object : BattleObserver {},
    seed: Long? = null,
    sideNames: List<String> = listOf("Player 1", "Player 2"),
    /** Fields to set when the battle starts, after Rules.startFields (e.g. picked in the battle menu). */
    val startFields: List<FieldStart> = emptyList(),
) {
    val random: Random = Random(seed ?: System.nanoTime())
    val battle: Battle = buildBattle(teams, controllers, sideNames)
    val events: ActionEvents = ActionEvents(this)
    val effects: EffectRunner = EffectRunner(this)
    val damage: DamageCalculator = DamageCalculator(setup)

    private val rules get() = setup.rules
    private var started = false
    private var result: BattleResult? = null
    /** Number of lines logged so far (used to know whether a cancelled move already explained itself). */
    private var logCount = 0L
    /** Monsters whose faint was already processed. */
    private val faintHandled: MutableSet<Battler> = Collections.newSetFromMap(IdentityHashMap())
    /** Monsters whose fainting is being processed right now (a revive of one of them waits until it is done). */
    private val fainting: MutableSet<Battler> = Collections.newSetFromMap(IdentityHashMap())
    private val pendingRevives: MutableMap<Battler, PendingRevive> = IdentityHashMap()
    /** speed_order: monsters revived during the current round (their planned actions are dropped). */
    private val revivedThisRound: MutableSet<Battler> = Collections.newSetFromMap(IdentityHashMap())
    /** speed_order: monsters that left the field during the current round (their planned actions are dropped). */
    private val withdrawnThisRound: MutableSet<Battler> = Collections.newSetFromMap(IdentityHashMap())
    /** speed_order: true from the start of a round until its turnEnd/countdown phase (a monster switched in meanwhile has its turn). */
    private var roundOpen = false
    /** Monsters waiting for an extra turn (extraTurn effect), in the order they got it. */
    private val extraTurns = ArrayList<Battler>()
    /** How many moves used by the useMove effect are nested right now (at most [MAX_EFFECT_MOVE_DEPTH]). */
    private var effectMoveDepth = 0

    /** Effects of a delay effect waiting for the end of [attached]'s turns (README "Effects", delay). */
    private class DelayedEffects(
        val attached: Battler,
        var turnsLeft: Int,
        val effects: List<Effect>,
        val ctx: EventContext,
        val source: TriggerSource,
        val message: String?,
    )

    /** Delayed effects, in the order they were scheduled. */
    private val delays = ArrayList<DelayedEffects>()

    /** The battler whose end-of-turn status countdown is running, if any. */
    private var countdownOwner: Battler? = null

    /** Its statuses refreshed (or re-stacked) during that countdown: they are not counted down again in it. */
    private val refreshedInCountdown: MutableSet<StatusInstance> = Collections.newSetFromMap(IdentityHashMap())

    /** Targets for which the current status move already reported why it failed (at most one line per target). */
    internal val moveFailureLogged: MutableSet<Battler> = Collections.newSetFromMap(IdentityHashMap())

    /** How many targets the move in progress affects (a failure line names the target when there are several). */
    internal var moveTargetCount = 0

    /** True once the current move reported that a setField failed (at most one "But it failed!" per move). */
    private var fieldFailureLogged = false

    /** True once a field was set (or refreshed / stacked) during the current move. */
    internal var fieldSetThisMove = false
        private set

    /**
     * Claims the right to log the current move's setField failure: true the first time per move, and only while the
     * move has set no field (a team or allEnemies move that set the field for its first target did not fail).
     */
    internal fun claimFieldFailure(): Boolean =
        if (fieldFailureLogged || fieldSetThisMove) false else true.also { fieldFailureLogged = true }

    private fun buildBattle(teams: List<Team>, controllers: List<Controller>, sideNames: List<String>): Battle {
        require(teams.size == 2) { "A battle needs exactly 2 teams (got ${teams.size})" }
        require(controllers.size == 2) { "A battle needs exactly 2 controllers (got ${controllers.size})" }
        val slots = setup.rules.activePerSide.coerceAtLeast(1)
        val sides = List(2) { i ->
            val name = sideNames.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Player ${i + 1}"
            Side(i, name, controllers[i], slots)
        }
        // A name used by both sides (e.g. the same species on each team) gets the side name appended, so the log
        // says which one acted: "Embercub (Player 1)" / "Embercub (Player 2)".
        val names = teams.map { team -> team.members.map { it.displayName(setup) } }
        val shared = names[0].toSet() intersect names[1].toSet()
        for (side in sides) {
            val tag = if (sides[0].name != sides[1].name) side.name else "${side.index + 1}"
            teams[side.index].members.forEachIndexed { index, member ->
                val name = names[side.index][index]
                side.team += Battler.create(setup, member, side, index, if (name in shared) "$name ($tag)" else name)
            }
        }
        return Battle(setup, sides, random)
    }

    /** Runs the battle to the end. Can be called once. */
    fun run(): BattleResult {
        check(!started) { "Manager.run() can be called only once" }
        started = true
        startBattle()
        var iterations = 0
        while (!checkEnd()) {
            if (++iterations > MAX_ITERATIONS) {
                finish(null, "The battle took too long")
                break
            }
            val limit = rules.turnLimit
            if (limit > 0 && battle.turn >= limit) {
                finish(null, "Turn limit reached")
                break
            }
            when (rules.turnSystem) {
                TurnSystem.SPEED_ORDER -> playRound()
                TurnSystem.ACTION_BAR -> playActionBarTurn()
            }
        }
        return result ?: BattleResult(null, battle.turn, "The battle was interrupted")
    }

    // ---- turn systems -------------------------------------------------------------------------------------------

    private fun startBattle() {
        // Start fields are in place before anyone enters, so the leads' switchIn already sees them.
        for (start in rules.startFields + startFields) setStartField(start)
        for (side in battle.sides) {
            for (slot in side.active.indices) {
                val member = side.team.getOrNull(slot) ?: break
                if (!member.isFainted) place(member, slot)
            }
        }
        if (rules.turnSystem == TurnSystem.ACTION_BAR) {
            for (b in battle.allActive()) b.gauge = startGauge(b)
        }
        // Every lead is on the field before any switchIn runs; then fastest first, not side 0 first.
        val leads = bySpeed(battle.allActive())
        for (b in leads) if (b.isActive) events.emit(EventType.SWITCH_IN, b)
        for (b in leads) if (b.isActive) events.emit(EventType.BATTLE_START, b)
        if (!checkEnd()) replaceFainted()
    }

    /** Sets one start field (no setter: in action_bar it counts down at the end of every turn). */
    private fun setStartField(start: FieldStart) {
        val def = setup.fields[start.field] ?: return
        val duration = if (start.permanent) null else start.duration?.let { formula ->
            formula.eval(battle.context(null)).floorToInt()
        }
        val places: List<Side?> = when {
            def.isGlobal -> listOf(null)
            start.side == null -> battle.sides
            else -> listOfNotNull(battle.sides.getOrNull(start.side))
        }
        for (side in places) trySetField(def, side, null, duration, 1, null, start.permanent)
    }

    /**
     * [battlers] fastest first (effective speed), ties in random order: the order of events that happen to several
     * monsters at once (README "Turn systems"), so no side is favoured by its index.
     */
    private fun bySpeed(battlers: List<Battler>): List<Battler> {
        if (battlers.size <= 1) return battlers
        val keyed = battlers.map { Triple(it, it.stat(rules.speedStat), random.nextDouble()) }
        return keyed.sortedWith(compareByDescending<Triple<Battler, Double, Double>> { it.second }.thenBy { it.third }).map { it.first }
    }

    private fun startGauge(b: Battler): Double {
        val formula = rules.actionBarStart
        val percent = formula.constant
            ?: formula.eval(battle.context(b, vars = mapOf("speed" to b.stat(rules.speedStat))))
        val size = rules.actionBarSize.coerceAtLeast(0.0)
        if (percent.isNaN()) return 0.0
        return (percent * size / 100.0).coerceIn(0.0, size)
    }

    /** One speed_order round. */
    private fun playRound() {
        battle.turn++
        log(LogKind.TURN, "Turn ${battle.turn}")
        // Every monster on the field has its turn this round: a status it gets before its action affects that action,
        // so it counts down at the end of the round (README "Durations").
        roundOpen = true
        for (b in battle.allActive()) b.turnPhase = TurnPhase.WAITING
        observer.onTurnStart(battle, null)
        // turnStart runs for every active monster (fastest first) before the end is checked, so a mutual wipe is a draw.
        for (b in bySpeed(battle.allActive())) {
            if (!b.isActive) continue
            events.emit(EventType.TURN_START, b)
        }
        if (checkEnd()) return

        revivedThisRound.clear()
        withdrawnThisRound.clear()
        val planned = ArrayList<Planned>()
        try {
            for (b in battle.allActive()) {
                val action = choose(b)
                // A teammate already picked by an ally's switch is not offered (nor accepted) again this round.
                if (action is Action.Switch) battle.claimedSwitchIns += action.replacement
                planned += plan(b, action, b.stat(rules.speedStat), random.nextDouble())
            }
        } finally {
            battle.claimedSwitchIns.clear()
        }
        // A global "reverseSpeed" field (README "Fields.json") active now orders this round slowest first.
        val order = if (battle.activeFields.any { it.side == null && it.def.rules.reverseSpeed }) REVERSED_PLAN_ORDER else PLAN_ORDER
        val queue = planned.sortedWith(order).toMutableList()
        var next = 0
        while (next < queue.size) {
            val p = queue[next++]
            val actor = p.actor
            // A monster that fainted and was revived (or left the field) since it chose its action does not act this round.
            if (!actor.isActive || actor in revivedThisRound || actor in withdrawnThisRound) continue
            var action = p.action
            if (action is Action.Switch) {
                // A switch chosen at the start of the round may no longer be possible: its replacement action runs at
                // its own place in the order (priority, speed), not in the switch phase.
                val legal = validate(actor, action)
                if (legal !is Action.Switch) {
                    val replacement = plan(actor, legal, p.speed, p.tieBreak)
                    var at = next
                    while (at < queue.size && order.compare(queue[at], replacement) <= 0) at++
                    queue.add(at, replacement)
                    continue
                }
                action = legal
            }
            actor.turnPhase = TurnPhase.ACTED
            if (action is Action.UseMove && action.slot != null && !battle.isUsable(action.slot)) {
                // Chosen at the start of the round, but made unusable since (e.g. a cooldown effect): it fails.
                log(LogKind.MISS, "${actor.name} can't use ${action.move.name} right now!")
                continue
            }
            battle.currentActor = actor
            execute(actor, action)
            battle.currentActor = null
            if (checkEnd()) return
        }

        // Extra turns (extraTurn effect): one more action each, at most one per monster per round.
        while (extraTurns.isNotEmpty()) {
            val b = extraTurns.removeAt(0)
            b.extraTurnPending = false
            if (!b.isActive || b in revivedThisRound) continue
            b.extraTurnRound = battle.turn
            log(LogKind.INFO, "${b.name} takes its extra turn!")
            val action = choose(b)
            battle.currentActor = b
            execute(b, action)
            battle.currentActor = null
            if (checkEnd()) return
        }

        // turnEnd + countdown for every active monster, fastest first (residual effects that interact, like a seed
        // healing its planter, do not depend on the side index), then one end check: residual damage that knocks out
        // the last monster of both sides is a draw.
        roundOpen = false
        for (b in bySpeed(battle.allActive())) {
            if (!b.isActive) continue
            endTurn(b)
        }
        // Delayed effects count the round, including the round they were scheduled in (README "Effects", delay).
        countDownDelays { true }
        // Every field counts the round, including the round it was set in (README "Fields.json").
        countDownFields { true }
        if (checkEnd()) return
        replaceFainted()
    }

    /**
     * Counts down every active field with a duration for which [counts] is true (in the order they were set) and
     * ends those that reach 0.
     */
    private inline fun countDownFields(counts: (FieldInstance) -> Boolean) {
        if (battle.activeFields.isEmpty()) return
        for (field in battle.activeFields.toList()) {
            if (battle.activeFields.none { it === field }) continue
            val left = field.turnsLeft ?: continue
            if (!counts(field)) continue
            field.turnsLeft = left - 1
            if (left - 1 <= 0) removeField(field, null, null)
        }
    }

    private fun plan(actor: Battler, action: Action, speed: Double, tieBreak: Double): Planned {
        val priority = if (action is Action.UseMove) action.move.priority else 0
        return Planned(actor, action, action is Action.Switch, priority, speed, tieBreak)
    }

    /** One action_bar turn. */
    private fun playActionBarTurn() {
        // A monster that got an extra turn acts first, before anyone else with a full bar (no time passes).
        var extra: Battler? = null
        while (extra == null && extraTurns.isNotEmpty()) {
            val b = extraTurns.removeAt(0)
            b.extraTurnPending = false
            if (b.isActive) extra = b
        }
        val actor: Battler
        if (extra != null) {
            actor = extra
        } else {
            val actives = battle.allActive()
            if (actives.isEmpty()) {
                finish(null, "No monster can act")
                return
            }
            val rates = DoubleArray(actives.size) { battle.fillRate(actives[it]) }
            val gauges = DoubleArray(actives.size) { actives[it].gauge }
            val next = battle.selectNext(actives, gauges, rates, random)
            if (next < 0) {
                finish(null, "No monster can act")
                return
            }
            battle.advanceGauges(gauges, rates, battle.timeToFull(gauges[next], rates[next]))
            for (i in actives.indices) actives[i].gauge = gauges[i]
            actor = actives[next]
            // A normal turn: it may get an extra turn again.
            actor.extraTurnTaken = false
        }

        battle.turn++
        actor.gauge = 0.0
        battle.currentActor = actor
        actor.turnPhase = TurnPhase.WAITING
        log(LogKind.TURN, "Turn ${battle.turn}: ${actor.baseName} (${actor.side.name})" + if (extra != null) " - extra turn" else "")
        observer.onTurnStart(battle, actor)
        playActorTurn(actor)
        actor.turnPhase = TurnPhase.CLOSED
        battle.currentActor = null
        // The actor's turn is over: the fields its side set (and the start fields) count it (README "Fields.json").
        if (!battle.ended) {
            val side = actor.side
            countDownFields { it.setterSide == null || it.setterSide === side }
        }
        if (!checkEnd()) replaceFainted()
    }

    private fun playActorTurn(actor: Battler) {
        val revivesBefore = actor.revives
        events.emit(EventType.TURN_START, actor)
        // Fainted at the start of its turn (e.g. continuous damage) and revived: that turn is lost, like in speed_order.
        if (checkEnd() || !actor.isActive || actor.revives != revivesBefore) return
        val action = choose(actor)
        actor.turnPhase = TurnPhase.ACTED
        execute(actor, action)
        if (checkEnd() || !actor.isActive) return
        endTurn(actor)
        // The delayed effects attached to the actor count its turn (README "Effects", delay).
        countDownDelays { it.attached === actor }
    }

    /**
     * Counts down the delayed effects for which [counts] is true (a snapshot: effects scheduled meanwhile wait for the
     * next count) and runs those that reach 0, in the order they were scheduled. The roles are those of the trigger
     * that scheduled them; the attached monster must still be on the field.
     */
    private inline fun countDownDelays(counts: (DelayedEffects) -> Boolean) {
        if (delays.isEmpty()) return
        for (d in delays.toList()) {
            if (over()) return
            if (delays.none { it === d } || !counts(d)) continue
            d.turnsLeft--
            if (d.turnsLeft > 0) continue
            delays.removeAll { it === d }
            if (!d.attached.isActive) continue
            moveFailureLogged.clear()
            moveTargetCount = 1
            d.message?.let { log(LogKind.INFO, format(it, d.ctx, d.source)) }
            effects.run(d.effects, d.ctx, d.source)
        }
    }

    /**
     * The delay effect (README "Effects"): runs [effects] with the roles of [ctx] and [source] at the end of
     * [attached]'s [turns]th turn from now (speed_order: the [turns]th round end, counting the current round).
     * Dropped when [attached] faints or leaves the field before. At most [MAX_DELAYS] wait at the same time.
     */
    fun scheduleDelay(attached: Battler, turns: Int, effects: List<Effect>, ctx: EventContext, source: TriggerSource, message: String?) {
        if (!attached.isActive || battle.ended || delays.size >= MAX_DELAYS || effects.isEmpty() && message == null) return
        delays += DelayedEffects(attached, turns.coerceAtLeast(1), effects, ctx, source, message)
    }

    /** How many delayed effects wait (for tests and the battle screen). */
    val pendingDelays: Int get() = delays.size

    /** turnEnd for [b], then its cooldown and status countdown (README "Durations and cooldowns"). */
    private fun endTurn(b: Battler) {
        events.emit(EventType.TURN_END, b)
        // Its turn is over: a status applied from now on (e.g. by a statusRemoved trigger during the countdown)
        // is not fresh, so it counts down at the end of the owner's next turn.
        b.turnPhase = TurnPhase.CLOSED
        if (!b.isActive) return
        for (slot in b.moves) if (slot.cooldown > 0) slot.cooldown--
        countdownOwner = b
        refreshedInCountdown.clear()
        try {
            for (instance in b.statuses.toList()) {
                if (b.statuses.none { it === instance }) continue
                // Refreshed during this countdown (e.g. by an earlier status's statusRemoved trigger): like a status
                // applied now, it counts down at the end of the owner's next turn, wherever it sits in the list.
                if (instance in refreshedInCountdown) continue
                val left = instance.turnsLeft
                if (left == null) {
                    instance.fresh = false
                    continue
                }
                if (instance.fresh && left > 0) {
                    instance.fresh = false
                    continue
                }
                instance.fresh = false
                instance.turnsLeft = left - 1
                if (left - 1 <= 0) {
                    removeStatus(b, instance)
                    if (!b.isActive) return
                }
            }
        } finally {
            countdownOwner = null
            refreshedInCountdown.clear()
        }
    }

    /**
     * Fills the slots of fainted monsters with benched teammates. Every side's controller chooses for all its empty
     * slots first, while the fainted monsters are still in their slots (no side sees the other side's pick); then
     * every replacement enters, then their switchIn triggers run fastest first (like the leads at battle start).
     */
    private fun replaceFainted() {
        var passes = 0
        while (passes++ < MAX_REPLACEMENT_PASSES) {
            if (over()) return
            val incoming = ArrayList<Pair<Battler, Int>>()
            for (side in battle.sides) {
                for (slot in side.active.indices) {
                    val current = side.active[slot]
                    if (current != null && !current.isFainted) continue
                    // A teammate already picked for another slot of this side is not offered again.
                    val options = side.bench().filter { b -> incoming.none { it.first === b } }
                    if (options.isEmpty()) continue
                    val chosen = side.controller.chooseReplacement(battle, side, slot, options)
                    incoming += (options.firstOrNull { it === chosen } ?: options.first()) to slot
                }
            }
            if (incoming.isEmpty()) return
            for ((b, slot) in incoming) place(b, slot)
            for (b in bySpeed(incoming.map { it.first })) {
                if (over()) return
                if (b.isActive) events.emit(EventType.SWITCH_IN, b)
            }
            // A switch-in effect may have knocked out something else: replace again.
        }
    }

    private fun checkEnd(): Boolean {
        if (battle.ended) return true
        val lost0 = battle.sides[0].isDefeated()
        val lost1 = battle.sides[1].isDefeated()
        when {
            lost0 && lost1 -> finish(null, "Both sides were defeated")
            lost0 -> finish(1, "${battle.sides[0].name} has no monsters left")
            lost1 -> finish(0, "${battle.sides[1].name} has no monsters left")
            else -> return false
        }
        return true
    }

    /** True once the battle is decided (even if [finish] was not called yet). */
    private fun over(): Boolean = battle.ended || battle.sides[0].isDefeated() || battle.sides[1].isDefeated()

    private fun finish(winner: Int?, reason: String) {
        if (battle.ended) return
        battle.ended = true
        battle.currentActor = null
        val r = BattleResult(winner, battle.turn, reason)
        result = r
        log(
            LogKind.RESULT,
            if (winner != null) "${battle.sides[winner].name} wins! ($reason)" else "The battle ended in a draw. ($reason)",
        )
        observer.onBattleEnd(battle, r)
    }

    // ---- choosing actions -------------------------------------------------------------------------------------

    /** The action of [actor]: a monster locked into a charge move continues it; the others ask their controller. */
    private fun choose(actor: Battler): Action {
        actor.charge?.let { return Action.UseMove(it.move, null, it.target) }
        return validate(actor, actor.side.controller.chooseAction(battle, actor))
    }

    /** Returns [action] if it is legal for [actor] right now, otherwise the first legal action. */
    private fun validate(actor: Battler, action: Action): Action {
        when (action) {
            is Action.UseMove -> {
                val slot = action.slot ?: actor.moves.firstOrNull { it.def.id == action.move.id }
                if (slot != null) {
                    // A move without any legal target (an ally move with no ally left) is not a legal choice.
                    val legal = actor.moves.any { it === slot } && battle.isUsable(slot) && battle.hasTarget(actor, slot.def) &&
                        !battle.isMoveBlocked(actor, slot.def, null)
                    if (legal) {
                        return withLegalTarget(actor, action, slot.def, slot)
                    }
                } else {
                    val fallback = battle.fallbackMove()
                    if (fallback != null && fallback.id == action.move.id && battle.usableMoves(actor).none { battle.hasTarget(actor, it.def) }) {
                        return withLegalTarget(actor, action, fallback, null)
                    }
                }
            }
            is Action.Switch -> {
                val legal = rules.switching && actor.isActive && battle.switchOptions(actor).any { it === action.replacement }
                if (legal) return action
            }
            Action.Pass -> if (firstLegal(actor) == Action.Pass) return action
        }
        return firstLegal(actor)
    }

    /** Keeps the chosen target if it is a legal choice, otherwise picks the first option. */
    private fun withLegalTarget(actor: Battler, action: Action.UseMove, move: MoveDef, slot: MoveSlot?): Action {
        if (!move.target.needsChoice) {
            return if (action.move === move && action.slot === slot) action else Action.UseMove(move, slot, action.target)
        }
        val options = battle.targetOptions(actor, move)
        // Nobody to choose (only possible for the fallback move): the first legal action, which is that move anyway.
        if (options.isEmpty()) return firstLegal(actor)
        val target = options.firstOrNull { it === action.target } ?: options.first()
        return if (action.move === move && action.slot === slot && action.target === target) action else Action.UseMove(move, slot, target)
    }

    /**
     * The first usable move that has a legal target; else the fallback move (a monster whose usable moves all lack a
     * target, e.g. an ally-only move with no ally left, counts as having no usable move); else Pass.
     */
    private fun firstLegal(actor: Battler): Action {
        val pick = battle.usableMoves(actor).firstOrNull { battle.hasTarget(actor, it.def) }
        if (pick != null) return Action.UseMove(pick.def, pick, defaultTarget(actor, pick.def))
        val fallback = battle.fallbackMove() ?: return Action.Pass
        return Action.UseMove(fallback, null, defaultTarget(actor, fallback))
    }

    private fun defaultTarget(actor: Battler, move: MoveDef): Battler? =
        if (move.target.needsChoice) battle.targetOptions(actor, move).firstOrNull() else null

    // ---- operations (used by the turn loop and by effects) ----------------------------------------------------

    /** Sends one line to the observer. Blank lines are dropped (an empty JSON message means "say nothing"). */
    fun log(kind: LogKind, text: String) {
        if (text.isBlank()) return
        logCount++
        observer.onLog(LogEntry(kind, text))
    }

    /**
     * Fills message placeholders ({self}, {other}, {user}, {target}, {move}, {damage}, {amount}, {status},
     * {ability}, {item}, {field}) from an event context and optional source.
     */
    fun format(text: String, ctx: EventContext?, source: TriggerSource? = null, extra: Map<String, String> = emptyMap()): String {
        if (text.indexOf('{') < 0) return text
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val firstOpen = text.indexOf('{', i)
            val close = if (firstOpen < 0) -1 else text.indexOf('}', firstOpen + 1)
            if (close < 0) {
                out.append(text, i, text.length)
                break
            }
            val open = text.lastIndexOf('{', close)
            out.append(text, i, open)
            val key = text.substring(open + 1, close)
            val value = extra[key] ?: placeholder(key, ctx, source)
            if (value != null) out.append(value) else out.append(text, open, close + 1)
            i = close + 1
        }
        return out.toString()
    }

    private fun placeholder(key: String, ctx: EventContext?, source: TriggerSource?): String? = when (key) {
        "self" -> ctx?.subject?.name ?: ""
        "other" -> ctx?.other?.name ?: ""
        "user" -> (ctx?.moveUser ?: ctx?.subject)?.name ?: ""
        "target" -> (ctx?.moveTarget ?: ctx?.other)?.name ?: ""
        "move" -> ctx?.move?.name ?: source?.takeIf { it.kind == TriggerSource.Kind.MOVE }?.name ?: ""
        "damage" -> number(ctx?.vars?.get("damage"))
        "amount" -> number(ctx?.vars?.get("amount"))
        "status" -> (ctx?.vars?.get("status") as? String)?.let { setup.statuses[it]?.name ?: it }
            ?: source?.takeIf { it.kind == TriggerSource.Kind.STATUS }?.name ?: ""
        "ability" -> source?.takeIf { it.kind == TriggerSource.Kind.ABILITY }?.name ?: ctx?.subject?.ability?.name ?: ""
        "item" -> source?.takeIf { it.kind == TriggerSource.Kind.ITEM }?.name ?: ctx?.subject?.item?.name ?: ""
        "field" -> source?.field?.def?.name
            ?: (ctx?.vars?.get("field") as? String)?.let { setup.fields[it]?.name ?: it } ?: ""
        else -> null
    }

    /** Executes one action of [actor] (move, switch or pass). */
    fun execute(actor: Battler, action: Action) {
        when (action) {
            is Action.UseMove -> useMove(actor, action.move, action.slot, action.target)
            is Action.Switch -> performSwitch(actor, action.replacement)
            Action.Pass -> log(LogKind.INFO, "${actor.name} is waiting.")
        }
    }

    /**
     * Full move procedure described in README "Moves.json" (beforeMove ... afterMove). A monster locked into a charge
     * move ([Battler.charge]) that uses that move continues it: it charges on, or releases it.
     */
    fun useMove(user: Battler, move: MoveDef, slot: MoveSlot?, chosenTarget: Battler?) {
        if (!user.isActive) return
        moveFailureLogged.clear()
        fieldFailureLogged = false
        fieldSetThisMove = false
        val charge = user.charge?.takeIf { it.move === move }
        val targets = moveTargets(user, move, charge?.target ?: chosenTarget)
        perform(user, move, slot, targets, scale = 1.0, fromEffect = false)
    }

    /**
     * The useMove effect (README "Effects"): [actor] uses [move] right away, outside its turn and without changing its
     * turn or action bar. [slot] null = nothing is paid. [chosen] is the aimed-at monster (null = the move's normal
     * target); [scale] multiplies the move's damage; [message] (already formatted) is logged first. Fails silently when
     * the move has nobody to affect or too many moves are already nested ([MAX_EFFECT_MOVE_DEPTH]). A charge move
     * used this way is released at once.
     */
    fun useMoveFromEffect(actor: Battler, move: MoveDef, slot: MoveSlot?, chosen: Battler?, scale: Double, message: String?) {
        if (!actor.isActive || over() || effectMoveDepth >= MAX_EFFECT_MOVE_DEPTH) return
        val revival = move.target == MoveTarget.FAINTED_ALLY
        val targets = moveTargets(actor, move, chosen)
        if (targets.none { it.isFainted == revival }) return
        message?.let { log(LogKind.INFO, it) }
        // The move in progress (whose trigger used this one) keeps its own failure bookkeeping.
        val logged = moveFailureLogged.toList()
        val fieldFailure = fieldFailureLogged
        val fieldSet = fieldSetThisMove
        val targetCount = moveTargetCount
        effectMoveDepth++
        try {
            moveFailureLogged.clear()
            fieldFailureLogged = false
            fieldSetThisMove = false
            perform(actor, move, slot, targets, if (scale.isNaN()) 1.0 else scale.coerceAtLeast(0.0), fromEffect = true)
        } finally {
            effectMoveDepth--
            moveFailureLogged.clear()
            moveFailureLogged.addAll(logged)
            fieldFailureLogged = fieldFailure
            fieldSetThisMove = fieldSet
            moveTargetCount = targetCount
        }
    }

    /** What one use of a move did to the monsters it targeted (for `attacked`). */
    private class MoveRun {
        /** The targets that heard `targeted`, in that order. */
        val targeted: MutableList<Battler> = ArrayList(2)
        /** HP + shield the move's hits took from each target. */
        val damage: MutableMap<Battler, Int> = IdentityHashMap()
        /** Targets that at least one hit landed on. */
        val landed: MutableSet<Battler> = Collections.newSetFromMap(IdentityHashMap())

        /** Records [target] as targeted; true the first time (it hears `targeted` once per move use). */
        fun target(target: Battler): Boolean = if (targeted.any { it === target }) false else targeted.add(target)
    }

    /**
     * The move procedure on precomputed [targets]: blockMove, beforeMove, charge, cost, targeted / accuracy / hits per
     * target, afterMove, attacked, allyUsedMove / enemyUsedMove. [scale] multiplies the damage; [fromEffect] = used
     * by the useMove effect (no charge).
     */
    private fun perform(user: Battler, move: MoveDef, slot: MoveSlot?, targets: List<Battler>, scale: Double, fromEffect: Boolean) {
        val randomHits = move.target == MoveTarget.RANDOM_ENEMIES
        val primary = if (randomHits) null else targets.firstOrNull()
        // The charge this use continues (its release or one more charging turn), if any.
        val charge = if (fromEffect) null else user.charge?.takeIf { it.move === move }

        // A field's blockMove rule, checked again now that the target is known: the move is not used (no PP spent).
        val blocker = battle.moveBlocker(user, move, primary)
        if (blocker != null) {
            if (charge != null) user.charge = null
            val text = blocker.def.rules.blockMoveMessage ?: "{user} can't use {move} because of {field}!"
            val names = mapOf("user" to user.name, "move" to move.name, "field" to blocker.def.name, "target" to (primary?.name ?: ""))
            log(LogKind.FIELD, format(text, null, null, names))
            return
        }

        val logMark = logCount
        val before = events.emit(EventType.BEFORE_MOVE, user, primary, move, includeMoveTriggers = true, moveUser = user, moveTarget = primary)
        if (before.cancelled) {
            // A cancelled release (e.g. the user is stunned) ends the charge.
            if (charge != null && user.charge === charge) user.charge = null
            val message = before.cancelMessage
            if (before.cancelSource == TriggerSource.Kind.MOVE) {
                // Only the move's own trigger stopped it: the move was used but failed, so PP / cooldown are spent
                // (otherwise a move that keeps failing would never run out of PP and the fallback move never comes).
                if (!user.isActive || over()) return
                log(LogKind.ACTION, "${user.name} used ${move.name}!")
                payCost(slot)
                log(LogKind.MISS, message ?: "But it failed!")
                return
            }
            when {
                message != null -> log(LogKind.INFO, message)
                logCount == logMark -> log(LogKind.INFO, "${user.name} couldn't move!")
            }
            return
        }
        if (!user.isActive || over()) return

        val chargeDef = move.charge
        when {
            charge != null -> {
                if (user.charge !== charge) return
                if (charge.turnsLeft > 0) {
                    charge.turnsLeft--
                    logCharge(user, move, chargeDef, primary)
                    return
                }
                // The release: already paid for when the charge started.
                user.charge = null
                log(LogKind.ACTION, "${user.name} used ${move.name}!")
            }
            !fromEffect && chargeDef != null && !skipsCharge(user, move, chargeDef, primary) -> {
                logCharge(user, move, chargeDef, primary)
                payCost(slot)
                user.charge = ChargeState(move, primary, chargeDef.turns - 1, chargeDef.invulnerable)
                return
            }
            else -> {
                log(LogKind.ACTION, "${user.name} used ${move.name}!")
                payCost(slot)
            }
        }

        // A move aimed at a fainted ally (a revive) affects that fainted monster; every other move only living ones.
        val revival = move.target == MoveTarget.FAINTED_ALLY
        val live = targets.filter { it.isFainted == revival }
        moveTargetCount = if (randomHits) 1 else live.size
        if (live.isEmpty()) {
            log(LogKind.INFO, "But there was no target...")
            return
        }
        val run = MoveRun()
        val damaging = damage.isDamaging(move)
        var completed = true
        if (randomHits) {
            completed = randomStrikes(user, move, scale, run)
        } else {
            for (target in live) {
                if (over() || !user.isActive) {
                    completed = false
                    break
                }
                if (target.isFainted != revival) continue
                if (!revival && target !== user) {
                    // Another monster's move: the target hears `targeted` before the accuracy check.
                    if (!reach(user, move, target, run)) {
                        if (over() || !user.isActive) {
                            completed = false
                            break
                        }
                        continue
                    }
                } else if (!revival && !target.isActive) {
                    continue
                }
                if (!rollsHit(user, target, move)) {
                    log(LogKind.MISS, "${target.name} avoided the attack!")
                    events.emit(EventType.MISSED, user, target, move, includeMoveTriggers = true, moveUser = user, moveTarget = target)
                    continue
                }
                if (damaging) {
                    val effectiveness = damage.effectiveness(move, target)
                    if (effectiveness == 0.0) {
                        log(LogKind.EFFECTIVE, typeMessage(0.0, target) ?: "It doesn't affect ${target.name}...")
                        continue
                    }
                    hitTarget(user, move, target, live.size, scale, run)
                } else {
                    run.landed += target
                    val vars = mapOf(
                        "damage" to 0.0, "isCrit" to 0.0, "effectiveness" to 1.0, "hitType" to "", HIT_INDEX to 1.0, HIT_COUNT to 1.0,
                    )
                    events.emit(EventType.HIT, user, target, move, vars, includeMoveTriggers = true, moveUser = user, moveTarget = target)
                }
            }
        }
        if (over()) return
        if (completed) {
            events.emit(EventType.AFTER_MOVE, user, primary, move, includeMoveTriggers = true, moveUser = user, moveTarget = primary)
        }
        // Every monster this move targeted hears `attacked` (hit, missed or not), then the user's allies and enemies
        // hear that it used a move.
        if (events.heard(EventType.ATTACKED)) for (target in run.targeted) {
            if (over()) return
            if (target.isFainted) continue
            val vars = mapOf("damage" to (run.damage[target] ?: 0).toDouble(), "landed" to if (target in run.landed) 1.0 else 0.0)
            events.emit(EventType.ATTACKED, target, user, move, vars, moveUser = user, moveTarget = target)
        }
        if (!completed || over()) return
        if (events.heard(EventType.ALLY_USED_MOVE)) for (ally in battle.allies(user)) {
            events.emit(EventType.ALLY_USED_MOVE, ally, user, move, moveUser = user, moveTarget = primary)
        }
        if (events.heard(EventType.ENEMY_USED_MOVE)) for (enemy in battle.enemies(user)) {
            events.emit(EventType.ENEMY_USED_MOVE, enemy, user, move, moveUser = user, moveTarget = primary)
        }
    }

    /**
     * [user]'s move reaching [target], another monster: `targeted` (once per move use), then a charging target that
     * cannot be reached makes it miss. False = the move does not go on against [target].
     */
    private fun reach(user: Battler, move: MoveDef, target: Battler, run: MoveRun): Boolean {
        if (run.target(target) && events.heard(EventType.TARGETED)) {
            events.emit(EventType.TARGETED, target, user, move, moveUser = user, moveTarget = target)
            if (over() || !user.isActive || !target.isActive) return false
        }
        if (!target.isActive) return false
        if (target.isInvulnerable) {
            log(LogKind.MISS, "${target.name} can't be reached!")
            events.emit(EventType.MISSED, user, target, move, includeMoveTriggers = true, moveUser = user, moveTarget = target)
            return false
        }
        return true
    }

    /** One accuracy roll of [move] against [target] (no dice when the move never misses or the target is an ally). */
    private fun rollsHit(user: Battler, target: Battler, move: MoveDef): Boolean {
        if (move.accuracy == null || target.side === user.side) return true
        val chance = damage.hitChance(battle, user, target, move)
        return !(chance < 1.0 && random.nextDouble() >= chance)
    }

    /** True if [def]'s `skipIf` is true now: the charge move is used at once. */
    private fun skipsCharge(user: Battler, move: MoveDef, def: ChargeDef, target: Battler?): Boolean =
        def.skipIf?.test(battle.context(user, target, move = move)) == true

    private fun logCharge(user: Battler, move: MoveDef, def: ChargeDef?, target: Battler?) {
        val text = def?.message ?: ChargeDef.DEFAULT_MESSAGE
        log(LogKind.ACTION, format(text, null, null, mapOf("user" to user.name, "move" to move.name, "target" to (target?.name ?: ""))))
    }

    /** Spends one PP and starts the cooldown of the move in [slot] (nothing for the fallback move). */
    private fun payCost(slot: MoveSlot?) {
        if (slot == null) return
        if (rules.usePP && slot.def.pp > 0) slot.pp = (slot.pp - 1).coerceAtLeast(0)
        if (rules.useCooldowns && slot.def.cooldown > 0) slot.cooldown = slot.def.cooldown + 1
    }

    /** What [strike] tells the hit loop. */
    private enum class Strike { NO_DAMAGE, LANDED, STOP, OVER }

    /** All hits of a damaging move on one target (with `hitAccuracy: "each"`, every hit after the first rolls again). */
    private fun hitTarget(user: Battler, move: MoveDef, target: Battler, targetCount: Int, scale: Double, run: MoveRun?) {
        val hits = (move.hits.constant ?: move.hits.eval(battle.context(user, target, move = move))).floorToInt().coerceAtLeast(1)
        var landed = 0
        val shown = BooleanArray(1)
        for (i in 0 until hits) {
            if (!target.isActive || !user.isActive || over()) break
            if (i > 0 && move.hitAccuracy == HitAccuracy.EACH && !rollsHit(user, target, move)) {
                log(LogKind.MISS, "${target.name} avoided the attack!")
                events.emit(EventType.MISSED, user, target, move, includeMoveTriggers = true, moveUser = user, moveTarget = target)
                break
            }
            when (strike(user, move, target, targetCount, i + 1, hits, scale, run, shown, fireHit = true)) {
                Strike.NO_DAMAGE -> {}
                Strike.LANDED -> landed++
                Strike.STOP -> break
                Strike.OVER -> return
            }
        }
        if (landed > 1) log(LogKind.INFO, "Hit $landed times!")
    }

    /**
     * randomEnemies: `hits` hits in all, each on a random active enemy (targeted, reach and accuracy per hit). False
     * when the user stopped before the end (it fainted or left).
     */
    private fun randomStrikes(user: Battler, move: MoveDef, scale: Double, run: MoveRun): Boolean {
        val hits = (move.hits.constant ?: move.hits.eval(battle.context(user, null, move = move))).floorToInt().coerceAtLeast(1)
        var landed = 0
        val shown: MutableMap<Battler, BooleanArray> = IdentityHashMap()
        for (i in 1..hits) {
            if (over()) return true
            if (!user.isActive) return false
            val enemies = battle.enemies(user)
            if (enemies.isEmpty()) break
            val target = if (enemies.size == 1) enemies[0] else enemies[random.nextInt(enemies.size)]
            if (!reach(user, move, target, run)) continue
            if (!rollsHit(user, target, move)) {
                log(LogKind.MISS, "${target.name} avoided the attack!")
                events.emit(EventType.MISSED, user, target, move, includeMoveTriggers = true, moveUser = user, moveTarget = target)
                continue
            }
            val effectiveness = damage.effectiveness(move, target)
            if (damage.isDamaging(move) && effectiveness == 0.0) {
                log(LogKind.EFFECTIVE, typeMessage(0.0, target) ?: "It doesn't affect ${target.name}...")
                continue
            }
            if (!damage.isDamaging(move)) {
                run.landed += target
                val vars = mapOf(
                    "damage" to 0.0, "isCrit" to 0.0, "effectiveness" to 1.0, "hitType" to "",
                    HIT_INDEX to i.toDouble(), HIT_COUNT to hits.toDouble(),
                )
                events.emit(EventType.HIT, user, target, move, vars, includeMoveTriggers = true, moveUser = user, moveTarget = target)
                continue
            }
            when (strike(user, move, target, 1, i, hits, scale, run, shown.getOrPut(target) { BooleanArray(1) }, fireHit = true)) {
                Strike.NO_DAMAGE, Strike.STOP -> {}
                Strike.LANDED -> landed++
                Strike.OVER -> return true
            }
        }
        if (landed > 1) log(LogKind.INFO, "Hit $landed times!")
        return user.isActive
    }

    /**
     * One hit of [move] on [target]: crit, hit type, damage formula (times [scale]), modifyDamageDealt / Taken, the
     * crit / hit type / type lines (the type line once per target: [typeShown]), the damage, then `hit` ([fireHit]).
     * [run] collects what the move did (null for the attack effect).
     */
    private fun strike(
        user: Battler,
        move: MoveDef,
        target: Battler,
        targetCount: Int,
        hitIndex: Int,
        hitCount: Int,
        scale: Double,
        run: MoveRun?,
        typeShown: BooleanArray,
        fireHit: Boolean,
    ): Strike {
        val index = hitIndex.toDouble()
        val count = hitCount.toDouble()
        val r = damage.calculate(battle, user, target, move, targetCount, hitIndex = hitIndex, hitCount = hitCount)
        if (r.power <= 0.0) {
            // The power formula gave 0 for this hit: no damage (README "power"), like a move that deals none.
            if (!fireHit) return Strike.NO_DAMAGE
            run?.landed?.add(target)
            val noDamage = mapOf(
                "damage" to 0.0, "isCrit" to 0.0, "effectiveness" to r.effectiveness, "hitType" to "", HIT_INDEX to index, HIT_COUNT to count,
            )
            events.emit(EventType.HIT, user, target, move, noDamage, includeMoveTriggers = true, moveUser = user, moveTarget = target, power = 0.0)
            return if (over()) Strike.OVER else Strike.NO_DAMAGE
        }
        val base = if (scale == 1.0) r.damage else maxOf((r.damage * scale + ROUNDING_EPSILON).floorToInt(), if (scale > 0.0) rules.minDamage else 0)
        val vars = mapOf(
            "damage" to base.toDouble(),
            "isCrit" to if (r.isCrit) 1.0 else 0.0,
            "effectiveness" to r.effectiveness,
            "hitType" to (r.hitType?.id ?: ""),
            HIT_INDEX to index,
            HIT_COUNT to count,
        )
        val dealt = events.emit(
            EventType.MODIFY_DAMAGE_DEALT, user, target, move, vars, includeMoveTriggers = fireHit,
            moveUser = user, moveTarget = target, power = r.power,
        )
        // The defender sees the damage after the attacker's own modifiers (so "survive at 1 HP" effects work).
        val takenVars = if (dealt.damageMultiplier == 1.0) vars else vars + ("damage" to base * dealt.damageMultiplier)
        val taken = events.emit(
            EventType.MODIFY_DAMAGE_TAKEN, target, user, move, takenVars, moveUser = user, moveTarget = target, power = r.power,
        )
        if (!target.isActive || (fireHit && !user.isActive)) return Strike.STOP
        val multiplier = dealt.damageMultiplier * taken.damageMultiplier
        // The epsilon absorbs rounding in chains of multipliers (e.g. "(self.hp - 1) / damage" must leave 1 HP).
        val amount = if (multiplier > 0.0 && base > 0) maxOf((base * multiplier + ROUNDING_EPSILON).floorToInt(), rules.minDamage) else 0
        // A hit fully cancelled by a modifier (protection, absorbing ability) prints no crit / hit type / type line.
        if (amount > 0) {
            if (r.isCrit) log(LogKind.CRIT, "A critical hit!")
            r.hitType?.message?.let { text ->
                log(LogKind.CRIT, format(text, null, null, mapOf("user" to user.name, "target" to target.name, "move" to move.name)))
            }
            if (!typeShown[0]) typeMessage(r.effectiveness, target)?.let { log(LogKind.EFFECTIVE, it) }
            typeShown[0] = true
        }
        run?.landed?.add(target)
        val done = inflict(target, amount, user, move)
        if (run != null && done.total > 0) run.damage[target] = (run.damage[target] ?: 0) + done.total
        if (over()) return Strike.OVER
        if (!fireHit) return Strike.LANDED
        // `damage` = what the hit really did (shield part + HP lost, not the overkill), like damaged / dealtDamage:
        // drain and recoil effects scale with it.
        val hitVars = mapOf(
            "damage" to done.total.toDouble(), "isCrit" to vars["isCrit"], "effectiveness" to r.effectiveness, "hitType" to vars["hitType"],
            HIT_INDEX to index, HIT_COUNT to count,
        )
        events.emit(EventType.HIT, user, target, move, hitVars, includeMoveTriggers = true, moveUser = user, moveTarget = target, power = r.power)
        return Strike.LANDED
    }

    /**
     * The attack effect (README "Effects"): one strike of [attacker] against [target] with [move] (a typeless or typed
     * damaging stand-in without triggers): type immunity, crit, hit type, damage formula, damage modifiers and the
     * damage. It never misses and fires no `hit`.
     */
    fun attack(attacker: Battler, target: Battler, move: MoveDef) {
        if (!target.isActive || over() || !damage.isDamaging(move)) return
        if (damage.effectiveness(move, target) == 0.0) {
            log(LogKind.EFFECTIVE, typeMessage(0.0, target) ?: "It doesn't affect ${target.name}...")
            return
        }
        strike(attacker, move, target, 1, 1, 1, 1.0, null, BooleanArray(1), fireHit = false)
    }

    /** The monsters [move] affects, retargeting a single-enemy move whose chosen target is gone. */
    private fun moveTargets(user: Battler, move: MoveDef, chosen: Battler?): List<Battler> = when (move.target) {
        MoveTarget.ENEMY -> {
            val enemies = battle.enemies(user)
            when {
                chosen != null && enemies.any { it === chosen } -> listOf(chosen)
                enemies.isEmpty() -> emptyList()
                enemies.size == 1 -> enemies
                else -> listOf(enemies[random.nextInt(enemies.size)])
            }
        }
        MoveTarget.RANDOM_ENEMY -> {
            val enemies = battle.enemies(user)
            when (enemies.size) {
                0 -> emptyList()
                1 -> enemies
                else -> listOf(enemies[random.nextInt(enemies.size)])
            }
        }
        MoveTarget.ALL_ENEMIES -> battle.enemies(user)
        MoveTarget.SELF -> listOf(user)
        MoveTarget.ALLY -> if (chosen != null && battle.allies(user).any { it === chosen }) listOf(chosen) else emptyList()
        MoveTarget.ALLY_OR_SELF ->
            if (chosen != null && chosen !== user && battle.allies(user).any { it === chosen }) listOf(chosen) else listOf(user)
        MoveTarget.ALL_ALLIES -> battle.allies(user)
        MoveTarget.TEAM -> user.side.activeBattlers()
        MoveTarget.ALL -> battle.allActive().filter { it !== user }
        MoveTarget.FAINTED_ALLY -> {
            val fainted = battle.faintedAllies(user)
            when {
                chosen != null && fainted.any { it === chosen } -> listOf(chosen)
                else -> fainted.take(1)
            }
        }
        // Chosen automatically when the move runs (README "Moves.json").
        MoveTarget.LOWEST_HP_ALLY -> listOfNotNull(battle.lowestHp(user.side.activeBattlers()))
        MoveTarget.LOWEST_HP_ENEMY -> listOfNotNull(battle.lowestHp(battle.enemies(user)))
        // Every hit picks its own target (see randomStrikes); the list only says whether there is anybody to hit.
        MoveTarget.RANDOM_ENEMIES -> battle.enemies(user)
    }

    private fun typeMessage(effectiveness: Double, target: Battler): String? =
        setup.types.message(effectiveness)?.let { format(it, null, null, mapOf("target" to target.name)) }

    /**
     * Removes HP from [target] (shield absorbs first), logs it, emits damaged / dealtDamage and handles fainting
     * (fainted, knockedOut, allyFainted, enemyFainted). damaged / dealtDamage also fire when the shield absorbed
     * everything; their `damage` is the shield part plus the HP lost. Returns the HP actually lost.
     */
    fun dealDamage(target: Battler, amount: Int, source: Battler?, move: MoveDef?): Int = inflict(target, amount, source, move).lost

    /** What one application of damage did: [absorbed] by the shield, [lost] HP. */
    private class Inflicted(val absorbed: Int, val lost: Int) {
        val total: Int get() = absorbed + lost
    }

    /** [dealDamage], returning the shield part too. [ignoreShield]: the shield absorbs nothing (setHp). */
    private fun inflict(target: Battler, amount: Int, source: Battler?, move: MoveDef?, ignoreShield: Boolean = false): Inflicted {
        if (amount <= 0 || target.isFainted) return NOTHING_INFLICTED
        val hpBefore = target.hp
        var remaining = amount
        var absorbed = 0
        if (target.shield > 0 && !ignoreShield) {
            absorbed = minOf(target.shield, remaining)
            target.shield -= absorbed
            remaining -= absorbed
            val broke = if (target.shield == 0) " It broke!" else ""
            log(LogKind.DAMAGE, "${target.name}'s shield absorbed $absorbed damage.$broke")
        }
        val lost = minOf(target.hp, remaining.coerceAtLeast(0))
        if (lost > 0) {
            target.hp -= lost
            log(LogKind.DAMAGE, "${target.name} took $lost damage.")
        }
        // A hit that a shield absorbed is still a hit: counters, reflect and contact effects react to it.
        // `damage` = shield part + HP lost (damage beyond the remaining HP is not counted).
        val vars = mapOf("damage" to (absorbed + lost).toDouble())
        val moveUser = if (move != null) source else null
        val moveTarget = if (move != null) target else null
        // `other` is never the subject itself (self-inflicted damage such as burn or recoil has no other monster).
        events.emit(
            EventType.DAMAGED, target, otherThan(source, target), move, mapOf("damage" to (absorbed + lost).toDouble(), "hpBefore" to hpBefore.toDouble()),
            moveUser = moveUser, moveTarget = moveTarget,
        )
        if (source != null && source !== target) {
            events.emit(EventType.DEALT_DAMAGE, source, target, move, vars, moveUser = moveUser, moveTarget = moveTarget)
        }
        if (events.heard(EventType.ALLY_DAMAGED)) for (ally in battle.allies(target)) {
            events.emit(EventType.ALLY_DAMAGED, ally, target, move, vars, moveUser = moveUser, moveTarget = moveTarget)
        }
        if (events.heard(EventType.ENEMY_DAMAGED)) for (enemy in battle.enemies(target)) {
            events.emit(EventType.ENEMY_DAMAGED, enemy, target, move, vars, moveUser = moveUser, moveTarget = moveTarget)
        }
        if (target.isFainted) faint(target, source, move)
        return Inflicted(absorbed, lost)
    }

    /**
     * The setHp effect: sets [target]'s HP to [amount] (clamped to 0..max HP). Lowering it is damage from [source]
     * that no shield absorbs (damaged, dealtDamage..., and it can faint); raising it is healing (healed).
     */
    fun setHp(target: Battler, amount: Int, source: Battler?, move: MoveDef?) {
        if (target.isFainted) return
        val value = amount.coerceIn(0, target.maxHp)
        when {
            value < target.hp -> inflict(target, target.hp - value, source, move, ignoreShield = true)
            value > target.hp -> heal(target, value - target.hp, source)
        }
    }

    /** [fraction] of [b]'s max HP, at least 1 (the monsters whose HP is shared or swapped are alive). */
    private fun hpAt(b: Battler, fraction: Double): Int = (fraction * b.maxHp + ROUNDING_EPSILON).floorToInt().coerceIn(1, b.maxHp.coerceAtLeast(1))

    /** The balanceHp effect: every monster of [group] gets the group's average HP percent (computed first). */
    fun balanceHp(group: List<Battler>, source: Battler?, move: MoveDef?) {
        val members = group.filter { !it.isFainted && it.maxHp > 0 }
        if (members.size < 2 || battle.ended) return
        val average = members.sumOf { it.hpFraction } / members.size
        val names = members.map { it.name }
        log(LogKind.HEAL, names.dropLast(1).joinToString(", ") + " and " + names.last() + " shared their HP!")
        for ((b, hp) in members.map { it to hpAt(it, average) }) {
            if (over()) return
            setHp(b, hp, source, move)
        }
    }

    /** The swapHp effect: [owner] and [target] swap their HP percentages. */
    fun swapHp(owner: Battler, target: Battler) {
        if (owner === target || owner.isFainted || target.isFainted || battle.ended) return
        val ownerHp = hpAt(owner, target.hpFraction)
        val targetHp = hpAt(target, owner.hpFraction)
        log(LogKind.HEAL, "${owner.name} and ${target.name} swapped their HP!")
        setHp(target, targetHp, owner, null)
        if (!over()) setHp(owner, ownerHp, owner, null)
    }

    /** The copyStages effect: [to]'s stat stages become a copy of [from]'s. */
    fun copyStages(from: Battler, to: Battler) {
        if (to.isFainted || from === to) return
        to.stages.clear()
        to.stages.putAll(from.stages)
        log(LogKind.STAT, "${to.name} copied ${from.name}'s stat changes!")
    }

    /**
     * The extraTurn effect (README "Effects"): [b] gets another turn. speed_order: it acts once more at the end of the
     * round's actions (at most once per round; given after them, it waits for the next round's end). action_bar: its
     * bar is full and it acts next, before anyone else (at most one extra turn between two of its normal turns).
     * False when it cannot get one now.
     */
    fun grantExtraTurn(b: Battler): Boolean {
        if (!b.isActive || battle.ended || b.extraTurnPending) return false
        if (rules.turnSystem == TurnSystem.ACTION_BAR) {
            if (b.extraTurnTaken) return false
            b.extraTurnTaken = true
            b.gauge = rules.actionBarSize.coerceAtLeast(0.0)
        } else {
            val round = if (roundOpen) battle.turn else battle.turn + 1
            if (b.extraTurnRound >= round) return false
            b.extraTurnRound = round
        }
        b.extraTurnPending = true
        extraTurns += b
        log(LogKind.INFO, "${b.name} gets another turn!")
        return true
    }

    /** Drops [b]'s waiting extra turn, charge and the delayed effects attached to it (it fainted or left the field). */
    private fun dropTurnState(b: Battler) {
        b.charge = null
        if (b.extraTurnPending) {
            b.extraTurnPending = false
            extraTurns.removeAll { it === b }
        }
        if (delays.isNotEmpty()) delays.removeAll { it.attached === b }
    }

    private fun faint(target: Battler, source: Battler?, move: MoveDef?) {
        if (!faintHandled.add(target)) return
        fainting.add(target)
        try {
            processFaint(target, source, move)
        } finally {
            fainting.remove(target)
        }
        // A revive asked for while the faint was being processed (e.g. by its own fainted trigger) happens now, after
        // the cleanup, so the revived monster starts clean and its revived triggers can give it new statuses.
        val pending = pendingRevives.remove(target)
        if (pending != null && !target.reviveBlocked && !battle.ended) restore(target, pending.hp, pending.source, pending.message)
    }

    private fun processFaint(target: Battler, source: Battler?, move: MoveDef?) {
        target.hp = 0
        log(LogKind.FAINT, "${target.name} fainted!")
        // The fainted monster's own triggers (ability, item and statuses) see its state as it fell; `move` is the move
        // that knocked it out, if any (so a contact-punishing trigger can still react to the knockout hit).
        events.emit(
            EventType.FAINTED, target, otherThan(source, target), move,
            moveUser = if (move != null) source else null,
            moveTarget = if (move != null) target else null,
        )
        target.statuses.clear()
        target.shield = 0
        target.stages.clear()
        target.turnPhase = TurnPhase.CLOSED
        dropTurnState(target)
        if (source != null && source !== target && !source.isFainted) {
            events.emit(
                EventType.KNOCKED_OUT, source, target, move,
                includeMoveTriggers = move != null,
                moveUser = if (move != null) source else null,
                moveTarget = if (move != null) target else null,
            )
        }
        for (ally in battle.allies(target)) events.emit(EventType.ALLY_FAINTED, ally, target)
        for (enemy in battle.enemies(target)) events.emit(EventType.ENEMY_FAINTED, enemy, target)
    }

    /** A revive requested while its target was still fainting (applied when [faint] finishes). */
    private class PendingRevive(val hp: Int, val source: Battler?, val message: String?)

    /**
     * Brings a fainted monster back with [amount] HP (at least 1, at most its max HP): README "Revives".
     * A monster still in its field slot comes back there; one that was already replaced comes back to the bench.
     * Fails (false) if it is not fainted, was blocked by preventRevive, or the battle is over.
     * [message] (already formatted) replaces the default line; blank = say nothing.
     */
    fun revive(target: Battler, amount: Int, source: Battler?, message: String? = null): Boolean {
        if (!target.isFainted || target.reviveBlocked || battle.ended) return false
        val hp = amount.coerceIn(1, target.maxHp.coerceAtLeast(1))
        if (target in fainting) {
            pendingRevives.putIfAbsent(target, PendingRevive(hp, source, message))
            return true
        }
        restore(target, hp, source, message)
        return true
    }

    private fun restore(target: Battler, hp: Int, source: Battler?, message: String?) {
        target.hp = hp
        target.revives++
        target.gauge = 0.0
        target.shield = 0
        target.statuses.clear()
        target.stages.clear()
        // It waits for its next turn: nothing it was about to do this round happens.
        target.turnPhase = TurnPhase.CLOSED
        revivedThisRound.add(target)
        faintHandled.remove(target)
        val where = if (target.slot >= 0) "" else " It returns to the bench."
        log(LogKind.HEAL, message ?: "${target.name} was revived with $hp HP!$where")
        events.emit(EventType.REVIVED, target, otherThan(source, target), vars = mapOf("amount" to hp.toDouble()))
        for (ally in battle.allies(target)) events.emit(EventType.ALLY_REVIVED, ally, target)
        for (enemy in battle.enemies(target)) events.emit(EventType.ENEMY_REVIVED, enemy, target)
    }

    /** The target can no longer be revived in this battle (logs [message], or a default line when it is fainted). */
    fun preventRevive(target: Battler, message: String? = null) {
        if (target.reviveBlocked) return
        target.reviveBlocked = true
        when {
            message != null -> log(LogKind.STATUS, message)
            target.isFainted -> log(LogKind.STATUS, "${target.name} can no longer be revived!")
        }
    }

    /** Restores HP up to max, logs it and emits healed. Returns the HP actually restored. */
    fun heal(target: Battler, amount: Int, source: Battler?): Int {
        if (amount <= 0 || target.isFainted) return 0
        val restored = minOf(amount, target.maxHp - target.hp)
        if (restored <= 0) return 0
        target.hp += restored
        log(LogKind.HEAL, "${target.name} restored $restored HP.")
        events.emit(EventType.HEALED, target, otherThan(source, target), vars = mapOf("amount" to restored.toDouble()))
        return restored
    }

    /**
     * Applies a status, honouring immuneTypes, the single-MAJOR rule and stacking ([stacks] only matters for
     * "stack" statuses; the others always have 1). [duration] null = the status default. Whether it is fresh
     * depends on the target's [Battler.turnPhase]. Logs the apply message and emits statusApplied. Returns false if it failed.
     */
    fun applyStatus(target: Battler, statusId: String, source: Battler?, duration: Int? = null, stacks: Int = 1): Boolean =
        tryApplyStatus(target, statusId, source, duration, stacks) == null

    /**
     * [applyStatus] that returns null on success, or a short user-facing reason why it failed (nothing is logged then).
     * [message] (already formatted) replaces the status's apply message; blank = log nothing.
     * A field's "blockStatus" rule logs its own line (when [fromMove], once per target and move, or when the status
     * would not have been silent) and returns "" (nothing more to say).
     */
    internal fun tryApplyStatus(
        target: Battler,
        statusId: String,
        source: Battler?,
        duration: Int?,
        stacks: Int,
        message: String? = null,
        fromMove: Boolean = false,
        /** True: [duration] is exact, null = permanent (transferStatus keeps the moved status's turns). */
        exactDuration: Boolean = false,
    ): String? {
        val def = setup.statuses[statusId] ?: return "But it failed!"
        if (target.isFainted) return "But it failed!"
        if (def.immuneTypes.any { target.hasType(it) }) {
            return typeMessage(0.0, target) ?: "It doesn't affect ${target.name}..."
        }
        val blocker = battle.statusBlocker(target, def, source)
        if (blocker != null) {
            val silent = if (message != null) message.isBlank() else def.applyMessage?.isBlank() == true
            if (if (fromMove) moveFailureLogged.add(target) else !silent) {
                val text = blocker.def.rules.blockStatusMessage ?: "{self} is protected by {field}!"
                val names = mapOf(
                    "self" to target.name, "target" to target.name, "other" to (source?.takeIf { it !== target }?.name ?: ""),
                    "status" to def.name, "field" to blocker.def.name,
                )
                log(LogKind.FIELD, format(text, null, null, names))
            }
            return ""
        }
        val existing = target.status(def.id)
        if (existing == null && def.kind == StatusKind.MAJOR) {
            target.statuses.firstOrNull { it.def.kind == StatusKind.MAJOR }?.let {
                return "${target.name} is already affected by ${it.def.name}."
            }
        }
        // Only "stack" statuses have stacks (README "Statuses.json"); the others always have exactly 1.
        val newStacks = when {
            def.stacking != Stacking.STACK -> existing?.stacks ?: 1
            existing == null -> minOf(stacks.coerceAtLeast(1), def.maxStacks.coerceAtLeast(1))
            else -> minOf(existing.stacks + stacks.coerceAtLeast(1), def.maxStacks.coerceAtLeast(1)).coerceAtLeast(existing.stacks)
        }
        val turns = if (exactDuration) duration else duration ?: def.duration?.let { formula ->
            val vars = mapOf("stacks" to newStacks.toDouble(), "turnsLeft" to (existing?.turnsLeft ?: 0).toDouble())
            formula.eval(battle.context(target, source, vars = vars)).floorToInt()
        }
        // Fresh = applied during the owner's turn after its action started: it has not affected any of its actions
        // yet, so the countdown at the end of this turn skips it (README "Durations").
        val fresh = target.turnPhase == TurnPhase.ACTED

        if (existing != null) {
            val already = "${target.name} is already affected by ${def.name}."
            when (def.stacking) {
                Stacking.IGNORE -> return already
                Stacking.REFRESH -> if (turns == null && existing.turnsLeft == null) return already
                Stacking.STACK -> if (newStacks == existing.stacks && turns == null && existing.turnsLeft == null) return already
            }
            existing.stacks = newStacks
            existing.turnsLeft = turns
            existing.fresh = fresh
            // Refreshed during its owner's countdown: that countdown must not count the new duration down too.
            if (target === countdownOwner) refreshedInCountdown += existing
        } else {
            target.statuses += StatusInstance(def, turns, newStacks, source, fresh)
        }

        if (message != null) {
            log(LogKind.STATUS, message)
        } else {
            val text = def.applyMessage
                ?: if (newStacks > 1) "{target} is affected by {status} (x{stacks})!" else "{target} is affected by {status}!"
            log(LogKind.STATUS, format(text, null, null, statusPlaceholders(target, def, source, newStacks)))
        }
        events.emit(EventType.STATUS_APPLIED, target, otherThan(source, target), vars = mapOf("status" to def.id))
        if (source != null && source !== target && events.heard(EventType.STATUS_INFLICTED)) {
            events.emit(EventType.STATUS_INFLICTED, source, target, vars = mapOf("status" to def.id))
        }
        return null
    }

    /** [source] as the `other` monster of an event about [subject]: null when absent or when it is the subject itself. */
    private fun otherThan(source: Battler?, subject: Battler): Battler? = source?.takeIf { it !== subject }

    private fun statusPlaceholders(target: Battler, def: StatusDef, source: Battler?, stacks: Int): Map<String, String> = mapOf(
        "target" to target.name,
        "self" to target.name,
        "other" to (source?.name ?: ""),
        "user" to (source?.name ?: target.name),
        "status" to def.name,
        "stacks" to stacks.toString(),
    )

    /** Removes a status instance, logs the remove message and emits statusRemoved. */
    fun removeStatus(target: Battler, instance: StatusInstance) {
        val index = target.statuses.indexOfFirst { it === instance }
        if (index < 0) return
        target.statuses.removeAt(index)
        val text = instance.def.removeMessage ?: "{target} is no longer affected by {status}."
        log(LogKind.STATUS, format(text, null, null, statusPlaceholders(target, instance.def, instance.source, instance.stacks)))
        events.emitStatusRemoved(target, instance)
    }

    /**
     * Changes a stat stage (clamped), logging "rose / sharply rose / fell..." or "won't go any higher/lower".
     * [source] is who causes the change (a field's "blockStatStage" rule reads it as `other`).
     */
    fun changeStage(target: Battler, stat: String, delta: Int, source: Battler? = null) {
        if (delta == 0 || target.isFainted || rules.stageMin > rules.stageMax) return
        val statName = rules.statName(stat)
        val blocker = battle.statStageBlocker(target, stat, delta, source)
        if (blocker != null) {
            val text = blocker.def.rules.blockStatStageMessage ?: "{self} is protected by {field}!"
            val names = mapOf(
                "self" to target.name, "target" to target.name, "other" to (source?.takeIf { it !== target }?.name ?: ""),
                "stat" to statName, "field" to blocker.def.name,
            )
            log(LogKind.FIELD, format(text, null, null, names))
            return
        }
        val current = target.stage(stat)
        val next = (current + delta).coerceIn(rules.stageMin, rules.stageMax)
        if (next == current) {
            log(LogKind.STAT, "${target.name}'s $statName won't go any ${if (delta > 0) "higher" else "lower"}!")
            return
        }
        if (next == 0) target.stages.remove(stat) else target.stages[stat] = next
        val words = when (next - current) {
            1 -> "rose!"
            2 -> "rose sharply!"
            -1 -> "fell!"
            -2 -> "harshly fell!"
            else -> if (next > current) "rose drastically!" else "severely fell!"
        }
        log(LogKind.STAT, "${target.name}'s $statName $words")
    }

    /** Adds [percent] of the bar to the target's gauge (clamped 0..size). No-op in speed_order. */
    fun changeGauge(target: Battler, percent: Double) {
        if (rules.turnSystem != TurnSystem.ACTION_BAR || target.isFainted || percent == 0.0 || percent.isNaN()) return
        val size = rules.actionBarSize.coerceAtLeast(0.0)
        target.gauge = (target.gauge + percent * size / 100.0).coerceIn(0.0, size)
        val text = if (percent > 0) "was filled by ${number(percent)}%" else "was pushed back by ${number(-percent)}%"
        log(LogKind.STAT, "${target.name}'s action bar $text!")
    }

    fun addShield(target: Battler, amount: Int) {
        if (amount <= 0 || target.isFainted) return
        target.shield += amount
        log(LogKind.STAT, "${target.name} gained a shield of $amount!")
    }

    /**
     * Adds to the remaining cooldown of all the target's moves (min 0); `amount` counts the owner's turns (README
     * "Durations and cooldowns"). Once the owner's action has started in its turn ([TurnPhase.ACTED]), the countdown
     * at the end of that turn would eat one turn of a positive amount on a ready move, so that move gets amount + 1
     * (as [payCost] does for the move just used). A move already on cooldown has that countdown in its value already.
     */
    fun changeCooldowns(target: Battler, amount: Int) {
        if (amount == 0 || !rules.useCooldowns || target.isFainted) return
        val countedThisTurn = amount > 0 && target.turnPhase == TurnPhase.ACTED
        for (slot in target.moves) {
            slot.cooldown = if (countedThisTurn && slot.cooldown == 0) amount + 1 else (slot.cooldown + amount).coerceAtLeast(0)
        }
        val text = if (amount > 0) "increased by $amount" else "were reduced by ${-amount}"
        log(LogKind.STAT, "${target.name}'s cooldowns $text!")
    }

    // ---- fields ---------------------------------------------------------------------------------------------------

    /**
     * Sets field [fieldId] (README "Fields.json"): globally for a global field, else on [side] (null = both sides).
     * [setter] is the monster that sets it (null for none); [duration] in turns overrides the field default and
     * [permanent] makes it never expire; [message] replaces the start message (blank = log nothing).
     * Returns true if it was set (or refreshed / stacked) at least once.
     */
    fun setField(
        fieldId: String,
        side: Side? = null,
        setter: Battler? = null,
        duration: Int? = null,
        stacks: Int = 1,
        message: String? = null,
        permanent: Boolean = false,
    ): Boolean {
        val def = setup.fields[fieldId] ?: return false
        val places: List<Side?> = if (def.isGlobal) listOf(null) else if (side != null) listOf(side) else battle.sides
        var any = false
        for (place in places) if (trySetField(def, place, setter, duration, stacks, message, permanent)) any = true
        return any
    }

    /**
     * Sets [def] in one place ([side] null = global). Honours stacking (ignore: fails; refresh: resets the duration;
     * stack: adds layers up to maxStacks and resets the duration) and the exclusive group (the other fields of the
     * group in that place are removed without their end message, but with their fieldRemoved events). Logs the
     * start message ([message] when given) and emits fieldSet for every active monster, fastest first.
     * Returns false if nothing changed.
     *
     * The new field takes the group's place before the replaced fields' fieldRemoved events run, so a trigger that
     * reacts to them by setting another field of the group replaces the new one the normal way (never two fields of a
     * group in one place); the new field's start message and fieldSet are then skipped, as it is already gone.
     */
    internal fun trySetField(
        def: FieldDef,
        side: Side?,
        setter: Battler?,
        duration: Int?,
        stacks: Int,
        message: String?,
        permanent: Boolean,
    ): Boolean {
        val place = if (def.isGlobal) null else side ?: return false
        val existing = battle.field(def.id, place)
        val newStacks = when {
            def.stacking != Stacking.STACK -> 1
            existing == null -> minOf(stacks.coerceAtLeast(1), def.maxStacks)
            else -> minOf(existing.stacks + stacks.coerceAtLeast(1), def.maxStacks).coerceAtLeast(existing.stacks)
        }
        val turns = when {
            permanent -> null
            duration != null -> duration
            else -> def.duration?.let { formula ->
                val vars = mapOf("stacks" to newStacks.toDouble(), "turnsLeft" to (existing?.turnsLeft ?: 0).toDouble())
                formula.eval(battle.context(setter, null, setter, null, vars = vars)).floorToInt()
            }
        }
        if (existing != null) {
            when (def.stacking) {
                Stacking.IGNORE -> return false
                Stacking.REFRESH -> if (turns == null && existing.turnsLeft == null) return false
                Stacking.STACK -> if (newStacks == existing.stacks && turns == null && existing.turnsLeft == null) return false
            }
            existing.stacks = newStacks
            existing.turnsLeft = turns
        } else {
            val group = def.group
            val replaced = if (group == null) emptyList() else battle.activeFields.filter { it.def.group == group && it.side === place }
            battle.activeFields.removeAll { field -> replaced.any { it === field } }
            val instance = FieldInstance(def, place, turns, newStacks, setter)
            battle.activeFields += instance
            fieldSetThisMove = true
            for (other in replaced) announceFieldRemoved(other, "", setter)
            if (battle.activeFields.none { it === instance }) return true
        }
        fieldSetThisMove = true
        val text = message ?: def.startMessage ?: if (place == null) "{field} started." else "{field} started on {side}'s side."
        log(LogKind.FIELD, format(text, null, null, fieldMessagePlaceholders(def, place, setter, newStacks)))
        for (b in bySpeed(battle.allActive())) {
            if (b.isActive) events.emit(EventType.FIELD_SET, b, otherThan(setter, b), vars = mapOf("field" to def.id))
        }
        return true
    }

    /**
     * Ends an active field: logs its end message ([message] when given; blank = log nothing) and emits fieldRemoved
     * for every active monster, fastest first (`other` = [by], the monster that removed it, else the monster that set
     * it while it is still on the field; the field's own triggers hear it too).
     */
    fun removeField(instance: FieldInstance, message: String? = null, by: Battler? = null) {
        val index = battle.activeFields.indexOfFirst { it === instance }
        if (index < 0) return
        battle.activeFields.removeAt(index)
        announceFieldRemoved(instance, message, by)
    }

    /** The end message and fieldRemoved events of [instance], already taken out of the active fields (see [removeField]). */
    private fun announceFieldRemoved(instance: FieldInstance, message: String?, by: Battler?) {
        val def = instance.def
        val text = message ?: def.endMessage ?: if (instance.side == null) "{field} ended." else "{field} ended on {side}'s side."
        log(LogKind.FIELD, format(text, null, null, fieldMessagePlaceholders(def, instance.side, by ?: instance.setter, instance.stacks)))
        val other = by ?: instance.setter?.takeIf { it.isActive }
        for (b in bySpeed(battle.allActive())) {
            if (b.isActive) events.emitFieldRemoved(b, otherThan(other, b), instance)
        }
    }

    private fun fieldMessagePlaceholders(def: FieldDef, side: Side?, user: Battler?, stacks: Int): Map<String, String> = mapOf(
        "field" to def.name,
        "side" to (side?.name ?: ""),
        "user" to (user?.name ?: ""),
        "stacks" to stacks.toString(),
    )

    /** Removes the held item for the rest of the battle. [message] replaces the default line (blank = log nothing). */
    fun consumeItem(target: Battler, message: String? = null) {
        val item = target.item ?: return
        target.item = null
        log(LogKind.ITEM, message ?: "${target.name}'s ${item.name} was used up.")
    }

    /** Puts [incoming] into [slot] of its side (after [outgoing] left, if any): resets, logs, emits switchIn. */
    fun switchIn(incoming: Battler, slot: Int) {
        place(incoming, slot)
        events.emit(EventType.SWITCH_IN, incoming)
    }

    /** Puts [incoming] on the field without emitting anything. */
    private fun place(incoming: Battler, slot: Int) {
        val side = incoming.side
        val previous = side.active[slot]
        if (previous != null && previous !== incoming) previous.slot = -1
        side.active[slot] = incoming
        incoming.slot = slot
        incoming.gauge = 0.0
        incoming.shield = 0
        incoming.stages.clear()
        // Switched in during a speed_order round: it takes no action this round, but its turn ends with the round
        // (a status it gets now is fresh). Otherwise (battle start, replacements) its next turn has not started yet.
        incoming.turnPhase = if (roundOpen) TurnPhase.ACTED else TurnPhase.CLOSED
        log(LogKind.SWITCH, "${side.name} sends out ${incoming.name}!")
    }

    /** The Switch action: switchOut, leave the field (stages and non-major statuses are lost), then switchIn. */
    private fun performSwitch(actor: Battler, replacement: Battler) {
        val side = actor.side
        val slot = actor.slot
        if (slot < 0 || replacement.side !== side || replacement.slot >= 0 || replacement.isFainted) return
        log(LogKind.SWITCH, "${side.name} withdraws ${actor.name}!")
        withdraw(actor)
        if (over() || replacement.isFainted || replacement.slot >= 0) return
        switchIn(replacement, slot)
    }

    /**
     * Takes [b] off the field: switchOut, then it loses its stat stages, non-major statuses, shield, charge, waiting
     * extra turn and the delayed effects attached to it.
     */
    private fun withdraw(b: Battler) {
        val side = b.side
        val slot = b.slot
        events.emit(EventType.SWITCH_OUT, b)
        b.stages.clear()
        b.statuses.removeAll { it.def.kind != StatusKind.MAJOR }
        b.shield = 0
        dropTurnState(b)
        if (slot >= 0 && side.active[slot] === b) side.active[slot] = null
        b.slot = -1
        if (roundOpen) withdrawnThisRound += b
    }

    /**
     * The forceSwitch effect: [target] is replaced right away by a random benched teammate. False when it is not on
     * the field or has nobody to come in.
     */
    fun forceSwitch(target: Battler): Boolean {
        if (!target.isActive || over()) return false
        val options = target.side.bench()
        if (options.isEmpty()) return false
        val replacement = if (options.size == 1) options[0] else options[random.nextInt(options.size)]
        val slot = target.slot
        log(LogKind.SWITCH, "${target.name} was dragged out!")
        withdraw(target)
        if (over() || replacement.isFainted || replacement.slot >= 0 || target.side.active[slot] != null) return true
        switchIn(replacement, slot)
        return true
    }

    /**
     * The switchOut effect (a U-turn): [target] switches out right away and its controller picks who comes in from
     * its bench. False when it is not on the field or its bench is empty.
     */
    fun switchOutNow(target: Battler): Boolean {
        if (!target.isActive || over()) return false
        val side = target.side
        val slot = target.slot
        val options = side.bench()
        if (options.isEmpty()) return false
        val chosen = side.controller.chooseReplacement(battle, side, slot, options)
        val replacement = options.firstOrNull { it === chosen } ?: options.first()
        if (!target.isActive || replacement.slot >= 0 || replacement.isFainted) return false
        log(LogKind.SWITCH, "${side.name} withdraws ${target.name}!")
        withdraw(target)
        if (over() || replacement.isFainted || replacement.slot >= 0 || side.active[slot] != null) return true
        switchIn(replacement, slot)
        return true
    }

    /** Rolls a percent chance with the battle random (no roll for <= 0 or >= 100). */
    internal fun rollPercent(percent: Double): Boolean = when {
        percent.isNaN() || percent <= 0.0 -> false
        percent >= 100.0 -> true
        else -> random.nextDouble() * 100.0 < percent
    }

    private class Planned(
        val actor: Battler,
        val action: Action,
        val isSwitch: Boolean,
        val priority: Int,
        val speed: Double,
        val tieBreak: Double,
    )

    private companion object {
        const val MAX_ITERATIONS = 100_000
        const val MAX_REPLACEMENT_PASSES = 20
        /** At most this many moves used by the useMove effect inside one another (counters of counters...). */
        const val MAX_EFFECT_MOVE_DEPTH = 3
        /** At most this many delayed effects wait at the same time. */
        const val MAX_DELAYS = 1000
        const val HIT_INDEX = "hitIndex"
        const val HIT_COUNT = "hitCount"
        val NOTHING_INFLICTED = Inflicted(0, 0)
        /** Added before rounding damage down, so 48.99999999 (floating-point noise) counts as 49. */
        const val ROUNDING_EPSILON = 1e-7

        /** Switches first (faster first), then priority, then effective speed, then a random tie-break. */
        val PLAN_ORDER: Comparator<Planned> = compareByDescending<Planned> { it.isSwitch }
            .thenByDescending { it.priority }
            .thenByDescending { it.speed }
            .thenBy { it.tieBreak }

        /** [PLAN_ORDER] with the slower first (a global "reverseSpeed" field is active). */
        val REVERSED_PLAN_ORDER: Comparator<Planned> = compareByDescending<Planned> { it.isSwitch }
            .thenByDescending { it.priority }
            .thenBy { it.speed }
            .thenBy { it.tieBreak }

        fun number(value: Any?): String = when (value) {
            null -> ""
            is Double -> if (!value.isInfinite() && !value.isNaN() && value == Math.floor(value)) value.toLong().toString()
                else String.format(java.util.Locale.ROOT, "%.1f", value)
            is Number -> number(value.toDouble())
            else -> value.toString()
        }
    }
}
