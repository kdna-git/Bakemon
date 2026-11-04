import bakemon.Bakedex
import bakemon.Bakemon
import bakemon.Setup
import bakemon.Team
import ui.Combat
import ui.TeamCreator
import ui.Terminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.file.Files

/**
 * Tests for the terminal UI logic (input decoding, team creator, battle menu fields, --simulate --fields), driven by
 * scripted input. [root] is the project folder.
 */
fun runUiTests(root: File) {
    Check.section("ui")
    val temp = Files.createTempDirectory("bakemon-ui-tests").toFile()
    try {
        Check.guard("ui: input decoding") { uiInputTests() }
        Check.guard("ui: command-line quoting") { uiQuoteTests() }
        val setup = uiSetup(root, temp) ?: return
        Check.guard("ui: team creator names and files") { uiTeamCreatorTests(setup) }
        Check.guard("ui: empty Bakedex") { uiEmptyDexTests(setup) }
        Check.guard("ui: fields") { uiFieldTests(setup, temp) }
        Check.guard("ui: no fields") { uiNoFieldTests(setup) }
        Check.guard("ui: setup creator, new setup") { uiCreatorNewSetup(temp) }
        Check.guard("ui: setup creator, edit a shipped setup") { uiCreatorEditShipped(root, temp) }
        Check.guard("ui: setup creator, multi-hit, delayed, charge moves and new triggers") { uiCreatorNewBehaviours(root, temp) }
        Check.guard("ui: setup creator, entry points") { uiCreatorEntryPoints(root, temp) }
    } finally {
        Terminal.useInput(System.`in`)
        temp.deleteRecursively()
    }
}

/** Runs [block] with [input] as the keyboard (encoded with [charset]) and returns what it printed. */
private fun uiRun(input: String, charset: Charset = Charsets.UTF_8, block: () -> Unit): String =
    uiRunBytes(input.toByteArray(charset), charset, block)

private fun uiRunBytes(bytes: ByteArray, charset: Charset, block: () -> Unit): String {
    val out = ByteArrayOutputStream()
    val previous = System.out
    System.setOut(PrintStream(out, true, "UTF-8"))
    Terminal.useInput(ByteArrayInputStream(bytes), charset)
    try {
        block()
    } catch (e: Terminal.InputClosed) {
        // The script ran out of lines: the screen under test is checked through its output and files.
    } finally {
        System.out.flush()
        System.setOut(previous)
    }
    return out.toString("UTF-8")
}

/** A copy of the shipped Classic setup (JSON files only) in [temp], with an empty Teams folder. */
private fun uiSetup(root: File, temp: File): Setup? {
    val source = File(root, "Setups/Classic")
    if (!source.isDirectory) {
        println("  (no Classic setup under ${source.path})")
        return null
    }
    val dir = File(temp, "Classic")
    dir.mkdirs()
    source.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { it.copyTo(File(dir, it.name)) }
    return Setup.load(dir)
}

private fun uiInputTests() {
    // A Windows console in code page 850 sends 0xA4 for 'n with tilde': decoded with the console charset it is read as is.
    val cp850 = Charset.forName("IBM850")
    val word = byteArrayOf('M'.code.toByte(), 'u'.code.toByte(), 0xA4.toByte(), 'e'.code.toByte(), 'c'.code.toByte(), 'o'.code.toByte(), '\n'.code.toByte())
    var line = ""
    uiRunBytes(word, cp850) { line = Terminal.readLine("> ") }
    Check.eq("Muñeco", line, "cp850 input decoded with the console charset")
    // The same byte is not valid UTF-8: it becomes U+FFFD instead of ending the program.
    var replaced = ""
    val output = uiRunBytes(word, Charsets.UTF_8) { replaced = Terminal.readLine("> ") }
    Check.ok(replaced.startsWith("Mu") && replaced.endsWith("eco") && '�' in replaced, "malformed input is replaced: '$replaced' ($output)")
    // End of input still ends the program cleanly.
    var closed = false
    uiRun("") {
        try {
            Terminal.readLine("> ")
        } catch (e: Terminal.InputClosed) {
            closed = true
        }
    }
    Check.ok(closed, "end of input throws InputClosed")
    Check.ok(Terminal.inputCharset().name().isNotEmpty(), "an input charset is always available")
}

/** The --simulate reproduction hint quotes values so Windows command-line parsing gives them back unchanged. */
private fun uiQuoteTests() {
    Check.eq("\"Source\\Setups\"", quoteArgument("Source\\Setups"), "plain path")
    Check.eq("\"Source\\Setups\\\\\"", quoteArgument("Source\\Setups\\"), "a trailing backslash is doubled (it would escape the closing quote)")
    Check.eq("\"C:\\\\\"", quoteArgument("C:\\"), "drive root")
    Check.eq("\"Balanced Six,Starter Trio\"", quoteArgument("Balanced Six,Starter Trio"), "spaces and commas stay inside the quotes")
    Check.eq("\"say \\\"hi\\\"\"", quoteArgument("say \"hi\""), "inner quotes are escaped")
    Check.eq("\"a\\\\\\\"b\"", quoteArgument("a\\\"b"), "a backslash before an inner quote is doubled")
}

