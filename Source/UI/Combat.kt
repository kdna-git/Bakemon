package ui

import bakemon.FieldDef
import bakemon.FieldStart
import bakemon.MoveDef
import bakemon.Setup
import bakemon.StatusKind
import bakemon.Team
import bakemon.TurnSystem
import engine.Action
import engine.AiController
import engine.AiLevel
import engine.Battle
import engine.BattleObserver
import engine.BattleResult
import engine.Battler
import engine.Controller
import engine.FieldInstance
import engine.LogEntry
import engine.LogKind
import engine.Manager
import engine.MoveSlot
import engine.Side
import engine.StatusInstance
import kotlin.random.Random

/**
 * The battle screen: choose a mode (you vs AI, 2 players on one keyboard, AI vs AI), choose each side's team
 * (a saved team or a random one), optionally pick starting fields (only when the setup has selectable fields), run the
 * battle with the [engine.Manager], show the result, offer a rematch (same teams and fields).
 */
class Combat(private val setup: Setup, private val seed: Long? = null) {
    private var battlesStarted = 0

    private enum class Mode(val label: String) {
        HUMAN_VS_AI("You vs AI"),
        PLAYER_VS_PLAYER("Player vs Player (same keyboard)"),
        AI_VS_AI("AI vs AI (watch)"),
    }

    /** Everything about a battle except the teams and the seed. */
    private class Plan(val sideNames: List<String>, val controllers: List<Controller>, val observer: BattleObserver)

    /** Interactive entry point from the main menu. Returns when the user goes back. */
    fun run() {
        while (true) {
            Terminal.header("Battle - ${setup.displayName}")
            val modeIndex = Terminal.menu("Choose a mode", Mode.entries.map { it.label })
            if (modeIndex < 0) return
            val plan = plan(Mode.entries[modeIndex]) ?: continue
            var battleSeed = nextSeed()
            val teams = chooseTeams(plan.sideNames, Random(battleSeed)) ?: continue
            val fields = chooseStartFields(plan.sideNames) ?: continue
            while (true) {
                fight(plan, teams, fields, battleSeed)
                when (Terminal.menu("What now?", listOf("Rematch with the same teams", "New battle"), "Back to the main menu")) {
                    0 -> battleSeed = nextSeed()
                    1 -> break
                    else -> return
                }
            }
        }
    }

    private fun nextSeed(): Long {
        val next = seed?.let { it + battlesStarted } ?: Random.nextLong(1, 1_000_000_000)
        battlesStarted++
        return next
    }

    private fun plan(mode: Mode): Plan? = when (mode) {
        Mode.HUMAN_VS_AI -> {
            val level = askAiLevel("AI level")
            // The side is "Player", not "You": the engine writes messages like "<side> sends out X!".
            level?.let { Plan(listOf("Player", "AI"), listOf(HumanController("You"), AiController("AI", it)), TerminalObserver()) }
        }
        Mode.PLAYER_VS_PLAYER -> {
            val first = Terminal.ask("Name of player 1", "Player 1").ifBlank { "Player 1" }
            var second = Terminal.ask("Name of player 2", "Player 2").ifBlank { "Player 2" }
            if (second.equals(first, ignoreCase = true)) second = "$second (2)"
            Plan(listOf(first, second), listOf(HumanController(first), HumanController(second)), TerminalObserver())
        }
        Mode.AI_VS_AI -> planAiVsAi()
    }

    private fun planAiVsAi(): Plan? {
        val first = askAiLevel("Level of AI 1") ?: return null
        val second = askAiLevel("Level of AI 2") ?: return null
        val speed = Terminal.menu("Watch speed", listOf("Watch (short pause after each line)", "Instant"))
        if (speed < 0) return null
        val names = listOf("AI 1", "AI 2")
        val controllers = listOf(AiController(names[0], first), AiController(names[1], second))
        return Plan(names, controllers, TerminalObserver(delayMs = if (speed == 0) WATCH_DELAY_MS else 0, showField = true))
    }

    private fun askAiLevel(title: String): AiLevel? {
        val labels = listOf(
            "Easy   - random moves",
            "Normal - picks strong moves",
            "Hard   - also switches out of bad matchups and plays support moves better",
        )
        val index = Terminal.menu(title, labels)
        return if (index < 0) null else AiLevel.entries[index]
    }

    private fun chooseTeams(sideNames: List<String>, random: Random): List<Team>? =
        sideNames.map { chooseTeam(it, random) ?: return null }

