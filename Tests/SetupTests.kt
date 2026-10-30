import bakemon.Bakemon
import bakemon.FieldAudience
import bakemon.FieldScope
import bakemon.FormulaScope
import bakemon.HitAccuracy
import bakemon.MoveTarget
import bakemon.Setup
import bakemon.Stacking
import bakemon.StatusKind
import bakemon.Team
import bakemon.TurnSystem
import engine.Effect
import engine.EventType
import engine.Formula
import engine.MapContext
import engine.TargetSelector
import engine.TransferMode
import util.Json
import util.JsonObj
import util.SetupException
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** Tests for loading/validating setups and teams. [root] is the project folder. */
fun runSetupTests(root: File) {
    Check.section("setup")
    val temp = Files.createTempDirectory("bakemon-setup-tests").toFile()
    try {
        Check.guard("setup: valid setup") { testValidSetup(temp) }
        Check.guard("setup: defaults") { testDefaults(temp) }
        Check.guard("setup: errors") { testErrors(temp) }
        Check.guard("setup: warnings") { testWarnings(temp) }
        Check.guard("setup: message checks") { testMessageChecks(temp) }
        Check.guard("setup: checkFormula") { testCheckFormula(temp) }
        Check.guard("setup: hit types") { testHitTypes(temp) }
        Check.guard("setup: fields") { testFields(temp) }
        Check.guard("setup: field errors") { testFieldErrors(temp) }
        Check.guard("setup: field warnings") { testFieldWarnings(temp) }
        Check.guard("setup: stats") { testStats(temp) }
        Check.guard("setup: validate") { testValidate(temp) }
        Check.guard("setup: team storage") { testTeamStorage(temp) }
        Check.guard("setup: random teams") { testRandomTeams(temp) }
        Check.guard("setup: discovery") { testDiscovery(temp) }
        Check.guard("setup: triggers, targets and effects") { testTriggerExtensions(temp) }
        Check.guard("setup: trigger errors") { testTriggerErrors(temp) }
        Check.guard("setup: trigger warnings") { testTriggerWarnings(temp) }
    } finally {
        temp.deleteRecursively()
    }
    Check.guard("setup: bundled setups") { reportBundledSetups(root) }
}

// ---------------------------------------------------------------------------------------------------------------
// Fixtures

/** The JSON files of a setup folder, as text. Mutations return a copy. */
private class Fixture(val files: Map<String, String>) {
    /** Replaces the first occurrence of [old] in [file] (which must contain it). */
    fun with(file: String, old: String, new: String): Fixture {
        val text = files[file] ?: throw IllegalArgumentException("fixture has no $file")
        require(old in text) { "fixture: '$old' not found in $file" }
        return Fixture(files + (file to text.replaceFirst(old, new)))
    }

    fun set(file: String, text: String) = Fixture(files + (file to text))
    fun without(file: String) = Fixture(files - file)

    fun write(parent: File, name: String): File {
        val dir = File(parent, name)
        dir.deleteRecursively()
        dir.mkdirs()
        files.forEach { (fileName, text) -> File(dir, fileName).writeText(text) }
        return dir
    }
}

private val RULES = """
{
  "name": "Test Rules",
  "turnSystem": "speed_order",
  "team": { "min": 1, "max": 3, "active": 1, "speciesClause": true, "itemClause": true },
  "level": { "min": 1, "max": 100, "default": 50 },
  "maxMoves": 2,
  "stats": [
    { "id": "hp", "name": "HP" },
    { "id": "atk", "name": "Attack" },
    { "id": "def", "name": "Defense" },
    { "id": "spe", "name": "Speed" }
  ],
  "statFormulas": {
    "hp": "floor((2 * base + floor(points / 4)) * level / 100) + level + 10",
    "default": "floor((2 * base + floor(points / 4)) * level / 100) + 5"
  },
  "statPoints": { "total": 100, "maxPerStat": 60 },
  "categories": { "physical": { "attack": "atk", "defense": "def" }, "status": {} },
  "damage": "floor(floor(2 * user.level / 5 + 2) * move.power * attack / defense / 50 + 2) * (sameType ? 1.5 : 1) * effectiveness * crit * random(0.85, 1)",
  "critChance": "1 / 24",
  "fallbackMove": "struggle",
  "actionBar": { "fill": "speed", "start": "random(0, 10)" }
}
""".trimIndent()

private val TYPES = """
{
  "types": [
    { "id": "normal", "name": "Normal", "color": "white" },
    { "id": "fire", "name": "Fire", "color": "red" },
    { "id": "water", "name": "Water", "color": "blue" },
    { "id": "grass", "name": "Grass", "color": "brightGreen" },
    { "id": "ghost", "name": "Ghost", "color": "magenta" }
  ],
  "chart": {
    "fire": { "grass": 2, "water": 0.5, "fire": 0.5 },
    "water": { "fire": 2, "grass": 0.5 },
    "grass": { "water": 2, "fire": 0.5 },
    "normal": { "ghost": 0 }
  }
}
""".trimIndent()

private val BAKEMONS = """
{ "bakemons": [
  { "id": "embercub", "name": "Embercub", "types": ["fire"], "baseStats": { "hp": 45, "atk": 60, "def": 40, "spe": 65 },
    "abilities": ["blaze", "guts"], "moves": ["ember", "tackle", "growl", "swords_dance"], "description": "A cub." },
  { "id": "droplet", "name": "Droplet", "types": ["water"], "baseStats": { "hp": 50, "atk": 50, "def": 55, "spe": 45 },
    "abilities": ["torrent"], "moves": ["bubble", "tackle", "growl"] },
  { "id": "sprout", "name": "Sprout", "types": ["grass"], "baseStats": { "hp": 55, "atk": 45, "def": 50, "spe": 40 },
    "moves": ["vine_whip", "tackle"] },
  { "id": "spook", "name": "Spook", "types": ["ghost"], "baseStats": { "hp": 40, "atk": 55, "def": 45, "spe": 70 },
    "abilities": ["guts"], "moves": ["shadow_claw", "growl"] },
  { "id": "pebble", "name": "Pebble", "types": ["normal"], "baseStats": { "hp": 60, "atk": 30, "def": 70, "spe": 20 },
    "abilities": ["guts"], "moves": ["growl"] }
] }
""".trimIndent()

private val MOVES = """
{ "moves": [
  { "id": "tackle", "name": "Tackle", "type": "normal", "category": "physical", "power": 40, "accuracy": 100, "pp": 35, "flags": ["contact"] },
  { "id": "ember", "name": "Ember", "type": "fire", "category": "physical", "power": 40, "accuracy": 100, "pp": 25,
    "triggers": [ { "on": "hit", "chance": 10, "effects": [ { "type": "applyStatus", "status": "burn" } ] } ] },
  { "id": "bubble", "name": "Bubble", "type": "water", "category": "physical", "power": "user.spe > target.spe ? 50 : 40", "accuracy": 100 },
  { "id": "vine_whip", "name": "Vine Whip", "type": "grass", "category": "physical", "power": 45, "accuracy": 100, "hits": "randint(1, 2)" },
  { "id": "shadow_claw", "name": "Shadow Claw", "type": "ghost", "category": "physical", "power": 70, "accuracy": 100, "critBonus": 0.125 },
  { "id": "growl", "name": "Growl", "type": "normal", "category": "status", "accuracy": 100, "target": "allEnemies",
    "triggers": [ { "on": "hit", "effects": [ { "type": "statStage", "stat": "atk", "stages": -1 } ] } ] },
  { "id": "swords_dance", "name": "Swords Dance", "category": "status", "target": "self",
    "triggers": [ { "on": "hit", "effects": [
      { "type": "statStage", "target": "self", "stat": "atk", "stages": 2 },
      { "type": "applyStatus", "target": "self", "status": "boost", "duration": 2 } ] } ] },
  { "id": "struggle", "name": "Struggle", "category": "physical", "power": 50,
    "triggers": [ { "on": "afterMove", "effects": [ { "type": "damage", "target": "self", "amount": "floor(self.maxHp / 4)" } ] } ] }
] }
""".trimIndent()

private val ABILITIES = """
{ "abilities": [
  { "id": "blaze", "name": "Blaze", "description": "Fire moves hit harder at low HP.",
    "triggers": [ { "on": "modifyDamageDealt", "condition": "move.type == 'fire' && self.hpPercent <= 33",
                    "effects": [ { "type": "multiplyDamage", "value": 1.5 } ] } ] },
  { "id": "guts", "name": "Guts", "statModifiers": { "atk": "hasStatus(self, 'burn') ? 1.5 : 1" } },
  { "id": "torrent", "name": "Torrent",
    "triggers": [ { "on": "modifyDamageDealt", "condition": "move.type == 'water' && self.hpPercent <= 33",
                    "effects": [ { "type": "multiplyDamage", "value": 1.5 } ] } ] }
] }
""".trimIndent()

/** Bare-array form on purpose. */
private val ITEMS = """
[
  { "id": "leftovers", "name": "Leftovers",
    "triggers": [ { "on": "turnEnd", "condition": "self.hp < self.maxHp", "message": "{self} restored a little HP with its {item}!",
                    "effects": [ { "type": "heal", "amount": "max(1, floor(self.maxHp / 16))" } ] } ] },
  { "id": "power_band", "name": "Power Band", "statModifiers": { "atk": 1.2 } },
  { "id": "oran_berry", "name": "Oran Berry",
    "triggers": [ { "on": "damaged", "condition": "self.hpPercent <= 50", "limit": 1,
                    "effects": [ { "type": "heal", "amount": 10 }, { "type": "consumeItem" } ] } ] }
]
""".trimIndent()

private val STATUSES = """
{ "statuses": [
  { "id": "burn", "name": "Burn", "short": "BRN", "kind": "major", "immuneTypes": ["fire"],
    "statModifiers": { "atk": 0.5 },
    "triggers": [ { "on": "turnEnd", "effects": [ { "type": "damage", "target": "self", "amount": "max(1, floor(self.maxHp / 16))" } ] } ],
    "applyMessage": "{target} was burned!", "removeMessage": "{target}'s burn healed." },
  { "id": "boost", "name": "Boost", "kind": "buff", "duration": 3, "stacking": "stack", "maxStacks": 3,
    "statModifiers": { "spe": "1 + 0.25 * stacks" },
    "triggers": [ { "on": "turnEnd", "condition": "turnsLeft == 1", "effects": [ { "type": "message", "text": "{self}'s boost is fading." } ] } ] }
] }
""".trimIndent()

private val BASE = Fixture(
    mapOf(
        "Rules.json" to RULES, "Types.json" to TYPES, "Bakemons.json" to BAKEMONS, "Moves.json" to MOVES,
        "Abilities.json" to ABILITIES, "Items.json" to ITEMS, "Statuses.json" to STATUSES,
    ),
)

private val FIELDS = """
{ "fields": [
  { "id": "rain", "name": "Rain", "group": "weather", "duration": 5, "selectable": true,
    "triggers": [ { "on": "modifyDamageDealt", "condition": "move.type == 'water'", "effects": [ { "type": "multiplyDamage", "value": 1.5 } ] } ],
    "startMessage": "It started to rain!" },
  { "id": "reflect", "name": "Reflect", "scope": "side", "duration": "3 + stacks", "statModifiers": { "def": "1 + 0.5 * stacks" },
    "triggers": [ { "on": "damaged", "for": "opponents", "message": "{self} was hit near {field}", "effects": [] } ],
    "rules": { "blockStatus": "statusKind == 'major' && !sameSide(self, other)", "blockStatStage": "stages < 0 && stat == 'atk'",
               "blockMove": "move.id == 'growl' && fieldTurns('rain') > 0", "damage": "floor(move.power * attack / defense) + stacks",
               "critChance": "sideField(user, 'reflect') * 0", "disableItems": true },
    "startMessage": "Reflect raised {side}'s defense!", "endMessage": "{side}'s Reflect wore off." },
  { "id": "spikes", "scope": "side", "group": "hazards", "stacking": "stack", "maxStacks": 3,
    "triggers": [ { "on": "switchIn", "effects": [ { "type": "damage", "amount": "floor(self.maxHp * typeMultiplier('fire', self) / 8)" } ] } ] }
] }
""".trimIndent()