private fun uiTeamCreatorTests(setup: Setup) {
    val teams = setup.teamsDir
    val members = listOf(
        Bakemon("cindercub", level = 50, ability = "blaze", moves = listOf("ember", "tackle")),
        Bakemon("aquafin", level = 50, ability = "riptide", moves = listOf("bubble_shot", "tackle")),
    )
    setup.saveTeam(Team("Alpha", members))
    setup.saveTeam(Team("Beta", members))
    val alpha = File(teams, "Alpha.json")
    val alphaText = alpha.readText()

    // Rename to a name that maps to another team's file: the overwrite question is asked (and "no" keeps that team).
    run {
        val out = uiRun("4\n2\nAlpha.\nn\nGamma\n0\n") { TeamCreator(setup).run() }
        Check.ok("would be saved as 'Alpha.json'" in out, "rename asks before overwriting Alpha.json:\n$out")
        Check.eq(alphaText, alpha.readText(), "Alpha.json untouched")
        Check.ok(File(teams, "Gamma.json").isFile && !File(teams, "Beta.json").exists(), "renamed to Gamma: ${setup.listTeams()}")
    }

    // New team: a too long name is refused (it would not fit in a file name), then a short one is saved.
    run {
        val out = uiRun("1\n${"L".repeat(61)}\nShort\n3\n0\n0\n") { TeamCreator(setup).run() }
        Check.ok("at most 60 characters" in out, "long team name refused:\n$out")
        Check.ok(File(teams, "Short.json").isFile, "short name saved: ${setup.listTeams()}")
    }

    // Editing a team saves back to the file it was loaded from, not to the file its name maps to.
    run {
        File(teams, "Alpha - Copy.json").writeText(alphaText)
        Check.eq(listOf("Alpha", "Alpha - Copy", "Gamma", "Short"), setup.listTeams(), "teams before editing the copy")
        // Edit team > 2nd entry (the copy, also named Alpha) > Remove member > 1 > yes > Save > back > back.
        val out = uiRun("3\n2\n3\n1\ny\n5\n0\n0\n") { TeamCreator(setup).run() }
        Check.ok("Alpha - Copy.json" in out, "saved to the copy's file:\n$out")
        Check.eq(alphaText, alpha.readText(), "Alpha.json untouched by editing the copy")
        Check.eq(1, setup.loadTeam("Alpha - Copy").members.size, "the copy lost a member")
    }

    // A save that fails (read-only file) is reported and the editor stays open; the program does not end.
    run {
        Check.ok(alpha.setReadOnly(), "Alpha.json made read-only")
        try {
            // Edit team > Alpha > Remove member > 1 > yes > Save (fails) > back > don't save > back.
            val out = uiRun("3\n1\n3\n1\ny\n5\n0\nn\n0\n") { TeamCreator(setup).run() }
            Check.ok("The team could not be saved" in out, "save error reported:\n$out")
            Check.ok("Save the changes to 'Alpha' before leaving?" in out, "still unsaved after the failure")
        } finally {
            alpha.setWritable(true)
        }
        Check.eq(alphaText, alpha.readText(), "the read-only file kept its content")
    }

    // Copy team can be cancelled at the name prompt ("0"); Enter accepts the suggested name.
    run {
        val before = setup.listTeams()
        val out = uiRun("5\n1\n0\n0\n") { TeamCreator(setup).run() }
        Check.ok("0 = cancel" in out, "the name prompt says how to cancel:\n$out")
        Check.eq(before, setup.listTeams(), "cancelled copy writes nothing")
        uiRun("5\n1\n\n0\n") { TeamCreator(setup).run() }
        Check.ok(File(teams, "Alpha copy.json").isFile, "Enter accepts the suggested name: ${setup.listTeams()}")
    }

    // The suggested name of a copy fits in the name limit (it used to be refused by the prompt that suggested it).
    run {
        val long = "N".repeat(58)
        setup.saveTeam(Team(long, members))
        val index = setup.listTeams().indexOf(long) + 1
        val out = uiRun("5\n$index\n\n0\n") { TeamCreator(setup).run() }
        Check.ok("at most 60 characters" !in out, "the suggested copy name is accepted:\n$out")
        val copy = setup.listTeams().map { setup.loadTeam(it).name }.firstOrNull { it != long && it.startsWith("NNNN") }
        Check.ok(copy != null && copy.length <= 60 && copy.endsWith(" copy"), "copy named '$copy'")
    }
}