    /** A saved team the user could pick, or why it can't be picked. */
    private class Candidate(val file: String, val team: Team?, val problem: String?)

    private fun chooseTeam(sideName: String, random: Random): Team? {
        val candidates = setup.listTeams().map { file ->
            try {
                val team = setup.loadTeam(file)
                Candidate(file, team, team.validate(setup).firstOrNull())
            } catch (e: Exception) {
                Candidate(file, null, e.message ?: "cannot be read")
            }
        }
        val valid = candidates.filter { it.team != null && it.problem == null }
        val invalid = candidates - valid.toSet()
        Terminal.header("Team for $sideName")
        if (invalid.isNotEmpty()) {
            println(Terminal.dim("Not usable (fix them in the Team creator):"))
            invalid.forEach { println(Terminal.dim("  - ${it.team?.name ?: it.file}: ${it.problem}")) }
        }
        val options = listOf("Random team") + valid.map { teamLabel(it.team!!) }
        val index = Terminal.menu("Choose a team", options)
        return when {
            index < 0 -> null
            index == 0 -> setup.randomTeam(random, name = "Random team").also {
                println("Random team for $sideName: " + it.members.joinToString(", ") { m -> "${m.displayName(setup)} Lv${m.level}" })
            }
            else -> valid[index - 1].team
        }
    }

    private fun teamLabel(team: Team): String {
        val names = team.members.map { it.displayName(setup) }
        var shown = names
        while (shown.size > 1 && shown.joinToString(", ").length > 60) shown = shown.dropLast(1)
        val members = shown.joinToString(", ") + if (shown.size < names.size) ", ..." else ""
        return "${team.name}  " + Terminal.dim("($members)")
    }

    /**
     * Starting fields picked by the user (README "Fields.json"): one choice per exclusive group, then a yes/no for
     * every selectable field without a group; side fields also ask for the side. Fields picked here have no duration,
     * but moves and abilities can still replace or remove them. Returns an empty list when the setup has no
     * selectable field (no menu), null when the user goes back.
     */
    private fun chooseStartFields(sideNames: List<String>): List<FieldStart>? {
        val selectable = setup.fields.selectable()
        if (selectable.isEmpty()) return emptyList()
        Terminal.header("Starting fields")
        val always = setup.rules.startFields.mapNotNull { describeStart(it, sideNames) }
        if (always.isNotEmpty()) println("Set at the start of every battle: " + always.joinToString(", "))
        println(Terminal.dim("Fields picked here do not expire on their own (moves and abilities can still replace or remove them)."))
        when (Terminal.menu("Start with extra fields?", listOf("No extra fields", "Choose starting fields"))) {
            -1 -> return null
            0 -> return emptyList()
        }
        val alwaysIds = setup.rules.startFields.map { it.field }.toSet()
        fun option(field: FieldDef): String {
            val tags = listOfNotNull(
                if (field.isGlobal) null else "one side",
                if (field.id in alwaysIds) "set at start" else null,
            )
            return field.name + (if (tags.isEmpty()) "" else Terminal.dim(" (${tags.joinToString(", ")})")) +
                (if (field.description.isBlank()) "" else Terminal.dim(" - ${field.description}"))
        }
        val picks = mutableListOf<FieldStart>()
        selectable.filter { it.group != null }.groupBy { it.group!! }.forEach { (group, fields) ->
            println()
            val index = Terminal.menu(groupTitle(group), fields.map(::option), backLabel = "None")
            if (index >= 0) picks += startFor(fields[index], sideNames)
        }
        selectable.filter { it.group == null }.forEach { field ->
            println()
            println(option(field))
            if (Terminal.confirm("Start with ${field.name}?")) picks += startFor(field, sideNames)
        }
        val chosen = picks.mapNotNull { describeStart(it, sideNames) }
        println()
        println("Starting fields: " + chosen.ifEmpty { listOf("none") }.joinToString(", "))
        return picks
    }

    /** "weather" -> "Weather", "entry_hazards" -> "Entry hazards". */
    private fun groupTitle(group: String): String = group.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    /** A start for [field]; a side field asks which side it covers. */
    private fun startFor(field: FieldDef, sideNames: List<String>): FieldStart {
        if (field.isGlobal) return FieldStart(field.id)
        val index = Terminal.menu("${field.name} on which side?", sideNames + "Both sides", backLabel = null)
        return FieldStart(field.id, side = index.takeIf { it < sideNames.size })
    }