/** [BASE] with Fields.json, a move that sets and removes fields, and Rules.startFields. */
private val FIELD_BASE = BASE
    .set("Fields.json", FIELDS)
    .with(
        "Moves.json", "{ \"id\": \"struggle\"",
        "{ \"id\": \"rain_dance\", \"name\": \"Rain Dance\", \"category\": \"status\", \"target\": \"self\", \"triggers\": [ { \"on\": \"hit\", " +
            "\"effects\": [ { \"type\": \"setField\", \"field\": \"rain\" }, { \"type\": \"removeField\", \"group\": \"hazards\", \"side\": \"self\" } ] } ] },\n  " +
            "{ \"id\": \"struggle\"",
    )
    .with(
        "Rules.json", "\"fallbackMove\": \"struggle\",",
        "\"fallbackMove\": \"struggle\",\n  \"startFields\": [ { \"field\": \"spikes\", \"side\": \"side2\" }, { \"field\": \"rain\", \"duration\": 2 } ],",
    )

/** Only the required files, with as many defaults as possible. */
private val MINIMAL = Fixture(
    mapOf(
        "Rules.json" to """
            { "turnSystem": "action_bar", "stats": ["hp", "atk", "def", "spe"],
              "statFormulas": { "hp": "base - 1000", "def": "base - 1000" },
              "categories": { "physical": { "attack": "atk", "defense": "def" } },
              "damage": "move.power * attack / defense" }
        """.trimIndent(),
        "Types.json" to """{ "types": [ { "id": "normal" } ] }""",
        "Bakemons.json" to """[ { "id": "blob", "types": ["normal"], "baseStats": { "hp": 50, "atk": 60, "def": 70, "spe": 80 }, "moves": ["bonk"] } ]""",
        "Moves.json" to """[ { "id": "bonk", "category": "physical", "power": 10 } ]""",
    ),
)

private fun loadSetup(dir: File): Setup? = try {
    Setup.load(dir)
} catch (e: SetupException) {
    Check.ok(false, "${dir.name} should load but failed:\n${e.message}")
    null
}

/** Loads a broken variant of [fixture] and checks that the error message contains every fragment. Returns the message. */
private fun expectErrors(temp: File, name: String, fixture: Fixture, vararg fragments: String): String {
    val dir = fixture.write(temp, name)
    val message = try {
        Setup.load(dir)
        Check.ok(false, "$name: expected a SetupException")
        return ""
    } catch (e: SetupException) {
        e.message ?: ""
    }
    Check.ok(message.startsWith("Setup '$name' has "), "$name: first line should be \"Setup '$name' has N error(s):\" but was: ${message.lineSequence().first()}")
    for (fragment in fragments) Check.ok(fragment in message, "$name: errors should mention \"$fragment\" but were:\n$message")
    return message
}

/** N from the "Setup 'x' has N error(s):" header. */
private fun errorCount(message: String): Int =
    Regex("has (\\d+) error\\(s\\):").find(message)?.groupValues?.get(1)?.toInt() ?: -1

// ---------------------------------------------------------------------------------------------------------------
// Tests

private fun testValidSetup(temp: File) {
    val setup = loadSetup(BASE.write(temp, "Valid")) ?: return
    Check.eq(emptyList<String>(), setup.warnings, "a clean setup has no warnings")
    Check.eq("Valid", setup.name, "setup name = folder name")
    Check.eq("Test Rules", setup.displayName, "display name = Rules.name")
    Check.eq(listOf("normal", "fire", "water", "grass", "ghost"), setup.types.all().map { it.id }, "types in file order")
    Check.eq(listOf("embercub", "droplet", "sprout", "spook", "pebble"), setup.dex.all().map { it.id }, "species in file order")
    Check.eq(8, setup.moves.all().size, "moves")
    Check.eq(3, setup.abilities.all().size, "abilities")
    Check.eq(3, setup.items.all().size, "items (bare array)")
    Check.eq(2, setup.statuses.all().size, "statuses")

    val rules = setup.rules
    Check.eq(TurnSystem.SPEED_ORDER, rules.turnSystem, "turnSystem")
    Check.eq(3, rules.teamMax, "team.max")
    Check.ok(rules.speciesClause && rules.itemClause, "clauses")
    Check.eq(2, rules.maxMoves, "maxMoves")
    Check.eq(listOf("hp", "atk", "def", "spe"), rules.stats.map { it.id }, "stats")
    Check.eq("Attack", rules.statName("atk"), "statName")
    Check.eq("xyz", rules.statName("xyz"), "statName of unknown stat")
    Check.ok(rules.statPointsEnabled, "stat points enabled")
    Check.eq(100, rules.statPointsTotal, "statPoints.total")
    Check.eq(60, rules.statPointsMax, "statPoints.maxPerStat")
    Check.eq("struggle", rules.fallbackMove, "fallbackMove")
    Check.ok(rules.categories.getValue("physical").dealsDamage, "physical deals damage")
    Check.ok(!rules.categories.getValue("status").dealsDamage, "status deals no damage")
    Check.near(1.0 / 24, rules.critChance.eval(MapContext(emptyMap())), "critChance")

    val types = setup.types
    Check.near(2.0, types.multiplier("fire", listOf("grass")), "fire vs grass")
    Check.near(1.0, types.multiplier("fire", listOf("grass", "water")), "fire vs grass/water")
    Check.near(0.0, types.multiplier("normal", listOf("ghost")), "normal vs ghost")
    Check.near(1.0, types.multiplier(null, listOf("ghost")), "typeless")
    Check.near(1.0, types.multiplier("ghost", listOf("fire")), "missing pair")
    Check.eq("It's super effective!", types.message(2.0), "strong message")
    Check.eq("It's not very effective...", types.message(0.5), "weak message")
    Check.eq("It doesn't affect {target}...", types.message(0.0), "immune message")
    Check.eq(null, types.message(1.0), "neutral message")
    Check.eq("Fire", types.name("fire"), "type name")
    Check.eq("ice", types.name("ice"), "unknown type name")
    Check.eq("brightGreen", types.types["grass"]?.color, "type colour")

    val tackle = setup.moves.require("tackle")
    Check.eq(MoveTarget.ENEMY, tackle.target, "default move target")
    Check.eq(0, tackle.priority, "default priority")
    Check.eq(35, tackle.pp, "pp")
    Check.eq(1.0, tackle.hits.constant, "default hits")
    Check.eq(setOf("contact"), tackle.flags, "flags")
    Check.eq(100.0, tackle.accuracy, "accuracy")
    Check.ok(setup.isDamaging(tackle), "tackle is damaging")
    val growl = setup.moves.require("growl")
    Check.eq(null, growl.power, "status move has no power")
    Check.ok(!setup.isDamaging(growl), "growl is not damaging")
    Check.eq(MoveTarget.ALL_ENEMIES, growl.target, "growl target")
    Check.eq(1, growl.triggers.size, "growl triggers")
    val struggle = setup.moves.require("struggle")
    Check.eq(null, struggle.accuracy, "no accuracy = never misses")
    Check.eq(null, struggle.type, "typeless move")
    Check.eq(null, setup.moves.require("bubble").power?.constant, "formula power")
    Check.near(0.125, setup.moves.require("shadow_claw").critBonus?.constant ?: -1.0, "critBonus")

    val burn = setup.statuses.require("burn")
    Check.eq(StatusKind.MAJOR, burn.kind, "status kind")
    Check.eq("BRN", burn.short, "explicit short")
    Check.eq(setOf("fire"), burn.immuneTypes, "immuneTypes")
    Check.eq(null, burn.duration, "no duration = permanent")
    Check.eq(Stacking.IGNORE, burn.stacking, "default stacking")
    val boost = setup.statuses.require("boost")
    Check.eq("BOO", boost.short, "default short")
    Check.eq(Stacking.STACK, boost.stacking, "stacking")
    Check.eq(3, boost.maxStacks, "maxStacks")
    Check.eq(3.0, boost.duration?.constant, "duration")

    Check.eq(listOf("blaze", "guts"), setup.dex.require("embercub").abilities, "species abilities")
    Check.eq(listOf("embercub"), setup.dex.search("EMBER").map { it.id }, "search by name")
    Check.eq(listOf("droplet"), setup.dex.search("water").map { it.id }, "search by type")
    Check.eq(5, setup.dex.search("").size, "empty search = all")
    Check.throws<SetupException>("require unknown species") { setup.dex.require("nope") }
    Check.throws<SetupException>("require unknown move") { setup.moves.require("nope") }
    Check.throws<SetupException>("require unknown ability") { setup.abilities.require("nope") }
    Check.throws<SetupException>("require unknown item") { setup.items.require("nope") }
    Check.throws<SetupException>("require unknown status") { setup.statuses.require("nope") }
}

private fun testDefaults(temp: File) {
    val setup = loadSetup(MINIMAL.write(temp, "Minimal")) ?: return
    val r = setup.rules
    Check.eq("", r.name, "default name")
    Check.eq("Minimal", setup.displayName, "display name falls back to the folder name")
    Check.eq("", r.description, "default description")
    Check.eq(TurnSystem.ACTION_BAR, r.turnSystem, "turnSystem")
    Check.eq(listOf(1, 6, 1), listOf(r.teamMin, r.teamMax, r.activePerSide), "team defaults")
    Check.ok(!r.speciesClause && !r.itemClause, "clauses off by default")
    Check.ok(r.switching, "switching on by default")
    Check.eq(listOf(1, 100, 50), listOf(r.levelMin, r.levelMax, r.levelDefault), "level defaults")
    Check.eq(4, r.maxMoves, "maxMoves default")
    Check.ok(r.heldItems, "heldItems default")
    Check.eq("hp", r.hpStat, "hpStat default")
    Check.eq("spe", r.speedStat, "speedStat default")
    Check.eq(listOf("hp", "hp", "atk"), listOf(r.stats[0].id, r.stats[0].name, r.stats[1].id), "string stats")
    Check.eq("base", r.statFormula("atk").toString(), "default stat formula")
    Check.ok(!r.statPointsEnabled, "stat points disabled when absent")
    Check.eq(0, r.statPointsMax, "maxPerStat 0 when disabled")
    Check.eq(listOf(-6, 6), listOf(r.stageMin, r.stageMax), "stage defaults")
    Check.near(2.0, r.stageMultiplier.eval(MapContext(mapOf("stage" to 2.0))), "stage +2")
    Check.near(0.5, r.stageMultiplier.eval(MapContext(mapOf("stage" to -2.0))), "stage -2")
    Check.eq("move.accuracy / 100", r.hitChance.toString(), "default hitChance")
    Check.eq(0.0, r.critChance.constant, "default critChance")
    Check.eq(1.5, r.critMultiplier.constant, "default critMultiplier")
    Check.eq(1, r.minDamage, "default minDamage")
    Check.ok(r.usePP && !r.useCooldowns, "pp on, cooldowns off by default")
    Check.eq(null, r.fallbackMove, "no fallbackMove")
    Check.near(100.0, r.actionBarSize, "default bar size")
    Check.eq("speed", r.actionBarFill.toString(), "default bar fill")
    Check.eq(0.0, r.actionBarStart.constant, "default bar start")
    Check.eq(0, r.turnLimit, "default turnLimit")

    val type = setup.types.types.getValue("normal")
    Check.eq("normal", type.name, "type name defaults to id")
    Check.eq(null, type.color, "no colour")
    val bonk = setup.moves.require("bonk")
    Check.eq("bonk", bonk.name, "move name defaults to id")
    Check.eq(listOf<Any?>(null, 0, 0, 0, null), listOf(bonk.accuracy, bonk.priority, bonk.pp, bonk.cooldown, bonk.critBonus), "move defaults")
    Check.eq(MoveTarget.ENEMY, bonk.target, "default target")
    Check.eq(emptySet<String>(), bonk.flags, "no flags")
    Check.eq(0, setup.abilities.all().size + setup.items.all().size + setup.statuses.all().size, "optional files missing = empty")
    Check.ok(setup.warnings.any { it.startsWith("Abilities.json: file not found") }, "missing Abilities.json warns: ${setup.warnings}")
    Check.ok(setup.warnings.any { it.startsWith("Items.json: file not found") }, "missing Items.json warns: ${setup.warnings}")
    Check.ok(setup.warnings.none { "Statuses.json" in it }, "missing Statuses.json is silent: ${setup.warnings}")
    // Species without abilities: default member has no ability and is valid.
    val blob = Bakemon.default(setup.dex.require("blob"), setup)
    Check.eq(emptyList<String>(), blob.validate(setup), "default member of the minimal setup is valid")
}