private fun uiEmptyDexTests(setup: Setup) {
    val empty = Setup(
        setup.name, setup.dir, setup.rules, setup.types, Bakedex(emptyMap()),
        setup.moves, setup.abilities, setup.items, setup.statuses, emptyList(),
    )
    // Browse > Bakedex > back: must return at once (it used to loop forever printing "No species match ''.").
    var out = ""
    val console = System.out
    val worker = Thread {
        out = uiRun("1\n0\n") { TeamCreator(empty).browse() }
    }
    worker.isDaemon = true
    worker.start()
    worker.join(10_000)
    if (worker.isAlive) System.setOut(console) // a stuck browser must not swallow the rest of the test output
    Check.ok(!worker.isAlive, "the Bakedex browser returns with an empty Bakedex")
    Check.ok("This setup has no species" in out, "and says why:\n${out.take(500)}")
}

/**
 * A copy of [base] (JSON files only) under [temp]/FieldUi with a small Fields.json and a Rules.json "startFields":
 * mud (not selectable) always starts for 3 turns; rain / sun (weather group) and wall (side, no group) are selectable.
 * Sun blocks every move, so a player under it only has the fallback move.
 */
private fun uiFieldSetup(base: Setup, temp: File): Setup {
    val dir = File(temp, "FieldUi")
    dir.mkdirs()
    base.dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
    val rules = File(dir, "Rules.json")
    rules.writeText(rules.readText().replaceFirst("{", "{ \"startFields\": [ { \"field\": \"mud\", \"duration\": 3 } ],"))
    File(dir, "Fields.json").writeText(
        """
        { "fields": [
          { "id": "mud", "name": "Mud", "description": "Sticky ground.", "statModifiers": { "spe": 0.5 } },
          { "id": "rain", "name": "Rain", "group": "weather", "selectable": true, "duration": 5,
            "description": "It rains.", "startMessage": "UI rain falls!" },
          { "id": "sun", "name": "Sun", "group": "weather", "selectable": true, "rules": { "blockMove": true } },
          { "id": "wall", "name": "Wall", "scope": "side", "selectable": true, "description": "A test wall." }
        ] }
        """.trimIndent(),
    )
    val setup = Setup.load(dir)
    Check.eq(4, setup.fields.all().size, "the field test setup has its fields")
    return setup
}