    /** "Rain", "Reflect (Player)", "Spikes (both sides, 5 turns)"; null for an unknown field id. */
    private fun describeStart(start: FieldStart, sideNames: List<String>): String? {
        val def = setup.fields[start.field] ?: return null
        val notes = buildList {
            if (!def.isGlobal) add(start.side?.let { sideNames.getOrNull(it) } ?: "both sides")
            val duration = if (start.permanent) null else start.duration ?: def.duration
            duration?.let { d -> add(d.constant?.let { "${Describe.num(it)} turns" } ?: "duration ${d.source}") }
        }
        return def.name + if (notes.isEmpty()) "" else " (${notes.joinToString(", ")})"
    }

    private fun fight(plan: Plan, teams: List<Team>, fields: List<FieldStart>, battleSeed: Long) {
        println()
        println(Terminal.dim("Battle seed: $battleSeed"))
        try {
            Manager(setup, teams, plan.controllers, plan.observer, battleSeed, plan.sideNames, fields).run()
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is Terminal.InputClosed }) throw e
            Terminal.error("The battle stopped because of an internal error: ${e.message ?: e::class.simpleName}")
        } catch (e: StackOverflowError) {
            Terminal.error("The battle stopped because of an internal error (stack overflow).")
        }
    }

    private companion object {
        const val WATCH_DELAY_MS = 300L
    }
}

/**
 * A human player choosing actions in the terminal. Shows the field (both sides' active monsters with HP bars,
 * statuses, stat stages; action_bar: gauges and the predicted turn order), then a menu:
 * moves (type, category, power, accuracy, PP / cooldown, effectiveness hint against the current target),
 * switch (if allowed), info (inspect any monster: stats, ability, item, moves).
 * Unusable moves (no PP, on cooldown, blocked by a field, or without any legal target) are shown with the reason but
 * cannot be picked; info also lists the active fields with their descriptions. When nothing
 * else is left the fallback move (or Wait) is offered. Asks for a target when the move needsChoice and there is
 * more than one option. A monster locked into a charge move is never asked (the engine releases the move itself).
 */
class HumanController(override val name: String) : Controller {

    /** One line of the action menu. */
    private sealed class Choice {
        class Move(val slot: MoveSlot, val usable: Boolean) : Choice()
        class Fallback(val move: MoveDef) : Choice()
        data object Wait : Choice()
        data object Switch : Choice()
        data object Info : Choice()
    }

    /** A picked target; [target] may be null when the move does not need one. */
    private class Picked(val target: Battler?)

    override fun chooseAction(battle: Battle, actor: Battler): Action {
        var showField = true
        while (true) {
            if (showField) {
                banner(battle, "what will ${actor.name} do?")
                println(FieldView.render(battle, actor))
                showField = false
            }
            when (val choice = actionMenu(battle, actor)) {
                is Choice.Move -> {
                    if (!choice.usable) {
                        Terminal.warn("${choice.slot.def.name} can't be used now (${disabledReason(battle, actor, choice.slot)}).")
                        continue
                    }
                    val picked = pickTarget(battle, actor, choice.slot.def) ?: continue
                    return Action.UseMove(choice.slot.def, choice.slot, picked.target)
                }
                is Choice.Fallback -> {
                    val picked = pickTarget(battle, actor, choice.move) ?: continue
                    return Action.UseMove(choice.move, null, picked.target)
                }
                Choice.Wait -> return Action.Pass
                Choice.Switch -> pickSwitch(battle, actor)?.let { return Action.Switch(it) }
                Choice.Info -> {
                    showInfo(battle, actor)
                    showField = true
                }
            }
        }
    }

    override fun chooseReplacement(battle: Battle, side: Side, slot: Int, options: List<Battler>): Battler {
        val fainted = side.active.getOrNull(slot)
        if (options.size == 1) return options[0]
        banner(battle, "choose who replaces ${fainted?.name ?: "slot ${slot + 1}"}")
        println(FieldView.render(battle))
        val rows = Terminal.table(options.map { FieldView.summaryCells(battle, it) })
        return options[Terminal.menu("Send out", rows, backLabel = null)]
    }

    private fun banner(battle: Battle, question: String) {
        println()
        println(Terminal.bold(Terminal.color(">> $name - turn ${battle.turn}: $question", "brightCyan")))
    }