private fun testErrors(temp: File) {
    expectErrors(temp, "UnknownType", BASE.with("Bakemons.json", "\"types\": [\"fire\"]", "\"types\": [\"fyre\"]"), "unknown type 'fyre'", "did you mean 'fire'")
    expectErrors(temp, "UnknownMove", BASE.with("Bakemons.json", "\"moves\": [\"ember\",", "\"moves\": [\"embr\","), "unknown move 'embr'")
    expectErrors(temp, "UnknownAbility", BASE.with("Bakemons.json", "[\"blaze\", \"guts\"]", "[\"blazee\", \"guts\"]"), "unknown ability 'blazee'")
    expectErrors(temp, "MissingBaseStat", BASE.with("Bakemons.json", "\"def\": 40, \"spe\": 65", "\"def\": 40"), "embercub", "baseStats is missing 'spe'")
    expectErrors(temp, "UnknownStatus", BASE.with("Moves.json", "\"status\": \"burn\"", "\"status\": \"burnn\""), "unknown status 'burnn'")
    expectErrors(temp, "BadVariable", BASE.with("Moves.json", "\"power\": 40, \"accuracy\": 100, \"pp\": 35", "\"power\": \"user.atkk\", \"accuracy\": 100, \"pp\": 35"),
        "Moves.json: moves[0] (tackle).power: unknown variable 'user.atkk'", "did you mean 'user.atk'")
    expectErrors(temp, "BadFunction", BASE.with("Abilities.json", "hasStatus(self, 'burn')", "hasStatuss(self, 'burn')"), "unknown function 'hasStatuss'")
    expectErrors(temp, "BadMoveEvent", BASE.with("Moves.json", "{ \"on\": \"afterMove\"", "{ \"on\": \"turnEnd\""), "struggle", "event 'turnEnd' cannot be used in a move trigger")
    val dup = expectErrors(temp, "DuplicateId", BASE.with("Moves.json", "{ \"id\": \"ember\"", "{ \"id\": \"tackle\", \"category\": \"physical\" },\n  { \"id\": \"ember\""), "duplicate id 'tackle'")
    Check.eq(1, errorCount(dup), "a duplicate id is one error:\n$dup")
    val syntax = expectErrors(temp, "BadSyntax", BASE.with("Moves.json", "\"pp\": 35, \"flags\"", "\"pp\": 35 \"flags\""), "Moves.json")
    Check.eq(1, errorCount(syntax), "a broken file is one error (no cascade of unknown moves):\n$syntax")
    expectErrors(temp, "UnknownStatStage", BASE.with("Moves.json", "\"stat\": \"atk\", \"stages\": -1", "\"stat\": \"attk\", \"stages\": -1"), "unknown stat 'attk'")
    expectErrors(temp, "UnknownCategory", BASE.with("Moves.json", "\"category\": \"physical\", \"power\": 70", "\"category\": \"special\", \"power\": 70"), "unknown category 'special'")
    expectErrors(temp, "UnknownMoveType", BASE.with("Moves.json", "\"type\": \"ghost\"", "\"type\": \"spirit\""), "unknown type 'spirit'")
    expectErrors(temp, "UnknownFallback", BASE.with("Rules.json", "\"fallbackMove\": \"struggle\"", "\"fallbackMove\": \"strugle\""), "fallbackMove: unknown move 'strugle'")
    expectErrors(temp, "NoMoves", BASE.with("Bakemons.json", "\"moves\": [\"growl\"]", "\"moves\": []"), "pebble", "has no moves")
    expectErrors(temp, "ChartType", BASE.with("Types.json", "\"normal\": { \"ghost\": 0 }", "\"normal\": { \"ghost\": -1, \"ice\": 2 }"), "unknown defending type 'ice'", "must be 0 or more")
    expectErrors(temp, "ImmuneType", BASE.with("Statuses.json", "\"immuneTypes\": [\"fire\"]", "\"immuneTypes\": [\"fyre\"]"), "unknown type 'fyre'")
    expectErrors(temp, "ModifierStat", BASE.with("Items.json", "\"statModifiers\": { \"atk\": 1.2 }", "\"statModifiers\": { \"spatk\": 1.2 }"), "statModifiers: unknown stat 'spatk'")
    expectErrors(temp, "ModifierScope", BASE.with("Items.json", "\"statModifiers\": { \"atk\": 1.2 }", "\"statModifiers\": { \"atk\": \"target.hp > 0 ? 1.2 : 1\" }"), "unknown variable 'target.hp'")
    expectErrors(temp, "StatusKindMissing", BASE.with("Statuses.json", "\"kind\": \"buff\", ", ""), "boost")
    expectErrors(temp, "BadEffectType", BASE.with("Items.json", "{ \"type\": \"consumeItem\" }", "{ \"type\": \"explode\" }"), "oran_berry")
    expectErrors(
        temp, "EventVariable",
        BASE.with("Statuses.json", "\"amount\": \"max(1, floor(self.maxHp / 16))\"", "\"amount\": \"max(1, floor(self.maxHp / 16)) + damage\""),
        "Statuses.json: statuses[0] (burn).triggers[0].effects[0].amount: variable 'damage' has no value in a 'turnEnd' trigger",
    )
    expectErrors(temp, "DurationVariable", BASE.with("Statuses.json", "\"duration\": 3,", "\"duration\": \"amount\","), "(boost).duration: variable 'amount' has no value in a status duration")
    expectErrors(temp, "MissingRules", BASE.without("Rules.json"), "Rules.json: file not found")
    expectErrors(temp, "MissingMoves", BASE.without("Moves.json"), "Moves.json: file not found")
    expectErrors(temp, "MissingDamage", MINIMAL.with("Rules.json", "\"damage\": \"move.power * attack / defense\"", "\"turnLimit\": 0"), "damage")

    val badRules = MINIMAL.set(
        "Rules.json",
        """
        { "turnSystem": "turn_based", "team": { "min": 3, "max": 2 }, "level": { "default": 200 },
          "maxMoves": 0, "minDamage": -1, "stats": ["hp", "atk", "def", "atk"], "hpStat": "health",
          "statFormulas": { "speed": "base" }, "categories": { "physical": { "attack": "atk", "defense": "def" } },
          "damage": "1", "actionBar": { "size": 0 } }
        """.trimIndent(),
    )
    val rulesMessage = expectErrors(
        temp, "BadRules", badRules,
        "unknown turn system 'turn_based'", "max (2) is smaller than min (3)", "200 is outside", "maxMoves: must be at least 1",
        "minDamage: must not be negative", "duplicate stat id 'atk'", "'health' is not one of the stats", "unknown stat 'speed'",
        "size: must be greater than 0", "speedStat: 'spe' is not one of the stats",
    )
    Check.eq(10, errorCount(rulesMessage), "every Rules.json problem is reported:\n$rulesMessage")

    // Several independent errors in different files are reported together.
    val several = BASE
        .with("Bakemons.json", "\"types\": [\"fire\"]", "\"types\": [\"fyre\"]")
        .with("Bakemons.json", "[\"torrent\"]", "[\"torent\"]")
        .with("Moves.json", "\"power\": 40, \"accuracy\": 100, \"pp\": 35", "\"power\": \"user.atkk\", \"accuracy\": 100, \"pp\": 35")
        .with("Statuses.json", "\"stacking\": \"stack\"", "\"stacking\": \"pile\"")
    val message = expectErrors(temp, "Several", several, "unknown type 'fyre'", "unknown ability 'torent'", "unknown variable 'user.atkk'", "unknown stacking 'pile'")
    Check.eq(4, errorCount(message), "four errors:\n$message")
    Check.eq(5, message.lines().size, "one header line + one line per error")
}

private fun testWarnings(temp: File) {
    val fixture = BASE
        .with("Rules.json", "\"maxMoves\": 2,", "\"maxMoves\": 2, \"turnLimt\": 5,")
        .with("Rules.json", "\"active\": 1,", "\"active\": 1, \"size\": 3,")
        .with("Types.json", "\"name\": \"Fire\",", "\"name\": \"Fire\", \"colour\": \"red\",")
        .with("Types.json", "\"color\": \"blue\"", "\"color\": \"navy\"")
        .with("Bakemons.json", "\"description\": \"A cub.\"", "\"description\": \"A cub.\", \"desc\": \"x\"")
        .with("Moves.json", "\"pp\": 35,", "\"pp\": 35, \"acuracy\": 90,")
        .with("Items.json", "\"condition\": \"self.hp < self.maxHp\",", "\"condition\": \"self.hp < self.maxHp\", \"condtion\": \"1\",")
        .with("Items.json", "{ \"type\": \"heal\", \"amount\": \"max(1", "{ \"type\": \"heal\", \"amout\": 3, \"amount\": \"max(1")
        .with("Items.json", "{ \"type\": \"heal\", \"amount\": 10 }", "{ \"type\": \"heal\", \"amount\": \"damage\" }, { \"type\": \"multiplyDamage\", \"value\": 2 }")
        .with("Statuses.json", "\"maxStacks\": 3,", "\"maxStacks\": 3, \"stack\": 2,")
        .with("Statuses.json", "{self}'s boost", "{slef}'s boost")
        .with("Statuses.json", "\"duration\": 3,", "\"duration\": \"2 + stacks + turnsLeft\",")
        .with("Bakemons.json", "\"id\": \"droplet\",", "\"id\": \"droplet\", \"image\": \"Assets/droplet.png\",")
    val setup = loadSetup(fixture.write(temp, "Warnings")) ?: return
    val warnings = setup.warnings
    fun expectWarning(fragment: String) = Check.ok(warnings.any { fragment in it }, "expected a warning with \"$fragment\" in:\n${warnings.joinToString("\n")}")
    expectWarning("Rules.json: unknown key 'turnLimt' (did you mean 'turnLimit'?)")
    expectWarning("Rules.json.team: unknown key 'size'")
    expectWarning("Types.json: types[1] (fire): unknown key 'colour' (did you mean 'color'?)")
    expectWarning("unknown colour 'navy'")
    expectWarning("Bakemons.json: bakemons[0] (embercub): unknown key 'desc'")
    expectWarning("Moves.json: moves[0] (tackle): unknown key 'acuracy' (did you mean 'accuracy'?)")
    expectWarning("Items.json: [0] (leftovers).triggers[0]: unknown key 'condtion' (did you mean 'condition'?)")
    expectWarning("Items.json: [0] (leftovers).triggers[0].effects[0]: unknown key 'amout' for effect 'heal'")
    expectWarning("Statuses.json: statuses[1] (boost): unknown key 'stack'")
    expectWarning("unknown placeholder '{slef}'")
    expectWarning("multiplyDamage only works in modifyDamageDealt / modifyDamageTaken")
    expectWarning("Bakemons.json: bakemons[1] (droplet): image 'Assets/droplet.png' not found")
    Check.eq(12, warnings.size, "one warning per problem:\n${warnings.joinToString("\n")}")
}

/** Placeholders are checked per kind of message; statModifiers on HP and unknown colours are reported. */
private fun testMessageChecks(temp: File) {
    val fixture = BASE
        .with("Statuses.json", "\"applyMessage\": \"{target} was burned!\"", "\"applyMessage\": \"{target} has {stacks} stack(s) of {status} from {move}!\"")
        .with("Types.json", "\"normal\": { \"ghost\": 0 }\n  }", "\"normal\": { \"ghost\": 0 }\n  },\n  \"messages\": { \"weak\": \"{user}'s {move} was resisted by {target}...\" }")
        .with("Items.json", "\"statModifiers\": { \"atk\": 1.2 }", "\"statModifiers\": { \"atk\": 1.2, \"hp\": 2 }")
        .with("Types.json", "\"color\": \"white\"", "\"color\": \"brightGray\"")
        .with("Types.json", "\"color\": \"magenta\"", "\"color\": \"grey\"")
    val setup = loadSetup(fixture.write(temp, "MessageChecks")) ?: return
    val warnings = setup.warnings
    fun has(fragment: String) = warnings.any { fragment in it }
    Check.ok(has("(burn).applyMessage: placeholder '{move}' is not filled"), "{move} in a status message is reported: $warnings")
    Check.ok(!has("'{stacks}'"), "{stacks} is fine in a status message: $warnings")
    Check.ok(has("messages.weak: placeholder '{user}'") && has("messages.weak: placeholder '{move}'"), "type messages only fill {target}: $warnings")
    Check.ok(has("(power_band).statModifiers.hp: statModifiers do not change max HP"), "statModifiers on HP are reported: $warnings")
    Check.ok(warnings.none { "colour" in it }, "brightGray and grey are known colours: $warnings")
    Check.eq(4, warnings.size, "one warning per problem:\n${warnings.joinToString("\n")}")

    // Trigger and effect messages are checked against their event: placeholders that can never be filled are reported.
    val events = BASE
        .with("Items.json", "\"message\": \"{self} restored a little HP with its {item}!\"", "\"message\": \"{self} took {damage} and healed {amount} after {move} by {other}!\"")
        .with("Items.json", "{ \"type\": \"consumeItem\" }", "{ \"type\": \"consumeItem\", \"message\": \"{self} ate its {item} after {damage} damage from {move} ({other})!\" }")
        .with("Statuses.json", "\"text\": \"{self}'s boost is fading.\"", "\"text\": \"{other}'s {status} on {self} is fading.\"")
    val eventSetup = loadSetup(events.write(temp, "EventMessages")) ?: return
    val eventWarnings = eventSetup.warnings
    fun warned(fragment: String) = eventWarnings.any { fragment in it }
    Check.ok(warned("(leftovers).triggers[0].message: placeholder '{damage}' is not filled in an ability or item 'turnEnd' trigger"),
        "{damage} in a turnEnd message is reported: $eventWarnings")
    for (name in listOf("amount", "move", "other")) {
        Check.ok(warned("(leftovers).triggers[0].message: placeholder '{$name}'"), "{$name} in an item's turnEnd message is reported: $eventWarnings")
    }
    Check.ok(!warned("(oran_berry)"), "damaged fills {damage}, may carry a move and has another monster: $eventWarnings")
    Check.ok(!warned("(boost)"), "a status's turnEnd trigger fills {status} and {other} (who applied it): $eventWarnings")
    Check.eq(4, eventWarnings.size, "one warning per unfilled placeholder:\n${eventWarnings.joinToString("\n")}")

    // Every colour Types.json accepts is one the terminal can draw.
    for (name in bakemon.TypeChart.COLORS) {
        Check.ok(ui.Terminal.knowsColor(name) && ui.Terminal.knowsColor("bright" + name.replaceFirstChar { it.uppercase() }), "colour '$name' renders")
    }

    // An empty Bakedex is an error (no team could ever be built).
    expectErrors(temp, "EmptyDex", BASE.set("Bakemons.json", "{ \"bakemons\": [] }"), "the Bakedex is empty")
}

