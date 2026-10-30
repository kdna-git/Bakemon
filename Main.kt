import bakemon.FieldStart
import bakemon.Setup
import bakemon.Team
import engine.AiController
import engine.AiLevel
import engine.BattleObserver
import engine.Manager
import ui.Combat
import ui.SetupCreator
import ui.TeamCreator
import ui.Terminal
import util.SetupException
import java.io.File
import java.io.PrintStream
import java.util.Locale
import kotlin.random.Random

/**
 * Bakemon entry point.
 *
 * Options (see README): --setups <dir>, --setup <name>, --seed <n>, --color / --no-color,
 * --validate, --simulate <setup> [--count N] [--teams A,B] [--ai easy|normal|hard] [--fields a,b].
 *
 * Interactive flow: find the setups folder -> choose a setup (skipped if there is only one or --setup is given; the
 * screen also offers "Create a new setup" and the setup creator, even when no setup loads)
 * -> load it (errors are shown and another setup can be picked; warnings are shown once)
 * -> main menu: Battle / Team creator / Bakedex / Setup creator / Change setup / Exit
 * (after the setup creator saved the current setup, it is reloaded).
 */
fun main(args: Array<String>) {
    val code = try {
        val options = parseOptions(args)
        Terminal.colors = options.color ?: Terminal.detectColors()
        when {
            options.help -> {
                printUsage(System.out)
                0
            }
            options.validate -> validateAll(options)
            options.simulate != null -> simulate(options, options.simulate!!)
            else -> interactive(options)
        }
    } catch (e: UsageException) {
        System.err.println("Error: ${e.message}")
        System.err.println()
        printUsage(System.err)
        2
    } catch (e: Throwable) {
        if (causedByClosedInput(e)) {
            println()
            0
        } else {
            println()
            println("Unexpected error: ${e.message ?: e::class.simpleName}")
            1
        }
    }
    System.out.flush()
    System.exit(code)
}

// ---- command line -----------------------------------------------------------------------------------------------

/** Parsed command line (README "Building and running"). */
internal class Options {
    var setupsDir: String? = null
    var setupName: String? = null
    var seed: Long? = null
    var color: Boolean? = null
    var validate = false
    var simulate: String? = null
    var count = 100
    var teams: List<String>? = null
    var ai = AiLevel.NORMAL
    /** --fields: ids of the extra starting fields of every simulated battle (checked once the setup is loaded). */
    var fields: List<String>? = null
    var help = false
}

/** A wrong command line: the message is printed with the usage, exit code 2. */
internal class UsageException(message: String) : Exception(message)

internal fun parseOptions(args: Array<String>): Options {
    val o = Options()
    var simulationOnly: String? = null
    var i = 0
    fun value(option: String): String {
        val v = args.getOrNull(i + 1)
        if (v == null || v.startsWith("--")) throw UsageException("$option needs a value")
        i++
        return v
    }
    while (i < args.size) {
        when (val arg = args[i]) {
            "--setups" -> o.setupsDir = value(arg)
            "--setup" -> o.setupName = value(arg)
            "--seed" -> o.seed = value(arg).toLongOrNull() ?: throw UsageException("--seed needs a whole number")
            "--color" -> o.color = true
            "--no-color" -> o.color = false
            "--validate" -> o.validate = true
            "--simulate" -> o.simulate = value(arg)
            "--count" -> {
                o.count = value(arg).toIntOrNull()?.takeIf { it > 0 } ?: throw UsageException("--count needs a positive whole number")
                simulationOnly = arg
            }
            "--teams" -> {
                val names = value(arg).split(',').map { it.trim() }
                if (names.size !in 1..2 || names.any { it.isEmpty() }) throw UsageException("--teams needs one or two team names: A,B")
                o.teams = names
                simulationOnly = arg
            }
            "--ai" -> {
                o.ai = AiLevel.fromId(value(arg)) ?: throw UsageException("--ai must be easy, normal or hard")
                simulationOnly = arg
            }
            "--fields" -> {
                val ids = value(arg).split(',').map { it.trim() }
                if (ids.any { it.isEmpty() }) throw UsageException("--fields needs field ids separated by commas: rain,spikes")
                o.fields = ids
                simulationOnly = arg
            }
            "--help", "-h", "-?", "/?" -> o.help = true
            else -> throw UsageException("Unknown option '$arg'")
        }
        i++
    }
    if (simulationOnly != null && o.simulate == null) throw UsageException("$simulationOnly only works with --simulate")
    if (o.validate && o.simulate != null) throw UsageException("Use either --validate or --simulate, not both")
    return o
}