    private fun actionMenu(battle: Battle, actor: Battler): Choice {
        // Like the Manager: a usable move without any legal target (an ally move with no ally) cannot be picked.
        val usable = battle.usableMoves(actor).filter { battle.hasTarget(actor, it.def) }
        val choices = mutableListOf<Choice>()
        val labels = mutableListOf<String>()
        if (usable.isEmpty()) {
            val fallback = battle.fallbackMove()
            if (fallback != null) {
                choices += Choice.Fallback(fallback)
                labels += "Use ${fallback.name} " + Terminal.dim("(no usable moves)")
            } else {
                choices += Choice.Wait
                labels += "Wait " + Terminal.dim("(no usable moves)")
            }
        }
        val enemies = battle.enemies(actor)
        val header = Describe.moveHeader(battle.setup).dropLast(counterColumns(battle)) + runtimeHeader(battle, enemies)
        val rows = actor.moves.map { slot -> moveRow(battle, actor, slot, slot in usable, enemies) }
        val table = Terminal.table(listOf(header) + rows, right = Describe.moveNumberColumns(battle.setup))
        choices += actor.moves.map { Choice.Move(it, it in usable) }
        labels += table.drop(1)
        if (battle.canSwitch(actor)) {
            choices += Choice.Switch
            labels += "Switch"
        }
        choices += Choice.Info
        labels += "Info"
        println()
        if (actor.moves.isNotEmpty()) {
            println(Terminal.menuIndent(labels.size) + Terminal.dim(table.first()))
        }
        return choices[Terminal.menu("", labels, backLabel = null)]
    }

    /** moveHeader includes PP / Cd as max values; the battle menu replaces them with runtime columns. */
    private fun counterColumns(battle: Battle): Int =
        (if (battle.rules.usePP) 1 else 0) + (if (battle.rules.useCooldowns) 1 else 0)

    private fun runtimeHeader(battle: Battle, enemies: List<Battler>): List<String> = buildList {
        if (battle.rules.usePP) add("PP")
        if (battle.rules.useCooldowns) add("Cd")
        add(if (enemies.size == 1) "vs ${enemies[0].name}" else "vs foes")
    }

    private fun moveRow(battle: Battle, actor: Battler, slot: MoveSlot, usable: Boolean, enemies: List<Battler>): List<String> {
        val move = slot.def
        val base = Describe.moveCells(battle.setup, move).dropLast(counterColumns(battle))
        val cells = base.toMutableList()
        if (battle.rules.usePP) cells += if (move.pp <= 0) "-" else "${slot.pp}/${move.pp}"
        if (battle.rules.useCooldowns) cells += when {
            slot.cooldown > 0 -> "wait ${slot.cooldown}"
            move.cooldown > 0 -> "(${move.cooldown})"
            else -> "-"
        }
        cells += effectivenessHint(battle, move, enemies)
        if (!usable) {
            cells[0] = Terminal.dim(cells[0])
            cells += Terminal.color("[${disabledReason(battle, actor, slot)}]", "gray")
        }
        return cells
    }

    private fun effectivenessHint(battle: Battle, move: MoveDef, enemies: List<Battler>): String {
        if (!move.target.offensive || !battle.setup.isDamaging(move) || enemies.isEmpty()) return ""
        val hints = enemies.map { Describe.effectivenessHint(battle.setup.types.multiplier(move.type, it.types)) }
        return if (enemies.size == 1) hints[0] else hints.joinToString("/")
    }

    private fun disabledReason(battle: Battle, actor: Battler, slot: MoveSlot): String = when {
        battle.rules.usePP && slot.def.pp > 0 && slot.pp <= 0 -> "no PP"
        battle.rules.useCooldowns && slot.cooldown > 0 -> "cooldown ${slot.cooldown}"
        else -> battle.moveBlocker(actor, slot.def, null)?.let { "blocked by ${it.def.name}" }
            ?: if (!battle.hasTarget(actor, slot.def)) "no target" else "unusable"
    }