private fun testCheckFormula(temp: File) {
    val setup = loadSetup(BASE.write(temp, "Formulas")) ?: return
    val rules = setup.rules
    fun problems(source: String, scope: FormulaScope, statusContext: Boolean = false) =
        Setup.checkFormula(Formula.parse(source), scope, rules, "here", statusContext)
    fun accepts(source: String, scope: FormulaScope, statusContext: Boolean = false) =
        Check.eq(emptyList<String>(), problems(source, scope, statusContext), "$scope accepts '$source'")
    fun rejects(source: String, scope: FormulaScope, name: String, statusContext: Boolean = false) {
        val p = problems(source, scope, statusContext)
        Check.ok(p.size == 1 && p[0].startsWith("here: unknown ") && "'$name'" in p[0] && "formula '$source'" in p[0],
            "$scope rejects '$name' in '$source': $p")
    }

    accepts("floor((2 * base + floor(points / 4)) * level / 100) + 5", FormulaScope.STAT)
    rejects("base + user.atk", FormulaScope.STAT, "user.atk")
    rejects("base + turn", FormulaScope.STAT, "turn")
    rejects("floor(base) + hasFlag('contact')", FormulaScope.STAT, "hasFlag")
    accepts("stage >= 0 ? (2 + stage) / 2 : 2 / (2 - stage)", FormulaScope.STAGE)
    rejects("stage * base", FormulaScope.STAGE, "base")

    accepts(
        "attack / defense * effectiveness * crit * (sameType ? 1.5 : 1) * targets + isCrit + user.level + target.def" +
            " + self.raw.atk + other.base.spe + move.power + turn + allies + enemies + user.stage.atk + target.hpPercent",
        FormulaScope.DAMAGE,
    )
    rejects("attack * damage", FormulaScope.DAMAGE, "damage")
    rejects("attack * speed", FormulaScope.DAMAGE, "speed")
    accepts("attack * hitType", FormulaScope.DAMAGE)
    accepts("effectiveness > 1 && !isCrit ? 100 : 0 + sameType + crit + targets + user.atk + move.power + turn", FormulaScope.HIT_TYPE)
    rejects("attack * 2", FormulaScope.HIT_TYPE, "attack")
    rejects("defense * 2", FormulaScope.HIT_TYPE, "defense")
    rejects("hitType == 'x'", FormulaScope.HIT_TYPE, "hitType")
    rejects("damage", FormulaScope.HIT_TYPE, "damage")
    accepts("effectiveness > 1 ? 0.3 : (sameType ? 0.2 : 0.1) + user.spe / target.spe + move.accuracy + turn", FormulaScope.HIT)
    rejects("isCrit", FormulaScope.HIT, "isCrit")
    rejects("crit * 2", FormulaScope.HIT, "crit")
    rejects("targets", FormulaScope.HIT, "targets")
    // A move's power and hits are evaluated without the type matchup.
    rejects("effectiveness * 40", FormulaScope.MOVE, "effectiveness")
    rejects("sameType ? 2 : 1", FormulaScope.MOVE, "sameType")
    Check.ok(" (not available in this formula)" in problems("effectiveness * 40", FormulaScope.MOVE).single(), "effectiveness in a power formula: not available")
    accepts("user.spe > target.spe ? 80 : 40", FormulaScope.MOVE)
    accepts("move.accuracy / 100 + move.priority + move.pp + move.cooldown + (move.type == 'fire') + (move.category == 'x')", FormulaScope.MOVE)
    accepts("hasType(target, 'fire') ? 2 : random(1, 2)", FormulaScope.MOVE)
    rejects("attack + 1", FormulaScope.MOVE, "attack")
    rejects("move.foo", FormulaScope.MOVE, "move.foo")
    rejects("user.raw", FormulaScope.MOVE, "user.raw")
    rejects("user.base.xyz", FormulaScope.MOVE, "user.base.xyz")
    rejects("user.atkk", FormulaScope.MOVE, "user.atkk")

    accepts("speed * 2 + self.spe + turn + self.gauge + self.shield", FormulaScope.ACTION_BAR)
    rejects("user.spe", FormulaScope.ACTION_BAR, "user.spe")
    rejects("target.hp", FormulaScope.ACTION_BAR, "target.hp")
    rejects("move.power", FormulaScope.ACTION_BAR, "move.power")
    accepts("self.hpPercent < 50 && hasStatus(self, 'burn') ? 1.5 : 1", FormulaScope.MODIFIER)
    rejects("stacks", FormulaScope.MODIFIER, "stacks")
    rejects("other.atk", FormulaScope.MODIFIER, "other.atk")
    accepts("1 + 0.5 * stacks + turnsLeft + self.atk", FormulaScope.STATUS_MODIFIER)
    rejects("user.atk", FormulaScope.STATUS_MODIFIER, "user.atk")

    accepts(
        "damage + amount + isCrit + effectiveness + target.hp + user.stage.atk + (move.type == 'fire') + (status == 'burn')" +
            " + self.fainted + (other.name == 'x') + (self.id == 'y') + user.raw.hp + hasFlag('contact') + statusStacks(target, 'burn')" +
            " + hasAbility(self, 'blaze') + hasItem(self) + hasItem(other, 'leftovers') + chance(10) + randint(1, 3) + clamp(1, 0, 2)",
        FormulaScope.TRIGGER,
    )
    rejects("stacks + 1", FormulaScope.TRIGGER, "stacks")
    accepts("stacks + turnsLeft", FormulaScope.TRIGGER, statusContext = true)
    rejects("attack", FormulaScope.TRIGGER, "attack")
    rejects("foo(1)", FormulaScope.TRIGGER, "foo")
    rejects("speed", FormulaScope.TRIGGER, "speed")
    Check.eq(2, problems("user.atkk + nope(1)", FormulaScope.TRIGGER).size, "one message per unknown name")

    // Battle functions are arity-checked when the setup loads (not mid-battle).
    Check.eq(
        listOf("here: hasType() takes 2 arguments but got 1 in formula 'hasType(self)'"),
        problems("hasType(self)", FormulaScope.TRIGGER), "hasType with 1 argument",
    )
    Check.eq(
        listOf("here: hasItem() takes 1 or 2 arguments but got 0 in formula 'hasItem() + hasItem(self, 'x', 1)'",
            "here: hasItem() takes 1 or 2 arguments but got 3 in formula 'hasItem() + hasItem(self, 'x', 1)'"),
        problems("hasItem() + hasItem(self, 'x', 1)", FormulaScope.TRIGGER), "hasItem with 0 and 3 arguments",
    )
    Check.ok(problems("hasFlag() + statusStacks(self)", FormulaScope.MOVE).size == 2, "hasFlag() and statusStacks(self)")
    accepts("hasItem(self) + hasItem(self, 'x') + hasFlag('contact')", FormulaScope.TRIGGER)
    expectErrors(temp, "BadArity", BASE.with("Abilities.json", "hasStatus(self, 'burn')", "hasStatus(self)"), "hasStatus() takes 2 arguments but got 1")

    // Stat formulas are computed outside the battle's seeded random: no dice there.
    val dice = problems("base + randint(0, 31)", FormulaScope.STAT)
    Check.ok(dice.size == 1 && "randint() cannot be used in a stat formula" in dice[0], "random function in a stat formula: $dice")
    accepts("random(0.9, 1)", FormulaScope.DAMAGE)
    // Nor in statModifiers: effective stats are read by previews, the UI and the AI, which must not roll the battle's dice.
    val modifierDice = problems("random(0.9, 1.1)", FormulaScope.MODIFIER)
    Check.ok(modifierDice.size == 1 && "random() cannot be used in statModifiers" in modifierDice[0], "random in an ability/item modifier: $modifierDice")
    val statusDice = problems("chance(50) ? 2 : 1", FormulaScope.STATUS_MODIFIER)
    Check.ok(statusDice.size == 1 && "chance() cannot be used in statModifiers" in statusDice[0], "chance in a status modifier: $statusDice")
    expectErrors(temp, "RandomModifier", BASE.with("Items.json", "\"statModifiers\": { \"atk\": 1.2 }", "\"statModifiers\": { \"atk\": \"randint(1, 2)\" }"),
        "(power_band).statModifiers.atk: randint() cannot be used in statModifiers")
}