/** The battle menu's starting fields, the field display, the move menu under a blocking field and the info text. */
private fun uiFieldTests(classic: Setup, temp: File) {
    val setup = uiFieldSetup(classic, temp)

    // AI vs AI (easy, instant), random teams, choose fields: Rain, then Wall on AI 2; then a rematch keeps them.
    run {
        val out = uiRun("3\n1\n1\n2\n1\n1\n2\n1\ny\n2\n1\n") { Combat(setup, seed = 3).run() }
        Check.ok("== Starting fields ==" in out, "the field menu is shown:\n${out.take(3000)}")
        Check.ok("Set at the start of every battle: Mud (3 turns)" in out, "Rules.startFields are listed with their duration:\n${out.take(3000)}")
        Check.ok("Weather" in out && "0) None" in out, "one choice per exclusive group")
        Check.ok("Start with Wall?" in out && "Wall on which side?" in out, "an ungrouped side field asks yes/no and the side")
        Check.ok("Starting fields: Rain, Wall (AI 2)" in out, "the picks are summed up:\n${out.take(3000)}")
        Check.ok("Field: Mud (3) | Rain" in out, "global fields line, start fields first:\n${out.take(5000)}")
        Check.ok(Regex("""(?m)^AI 2  Wall\b""").containsMatchIn(out), "side fields next to the side's name")
        Check.ok(Regex("""(?m)^AI 1  Wall\b""").find(out) == null, "Wall only on AI 2")
        Check.eq(2, Regex("UI rain falls!").findAll(out).count(), "the rematch starts with the same fields")
    }

    // "No extra fields" skips the questions: only the always-active field.
    run {
        val out = uiRun("3\n1\n1\n2\n1\n1\n1\n") { Combat(setup, seed = 3).run() }
        Check.ok("Weather" !in out && "UI rain falls!" !in out, "no extra field after skipping:\n${out.take(3000)}")
        Check.ok("Field: Mud (3)\n" in out, "the start field is still shown:\n${out.take(5000)}")
    }

    // Back from the field menu returns to the mode menu without a battle.
    run {
        val out = uiRun("3\n1\n1\n2\n1\n1\n0\n") { Combat(setup, seed = 3).run() }
        Check.eq(2, Regex("Choose a mode").findAll(out).count(), "back from the field menu goes to the mode menu")
        Check.ok("Battle seed" !in out, "no battle after going back")
    }

    // You vs AI under Sun (blocks every move): the moves are shown as blocked and the fallback move is offered.
    run {
        val out = uiRun("1\n1\n1\n1\n2\n2\nn\n") { Combat(setup, seed = 3).run() }
        Check.ok("[blocked by Sun]" in out, "blocked moves show the field:\n${out.takeLast(3000)}")
        Check.ok("(no usable moves)" in out, "the fallback move is offered:\n${out.takeLast(3000)}")
    }

    // The info text lists the active fields with their place, turns left and description.
    run {
        var details = ""
        val observer = object : engine.BattleObserver {
            override fun onTurnStart(battle: engine.Battle, actor: engine.Battler?) {
                if (details.isEmpty()) details = ui.FieldView.fieldDetails(battle)
            }
        }
        val random = kotlin.random.Random(1)
        val teams = listOf(setup.randomTeam(random, name = "A"), setup.randomTeam(random, name = "B"))
        val controllers = listOf(engine.AiController("A", engine.AiLevel.EASY), engine.AiController("B", engine.AiLevel.EASY))
        engine.Manager(setup, teams, controllers, observer, 1, listOf("A", "B"), listOf(bakemon.FieldStart("wall", side = 0))).run()
        Check.ok("Mud (whole battle, 3 turns left)" in details, "start field with its turns:\n$details")
        Check.ok("Wall (A, until removed)" in details && "A test wall." in details, "side field with its side and description:\n$details")
    }

    // Bakedex browser: a Fields section only when the setup has fields.
    run {
        val out = uiRun("7\n\n0\n") { TeamCreator(setup).browse() }
        Check.ok("7) Fields (4)" in out, "browse offers the fields:\n$out")
        Check.ok("Wall [WALL] one side, until removed, selectable at battle start" in out, "field line:\n$out")
        Check.ok("Rain [RAIN] global, group weather, 5 turns, selectable at battle start" in out, "field line:\n$out")
        val plain = uiRun("0\n") { TeamCreator(classic).browse() }
        Check.ok("Fields (" !in plain, "no Fields section without Fields.json:\n$plain")
    }

    // --simulate --fields: parsing, unknown ids, and a short run.
    run {
        Check.eq(listOf("rain", "wall"), parseOptions(arrayOf("--simulate", "FieldUi", "--fields", "rain, wall")).fields, "--fields parsed")
        Check.throws<UsageException>("--fields without --simulate") { parseOptions(arrayOf("--fields", "rain")) }
        Check.throws<UsageException>("--fields with an empty id") { parseOptions(arrayOf("--simulate", "X", "--fields", "rain,")) }
        Check.eq(listOf(bakemon.FieldStart("rain"), bakemon.FieldStart("wall")), simulationFields(setup, listOf("rain", "Wall", "rain")), "ids resolved, side fields on both sides")
        Check.eq(listOf(bakemon.FieldStart("wall")), simulationFields(setup, listOf("wall", "WALL")), "ids differing only in case are one field")
        val unknown = try {
            simulationFields(setup, listOf("rian"))
            ""
        } catch (e: UsageException) {
            e.message ?: ""
        }
        Check.ok("Unknown field 'rian'" in unknown && "mud, rain, sun, wall" in unknown, "unknown id names the fields: $unknown")
        val none = try {
            simulationFields(classic, listOf("rain"))
            ""
        } catch (e: UsageException) {
            e.message ?: ""
        }
        Check.ok("has no fields" in none, "a setup without fields says so: $none")
        val options = parseOptions(arrayOf("--setups", temp.path, "--simulate", "FieldUi", "--count", "3", "--seed", "1", "--ai", "easy", "--fields", "rain,wall"))
        var code = -1
        val out = uiRun("") { code = simulate(options, "FieldUi") }
        Check.eq(0, code, "simulation with --fields:\n$out")
        Check.ok("Starting fields: Mud, Rain, Wall" in out, "the simulation lists its starting fields:\n$out")
    }
}

/** A setup without Fields.json: no field menu, no field line, unchanged display. */
private fun uiNoFieldTests(classic: Setup) {
    val out = uiRun("3\n1\n1\n2\n1\n1\n") { Combat(classic, seed = 3).run() }
    Check.ok("Battle seed" in out, "the battle ran:\n${out.take(2000)}")
    Check.ok("Starting fields" !in out && "Field:" !in out, "no field menu or line without fields:\n${out.take(2000)}")
}

// ---- setup creator ------------------------------------------------------------------------------------------------

/** Script lines for [uiRun]: comments after "#" are dropped, so the scripts can say what each line answers. */
private fun uiScript(vararg lines: String): String = lines.joinToString("\n") { it.substringBefore("#").trimEnd() } + "\n"

/** Every file under [dir] (relative path with "/" -> text). */
private fun uiFiles(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }
    .associate { it.relativeTo(dir).path.replace(File.separatorChar, '/') to it.readText() }