private fun printUsage(out: PrintStream) {
    out.println(
        """
        Bakemon - turn-based monster battles defined by JSON setups

        Usage: Bakemon [options]

        Options:
          --setups <dir>        Folder that contains the setups (default: auto-detects the Setups folder)
          --setup <name>        Open this setup directly
          --seed <n>            Fix the random seed so battles can be reproduced
          --color, --no-color   Turn ANSI colours on or off (auto-detected by default)
          --validate            Load every setup, print errors and warnings, then exit
                                (exit code 1 if any setup is invalid)
          --simulate <setup>    Run AI-vs-AI battles without the UI and print statistics
            --count <n>         number of battles (default 100)
            --teams <A,B>       saved team names for side 1 and side 2 (default: random teams)
            --ai <level>        easy, normal or hard (default normal)
            --fields <a,b>      extra starting fields (Fields.json ids) for every battle, on top of
                                Rules.json startFields; side fields go on both sides
          --help                Show this help
        """.trimIndent()
    )
}

private fun causedByClosedInput(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.take(20).any { it is Terminal.InputClosed }

// ---- setups folder ------------------------------------------------------------------------------------------------

/** The setups folder, or null after explaining where it looked. */
private fun locateRoot(o: Options): File? {
    val root = Setup.findSetupsRoot(o.setupsDir)
    if (root != null && root.isDirectory) return root
    if (o.setupsDir != null) {
        println("The setups folder '${o.setupsDir}' does not exist (looked at ${File(o.setupsDir!!).absolutePath}).")
        return null
    }
    val cwd = File("").absoluteFile
    println("Could not find the folder with the setups. Looked in:")
    println("  ${File(cwd, "Setups").path}")
    println("  Setups in the program folder and up to 6 folders above it" + (programDir()?.let { " ($it)" } ?: ""))
    println("Run Bakemon from the project folder, or tell it where the setups are: --setups <dir>")
    return null
}

/** The folder of the running jar (or the classes folder when not running from a jar). */
private fun programDir(): String? = try {
    val location = File(Options::class.java.protectionDomain.codeSource.location.toURI())
    (if (location.isFile) location.parentFile else location)?.path
} catch (e: Exception) {
    null
}

private fun discoverSetups(root: File): List<File>? {
    val dirs = Setup.discover(root)
    if (dirs.isEmpty()) {
        println("No setups found in ${root.path}. A setup is a folder that contains a Rules.json file.")
        return null
    }
    return dirs
}

private fun errorLines(e: Exception): List<String> =
    (e.message ?: e::class.simpleName ?: "unknown error").lines().filter { it.isNotBlank() }

// ---- --validate ---------------------------------------------------------------------------------------------------

private fun validateAll(o: Options): Int {
    val root = locateRoot(o) ?: return 1
    val dirs = discoverSetups(root) ?: return 1
    var failed = 0
    for (dir in dirs) {
        try {
            val setup = Setup.load(dir)
            println("OK ${dir.name} (${setup.warnings.size} warnings)")
            setup.warnings.forEach { println("  warning: $it") }
        } catch (e: Exception) {
            failed++
            println("FAILED ${dir.name}")
            val lines = if (e is SetupException) errorLines(e) else listOf("internal error: ${e.message ?: e::class.simpleName}")
            lines.forEach { println("  $it") }
        }
    }
    println()
    println(if (failed == 0) "All ${dirs.size} setup(s) are valid." else "$failed of ${dirs.size} setup(s) failed.")
    return if (failed == 0) 0 else 1
}

// ---- --simulate ---------------------------------------------------------------------------------------------------

/**
 * The --fields ids (matched ignoring case, each field once) as permanent starting fields (side fields on both
 * sides). An id that is not in the setup's Fields.json is a [UsageException] naming the fields that exist.
 */
internal fun simulationFields(setup: Setup, ids: List<String>): List<FieldStart> = ids.map { id ->
    val def = setup.fields[id] ?: setup.fields.all().firstOrNull { it.id.equals(id, ignoreCase = true) }
    if (def == null) {
        val known = setup.fields.all().joinToString(", ") { it.id }
        throw UsageException(
            "Unknown field '$id' in --fields. " +
                if (known.isEmpty()) "${setup.displayName} has no fields (no Fields.json)." else "Fields of ${setup.displayName}: $known",
        )
    }
    def
}.distinctBy { it.id }.map { FieldStart(it.id) }

internal fun simulate(o: Options, setupName: String): Int {
    val root = locateRoot(o) ?: return 1
    val dirs = discoverSetups(root) ?: return 1
    val dir = dirs.firstOrNull { it.name.equals(setupName, ignoreCase = true) }
    if (dir == null) {
        println("Unknown setup '$setupName'. Available: ${dirs.joinToString(", ") { it.name }}")
        return 1
    }
    val setup = try {
        Setup.load(dir)
    } catch (e: Exception) {
        println("Setup ${dir.name} could not be loaded:")
        errorLines(e).forEach { println("  $it") }
        return 1
    }
    val fields = o.fields?.let { simulationFields(setup, it) } ?: emptyList()
    val fixedTeams = o.teams?.let { names -> loadSimulationTeams(setup, names) ?: return 1 }
    val base = o.seed ?: Random.nextLong(1, 1_000_000_000)
    val controllers = listOf(AiController("AI 1", o.ai), AiController("AI 2", o.ai))
    val sideNames = listOf("AI 1", "AI 2")
    val silent = object : BattleObserver {}

    val teamsLabel = fixedTeams?.let { "teams '${it[0].name}' vs '${it[1].name}'" } ?: "random teams"
    println("Simulating ${o.count} battle(s) of ${setup.displayName}: AI ${o.ai.id} vs AI ${o.ai.id}, $teamsLabel, seeds $base..${base + o.count - 1}")
    val startNames = (setup.rules.startFields.map { it.field } + fields.map { it.field }).mapNotNull { setup.fields[it]?.name }
    if (startNames.isNotEmpty()) println("Starting fields: ${startNames.joinToString(", ")}")

    val wins = IntArray(2)
    var draws = 0
    val turns = mutableListOf<Int>()
    val crashes = mutableListOf<Long>()
    val start = System.nanoTime()
    for (i in 0 until o.count) {
        val seed = base + i
        try {
            val teams = fixedTeams ?: Random(seed).let { r ->
                listOf(setup.randomTeam(r, name = "Random 1"), setup.randomTeam(r, name = "Random 2"))
            }
            val result = Manager(setup, teams, controllers, silent, seed, sideNames, fields).run()
            val winner = result.winner
            if (winner != null && winner in wins.indices) wins[winner]++ else draws++
            turns += result.turns
        } catch (e: Throwable) {
            crashes += seed
            if (crashes.size <= 3) {
                println()
                println("Battle ${i + 1} (seed $seed) threw an exception:")
                println(e.stackTraceToString().trimEnd())
            }
        }
    }
    val seconds = (System.nanoTime() - start) / 1e9
    printSimulationSummary(o, wins, draws, turns, crashes, seconds)
    if (crashes.isNotEmpty()) {
        // Every option that shaped the battles, quoted so the line can be pasted as is (names may contain spaces).
        val extra = buildString {
            o.setupsDir?.let { append(" --setups ${quoteArgument(it)}") }
            o.teams?.let { append(" --teams ${quoteArgument(it.joinToString(","))}") }
            if (o.ai != AiLevel.NORMAL) append(" --ai ${o.ai.id}")
            if (fields.isNotEmpty()) append(" --fields ${quoteArgument(fields.joinToString(",") { it.field })}")
        }
        println()
        println("${crashes.size} battle(s) threw. Seeds: ${crashes.take(20).joinToString(", ")}${if (crashes.size > 20) ", ..." else ""}")
        println("Reproduce one with: --simulate \"${setup.name}\" --count 1 --seed ${crashes.first()}$extra")
        return 1
    }
    return 0
}

/**
 * [value] in double quotes, written so that the Windows command-line rules (used by the java launcher) give it back
 * unchanged: backslashes before a quote or before the closing quote are doubled, quotes are escaped. Without this,
 * a folder typed with a trailing backslash ("C:\Games\Setups\") would swallow the closing quote and the next options.
 */
internal fun quoteArgument(value: String): String = buildString {
    append('"')
    var backslashes = 0
    for (c in value) {
        when (c) {
            '\\' -> backslashes++
            '"' -> {
                repeat(backslashes * 2 + 1) { append('\\') }
                append('"')
                backslashes = 0
            }
            else -> {
                repeat(backslashes) { append('\\') }
                append(c)
                backslashes = 0
            }
        }
    }
    repeat(backslashes * 2) { append('\\') }
    append('"')
}

private fun loadSimulationTeams(setup: Setup, names: List<String>): List<Team>? {
    val teams = mutableListOf<Team>()
    for (name in names) {
        val file = setup.listTeams().firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        val team = try {
            setup.loadTeam(file)
        } catch (e: Exception) {
            println("Team '$name' could not be loaded: ${e.message}")
            val available = setup.listTeams()
            println(if (available.isEmpty()) "There are no saved teams in ${setup.teamsDir.path}." else "Saved teams: ${available.joinToString(", ")}")
            return null
        }
        val problems = team.validate(setup)
        if (problems.isNotEmpty()) {
            println("Team '$name' is not valid:")
            problems.forEach { println("  $it") }
            return null
        }
        teams += team
    }
    return if (teams.size == 1) listOf(teams[0], teams[0]) else teams
}

private fun printSimulationSummary(o: Options, wins: IntArray, draws: Int, turns: List<Int>, crashes: List<Long>, seconds: Double) {
    fun percent(n: Int) = if (o.count == 0) "" else String.format(Locale.ROOT, "(%.1f%%)", 100.0 * n / o.count)
    val sideLabels = o.teams?.let { t -> listOf("Side 1 (${t[0]})", "Side 2 (${t.getOrElse(1) { t[0] }})") } ?: listOf("Side 1", "Side 2")
    println()
    println("Results:")
    println("  ${sideLabels[0]} wins: ${wins[0]} ${percent(wins[0])}")
    println("  ${sideLabels[1]} wins: ${wins[1]} ${percent(wins[1])}")
    println("  Draws: $draws ${percent(draws)}")
    if (crashes.isNotEmpty()) println("  Errors: ${crashes.size} ${percent(crashes.size)}")
    if (turns.isNotEmpty()) {
        println(String.format(Locale.ROOT, "Turns: average %.1f, min %d, max %d", turns.average(), turns.min(), turns.max()))
    }
    val rate = if (seconds > 0) o.count / seconds else 0.0
    println(String.format(Locale.ROOT, "Speed: %.1f battles per second (%.2f s total)", rate, seconds))
}

// ---- interactive ----------------------------------------------------------------------------------------------------

private enum class MenuExit { CHANGE_SETUP, EXIT }

/** What the setup selection screen returned. */
private sealed class SetupChoice {
    class Open(val dir: File) : SetupChoice()
    object Create : SetupChoice()
    object Creator : SetupChoice()
    object Exit : SetupChoice()
}

internal fun interactive(o: Options): Int {
    val root = locateRoot(o) ?: return 1
    println(Terminal.bold("Bakemon") + Terminal.dim(" - setups in ${root.path}"))
    var wanted = o.setupName
    // The only setup opens directly, unless it could not be loaded (then the selection screen offers the creator).
    var autoOpen = true
    val warned = mutableSetOf<String>()
    while (true) {
        val dirs = Setup.discover(root)
        if (dirs.isEmpty()) {
            println("No setups found in ${root.path}. A setup is a folder that contains a Rules.json file.")
            println("Use \"Create a new setup\" to make one.")
        }
        val choice = chooseSetup(dirs, wanted, autoOpen)
        wanted = null
        when (choice) {
            SetupChoice.Exit -> return 0
            SetupChoice.Create -> SetupCreator(root).createNew()
            SetupChoice.Creator -> SetupCreator(root).run()
            is SetupChoice.Open -> {
                val setup = loadSetup(choice.dir, warned)
                if (setup == null) {
                    autoOpen = false
                    continue
                }
                autoOpen = true
                if (mainMenu(root, setup, o.seed, dirs.size, warned) == MenuExit.EXIT) return 0
            }
        }
    }
}

/**
 * The setup selection screen. [wanted] (--setup) opens that setup; with [autoOpen] the only setup opens directly.
 * The screen always offers "Create a new setup" and the setup creator, so a new or broken setup can be reached.
 */
private fun chooseSetup(dirs: List<File>, wanted: String?, autoOpen: Boolean): SetupChoice {
    if (wanted != null) {
        dirs.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return SetupChoice.Open(it) }
        Terminal.warn("There is no setup called '$wanted'. Available: ${dirs.joinToString(", ") { it.name }.ifEmpty { "none" }}")
    }
    if (dirs.size == 1 && autoOpen) return SetupChoice.Open(dirs[0])
    Terminal.header("Choose a setup")
    val options = dirs.map { it.name } + listOf("Create a new setup", "Setup creator (edit, rename or delete setups)")
    val index = Terminal.menu("", options, "Exit")
    return when {
        index < 0 -> SetupChoice.Exit
        index < dirs.size -> SetupChoice.Open(dirs[index])
        index == dirs.size -> SetupChoice.Create
        else -> SetupChoice.Creator
    }
}