/** Rules.json "hitTypes" and the type matchup in the hit chance / crit formulas. */
private fun testHitTypes(temp: File) {
    val crushing = """{ "id": "crushing", "chance": "effectiveness > 1 && !isCrit ? 100 : 0", "multiplier": 1.3, "message": "Crushing hit on {target}!" }"""
    val glancing = """{ "id": "glancing", "chance": "effectiveness < 1 ? 50 : 0", "multiplier": "0.7", "cancelsCrit": true }"""
    fun withHitTypes(vararg entries: String, base: Fixture = BASE) =
        base.with("Rules.json", "\"fallbackMove\": \"struggle\",", "\"fallbackMove\": \"struggle\", \"hitTypes\": [ ${entries.joinToString(", ")} ],")

    val valid = withHitTypes(crushing, glancing, """{ "id": "plain_luck", "chance": 5 }""")
        .with("Rules.json", "\"critChance\": \"1 / 24\"", "\"critChance\": \"effectiveness > 1 ? 0.2 : 1 / 24\", \"critMultiplier\": \"sameType ? 2 : 1.5\", \"hitChance\": \"effectiveness < 1 ? 0.9 : 1\"")
        .with("Rules.json", "* crit * random(0.85, 1)", "* crit * random(0.85, 1) * (hitType == 'glancing' ? 0.9 : 1)")
        .with("Moves.json", "\"critBonus\": 0.125", "\"critBonus\": \"effectiveness > 1 ? 0.125 : 0\"")
        .with("Moves.json", "{ \"on\": \"hit\", \"chance\": 10,", "{ \"on\": \"hit\", \"chance\": \"hitType == 'crushing' ? 30 : 10\",")
        .with("Abilities.json", "\"condition\": \"move.type == 'water' && self.hpPercent <= 33\"", "\"condition\": \"hitType != '' || move.type == 'water' && self.hpPercent <= 33\"")
    val setup = loadSetup(valid.write(temp, "HitTypes")) ?: return
    Check.eq(emptyList<String>(), setup.warnings, "a setup with hit types has no warnings")
    val types = setup.rules.hitTypes
    Check.eq(listOf("crushing", "glancing", "plain_luck"), types.map { it.id }, "hit types in file order")
    Check.ok(types[0].multiplier.constant == 1.3 && types[0].message == "Crushing hit on {target}!" && !types[0].cancelsCrit, "crushing parsed")
    Check.ok(types[1].cancelsCrit && types[1].multiplier.toString() == "0.7", "glancing parsed")
    Check.ok(types[2].multiplier.constant == 1.0 && types[2].message == null && !types[2].cancelsCrit, "defaults: multiplier 1, no message, no crit cancel")
    Check.eq(emptyList<Any>(), loadSetup(BASE.write(temp, "NoHitTypes"))?.rules?.hitTypes, "no hitTypes by default")

    // Errors: ids, duplicates, shapes, formula syntax and scopes, event variables.
    expectErrors(temp, "HitTypeId", withHitTypes("""{ "id": "crushing hit", "chance": 10 }"""), "Rules.json.hitTypes[0]: hit type id 'crushing hit' must be a simple name")
    expectErrors(temp, "HitTypeEmptyId", withHitTypes("""{ "id": "", "chance": 10 }"""), "hit type id '' must be a simple name")
    val duplicate = expectErrors(temp, "HitTypeDuplicate", withHitTypes(crushing, crushing), "Rules.json.hitTypes[1]: duplicate hit type id 'crushing'")
    Check.eq(1, errorCount(duplicate), "a duplicate hit type is one error:\n$duplicate")
    expectErrors(temp, "HitTypeNoId", withHitTypes("""{ "chance": 10 }"""), "Rules.json.hitTypes[0]: missing required field 'id'")
    expectErrors(temp, "HitTypeNotObject", withHitTypes("\"crushing\""), "Rules.json.hitTypes[0]: expected an object")
    expectErrors(
        temp, "HitTypesNotArray",
        BASE.with("Rules.json", "\"fallbackMove\": \"struggle\",", "\"fallbackMove\": \"struggle\", \"hitTypes\": { \"id\": \"crushing\" },"),
        "Rules.json.hitTypes: expected an array",
    )
    expectErrors(temp, "HitTypeSyntax", withHitTypes("""{ "id": "crushing", "chance": "10 +" }"""), "Rules.json.hitTypes[0] (crushing).chance: ")
    expectErrors(temp, "HitTypeCancels", withHitTypes("""{ "id": "glancing", "chance": 10, "cancelsCrit": "yes" }"""),
        "Rules.json.hitTypes[0] (glancing).cancelsCrit: expected true or false")
    expectErrors(temp, "HitTypeMessage", withHitTypes("""{ "id": "glancing", "chance": 10, "message": 3 }"""), "(glancing).message: expected a string")
    expectErrors(temp, "HitTypeScope", withHitTypes("""{ "id": "crushing", "chance": "attack > defense ? 10 : 0", "multiplier": "hitType == 'x' ? 2 : 1" }"""),
        "Rules.json.hitTypes[0] (crushing).chance: unknown variable 'attack'", "Rules.json.hitTypes[0] (crushing).chance: unknown variable 'defense'",
        "Rules.json.hitTypes[0] (crushing).multiplier: unknown variable 'hitType'")
    expectErrors(temp, "HitTypeUnknownVar", withHitTypes("""{ "id": "crushing", "chance": "efectiveness > 1 ? 10 : 0" }"""),
        "unknown variable 'efectiveness'", "did you mean 'effectiveness'")
    expectErrors(temp, "PowerMatchup", BASE.with("Moves.json", "\"power\": 70,", "\"power\": \"effectiveness > 1 ? 90 : 70\","),
        "(shadow_claw).power: unknown variable 'effectiveness'", "(not available in this formula)")
    expectErrors(temp, "HitsMatchup", BASE.with("Moves.json", "\"hits\": \"randint(1, 2)\"", "\"hits\": \"sameType ? 2 : 1\""),
        "(vine_whip).hits: unknown variable 'sameType'")
    expectErrors(temp, "CritChanceScope", BASE.with("Rules.json", "\"critChance\": \"1 / 24\"", "\"critChance\": \"isCrit + hitType\""),
        "Rules.json.critChance: unknown variable 'hitType'", "Rules.json.critChance: unknown variable 'isCrit'")
    expectErrors(temp, "HitTypeEvent", BASE.with("Items.json", "\"condition\": \"self.hp < self.maxHp\"", "\"condition\": \"self.hp < self.maxHp && hitType == ''\""),
        "(leftovers).triggers[0].condition: variable 'hitType' has no value in a 'turnEnd' trigger (it is set only in: hit, modifyDamageDealt, modifyDamageTaken)")

    // Warnings: unknown keys, a hit type without a chance, placeholders a hit type message cannot fill.
    val warned = loadSetup(
        withHitTypes(
            """{ "id": "crushing", "chanse": 10, "multipler": 2 }""",
            """{ "id": "glancing", "chance": 10, "message": "{self} glanced off {target} ({damage})!" }""",
        ).write(temp, "HitTypeWarnings"),
    ) ?: return
    val warnings = warned.warnings
    fun expectWarning(fragment: String) = Check.ok(warnings.any { fragment in it }, "expected a warning with \"$fragment\" in:\n${warnings.joinToString("\n")}")
    expectWarning("Rules.json.hitTypes[0] (crushing): unknown key 'chanse' (did you mean 'chance'?)")
    expectWarning("Rules.json.hitTypes[0] (crushing): unknown key 'multipler' (did you mean 'multiplier'?)")
    expectWarning("Rules.json.hitTypes[0] (crushing): no \"chance\", so this hit type never happens")
    expectWarning("Rules.json.hitTypes[1] (glancing).message: placeholder '{self}' is not filled in a hit type message (available here: {user}, {target}, {move})")
    expectWarning("Rules.json.hitTypes[1] (glancing).message: placeholder '{damage}' is not filled in a hit type message")
    Check.eq(5, warnings.size, "one warning per problem:\n${warnings.joinToString("\n")}")
}

private fun testFields(temp: File) {
    val setup = loadSetup(FIELD_BASE.write(temp, "Fields")) ?: return
    Check.ok(setup.warnings.isEmpty(), "no warnings:\n${setup.warnings.joinToString("\n")}")
    Check.eq(listOf("rain", "reflect", "spikes"), setup.fields.all().map { it.id }, "fields in file order")
    val rain = setup.fields.require("rain")
    val reflect = setup.fields.require("reflect")
    val spikes = setup.fields.require("spikes")
    Check.ok(rain.scope == FieldScope.GLOBAL && reflect.scope == FieldScope.SIDE, "scopes (global by default)")
    Check.eq(listOf("rain"), setup.fields.selectable().map { it.id }, "selectable")
    Check.eq(setOf("weather", "hazards"), setup.fields.groups, "groups")
    Check.ok(spikes.name == "spikes" && spikes.short == "SPIK" && spikes.stacking == Stacking.STACK && spikes.maxStacks == 3, "defaults and stacking")
    Check.ok(spikes.duration == null && rain.duration?.constant == 5.0, "durations")
    Check.eq(FieldAudience.OPPONENTS, reflect.triggers.single().audience, "audience")
    Check.ok(reflect.rules.blockStatus != null && reflect.rules.blockMove != null && reflect.rules.damage != null, "rules parsed")
    Check.eq(1.0, reflect.rules.disableItems?.constant, "a JSON boolean as a rule")
    Check.ok(!reflect.rules.reverseSpeed && rain.rules.isEmpty, "absent rules")
    Check.eq(listOf("spikes", "rain"), setup.rules.startFields.map { it.field }, "startFields")
    Check.ok(setup.rules.startFields[0].side == 1 && setup.rules.startFields[0].permanent, "side2, permanent without a duration")
    Check.ok(setup.rules.startFields[1].side == null && !setup.rules.startFields[1].permanent, "a duration: not permanent")
    Check.ok(loadSetup(BASE.write(temp, "NoFields"))?.fields?.all()?.isEmpty() == true, "Fields.json is optional")
    // checkFormula checks literal field ids only when given the fields.
    val formula = Formula.parse("fieldActive('rian') + sideFieldStacks(self, 'reflect')")
    Check.ok(Setup.checkFormula(formula, FormulaScope.TRIGGER, setup.rules, "w", fields = setup.fields).single().contains("unknown field 'rian' in fieldActive()"), "literal field id")
    Check.ok(Setup.checkFormula(formula, FormulaScope.TRIGGER, setup.rules, "w").isEmpty(), "no fields given: no id check")
    Check.eq(setOf(0 to "rian"), formula.stringArguments("fieldActive"), "string arguments")
}

private fun testFieldErrors(temp: File) {
    expectErrors(temp, "FieldUnknownSet", FIELD_BASE.with("Moves.json", "\"field\": \"rain\" }", "\"field\": \"rainn\" }"), "unknown field 'rainn'", "did you mean 'rain'")
    expectErrors(temp, "FieldUnknownGroup", FIELD_BASE.with("Moves.json", "\"group\": \"hazards\"", "\"group\": \"hazard\""), "no field has the group 'hazard'")
    expectErrors(temp, "FieldRemoveBoth", FIELD_BASE.with("Moves.json", "\"group\": \"hazards\"", "\"field\": \"spikes\", \"group\": \"hazards\""), "removeField needs either")
    expectErrors(temp, "FieldUnknownStart", FIELD_BASE.with("Rules.json", "\"field\": \"spikes\"", "\"field\": \"spike\""), "Rules.json.startFields[0]: unknown field 'spike'")
    expectErrors(temp, "FieldStartSide", FIELD_BASE.with("Rules.json", "\"side\": \"side2\"", "\"side\": \"side3\""), "unknown side 'side3'")
    expectErrors(
        temp, "FieldOpponentsGlobal", FIELD_BASE.with("Fields.json", "\"on\": \"modifyDamageDealt\"", "\"on\": \"modifyDamageDealt\", \"for\": \"opponents\""),
        "\"opponents\" only works in side fields",
    )
    expectErrors(temp, "FieldBadScope", FIELD_BASE.with("Fields.json", "\"scope\": \"side\", \"duration\"", "\"scope\": \"team\", \"duration\""), "unknown scope 'team'")
    expectErrors(temp, "FieldBadFor", FIELD_BASE.with("Fields.json", "\"for\": \"opponents\"", "\"for\": \"enemies\""), "unknown value 'enemies'")
    expectErrors(temp, "FieldBadStacking", FIELD_BASE.with("Fields.json", "\"stacking\": \"stack\"", "\"stacking\": \"pile\""), "unknown stacking 'pile'")
    expectErrors(temp, "FieldLiteral", FIELD_BASE.with("Fields.json", "move.type == 'water'", "move.type == 'water' && fieldActive('rian')"), "unknown field 'rian' in fieldActive()")
    expectErrors(temp, "FieldArity", FIELD_BASE.with("Fields.json", "move.type == 'water'", "sideField('rain')"), "sideField() takes 2 arguments but got 1")
    expectErrors(temp, "FieldBlockScope", FIELD_BASE.with("Fields.json", "stat == 'atk'", "status == 'burn'"), "(reflect).rules.blockStatStage: unknown variable 'status'")
    expectErrors(temp, "FieldBlockDice", FIELD_BASE.with("Fields.json", "move.id == 'growl'", "random() < 0.5"), "random() cannot be used in a blockMove rule")
    expectErrors(temp, "FieldModifierStat", FIELD_BASE.with("Fields.json", "\"statModifiers\": { \"def\"", "\"statModifiers\": { \"dfe\""), "statModifiers: unknown stat 'dfe'")
    expectErrors(temp, "FieldTypeLiteral", FIELD_BASE.with("Fields.json", "typeMultiplier('fire'", "typeMultiplier('fyre'"), "unknown type 'fyre' in typeMultiplier()")
    expectErrors(
        temp, "FieldEventVariable", FIELD_BASE.with("Fields.json", "move.type == 'water'", "field == 'rain'"),
        "variable 'field' has no value in a 'modifyDamageDealt' trigger",
    )
    expectErrors(temp, "FieldDuplicate", FIELD_BASE.with("Fields.json", "\"id\": \"spikes\"", "\"id\": \"rain\""), "duplicate id 'rain'")
    expectErrors(temp, "FieldReserved", FIELD_BASE.with("Fields.json", "\"id\": \"spikes\"", "\"id\": \"all\""), "'all' is reserved")
}