    /** Null = the user went back. */
    private fun pickTarget(battle: Battle, actor: Battler, move: MoveDef): Picked? {
        if (!move.target.needsChoice) return Picked(null)
        val options = battle.targetOptions(actor, move)
        if (options.size <= 1) return Picked(options.firstOrNull())
        val rows = Terminal.table(options.map { target ->
            val cells = FieldView.summaryCells(battle, target).toMutableList()
            cells += if (target.side === actor.side) Terminal.dim("ally") else Terminal.dim("enemy")
            if (target.side !== actor.side && battle.setup.isDamaging(move)) {
                cells += Describe.effectivenessHint(battle.setup.types.multiplier(move.type, target.types))
            }
            cells
        })
        val index = Terminal.menu("Target for ${move.name}", rows)
        return if (index < 0) null else Picked(options[index])
    }

    private fun pickSwitch(battle: Battle, actor: Battler): Battler? {
        val options = battle.switchOptions(actor)
        if (options.isEmpty()) {
            Terminal.warn("Nobody can switch in.")
            return null
        }
        val rows = Terminal.table(options.map { FieldView.summaryCells(battle, it) })
        val index = Terminal.menu("Switch ${actor.name} for", rows)
        return if (index < 0) null else options[index]
    }

    private fun showInfo(battle: Battle, actor: Battler) {
        val monsters = actor.side.team + battle.enemies(actor)
        FieldView.fieldDetails(battle).takeIf { it.isNotEmpty() }?.let {
            println()
            println(it)
        }
        while (true) {
            val rows = Terminal.table(monsters.map { b ->
                listOf(b.baseName, Terminal.dim(b.side.name)) + FieldView.summaryCells(battle, b).drop(1)
            })
            val index = Terminal.menu("Inspect which monster?", rows)
            if (index < 0) return
            println()
            println(FieldView.details(battle, monsters[index]))
            Terminal.pause()
        }
    }
}

/**
 * Prints the battle log with colours by kind. [delayMs] > 0 pauses after each line (used for AI vs AI
 * so the user can follow). In AI-vs-AI mode, [showField] prints the field at the start of every turn.
 */
class TerminalObserver(private val delayMs: Long = 0, private val showField: Boolean = false) : BattleObserver {
    /** Text of the RESULT log lines of the current battle (to avoid repeating the reason in the final banner). */
    private val resultLog = StringBuilder()

    override fun onLog(entry: LogEntry) {
        if (entry.text.isBlank()) return
        if (entry.kind == LogKind.RESULT) resultLog.append(entry.text).append('\n')
        if (entry.kind == LogKind.TURN) println()
        entry.text.split('\n').forEach { println(indent(entry.kind) + style(entry.kind, it)) }
        sleep()
    }

    override fun onTurnStart(battle: Battle, actor: Battler?) {
        if (!showField) return
        println()
        println(FieldView.render(battle, actor))
        sleep()
    }

    override fun onBattleEnd(battle: Battle, result: BattleResult) {
        println()
        println(FieldView.render(battle))
        val winner = result.winner?.let { battle.sides.getOrNull(it)?.name ?: "Side ${it + 1}" }
        val headline = if (winner != null) "Winner: $winner" else "Draw"
        val turns = if (result.turns == 1) "1 turn" else "${result.turns} turns"
        val line = "=".repeat(48)
        println(Terminal.bold(line))
        println(Terminal.bold(Terminal.color("  $headline  ($turns)", if (winner != null) "brightGreen" else "yellow")))
        if (result.reason.isNotBlank() && !resultLog.contains(result.reason)) println("  ${result.reason}")
        println(Terminal.bold(line))
        resultLog.clear()
    }

    private fun indent(kind: LogKind): String = when (kind) {
        LogKind.TURN -> ""
        LogKind.ACTION, LogKind.SWITCH, LogKind.INFO, LogKind.FAINT, LogKind.RESULT -> "  "
        else -> "    "
    }

    private fun style(kind: LogKind, text: String): String = when (kind) {
        LogKind.TURN -> Terminal.bold(Terminal.color(text, "cyan"))
        LogKind.ACTION -> Terminal.bold(text)
        LogKind.DAMAGE -> Terminal.color(text, "red")
        LogKind.HEAL -> Terminal.color(text, "green")
        LogKind.STATUS -> Terminal.color(text, "magenta")
        LogKind.STAT -> Terminal.color(text, "cyan")
        LogKind.ABILITY -> Terminal.color(text, "brightBlue")
        LogKind.ITEM -> Terminal.color(text, "brightMagenta")
        LogKind.MISS -> Terminal.color(text, "gray")
        LogKind.EFFECTIVE -> Terminal.color(text, "brightYellow")
        LogKind.CRIT -> Terminal.bold(Terminal.color(text, "brightYellow"))
        LogKind.FAINT -> Terminal.bold(Terminal.color(text, "brightRed"))
        LogKind.SWITCH -> Terminal.color(text, "blue")
        LogKind.RESULT -> Terminal.bold(Terminal.color(text, "brightGreen"))
        LogKind.FIELD -> Terminal.color(text, FieldView.FIELD_COLOR)
        LogKind.INFO -> text
    }