@Suppress("UNCHECKED_CAST")
private fun uiEntries(dir: File, file: String, key: String): List<Map<String, Any?>> {
    val value = util.Json.parse(File(dir, file).readText(), file)
    val list = if (value is Map<*, *>) value[key] else value
    return (list as List<Any?>).map { it as Map<String, Any?> }
}

/**
 * A new setup built only through the creator's menus (piped input): rule template, two types with matchups, a status
 * from a preset, a move with a secondary effect, an ability, an item, a field and two bakemons; then it validates,
 * saves, loads and runs battles.
 */
private fun uiCreatorNewSetup(temp: File) {
    val root = File(temp, "creator-new")
    root.mkdirs()
    val input = uiScript(
        "abc",          // letters at a menu: asked again
        "",             // empty input at a menu without a default: asked again
        "1",            // Create a new setup
        "UI Game",
        "",             // turns: Classic-style rounds
        "",             // stats: Classic
        "",             // answer the other questions now? no
        "",             // Create the setup (Enter = Done)
        "1", "1", "Fire", "",                           // checklist > Types > add Fire (suggested colour)
        "2", "Water", "", "y", "1", "1", "0", "y", "1", "2", "0", // Water: strong against Fire, Fire is weak against it
        "0", "0",                                       // back to the checklist, back to the setup
        "3", "1", "Burn", "1", "2", "", "", "", "0",     // Statuses > add Burn: major, damage over time preset
        "4", "2", "Ember", "1", "2", "", "",            // Moves > add Ember: Fire, special, fixed power 40
        "", "", "",                                     // one hit, ignores no defense, no charge
        "16", "1", "", "3", "1", "", "", "30", "", "", "", // Effects > add a trigger: when it hits, Statuses > apply Burn, 30%
        "", "0", "", "0",                               // trigger done, back, add the move, back
        "5", "1", "Blaze", "3", "1", "", "", "0",        // Abilities > Blaze: boost Fire moves by 50%
        "6", "1", "Berry", "5", "", "", "y", "", "0",    // Items > Berry: heal at the end of the turn, used up
        "7", "1", "Rain", "", "2", "2", "", "1", "", "", "", // Fields > Rain: weather, boosts Water, weakens Fire
        "7", "2", "5", "10", "y", "", "0",               // 5 turns, selectable, add
        "8", "1", "Cinder", "1", "3", "45", "60", "40", "70", "50", "65", // Bakemons > Cinder: Fire, base stats
        "1", "2", "4", "3", "A small fire cub.", "", "", // ability Blaze, the moves of its types, add
        "2", "Splash", "2", "3", "", "", "", "", "", "", // Splash: Water, default stats
        "2", "5", "3", "", "", "", "0",                  // no ability, the moves without a type, add
        "10", "",       // Validate
        "11", "",       // Save (yes)
        "0", "0",       // back to the home screen, back
    )
    val out = uiRun(input) { ui.SetupCreator(root).run() }
    Check.ok("Please enter a number from 0 to 3." in out, "letters and empty input are asked again:\n${out.take(1500)}")
    Check.ok("Created the setup 'UI Game'" in out, "the setup is created:\n${out.take(3000)}")
    Check.ok("== Checklist: what UI Game needs to battle ==" in out && "[ ] At least one type" in out, "the checklist follows the creation")
    Check.ok("Something went wrong" !in out, "no error in the screens:\n${out.lines().filter { "Something went wrong" in it }}")
    Check.ok("The setup is valid: ready to battle" in out, "validate and save say it is valid:\n${out.takeLast(3000)}")
    Check.ok(Regex("UI Game +1 warning").containsMatchIn(out), "the home screen shows its status (missing images only):\n${out.takeLast(1500)}")
    val dir = File(root, "UI Game")
    val setup = Setup.load(dir)
    Check.eq(listOf("fire", "water"), setup.types.all().map { it.id }, "types")
    Check.eq(2.0, setup.types.single("water", "fire"), "Water is strong against Fire")
    Check.eq(0.5, setup.types.single("fire", "water"), "Fire is weak against Water")
    Check.eq(listOf("desperate_strike", "ember"), setup.moves.all().map { it.id }, "moves (the template's Desperate Strike and Ember)")
    Check.eq(listOf("burn"), setup.statuses.all().map { it.id }, "statuses")
    Check.eq(listOf("blaze"), setup.abilities.all().map { it.id }, "abilities")
    Check.eq(listOf("berry"), setup.items.all().map { it.id }, "items")
    Check.eq(listOf("rain"), setup.fields.all().map { it.id }, "fields")
    Check.eq(listOf("cinder", "splash"), setup.dex.all().map { it.id }, "bakemons")
    val ember = uiEntries(dir, "Moves.json", "moves").first { it["id"] == "ember" }
    Check.eq("[{on=hit, effects=[{type=applyStatus, status=burn, chance=30.0}]}]", ember["triggers"].toString(), "Ember's secondary effect")
    val burn = uiEntries(dir, "Statuses.json", "statuses").first()
    Check.ok(burn["kind"] == "major" && burn["triggers"].toString().contains("turnEnd"), "Burn from the damage-over-time preset: $burn")
    val rain = uiEntries(dir, "Fields.json", "fields").first()
    Check.ok(rain["duration"] == 5.0 && rain["selectable"] == true, "Rain: 5 turns, selectable: $rain")
    val species = uiEntries(dir, "Bakemons.json", "bakemons")
    Check.eq(listOf("ember"), species[0]["moves"], "the moves of its types")
    Check.eq(listOf("blaze"), species[0]["abilities"], "Cinder's ability")
    Check.eq(65.0, (species[0]["baseStats"] as Map<*, *>)["spe"], "base stats typed one by one")
    Check.eq(listOf("desperate_strike"), species[1]["moves"], "the moves without a type")
    Check.ok(File(dir, "Teams").isDirectory && File(dir, "Assets/README.txt").isFile, "Teams/ and Assets/ are created")
    // Battles in the new setup.
    var code = -1
    val sim = uiRun("") { code = simulate(parseOptions(arrayOf("--setups", root.path, "--simulate", "UI Game", "--count", "30", "--seed", "1")), "UI Game") }
    Check.eq(0, code, "30 AI battles in the new setup:\n$sim")
    Check.ok("Errors:" !in sim, "no battle threw:\n$sim")
}