private fun testFieldWarnings(temp: File) {
    val fixture = FIELD_BASE
        .with("Fields.json", "\"blockStatus\"", "\"reverseSpeed\": true, \"damge\": 1, \"blockStatus\"")
        .with("Fields.json", "\"selectable\": true,", "\"selectable\": true, \"scop\": \"global\",")
        .with("Fields.json", "\"for\": \"opponents\",", "\"for\": \"opponents\", \"forr\": 1,")
        .with("Fields.json", "It started to rain!", "It started to rain on {sidee}!")
        .with("Fields.json", "move.type == 'water'", "move.type == 'water' || fieldStacks('reflect') > 0")
        .with("Fields.json", "\"maxStacks\": 3,", "\"maxStacks\": 3, \"rules\": { \"blockMoveMessage\": \"x\" },")
        .with("Moves.json", "\"field\": \"rain\" }", "\"field\": \"rain\", \"side\": \"enemy\" }")
    val setup = loadSetup(fixture.write(temp, "FieldWarnings")) ?: return
    val warnings = setup.warnings
    fun expectWarning(fragment: String) = Check.ok(warnings.any { fragment in it }, "expected a warning with \"$fragment\" in:\n${warnings.joinToString("\n")}")
    expectWarning("(reflect).rules.reverseSpeed: only global fields can reverse the turn order")
    expectWarning("(reflect).rules: unknown key 'damge' (did you mean 'damage'?)")
    expectWarning("Fields.json: fields[0] (rain): unknown key 'scop' (did you mean 'scope'?)")
    expectWarning("(reflect).triggers[0]: unknown key 'forr' (did you mean 'for'?)")
    expectWarning("unknown placeholder '{sidee}'")
    expectWarning("fieldStacks('reflect') always reads 0 because 'reflect' is a side field")
    expectWarning("blockMoveMessage: unused without \"blockMove\"")
    expectWarning("side: ignored because 'rain' is a global field")
    Check.eq(8, warnings.size, "one warning per problem:\n${warnings.joinToString("\n")}")
}

private fun testStats(temp: File) {
    val setup = loadSetup(BASE.write(temp, "Stats")) ?: return
    val cub = Bakemon("embercub", level = 50, ability = "blaze", moves = listOf("ember"), points = mapOf("atk" to 40))
    val stats = cub.stats(setup)
    Check.eq(listOf("hp", "atk", "def", "spe"), stats.keys.toList(), "every stat, in Rules order")
    Check.near(105.0, stats.getValue("hp"), "hp = floor((2*45 + 0) * 50 / 100) + 50 + 10")
    Check.near(70.0, stats.getValue("atk"), "atk = floor((2*60 + floor(40/4)) * 50 / 100) + 5")
    Check.near(45.0, stats.getValue("def"), "def")
    Check.near(70.0, stats.getValue("spe"), "spe")
    Check.eq("Embercub", cub.displayName(setup), "display name = species name")
    Check.eq("Sparky", cub.copy(nickname = "Sparky").displayName(setup), "display name = nickname")
    Check.eq("ghost_mon", cub.copy(species = "ghost_mon").displayName(setup), "unknown species shows its id")
    Check.throws<SetupException>("stats of an unknown species") { cub.copy(species = "ghost_mon").stats(setup) }

    val minimal = loadSetup(MINIMAL.write(temp, "StatsMinimal")) ?: return
    val blob = Bakemon("blob", level = 50, moves = listOf("bonk")).stats(minimal)
    Check.near(1.0, blob.getValue("hp"), "HP is at least 1")
    Check.near(0.0, blob.getValue("def"), "stats are never negative")
    Check.near(60.0, blob.getValue("atk"), "default formula 'base'")
}

private fun testValidate(temp: File) {
    val setup = loadSetup(BASE.write(temp, "Validate")) ?: return
    val cub = Bakemon("embercub", level = 50, ability = "blaze", item = "leftovers", moves = listOf("ember", "tackle"), points = mapOf("atk" to 60, "spe" to 40))
    Check.eq(emptyList<String>(), cub.validate(setup), "a valid member")
    Check.eq(emptyList<String>(), Bakemon.default(setup.dex.require("embercub"), setup).validate(setup), "default member is valid")
    Check.eq(listOf("ember", "tackle"), Bakemon.default(setup.dex.require("embercub"), setup).moves, "default = first maxMoves moves")
    Check.eq("blaze", Bakemon.default(setup.dex.require("embercub"), setup).ability, "default = first ability")

    fun invalid(member: Bakemon, fragment: String) {
        val problems = member.validate(setup)
        Check.ok(problems.any { fragment in it }, "expected a problem with \"$fragment\" but got $problems")
    }
    invalid(cub.copy(species = "nope"), "Unknown bakemon 'nope'")
    invalid(cub.copy(level = 101), "Level 101 is out of range (1-100)")
    invalid(cub.copy(level = 0), "Level 0 is out of range")
    invalid(cub.copy(nickname = "A".repeat(21)), "too long")
    Check.eq(emptyList<String>(), cub.copy(nickname = "B".repeat(20)).validate(setup), "20-character nickname is fine")
    invalid(cub.copy(moves = listOf("ember", "tackle", "growl")), "Too many moves: 3 (max 2)")
    invalid(cub.copy(moves = emptyList()), "No moves chosen")
    invalid(cub.copy(moves = listOf("ember", "ember")), "Ember is chosen more than once")
    invalid(cub.copy(moves = listOf("ember", "bubble")), "Embercub cannot learn Bubble")
    invalid(cub.copy(moves = listOf("ember", "laser")), "Unknown move 'laser'")
    invalid(cub.copy(ability = "torrent"), "cannot have the ability Torrent")
    invalid(cub.copy(ability = "magic"), "Unknown ability 'magic'")
    invalid(cub.copy(ability = null), "No ability chosen")
    invalid(Bakemon("sprout", level = 50, ability = "guts", moves = listOf("tackle")), "Sprout cannot have an ability")
    Check.eq(emptyList<String>(), Bakemon("sprout", level = 50, moves = listOf("tackle")).validate(setup), "no ability for a species without abilities")
    invalid(cub.copy(item = "rock"), "Unknown item 'rock'")
    invalid(cub.copy(points = mapOf("atk" to 70)), "Stat points for Attack must be between 0 and 60 (has 70)")
    invalid(cub.copy(points = mapOf("atk" to -1)), "between 0 and 60")
    invalid(cub.copy(points = mapOf("atk" to 60, "spe" to 50)), "Too many stat points: 110 (max 100)")
    invalid(cub.copy(points = mapOf("spd" to 10)), "Unknown stat 'spd'")

    val minimal = loadSetup(MINIMAL.write(temp, "ValidateMinimal")) ?: return
    val blob = Bakemon("blob", level = 50, moves = listOf("bonk"))
    Check.ok(blob.copy(points = mapOf("atk" to 4)).validate(minimal).any { "Stat points are disabled" in it }, "points when disabled")
    Check.eq(emptyList<String>(), blob.copy(points = mapOf("atk" to 0)).validate(minimal), "zero points are fine when disabled")
    Check.ok(blob.copy(item = "x").validate(minimal).any { "Unknown item 'x'" in it }, "unknown item in a setup without items")

    val noItems = loadSetup(BASE.with("Rules.json", "\"maxMoves\": 2,", "\"maxMoves\": 2, \"heldItems\": false,").write(temp, "NoItems")) ?: return
    Check.ok(cub.validate(noItems).any { "Held items are disabled" in it }, "items disabled")

    // Team rules
    val drop = Bakemon("droplet", level = 50, ability = "torrent", item = "power_band", moves = listOf("bubble"))
    val spook = Bakemon("spook", level = 50, ability = "guts", moves = listOf("shadow_claw", "growl"))
    Check.eq(emptyList<String>(), Team("Good", listOf(cub, drop, spook)).validate(setup), "a valid team")
    fun teamInvalid(team: Team, fragment: String) {
        val problems = team.validate(setup)
        Check.ok(problems.any { fragment in it }, "expected a team problem with \"$fragment\" but got $problems")
    }
    teamInvalid(Team("Empty", emptyList()), "needs at least 1 member")
    teamInvalid(Team("Big", listOf(cub, drop, spook, Bakemon("pebble", level = 50, ability = "guts", moves = listOf("growl")))), "at most 3 member")
    teamInvalid(Team("Clones", listOf(cub, cub.copy(item = null))), "Species clause: Embercub is in the team more than once")
    teamInvalid(Team("Items", listOf(cub, drop.copy(item = "leftovers"))), "Item clause: Leftovers is held by more than one member")
    teamInvalid(Team("Member", listOf(cub, drop.copy(nickname = "Splash", level = 300))), "#2 Splash: Level 300 is out of range")
}

private fun testTeamStorage(temp: File) {
    val setup = loadSetup(BASE.write(temp, "Storage")) ?: return
    Check.eq(emptyList<String>(), setup.listTeams(), "no Teams folder = no teams")
    val members = listOf(
        Bakemon("embercub", nickname = "Sparky", level = 42, ability = "blaze", item = "leftovers", moves = listOf("ember", "tackle"), points = mapOf("atk" to 60)),
        Bakemon("sprout", level = 50, moves = listOf("vine_whip")),
    )
    val team = Team("My/Team: 1?", members)
    val file = setup.saveTeam(team)
    Check.eq("My_Team_ 1_.json", file.name, "unsafe characters become '_'")
    Check.ok(file.isFile && file.parentFile == setup.teamsDir, "saved in Teams/")
    Check.eq(listOf("My_Team_ 1_"), setup.listTeams(), "listTeams")
    Check.eq(team, setup.loadTeam("My_Team_ 1_"), "round trip by file name")
    Check.eq(team, setup.loadTeam("My/Team: 1?"), "round trip by team name")

    setup.saveTeam(Team("beta", members.take(1)))
    setup.saveTeam(Team("Alpha", members.take(1)))
    setup.saveTeam(Team("gamma", members.take(1)))
    Check.eq(listOf("Alpha", "beta", "gamma", "My_Team_ 1_"), setup.listTeams(), "sorted case-insensitively")
    Check.eq(Team("Alpha", members.take(1)), setup.loadTeam("Alpha"), "load another team")

    Check.ok(setup.deleteTeam("beta"), "delete existing")
    Check.ok(!setup.deleteTeam("beta"), "delete missing")
    Check.eq(listOf("Alpha", "gamma", "My_Team_ 1_"), setup.listTeams(), "list after delete")
    Check.throws<SetupException>("load missing team") { setup.loadTeam("beta") }

    File(setup.teamsDir, "Broken.json").writeText("{ \"name\": \"Broken\", \"members\": [ { \"species\": 3 } ] }")
    Check.throws<SetupException>("load malformed team") { setup.loadTeam("Broken") }
    File(setup.teamsDir, "Syntax.json").writeText("{ \"name\": ")
    Check.throws<SetupException>("load team with bad JSON") { setup.loadTeam("Syntax") }
    File(setup.teamsDir, "Nameless.json").writeText("{ \"members\": [] }")
    Check.eq("Nameless", setup.loadTeam("Nameless").name, "a team without name takes the file name")

    // JSON shape: null/empty fields are omitted.
    val json = Bakemon("sprout", level = 50, moves = listOf("tackle")).toJson()
    Check.eq(setOf("species", "level", "moves"), json.keys, "toJson omits empty fields")
    val parsed = Bakemon.fromJson(JsonObj.of(Json.parse(Json.stringify(members[0].toJson())), "test"))
    Check.eq(members[0], parsed, "Bakemon JSON round trip")
    Check.eq("Team", Setup.safeFileName("  ..  "), "blank file name")
    Check.eq("CON_", Setup.safeFileName("CON"), "reserved Windows name")

    // Names that share a file: the stem tells which saved team a save would overwrite.
    Check.eq("Alpha", Setup.teamFileStem("Alpha."), "trailing dot")
    Check.eq("x", Setup.teamFileStem("x . ."), "trailing dots and spaces")
    Check.eq("Alpha", setup.teamFileTaken("alpha."), "'alpha.' would overwrite Alpha.json")
    Check.eq("My_Team_ 1_", setup.teamFileTaken("My?Team/ 1:"), "'?' and '/' map to '_'")
    Check.eq(null, setup.teamFileTaken("Delta"), "a free name")

    // Long names are cut to a valid file name; the team keeps its full name.
    val long = Team("L".repeat(260), members.take(1))
    val longFile = setup.saveTeam(long)
    Check.eq(Setup.MAX_TEAM_FILE_STEM + ".json".length, longFile.name.length, "long file name cut")
    Check.eq(long, setup.loadTeam(long.name), "long name round trip")
    Check.ok(setup.deleteTeam(long.name), "delete by long name")

    // Stems that the sanitizer would change still name their own file (files copied or renamed by hand).
    val teams = setup.teamsDir
    File(teams, "x.json").writeText(Json.stringify(Team("Plain x", members.take(1)).toJson()))
    File(teams, "x..json").writeText(Json.stringify(Team("Dotted x", members.take(1)).toJson()))
    File(teams, "y .json").writeText(Json.stringify(Team("Spaced y", members.take(1)).toJson()))
    Check.ok(setup.listTeams().containsAll(listOf("x", "x.", "y ")), "listed stems: ${setup.listTeams()}")
    Check.eq("Dotted x", setup.loadTeam("x.").name, "'x.' loads x..json")
    Check.eq("Spaced y", setup.loadTeam("y ").name, "'y ' loads 'y .json'")
    Check.eq("Plain x", setup.loadTeam("x").name, "'x' loads x.json")
    Check.ok(setup.deleteTeam("x.") && File(teams, "x.json").isFile && !File(teams, "x..json").exists(), "'x.' deletes x..json only")

    // Saving back to the file a team came from, whatever its name maps to.
    File(teams, "Alpha - Copy.json").writeText(Json.stringify(Team("Alpha", members).toJson()))
    val alphaBefore = File(teams, "Alpha.json").readText()
    val written = setup.saveTeam(Team("Alpha", members.take(1)), file = "Alpha - Copy")
    Check.eq("Alpha - Copy.json", written.name, "saved to the source file")
    Check.eq(alphaBefore, File(teams, "Alpha.json").readText(), "the other 'Alpha' file is untouched")
    Check.eq(1, setup.loadTeam("Alpha - Copy").members.size, "source file updated")
    // The source file was renamed meanwhile (in Explorer): saving writes it again, never into the file the name maps to.
    Check.ok(File(teams, "Alpha - Copy.json").renameTo(File(teams, "Alpha 2.json")), "copy renamed by hand")
    val again = setup.saveTeam(Team("Alpha", members), file = "Alpha - Copy")
    Check.eq("Alpha - Copy.json", again.name, "a vanished source file is written again under its own name")
    Check.eq(alphaBefore, File(teams, "Alpha.json").readText(), "the other 'Alpha' file is still untouched")
    Check.eq(2, setup.loadTeam("Alpha - Copy").members.size, "recreated source file holds the saved team")
}