    private fun sleep() {
        if (delayMs <= 0) return
        try {
            Thread.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/** Shared field rendering used by HumanController and TerminalObserver. */
object FieldView {
    private const val RULE_WIDTH = 72

    /** Colour of field tags and of LogKind.FIELD log lines. */
    internal const val FIELD_COLOR = "brightCyan"

    /**
     * One line per active monster per side: name, level, types, HP bar + numbers, short status tags, shield, gauge.
     * Active fields: "Field: Rain (3) | Grassy Terrain (2)" for the global ones, and each side's fields next to its name.
     */
    fun render(battle: Battle, highlight: Battler? = null): String {
        val lines = mutableListOf<String>()
        val rule = Terminal.dim("-".repeat(RULE_WIDTH))
        val onField = battle.sides.flatMap { side -> side.active.filterNotNull() }
        val layout = Layout(
            nameWidth = (onField.maxOfOrNull { it.name.length } ?: 8).coerceIn(8, 24),
            hpWidth = onField.maxOfOrNull { "${it.maxHp}/${it.maxHp}".length } ?: 7,
        )
        lines += rule
        globalFieldsLine(battle)?.let { lines += it }
        // The second side is drawn on top, the first at the bottom (like "you" in Classic).
        battle.sides.reversed().forEachIndexed { i, side ->
            if (i > 0) lines += ""
            lines += sideHeader(battle, side)
            side.active.filterNotNull().forEach { lines += monsterLines(battle, it, it === highlight, layout) }
        }
        lines += rule
        val over = battle.ended || battle.sides.any { it.isDefeated() }
        if (battle.rules.turnSystem == TurnSystem.ACTION_BAR && !over) lines += previewLines(battle)
        return lines.joinToString("\n")
    }

    /** Column widths shared by every monster line of one render. */
    private class Layout(val nameWidth: Int, val hpWidth: Int)

    /** Detailed sheet of one battler: stats (effective and raw), stages, ability, item, fields covering it, statuses, moves. */
    fun details(battle: Battle, b: Battler): String {
        val setup = battle.setup
        val lines = mutableListOf<String>()
        val title = if (b.baseName != b.species.name) "${b.baseName} (${b.species.name})" else b.baseName
        lines += Terminal.bold("== $title - ${b.side.name} ==")
        val state = if (b.isFainted) Terminal.color("fainted", "red") else if (b.slot >= 0) "on the field" else "on the bench"
        lines += "Lv${b.level}  ${Describe.types(setup, b.types)}  HP ${Terminal.hpBar(b.hp, b.maxHp)} ${b.hp}/${b.maxHp}  ($state)"
        val extras = buildList {
            if (b.shield > 0) add("Shield ${b.shield}")
            if (battle.rules.turnSystem == TurnSystem.ACTION_BAR) add("Gauge ${Describe.num(b.gaugePercent().toInt().toDouble())}%")
            chargeTag(b)?.let { add(it) }
            if (b.extraTurnPending) add("extra turn coming")
        }
        if (extras.isNotEmpty()) lines += extras.joinToString("  ")
        // A field rule ("disableAbilities", "disableItems") may switch them off for now.
        val off = Terminal.color(" (no effect now)", "red")
        lines += "Ability: " + (b.ability?.let { "${it.name} - ${it.description}" + if (b.activeAbility == null) off else "" } ?: "none")
        if (battle.rules.heldItems) {
            lines += "Item: " + (b.item?.let { "${it.name} - ${it.description}" + if (b.activeItem == null) off else "" } ?: "none")
        }
        val covering = battle.fieldsCovering(b)
        if (covering.isNotEmpty()) lines += "Fields: " + covering.joinToString(", ") { Terminal.color(fieldTag(it), FIELD_COLOR) }
        lines += "Statuses: " + if (b.statuses.isEmpty()) "none" else ""
        b.statuses.forEach { lines += "  " + statusTag(it) + " " + it.def.name + " - " + it.def.description }
        lines += ""
        lines += statLines(battle, b)
        lines += ""
        lines += moveLines(battle, b)
        return lines.joinToString("\n")
    }

    /** Cells for lists of battlers: name, level, types, HP bar, HP numbers, status tags. */
    internal fun summaryCells(battle: Battle, b: Battler): List<String> = listOf(
        b.name,
        "Lv${b.level}",
        Describe.types(battle.setup, b.types),
        Terminal.hpBar(b.hp, b.maxHp, 10),
        "${b.hp}/${b.maxHp}",
        if (b.isFainted) Terminal.color("fainted", "red") else b.statuses.joinToString(" ") { statusTag(it) },
    )

    /** "BRN", "PSN x2", "SLP(3)": short name, stacks when > 1, remaining turns when limited. Coloured by kind. */
    internal fun statusTag(status: StatusInstance): String {
        val stacks = if (status.stacks > 1) " x${status.stacks}" else ""
        val turns = status.turnsLeft?.let { "($it)" } ?: ""
        val tone = when (status.def.kind) {
            StatusKind.MAJOR -> "magenta"
            StatusKind.VOLATILE -> "yellow"
            StatusKind.BUFF -> "green"
            StatusKind.DEBUFF -> "red"
        }
        return Terminal.color(status.def.short + stacks + turns, tone)
    }

    /** "Rain (3)", "Spikes x2", "Reflect (4)": name, layers when more than one, turns left when limited. */
    internal fun fieldTag(field: FieldInstance): String =
        field.def.name + (if (field.stacks > 1) " x${field.stacks}" else "") + (field.turnsLeft?.let { " ($it)" } ?: "")

    /** "Field: Rain (3) | Grassy Terrain (2)", or null when no global field is active. */
    internal fun globalFieldsLine(battle: Battle): String? {
        val global = battle.fields.filter { it.side == null }
        if (global.isEmpty()) return null
        return Terminal.dim("Field: ") + global.joinToString(Terminal.dim(" | ")) { Terminal.color(fieldTag(it), FIELD_COLOR) }
    }

    /**
     * Active fields with their place, turns left and description, one entry per field (empty when there are none).
     * Shown by the info screen.
     */
    fun fieldDetails(battle: Battle): String {
        if (battle.fields.isEmpty()) return ""
        val lines = mutableListOf(Terminal.bold("Active fields:"))
        battle.fields.forEach { f ->
            val notes = listOfNotNull(
                f.side?.name ?: "whole battle",
                if (f.stacks > 1) "${f.stacks} layers" else null,
                f.turnsLeft?.let { if (it == 1) "1 turn left" else "$it turns left" } ?: "until removed",
            )
            lines += "  " + Terminal.color(f.def.name, FIELD_COLOR) + Terminal.dim(" (${notes.joinToString(", ")})")
            if (f.def.description.isNotBlank()) lines += Terminal.dim(Terminal.wrap(f.def.description, 76, "      "))
        }
        return lines.joinToString("\n")
    }

    /** Side name, its fields ("Reflect (4), Spikes x2") and, when there is a reserve, the bench markers on the right. */
    private fun sideHeader(battle: Battle, side: Side): String {
        val sideFields = battle.fields.filter { it.side === side }
        val name = Terminal.bold(side.name) +
            if (sideFields.isEmpty()) "" else "  " + sideFields.joinToString(", ") { Terminal.color(fieldTag(it), FIELD_COLOR) }
        val reserve = side.team.size - side.active.size
        if (reserve <= 0 && side.team.none { it.isFainted }) return name
        val bench = side.bench().size
        val fainted = side.team.count { it.isFainted }
        val markers = (List(bench) { "o" } + List(fainted) { Terminal.dim("x") }).joinToString(" ")
        val label = Terminal.dim("bench ") + markers.ifEmpty { "-" }
        // Many side fields: the bench markers go on their own line instead of being cut off.
        val room = RULE_WIDTH - Terminal.visibleLength(label)
        if (Terminal.visibleLength(name) + 2 > room) return name + "\n" + " ".repeat(room) + label
        return Terminal.pad(name, room) + label
    }

    private fun monsterLines(battle: Battle, b: Battler, highlighted: Boolean, layout: Layout): List<String> {
        val nameWidth = layout.nameWidth
        val marker = if (highlighted) Terminal.bold(Terminal.color(">", "brightCyan")) else " "
        val name = if (highlighted) Terminal.bold(b.name) else b.name
        if (b.isFainted) {
            return listOf(" $marker ${Terminal.pad(name, nameWidth)}  " + Terminal.color("fainted", "red"))
        }
        val first = " $marker ${Terminal.pad(name, nameWidth)}  ${Terminal.pad("Lv${b.level}", 5)} " +
            Terminal.pad(Describe.types(battle.setup, b.types), 15) + " HP " + Terminal.hpBar(b.hp, b.maxHp) +
            " " + "${b.hp}/${b.maxHp}".padStart(layout.hpWidth)
        val tags = buildList {
            if (battle.rules.turnSystem == TurnSystem.ACTION_BAR) {
                val percent = b.gaugePercent().coerceIn(0.0, 100.0)
                add(Terminal.gaugeBar(percent / 100) + " " + "${percent.toInt()}%".padStart(4))
            }
            b.statuses.forEach { add(statusTag(it)) }
            battle.rules.stats.forEach { stat ->
                val stage = b.stage(stat.id)
                if (stage != 0) {
                    val text = Describe.statShort(stat.id) + (if (stage > 0) "+$stage" else "$stage")
                    add(Terminal.color(text, if (stage > 0) "green" else "red"))
                }
            }
            if (b.shield > 0) add(Terminal.color("Shield ${b.shield}", "cyan"))
            chargeTag(b)?.let { add(Terminal.color(it, "brightYellow")) }
            if (b.extraTurnPending) add(Terminal.color("extra turn", "brightGreen"))
        }
        if (tags.isEmpty()) return listOf(first)
        return listOf(first, " ".repeat(nameWidth + 5) + tags.joinToString("  "))
    }

    /** "Charging Solar Beam", "Charging Fly (out of reach)", or null when [b] is not charging a move. */
    internal fun chargeTag(b: Battler): String? = b.charge?.let { "Charging ${it.move.name}" + if (it.invulnerable) " (out of reach)" else "" }

    /** "Next: A (side) > B (side) > ...", wrapped between entries. Second-side entries are coloured. */
    private fun previewLines(battle: Battle): List<String> {
        val next = battle.turnOrderPreview(6)
        if (next.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var line = "Next: "
        next.forEachIndexed { i, b ->
            val text = "${b.baseName} (${b.side.name})"
            val entry = if (b.side.index == 0) text else Terminal.color(text, "yellow")
            val separator = if (i == 0) "" else " > "
            if (i > 0 && Terminal.visibleLength(line) + separator.length + text.length > RULE_WIDTH) {
                lines += "$line >"
                line = "      $entry"
            } else {
                line += separator + entry
            }
        }
        return lines + line
    }

    private fun statLines(battle: Battle, b: Battler): List<String> {
        val rules = battle.rules
        val rows = mutableListOf(listOf("Stat", "Raw", "Now", "Stage").map { Terminal.dim(it) })
        rules.stats.forEach { stat ->
            val isHp = stat.id == rules.hpStat
            val now = if (isHp) b.maxHp.toDouble() else b.stat(stat.id)
            val stage = b.stage(stat.id)
            rows += listOf(
                stat.name,
                Describe.num(b.rawStat(stat.id)),
                Describe.num(Math.round(now).toDouble()),
                if (stage == 0) "" else if (stage > 0) "+$stage" else "$stage",
            )
        }
        return Terminal.table(rows, right = setOf(1, 2, 3)).map { "  $it" }
    }

    private fun moveLines(battle: Battle, b: Battler): List<String> {
        val setup = battle.setup
        val header = (Describe.moveHeader(setup) + "Target").map { Terminal.dim(it) }
        val rows = b.moves.map { slot ->
            val cells = Describe.moveCells(setup, slot.def).toMutableList()
            var column = 5
            if (setup.rules.usePP) cells[column++] = if (slot.def.pp <= 0) "-" else "${slot.pp}/${slot.def.pp}"
            if (setup.rules.useCooldowns && slot.cooldown > 0) cells[column] = "wait ${slot.cooldown}"
            cells + slot.def.target.id
        }
        val table = Terminal.table(listOf(header) + rows, right = Describe.moveNumberColumns(setup))
        val lines = mutableListOf("Moves:", "  " + table.first())
        b.moves.forEachIndexed { i, slot ->
            lines += "  " + table[i + 1]
            if (slot.def.description.isNotBlank()) lines += Terminal.dim(Terminal.wrap(slot.def.description, 76, "      "))
        }
        return lines
    }
}