/**
 * The new move behaviours and triggers built through the menus in a copy of Classic: a multi-hit move whose power grows
 * (and whose 3rd hit burns), a 2-5 hit move, a Future-Sight-like delayed move, a charge move, an ability that heals
 * every ally 20% of its own max HP, a counterattack ability and an "HP drops below 50%" ability. Then it validates, and
 * 30 battles run with every species knowing the new moves and abilities.
 */
private fun uiCreatorNewBehaviours(root: File, temp: File) {
    val setups = File(temp, "creator-behaviours")
    val dir = File(setups, "Classic")
    File(root, "Setups/Classic").copyRecursively(dir)
    val input = uiScript(
        "1", "4",                                       // Classic > Moves
        "21", "Triple Kick", "1", "1", "", "20",         // add: Normal, physical, fixed power 20
        "2", "3", "y", "n", "y",                         // Hits: always 3, the power grows, no random enemies, each hit rolls the accuracy
        "", "",                                          // ignores no defense, no charge
        "16", "1", "", "3", "1", "", "", "", "", "", "", // Effects > when it hits: Statuses and stats > apply Burn
        "3", "1", "3", "8", "3", "0", "",                // IF > The move and the hit > it is hit number 3; back; Done
        "0", "",                                         // back, add the move
        "21", "Fury Swipes", "1", "1", "", "18",         // Normal, physical, fixed power 18
        "3", "", "", "n", "n", "n", "", "", "",          // Hits: random 2 to 5; no extras; no defense ignored, no charge; add
        "21", "Future Strike", "11", "2", "2", "",       // Psychic, special, no direct damage, no charge
        "16", "1", "", "4", "3", "", "",                 // Effects > when it hits: Moves and turns > delayed effect, 2 turns, the target's
        "1", "2", "", "6", "120", "", "", "",            // its effect: replace it with an extra strike of power 120
        "0", "The foreseen attack hits {target}!", "",   // back, message, Done
        "0", "",                                         // back, add the move
        "21", "Sky Dive", "10", "1", "", "90", "", "",   // Flying, physical, power 90, one hit, no defense ignored
        "y", "3", "y", "", "",                           // charges first, out of reach while charging; Done; add
        "0", "5",                                        // back, Abilities
        "21", "Healing Aura", "", "5", "1", "4",         // no preset; Triggers > add: at the end of its turn
        "2", "1", "20", "", "3", "", "0", "",            // Healing and HP > heal 20% of its max HP, its allies; Done; back; add
        "21", "Vengeance", "", "5", "1", "15",           // when it has been attacked
        "4", "1", "50", "{self} strikes back!",          // Moves and turns > strike back with 50% power
        "3", "2", "3", "6", "0", "", "0", "",            // IF: + the attack landed; Done; back; add
        "21", "Last Stand", "", "5", "1", "13", "",      // when its HP drops below 50%
        "3", "6", "", "2", "", "", "0", "",              // Statuses and stats > raise Attack by 2; Done; back; add
        "0", "10", "",                                   // back, Validate
        "11", "", "0", "0",                              // Save, back, back
    )
    val out = uiRun(input) { ui.SetupCreator(setups).run() }
    Check.ok("Something went wrong" !in out && "Please enter" !in out, "the script fits the menus:\n${out.lines().filter { "Something went wrong" in it || "Please enter" in it }.take(5)}")
    Check.ok("Moves.json: 4 added (triple_kick, fury_swipes, future_strike, sky_dive)" in out && "Abilities.json: 3 added (healing_aura, vengeance, last_stand)" in out,
        "the moves and abilities are added:\n${out.takeLast(2500)}")
    Check.ok(out.split("The setup is valid: ready to battle").size >= 3, "validate and save say it is valid:\n${out.takeLast(2500)}")
    Check.ok("Hits: 3 hits, power grows with each hit, each hit rolls the accuracy" in out, "the Hits row in plain words")
    Check.ok("When its HP drops below 50%, once per battle: raise or lower a stat" in out, "the HP threshold in plain words")
    val moves = uiEntries(dir, "Moves.json", "moves").associateBy { it["id"] }
    val kick = moves.getValue("triple_kick")
    Check.ok(kick["power"] == "20 * hitIndex" && kick["hits"] == 3.0 && kick["hitAccuracy"] == "each", "Triple Kick: $kick")
    Check.eq("[{on=hit, condition=hitIndex == 3, effects=[{type=applyStatus, status=burn}]}]", kick["triggers"].toString(), "the 3rd hit burns")
    Check.eq("randint(2, 5)", moves.getValue("fury_swipes")["hits"], "Fury Swipes hits 2 to 5 times")
    Check.eq("[{on=hit, effects=[{type=delay, turns=2.0, effects=[{type=attack, power=120.0}], message=The foreseen attack hits {target}!}]}]",
        moves.getValue("future_strike")["triggers"].toString(), "Future Strike")
    Check.eq("{invulnerable=true}", moves.getValue("sky_dive")["charge"].toString(), "Sky Dive charges out of reach")
    val abilities = uiEntries(dir, "Abilities.json", "abilities").associateBy { it["id"] }
    Check.eq("[{on=turnEnd, effects=[{type=heal, target=allies, amount=recipient.maxHp * 0.2}]}]", abilities.getValue("healing_aura")["triggers"].toString(), "Healing Aura")
    Check.eq("[{on=attacked, condition=!sameSide(self, other) && landed, effects=[{type=useMove, move=first, power=50.0, message={self} strikes back!}]}]",
        abilities.getValue("vengeance")["triggers"].toString(), "Vengeance")
    Check.eq("[{on=damaged, condition=self.hpPercent < 50 && hpBefore * 100 / self.maxHp >= 50, limit=1.0, effects=[{type=statStage, stat=atk, stages=2.0}]}]",
        abilities.getValue("last_stand")["triggers"].toString(), "Last Stand")
    // Every species learns the new moves and has the new abilities, so the battles use them.
    val draft = creator.SetupDraft.load(dir)
    val newMoves = listOf("triple_kick", "fury_swipes", "future_strike", "sky_dive")
    for (id in draft.ids(creator.EntryKind.BAKEMON)) {
        val entry = draft.entry(creator.EntryKind.BAKEMON, id)!!
        entry["moves"] = newMoves
        entry["abilities"] = listOf("healing_aura", "vengeance", "last_stand").shuffled(kotlin.random.Random(id.hashCode()))
        draft.replace(creator.EntryKind.BAKEMON, id, entry)
    }
    val saved = draft.save()
    Check.ok(saved.validation.ok, "the species changes are valid: ${saved.validation.errors.take(3)}")
    var code = -1
    val sim = uiRun("") { code = simulate(parseOptions(arrayOf("--setups", setups.path, "--simulate", "Classic", "--count", "30", "--seed", "7")), "Classic") }
    Check.eq(0, code, "30 AI battles with the new moves and abilities:\n${sim.takeLast(1500)}")
    Check.ok("Errors:" !in sim, "no battle threw:\n${sim.takeLast(1500)}")
}