private fun testRandomTeams(temp: File) {
    val setup = loadSetup(BASE.write(temp, "Random")) ?: return
    for (seed in 0 until 100) {
        val team = setup.randomTeam(Random(seed))
        val problems = team.validate(setup)
        Check.ok(problems.isEmpty(), "random team (seed $seed) is valid: $problems")
        Check.eq(3, team.members.size, "random team size (seed $seed)")
        for (m in team.members) {
            val species = setup.dex.require(m.species)
            val canDamage = species.moves.any { setup.isDamaging(setup.moves.require(it)) }
            if (canDamage) Check.ok(m.moves.any { setup.isDamaging(setup.moves.require(it)) }, "seed $seed: ${m.species} has a damaging move: ${m.moves}")
        }
    }
    Check.eq(3, setup.randomTeam(Random(1), size = 10).members.size, "size clamped to team.max")
    Check.eq(1, setup.randomTeam(Random(1), size = 0).members.size, "size clamped to team.min")
    Check.eq("Mine", setup.randomTeam(Random(1), name = "Mine").name, "team name")

    val minimal = loadSetup(MINIMAL.write(temp, "RandomMinimal")) ?: return
    for (seed in 0 until 20) {
        val team = minimal.randomTeam(Random(seed))
        Check.ok(team.validate(minimal).isEmpty(), "minimal random team (seed $seed) is valid: ${team.validate(minimal)}")
    }
}

private fun testDiscovery(temp: File) {
    val root = File(temp, "Discovery")
    BASE.write(root, "beta")
    MINIMAL.write(root, "Alpha")
    File(root, "NotASetup").mkdirs()
    Check.eq(listOf("Alpha", "beta"), Setup.discover(root).map { it.name }, "discover: folders with Rules.json, sorted")
    Check.eq(emptyList<File>(), Setup.discover(File(temp, "missing")), "discover on a missing folder")
    Check.eq(root.absoluteFile, Setup.findSetupsRoot(root.path), "explicit setups folder")
    Check.eq(null, Setup.findSetupsRoot(File(temp, "missing").path), "explicit missing folder")
}