/** Loads a setup, printing its errors (null) or, the first time only, its warnings. */
private fun loadSetup(dir: File, warned: MutableSet<String>): Setup? {
    val setup = try {
        Setup.load(dir)
    } catch (e: Exception) {
        if (causedByClosedInput(e)) throw e
        Terminal.error("The setup '${dir.name}' could not be loaded:")
        errorLines(e).forEach { Terminal.error("  $it") }
        Terminal.warn("Fix it with the setup creator (Choose a setup > Setup creator).")
        return null
    }
    if (setup.warnings.isNotEmpty() && warned.add(dir.absolutePath)) {
        Terminal.warn("${setup.displayName} loaded with ${setup.warnings.size} warning(s):")
        setup.warnings.forEach { Terminal.warn("  $it") }
    }
    return setup
}

private fun mainMenu(root: File, start: Setup, seed: Long?, setupCount: Int, warned: MutableSet<String>): MenuExit {
    var setup = start
    var combat = Combat(setup, seed)
    while (true) {
        Terminal.header("Bakemon - ${setup.displayName}")
        if (setup.rules.description.isNotBlank()) println(Terminal.dim(setup.rules.description))
        val change = if (setupCount > 1) "Change setup" else "Reload setup"
        when (Terminal.menu("", listOf("Battle", "Team creator", "Bakedex", "Setup creator", change), "Exit")) {
            0 -> combat.run()
            1 -> TeamCreator(setup).run()
            2 -> TeamCreator(setup).browse()
            3 -> {
                val creator = SetupCreator(root)
                creator.run()
                // The setup may have been saved, renamed or deleted in the creator: reload it from where it is now.
                var dir = setup.dir.absoluteFile.normalize()
                repeat(20) { creator.renamed[dir]?.let { dir = it } }
                if (!File(dir, "Rules.json").isFile) {
                    Terminal.warn("The setup '${setup.displayName}' is no longer there (deleted or renamed).")
                    return MenuExit.CHANGE_SETUP
                }
                if (dir in creator.saved || dir != setup.dir.absoluteFile.normalize()) {
                    setup = loadSetup(dir, warned) ?: return MenuExit.CHANGE_SETUP
                    combat = Combat(setup, seed)
                    println("Reloaded ${setup.displayName} with the saved changes.")
                }
            }
            4 -> return MenuExit.CHANGE_SETUP
            else -> return MenuExit.EXIT
        }
    }
}