/** Editing one entry of a copied shipped setup changes only that entry; rename and delete update the references. */
private fun uiCreatorEditShipped(root: File, temp: File) {
    val setups = File(temp, "creator-edit")
    val dir = File(setups, "Classic")
    File(root, "Setups/Classic").copyRecursively(dir)
    val before = uiFiles(dir)

    // Edit Ember's power and save: Moves.json changes on that one line only.
    run {
        val out = uiRun(uiScript("1", "4", "ember", "1", "1", "6", "", "45", "", "0", "0", "11", "", "0", "0")) { ui.SetupCreator(setups).run() }
        Check.ok("Moves.json: 1 changed (ember)" in out, "the save shows the change summary:\n${out.takeLast(2500)}")
        Check.ok("The setup is valid: ready to battle." in out, "then the validation:\n${out.takeLast(1500)}")
        val after = uiFiles(dir)
        Check.eq(before.keys, after.keys, "no file added or removed")
        Check.eq(listOf("Moves.json"), before.keys.filter { before[it] != after[it] }, "only Moves.json was written")
        val old = before.getValue("Moves.json").lines()
        val new = after.getValue("Moves.json").lines()
        Check.eq(old.size, new.size, "same number of lines")
        val changed = old.indices.filter { old[it] != new.getOrNull(it) }
        Check.ok(changed.size == 1 && new[changed[0]].trim() == "\"power\": 45,", "only Ember's power line changed: ${changed.map { new[it] }}")
    }

    // Rename the ability blaze: the bakemons and the team that use it follow.
    run {
        val out = uiRun(uiScript("1", "5", "blaze", "1", "3", "fire_heart", "", "0", "0", "11", "", "0", "0")) { ui.SetupCreator(setups).run() }
        Check.ok("These 3 references will be updated:" in out && "Teams/Starter Trio.json: member 1 (cindercub) > ability" in out, "the references are shown:\n${out.take(4000)}")
        val text = uiFiles(dir)
        Check.ok("\"blaze\"" !in text.getValue("Bakemons.json") && "\"fire_heart\"" in text.getValue("Bakemons.json"), "Bakemons.json updated")
        Check.ok("\"fire_heart\"" in text.getValue("Teams/Starter Trio.json"), "the team file is updated")
        Check.ok(uiEntries(dir, "Abilities.json", "abilities").any { it["id"] == "fire_heart" }, "the ability has its new id")
        Setup.load(dir)
    }

    // Delete the ability riptide and remove its references.
    run {
        val out = uiRun(uiScript("1", "5", "riptide", "1", "4", "1", "0", "11", "", "0", "0")) { ui.SetupCreator(setups).run() }
        Check.ok("'Riptide' is used in 2 places:" in out && "Delete it and remove these references" in out, "the references are shown before deleting:\n${out.take(4000)}")
        Check.ok(uiFiles(dir).values.none { "\"riptide\"" in it }, "no reference to riptide is left")
        val setup = Setup.load(dir)
        Check.ok(setup.abilities["riptide"] == null && setup.dex["aquafin"]?.abilities == listOf("sponge_body"), "aquafin keeps its other ability")
        Check.ok("Saved teams that used it: Starter Trio." in out, "the teams to check are named:\n${out.takeLast(3000)}")
        val problems = setup.loadTeam("Starter Trio").validate(setup)
        Check.ok(problems.size == 1 && "No ability chosen" in problems[0], "the team member only needs a new ability: $problems")
    }
}