/** Loads every setup shipped in Setups and prints its problems (informative only). */
private fun reportBundledSetups(root: File) {
    val setupsDir = File(root, "Setups")
    val dirs = Setup.discover(setupsDir)
    if (dirs.isEmpty()) {
        println("  (no setups in ${setupsDir.path})")
        return
    }
    for (dir in dirs) {
        try {
            val setup = Setup.load(dir)
            println("  setup '${dir.name}': OK, ${setup.dex.all().size} bakemons, ${setup.moves.all().size} moves, ${setup.warnings.size} warning(s)")
            setup.warnings.forEach { println("    warning: $it") }
        } catch (e: SetupException) {
            println("  setup '${dir.name}': NOT LOADABLE")
            e.message?.lines()?.forEach { println("    $it") }
        } catch (e: Throwable) {
            println("  setup '${dir.name}': crashed while loading: $e")
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// More triggers, targets, effects and move behaviours

/** Moves that use every new move key, target and effect (inserted before Struggle in [BASE]). */
private val TRIGGER_MOVES = """
  { "id": "triple_kick", "name": "Triple Kick", "type": "normal", "category": "physical", "power": "20 * hitIndex", "accuracy": 90,
    "hits": 3, "hitAccuracy": "each", "ignoreDefense": "hitIndex == hitCount ? 50 : 0",
    "triggers": [ { "on": "hit", "condition": "hitIndex == hitCount", "effects": [ { "type": "attack", "power": "10 * hitCount", "moveType": "fire", "category": "physical" } ] },
                  { "on": "modifyDamageDealt", "condition": "hitIndex > 1", "effects": [ { "type": "multiplyDamage", "value": 1.1 } ] } ] },
  { "id": "solar", "name": "Solar", "type": "grass", "category": "physical", "power": 120, "accuracy": 100, "target": "lowestHpEnemy",
    "charge": { "turns": 2, "message": "{user} absorbs light for {move}!", "invulnerable": true, "skipIf": "user.hpPercent < 10" } },
  { "id": "spray", "name": "Spray", "category": "physical", "power": 15, "hits": 4, "target": "randomEnemies" },
  { "id": "mend", "name": "Mend", "category": "status", "target": "lowestHpAlly",
    "triggers": [ { "on": "hit", "effects": [
      { "type": "heal", "target": "team", "amount": "recipient.maxHp * 0.2" },
      { "type": "delay", "turns": 2, "attachTo": "self", "message": "{self}'s wish came true!",
        "effects": [ { "type": "setHp", "target": "self", "amount": "recipient.maxHp" } ] },
      { "type": "transferStatus", "target": "self", "to": "enemies", "kind": "debuff", "count": 0, "mode": "steal" },
      { "type": "transferStatus", "target": "highestHpEnemy", "status": "boost", "mode": "copy" },
      { "type": "copyStages", "target": "lowestHpEnemy", "to": "self" },
      { "type": "balanceHp", "target": "team" },
      { "type": "swapHp", "target": "randomAlly" } ] } ] },
  { "id": "pivot", "name": "Pivot", "type": "normal", "category": "physical", "power": 30, "accuracy": 100,
    "triggers": [ { "on": "afterMove", "effects": [ { "type": "switchOut" }, { "type": "extraTurn", "target": "self" } ] },
                  { "on": "hit", "effects": [ { "type": "forceSwitch" }, { "type": "useMove", "actor": "self", "move": "same", "moveTarget": "target", "power": 50, "free": false } ] } ] },
"""

/** An ability that listens to every new event (inserted before Guts in [BASE]). */
private val TRIGGER_ABILITY = """
  { "id": "vengeful", "name": "Vengeful", "triggers": [
    { "on": "attacked", "condition": "landed && damage > 0 && statusCount(self, 'debuff') == 0",
      "effects": [ { "type": "useMove", "move": "first", "power": "recipient.hpPercent", "message": "{self} strikes back at {other}!" } ] },
    { "on": "targeted", "message": "{self} sees {other}'s {move}!", "effects": [] },
    { "on": "allyUsedMove", "effects": [ { "type": "useMove", "moveTarget": "otherTarget", "move": "same" } ] },
    { "on": "enemyUsedMove", "condition": "other.acted && !self.charging", "effects": [] },
    { "on": "damaged", "condition": "self.hpPercent < 50 && hpBefore * 100 / self.maxHp >= 50", "limit": 1,
      "effects": [ { "type": "statStage", "target": "self", "stat": "atk", "stages": 1 } ] },
    { "on": "allyDamaged", "effects": [ { "type": "shield", "target": "other", "amount": "damage" } ] },
    { "on": "enemyDamaged", "effects": [ { "type": "damage", "target": "highestHpEnemy", "amount": 1 } ] },
    { "on": "statusInflicted", "condition": "status == 'burn'", "message": "{self} burned {other} ({status})!",
      "effects": [ { "type": "heal", "target": "randomAlly", "amount": 5 } ] },
    { "on": "turnEnd", "effects": [ { "type": "delay", "turns": 1, "effects": [ { "type": "extraTurn" } ] } ] } ] },
"""

private val TRIGGER_BASE = BASE
    .with("Moves.json", "{ \"id\": \"struggle\"", TRIGGER_MOVES.trim() + "\n  { \"id\": \"struggle\"")
    .with("Abilities.json", "{ \"id\": \"guts\"", TRIGGER_ABILITY.trim() + "\n  { \"id\": \"guts\"")

private fun testTriggerExtensions(temp: File) {
    val setup = loadSetup(TRIGGER_BASE.write(temp, "Triggers")) ?: return
    Check.ok(setup.warnings.isEmpty(), "the new keys load without warnings:\n${setup.warnings.joinToString("\n")}")
    val kick = setup.moves.require("triple_kick")
    Check.ok(kick.hitAccuracy == HitAccuracy.EACH && kick.ignoreDefense != null && kick.charge == null, "triple kick keys")
    val solar = setup.moves.require("solar")
    val charge = solar.charge!!
    Check.ok(charge.turns == 2 && charge.invulnerable && charge.skipIf != null && charge.message.startsWith("{user} absorbs"), "charge keys")
    Check.eq(MoveTarget.LOWEST_HP_ENEMY, solar.target, "lowestHpEnemy move target")
    Check.eq(MoveTarget.RANDOM_ENEMIES, setup.moves.require("spray").target, "randomEnemies move target")
    Check.eq(MoveTarget.LOWEST_HP_ALLY, setup.moves.require("mend").target, "lowestHpAlly move target")
    Check.ok(setup.moves.require("tackle").let { it.hitAccuracy == HitAccuracy.ONCE && it.charge == null && it.ignoreDefense == null }, "defaults")
    val mend = setup.moves.require("mend").triggers.single().effects
    val delay = mend[1] as Effect.Delay
    Check.ok(delay.attachTo == TargetSelector.SELF && delay.turns.constant == 2.0 && delay.effects.single() is Effect.SetHp, "delay parsed")
    val steal = mend[2] as Effect.TransferStatus
    Check.ok(steal.target == TargetSelector.SELF && steal.to == TargetSelector.ENEMIES && steal.mode == TransferMode.STEAL && steal.count?.constant == 0.0, "steal parsed")
    Check.eq(TransferMode.COPY, (mend[3] as Effect.TransferStatus).mode, "copy mode")
    Check.eq(TargetSelector.SELF, (mend[3] as Effect.TransferStatus).to, "transferStatus 'to' defaults to self")
    val pivot = setup.moves.require("pivot").triggers
    Check.eq(TargetSelector.SELF, (pivot[0].effects[0] as Effect.SwitchOut).target, "switchOut defaults to self, even in a move trigger")
    Check.eq(TargetSelector.TARGET, (pivot[1].effects[0] as Effect.ForceSwitch).target, "forceSwitch takes the move trigger's default target")
    val use = pivot[1].effects[1] as Effect.UseMove
    Check.ok(use.move == "same" && use.moveTarget == TargetSelector.TARGET && !use.free && use.power?.constant == 50.0, "useMove parsed")
    val ability = setup.abilities.require("vengeful").triggers
    val counter = ability[0].effects.single() as Effect.UseMove
    Check.ok(counter.actor == TargetSelector.SELF && counter.free && counter.moveTarget == null && counter.move == "first", "useMove defaults")
    Check.eq(EventType.ATTACKED, ability[0].on, "attacked event")
    Check.eq(
        listOf("attacked", "targeted", "allyUsedMove", "enemyUsedMove", "damaged", "allyDamaged", "enemyDamaged", "statusInflicted", "turnEnd"),
        ability.map { it.on.id }, "new events parse",
    )
    val attack = kick.triggers[0].effects.single() as Effect.Attack
    Check.ok(attack.type == "fire" && attack.category == "physical", "attack reads moveType and category")

    // Formula scopes: recipient only in effect values, the hit counters in move / damage formulas and the hit events.
    val rules = setup.rules
    fun problems(formula: String, scope: FormulaScope) = Setup.checkFormula(Formula.parse(formula), scope, rules, "w")
    Check.ok(problems("recipient.maxHp + recipient.atk + hasType(recipient, 'fire')", FormulaScope.TRIGGER).isEmpty(), "recipient in the trigger scope")
    Check.ok(problems("recipient.hp", FormulaScope.MOVE).single().contains("unknown variable 'recipient.hp'"), "recipient not in a move formula")
    Check.ok(problems("20 * hitIndex + hitCount", FormulaScope.MOVE).isEmpty(), "hit counters in the move power")
    Check.ok(problems("hitIndex * attack / defense", FormulaScope.DAMAGE).isEmpty(), "hit counters in the damage formula")
    Check.ok(problems("hitIndex", FormulaScope.HIT_TYPE).isEmpty(), "hit counters in hit types")
    Check.ok(problems("hitIndex", FormulaScope.HIT).isNotEmpty(), "no hit counters in hitChance / crit formulas")
    Check.ok(problems("self.acted + target.charging + statusCount(self) + statusCount(other, 'buff')", FormulaScope.TRIGGER).isEmpty(), "acted, charging, statusCount")
    Check.ok(problems("statusCount(self, 'buf')", FormulaScope.TRIGGER).single().contains("unknown status kind 'buf' in statusCount() in formula 'statusCount(self, 'buf')' (did you mean 'buff'?)"), "statusCount kind literal")
    Check.ok(problems("statusCount()", FormulaScope.TRIGGER).single().contains("statusCount() takes 1 or 2 arguments but got 0"), "statusCount arity")
    Check.eq(1, Setup.recipientProblems(Formula.parse("recipient.hp > 1"), "w", "x").size, "recipientProblems")
}

private fun testTriggerErrors(temp: File) {
    val t = TRIGGER_BASE
    expectErrors(temp, "TrRecipientCondition", t.with("Abilities.json", "\"condition\": \"landed &&", "\"condition\": \"recipient.hp > 0 && landed &&"),
        "'recipient.hp' has no value in a trigger condition")
    expectErrors(temp, "TrRecipientChance", t.with("Abilities.json", "{ \"on\": \"targeted\",", "{ \"on\": \"targeted\", \"chance\": \"recipient.hp\","),
        "'recipient.hp' has no value in a trigger chance")
    expectErrors(temp, "TrRecipientDuration", t.with("Statuses.json", "\"duration\": 3,", "\"duration\": \"recipient.level\","),
        "'recipient.level' has no value in a status duration")
    expectErrors(temp, "TrRecipientDelay", t.with("Moves.json", "\"turns\": 2, \"attachTo\"", "\"turns\": \"recipient.hp\", \"attachTo\""),
        "'recipient.hp' has no value in this value (its effect has no target)")
    expectErrors(temp, "TrRecipientMultiply", t.with("Moves.json", "\"value\": 1.1", "\"value\": \"recipient.hp\""),
        "'recipient.hp' has no value in this value")
    expectErrors(temp, "TrHitIndexDamaged", t.with("Abilities.json", "\"self.hpPercent < 50 &&", "\"hitIndex > 1 && self.hpPercent < 50 &&"),
        "variable 'hitIndex' has no value in a 'damaged' trigger")
    expectErrors(temp, "TrHpBeforeHit", t.with("Moves.json", "\"condition\": \"hitIndex == hitCount\"", "\"condition\": \"hpBefore > 0\""),
        "variable 'hpBefore' has no value in a 'hit' trigger (it is set only in: damaged)")
    expectErrors(temp, "TrLandedDamaged", t.with("Abilities.json", "\"self.hpPercent < 50 &&", "\"landed && self.hpPercent < 50 &&"),
        "variable 'landed' has no value in a 'damaged' trigger (it is set only in: attacked)")
    expectErrors(temp, "TrHitsCounter", t.with("Moves.json", "\"hits\": 3,", "\"hits\": \"hitCount\","), "triple_kick).hits: variable 'hitCount' has no value in 'hits'")
    expectErrors(temp, "TrHitChance", t.with("Rules.json", "\"critChance\": \"1 / 24\"", "\"critChance\": \"hitIndex / 24\""), "Rules.json.critChance: unknown variable 'hitIndex'")
    expectErrors(temp, "TrUseMoveUnknown", t.with("Moves.json", "\"move\": \"same\", \"moveTarget\"", "\"move\": \"tackel\", \"moveTarget\""),
        "unknown move 'tackel' (did you mean 'tackle'?) (use a move id, \"first\" or \"same\")")
    expectErrors(temp, "TrUseMoveTarget", t.with("Moves.json", "\"moveTarget\": \"target\"", "\"moveTarget\": \"foe\""), "moveTarget: unknown target 'foe'")
    expectErrors(temp, "TrUseMoveFree", t.with("Moves.json", "\"free\": false", "\"free\": \"no\""), "free")
    expectErrors(temp, "TrTransferNothing", t.with("Moves.json", "\"kind\": \"debuff\", \"count\": 0,", "\"count\": 0,"), "transferStatus needs \"status\" (an id or \"all\") and/or \"kind\"")
    expectErrors(temp, "TrTransferMode", t.with("Moves.json", "\"mode\": \"copy\"", "\"mode\": \"clone\""), "unknown mode 'clone' (expected one of: steal, copy)")
    expectErrors(temp, "TrTransferStatus", t.with("Moves.json", "\"status\": \"boost\", \"mode\"", "\"status\": \"bost\", \"mode\""), "unknown status 'bost' (did you mean 'boost'?)")
    expectErrors(temp, "TrTransferKind", t.with("Moves.json", "\"kind\": \"debuff\", \"count\"", "\"kind\": \"debuf\", \"count\""), "unknown status kind 'debuf'")
    expectErrors(temp, "TrAttackType", t.with("Moves.json", "\"moveType\": \"fire\"", "\"moveType\": \"fyre\""), "moveType: unknown type 'fyre' (did you mean 'fire'?)")
    expectErrors(temp, "TrAttackCategory", t.with("Moves.json", "\"category\": \"physical\" } ]", "\"category\": \"phys\" } ]"), "category: unknown category 'phys'")
    expectErrors(temp, "TrAttackNoDamage", t.with("Moves.json", "\"category\": \"physical\" } ]", "\"category\": \"status\" } ]"), "category 'status' deals no damage")
    expectErrors(temp, "TrAttackPower", t.with("Moves.json", "\"power\": \"10 * hitCount\", ", ""), "power")
    expectErrors(temp, "TrHitAccuracy", t.with("Moves.json", "\"hitAccuracy\": \"each\"", "\"hitAccuracy\": \"every\""), "hitAccuracy: unknown value 'every' (use one of: once, each)")
    expectErrors(temp, "TrChargeTurns", t.with("Moves.json", "\"turns\": 2, \"message\"", "\"turns\": 0, \"message\""), "charge.turns: must be at least 1 (got 0)")
    expectErrors(temp, "TrChargeSkip", t.with("Moves.json", "\"skipIf\": \"user.hpPercent < 10\"", "\"skipIf\": \"user.hpPercent < hitIndex\""), "charge.skipIf: variable 'hitIndex' has no value")
    expectErrors(temp, "TrChargeInvulnerable", t.with("Moves.json", "\"invulnerable\": true", "\"invulnerable\": 1"), "invulnerable")
    expectErrors(temp, "TrIgnoreDefense", t.with("Moves.json", "\"ignoreDefense\": \"hitIndex == hitCount ? 50 : 0\"", "\"ignoreDefense\": \"recipient.def\""),
        "ignoreDefense: unknown variable 'recipient.def'")
    expectErrors(temp, "TrMoveTarget", t.with("Moves.json", "\"target\": \"randomEnemies\"", "\"target\": \"randomEnemys\""), "unknown target 'randomEnemys'")
    expectErrors(temp, "TrEffectTarget", t.with("Moves.json", "\"target\": \"highestHpEnemy\"", "\"target\": \"highestEnemy\""), "unknown target 'highestEnemy'")
    expectErrors(temp, "TrDelayEffects", t.with("Moves.json", "\"effects\": [ { \"type\": \"setHp\"", "\"effect\": [ { \"type\": \"setHp\""), "effects")
    expectErrors(temp, "TrDelayInner", t.with("Moves.json", "{ \"type\": \"setHp\", \"target\": \"self\", \"amount\": \"recipient.maxHp\" }",
        "{ \"type\": \"applyStatus\", \"status\": \"brn\" }"), "unknown status 'brn'")
}

private fun testTriggerWarnings(temp: File) {
    val fixture = TRIGGER_BASE
        .with("Moves.json", "\"power\": 50, \"free\": false", "\"power\": 50, \"free\": false, \"powr\": 2")
        .with("Moves.json", "\"amount\": \"recipient.maxHp\" }", "\"amount\": \"recipient.maxHp\", \"amout\": 1 }")
        .with("Moves.json", "\"invulnerable\": true,", "\"invulnerable\": true, \"turn\": 1,")
        .with("Moves.json", "\"message\": \"{user} absorbs light for {move}!\"", "\"message\": \"{user} absorbs light ({damage})!\"")
        .with("Moves.json", "\"hits\": 4, \"target\": \"randomEnemies\"", "\"hits\": 4, \"target\": \"randomEnemies\", \"hitAccuracy\": \"each\"")
        .with("Moves.json", "\"ignoreDefense\": \"hitIndex == hitCount ? 50 : 0\"", "\"ignoreDefense\": 150")
        .with("Moves.json", "{ \"type\": \"balanceHp\", \"target\": \"team\" }", "{ \"type\": \"balanceHp\", \"target\": \"self\" }")
        .with("Moves.json", "{ \"type\": \"swapHp\", \"target\": \"randomAlly\" }", "{ \"type\": \"swapHp\", \"target\": \"self\" }")
        .with("Moves.json", "\"effects\": [ { \"type\": \"setHp\"", "\"effects\": [ { \"type\": \"multiplyDamage\", \"value\": 2 }, { \"type\": \"setHp\"")
        .with("Moves.json", "\"turns\": 2, \"attachTo\"", "\"turns\": 0, \"attachTo\"")
        .with("Moves.json", "{ \"type\": \"copyStages\", \"target\": \"lowestHpEnemy\", \"to\": \"self\" }", "{ \"type\": \"copyStages\", \"target\": \"self\", \"to\": \"self\" }")
        .with("Abilities.json", "\"effects\": [ { \"type\": \"delay\", \"turns\": 1,", "\"effects\": [ { \"type\": \"useMove\", \"move\": \"same\" }, { \"type\": \"delay\", \"turns\": 1,")
        .with("Moves.json", "\"accuracy\": 90,\n    \"hits\": 3,", "\"hits\": 3,")
    val setup = loadSetup(fixture.write(temp, "TriggerWarnings")) ?: return
    val warnings = setup.warnings
    fun expectWarning(fragment: String) = Check.ok(warnings.any { fragment in it }, "expected a warning with \"$fragment\" in:\n${warnings.joinToString("\n")}")
    expectWarning("unknown key 'powr' for effect 'useMove'")
    expectWarning("effects[1].effects[1]: unknown key 'amout' for effect 'setHp'")
    expectWarning("(solar).charge: unknown key 'turn' (did you mean 'turns'?)")
    expectWarning("(solar).charge.message: placeholder '{damage}' is not filled in a charge message")
    expectWarning("(spray).hitAccuracy: \"each\" changes nothing for target 'randomEnemies'")
    expectWarning("(triple_kick).hitAccuracy: \"each\" does nothing because the move never misses")
    expectWarning("(triple_kick).ignoreDefense: 150.0 is outside 0-100")
    expectWarning("balanceHp shares HP within a group")
    expectWarning("swapping HP with itself does nothing")
    expectWarning("effects[1].effects[0]: multiplyDamage does nothing in a delayed effect")
    expectWarning("effects[1].turns: 0.0 counts as 1")
    expectWarning("copied from 'self' to itself")
    expectWarning("\"same\" always means the actor's first move in a 'turnEnd' trigger")
    Check.eq(13, warnings.size, "one warning per problem:\n${warnings.joinToString("\n")}")

    // forceSwitch / switchOut without any bench.
    val solo = TRIGGER_BASE.with("Rules.json", "\"max\": 3, \"active\": 1", "\"max\": 1, \"active\": 1")
        .with("Rules.json", "\"speciesClause\": true", "\"speciesClause\": false")
    val soloSetup = loadSetup(solo.write(temp, "TriggerSolo")) ?: return
    Check.ok(soloSetup.warnings.any { "switchOut never works: with team.max 1 and team.active 1" in it }, "switchOut without a bench: ${soloSetup.warnings}")
    Check.ok(soloSetup.warnings.any { "forceSwitch never works" in it }, "forceSwitch without a bench: ${soloSetup.warnings}")
}