/** The setup selection screen offers the creator even when no setup loads; a saved current setup is reloaded. */
private fun uiCreatorEntryPoints(root: File, temp: File) {
    // A broken setup: the error is shown, then the selection screen with the creator.
    run {
        val setups = File(temp, "entry-broken")
        File(setups, "Broken").mkdirs()
        File(setups, "Broken/Rules.json").writeText("{ broken")
        var code = -1
        val out = uiRun("0\n") { code = interactive(parseOptions(arrayOf("--setups", setups.path))) }
        Check.eq(0, code, "exit code")
        Check.ok("could not be loaded" in out && "Create a new setup" in out && "Setup creator (edit, rename or delete setups)" in out,
            "a broken setup still reaches the creator:\n$out")
        val status = uiRun(uiScript("0")) { ui.SetupCreator(setups).run() }
        Check.ok(Regex("Broken +1 file cannot be read").containsMatchIn(status), "its status on the creator's home screen:\n$status")
    }
    // No setup at all.
    run {
        val setups = File(temp, "entry-empty")
        setups.mkdirs()
        val out = uiRun("0\n") { interactive(parseOptions(arrayOf("--setups", setups.path))) }
        Check.ok("No setups found" in out && "1) Create a new setup" in out, "an empty folder offers to create a setup:\n$out")
    }
    // Main menu > Setup creator > edit and save the current setup > it is reloaded.
    run {
        val setups = File(temp, "entry-reload")
        File(root, "Setups/Classic").copyRecursively(File(setups, "Classic"))
        var code = -1
        val script = uiScript("4", "1", "4", "ember", "1", "1", "6", "", "45", "", "0", "0", "11", "", "0", "0", "0")
        val out = uiRun(script) { code = interactive(parseOptions(arrayOf("--setups", setups.path))) }
        Check.eq(0, code, "exit code")
        Check.ok("4) Setup creator" in out, "the main menu offers the setup creator:\n${out.take(1500)}")
        Check.ok("Reloaded Classic with the saved changes." in out, "the saved setup is reloaded:\n${out.takeLast(1500)}")
    }
    // End of input in the middle of a form ends the creator cleanly (InputClosed, caught by uiRun).
    run {
        val setups = File(temp, "entry-eof")
        File(root, "Setups/Classic").copyRecursively(File(setups, "Classic"))
        val out = uiRun(uiScript("1", "4", "2", "1")) { ui.SetupCreator(setups).run() }
        Check.ok("== Move:" in out && "Something went wrong" !in out, "the form was open when the input ended:\n${out.takeLast(800)}")
    }
}
