import bakemon.Bakemon
import bakemon.FieldAudience
import bakemon.FieldScope
import bakemon.FieldStart
import bakemon.Setup
import bakemon.Stacking
import bakemon.Team
import engine.Action
import engine.AiController
import engine.AiLevel
import engine.Battle
import engine.BattleObserver
import engine.BattleResult
import engine.Battler
import engine.Controller
import engine.Effect
import engine.EventContext
import engine.EventType
import engine.ExpressionException
import engine.Formula
import engine.LogEntry
import engine.LogKind
import engine.Manager
import engine.Side
import engine.TargetSelector
import engine.Trigger
import util.Json
import util.JsonObj
import util.SetupException
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** Tests for the battle engine. [root] is the project folder. */
fun runEngineTests(root: File) {
    val tmp = Files.createTempDirectory("bakemon-engine-tests").toFile()
    try {
        val lab = EngineLab(tmp)
        Check.section("engine: trigger/effect parsing")
        Check.guard("engine parsing") { parsingTests() }
        Check.section("engine: formula context")
        Check.guard("engine context") { contextTests(lab) }
        Check.section("engine: damage")
        Check.guard("engine damage") { damageTests(lab) }
        Check.section("engine: statuses")
        Check.guard("engine statuses") { statusTests(lab) }
        Check.section("engine: status durations")
        Check.guard("engine durations") { durationTests(lab) }
        Check.section("engine: stages, shields, gauges, items")
        Check.guard("engine operations") { operationTests(lab) }
        Check.section("engine: turn order, pp, cooldowns")
        Check.guard("engine order") { orderTests(lab) }
        Check.section("engine: fainting and replacement")
        Check.guard("engine fainting") { faintTests(lab) }
        Check.section("engine: guards and determinism")
        Check.guard("engine guards") { guardTests(lab) }
        Check.section("engine: status freshness and end of round")
        Check.guard("engine freshness") { freshnessTests(lab) }
        Check.section("engine: events, damage pipeline, planning")
        Check.guard("engine pipeline") { pipelineTests(lab) }
        Check.section("engine: simultaneous events, cooldown effects, legal targets")
        Check.guard("engine simultaneous") { simultaneityTests(lab) }
        Check.section("engine: hit types, type matchup in hit/crit formulas")
        Check.guard("engine hit types") { hitTypeTests(lab) }
        Check.section("engine: fields")
        Check.guard("engine fields") { fieldTests(lab) }
        Check.section("engine: field rules")
        Check.guard("engine field rules") { fieldRuleTests(lab) }
        Check.section("engine: revives")
        Check.guard("engine revives") { reviveTests(lab) }
        Check.section("engine: new formula variables")
        Check.guard("engine trigger formulas") { formulaTriggerTests(lab) }
        Check.section("engine: new targets")
        Check.guard("engine trigger targets") { targetTriggerTests(lab) }
        Check.section("engine: new events")
        Check.guard("engine trigger events") { eventTriggerTests(lab) }
        Check.section("engine: useMove")
        Check.guard("engine useMove") { useMoveTriggerTests(lab) }
        Check.section("engine: extra turns")
        Check.guard("engine extra turns") { extraTurnTests(lab) }
        Check.section("engine: status and HP effects")
        Check.guard("engine status and HP effects") { statusAndHpEffectTests(lab) }
        Check.section("engine: switching and delayed effects")
        Check.guard("engine switch and delay") { switchAndDelayTests(lab) }
        Check.section("engine: attack effect, ignoreDefense, hitAccuracy")
        Check.guard("engine attack and move keys") { attackAndMoveKeyTests(lab) }
        Check.section("engine: charge moves")
        Check.guard("engine charge") { chargeTests(lab) }
        Check.section("engine: shipped data")
        Check.guard("engine shipped data") { shippedDataTests(root) }
        Check.section("engine: smoke test of the shipped setups")
        Check.guard("engine smoke") { smokeTests(root) }
    } finally {
        tmp.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Test fixtures

/** Records everything a battle reports. */
private class Recorder : BattleObserver {
    val log = mutableListOf<LogEntry>()
    val actors = mutableListOf<Battler?>()
    var result: BattleResult? = null

    override fun onLog(entry: LogEntry) {
        log += entry
    }

    override fun onTurnStart(battle: Battle, actor: Battler?) {
        actors += actor
    }

    override fun onBattleEnd(battle: Battle, result: BattleResult) {
        this.result = result
    }

    fun has(text: String) = log.any { it.text == text }
    fun count(text: String) = log.count { it.text == text }
    fun index(text: String) = log.indexOfFirst { it.text == text }
    fun texts() = log.map { it.text }

    /** Turn number (from the last TURN line) of every line equal to [text]. */
    fun turnsOf(text: String): List<Int> {
        var turn = 0
        val result = mutableListOf<Int>()
        for (entry in log) {
            if (entry.kind == LogKind.TURN) turn = entry.text.removePrefix("Turn ").takeWhile { it.isDigit() }.toInt()
            if (entry.text == text) result += turn
        }
        return result
    }
}

/** A controller driven by a lambda; replacements pick the first option unless [replace] says otherwise. */
private class Script(
    override val name: String = "Script",
    val replace: (List<Battler>) -> Battler = { it.first() },
    val choose: (Battle, Battler) -> Action,
) : Controller {
    var replacementOptions = mutableListOf<List<Battler>>()

    override fun chooseAction(battle: Battle, actor: Battler): Action = choose(battle, actor)

    override fun chooseReplacement(battle: Battle, side: Side, slot: Int, options: List<Battler>): Battler {
        replacementOptions += options
        return replace(options)
    }
}

/** Random legal choices from its own seeded random (deterministic). */
private class RandomController(seed: Long) : Controller {
    override val name = "Random"
    private val rng = Random(seed)

    override fun chooseAction(battle: Battle, actor: Battler): Action {
        if (battle.canSwitch(actor) && rng.nextInt(10) == 0) {
            return Action.Switch(battle.switchOptions(actor).random(rng))
        }
        val usable = battle.usableMoves(actor)
        if (usable.isEmpty()) {
            val fallback = battle.fallbackMove() ?: return Action.Pass
            return Action.UseMove(fallback, null, battle.targetOptions(actor, fallback).randomOrNull(rng))
        }
        val slot = usable.random(rng)
        return Action.UseMove(slot.def, slot, battle.targetOptions(actor, slot.def).randomOrNull(rng))
    }

    override fun chooseReplacement(battle: Battle, side: Side, slot: Int, options: List<Battler>): Battler = options.random(rng)
}

/** Always uses [moveId] on the first enemy option. */
private fun use(moveId: String): (Battle, Battler) -> Action = { battle, actor ->
    val slot = actor.moves.first { it.def.id == moveId }
    Action.UseMove(slot.def, slot, battle.targetOptions(actor, slot.def).firstOrNull())
}

/** Uses [first] on the actor's first [times] turns, then [then]. */
private fun useThen(first: String, times: Int, then: String): (Battle, Battler) -> Action {
    var used = 0
    return { battle, actor -> if (used++ < times) use(first)(battle, actor) else use(then)(battle, actor) }
}

private fun json(text: String): Any? = Json.parse(text, "test")

/** Builds small setups on disk. Every species can learn every move; abilities/items are attached freely. */
private class EngineLab(private val dir: File) {
    private var counter = 0

    val types = json(
        """
        { "types": [ {"id": "normal", "name": "Normal"}, {"id": "fire", "name": "Fire"}, {"id": "water", "name": "Water"},
                     {"id": "grass", "name": "Grass"}, {"id": "ghost", "name": "Ghost"} ],
          "chart": { "fire": { "grass": 2, "water": 0.5 }, "water": { "fire": 2 }, "normal": { "ghost": 0 } },
          "messages": { "strong": "It's super effective!", "weak": "It's not very effective...", "immune": "It doesn't affect {target}..." } }
        """
    )

    fun rules(vararg overrides: Pair<String, Any?>): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "name" to "Lab",
            "turnSystem" to "speed_order",
            "team" to mapOf("min" to 1, "max" to 6, "active" to 1),
            "switching" to true,
            "level" to mapOf("min" to 1, "max" to 100, "default" to 50),
            "maxMoves" to 8,
            "heldItems" to true,
            "stats" to listOf(
                mapOf("id" to "hp", "name" to "HP"), mapOf("id" to "atk", "name" to "Attack"),
                mapOf("id" to "def", "name" to "Defense"), mapOf("id" to "spe", "name" to "Speed"),
            ),
            "hpStat" to "hp",
            "speedStat" to "spe",
            "statFormulas" to mapOf("default" to "base"),
            "statStages" to mapOf("min" to -6, "max" to 6, "multiplier" to "stage >= 0 ? (2 + stage) / 2 : 2 / (2 - stage)"),
            "categories" to mapOf("physical" to mapOf("attack" to "atk", "defense" to "def"), "status" to emptyMap<String, Any>()),
            "damage" to "floor(move.power * attack / defense * (sameType ? 1.5 : 1) * effectiveness * crit)",
            "hitChance" to "move.accuracy / 100",
            "critChance" to "0",
            "critMultiplier" to "2",
            "minDamage" to 1,
            "pp" to true,
            "cooldowns" to false,
            "actionBar" to mapOf("size" to 100, "fill" to "speed", "start" to "0"),
            "turnLimit" to 0,
        )
        for ((k, v) in overrides) if (v == null) map.remove(k) else map[k] = v
        return map
    }

    private fun species(id: String, type: String = "normal", hp: Int = 200, atk: Int = 100, def: Int = 100, spe: Int = 50) =
        mapOf(
            "id" to id, "name" to id.replaceFirstChar { it.uppercase() }, "types" to listOf(type),
            "baseStats" to mapOf("hp" to hp, "atk" to atk, "def" to def, "spe" to spe),
            "abilities" to emptyList<String>(), "moves" to moveIds,
        )

    private fun move(id: String, vararg fields: Pair<String, Any?>): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "id" to id, "name" to id.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
            "category" to "physical", "power" to 40, "target" to "enemy",
        )
        for ((k, v) in fields) if (v == null) map.remove(k) else map[k] = v
        return map
    }

    private fun status(vararg fields: Pair<String, Any?>): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        for ((k, v) in fields) map[k] = v
        map["name"] = map["name"] ?: (map["id"] as String).replaceFirstChar { it.uppercase() }
        return map
    }

    private val moves: List<Map<String, Any?>> = listOf(
        move("tackle", "type" to "normal", "flags" to listOf("contact")),
        move("ember", "type" to "fire"),
        move("bubble", "type" to "water"),
        move("jab", "pp" to 2),
        move("struggle", "power" to 10),
        move("triple", "power" to 10, "hits" to 3),
        move("multi", "power" to 10, "hits" to "randint(2, 5)"),
        move("whiff", "accuracy" to 0, "triggers" to json("""[{"on": "missed", "message": "Whiffed!", "effects": []}]""")),
        move("sure", "accuracy" to 100),
        move("wait", "category" to "status", "power" to null, "target" to "self"),
        move("quick", "priority" to 1),
        move("big", "cooldown" to 2),
        move("small", "power" to 10),
        move("fury", "power" to "move.power + 50"),
        move("lucky", "triggers" to json("""[{"on": "modifyDamageDealt", "effects": [{"type": "multiplyDamage", "value": 3}]}]""")),
        move("scare", "power" to 10, "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "flinch"}]}]""")),
        move(
            "guard_up", "category" to "status", "power" to null, "target" to "self",
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "guard"}]}]"""),
        ),
        move(
            "burn_it", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "burn"}]}]"""),
        ),
        move(
            "double_status", "power" to 10,
            "triggers" to json(
                """[{"on": "hit", "effects": [
                     {"type": "applyStatus", "status": "charged", "target": "self"},
                     {"type": "applyStatus", "status": "dazed"},
                     {"type": "applyStatus", "status": "brace", "target": "self"} ]}]"""
            ),
        ),
        move(
            "rush", "category" to "status", "power" to null, "target" to "self",
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "gauge", "amount": 100, "target": "self"}]}]"""),
        ),
        move("finisher", "power" to 1000, "triggers" to json("""[{"on": "knockedOut", "message": "Finisher KO!", "effects": []}]""")),
        move(
            "seed", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "leech"}]}]"""),
        ),
        move(
            "plant_bomb", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "bomb"}]}]"""),
        ),
        move(
            "hush_burn", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "burn", "message": ""}]}]"""),
        ),
        move(
            "loud_burn", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "burn", "message": "{user} set {target} ablaze with {move} ({status})!"}]}]"""),
        ),
        move(
            "rest_up", "category" to "status", "power" to null, "target" to "self", "pp" to 5,
            "triggers" to json("""[{"on": "beforeMove", "condition": "self.hp >= self.maxHp", "effects": [{"type": "cancelAction"}]}]"""),
        ),
        move("cheer", "category" to "status", "power" to null, "target" to "ally"),
        move(
            "pluck", "power" to 10,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "consumeItem", "message": "{user} plucked {target}'s {item}!"}]}]"""),
        ),
        move(
            "lull", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "doze"}]}]"""),
        ),
        move(
            "light_fuse", "category" to "status", "power" to null, "target" to "self",
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "fuse"}]}]"""),
        ),
        move(
            "jam", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "cooldown", "amount": 3}]}]"""),
        ),
        move(
            "curse", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "doom"}]}]"""),
        ),
        move("hp_power", "power" to "self.hpPercent"),
        move("rally", "category" to "status", "power" to null, "target" to "allAllies"),
        move(
            "lock", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "cooldown", "amount": 1}]}]"""),
        ),
        move(
            "self_lock", "power" to 10,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "cooldown", "target": "self", "amount": 1}]}]"""),
        ),
        move(
            "timer_first", "category" to "status", "power" to null, "target" to "self",
            "triggers" to json(
                """[{"on": "hit", "effects": [{"type": "applyStatus", "target": "self", "status": "timer"},
                                              {"type": "applyStatus", "target": "self", "status": "guard", "duration": 1}]}]"""
            ),
        ),
        move(
            "guard_first", "category" to "status", "power" to null, "target" to "self",
            "triggers" to json(
                """[{"on": "hit", "effects": [{"type": "applyStatus", "target": "self", "status": "guard", "duration": 1},
                                              {"type": "applyStatus", "target": "self", "status": "timer"}]}]"""
            ),
        ),
        move(
            "sip",
            "triggers" to json("""[{"on": "hit", "message": "Sipped {damage}!", "effects": [{"type": "heal", "target": "user", "amount": "damage"}]}]"""),
        ),
        move("maybe", "power" to "user.hp > 0 ? 0 : 50", "triggers" to json("""[{"on": "hit", "message": "Maybe hit for {damage}!", "effects": []}]""")),
        // Hit types (Rules.hitTypes) and the type matchup in hit / crit formulas.
        move("flame_shot", "type" to "fire", "accuracy" to 100),
        move("keen", "type" to "fire", "critBonus" to "effectiveness > 1 ? 1 : 0"),
        move(
            "crush_note", "type" to "fire",
            "triggers" to json(
                """[{"on": "modifyDamageDealt", "condition": "hitType == 'crushing'", "effects": [{"type": "multiplyDamage", "value": 2}]},
                    {"on": "hit", "condition": "hitType == 'crushing'", "message": "Crush registered for {damage}!", "effects": []},
                    {"on": "hit", "condition": "hitType == ''", "message": "Normal hit for {damage}.", "effects": []}]"""
            ),
        ),
        move(
            "probe", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "condition": "hitType == '' && isCrit == 0", "message": "Probe: normal hit.", "effects": []}]"""),
        ),
        // Fields (Fields.json below).
        fieldMove("rain_dance", """{"type": "setField", "field": "rain"}"""),
        fieldMove("short_rain", """{"type": "setField", "field": "rain", "duration": 2, "message": "{user} summoned a short {field}!"}"""),
        fieldMove("sunny_day", """{"type": "setField", "field": "sun"}"""),
        fieldMove("screen_up", """{"type": "setField", "field": "screen"}"""),
        fieldMove("screen_both", """{"type": "setField", "field": "screen", "side": "both"}"""),
        fieldMove("spike_it", """{"type": "setField", "field": "spikes", "side": "enemy"}"""),
        fieldMove("spike_two", """{"type": "setField", "field": "spikes", "side": "enemy", "stacks": 2}"""),
        fieldMove("clear_spikes", """{"type": "removeField", "field": "spikes", "side": "enemy", "message": "{user} cleared the {field} on {side}'s side!"}"""),
        fieldMove("clear_weather", """{"type": "removeField", "group": "weather"}"""),
        fieldMove("clear_mine", """{"type": "removeField", "field": "all", "side": "self"}"""),
        fieldMove("trick_room", """{"type": "setField", "field": "room"}"""),
        fieldMove("set_thorns", """{"type": "setField", "field": "thorns"}"""),
        fieldMove("tailwind", """{"type": "setField", "field": "tailwind"}"""),
        mapOf(
            "id" to "team_screen", "name" to "Team Screen", "category" to "status", "target" to "team",
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "setField", "field": "screen"}]}]"""),
        ),
        move("water_gun", "type" to "water"),
        move(
            "raise", "category" to "status", "power" to null, "target" to "faintedAlly",
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "revive", "amount": "target.maxHp / 2"}]}]"""),
        ),
        move(
            "mass_raise", "category" to "status", "power" to null, "target" to "self",
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "revive", "target": "faintedAllies", "amount": 10, "message": "{self} called back the fallen!"}]}]"""),
        ),
        move(
            "soul_up", "category" to "status", "power" to null, "target" to "self",
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "soul"}]}]"""),
        ),
        move(
            "hex_tick", "category" to "status", "power" to null,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "applyStatus", "status": "tick"}]}]"""),
        ),
        move(
            "doom", "power" to 1000,
            "triggers" to json("""[{"on": "knockedOut", "effects": [{"type": "preventRevive"}]}]"""),
        ),
    )
    private val moveIds = moves.map { it["id"] as String }

    private val species = listOf(
        species("blaze", type = "fire", spe = 100),
        species("leaf", type = "grass"),
        species("puddle", type = "water"),
        species("spook", type = "ghost"),
        species("plain"),
        species("slow", spe = 10),
        species("swift", spe = 200),
        species("fast", spe = 101),
        species("even", spe = 100),
        species("weak", hp = 10, spe = 10),
        species("backup", hp = 50, spe = 10),
        species("brawny", atk = 150),
    )

    private val statuses = listOf(
        status(
            "id" to "tick", "kind" to "debuff", "duration" to 3,
            "triggers" to json("""[{"on": "turnStart", "effects": [{"type": "damage", "target": "self", "amount": 1000}]}]"""),
        ),
        status(
            "id" to "soul", "kind" to "buff", "duration" to 3,
            "triggers" to json("""[{"on": "fainted", "effects": [{"type": "revive", "target": "self", "amount": "self.maxHp * 0.3"}]}]"""),
        ),
        status(
            "id" to "burn", "kind" to "major", "immuneTypes" to listOf("fire"), "statModifiers" to mapOf("atk" to 0.5),
            "applyMessage" to "{target} was burned!",
            "triggers" to json("""[{"on": "turnEnd", "effects": [{"type": "damage", "amount": "self.maxHp / 8"}]}]"""),
        ),
        status("id" to "poison", "kind" to "major"),
        status("id" to "focus", "kind" to "buff", "stacking" to "stack", "maxStacks" to 3, "statModifiers" to mapOf("atk" to "1 + 0.5 * stacks")),
        status("id" to "regen", "kind" to "buff", "duration" to 1),
        status("id" to "guard", "kind" to "volatile", "duration" to 2, "stacking" to "refresh"),
        status(
            "id" to "flinch", "kind" to "volatile", "duration" to 0,
            "triggers" to json("""[{"on": "beforeMove", "effects": [{"type": "cancelAction", "message": "{self} flinched!"}]}]"""),
        ),
        status("id" to "charged", "kind" to "buff", "duration" to 1),
        status("id" to "dazed", "kind" to "debuff", "duration" to 1),
        status("id" to "brace", "kind" to "volatile", "duration" to 0),
        status(
            "id" to "leech", "kind" to "debuff",
            "triggers" to json(
                """[{"on": "turnEnd", "message": "{other} drains {self}!",
                     "effects": [{"type": "damage", "amount": 10}, {"type": "heal", "target": "other", "amount": 10}]}]"""
            ),
        ),
        status("id" to "odd", "kind" to "buff", "statModifiers" to mapOf("def" to "self.atk / self.def")),
        status("id" to "hush", "kind" to "volatile", "applyMessage" to "", "removeMessage" to ""),
        status(
            "id" to "bomb", "kind" to "debuff", "duration" to 1,
            "triggers" to json(
                """[{"on": "statusRemoved", "condition": "status == 'bomb'", "message": "The bomb on {self} exploded!",
                     "effects": [{"type": "damage", "amount": 30}]}]"""
            ),
        ),
        status(
            "id" to "doze", "kind" to "volatile", "duration" to 1,
            "triggers" to json("""[{"on": "beforeMove", "effects": [{"type": "cancelAction", "message": "{self} dozes."}]}]"""),
        ),
        status(
            "id" to "fuse", "kind" to "debuff", "duration" to 1,
            "triggers" to json("""[{"on": "statusRemoved", "condition": "status == 'fuse'", "effects": [{"type": "applyStatus", "status": "dazed"}]}]"""),
        ),
        status(
            "id" to "doom", "kind" to "debuff",
            "triggers" to json("""[{"on": "fainted", "message": "Doom strikes {other}!", "effects": [{"type": "damage", "target": "other", "amount": 7}]}]"""),
        ),
        status(
            "id" to "searing", "kind" to "debuff",
            "triggers" to json("""[{"on": "turnEnd", "effects": [{"type": "damage", "amount": "self.maxHp"}]}]"""),
        ),
        status(
            "id" to "pyre", "kind" to "debuff",
            "triggers" to json("""[{"on": "turnStart", "effects": [{"type": "damage", "amount": "self.maxHp"}]}]"""),
        ),
        status("id" to "rage", "kind" to "buff", "duration" to 2, "stacking" to "refresh", "maxStacks" to 3, "statModifiers" to mapOf("atk" to "1 + stacks")),
        status(
            "id" to "timer", "kind" to "volatile", "duration" to 1,
            "triggers" to json(
                """[{"on": "statusRemoved", "condition": "status == 'timer'",
                     "effects": [{"type": "applyStatus", "target": "self", "status": "guard", "duration": 2}]}]"""
            ),
        ),
        status(
            "id" to "wary", "kind" to "volatile",
            "triggers" to json("""[{"on": "beforeMove", "condition": "!cancelled", "message": "{self} is wary.", "effects": []}]"""),
        ),
    )

    private val abilities = listOf(
        mapOf(
            "id" to "phoenix", "name" to "Phoenix",
            "triggers" to json(
                """[{"on": "fainted", "limit": 1, "effects": [{"type": "revive", "target": "self", "amount": 1, "message": "{self} rose from the ashes!"}]},
                    {"on": "revived", "effects": [{"type": "applyStatus", "status": "guard", "target": "self"}]}]"""
            ),
        ),
        mapOf(
            "id" to "cheer", "name" to "Cheer",
            "triggers" to json(
                """[{"on": "allyRevived", "message": "{self} cheers for {other}!", "effects": []},
                    {"on": "enemyRevived", "message": "{self} groans at {other}!", "effects": []}]"""
            ),
        ),
        mapOf(
            "id" to "brute", "name" to "Brute",
            "triggers" to json("""[{"on": "modifyDamageDealt", "condition": "hasFlag('contact')", "effects": [{"type": "multiplyDamage", "value": 2}]}]"""),
        ),
        mapOf(
            "id" to "moxie", "name" to "Moxie",
            "triggers" to json("""[{"on": "knockedOut", "message": "{self} feels powerful!", "effects": [{"type": "statStage", "stat": "atk", "stages": 1}]}]"""),
        ),
        mapOf(
            "id" to "aftermath", "name" to "Aftermath",
            "triggers" to json("""[{"on": "fainted", "message": "{self} exploded!", "effects": [{"type": "damage", "target": "other", "amount": 10}]}]"""),
        ),
        mapOf(
            "id" to "grief", "name" to "Grief",
            "triggers" to json("""[{"on": "allyFainted", "effects": [{"type": "statStage", "stat": "atk", "stages": 2}]}]"""),
        ),
        mapOf(
            "id" to "once", "name" to "Once",
            "triggers" to json("""[{"on": "damaged", "limit": 1, "effects": [{"type": "heal", "amount": 10}]}]"""),
        ),
        mapOf(
            "id" to "echo", "name" to "Echo",
            "triggers" to json("""[{"on": "damaged", "effects": [{"type": "damage", "amount": 1}]}]"""),
        ),
        mapOf(
            "id" to "sponge", "name" to "Sponge",
            "triggers" to json("""[{"on": "modifyDamageTaken", "condition": "move.type == 'fire'", "message": "{self} soaked it up!", "effects": [{"type": "multiplyDamage", "value": 0}]}]"""),
        ),
        mapOf(
            "id" to "spite", "name" to "Spite",
            "triggers" to json("""[{"on": "damaged", "effects": [{"type": "damage", "target": "other", "amount": 1}]}]"""),
        ),
        mapOf(
            "id" to "dazer", "name" to "Dazer",
            "triggers" to json("""[{"on": "switchIn", "effects": [{"type": "applyStatus", "target": "enemies", "status": "dazed"}]}]"""),
        ),
        mapOf(
            "id" to "late", "name" to "Late",
            "triggers" to json("""[{"on": "turnEnd", "effects": [{"type": "applyStatus", "target": "enemies", "status": "dazed"}]}]"""),
        ),
        mapOf(
            "id" to "self_sear", "name" to "Self Sear",
            "triggers" to json("""[{"on": "switchIn", "effects": [{"type": "applyStatus", "target": "self", "status": "searing"}]}]"""),
        ),
        mapOf(
            "id" to "self_pyre", "name" to "Self Pyre",
            "triggers" to json("""[{"on": "switchIn", "effects": [{"type": "applyStatus", "target": "self", "status": "pyre"}]}]"""),
        ),
        mapOf(
            "id" to "endure", "name" to "Endure",
            "triggers" to json(
                """[{"on": "modifyDamageTaken", "condition": "self.hp == self.maxHp && damage >= self.hp", "limit": 1,
                     "message": "{self} endured!", "effects": [{"type": "multiplyDamage", "value": "(self.hp - 1) / damage"}]}]"""
            ),
        ),
        mapOf(
            "id" to "weigh", "name" to "Weigh",
            "triggers" to json("""[{"on": "modifyDamageTaken", "condition": "move.power < 50", "message": "Weighed!", "effects": []}]"""),
        ),
        mapOf(
            "id" to "dampen", "name" to "Dampen",
            "triggers" to json(
                """[{"on": "modifyDamageTaken", "condition": "hitType == 'crushing'", "message": "{self} dampened the crush!",
                     "effects": [{"type": "multiplyDamage", "value": 0.5}]}]"""
            ),
        ),
        mapOf(
            "id" to "sense", "name" to "Sense", "statModifiers" to mapOf("def" to 2),
            "triggers" to json(
                """[{"on": "fieldSet", "message": "{self} noticed {field} (set by {other})!", "effects": []},
                    {"on": "fieldRemoved", "condition": "field == 'rain'", "message": "{self} saw the {field} end.", "effects": []}]"""
            ),
        ),
    )

    private val fields = listOf(
        mapOf(
            "id" to "rain", "name" to "Rain", "group" to "weather", "duration" to 5,
            "triggers" to json(
                """[{"on": "modifyDamageDealt", "condition": "move.type == 'water'", "effects": [{"type": "multiplyDamage", "value": 1.5}]},
                    {"on": "modifyDamageDealt", "condition": "move.type == 'fire'", "effects": [{"type": "multiplyDamage", "value": 0.5}]}]"""
            ),
            "startMessage" to "It started to rain!", "endMessage" to "The rain stopped.",
        ),
        mapOf("id" to "sun", "name" to "Sun", "group" to "weather", "duration" to 5),
        // Sets another weather when it is replaced (group exclusivity must still hold).
        mapOf(
            "id" to "sand", "name" to "Sand", "group" to "weather", "duration" to 5,
            "triggers" to json("""[{"on": "fieldRemoved", "condition": "field == 'sand'", "effects": [{"type": "setField", "field": "hail"}]}]"""),
        ),
        mapOf("id" to "hail", "name" to "Hail", "group" to "weather", "duration" to 5),
        mapOf(
            "id" to "screen", "name" to "Screen", "scope" to "side", "duration" to 5,
            "triggers" to json("""[{"on": "modifyDamageTaken", "effects": [{"type": "multiplyDamage", "value": 0.5}]}]"""),
        ),
        mapOf("id" to "tailwind", "name" to "Tailwind", "scope" to "side", "duration" to 3, "statModifiers" to mapOf("spe" to 2)),
        mapOf(
            "id" to "spikes", "name" to "Spikes", "scope" to "side", "group" to "hazards", "stacking" to "stack", "maxStacks" to 3,
            "triggers" to json("""[{"on": "switchIn", "message": "{self} is hurt by {field}!", "effects": [{"type": "damage", "amount": "10 * stacks"}]}]"""),
        ),
        mapOf(
            "id" to "thorns", "name" to "Thorns", "scope" to "side",
            "triggers" to json(
                """[{"on": "damaged", "for": "opponents", "condition": "damage > 1", "message": "{self} is pricked by {field}!",
                     "effects": [{"type": "damage", "amount": 1}]}]"""
            ),
        ),
        mapOf("id" to "aura", "name" to "Aura", "triggers" to json("""[{"on": "turnEnd", "message": "{self} senses {other}.", "effects": []}]""")),
        mapOf(
            "id" to "watch", "name" to "Watch", "scope" to "side",
            "triggers" to json("""[{"on": "turnEnd", "for": "all", "message": "{self} is watched.", "effects": []}]"""),
        ),
        mapOf("id" to "room", "name" to "Room", "duration" to 5, "rules" to mapOf("reverseSpeed" to true)),
        mapOf("id" to "quickbar", "name" to "Quickbar", "scope" to "side", "rules" to mapOf("actionBarFill" to "speed * 3")),
        mapOf("id" to "flat", "name" to "Flat", "rules" to mapOf("damage" to "50")),
        mapOf("id" to "flatter", "name" to "Flatter", "scope" to "side", "stacking" to "stack", "maxStacks" to 3, "rules" to mapOf("damage" to "7 + stacks")),
        mapOf("id" to "fog", "name" to "Fog", "rules" to mapOf("hitChance" to 0)),
        mapOf("id" to "focus", "name" to "Focus", "rules" to mapOf("critChance" to 1, "critMultiplier" to 3)),
        mapOf("id" to "null_zone", "name" to "Null Zone", "rules" to mapOf("disableAbilities" to true, "disableItems" to "hasType(self, 'fire')")),
        mapOf(
            "id" to "grounded", "name" to "Grounded",
            "rules" to mapOf("blockMove" to "move.id == 'jab' || (move.id == 'tackle' && hasType(target, 'ghost'))"),
        ),
        mapOf("id" to "veil", "name" to "Veil", "scope" to "side", "rules" to mapOf("blockStatus" to "statusKind == 'major' && !sameSide(self, other)")),
        mapOf(
            "id" to "mist", "name" to "Mist", "scope" to "side",
            "rules" to mapOf("blockStatStage" to "stages < 0 && !sameSide(self, other)", "blockStatStageMessage" to "{self}'s {stat} is protected by {field}!"),
        ),
        mapOf("id" to "power", "name" to "Power", "stacking" to "stack", "maxStacks" to 3, "statModifiers" to mapOf("atk" to "1 + 0.5 * stacks")),
        mapOf("id" to "fresh", "name" to "Fresh", "duration" to 2, "stacking" to "refresh", "startMessage" to "Fresh ({stacks}).", "endMessage" to ""),
        mapOf(
            "id" to "timebomb", "name" to "Timebomb", "duration" to 1,
            "triggers" to json(
                """[{"on": "fieldRemoved", "condition": "field == 'timebomb'", "message": "{field} went off on {self}!",
                     "effects": [{"type": "damage", "amount": 30}]}]"""
            ),
        ),
    )

    private val items = listOf(
        mapOf(
            "id" to "armor", "name" to "Armor",
            "triggers" to json("""[{"on": "modifyDamageTaken", "effects": [{"type": "multiplyDamage", "value": 0.5}]}]"""),
        ),
        mapOf(
            "id" to "berry", "name" to "Berry",
            "triggers" to json(
                """[{"on": "damaged", "condition": "self.hpPercent <= 50", "message": "{self} ate its {item}!",
                     "effects": [{"type": "heal", "amount": 50}, {"type": "consumeItem"}]}]"""
            ),
        ),
        mapOf(
            "id" to "ribbon", "name" to "Ribbon",
            "triggers" to json(
                """[{"on": "modifyDamageTaken", "condition": "damage >= self.hp", "message": "{self} hung on!",
                     "effects": [{"type": "multiplyDamage", "value": "(self.hp - 1) / damage"}]}]"""
            ),
        ),
        mapOf(
            "id" to "trophy", "name" to "Trophy",
            "triggers" to json("""[{"on": "enemyFainted", "message": "{self} cheers for the fallen {other}!", "effects": []}]"""),
        ),
    )

    /** Moves of the trigger tests (new targets, effects and move keys); only in [triggerSetup]. */
    private val triggerMoves: List<Map<String, Any?>> = listOf(
        move(
            "kick3", "power" to "20 * hitIndex", "hits" to 3,
            "triggers" to json(
                """[{"on": "hit", "condition": "hitIndex == hitCount", "message": "Last of {damage}!", "effects": []},
                    {"on": "modifyDamageDealt", "condition": "hitIndex == 2", "message": "Second hit coming.", "effects": []}]"""
            ),
        ),
        move("each3", "power" to 10, "hits" to 3, "accuracy" to 50, "hitAccuracy" to "each"),
        move("once3", "power" to 10, "hits" to 3, "accuracy" to 50),
        move("pierce", "ignoreDefense" to 100),
        move("half_pierce", "ignoreDefense" to 50),
        move("beam", "power" to 80, "pp" to 5, "charge" to mapOf("invulnerable" to true)),
        move("solar", "power" to 80, "charge" to mapOf("turns" to 2, "message" to "{user} soaks up light!", "skipIf" to "fieldActive('sun')")),
        move("sweep", "power" to 10, "hits" to 4, "target" to "randomEnemies"),
        move("snipe", "power" to 10, "target" to "lowestHpEnemy"),
        move("payback", "power" to "target.acted ? 100 : 10"),
        move("u_turn", "power" to 10, "triggers" to json("""[{"on": "afterMove", "effects": [{"type": "switchOut"}]}]""")),
        move("again", "power" to 10, "triggers" to json("""[{"on": "afterMove", "effects": [{"type": "useMove", "move": "same", "moveTarget": "target"}]}]""")),
        move(
            "jab_twice", "power" to 10,
            "triggers" to json("""[{"on": "afterMove", "effects": [{"type": "useMove", "move": "jab", "moveTarget": "target", "free": false}]}]"""),
        ),
        move(
            "strike_plus", "power" to 10,
            "triggers" to json("""[{"on": "hit", "message": "Struck!", "effects": [{"type": "attack", "power": 30}]}]"""),
        ),
        move(
            "ghost_strike", "power" to 10,
            "triggers" to json("""[{"on": "hit", "effects": [{"type": "attack", "power": 30, "moveType": "normal"}]}]"""),
        ),
        statusMove("patch", """{"type": "heal", "amount": 50}""", target = "lowestHpAlly"),
        statusMove("roar", """{"type": "forceSwitch"}"""),
        statusMove("steal_buffs", """{"type": "transferStatus", "kind": "buff", "count": 0}"""),
        statusMove("copy_buffs", """{"type": "transferStatus", "kind": "buff", "count": 0, "mode": "copy"}"""),
        statusMove("give_burn", """{"type": "transferStatus", "target": "self", "to": "target", "status": "burn"}"""),
        statusMove("give_debuffs", """{"type": "transferStatus", "target": "self", "to": "target", "kind": "debuff", "count": 0}"""),
        statusMove("copy_cat", """{"type": "copyStages"}"""),
        statusMove("pain_swap", """{"type": "swapHp"}"""),
        statusMove("share", """{"type": "balanceHp", "target": "team"}""", target = "self"),
        statusMove("set_half", """{"type": "setHp", "amount": "recipient.maxHp / 2"}"""),
        statusMove("set_zero", """{"type": "setHp", "amount": 0}"""),
        statusMove(
            "future",
            """{"type": "delay", "turns": 2, "message": "{target} is struck by the future!", "effects": [{"type": "attack", "power": 50}]}""",
        ),
        statusMove("wish", """{"type": "delay", "turns": 1, "attachTo": "self", "effects": [{"type": "heal", "target": "self", "amount": 50}]}""", target = "self"),
        statusMove(
            "chain_delay",
            """{"type": "delay", "turns": 1, "attachTo": "self", "effects": [{"type": "message", "text": "tick"},
                {"type": "delay", "turns": 1, "attachTo": "self", "effects": [{"type": "message", "text": "tock"}]}]}""",
            target = "self",
        ),
        statusMove("haste", """{"type": "extraTurn", "target": "self"}""", target = "self"),
        statusMove("group_heal", """{"type": "heal", "target": "team", "amount": "recipient.maxHp * 0.2"}""", target = "self"),
    )

    /** Abilities of the trigger tests (new events); only in [triggerSetup]. */
    private val triggerAbilities: List<Map<String, Any?>> = listOf(
        ability("counter", """[{"on": "attacked", "condition": "landed", "effects": [{"type": "useMove"}]}]"""),
        ability("watchful", """[{"on": "targeted", "message": "{self} watches {other}'s {move}.", "effects": []}]"""),
        ability(
            "noter",
            """[{"on": "attacked", "condition": "landed", "message": "Hit for {damage}.", "effects": []},
                {"on": "attacked", "condition": "!landed", "message": "{self} dodged {other}.", "effects": []}]""",
        ),
        ability("assist", """[{"on": "allyUsedMove", "effects": [{"type": "useMove", "moveTarget": "otherTarget", "power": 50}]}]"""),
        ability("spy", """[{"on": "enemyUsedMove", "message": "{self} saw {other} use {move}.", "effects": []}]"""),
        ability("guardian", """[{"on": "allyDamaged", "message": "{self} saw {other} lose {damage}.", "effects": []}]"""),
        ability("gloat", """[{"on": "enemyDamaged", "message": "{self} enjoys {other} losing {damage}.", "effects": []}]"""),
        ability("inflictor", """[{"on": "statusInflicted", "message": "{self} gave {other} {status}.", "effects": []}]"""),
        ability(
            "threshold",
            """[{"on": "damaged", "condition": "self.hpPercent < 50 && hpBefore * 100 / self.maxHp >= 50", "message": "{self} dropped below half!", "effects": []}]""",
        ),
    )

    private fun statusMove(id: String, effect: String, target: String = "enemy"): Map<String, Any?> =
        move(id, "category" to "status", "power" to null, "target" to target, "triggers" to json("""[{"on": "hit", "effects": [$effect]}]"""))

    private fun ability(id: String, triggers: String): Map<String, Any?> =
        mapOf("id" to id, "name" to id.replaceFirstChar { it.uppercase() }, "triggers" to json(triggers))

    /** Writes a setup folder with [rules] and the shared content (plus the trigger tests' content if [triggers]), and loads it. */
    fun setup(rules: Map<String, Any?> = rules(), triggers: Boolean = false): Setup {
        val folder = File(dir, "lab${counter++}")
        val allMoves = if (triggers) moves + triggerMoves else moves
        val learnable = allMoves.map { it["id"] as String }
        Json.writeFile(File(folder, "Rules.json"), rules)
        Json.writeFile(File(folder, "Types.json"), types)
        Json.writeFile(File(folder, "Bakemons.json"), mapOf("bakemons" to if (triggers) species.map { it + ("moves" to learnable) } else species))
        Json.writeFile(File(folder, "Moves.json"), mapOf("moves" to allMoves))
        Json.writeFile(File(folder, "Abilities.json"), mapOf("abilities" to if (triggers) abilities + triggerAbilities else abilities))
        Json.writeFile(File(folder, "Items.json"), mapOf("items" to items))
        Json.writeFile(File(folder, "Statuses.json"), mapOf("statuses" to statuses))
        Json.writeFile(File(folder, "Fields.json"), mapOf("fields" to fields))
        return Setup.load(folder)
    }

    /** [setup] with the content of the trigger tests. */
    fun triggerSetup(rules: Map<String, Any?> = rules()): Setup = setup(rules, triggers = true)
}

/** A status move aimed at the user whose `hit` trigger runs the effect [effect] (JSON). */
private fun fieldMove(id: String, effect: String): Map<String, Any?> = mapOf(
    "id" to id, "name" to id.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
    "category" to "status", "target" to "self", "triggers" to json("""[{"on": "hit", "effects": [$effect]}]"""),
)

private fun member(species: String, vararg moves: String, ability: String? = null, item: String? = null) =
    Bakemon(species = species, level = 50, ability = ability, item = item, moves = moves.toList().ifEmpty { listOf("tackle") })

private fun team(vararg members: Bakemon) = Team("Test", members.toList())

private val idle = Script { battle, actor -> use("wait")(battle, actor) }

/** A manager whose leads are already on the field (no battle loop): for testing single operations. */
private fun field(
    setup: Setup,
    t0: Team,
    t1: Team,
    seed: Long = 1,
    recorder: Recorder = Recorder(),
): Pair<Manager, Recorder> {
    val m = Manager(setup, listOf(t0, t1), listOf(idle, idle), recorder, seed)
    for (side in m.battle.sides) {
        for (slot in side.active.indices) side.team.getOrNull(slot)?.let { m.switchIn(it, slot) }
    }
    return m to recorder
}

private fun battle(setup: Setup, t0: Team, t1: Team, c0: Controller, c1: Controller, seed: Long = 1): Pair<Manager, Recorder> {
    val recorder = Recorder()
    val m = Manager(setup, listOf(t0, t1), listOf(c0, c1), recorder, seed)
    m.run()
    return m to recorder
}

private fun Manager.mon(side: Int, index: Int = 0): Battler = battle.sides[side].team[index]

private fun Manager.use(user: Battler, moveId: String, target: Battler? = null) {
    val slot = user.moves.first { it.def.id == moveId }
    useMove(user, slot.def, slot, target ?: battle.enemies(user).firstOrNull())
}

// ---------------------------------------------------------------------------------------------------------------
// Tests

private fun parsingTests() {
    fun obj(text: String) = JsonObj.of(json(text), "t")

    val t = Trigger.parse(obj("""{"on": "hit", "chance": 30, "limit": 2, "message": "m", "effects": [{"type": "damage", "amount": 10}]}"""), TargetSelector.TARGET)
    Check.eq(EventType.HIT, t.on, "trigger event")
    Check.eq(2, t.limit, "trigger limit")
    Check.eq("m", t.message, "trigger message")
    Check.near(30.0, t.chance!!.constant!!, "trigger chance")
    val damage = t.effects.single() as Effect.Damage
    Check.eq(TargetSelector.TARGET, damage.target, "default effect target")
    Check.eq(0, Trigger.parse(obj("""{"on": "turnEnd", "effects": []}"""), TargetSelector.SELF).limit, "default limit")
    Check.eq(0, Trigger.parseList(obj("""{}"""), "triggers", TargetSelector.SELF).size, "missing trigger list")

    val apply = Effect.parse(obj("""{"type": "applyStatus", "status": "burn", "target": "enemies", "duration": "2 + 1"}"""), TargetSelector.SELF)
    Check.ok(apply is Effect.ApplyStatus && apply.target == TargetSelector.ENEMIES && apply.status == "burn", "applyStatus parsed")
    val remove = Effect.parse(obj("""{"type": "removeStatus", "kind": "debuff"}"""), TargetSelector.SELF) as Effect.RemoveStatus
    Check.ok(remove.status == null && remove.kind == bakemon.StatusKind.DEBUFF, "removeStatus by kind")
    Check.ok(Effect.parse(obj("""{"type": "message", "text": "hi"}"""), TargetSelector.SELF) is Effect.Message, "message effect")
    Check.ok(Effect.parse(obj("""{"type": "MultiplyDamage", "value": 2}"""), TargetSelector.SELF) is Effect.MultiplyDamage, "type names ignore case")

    Check.throws<SetupException>("unknown event") { Trigger.parse(obj("""{"on": "hitt", "effects": []}"""), TargetSelector.SELF) }
    Check.throws<SetupException>("missing effects") { Trigger.parse(obj("""{"on": "hit"}"""), TargetSelector.SELF) }
    Check.throws<SetupException>("negative limit") { Trigger.parse(obj("""{"on": "hit", "limit": -1, "effects": []}"""), TargetSelector.SELF) }
    Check.throws<SetupException>("unknown effect type") { Effect.parse(obj("""{"type": "explode"}"""), TargetSelector.SELF) }
    Check.throws<SetupException>("unknown target") { Effect.parse(obj("""{"type": "heal", "target": "friend", "amount": 1}"""), TargetSelector.SELF) }
    Check.throws<SetupException>("missing amount") { Effect.parse(obj("""{"type": "damage"}"""), TargetSelector.SELF) }
    Check.throws<SetupException>("applyStatus without status") { Effect.parse(obj("""{"type": "applyStatus"}"""), TargetSelector.SELF) }
    Check.throws<SetupException>("removeStatus without status/kind") { Effect.parse(obj("""{"type": "removeStatus"}"""), TargetSelector.SELF) }
    Check.throws<SetupException>("bad status kind") { Effect.parse(obj("""{"type": "removeStatus", "kind": "awful"}"""), TargetSelector.SELF) }
    Check.throws<SetupException>("bad formula") { Effect.parse(obj("""{"type": "heal", "amount": "1 +"}"""), TargetSelector.SELF) }
    try {
        Trigger.parse(obj("""{"on": "hit", "effects": [{"type": "heal", "target": "friend", "amount": 1}]}"""), TargetSelector.SELF)
    } catch (e: SetupException) {
        Check.ok(e.message!!.contains("t.effects[0].target"), "error has the JSON path: ${e.message}")
    }
}

private fun contextTests(lab: EngineLab) {
    val setup = lab.setup()
    val (m, _) = field(setup, team(member("blaze", "ember", "fury", ability = "brute", item = "armor")), team(member("leaf", "tackle")))
    val a = m.mon(0)
    val d = m.mon(1)
    val battle = m.battle
    val ember = setup.moves.require("ember")
    fun eval(src: String, self: Battler? = a, other: Battler? = d, move: bakemon.MoveDef? = ember, vars: Map<String, Any?> = emptyMap()) =
        Formula.parse(src).evalAny(battle.context(self, other, move = move, vars = vars))

    d.hp = 150
    Check.near(100.0, eval("user.atk") as Double, "user.atk")
    Check.near(150.0, eval("target.hp") as Double, "target.hp")
    Check.near(75.0, eval("other.hpPercent") as Double, "other.hpPercent")
    Check.near(200.0, eval("other.maxHp") as Double, "other.maxHp")
    Check.near(50.0, eval("self.level") as Double, "level")
    Check.eq("blaze", eval("self.id"), "self.id")
    Check.eq("Leaf", eval("target.name"), "target.name")
    Check.near(0.0, eval("other.hp", other = null) as Double, "absent monster number")
    Check.eq("", eval("other.name", other = null), "absent monster string")
    Check.near(0.0, eval("hasType(other, 'grass')", other = null) as Double, "absent monster in a function")
    Check.near(1.0, eval("hasType(user, 'fire') && hasType(target, 'GRASS')") as Double, "hasType")
    Check.near(1.0, eval("hasAbility(self, 'brute') && hasItem(self) && hasItem(self, 'armor') && !hasItem(other)") as Double, "ability/item functions")
    Check.near(0.0, eval("hasFlag('contact')") as Double, "hasFlag")
    Check.eq("fire", eval("move.type"), "move.type")
    Check.eq("physical", eval("move.category"), "move.category")
    Check.near(40.0, eval("move.power") as Double, "move.power")
    Check.near(0.0, eval("move.accuracy") as Double, "move.accuracy is 0 when the move never misses")
    Check.near(0.0, eval("move.power", move = null) as Double, "no move -> 0")
    Check.eq("", eval("move.type", move = null), "no move -> ''")
    Check.near(1.0, eval("turn + 1") as Double, "turn")
    Check.near(1.0, eval("enemies") as Double, "enemies")
    Check.near(0.0, eval("allies") as Double, "allies")
    Check.near(7.0, eval("damage + isCrit", vars = mapOf("damage" to 7, "isCrit" to false)) as Double, "event vars")
    Check.throws<ExpressionException>("unknown variable") { eval("user.atkk") }
    Check.near(0.0, eval("damage + amount") as Double, "event variables absent from the event read as 0")
    Check.eq("", eval("status"), "absent status variable reads as ''")
    Check.near(0.0, eval("stacks + turnsLeft") as Double, "stacks/turnsLeft outside a status read as 0")
    Check.throws<ExpressionException>("unknown name") { eval("speedy") }
    Check.throws<ExpressionException>("unknown function") { eval("explode(1)") }

    // Stats: stages, raw/base/stage fields, rawStats mode.
    a.stages["atk"] = 2
    Check.near(200.0, eval("self.atk") as Double, "stage multiplier applies")
    Check.near(100.0, eval("self.raw.atk") as Double, "raw stat")
    Check.near(100.0, eval("self.base.atk") as Double, "base stat")
    Check.near(2.0, eval("self.stage.atk") as Double, "stage field")
    Check.near(100.0, Formula.parse("self.atk").eval(battle.context(a, rawStats = true)), "rawStats context")
    a.stages.clear()

    // move.power inside its own power formula reads 0.
    val fury = setup.moves.require("fury")
    Check.near(50.0, m.damage.power(battle, a, d, fury), "power formula sees move.power = 0")
    Check.near(50.0, eval("move.power", move = fury) as Double, "move.power evaluates the power formula")
}

private fun damageTests(lab: EngineLab) {
    val setup = lab.setup()
    run {
        val (m, rec) = field(setup, team(member("blaze", "ember", "tackle", "bubble")), team(member("leaf", "tackle")))
        val a = m.mon(0)
        val d = m.mon(1)
        val r = m.damage.calculate(m.battle, a, d, setup.moves.require("ember"), 1)
        Check.eq(120, r.damage, "STAB x super effective damage")
        Check.ok(r.sameType && !r.isCrit && r.effectiveness == 2.0, "result flags")
        Check.eq(40, m.damage.calculate(m.battle, a, d, setup.moves.require("tackle"), 1).damage, "neutral, no STAB")
        m.use(a, "ember")
        Check.eq(80, d.hp, "ember took 120 HP")
        Check.ok(rec.has("Blaze used Ember!") && rec.has("It's super effective!") && rec.has("Leaf took 120 damage."), "move log")
        Check.ok(rec.index("It's super effective!") < rec.index("Leaf took 120 damage."), "effectiveness message before damage")
    }
    run {
        val (m, rec) = field(setup, team(member("plain", "ember")), team(member("puddle", "tackle")))
        m.use(m.mon(0), "ember")
        Check.eq(180, m.mon(1).hp, "not very effective: 40 x 0.5")
        Check.ok(rec.has("It's not very effective..."), "weak message")
    }
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle")), team(member("spook", "tackle")))
        m.use(m.mon(0), "tackle")
        Check.eq(200, m.mon(1).hp, "type immunity: no damage")
        Check.ok(rec.has("It doesn't affect Spook..."), "immune message with {target}")
        Check.eq(0, m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("tackle"), 1).damage, "immune calculate = 0")
    }
    run {
        val (m, rec) = field(setup, team(member("plain", "triple", "multi")), team(member("plain", "tackle")))
        m.use(m.mon(0), "triple")
        Check.eq(170, m.mon(1).hp, "3 hits of 10")
        Check.ok(rec.has("Hit 3 times!"), "hit count message")
        m.use(m.mon(0), "multi")
        val lost = 170 - m.mon(1).hp
        Check.ok(lost % 10 == 0 && lost in 20..50, "randint(2,5) hits: lost $lost")
    }
    run {
        val critSetup = lab.setup(lab.rules("critChance" to "1"))
        val (m, rec) = field(critSetup, team(member("plain", "struggle", "fury")), team(member("plain", "tackle")))
        m.use(m.mon(0), "struggle")
        Check.eq(180, m.mon(1).hp, "forced crit doubles 10")
        Check.ok(rec.has("A critical hit!"), "crit message")
        val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), critSetup.moves.require("struggle"), 1, forceCrit = false)
        Check.ok(r.damage == 10 && !r.isCrit, "forceCrit = false")
        Check.near(1.0, m.damage.critChance(m.battle, m.mon(0), m.mon(1), critSetup.moves.require("struggle")), "crit chance")
        m.use(m.mon(0), "fury")
        Check.eq(80, m.mon(1).hp, "power formula damage (50 x 2)")
    }
    run {
        val halfSetup = lab.setup(lab.rules("critChance" to "0.5"))
        val (m, _) = field(halfSetup, team(member("plain", "jab")), team(member("plain", "tackle")))
        val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), halfSetup.moves.require("jab"), 1, estimate = true)
        Check.eq(60, r.damage, "estimate uses the expected crit multiplier (1.5)")
    }
    run {
        val (m, rec) = field(setup, team(member("plain", "whiff", "sure")), team(member("plain", "tackle")))
        repeat(20) { m.use(m.mon(0), "whiff") }
        Check.eq(200, m.mon(1).hp, "accuracy 0 never hits")
        Check.eq(20, rec.count("Plain (Player 2) avoided the attack!"), "miss messages")
        // Same name on both sides: the side name is appended; unique names are left alone.
        Check.eq("Plain (Player 1)", m.mon(0).name, "shared name gets the side name")
        val (u, _) = field(setup, team(member("plain", "tackle")), team(member("leaf", "tackle")))
        Check.ok(u.mon(0).name == "Plain" && u.mon(1).name == "Leaf", "unique names unchanged")
        Check.eq(20, rec.count("Whiffed!"), "missed trigger fires")
        repeat(3) { m.use(m.mon(0), "sure") }
        Check.eq(80, m.mon(1).hp, "accuracy 100 always hits")
        Check.near(1.0, m.damage.hitChance(m.battle, m.mon(0), m.mon(0), setup.moves.require("whiff")), "no accuracy check on self")
    }
    run {
        // multiplyDamage from an ability (dealt), an item (taken) and a move trigger.
        val (m, _) = field(setup, team(member("plain", "tackle", "lucky", "struggle", ability = "brute")), team(member("plain", "tackle", item = "armor")))
        m.use(m.mon(0), "tackle")
        Check.eq(140, m.mon(1).hp, "40 x 1.5 (STAB) x 2 (contact ability) x 0.5 (item)")
        m.use(m.mon(0), "lucky")
        Check.eq(80, m.mon(1).hp, "40 x 3 (move trigger) x 0.5 (item)")
        m.use(m.mon(0), "struggle")
        Check.eq(75, m.mon(1).hp, "no contact flag: 10 x 0.5")
    }
    run {
        // modifyDamageTaken sees the damage after the attacker's modifiers: 90 raw, 180 after Brute, vs 150 HP.
        val (m, rec) = field(setup, team(member("brawny", "tackle", ability = "brute")), team(member("plain", "tackle", item = "ribbon")))
        m.mon(1).hp = 150
        m.use(m.mon(0), "tackle")
        Check.ok(m.mon(1).hp in 1..2, "survived the boosted hit at 1 HP (hp ${m.mon(1).hp})")
        Check.ok(rec.has("Plain hung on!"), "survival message")
    }
    run {
        // A hit fully cancelled by a modifier prints no effectiveness line.
        val (m, rec) = field(setup, team(member("blaze", "ember")), team(member("leaf", "tackle", ability = "sponge")))
        m.use(m.mon(0), "ember")
        Check.eq(200, m.mon(1).hp, "absorbed fire move")
        Check.ok(rec.has("Leaf soaked it up!") && !rec.has("It's super effective!"), "no type line for an absorbed hit")
    }
    run {
        // "message" on applyStatus / consumeItem replaces the default line ("" = silent).
        val (m, rec) = field(setup, team(member("plain", "hush_burn", "loud_burn", "pluck")), team(member("leaf", "tackle", item = "armor")))
        val a = m.mon(0)
        val d = m.mon(1)
        m.use(a, "hush_burn")
        Check.ok(d.hasStatus("burn") && !rec.has("Leaf was burned!"), "empty message silences applyStatus")
        m.removeStatus(d, d.status("burn")!!)
        m.use(a, "loud_burn")
        Check.ok(rec.has("Plain set Leaf ablaze with Loud Burn (Burn)!") && !rec.has("Leaf was burned!"), "applyStatus message override")
        m.use(a, "pluck")
        Check.ok(d.item == null && rec.has("Plain plucked Leaf's Armor!") && !rec.has("Leaf's Armor was used up."), "consumeItem message override")
    }
    run {
        // A move cancelled by its own beforeMove trigger was used and failed: it costs PP.
        val (m, rec) = field(setup, team(member("plain", "rest_up")), team(member("leaf", "tackle")))
        val slot = m.mon(0).moves.first { it.def.id == "rest_up" }
        m.useMove(m.mon(0), slot.def, slot, null)
        Check.eq(4, slot.pp, "a self-cancelled move costs PP")
        Check.ok(rec.index("Plain used Rest Up!") >= 0 && rec.index("But it failed!") > rec.index("Plain used Rest Up!"), "used, then failed")
    }
}

private fun statusTests(lab: EngineLab) {
    val setup = lab.setup()
    val (m, rec) = field(setup, team(member("blaze", "tackle")), team(member("leaf", "tackle", "burn_it")))
    val a = m.mon(0)
    val d = m.mon(1)

    Check.ok(m.applyStatus(d, "burn", a), "burn applied")
    Check.ok(rec.has("Leaf was burned!"), "custom apply message")
    Check.near(50.0, d.stat("atk"), "burn halves attack")
    Check.ok(!m.applyStatus(a, "burn", d), "fire type is immune to burn")
    Check.ok(!m.applyStatus(d, "poison", a), "only one major status")
    Check.ok(!m.applyStatus(d, "burn", a), "ignore stacking: no second burn")
    Check.eq(1, d.statuses.size, "still one status")
    Check.eq(a, d.status("burn")!!.source, "status source")

    // A status move that fails says why (once).
    m.use(d, "burn_it", a)
    Check.ok(rec.has("It doesn't affect Blaze..."), "status move failure is reported: ${rec.texts().takeLast(3)}")

    // Stacking.
    Check.ok(m.applyStatus(a, "focus", null), "focus x1")
    Check.near(150.0, a.stat("atk"), "focus x1 modifier")
    Check.ok(m.applyStatus(a, "focus", null, stacks = 5), "focus stacks")
    Check.eq(3, a.status("focus")!!.stacks, "stacks capped at maxStacks")
    Check.near(250.0, a.stat("atk"), "focus x3 modifier")
    Check.ok(!m.applyStatus(a, "focus", null), "permanent status at max stacks fails")

    // Refresh.
    Check.ok(m.applyStatus(a, "guard", null), "guard")
    a.status("guard")!!.turnsLeft = 1
    Check.ok(m.applyStatus(a, "guard", null), "guard refresh")
    Check.eq(2, a.status("guard")!!.turnsLeft, "refresh resets the duration")
    Check.ok(m.applyStatus(a, "regen", null), "regen")
    Check.ok(!m.applyStatus(a, "regen", null), "regen ignores re-application")
    Check.ok(m.applyStatus(d, "regen", null, duration = 5), "duration override")
    Check.eq(5, d.status("regen")!!.turnsLeft, "duration override value")

    // Removal by effect / by kind, default message, statusRemoved.
    m.removeStatus(d, d.status("regen")!!)
    Check.ok(rec.has("Leaf is no longer affected by Regen."), "default remove message")
    Check.ok(!d.hasStatus("regen"), "removed")

    // A modifier formula reads raw stats (no recursion).
    val (m2, _) = field(setup, team(member("brawny", "tackle")), team(member("plain", "tackle")))
    m2.applyStatus(m2.mon(0), "odd", null)
    m2.applyStatus(m2.mon(0), "focus", null)
    Check.near(150.0, m2.mon(0).stat("def"), "def modifier = raw atk / raw def")

    // Burn deals damage at turnEnd; leech heals its source (status 'other' = source).
    val (m3, rec3) = field(setup, team(member("plain", "seed")), team(member("leaf", "tackle")))
    val user = m3.mon(0)
    val target = m3.mon(1)
    user.hp = 100
    m3.use(user, "seed")
    Check.ok(target.hasStatus("leech"), "leech applied by a move")
    m3.events.emit(EventType.TURN_END, target)
    Check.eq(190, target.hp, "leech damages its owner")
    Check.eq(110, user.hp, "leech heals the monster that applied it")
    Check.ok(rec3.has("Plain drains Leaf!"), "{other} = status source in messages")
    m3.applyStatus(target, "burn", null)
    m3.events.emit(EventType.TURN_END, target)
    Check.eq(155, target.hp, "burn 1/8 + leech 10")
}

private fun durationTests(lab: EngineLab) {
    // speed_order: duration 2 lasts for 2 rounds after the one it was applied in.
    run {
        val setup = lab.setup(lab.rules("turnLimit" to 5))
        val (_, rec) = battle(
            setup,
            team(member("plain", "guard_up", "wait")), team(member("slow", "wait")),
            Script(choose = useThen("guard_up", 1, "wait")), idle,
        )
        Check.eq(listOf(1), rec.turnsOf("Plain is affected by Guard!"), "guard applied in round 1")
        Check.eq(listOf(3), rec.turnsOf("Plain is no longer affected by Guard."), "duration 2 ends at the end of round 3")
        Check.eq(BattleResult(null, 5, "Turn limit reached"), rec.result, "turn limit draw")
    }
    // speed_order: duration 0 lasts until the end of the round (flinch), and cancels the move without paying PP.
    run {
        val setup = lab.setup(lab.rules("turnLimit" to 2))
        val (m, rec) = battle(
            setup,
            team(member("plain", "scare", "wait")), team(member("slow", "jab")),
            Script(choose = useThen("scare", 1, "wait")), Script(choose = use("jab")),
        )
        Check.eq(listOf(1), rec.turnsOf("Slow flinched!"), "flinched in round 1")
        Check.eq(listOf(1), rec.turnsOf("Slow is no longer affected by Flinch."), "duration 0 ends with the round")
        Check.eq(listOf(2), rec.turnsOf("Slow used Jab!"), "acts normally in round 2")
        Check.eq(1, m.mon(1).moves[0].pp, "a cancelled move costs no PP")
    }
    // action_bar: fresh rule. A (faster) and B alternate: A=1, B=2, A=3, B=4...
    run {
        val setup = lab.setup(lab.rules("turnSystem" to "action_bar", "turnLimit" to 6))
        val (_, rec) = battle(
            setup,
            team(member("fast", "double_status", "wait")), team(member("even", "wait")),
            Script(choose = useThen("double_status", 1, "wait")), idle,
        )
        Check.eq(listOf("Fast", "Even", "Fast", "Even", "Fast", "Even"), rec.actors.map { it?.name }, "alternating turns")
        Check.eq(listOf(3), rec.turnsOf("Fast is no longer affected by Charged."), "self-applied duration 1 lasts one more own turn")
        Check.eq(listOf(2), rec.turnsOf("Even is no longer affected by Dazed."), "enemy-applied duration 1 ends after its next turn")
        Check.eq(listOf(1), rec.turnsOf("Fast is no longer affected by Brace."), "self-applied duration 0 ends with the current turn")
    }
    // action_bar: duration 0 on an enemy lasts until the end of its next turn.
    run {
        val setup = lab.setup(lab.rules("turnSystem" to "action_bar", "turnLimit" to 4))
        val (_, rec) = battle(
            setup,
            team(member("fast", "scare", "wait")), team(member("even", "tackle")),
            Script(choose = useThen("scare", 1, "wait")), Script(choose = use("tackle")),
        )
        Check.eq(listOf(2), rec.turnsOf("Even flinched!"), "flinch cancels the next turn")
        Check.eq(listOf(2), rec.turnsOf("Even is no longer affected by Flinch."), "then wears off")
        Check.eq(listOf(4), rec.turnsOf("Even used Tackle!"), "acts on its following turn")
    }
    // A status can react to its own end (statusRemoved).
    run {
        val setup = lab.setup(lab.rules("turnLimit" to 3))
        val (m, rec) = battle(
            setup,
            team(member("plain", "plant_bomb", "wait")), team(member("slow", "wait")),
            Script(choose = useThen("plant_bomb", 1, "wait")), idle,
        )
        // Planted by the faster Plain before Slow acted in round 1: round 1 counts, so the 1-turn bomb ends with it.
        Check.eq(listOf(1), rec.turnsOf("The bomb on Slow exploded!"), "statusRemoved fires for the removed status")
        Check.eq(170, m.mon(1).hp, "bomb damage")
    }
}

private fun operationTests(lab: EngineLab) {
    val setup = lab.setup()
    val (m, rec) = field(setup, team(member("plain", "tackle")), team(member("leaf", "tackle", item = "berry")))
    val a = m.mon(0)
    val d = m.mon(1)

    // Stat stages.
    m.changeStage(a, "atk", 2)
    Check.ok(rec.has("Plain's Attack rose sharply!"), "rose sharply")
    Check.near(200.0, a.stat("atk"), "+2 multiplier")
    m.changeStage(a, "atk", 5)
    Check.eq(6, a.stage("atk"), "clamped to max")
    Check.ok(rec.has("Plain's Attack rose drastically!"), "rose drastically")
    Check.near(400.0, a.stat("atk"), "+6 multiplier")
    m.changeStage(a, "atk", 1)
    Check.ok(rec.has("Plain's Attack won't go any higher!"), "won't go higher")
    m.changeStage(a, "atk", -8)
    Check.eq(-2, a.stage("atk"), "-8 from +6")
    Check.ok(rec.has("Plain's Attack severely fell!"), "severely fell")
    Check.near(50.0, a.stat("atk"), "-2 multiplier")
    m.changeStage(a, "def", -1)
    Check.ok(rec.has("Plain's Defense fell!"), "fell")
    m.changeStage(a, "hp", 3)
    Check.near(200.0, a.stat("hp"), "stages do not change the hp stat")

    // Shields.
    m.addShield(d, 30)
    Check.eq(20, m.dealDamage(d, 50, a, null), "shield absorbs first")
    Check.ok(d.shield == 0 && d.hp == 180, "shield broken, rest taken")
    Check.ok(rec.has("Leaf's shield absorbed 30 damage. It broke!"), "shield message")
    m.addShield(d, 15)
    Check.eq(0, m.dealDamage(d, 10, a, null), "fully absorbed")
    Check.ok(d.shield == 5 && d.hp == 180, "shield left")
    d.shield = 0

    // Berry: condition, heal, consumeItem.
    m.dealDamage(d, 90, a, null)
    Check.eq(140, d.hp, "berry healed 50 at 45%")
    Check.ok(d.item == null, "berry consumed")
    Check.ok(rec.has("Leaf ate its Berry!") && rec.has("Leaf's Berry was used up."), "item messages")
    m.dealDamage(d, 80, a, null)
    Check.eq(60, d.hp, "no berry the second time")

    // Trigger limit.
    val (m2, rec2) = field(setup, team(member("plain", "tackle", ability = "once")), team(member("plain", "tackle")))
    val o = m2.mon(0)
    m2.dealDamage(o, 50, null, null)
    m2.dealDamage(o, 50, null, null)
    Check.eq(110, o.hp, "limit 1: healed only once")
    Check.eq(1, rec2.count("Plain (Player 1) restored 10 HP."), "one heal")

    // Heal caps at max HP.
    Check.eq(90, m2.heal(o, 500, null), "heal up to max")
    Check.eq(0, m2.heal(o, 5, null), "nothing to heal")

    // Cooldown effect.
    val cdSetup = lab.setup(lab.rules("cooldowns" to true))
    val (m3, _) = field(cdSetup, team(member("plain", "tackle", "big")), team(member("plain", "tackle")))
    m3.changeCooldowns(m3.mon(0), 2)
    Check.eq(listOf(2, 2), m3.mon(0).moves.map { it.cooldown }, "cooldowns added")
    Check.eq(0, m3.battle.usableMoves(m3.mon(0)).size, "nothing usable")
    m3.changeCooldowns(m3.mon(0), -5)
    Check.eq(listOf(0, 0), m3.mon(0).moves.map { it.cooldown }, "cooldowns reduced to 0")

    // Gauge.
    val barSetup = lab.setup(lab.rules("turnSystem" to "action_bar"))
    val (m4, _) = field(barSetup, team(member("plain", "tackle")), team(member("plain", "tackle")))
    val g = m4.mon(0)
    m4.changeGauge(g, 30.0)
    Check.near(30.0, g.gaugePercent(), "gauge +30%")
    m4.changeGauge(g, -50.0)
    Check.near(0.0, g.gauge, "gauge clamped at 0")
    m4.changeGauge(g, 500.0)
    Check.near(100.0, g.gauge, "gauge clamped at size")
    m.changeGauge(a, 50.0)
    Check.near(0.0, a.gauge, "gauge is a no-op in speed_order")
}

private fun orderTests(lab: EngineLab) {
    val setup = lab.setup(lab.rules("turnLimit" to 1))
    run {
        val (_, rec) = battle(setup, team(member("slow", "quick")), team(member("swift", "tackle")), Script(choose = use("quick")), Script(choose = use("tackle")))
        Check.ok(rec.index("Slow used Quick!") in 0 until rec.index("Swift used Tackle!"), "priority beats speed")
    }
    run {
        val (_, rec) = battle(setup, team(member("slow", "tackle")), team(member("swift", "tackle")), Script(choose = use("tackle")), Script(choose = use("tackle")))
        Check.ok(rec.index("Swift used Tackle!") in 0 until rec.index("Slow used Tackle!"), "faster first")
    }
    run {
        val switcher = Script { battle, actor -> Action.Switch(battle.switchOptions(actor).first()) }
        val (m, rec) = battle(setup, team(member("swift", "quick")), team(member("slow", "tackle"), member("plain", "tackle")), Script(choose = use("quick")), switcher)
        Check.ok(rec.index("Player 2 withdraws Slow!") in 0 until rec.index("Swift used Quick!"), "switches go first")
        Check.ok(rec.index("Player 2 sends out Plain!") < rec.index("Swift used Quick!"), "replacement is in before the move")
        Check.eq(160, m.mon(1, 1).hp, "the incoming monster takes the hit")
        Check.eq(-1, m.mon(1, 0).slot, "switched-out monster is benched")
    }
    run {
        // Equal speed: random order, but both orders happen.
        val orders = (1L..40L).map { seed ->
            val (_, rec) = battle(setup, team(member("plain", "tackle")), team(member("leaf", "tackle")), Script(choose = use("tackle")), Script(choose = use("tackle")), seed)
            rec.index("Plain used Tackle!") < rec.index("Leaf used Tackle!")
        }
        Check.ok(orders.any { it } && orders.any { !it }, "speed ties are random")
    }
    run {
        // PP runs out -> fallback move (no PP spent for it).
        val ppSetup = lab.setup(lab.rules("turnLimit" to 4, "fallbackMove" to "struggle"))
        val (m, rec) = battle(ppSetup, team(member("plain", "jab")), team(member("slow", "wait")), Script(choose = use("jab")), idle)
        Check.eq(listOf(1, 2), rec.turnsOf("Plain used Jab!"), "jab twice (pp 2)")
        Check.eq(listOf(3, 4), rec.turnsOf("Plain used Struggle!"), "then the fallback move")
        Check.eq(0, m.mon(0).moves[0].pp, "pp spent")
        Check.eq(200 - 80 - 20, m.mon(1).hp, "damage of 2 jabs + 2 struggles")
    }
    run {
        // Usable moves without any legal target (an ally move in singles) count as "no usable move".
        val fbSetup = lab.setup(lab.rules("turnLimit" to 2, "fallbackMove" to "struggle"))
        val struggle = fbSetup.moves.require("struggle")
        val fallback = Script { battle, actor -> Action.UseMove(struggle, null, battle.targetOptions(actor, struggle).firstOrNull()) }
        val (_, rec) = battle(fbSetup, team(member("plain", "cheer")), team(member("slow", "wait")), fallback, idle)
        Check.eq(listOf(1, 2), rec.turnsOf("Plain used Struggle!"), "fallback accepted when no move has a target")
        // A move without a legal target is not a legal choice: chosen anyway, it is replaced by the first legal action.
        val (_, rec2) = battle(fbSetup, team(member("plain", "cheer")), team(member("slow", "wait")), Script(choose = use("cheer")), idle)
        Check.eq(listOf(1, 2), rec2.turnsOf("Plain used Struggle!"), "a targetless ally move chosen on purpose becomes the fallback move")
        Check.ok(!rec2.has("Plain used Cheer!") && !rec2.has("But there was no target..."), "and is never used: ${rec2.texts()}")
        // allAllies with no ally: the same (it used to count as having a target, so the fallback never came).
        val (_, rec3) = battle(fbSetup, team(member("plain", "rally")), team(member("slow", "wait")), Script(choose = use("rally")), idle)
        Check.eq(listOf(1, 2), rec3.turnsOf("Plain used Struggle!"), "allAllies alone on the field: the fallback move")
        for (level in engine.AiLevel.entries) {
            val (_, rec4) = battle(fbSetup, team(member("plain", "rally")), team(member("slow", "wait")), engine.AiController("AI", level), idle)
            Check.eq(listOf(1, 2), rec4.turnsOf("Plain used Struggle!"), "${level.id} AI: allAllies alone on the field is not an option: ${rec4.texts()}")
        }
    }
    run {
        // No PP and no fallback -> pass.
        val ppSetup = lab.setup(lab.rules("turnLimit" to 3))
        val (_, rec) = battle(ppSetup, team(member("plain", "jab")), team(member("slow", "wait")), Script(choose = use("jab")), idle)
        Check.eq(listOf(3), rec.turnsOf("Plain is waiting."), "passes when nothing is usable")
    }
    run {
        // Cooldown 2: usable every third turn.
        val cdSetup = lab.setup(lab.rules("turnLimit" to 7, "cooldowns" to true, "pp" to false))
        val pick = Script { battle, actor ->
            val big = actor.moves.first { it.def.id == "big" }
            val slot = if (big in battle.usableMoves(actor)) big else actor.moves.first { it.def.id == "small" }
            Action.UseMove(slot.def, slot, battle.enemies(actor).first())
        }
        val (_, rec) = battle(cdSetup, team(member("plain", "big", "small")), team(member("slow", "wait")), pick, idle)
        Check.eq(listOf(1, 4, 7), rec.turnsOf("Plain used Big!"), "cooldown 2 blocks the next 2 turns")
    }
    run {
        // An illegal action is replaced by the first legal one.
        val cheat = Script { battle, actor -> Action.UseMove(battle.setup.moves.require("finisher"), null, battle.enemies(actor).first()) }
        val (_, rec) = battle(setup, team(member("plain", "tackle")), team(member("slow", "wait")), cheat, idle)
        Check.ok(rec.has("Plain used Tackle!") && !rec.has("Plain used Finisher!"), "illegal move replaced")
    }
    run {
        // action_bar frequency: double speed acts about twice as often.
        val barSetup = lab.setup(lab.rules("turnSystem" to "action_bar", "turnLimit" to 300))
        val (_, rec) = battle(barSetup, team(member("swift", "wait")), team(member("even", "wait")), idle, idle)
        val swift = rec.actors.count { it?.name == "Swift" }
        val even = rec.actors.count { it?.name == "Even" }
        Check.eq(300, swift + even, "300 turns")
        Check.ok(swift.toDouble() / even in 1.9..2.1, "speed 200 vs 100 acts twice as often ($swift vs $even)")
        Check.eq(300, rec.result!!.turns, "action_bar turn limit")
    }
    run {
        // Gauge effect: a monster that refills its own bar acts again immediately.
        val barSetup = lab.setup(lab.rules("turnSystem" to "action_bar", "turnLimit" to 5))
        val (m, rec) = battle(barSetup, team(member("fast", "rush", "wait")), team(member("even", "wait")), Script(choose = useThen("rush", 1, "wait")), idle)
        Check.eq(listOf("Fast", "Fast", "Even", "Fast", "Even"), rec.actors.map { it?.name }, "extra turn from the gauge effect")
        Check.ok(rec.has("Fast's action bar was filled by 100%!"), "gauge message")
        Check.ok(m.battle.ended, "ended")
    }
    run {
        // turnOrderPreview: Swift fills in 0.5, Even in 1.0. At t=1 both are full: the higher gauge before the advance
        // (Even, 50 vs 0) goes first, then Swift acts at once with its full bar.
        val barSetup = lab.setup(lab.rules("turnSystem" to "action_bar", "turnLimit" to 6))
        val (m, _) = field(barSetup, team(member("swift", "wait")), team(member("even", "wait")))
        val preview = m.battle.turnOrderPreview(6).map { it.name }
        Check.eq(listOf("Swift", "Even", "Swift", "Swift", "Even", "Swift"), preview, "turn order preview")
        Check.eq(m.battle.allActive().map { 0.0 }, m.battle.allActive().map { it.gauge }, "preview does not change gauges")
        val (_, rec) = battle(barSetup, team(member("swift", "wait")), team(member("even", "wait")), idle, idle)
        Check.eq(preview, rec.actors.map { it?.name }, "preview matches the real order")
        Check.eq(0, lab.setup().let { s -> Manager(s, listOf(team(member("plain")), team(member("plain"))), listOf(idle, idle)).battle.turnOrderPreview(3).size }, "no preview in speed_order")
    }
}

private fun faintTests(lab: EngineLab) {
    // speed_order: the fainted monster is replaced at the end of the round.
    run {
        val setup = lab.setup()
        val p2 = Script(choose = use("tackle"))
        val (m, rec) = battle(setup, team(member("plain", "tackle", ability = "moxie")), team(member("weak", "tackle"), member("backup", "tackle")), Script(choose = use("tackle")), p2)
        Check.eq(0, rec.result!!.winner, "player 1 wins")
        Check.ok(rec.index("Weak fainted!") < rec.index("Player 2 sends out Backup!"), "replacement after the faint")
        Check.ok(!rec.has("Weak used Tackle!"), "a fainted monster does not act")
        Check.eq(listOf(1), rec.turnsOf("Player 2 sends out Backup!"), "replaced at the end of round 1")
        Check.ok(rec.index("Player 2 sends out Backup!") < rec.texts().indexOf("Turn 2"), "before round 2")
        Check.eq(listOf(listOf("Backup")), p2.replacementOptions.map { o -> o.map { it.name } }, "replacement options")
        Check.eq(2, rec.count("Plain feels powerful!"), "knockedOut trigger (twice)")
        Check.eq(2, m.mon(0).stage("atk"), "moxie stages")
        Check.ok(m.battle.ended, "battle ended")
    }
    // action_bar: replaced right after the action.
    run {
        val setup = lab.setup(lab.rules("turnSystem" to "action_bar"))
        val (_, rec) = battle(setup, team(member("swift", "tackle")), team(member("weak", "tackle"), member("backup", "tackle")), Script(choose = use("tackle")), Script(choose = use("tackle")))
        val faint = rec.index("Weak fainted!")
        val sendOut = rec.index("Player 2 sends out Backup!")
        val nextTurn = rec.log.withIndex().first { it.index > faint && it.value.kind == LogKind.TURN }.index
        Check.ok(faint in 0 until sendOut && sendOut < nextTurn, "replaced before the next turn")
        Check.eq(0, rec.result!!.winner, "player 1 wins")
    }
    // fainted / knockedOut (move trigger) / enemyFainted / allyFainted triggers.
    run {
        val setup = lab.setup(lab.rules("team" to mapOf("min" to 1, "max" to 6, "active" to 2)))
        val (m, rec) = field(
            setup,
            team(member("plain", "finisher", item = "trophy"), member("plain", "tackle")),
            team(member("weak", "tackle", ability = "aftermath"), member("leaf", "tackle", ability = "grief")),
        )
        val user = m.mon(0)
        m.use(user, "finisher", m.mon(1))
        Check.ok(m.mon(1).isFainted, "KO")
        Check.ok(rec.has("Weak exploded!") && user.hp == 190, "fainted trigger hits the attacker")
        Check.ok(rec.has("Finisher KO!"), "move knockedOut trigger")
        Check.ok(rec.has("Plain cheers for the fallen Weak!"), "enemyFainted trigger")
        Check.eq(2, m.mon(1, 1).stage("atk"), "allyFainted trigger")
        Check.eq(m.mon(1), m.battle.sides[1].active[0], "fainted monster stays in its slot")
        Check.ok(m.mon(1).statuses.isEmpty(), "statuses cleared")
    }
    // Retarget: a single-enemy move whose target fainted hits the other enemy.
    run {
        val setup = lab.setup(lab.rules("team" to mapOf("min" to 1, "max" to 6, "active" to 2)))
        val (m, rec) = field(
            setup,
            team(member("plain", "tackle"), member("plain", "tackle")),
            team(member("weak", "tackle"), member("leaf", "tackle"), member("backup", "tackle")),
        )
        m.mon(1).hp = 0
        m.use(m.mon(0), "tackle", m.mon(1))
        Check.eq(140, m.mon(1, 1).hp, "retargeted to the remaining enemy")
        m.mon(1, 1).hp = 0
        m.use(m.mon(0), "tackle", m.mon(1))
        Check.ok(rec.has("But there was no target..."), "no target left")
    }
}

private fun reviveTests(lab: EngineLab) {
    val duo = lab.rules("team" to mapOf("min" to 1, "max" to 6, "active" to 2))
    // A faintedAlly move revives a chosen fallen teammate in its field slot; revived / allyRevived / enemyRevived fire.
    run {
        val setup = lab.setup(duo)
        val (m, rec) = field(
            setup,
            team(member("plain", "raise", "tackle", ability = "cheer"), member("leaf", "tackle")),
            team(member("brawny", "finisher", ability = "cheer"), member("slow", "tackle")),
        )
        val reviver = m.mon(0)
        val raise = reviver.moves.first { it.def.id == "raise" }.def
        Check.ok(!m.battle.hasTarget(reviver, raise), "nobody to revive: the move has no target")
        m.use(m.mon(1), "finisher", m.mon(0, 1))
        val leaf = m.mon(0, 1)
        Check.ok(leaf.isFainted, "leaf fainted")
        Check.eq(listOf(leaf), m.battle.targetOptions(reviver, raise), "the fainted ally is the only option")
        m.use(reviver, "raise", leaf)
        Check.eq(100, leaf.hp, "revived with half of its max HP")
        Check.ok(leaf.isActive && m.battle.sides[0].active[1] === leaf, "back in its field slot")
        Check.eq(1, leaf.revives, "revive counter")
        Check.ok(rec.has("Leaf was revived with 100 HP!"), "revive line")
        Check.ok(rec.has("Plain cheers for Leaf!"), "allyRevived fires for the allies")
        Check.ok(rec.has("Brawny groans at Leaf!"), "enemyRevived fires for the enemies")
        Check.ok(!m.battle.hasTarget(reviver, raise), "nobody left to revive")
    }
    // A monster that was already replaced comes back to the bench (and can be switched in later).
    run {
        val setup = lab.setup()
        val (m, rec) = field(setup, team(member("plain", "tackle"), member("backup", "mass_raise")), team(member("brawny", "finisher")))
        val plain = m.mon(0)
        m.use(m.mon(1), "finisher", plain)
        m.switchIn(m.mon(0, 1), 0)
        Check.eq(-1, plain.slot, "replaced")
        m.use(m.mon(0, 1), "mass_raise")
        Check.eq(10, plain.hp, "revived through faintedAllies")
        Check.ok(!plain.isActive && plain in m.battle.sides[0].bench(), "back on the bench")
        Check.ok(rec.has("Backup called back the fallen!"), "custom revive message")
    }
    // Revive on death (a status's fainted trigger): after the faint is fully handled, with a clean state.
    run {
        val setup = lab.setup()
        val (m, rec) = field(setup, team(member("plain", "soul_up", "tackle")), team(member("brawny", "finisher")))
        val plain = m.mon(0)
        m.use(plain, "soul_up")
        Check.ok(plain.hasStatus("soul"), "soul protection applied")
        m.use(m.mon(1), "finisher", plain)
        Check.eq(60, plain.hp, "revived with 30% HP")
        Check.ok(plain.isActive, "still on the field")
        Check.ok(plain.statuses.isEmpty(), "the revive-on-death status is used up")
        Check.ok(rec.index("Plain fainted!") < rec.index("Finisher KO!"), "fainted before knockedOut")
        Check.ok(rec.index("Finisher KO!") < rec.index("Plain was revived with 60 HP!"), "revived after the faint was handled")
        Check.ok(!m.battle.sides[0].isDefeated(), "the side is not defeated")
    }
    // limit 1: revives once; revived triggers give statuses that are kept.
    run {
        val setup = lab.setup()
        val (m, rec) = field(setup, team(member("plain", "tackle", ability = "phoenix")), team(member("brawny", "finisher")))
        val plain = m.mon(0)
        m.use(m.mon(1), "finisher", plain)
        Check.eq(1, plain.hp, "rose with 1 HP")
        Check.ok(rec.has("Plain rose from the ashes!"), "custom message")
        Check.ok(plain.hasStatus("guard"), "a revived trigger's status survives")
        m.use(m.mon(1), "finisher", plain)
        Check.ok(plain.isFainted, "the second knockout sticks")
        Check.eq(1, plain.revives, "revived once")
    }
    // preventRevive (a knockedOut move trigger) beats a pending revive-on-death and removes the monster from faintedAlly options.
    run {
        val setup = lab.setup(duo)
        val (m, rec) = field(
            setup,
            team(member("plain", "tackle", ability = "phoenix"), member("leaf", "raise")),
            team(member("brawny", "doom"), member("slow", "tackle")),
        )
        val plain = m.mon(0)
        m.use(m.mon(1), "doom", plain)
        Check.ok(plain.isFainted && plain.reviveBlocked, "blocked")
        Check.ok(rec.has("Plain can no longer be revived!"), "block line")
        Check.ok(!rec.has("Plain rose from the ashes!"), "no revive")
        val raise = m.mon(0, 1).moves.first { it.def.id == "raise" }.def
        Check.ok(!m.battle.hasTarget(m.mon(0, 1), raise), "an unrevivable ally is not a target")
        Check.ok(!m.revive(plain, 50, null), "revive refuses")
    }
    // speed_order: a monster revived during the round does not run the action it chose before fainting.
    run {
        val setup = lab.setup(lab.rules("team" to mapOf("min" to 1, "max" to 6, "active" to 2)))
        // swift (fastest) knocks weak out; plain (faster than weak) revives it before weak's planned turn comes.
        val p0 = Script { battle, actor ->
            if (actor.species.id == "plain" && battle.faintedAllies(actor).isNotEmpty()) use("raise")(battle, actor) else use("tackle")(battle, actor)
        }
        var swung = false
        val p1 = Script { battle, actor ->
            if (actor.species.id == "swift" && !swung) { swung = true; use("finisher")(battle, actor) } else use("wait")(battle, actor)
        }
        val (_, rec) = battle(
            setup, team(member("weak", "tackle"), member("plain", "raise", "tackle")), team(member("swift", "finisher", "wait"), member("slow", "wait")), p0, p1,
        )
        Check.ok(rec.has("Weak was revived with 5 HP!"), "weak revived by its faster ally")
        val revivedIn = rec.turnsOf("Weak was revived with 5 HP!").first()
        Check.ok(revivedIn !in rec.turnsOf("Weak used Tackle!"), "the revived monster does not act in that round")
        Check.ok(revivedIn + 1 in rec.turnsOf("Weak used Tackle!"), "it acts again from the next round")
        Check.ok(rec.result != null, "the battle ends")
    }
    // action_bar: fainting at the start of its own turn and being revived costs that turn.
    run {
        val setup = lab.setup(lab.rules("turnSystem" to "action_bar"))
        var hexed = false
        val p1 = Script { battle, actor -> if (!hexed) { hexed = true; use("hex_tick")(battle, actor) } else use("wait")(battle, actor) }
        val (_, rec) = battle(setup, team(member("plain", "tackle", ability = "phoenix")), team(member("swift", "hex_tick", "wait")), Script(choose = use("tackle")), p1)
        val revivedAt = rec.index("Plain rose from the ashes!")
        Check.ok(revivedAt >= 0, "revived at the start of its turn")
        val nextTurn = rec.log.withIndex().first { it.index > revivedAt && it.value.kind == LogKind.TURN }.index
        val tackles = rec.log.withIndex().filter { it.value.text == "Plain used Tackle!" }.map { it.index }
        Check.ok(tackles.none { it in revivedAt..nextTurn }, "no action in the turn it was revived")
        Check.ok(tackles.any { it > nextTurn }, "it acts again later")
    }
    // The AI revives a fallen ally when it can.
    run {
        val setup = lab.setup(duo)
        val (m, _) = field(
            setup,
            team(member("plain", "raise", "small"), member("leaf", "tackle")),
            team(member("brawny", "finisher"), member("slow", "tackle")),
        )
        m.use(m.mon(1), "finisher", m.mon(0, 1))
        val action = AiController("AI", AiLevel.NORMAL).chooseAction(m.battle, m.mon(0))
        Check.ok(action is Action.UseMove && action.move.id == "raise" && action.target === m.mon(0, 1), "AI picks the revive: $action")
    }
}

private fun guardTests(lab: EngineLab) {
    val setup = lab.setup()
    // Recursion guard: "when damaged, damage self" must stop after 10 nested levels.
    run {
        val (m, _) = field(setup, team(member("plain", "tackle", ability = "echo")), team(member("plain", "tackle")))
        m.dealDamage(m.mon(0), 1, null, null)
        Check.eq(200 - 11, m.mon(0).hp, "1 damage + 10 nested echoes")
    }
    run {
        val (m, _) = field(setup, team(member("plain", "tackle", ability = "spite")), team(member("plain", "tackle", ability = "spite")))
        m.dealDamage(m.mon(0), 1, m.mon(1), null)
        Check.ok(m.mon(0).hp > 150 && m.mon(1).hp > 150, "mutual trigger loop terminates")
        // Self-inflicted damage has no `other`: spite must not hit its own owner.
        val before = m.mon(0).hp
        m.dealDamage(m.mon(0), 5, m.mon(0), null)
        Check.eq(before - 5, m.mon(0).hp, "self-inflicted damage: other is absent")
    }
    run {
        // Empty messages are not logged.
        val (m, rec) = field(setup, team(member("plain", "tackle")), team(member("plain", "tackle")))
        val lines = rec.log.size
        Check.ok(m.applyStatus(m.mon(0), "hush", null), "silent status applied")
        m.removeStatus(m.mon(0), m.mon(0).status("hush")!!)
        Check.eq(lines, rec.log.size, "empty apply/remove messages are silent")
        Check.ok(rec.log.none { it.text.isBlank() }, "no blank lines")
    }
    // Battle where nobody can win: turn limit, and the iteration cap with no limit.
    run {
        val (_, rec) = battle(lab.setup(lab.rules("turnLimit" to 3)), team(member("plain", "wait")), team(member("slow", "wait")), idle, idle)
        Check.eq(BattleResult(null, 3, "Turn limit reached"), rec.result, "speed_order turn limit")
        Check.ok(rec.has("The battle ended in a draw. (Turn limit reached)"), "draw message")
    }
    run {
        // No turn limit and nobody attacks: the loop guard ends it (only the result is recorded, not 100k log lines).
        var result: BattleResult? = null
        val observer = object : BattleObserver {
            override fun onBattleEnd(battle: Battle, result: BattleResult) {
                recordEnd(result)
            }

            private fun recordEnd(r: BattleResult) {
                result = r
            }
        }
        val start = System.nanoTime()
        Manager(setup, listOf(team(member("plain", "wait")), team(member("slow", "wait"))), listOf(idle, idle), observer, 1).run()
        Check.ok(result != null, "endless battle ends")
        Check.eq(null, result?.winner, "endless battle is a draw")
        Check.eq("The battle took too long", result?.reason, "loop guard reason")
        Check.ok((System.nanoTime() - start) / 1_000_000_000.0 < 30, "loop guard reached in reasonable time")
    }
    run {
        val frozen = lab.setup(lab.rules("turnSystem" to "action_bar", "actionBar" to mapOf("size" to 100, "fill" to "0", "start" to "0")))
        val (_, rec) = battle(frozen, team(member("plain", "wait")), team(member("slow", "wait")), idle, idle)
        Check.eq(null, rec.result!!.winner, "nobody can fill the bar: draw")
        Check.eq(0, rec.result!!.turns, "no turn happened")
    }
    // Determinism: same seed and controllers -> same battle.
    run {
        val random = lab.setup(
            lab.rules(
                "damage" to "floor(move.power * attack / defense * random(0.85, 1) * crit)", "critChance" to "0.2",
                "fallbackMove" to "struggle",
            )
        )
        fun play(seed: Long): List<String> {
            val t0 = team(member("plain", "tackle", "multi", "scare", "seed"), member("leaf", "ember", "triple"))
            val t1 = team(member("puddle", "bubble", "jab", "whiff"), member("blaze", "ember", "burn_it"))
            return battle(random, t0, t1, RandomController(7), RandomController(8), seed).second.texts()
        }
        val first = play(42)
        Check.eq(first, play(42), "same seed replays identically")
        Check.ok(first != play(43), "another seed gives another battle")
    }
}

/** README "Durations": freshness depends on where the owner is in its turn; end-of-round KOs are checked together. */
private fun freshnessTests(lab: EngineLab) {
    // speed_order, battle start: a 1-turn status from a switchIn trigger lasts round 1 (not 2).
    run {
        val setup = lab.setup(lab.rules("turnLimit" to 3))
        val (_, rec) = battle(setup, team(member("plain", "wait")), team(member("slow", "wait", ability = "dazer")), idle, idle)
        Check.eq(listOf(1), rec.turnsOf("Plain is no longer affected by Dazed."), "applied at battle start: ends with round 1")
    }
    // speed_order, end-of-round replacement: applied after the round's countdown, it lasts the next round only.
    run {
        val setup = lab.setup(lab.rules("turnLimit" to 3))
        val (_, rec) = battle(
            setup,
            team(member("plain", "finisher", "wait")), team(member("weak", "wait"), member("backup", "wait", ability = "dazer")),
            Script(choose = useThen("finisher", 1, "wait")), idle,
        )
        Check.eq(listOf(1), rec.turnsOf("Player 2 sends out Backup!"), "replacement at the end of round 1")
        Check.eq(listOf(2), rec.turnsOf("Plain is no longer affected by Dazed."), "applied by the replacement: ends with round 2")
    }
    // speed_order: applied in a later monster's turnEnd, after the target's countdown: ends with the next round.
    run {
        val setup = lab.setup(lab.rules("turnLimit" to 2))
        val (_, rec) = battle(setup, team(member("plain", "wait")), team(member("slow", "wait", ability = "late")), idle, idle)
        Check.eq(listOf(2), rec.turnsOf("Plain is no longer affected by Dazed."), "applied after its countdown: ends with the next round")
    }
    // speed_order: a 1-turn "can't move" status costs exactly one move, whether the inflicter is faster or slower.
    run {
        val setup = lab.setup(lab.rules("turnLimit" to 3))
        val (_, fast) = battle(setup, team(member("plain", "lull", "wait")), team(member("slow", "tackle")),
            Script(choose = useThen("lull", 1, "wait")), Script(choose = use("tackle")))
        Check.eq(listOf(1), fast.turnsOf("Slow dozes."), "inflicted before the target acted: it loses that round only")
        Check.eq(listOf(2, 3), fast.turnsOf("Slow used Tackle!"), "then it acts again")
        val (_, slow) = battle(setup, team(member("plain", "tackle")), team(member("slow", "lull", "wait")),
            Script(choose = use("tackle")), Script(choose = useThen("lull", 1, "wait")))
        Check.eq(listOf(2), slow.turnsOf("Plain dozes."), "inflicted after the target acted: it loses the next round only")
        Check.eq(listOf(1, 3), slow.turnsOf("Plain used Tackle!"), "the target acts in rounds 1 and 3")
    }
    // action_bar: a status added to the actor during its own countdown (statusRemoved chain) is not fresh.
    run {
        val setup = lab.setup(lab.rules("turnSystem" to "action_bar", "turnLimit" to 6))
        val (_, rec) = battle(
            setup, team(member("fast", "light_fuse", "wait")), team(member("even", "wait")),
            Script(choose = useThen("light_fuse", 1, "wait")), idle,
        )
        Check.eq(listOf(3), rec.turnsOf("Fast is no longer affected by Fuse."), "self-applied fuse ends after one more own turn")
        Check.eq(listOf(5), rec.turnsOf("Fast is no longer affected by Dazed."), "dazed added during that countdown lasts one own turn")
    }
    // Residual damage that knocks out the last monster of both sides is a draw, whichever side is processed first.
    for (ability in listOf("self_sear", "self_pyre")) {
        val setup = lab.setup(lab.rules("turnLimit" to 5))
        for ((a, b) in listOf("plain" to "slow", "slow" to "plain")) {
            val (m, rec) = battle(setup, team(member(a, "wait", ability = ability)), team(member(b, "wait", ability = ability)), idle, idle)
            Check.eq(BattleResult(null, 1, "Both sides were defeated"), rec.result, "$ability: mutual residual KO ($a vs $b) is a draw")
            Check.ok(m.mon(0).isFainted && m.mon(1).isFainted, "$ability: both sides took their residual damage ($a vs $b)")
        }
    }
}

/** Event order, the damage pipeline (running `damage`, power) and speed_order planning fixes. */
private fun pipelineTests(lab: EngineLab) {
    val setup = lab.setup()
    // A status's `fainted` trigger fires before the statuses are cleared.
    run {
        val (m, rec) = field(setup, team(member("plain", "curse", "finisher")), team(member("weak", "tackle"), member("backup", "tackle")))
        m.use(m.mon(0), "curse")
        m.use(m.mon(0), "finisher")
        Check.ok(m.mon(1).isFainted && rec.has("Doom strikes Plain!"), "status fainted trigger fires: ${rec.texts().takeLast(4)}")
        Check.eq(193, m.mon(0).hp, "and its effect hits the attacker")
        Check.ok(m.mon(1).statuses.isEmpty(), "statuses are cleared afterwards")
    }
    // modifyDamageTaken triggers see the damage left by the earlier ones.
    run {
        val (m, rec) = field(setup, team(member("blaze", "ember")), team(member("leaf", "tackle", ability = "sponge", item = "ribbon")))
        m.use(m.mon(0), "ember")
        Check.ok(rec.has("Leaf soaked it up!") && !rec.has("Leaf hung on!"), "a survival item does not fire on an absorbed hit")
        Check.eq(200, m.mon(1).hp, "no damage")
        val (m2, rec2) = field(setup, team(member("brawny", "finisher")), team(member("plain", "tackle", ability = "endure", item = "ribbon")))
        m2.use(m2.mon(0), "finisher")
        Check.ok(rec2.has("Plain endured!") && !rec2.has("Plain hung on!"), "only the first survival effect fires: ${rec2.texts().takeLast(4)}")
        Check.eq(1, m2.mon(1).hp, "(hp - 1) / damage leaves exactly 1 HP")
    }
    // move.power is evaluated with the move user as self, whoever reads it.
    run {
        val (m, rec) = field(setup, team(member("plain", "hp_power")), team(member("leaf", "tackle", ability = "weigh")))
        m.mon(1).hp = 50
        m.use(m.mon(0), "hp_power")
        Check.ok(!rec.has("Weighed!"), "power 'self.hpPercent' is the attacker's (100), not the defender's (25)")
        val (m2, rec2) = field(setup, team(member("plain", "hp_power")), team(member("leaf", "tackle", ability = "weigh")))
        m2.mon(0).hp = 50
        m2.use(m2.mon(0), "hp_power")
        Check.ok(rec2.has("Weighed!"), "and the defender's trigger sees the attacker's 25")
        // Outside a hit (e.g. a defender's damaged/beforeMove trigger), the formula still uses the move user as self.
        val (m3, _) = field(setup, team(member("plain", "hp_power")), team(member("leaf", "tackle")))
        m3.mon(1).hp = 50
        val defenderView = m3.battle.context(m3.mon(1), m3.mon(0), user = m3.mon(0), target = m3.mon(1), move = setup.moves.require("hp_power"))
        Check.near(100.0, Formula.parse("move.power").eval(defenderView), "move.power in the defender's context = the user's 100%")
    }
    // A power formula that gives 0 deals no damage; `hit` fires with damage 0.
    run {
        val (m, rec) = field(setup, team(member("plain", "maybe")), team(member("leaf", "tackle")))
        m.use(m.mon(0), "maybe")
        Check.eq(200, m.mon(1).hp, "power 0 = no damage")
        Check.ok(rec.has("Maybe hit for 0!") && rec.log.none { it.text.startsWith("Leaf took") }, "hit with damage 0: ${rec.texts()}")
    }
    // A refresh status keeps 1 stack.
    run {
        val (m, _) = field(setup, team(member("plain", "tackle")), team(member("leaf", "tackle")))
        val a = m.mon(0)
        repeat(3) { Check.ok(m.applyStatus(a, "rage", null, stacks = 2), "rage applied/refreshed") }
        Check.eq(1, a.status("rage")!!.stacks, "refresh does not add stacks")
        Check.near(200.0, a.stat("atk"), "atk x (1 + 1)")
    }
    // `cancelled` in beforeMove: a later trigger sees that the move was already cancelled.
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle")), team(member("slow", "tackle")))
        val slow = m.mon(1)
        m.applyStatus(slow, "wary", null)
        m.use(slow, "tackle")
        Check.ok(rec.has("Slow is wary."), "not cancelled: the trigger fires")
        m.removeStatus(slow, slow.status("wary")!!)
        m.applyStatus(slow, "flinch", null)
        m.applyStatus(slow, "wary", null)
        val before = rec.count("Slow is wary.")
        m.use(slow, "tackle")
        Check.ok(rec.has("Slow flinched!") && rec.count("Slow is wary.") == before, "cancelled by an older status: the trigger stays quiet")
    }
    // A planned move made unusable earlier in the round fails instead of running.
    run {
        val cdSetup = lab.setup(lab.rules("cooldowns" to true, "pp" to false, "turnLimit" to 1))
        val (m, rec) = battle(cdSetup, team(member("swift", "jam")), team(member("slow", "big", "small")), Script(choose = use("jam")), Script(choose = use("big")))
        Check.ok(rec.has("Slow can't use Big right now!") && !rec.has("Slow used Big!"), "planned move on cooldown fails: ${rec.texts()}")
        Check.ok(m.mon(1).moves.first { it.def.id == "big" }.cooldown >= 2, "the added cooldown is kept")
    }
    // speed_order doubles: two allies switching to the same teammate; the second one's move keeps its priority place.
    run {
        val doubles = lab.setup(lab.rules("team" to mapOf("min" to 1, "max" to 6, "active" to 2), "turnLimit" to 1))
        val switcher = Script { battle, _ -> Action.Switch(battle.sides[0].team[2]) }
        val (_, rec) = battle(
            doubles,
            team(member("slow", "tackle"), member("brawny", "tackle"), member("plain", "tackle")),
            team(member("swift", "quick"), member("fast", "quick")),
            switcher, Script(choose = use("quick")),
        )
        Check.ok(rec.has("Player 1 withdraws Slow!") && rec.has("Player 1 sends out Plain!"), "the first switch happens")
        val tackle = rec.index("Brawny used Tackle!")
        Check.ok(tackle > rec.index("Swift used Quick!") && tackle > rec.index("Fast used Quick!"), "the second one acts after the priority moves: ${rec.texts()}")
    }
    // Estimate mode never consumes the battle's random numbers.
    run {
        val random = lab.setup(lab.rules("critChance" to "random(0, 0.5)", "hitChance" to "random(0.5, 1)"))
        val (m, _) = field(random, team(member("plain", "jab", "sure")), team(member("leaf", "tackle")), seed = 7)
        val a = m.mon(0)
        val d = m.mon(1)
        m.damage.calculate(m.battle, a, d, random.moves.require("jab"), 1, estimate = true)
        m.damage.hitChance(m.battle, a, d, random.moves.require("sure"), estimate = true)
        m.damage.critChance(m.battle, a, d, random.moves.require("jab"), estimate = true)
        Check.near(Random(7).nextDouble(), m.battle.random.nextDouble(), "no dice rolled while estimating")
    }
    // A battle function with the wrong number of arguments names its formula.
    run {
        val (m, _) = field(setup, team(member("plain", "tackle")), team(member("leaf", "tackle")))
        val message = try {
            Formula.parse("hasType(self)").eval(m.battle.context(m.mon(0)))
            ""
        } catch (e: ExpressionException) {
            e.message ?: ""
        }
        Check.eq("Formula 'hasType(self)': hasType() takes 2 arguments but got 1", message, "runtime arity message quotes the formula")
    }
    // A shield that absorbs a whole hit still counts as being damaged (counters, reflect...).
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle")), team(member("leaf", "tackle", ability = "spite")))
        m.addShield(m.mon(1), 500)
        m.use(m.mon(0), "tackle")
        Check.eq(200, m.mon(1).hp, "the shield took the hit")
        Check.eq(199, m.mon(0).hp, "damaged fired anyway (Spite)")
        Check.ok(rec.has("Leaf's shield absorbed 60 damage."), "shield line: ${rec.texts()}")
    }
}

/** Round 2: events that happen to several monsters at once, the cooldown effect's turn rule, refreshes, legal targets. */
private fun simultaneityTests(lab: EngineLab) {
    // Both leads faint at the end of round 1: each side picks without seeing the other's pick, both replacements are
    // on the field before any switchIn runs, so each one's switch-in effect reaches the other.
    run {
        val setup = lab.setup(lab.rules("turnLimit" to 2))
        val seen = mutableListOf<List<String>>()
        val watcher = object : Controller {
            override val name = "Watcher"
            override fun chooseAction(battle: Battle, actor: Battler): Action = use("wait")(battle, actor)
            override fun chooseReplacement(battle: Battle, side: Side, slot: Int, options: List<Battler>): Battler {
                seen += battle.opponentOf(side).activeBattlers().map { it.name }
                return options.first()
            }
        }
        fun lineup() = team(member("plain", "wait", ability = "self_sear"), member("backup", "wait", ability = "dazer"))
        val (_, rec) = battle(setup, lineup(), lineup(), watcher, watcher)
        Check.eq(listOf(emptyList<String>(), emptyList()), seen, "no side sees the other side's replacement while choosing")
        val dazed = listOf("Backup (Player 1) is affected by Dazed!", "Backup (Player 2) is affected by Dazed!")
        Check.ok(dazed.all { rec.has(it) }, "both switch-in effects land on the other replacement: ${rec.texts()}")
        val lastSendOut = maxOf(rec.index("Player 1 sends out Backup (Player 1)!"), rec.index("Player 2 sends out Backup (Player 2)!"))
        Check.ok(dazed.all { rec.index(it) > lastSendOut }, "every replacement enters before any switchIn effect runs")
    }
    // turnEnd runs fastest first, not side 0 first: the faster seeded monster heals its slower, burned planter
    // before the planter's burn, wherever the two stand.
    for (seederSide in 0..1) {
        val setup = lab.setup(lab.rules("turnLimit" to 1))
        lateinit var manager: Manager
        var prepared = false
        val prep = Script { battle, actor ->
            if (!prepared) {
                prepared = true
                val seeder = battle.allActive().first { it.name == "Slow" }
                manager.applyStatus(battle.allActive().first { it.name == "Plain" }, "leech", seeder)
                manager.applyStatus(seeder, "burn", null)
                seeder.hp = 20
            }
            use("wait")(battle, actor)
        }
        val seederTeam = team(member("slow", "wait"))
        val otherTeam = team(member("plain", "wait"))
        val rec = Recorder()
        manager = Manager(setup, if (seederSide == 0) listOf(seederTeam, otherTeam) else listOf(otherTeam, seederTeam), listOf(prep, prep), rec, 1)
        manager.run()
        val seeder = manager.battle.sides[seederSide].team[0]
        Check.eq(20 + 10 - 25, seeder.hp, "seeder on side $seederSide: healed by the faster seeded monster, then burned: ${rec.texts()}")
    }
    // A cooldown effect that lands after the owner acted blocks its next turn (speed_order: a slower enemy's Lock).
    run {
        val setup = lab.setup(lab.rules("cooldowns" to true, "pp" to false, "turnLimit" to 3))
        val (_, rec) = battle(setup, team(member("swift", "tackle")), team(member("slow", "lock", "wait")),
            Script(choose = use("tackle")), Script(choose = useThen("lock", 1, "wait")))
        Check.eq(listOf(1, 3), rec.turnsOf("Swift used Tackle!"), "cooldown +1 after acting: the ready move waits one turn")
        Check.eq(listOf(2), rec.turnsOf("Swift is waiting."), "the blocked turn")
    }
    // action_bar: a cooldown the actor gives itself in its own turn blocks its next turn.
    run {
        val setup = lab.setup(lab.rules("turnSystem" to "action_bar", "cooldowns" to true, "pp" to false, "turnLimit" to 5))
        val (m, rec) = battle(setup, team(member("fast", "self_lock", "tackle")), team(member("even", "wait")),
            Script(choose = useThen("self_lock", 1, "tackle")), idle)
        Check.eq(listOf(3), rec.turnsOf("Fast is waiting."), "self-inflicted cooldown +1 blocks its next own turn: ${rec.texts()}")
        Check.eq(listOf(5), rec.turnsOf("Fast used Tackle!"), "then the move is ready again")
        // Outside the owner's turn, or on a move already on cooldown, the amount is added as is.
        val (m2, _) = field(setup, team(member("fast", "big", "tackle")), team(member("even", "wait")))
        val slots = m2.mon(0).moves
        slots[0].cooldown = 2
        m2.changeCooldowns(m2.mon(0), 1)
        Check.eq(listOf(3, 1), slots.map { it.cooldown }, "between turns: +1 everywhere")
        Check.ok(m.battle.ended, "battle ended")
    }
    // A status refreshed during its owner's countdown (by another status's statusRemoved trigger) is not counted down
    // again in that countdown, whatever the order of the statuses.
    for (moveId in listOf("timer_first", "guard_first")) {
        val setup = lab.setup(lab.rules("turnLimit" to 5))
        val (_, rec) = battle(setup, team(member("plain", moveId, "wait")), team(member("slow", "wait")),
            Script(choose = useThen(moveId, 1, "wait")), idle)
        Check.eq(4, rec.turnsOf("Plain is no longer affected by Guard.").lastOrNull(), "$moveId: Guard re-applied for 2 turns at the end of round 2 lasts rounds 3 and 4: ${rec.texts()}")
    }
    // `damage` in hit is what the hit really did: a finishing blow does not drain its overkill; a shield part counts.
    run {
        val setup = lab.setup()
        val (m, rec) = field(setup, team(member("plain", "sip")), team(member("leaf", "tackle"), member("backup", "tackle")))
        m.mon(0).hp = 100
        m.mon(1).hp = 5
        m.use(m.mon(0), "sip")
        Check.ok(m.mon(1).isFainted && rec.has("Sipped 5!"), "hit damage = the 5 HP left: ${rec.texts()}")
        Check.eq(105, m.mon(0).hp, "drain heals 5, not the 40 the hit could deal")
        val (m2, rec2) = field(setup, team(member("plain", "sip")), team(member("leaf", "tackle"), member("backup", "tackle")))
        m2.mon(0).hp = 100
        m2.mon(1).hp = 5
        m2.addShield(m2.mon(1), 10)
        m2.use(m2.mon(0), "sip")
        Check.ok(rec2.has("Sipped 15!") && m2.mon(0).hp == 115, "the shield part counts: ${rec2.texts()}")
    }
}

/**
 * Rules.hitTypes (crushing / glancing hits...) and the type matchup (`effectiveness`, `sameType`) in the hit chance and
 * crit formulas. Lab damage: floor(power x atk / def x STAB 1.5 x effectiveness x crit), critMultiplier 2, all stats 100.
 */
private fun hitTypeTests(lab: EngineLab) {
    val crushing = """{"id": "crushing", "chance": "effectiveness > 1 && !isCrit ? 100 : 0", "multiplier": 1.3,
                       "message": "Crushing hit on {target} by {user}'s {move}!"}"""
    val glancing = """{"id": "glancing", "chance": 100, "multiplier": 0.7, "cancelsCrit": true, "message": "Glancing hit!"}"""
    fun withHitTypes(vararg entries: String, rules: Array<Pair<String, Any?>> = emptyArray()): Setup =
        lab.setup(lab.rules("hitTypes" to json("[${entries.joinToString(",")}]"), *rules))

    // Crushing on type advantage, only for hits that are not critical; the multiplier scales the damage formula's result.
    run {
        val setup = withHitTypes(crushing)
        Check.eq(listOf("crushing"), setup.rules.hitTypes.map { it.id }, "hit types parsed")
        val (m, rec) = field(setup, team(member("plain", "ember", "jab")), team(member("leaf", "tackle")))
        val a = m.mon(0)
        val d = m.mon(1)
        val r = m.damage.calculate(m.battle, a, d, setup.moves.require("ember"), 1)
        Check.eq("crushing", r.hitType?.id, "advantage: crushing hit")
        Check.eq(104, r.damage, "80 x 1.3")
        val neutral = m.damage.calculate(m.battle, a, d, setup.moves.require("jab"), 1)
        Check.ok(neutral.hitType == null && neutral.damage == 40, "neutral: normal hit (${neutral.hitType}, ${neutral.damage})")
        m.use(a, "ember")
        Check.eq(96, d.hp, "the crushing hit took 104 HP")
        val crush = rec.index("Crushing hit on Leaf by Plain's Ember!")
        Check.ok(crush >= 0, "hit type message with {target} {user} {move}: ${rec.texts()}")
        Check.ok(crush < rec.index("It's super effective!") && rec.index("It's super effective!") < rec.index("Leaf took 104 damage."),
            "logged where the crit message goes (before the type line and the damage): ${rec.texts()}")
        Check.eq(LogKind.CRIT, rec.log[crush].kind, "logged as a crit line")
    }
    run {
        val setup = withHitTypes(crushing, rules = arrayOf("critChance" to "1"))
        val (m, rec) = field(setup, team(member("plain", "ember")), team(member("leaf", "tackle")))
        val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("ember"), 1)
        Check.ok(r.isCrit && r.hitType == null && r.damage == 160, "a crit is never crushing (${r.isCrit}, ${r.hitType}, ${r.damage})")
        m.use(m.mon(0), "ember")
        Check.ok(rec.has("A critical hit!") && rec.texts().none { it.startsWith("Crushing") }, "crit line only: ${rec.texts()}")
    }
    // A hit fully cancelled by a modifier prints no hit type line (like the crit line).
    run {
        val setup = withHitTypes(crushing)
        val (m, rec) = field(setup, team(member("blaze", "ember")), team(member("leaf", "tackle", ability = "sponge")))
        m.use(m.mon(0), "ember")
        Check.ok(m.mon(1).hp == 200 && rec.has("Leaf soaked it up!") && rec.texts().none { it.startsWith("Crushing") },
            "no hit type line for an absorbed hit: ${rec.texts()}")
    }

    // Glancing: cancels the crit (no crit line, crit = 1), then its multiplier applies.
    run {
        val setup = withHitTypes(glancing, rules = arrayOf("critChance" to "1"))
        val (m, rec) = field(setup, team(member("plain", "jab")), team(member("plain", "tackle")))
        val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("jab"), 1)
        Check.ok(!r.isCrit && r.hitType?.id == "glancing" && r.damage == 28, "crit cancelled: 40 x 0.7 (${r.isCrit}, ${r.damage})")
        m.use(m.mon(0), "jab")
        Check.ok(rec.has("Glancing hit!") && !rec.has("A critical hit!"), "glancing line, no crit line: ${rec.texts()}")
        Check.eq(172, m.mon(1).hp, "took 28")
    }
    run {
        val keepsCrit = """{"id": "glancing", "chance": 100, "multiplier": 0.7, "message": "Glancing hit!"}"""
        val setup = withHitTypes(keepsCrit, rules = arrayOf("critChance" to "1"))
        val (m, rec) = field(setup, team(member("plain", "jab")), team(member("plain", "tackle")))
        val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("jab"), 1)
        Check.ok(r.isCrit && r.damage == 56, "cancelsCrit defaults to false: 40 x 2 x 0.7 (${r.isCrit}, ${r.damage})")
        m.use(m.mon(0), "jab")
        Check.ok(rec.index("A critical hit!") in 0 until rec.index("Glancing hit!"), "crit line, then the hit type line: ${rec.texts()}")
    }

    // Order: the first hit type whose roll succeeds wins; chance 0 never happens; each hit of a multi-hit move rolls.
    run {
        val first = withHitTypes("""{"id": "first", "chance": 100, "multiplier": 2}""", """{"id": "second", "chance": 100, "multiplier": 3}""")
        val (m, _) = field(first, team(member("plain", "jab")), team(member("plain", "tackle")))
        val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), first.moves.require("jab"), 1)
        Check.ok(r.hitType?.id == "first" && r.damage == 80, "first match wins (${r.hitType}, ${r.damage})")
        val skip = withHitTypes("""{"id": "first", "chance": 0, "multiplier": 2}""", """{"id": "second", "chance": "100", "multiplier": "1 + 2"}""")
        val (m2, _) = field(skip, team(member("plain", "jab")), team(member("plain", "tackle")))
        val r2 = m2.damage.calculate(m2.battle, m2.mon(0), m2.mon(1), skip.moves.require("jab"), 1)
        Check.ok(r2.hitType?.id == "second" && r2.damage == 120, "a chance-0 hit type is skipped (${r2.hitType}, ${r2.damage})")
    }
    run {
        val setup = withHitTypes("""{"id": "a", "chance": 50, "multiplier": 2}""", """{"id": "b", "chance": 50, "multiplier": 3}""")
        val (m, _) = field(setup, team(member("plain", "jab")), team(member("plain", "tackle")), seed = 3)
        val counts = HashMap<String, Int>()
        repeat(4000) {
            val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("jab"), 1)
            val id = r.hitType?.id ?: ""
            counts[id] = (counts[id] ?: 0) + 1
            val expected = when (id) { "a" -> 80; "b" -> 120; else -> 40 }
            if (r.damage != expected) Check.eq(expected, r.damage, "damage of a '$id' hit")
        }
        Check.ok((counts["a"] ?: 0) in 1850..2150 && (counts["b"] ?: 0) in 900..1100 && (counts[""] ?: 0) in 900..1100,
            "sequential rolls: ~50% a, ~25% b, ~25% normal: $counts")
    }
    run {
        val setup = withHitTypes("""{"id": "double", "chance": 100, "multiplier": 2, "message": "Double!"}""")
        val (m, rec) = field(setup, team(member("plain", "triple")), team(member("plain", "tackle")))
        m.use(m.mon(0), "triple")
        Check.ok(m.mon(1).hp == 140 && rec.count("Double!") == 3, "every hit of a multi-hit move rolls: ${rec.texts()}")
    }
    // Hit types that cannot happen roll no dice (a setup without hit types plays exactly as before).
    run {
        val setup = withHitTypes("""{"id": "never", "chance": "effectiveness > 1 ? 50 : 0"}""", """{"id": "always", "chance": 100}""")
        val (m, _) = field(setup, team(member("plain", "jab")), team(member("plain", "tackle")), seed = 7)
        val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("jab"), 1)
        Check.ok(r.hitType?.id == "always" && r.damage == 40, "multiplier defaults to 1 (${r.hitType}, ${r.damage})")
        Check.near(Random(7).nextDouble(), m.battle.random.nextDouble(), "chances of 0 and 100 roll no dice")
        val plain = lab.setup()
        Check.eq(emptyList<Any>(), plain.rules.hitTypes, "no hitTypes by default")
    }

    // hitType in the damage formula and in hit / modifyDamageDealt / modifyDamageTaken triggers.
    run {
        val setup = withHitTypes(
            """{"id": "crushing", "chance": "effectiveness > 1 ? 100 : 0"}""",
            rules = arrayOf("damage" to "floor(move.power * attack / defense * (hitType == 'crushing' ? 10 : 1) * (hitType == '' ? 2 : 1))"),
        )
        val (m, _) = field(setup, team(member("plain", "ember", "jab")), team(member("leaf", "tackle")))
        Check.eq(400, m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("ember"), 1).damage, "hitType in the damage formula")
        Check.eq(80, m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("jab"), 1).damage, "hitType '' for a normal hit")
    }
    run {
        val setup = withHitTypes(crushing)
        val (m, rec) = field(setup, team(member("plain", "crush_note", "probe")), team(member("leaf", "tackle", ability = "dampen")))
        m.use(m.mon(0), "crush_note")
        // 80 x 1.3 = 104, x2 (modifyDamageDealt), x0.5 (modifyDamageTaken) = 104.
        Check.eq(96, m.mon(1).hp, "hitType in modifyDamageDealt and modifyDamageTaken")
        Check.ok(rec.has("Crush registered for 104!") && rec.has("Leaf dampened the crush!") && !rec.has("Normal hit for 104."),
            "hitType in hit triggers: ${rec.texts()}")
        m.use(m.mon(0), "probe")
        Check.ok(rec.has("Probe: normal hit."), "a move that deals no damage has hitType ''")
        val (m2, rec2) = field(lab.setup(), team(member("plain", "crush_note")), team(member("leaf", "tackle", ability = "dampen")))
        m2.use(m2.mon(0), "crush_note")
        Check.ok(m2.mon(1).hp == 120 && rec2.has("Normal hit for 80.") && !rec2.has("Leaf dampened the crush!"),
            "without hitTypes every hit has hitType '': ${rec2.texts()}")
    }

    // Estimate mode (AI): no dice, expected multipliers over the crit outcomes and the hit types in order.
    run {
        val setup = withHitTypes(crushing, rules = arrayOf("critChance" to "0.5"))
        val (m, _) = field(setup, team(member("plain", "ember")), team(member("leaf", "tackle")))
        val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("ember"), 1, estimate = true)
        // Half crits (80 x 2 = 160, never crushing), half crushing (80 x 1.3 = 104): 132.
        Check.ok(r.damage == 132 && r.hitType == null && !r.isCrit, "crushing excludes crits in the estimate (${r.damage})")
    }
    run {
        val setup = withHitTypes("""{"id": "a", "chance": 50, "multiplier": 2}""", """{"id": "b", "chance": 50, "multiplier": 0.5}""")
        val (m, _) = field(setup, team(member("plain", "jab")), team(member("plain", "tackle")), seed = 7)
        val r = m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("jab"), 1, estimate = true)
        Check.eq(55, r.damage, "40 x (0.5 x 2 + 0.25 x 0.5 + 0.25 x 1)")
        Check.near(Random(7).nextDouble(), m.battle.random.nextDouble(), "no dice rolled while estimating hit types")
    }
    run {
        val setup = withHitTypes("""{"id": "glancing", "chance": 100, "multiplier": 0.5, "cancelsCrit": true}""", rules = arrayOf("critChance" to "0.5"))
        val (m, _) = field(setup, team(member("plain", "jab")), team(member("plain", "tackle")))
        val estimate = m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("jab"), 1, estimate = true)
        val real = m.damage.calculate(m.battle, m.mon(0), m.mon(1), setup.moves.require("jab"), 1)
        Check.ok(estimate.damage == 20 && real.damage == 20, "a sure crit-cancelling hit type removes the expected crit (${estimate.damage}, ${real.damage})")
        val never = withHitTypes("""{"id": "never", "chance": 0, "multiplier": 9}""", rules = arrayOf("critChance" to "0.5"))
        val (m2, _) = field(never, team(member("plain", "jab")), team(member("plain", "tackle")))
        Check.eq(60, m2.damage.calculate(m2.battle, m2.mon(0), m2.mon(1), never.moves.require("jab"), 1, estimate = true).damage,
            "hit types that cannot happen leave the crit estimate alone (40 x 1.5)")
    }

    // effectiveness / sameType in Rules.hitChance, critChance, critMultiplier and move critBonus.
    run {
        val setup = lab.setup(lab.rules("critChance" to "effectiveness > 1 ? 1 : 0", "critMultiplier" to "sameType ? 3 : 2"))
        val (m, _) = field(setup, team(member("blaze", "ember", "jab")), team(member("leaf", "tackle"), member("puddle", "tackle")))
        val a = m.mon(0)
        val ember = setup.moves.require("ember")
        Check.near(1.0, m.damage.critChance(m.battle, a, m.mon(1), ember), "critChance sees the advantage")
        Check.near(0.0, m.damage.critChance(m.battle, a, m.mon(1, 1), ember), "critChance sees the disadvantage")
        val r = m.damage.calculate(m.battle, a, m.mon(1), ember, 1)
        Check.ok(r.isCrit && r.damage == 360, "40 x 1.5 (STAB) x 2 (type) x 3 (sameType crit multiplier): ${r.damage}")
        Check.ok(!m.damage.calculate(m.battle, a, m.mon(1), setup.moves.require("jab"), 1).isCrit, "neutral: no crit")
    }
    run {
        val setup = lab.setup(lab.rules("hitChance" to "effectiveness > 1 ? move.accuracy / 100 : (sameType ? 0.5 : 0)"))
        val (m, rec) = field(setup, team(member("plain", "flame_shot", "keen")), team(member("leaf", "tackle"), member("puddle", "tackle")))
        val shot = setup.moves.require("flame_shot")
        Check.near(1.0, m.damage.hitChance(m.battle, m.mon(0), m.mon(1), shot), "hitChance sees the advantage")
        Check.near(0.0, m.damage.hitChance(m.battle, m.mon(0), m.mon(1, 1), shot), "hitChance sees the matchup")
        m.use(m.mon(0), "flame_shot")
        Check.ok(m.mon(1).hp == 120 && !rec.has("Leaf avoided the attack!"), "a hit chance of 1 hits: ${rec.texts()}")
        val (m1, rec1) = field(setup, team(member("plain", "flame_shot")), team(member("puddle", "tackle")))
        m1.use(m1.mon(0), "flame_shot")
        Check.ok(rec1.has("Puddle avoided the attack!") && m1.mon(1).hp == 200, "a 0 hit chance misses: ${rec1.texts()}")
        val (m2, _) = field(setup, team(member("blaze", "flame_shot")), team(member("puddle", "tackle")))
        Check.near(0.5, m2.damage.hitChance(m2.battle, m2.mon(0), m2.mon(1), shot), "hitChance sees sameType")
        val keen = setup.moves.require("keen")
        Check.near(1.0, m.damage.critChance(m.battle, m.mon(0), m.mon(1), keen), "critBonus sees the advantage")
        Check.near(0.0, m.damage.critChance(m.battle, m.mon(0), m.mon(1, 1), keen), "critBonus sees the disadvantage")
    }
}

/** Regression checks on the shipped Classic and Gacha data. */
/** Evaluates [formula] for [self] (other = [other]) in [m]'s battle. */
private fun Manager.eval(formula: String, self: Battler, other: Battler? = null): Double = Formula.parse(formula).eval(battle.context(self, other))

private fun fieldTests(lab: EngineLab) {
    val setup = lab.setup()
    // Loading: defaults and parsed values.
    run {
        val rain = setup.fields.require("rain")
        Check.ok(rain.scope == FieldScope.GLOBAL && rain.group == "weather" && rain.stacking == Stacking.IGNORE, "rain defaults")
        Check.eq("RAIN", rain.short, "default short tag")
        Check.eq(FieldAudience.OPPONENTS, setup.fields.require("thorns").triggers.single().audience, "trigger audience")
        Check.eq(FieldAudience.COVERED, setup.fields.require("spikes").triggers.single().audience, "default audience")
        val fieldWarnings = setup.warnings.filter { "Fields.json" in it }
        Check.ok(fieldWarnings.size == 1 && "(quickbar).rules.actionBarFill: does nothing" in fieldWarnings[0], "field warnings: $fieldWarnings")
    }

    // Global field: set, coverage, stacking "ignore", trigger damage modifier, group exclusivity.
    run {
        val (m, rec) = field(setup, team(member("plain", "water_gun", "ember", "tackle")), team(member("slow", "tackle")))
        val a = m.mon(0)
        val d = m.mon(1)
        Check.ok(m.setField("rain", setter = a), "rain set")
        Check.ok(rec.has("It started to rain!"), "start message")
        val rain = m.battle.fields.single()
        Check.ok(rain.side == null && rain.turnsLeft == 5 && rain.stacks == 1 && rain.setter === a && rain.setterSide === a.side, "rain instance")
        Check.ok(m.battle.isFieldActive("rain") && m.battle.fieldsCovering(d).single() === rain, "a global field covers both sides")
        m.use(a, "water_gun")
        Check.eq(140, d.hp, "rain: water 40 x 1.5")
        m.use(a, "ember")
        Check.eq(120, d.hp, "rain: fire 40 x 0.5")
        Check.ok(!m.setField("rain", setter = a), "stacking ignore: setting it again fails")
        Check.eq(1, rec.count("It started to rain!"), "no second start message")
        Check.ok(m.setField("sun", setter = d), "sun replaces rain (same group)")
        Check.eq(listOf("sun"), m.battle.fields.map { it.def.id }, "only sun is left")
        Check.ok(!rec.has("The rain stopped."), "replaced silently")
        Check.ok(rec.has("Sun started."), "default start message")
        m.use(a, "water_gun")
        Check.eq(80, d.hp, "no rain bonus any more")
    }

    // Side fields: coverage, default messages, modifyDamageTaken trigger, side "both", "But it failed!".
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle", "screen_up", "screen_both")), team(member("leaf", "tackle")))
        val a = m.mon(0)
        val d = m.mon(1)
        Check.ok(m.setField("screen", side = m.battle.sides[1], setter = a), "screen on side 2")
        Check.ok(rec.has("Screen started on Player 2's side."), "side start message")
        Check.ok(m.battle.fieldsCovering(d).size == 1 && m.battle.fieldsCovering(a).isEmpty(), "a side field covers its side only")
        m.use(a, "tackle")
        Check.eq(170, d.hp, "screen halves 60")
        m.use(d, "tackle")
        Check.eq(160, a.hp, "the other side is not covered")
        m.use(a, "screen_both")
        Check.eq(2, m.battle.fields.size, "screen_both set the missing one")
        Check.ok(rec.has("Screen started on Player 1's side.") && !rec.has("But it failed!"), "one side was already covered: not a failure")
        m.use(a, "screen_up")
        Check.eq(1, rec.count("But it failed!"), "stacking ignore from a status move: But it failed!")
        m.removeField(m.battle.fields[0])
        Check.ok(rec.has("Screen ended on Player 2's side."), "default end message")
    }

    // Field statModifiers and stacking "stack"; field functions.
    run {
        val (m, _) = field(setup, team(member("plain", "tackle")), team(member("slow", "tackle")))
        val a = m.mon(0)
        val d = m.mon(1)
        m.setField("tailwind", side = a.side, setter = a)
        Check.near(100.0, a.stat("spe"), "tailwind doubles speed")
        Check.near(10.0, d.stat("spe"), "not on the other side")
        m.setField("power")
        Check.near(150.0, a.stat("atk"), "power x1 stack")
        m.setField("power", stacks = 2)
        Check.eq(3, m.battle.field("power")!!.stacks, "stacks capped at maxStacks")
        Check.near(250.0, d.stat("atk"), "power x3 stacks, every monster")
        Check.ok(!m.setField("power"), "full stacks and no duration: fails")
        m.setField("rain")
        Check.eq(1.0, m.eval("fieldActive('rain')", a), "fieldActive")
        Check.eq(0.0, m.eval("fieldActive('sun')", a), "fieldActive (inactive)")
        Check.eq(1.0, m.eval("sideField(self, 'tailwind')", a), "sideField")
        Check.eq(0.0, m.eval("sideField(other, 'tailwind')", a, d), "sideField on the other side")
        Check.eq(0.0, m.eval("sideField(other, 'tailwind')", a), "sideField of an absent monster")
        Check.eq(3.0, m.eval("fieldStacks('power')", a), "fieldStacks")
        Check.eq(1.0, m.eval("sideFieldStacks(self, 'tailwind')", a), "sideFieldStacks")
        Check.eq(5.0, m.eval("fieldTurns('rain')", a), "fieldTurns")
        Check.eq(0.0, m.eval("fieldTurns('power')", a), "fieldTurns of a permanent field")
        Check.eq(0.0, m.eval("fieldStacks('tailwind')", a), "fieldStacks reads global fields only")
        Check.eq(1.0, m.eval("sameSide(self, self)", a) - m.eval("sameSide(self, other)", a, d), "sameSide")
        val (m2, _) = field(setup, team(member("blaze", "tackle")), team(member("leaf", "tackle")))
        Check.eq(2.0, m2.eval("typeMultiplier('water', self)", m2.mon(0)), "typeMultiplier against fire")
        Check.eq(1.0, m2.eval("typeMultiplier('water', other)", m2.mon(0), m2.mon(1)), "typeMultiplier neutral")
        Check.eq(1.0, m2.eval("typeMultiplier('water', other)", m2.mon(0)), "typeMultiplier of an absent monster")
    }

    // Field triggers: covered (hazards), opponents ("when an enemy takes damage"), all, other = setter, fieldSet/fieldRemoved.
    run {
        val (m, rec) = field(
            setup,
            team(member("plain", "tackle", "spike_it", "spike_two", "clear_spikes", "set_thorns")),
            team(member("slow", "tackle"), member("backup", "tackle")),
        )
        val a = m.mon(0)
        val slow = m.mon(1)
        val backup = m.mon(1, 1)
        m.use(a, "spike_it")
        Check.ok(m.battle.field("spikes", m.battle.sides[1]) != null, "spikes set on the enemy side")
        m.switchIn(backup, 0)
        Check.ok(rec.has("Backup is hurt by Spikes!"), "hazard message")
        Check.eq(40, backup.hp, "1 layer: 10")
        m.use(a, "spike_two")
        Check.eq(3, m.battle.field("spikes", m.battle.sides[1])!!.stacks, "1 + 2 layers")
        m.switchIn(slow, 0)
        Check.eq(170, slow.hp, "3 layers: 30")
        m.use(a, "spike_it")
        Check.eq(1, rec.count("But it failed!"), "a 4th layer fails")
        m.use(a, "clear_spikes")
        Check.ok(rec.has("Plain cleared the Spikes on Player 2's side!") && m.battle.fields.isEmpty(), "removeField with a message")

        m.use(a, "set_thorns")
        m.use(a, "tackle")
        Check.ok(rec.has("Slow is pricked by Thorns!"), "opponents: the enemy took damage")
        Check.eq(109, slow.hp, "60 + 1")
        m.use(slow, "tackle")
        Check.eq(0, rec.count("Plain is pricked by Thorns!"), "covered monsters are not opponents")
    }
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle", ability = "sense")), team(member("slow", "tackle", ability = "sense")))
        val a = m.mon(0)
        val d = m.mon(1)
        m.setField("rain", setter = a)
        Check.ok(rec.has("Slow noticed Rain (set by Plain)!"), "fieldSet: other = the setter")
        Check.ok(rec.has("Plain noticed Rain (set by )!"), "fieldSet for the setter itself: no other")
        Check.ok(rec.index("It started to rain!") < rec.index("Slow noticed Rain (set by Plain)!"), "start message first")
        m.setField("aura", setter = a)
        m.setField("watch", side = a.side, setter = a)
        m.applyStatus(d, "leech", a)
        m.events.emit(EventType.TURN_END, d)
        Check.ok(rec.has("Slow senses Plain."), "no other in the event: other = the setter")
        Check.ok(rec.index("Plain drains Slow!") in 0 until rec.index("Slow senses Plain."), "statuses fire before fields")
        Check.ok(rec.index("Slow senses Plain.") < rec.index("Slow is watched."), "fields fire in the order they were set")
        m.removeStatus(d, d.status("leech")!!)
        Check.ok(rec.has("Slow is watched."), "\"all\" hears the other side too")
        m.events.emit(EventType.TURN_END, a)
        Check.ok(rec.has("Plain senses .") && rec.has("Plain is watched."), "the setter itself")
        // A fainted setter does not end the field, but it is no longer `other`.
        a.hp = 0
        m.events.emit(EventType.TURN_END, d)
        Check.eq(1, rec.count("Slow senses Plain."), "no new line with the fainted setter")
        Check.ok(rec.has("Slow senses ."), "fainted setter: no other")
        Check.ok(m.battle.isFieldActive("aura"), "fields outlive their setter")
        a.hp = a.maxHp
        m.removeField(m.battle.field("rain")!!, by = d)
        Check.ok(rec.has("The rain stopped.") && rec.has("Slow saw the Rain end.") && rec.has("Plain saw the Rain end."), "fieldRemoved")
        // A removed field's own triggers hear its fieldRemoved.
        m.setField("timebomb")
        m.removeField(m.battle.field("timebomb")!!)
        Check.ok(rec.has("Timebomb went off on Plain!") && rec.has("Timebomb went off on Slow!"), "own fieldRemoved triggers")
        Check.eq(160, d.hp, "timebomb damage (after 10 from the leech)")
    }

    // Effects: removeField by group, "all" with side "self", stacking "refresh", silent end message.
    run {
        val (m, rec) = field(setup, team(member("plain", "clear_weather", "clear_mine")), team(member("slow", "tackle")))
        val a = m.mon(0)
        m.setField("rain")
        m.setField("screen")
        Check.eq(3, m.battle.fields.size, "rain + screen on both sides")
        m.use(a, "clear_weather")
        Check.ok(rec.has("The rain stopped.") && m.battle.fields.size == 2, "removeField by group")
        m.setField("tailwind", side = m.battle.sides[1])
        m.setField("aura")
        m.use(a, "clear_mine")
        Check.eq(listOf("screen:Player 2", "tailwind:Player 2"), m.battle.fields.map { "${it.def.id}:${it.side?.name}" }, "all + side self: global fields and own side")
        m.setField("fresh")
        m.battle.field("fresh")!!.let { Check.eq(2, it.turnsLeft, "fresh duration") }
        Check.ok(m.setField("fresh"), "refresh with a duration succeeds")
        Check.eq(2, rec.count("Fresh (1)."), "refresh logs the start message again")
        m.removeField(m.battle.field("fresh")!!)
        Check.ok(!rec.has("Fresh ended."), "endMessage \"\" is silent")
    }

    // Countdown, speed_order: a 5-turn field set in round 1 ends at the end of round 5; the effect's duration and message.
    run {
        val s = lab.setup(lab.rules("turnLimit" to 7))
        val (_, rec) = battle(s, team(member("plain", "rain_dance", "wait")), team(member("slow", "wait")), Script(choose = useThen("rain_dance", 1, "wait")), idle)
        Check.eq(listOf(1), rec.turnsOf("It started to rain!"), "set in round 1")
        Check.eq(listOf(5), rec.turnsOf("The rain stopped."), "ends at the end of round 5")
        Check.ok(rec.index("The rain stopped.") > rec.index("Turn 5") && rec.index("The rain stopped.") < rec.index("Turn 6"), "after the round's turnEnd")
        val (_, rec2) = battle(s, team(member("plain", "short_rain", "wait")), team(member("slow", "wait")), Script(choose = useThen("short_rain", 1, "wait")), idle)
        Check.ok(rec2.has("Plain summoned a short Rain!") && !rec2.has("It started to rain!"), "setField message replaces the start message")
        Check.eq(listOf(2), rec2.turnsOf("The rain stopped."), "duration override 2")
    }
    // Start fields: Rules.startFields (before the leads enter, permanent by default) and the constructor's.
    run {
        val rules = lab.rules(
            "turnLimit" to 4,
            "startFields" to listOf(mapOf("field" to "rain", "duration" to 3), mapOf("field" to "screen", "side" to "side2")),
        )
        val s = lab.setup(rules)
        Check.eq(2, s.rules.startFields.size, "startFields parsed")
        Check.ok(s.rules.startFields[1].permanent && s.rules.startFields[1].side == 1 && !s.rules.startFields[0].permanent, "startFields values")
        val rec = Recorder()
        val m = Manager(s, listOf(team(member("plain", "wait")), team(member("slow", "wait"))), listOf(idle, idle), rec, 1, startFields = listOf(FieldStart("sun", permanent = false)))
        m.run()
        Check.ok(rec.index("It started to rain!") in 0 until rec.index("Player 1 sends out Plain!"), "start fields are set before the leads enter")
        Check.ok(rec.has("Screen started on Player 2's side.") && !rec.has("Screen ended on Player 2's side."), "a permanent start field")
        Check.ok(rec.has("Sun started.") && rec.index("Sun started.") < rec.index("Player 1 sends out Plain!"), "constructor start field")
        Check.ok(rec.index("Sun started.") > rec.index("It started to rain!"), "after Rules.startFields")
        // Sun replaced rain (same group), so only sun counts down (default duration 5 > turn limit).
        Check.eq(listOf("screen", "sun"), m.battle.fields.map { it.def.id }.sorted(), "fields at the end")
        val m2 = Manager(s, listOf(team(member("plain", "wait")), team(member("slow", "wait"))), listOf(idle, idle), Recorder(), 1)
        val rec3 = Recorder()
        Manager(s, listOf(team(member("plain", "wait")), team(member("slow", "wait"))), listOf(idle, idle), rec3, 1).run()
        Check.eq(listOf(3), rec3.turnsOf("The rain stopped."), "a start field with a duration counts down from round 1")
        Check.ok(m2.battle.fields.isEmpty(), "nothing is set before run()")
    }
    // Countdown, action_bar: on the turns of the setter's side (the turn it was set counts); start fields every turn.
    run {
        val start = mapOf("field" to "watch", "side" to "side1", "duration" to 2)
        val rules = lab.rules("turnSystem" to "action_bar", "turnLimit" to 6, "startFields" to listOf(start))
        val s = lab.setup(rules)
        val (_, rec) = battle(s, team(member("fast", "short_rain", "wait")), team(member("even", "wait")), Script(choose = useThen("short_rain", 1, "wait")), idle)
        Check.eq(listOf("Fast", "Even", "Fast", "Even", "Fast", "Even"), rec.actors.map { it?.name }, "alternating turns")
        Check.eq(listOf(2), rec.turnsOf("Watch ended on Player 1's side."), "a start field counts every turn")
        Check.eq(listOf(1), rec.turnsOf("Fast summoned a short Rain!"), "rain set on turn 1")
        Check.eq(listOf(3), rec.turnsOf("The rain stopped."), "counts on Fast's turns 1 and 3")
    }
    // Group exclusivity holds when a replaced field's fieldRemoved trigger sets another field of the group.
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle")), team(member("slow", "tackle")))
        val a = m.mon(0)
        Check.ok(m.setField("sand", setter = a), "sand set")
        Check.ok(m.setField("rain", setter = a), "rain replaces sand")
        Check.eq(listOf("hail"), m.battle.fields.map { it.def.id }, "sand's reaction replaced rain: one weather only")
        Check.ok(rec.has("Hail started.") && !rec.has("It started to rain!"), "no start line for the replaced rain")
        Check.ok(m.setField("sun", setter = a), "sun replaces hail")
        Check.eq(listOf("sun"), m.battle.fields.map { it.def.id }, "still one weather")
    }
    // A team-target status move that sets a field for its first target did not fail (no "But it failed!").
    run {
        val doubles = lab.setup(lab.rules("team" to mapOf("min" to 1, "max" to 6, "active" to 2)))
        val (m, rec) = field(doubles, team(member("plain", "team_screen"), member("slow", "tackle")), team(member("leaf", "tackle")))
        m.use(m.mon(0), "team_screen")
        Check.ok(rec.has("Screen started on Player 1's side."), "team_screen set the screen")
        Check.ok(!rec.has("But it failed!"), "no failure line after a successful set")
        m.use(m.mon(0), "team_screen")
        Check.eq(1, rec.count("But it failed!"), "setting it again does fail")
    }
}

private fun fieldRuleTests(lab: EngineLab) {
    val setup = lab.setup()
    // Formula overrides; the most recently set field wins; only when the attacker is covered.
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle", "sure")), team(member("slow", "tackle")))
        val a = m.mon(0)
        val d = m.mon(1)
        val tackle = a.moves[0].def
        Check.eq(60, m.damage.calculate(m.battle, a, d, tackle, 1).damage, "no field")
        m.setField("flat")
        Check.eq(50, m.damage.calculate(m.battle, a, d, tackle, 1).damage, "global damage override")
        m.setField("flatter", side = d.side)
        Check.eq(50, m.damage.calculate(m.battle, a, d, tackle, 1).damage, "a side override needs the attacker covered")
        Check.eq(8, m.damage.calculate(m.battle, d, a, tackle, 1).damage, "the most recent override wins (7 + stacks)")
        m.setField("flatter", side = d.side, stacks = 1)
        Check.eq(9, m.damage.calculate(m.battle, d, a, tackle, 1).damage, "its stacks")
        m.removeField(m.battle.field("flat")!!)
        m.removeField(m.battle.field("flatter", d.side)!!)
        m.setField("focus")
        val r = m.damage.calculate(m.battle, a, d, tackle, 1)
        Check.ok(r.isCrit && r.damage == 180, "critChance 1 and critMultiplier 3: ${r.damage}")
        m.setField("fog")
        m.use(a, "sure")
        Check.ok(rec.has("Slow avoided the attack!"), "hitChance override")
        Check.near(0.0, m.damage.hitChance(m.battle, a, d, a.moves[1].def), "hitChance 0")
    }
    run {
        val s = lab.setup(lab.rules("turnSystem" to "action_bar"))
        val (m, _) = field(s, team(member("plain", "tackle")), team(member("slow", "tackle")))
        m.setField("quickbar", side = m.mon(0).side)
        Check.near(150.0, m.battle.fillRate(m.mon(0)), "actionBarFill override for covered monsters")
        Check.near(10.0, m.battle.fillRate(m.mon(1)), "not for the others")
    }

    // reverseSpeed (speed_order): slowest first, priority still first; set mid-battle it applies from the next round.
    run {
        val s = lab.setup(lab.rules("turnLimit" to 1, "startFields" to listOf(mapOf("field" to "room"))))
        val (_, rec) = battle(s, team(member("slow", "tackle")), team(member("swift", "tackle")), Script(choose = use("tackle")), Script(choose = use("tackle")))
        Check.ok(rec.index("Slow used Tackle!") < rec.index("Swift used Tackle!"), "trick room: the slower first")
        val (_, rec2) = battle(s, team(member("slow", "tackle")), team(member("swift", "quick")), Script(choose = use("tackle")), Script(choose = use("quick")))
        Check.ok(rec2.index("Swift used Quick!") < rec2.index("Slow used Tackle!"), "priority still first")
        val s2 = lab.setup(lab.rules("turnLimit" to 2))
        val (_, rec3) = battle(s2, team(member("slow", "tackle")), team(member("swift", "trick_room", "tackle")), Script(choose = use("tackle")), Script(choose = useThen("trick_room", 1, "tackle")))
        Check.ok(rec3.index("Swift used Trick Room!") < rec3.index("Slow used Tackle!"), "round 1 in normal order")
        val texts = rec3.texts()
        val round2 = texts.subList(texts.indexOf("Turn 2"), texts.size)
        Check.ok(round2.indexOf("Slow used Tackle!") in 0 until round2.indexOf("Swift used Tackle!"), "round 2 reversed")
        val sw = lab.setup(lab.rules("turnLimit" to 1, "startFields" to listOf(mapOf("field" to "room"))))
        val (_, rec4) = battle(sw, team(member("slow", "tackle"), member("plain", "tackle")), team(member("swift", "tackle")),
            Script { battle, actor -> Action.Switch(battle.switchOptions(actor).first()) }, Script(choose = use("tackle")))
        Check.ok(rec4.index("Player 1 withdraws Slow!") < rec4.index("Swift used Tackle!"), "switches still go first")
    }

    // disableAbilities / disableItems: no statModifiers, no triggers, hasAbility/hasItem false.
    run {
        val (m, rec) = field(
            setup,
            team(member("blaze", "tackle", ability = "sense", item = "armor")),
            team(member("plain", "tackle", ability = "sense", item = "armor")),
        )
        val a = m.mon(0)
        val d = m.mon(1)
        Check.near(200.0, a.stat("def"), "sense doubles def")
        Check.eq(1.0, m.eval("hasAbility(self, 'sense') + hasItem(self) - hasItem(self, 'armor')", a), "before")
        m.setField("null_zone")
        Check.ok(a.activeAbility == null && a.ability != null, "ability suppressed (still held)")
        Check.ok(a.activeItem == null && d.activeItem != null, "items suppressed by the formula (fire types only)")
        Check.ok(!rec.has("Blaze noticed Null Zone (set by )!"), "a suppressed ability does not hear fieldSet")
        Check.near(100.0, a.stat("def"), "no ability statModifiers")
        Check.eq(0.0, m.eval("hasAbility(self, 'sense')", a), "hasAbility false")
        Check.eq(0.0, m.eval("hasItem(self)", a), "hasItem false")
        Check.eq(1.0, m.eval("hasItem(self, 'armor')", d), "not suppressed for the other one")
        m.use(d, "tackle")
        Check.eq(a.maxHp - 60, a.hp, "the armor trigger does nothing")
        m.use(a, "tackle")
        Check.eq(d.maxHp - 20, d.hp, "d's armor still works (40 x 0.5)")
    }

    // blockMove: at choice time (usableMoves, the Manager's validation) and at execution with the target.
    run {
        val (m, rec) = field(setup, team(member("plain", "jab", "tackle")), team(member("spook", "tackle")))
        val a = m.mon(0)
        m.setField("grounded")
        Check.eq(listOf("tackle"), m.battle.usableMoves(a).map { it.def.id }, "jab blocked at choice time; tackle has no target yet")
        Check.ok(m.battle.isMoveBlocked(a, a.moves[1].def, m.mon(1)), "tackle blocked against a ghost")
        m.use(a, "tackle")
        Check.ok(rec.has("Plain can't use Tackle because of Grounded!") && !rec.has("Plain used Tackle!"), "blocked at execution")
        m.use(a, "jab")
        Check.eq(2, a.moves[0].pp, "a blocked move spends no PP")
        val s = lab.setup(lab.rules("turnLimit" to 1, "startFields" to listOf(mapOf("field" to "grounded"))))
        val (_, rec2) = battle(s, team(member("plain", "jab", "tackle")), team(member("slow", "wait")), Script(choose = use("jab")), idle)
        Check.ok(rec2.has("Plain used Tackle!") && !rec2.has("Plain used Jab!"), "a blocked choice is replaced by the first legal move")
    }

    // blockStatus: blocked major statuses from the other side, with a message once per target; others pass.
    run {
        val (m, rec) = field(setup, team(member("plain", "burn_it", "hush_burn", "lull")), team(member("slow", "tackle")))
        val a = m.mon(0)
        val d = m.mon(1)
        m.setField("veil", side = d.side)
        m.use(a, "burn_it")
        Check.ok(!d.hasStatus("burn") && rec.count("Slow is protected by Veil!") == 1, "burn blocked")
        Check.ok(!rec.has("But it failed!"), "no extra failure line")
        m.use(a, "hush_burn")
        Check.eq(2, rec.count("Slow is protected by Veil!"), "a silent status from a move still says it was blocked")
        m.use(a, "lull")
        Check.ok(d.hasStatus("doze"), "other kinds pass")
        Check.ok(m.applyStatus(d, "burn", d), "self-inflicted: sameSide(self, other)")
        Check.ok(m.applyStatus(a, "poison", d), "the other side is not covered")
    }

    // blockStatStage.
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle")), team(member("slow", "tackle")))
        val a = m.mon(0)
        val d = m.mon(1)
        m.setField("mist", side = d.side)
        m.changeStage(d, "atk", -1, a)
        Check.ok(d.stage("atk") == 0 && rec.has("Slow's Attack is protected by Mist!"), "drop by an enemy blocked")
        m.changeStage(d, "atk", 1, a)
        Check.eq(1, d.stage("atk"), "raises pass")
        m.changeStage(d, "atk", -1, d)
        Check.eq(0, d.stage("atk"), "self-inflicted drops pass")
    }
}

private fun shippedDataTests(root: File) {
    val dir = File(root, "Setups")
    val classic = try { Setup.load(File(dir, "Classic")) } catch (e: SetupException) { null }
    val gacha = try { Setup.load(File(dir, "Gacha")) } catch (e: SetupException) { null }
    fun mon(species: String, level: Int, ability: String?, item: String?, vararg moves: String) =
        Bakemon(species = species, level = level, ability = ability, item = item, moves = moves.toList())
    if (classic != null) {
        // Lifeline Ribbon is not spent on a hit the holder's ability absorbed; Bulwark + Ribbon spend only Bulwark.
        run {
            val (m, rec) = field(classic, team(mon("tidemaw", 100, "mend", null, "tidal_wave")), team(mon("aquafin", 20, "sponge_body", "lifeline_ribbon", "tackle")))
            m.use(m.mon(0), "tidal_wave")
            Check.ok(m.mon(1).item?.id == "lifeline_ribbon" && rec.log.none { "Lifeline Ribbon" in it.text }, "ribbon kept under Sponge Body: ${rec.texts()}")
            val (m2, rec2) = field(classic, team(mon("knucklord", 100, "grit", null, "rapid_jab")), team(mon("molegrind", 20, "bulwark", "lifeline_ribbon", "tackle")))
            m2.use(m2.mon(0), "rapid_jab")
            Check.ok(rec2.texts().any { "Bulwark" in it } && m2.mon(1).item?.id == "lifeline_ribbon", "only Bulwark is spent: ${rec2.texts()}")
            Check.eq(1, m2.mon(1).hp, "Bulwark leaves exactly 1 HP")
        }
        // Spiked Hide and Thorn Helmet also punish the contact hit that knocks the holder out.
        run {
            val (m, _) = field(classic, team(mon("knucklord", 50, "grit", null, "rapid_jab")), team(mon("cragmaul", 50, "spiked_hide", "thorn_helmet", "rockfall")))
            val attacker = m.mon(0)
            m.mon(1).hp = 1
            m.use(attacker, "rapid_jab")
            Check.ok(m.mon(1).isFainted, "knocked out")
            Check.eq(attacker.maxHp - attacker.maxHp / 8 - attacker.maxHp / 6, attacker.hp, "recoil from both on the KO hit")
        }
        // Seed Leech heals only what it drained.
        run {
            val (m, _) = field(classic, team(mon("sproutling", 50, "overgrowth", null, "seed_leech")), team(mon("snugglug", 50, "thick_blubber", null, "tackle")))
            val seeder = m.mon(0)
            val seeded = m.mon(1)
            Check.ok(m.applyStatus(seeded, "seeded", seeder), "seeded")
            seeded.hp = 3
            seeder.hp = 10
            m.events.emit(EventType.TURN_END, seeded)
            Check.ok(seeded.isFainted, "drained to 0")
            Check.eq(13, seeder.hp, "healed by the 3 HP drained, not 1/8 of the max HP")
        }
        // Drain Fist "recovers half of the damage dealt": a finishing blow on a 1-HP target drains 1 HP, not half a hit.
        run {
            val (m, rec) = field(classic, team(mon("monkuru", 50, null, null, "drain_fist")),
                team(mon("snugglug", 50, null, null, "tackle"), mon("aquafin", 50, null, null, "tackle")))
            val monkuru = m.mon(0)
            monkuru.hp = monkuru.maxHp / 2
            m.mon(1).hp = 1
            m.use(monkuru, "drain_fist")
            Check.ok(m.mon(1).isFainted, "knocked out")
            Check.eq(monkuru.maxHp / 2 + 1, monkuru.hp, "drained max(1, 1 / 2) = 1 HP: ${rec.texts()}")
        }
        // Blaze works at exactly 1/3 HP.
        run {
            val (m, _) = field(classic, team(mon("cindercub", 50, "blaze", null, "ember")), team(mon("snugglug", 50, "thick_blubber", null, "tackle")))
            val cub = m.mon(0)
            val condition = classic.abilities.require("blaze").triggers.single().condition!!
            fun active(hp: Int): Boolean {
                cub.hp = hp
                return condition.test(m.battle.context(cub, m.mon(1), move = classic.moves.require("ember")))
            }
            Check.eq(0, cub.maxHp % 3, "max HP divisible by 3 (${cub.maxHp})")
            Check.ok(active(cub.maxHp / 3) && !active(cub.maxHp / 3 + 1), "Blaze at exactly 1/3 HP but not above")
        }
        // Paralysis and confusion never both stop the same move.
        run {
            var both = 0
            var para = 0
            var hurt = 0
            for (seed in 1L..300L) {
                val (m, rec) = field(classic, team(mon("snugglug", 50, "thick_blubber", null, "tackle")), team(mon("tidemaw", 50, "mend", null, "growl")), seed)
                val snug = m.mon(0)
                if (seed % 2 == 0L) {
                    m.applyStatus(snug, "paralysis", null)
                    m.applyStatus(snug, "confusion", null)
                } else {
                    m.applyStatus(snug, "confusion", null)
                    m.applyStatus(snug, "paralysis", null)
                }
                m.use(snug, "tackle")
                val p = rec.has("Snugglug is paralyzed! It can't move!")
                val h = rec.has("Snugglug hurt itself in its confusion!")
                if (p) para++
                if (h) hurt++
                if (p && h) both++
            }
            Check.eq(0, both, "never fully paralyzed and hurt by confusion in the same turn")
            Check.ok(para > 0 && hurt > 0, "both still happen on their own ($para, $hurt)")
        }
        // Sleep "for 1 to 3 turns": a faster Lullaby user costs the target 1 to 3 moves, never 4.
        run {
            val lengths = mutableSetOf<Int>()
            for (seed in 1L..40L) {
                var slept = false
                val c0 = Script { battle, actor ->
                    if (battle.sides[1].team[0].hasStatus("sleep")) slept = true
                    use(if (slept) "tackle" else "lullaby")(battle, actor)
                }
                val (_, rec) = battle(classic, team(mon("mindling", 100, "hover", null, "lullaby", "tackle")), team(mon("snugglug", 50, "thick_blubber", null, "tackle")),
                    c0, Script(choose = use("tackle")), seed)
                val texts = rec.texts()
                val start = texts.indexOf("Snugglug fell asleep!")
                if (start < 0) continue
                val end = texts.indexOf("Snugglug woke up!").let { if (it < 0) texts.size else it }
                lengths += texts.subList(start, end).count { it == "Snugglug is fast asleep." }
            }
            Check.ok(lengths.isNotEmpty() && lengths.all { it in 1..3 }, "lost moves per sleep are 1..3: $lengths")
        }
    }
    if (gacha != null) {
        // Flat stat bonuses add up: Keen Lens (+15) and Crit Rate Up (+30); Deep Shell (+15) and Ward Talisman (+20).
        run {
            val (m, _) = field(gacha, team(mon("ash_matron", 40, "regrowth", "keen_lens", "kindle_strike"), mon("abyss_guard", 40, "deep_shell", "ward_talisman", "shell_bash")),
                team(mon("night_reaper", 40, "vampiric", null, "shadow_scythe")))
            val matron = m.mon(0, 0)
            val guard = m.mon(0, 1)
            Check.near(30.0, matron.stat("crate"), "Keen Lens: 15 + 15")
            Check.ok(m.applyStatus(matron, "crit_up", null), "crit up")
            Check.near(60.0, matron.stat("crate"), "Keen Lens + Crit Rate Up: 15 + 15 + 30")
            Check.near(70.0, guard.stat("res"), "Deep Shell + Ward Talisman: 35 + 15 + 20")
        }
        // Vampire Chalice (hit) and Vampiric (dealtDamage) agree: 1 HP dealt to a 1-HP target heals 1 each.
        run {
            val (m, rec) = field(gacha, team(mon("night_reaper", 40, "vampiric", "vampire_chalice", "shadow_scythe")),
                team(mon("ash_matron", 40, "regrowth", null, "kindle_strike"), mon("abyss_guard", 40, "deep_shell", null, "shell_bash")))
            val reaper = m.mon(0)
            reaper.hp = reaper.maxHp / 2
            m.mon(1).hp = 1
            m.use(reaper, "shadow_scythe")
            Check.ok(m.mon(1).isFainted, "knocked out")
            Check.eq(reaper.maxHp / 2 + 2, reaper.hp, "Vampiric + Vampire Chalice heal 1 + 1: ${rec.texts()}")
        }
        // Focus Bead adds 20 Accuracy to the healers too (their base Accuracy is not 0).
        run {
            val (m, _) = field(gacha, team(mon("ash_matron", 40, "regrowth", "focus_bead", "kindle_strike"), mon("tide_warden", 40, "regrowth", "focus_bead", "riptide_jab")),
                team(mon("night_reaper", 40, "vampiric", null, "shadow_scythe")))
            for (healer in listOf(m.mon(0, 0), m.mon(0, 1))) {
                Check.near(healer.rawStat("acc") + 20.0, healer.stat("acc"), "${healer.name}: Focus Bead adds 20 Accuracy", 1e-6)
            }
        }
        // Tidal Shell's shield does not switch off its own Reflect.
        run {
            val (m, rec) = field(gacha, team(mon("abyss_guard", 40, "retaliation", null, "tidal_shell")), team(mon("night_reaper", 40, "bloodlust", null, "shadow_scythe")))
            val guard = m.mon(0)
            val reaper = m.mon(1)
            m.use(guard, "tidal_shell")
            Check.ok(guard.shield > 0 && guard.hasStatus("reflect"), "shield and reflect")
            m.use(reaper, "shadow_scythe", guard)
            Check.ok(rec.texts().any { "reflects the blow back" in it }, "Reflect fires while the shield absorbs: ${rec.texts().takeLast(5)}")
            Check.ok(reaper.hp < reaper.maxHp, "the attacker took the reflected damage")
        }
    }
}

private fun smokeTests(root: File) {
    val setupsRoot = File(root, "Setups")
    val folders = if (setupsRoot.isDirectory) Setup.discover(setupsRoot) else emptyList()
    if (folders.isEmpty()) println("  (no setups found under $setupsRoot)")
    for (folder in folders) {
        val setup = try {
            Setup.load(folder)
        } catch (e: SetupException) {
            println("  (skipping setup ${folder.name}: it does not load)")
            continue
        }
        val rng = Random(folder.name.hashCode())
        var ended = 0
        var failure: Throwable? = null
        val start = System.nanoTime()
        for (i in 0 until 30) {
            try {
                val teams = listOf(setup.randomTeam(rng), setup.randomTeam(rng))
                val recorder = Recorder()
                val m = Manager(setup, teams, listOf(RandomController(rng.nextLong()), RandomController(rng.nextLong())), recorder, rng.nextLong())
                val result = m.run()
                if (m.battle.ended && recorder.result == result) ended++
            } catch (t: Throwable) {
                if (failure == null) failure = t
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        Check.ok(failure == null, "${folder.name}: battles run without exceptions (${failure?.let { "$it at ${it.stackTrace.take(4).joinToString(" <- ")}" }})")
        Check.eq(30, ended, "${folder.name}: every battle ends")
        println("  ${folder.name}: 30 random battles in $ms ms")
    }
}

// ---------------------------------------------------------------------------------------------------------------
// More triggers, targets, effects and move behaviours

/** An event context for resolving selectors by hand. */
private fun ctxOf(subject: Battler, other: Battler? = null, moveUser: Battler? = null, moveTarget: Battler? = null) =
    EventContext(EventType.TURN_END, subject, other, null, moveUser, moveTarget)

private fun doubles(lab: EngineLab, vararg overrides: Pair<String, Any?>) =
    lab.triggerSetup(lab.rules("team" to mapOf("min" to 1, "max" to 6, "active" to 2), *overrides))

private fun formulaTriggerTests(lab: EngineLab) {
    val setup = lab.triggerSetup()

    // hitIndex / hitCount: Triple Kick-like power, per-hit conditions in hit and modifyDamageDealt.
    run {
        val (m, rec) = field(setup, team(member("plain", "kick3")), team(member("leaf")))
        m.use(m.mon(0), "kick3")
        for (d in listOf(20, 40, 60)) Check.ok(rec.has("Leaf took $d damage."), "hit power 20 * hitIndex: $d (${rec.texts()})")
        Check.ok(rec.has("Hit 3 times!"), "three hits")
        Check.eq(1, rec.count("Last of 60!"), "hitIndex == hitCount only on the last hit")
        Check.eq(1, rec.count("Second hit coming."), "hitIndex in modifyDamageDealt")
    }

    // recipient: every target's own max HP.
    run {
        val (m, _) = field(doubles(lab), team(member("plain", "group_heal"), member("weak")), team(member("leaf"), member("puddle")))
        val a = m.mon(0)
        val w = m.mon(0, 1)
        a.hp = 100
        w.hp = 5
        m.use(a, "group_heal")
        Check.eq(140, a.hp, "20% of the owner's own max HP")
        Check.eq(7, w.hp, "20% of the ally's own max HP (not the owner's)")
        Check.near(0.0, m.eval("recipient.hp + recipient.maxHp", a), "recipient is absent outside effects")
    }

    // hpBefore: "HP just dropped below 50%" fires once.
    run {
        val (m, rec) = field(setup, team(member("plain", ability = "threshold")), team(member("leaf")))
        val a = m.mon(0)
        m.dealDamage(a, 90, null, null)
        Check.eq(0, rec.count("Plain dropped below half!"), "still above half")
        m.dealDamage(a, 20, null, null)
        Check.eq(1, rec.count("Plain dropped below half!"), "crossed 50%")
        m.dealDamage(a, 10, null, null)
        Check.eq(1, rec.count("Plain dropped below half!"), "already below: no new crossing")
    }

    // m.acted: speed_order = already acted this round.
    run {
        val (_, rec) = battle(lab.triggerSetup(lab.rules("turnLimit" to 1)), team(member("swift", "payback")), team(member("slow", "payback")),
            Script { b, a -> use("payback")(b, a) }, Script { b, a -> use("payback")(b, a) })
        Check.ok(rec.has("Slow took 10 damage."), "the faster user hits a target that has not acted (${rec.texts()})")
        Check.ok(rec.has("Swift took 100 damage."), "the slower user hits a target that already acted")
    }
    run {
        val atb = lab.triggerSetup(lab.rules("turnSystem" to "action_bar"))
        val (m, _) = field(atb, team(member("plain")), team(member("leaf")))
        val a = m.mon(0)
        Check.near(0.0, m.eval("self.acted", a), "action_bar: not its turn")
        m.battle.currentActor = a
        Check.near(1.0, m.eval("self.acted", a), "action_bar: 1 while it is the acting monster")
        Check.near(0.0, m.eval("self.acted", m.mon(1)), "action_bar: the other monster")
        m.battle.currentActor = null
    }

    // statusCount: each status once, optionally by kind.
    run {
        val (m, _) = field(setup, team(member("plain")), team(member("leaf")))
        val a = m.mon(0)
        m.applyStatus(a, "focus", null, stacks = 2)
        m.applyStatus(a, "dazed", null)
        m.applyStatus(a, "burn", null)
        Check.near(3.0, m.eval("statusCount(self)", a), "statusCount counts statuses, not stacks")
        Check.near(1.0, m.eval("statusCount(self, 'debuff')", a), "debuffs")
        Check.near(1.0, m.eval("statusCount(self, 'buff')", a), "buffs")
        Check.near(1.0, m.eval("statusCount(self, 'major')", a), "major")
        Check.near(0.0, m.eval("statusCount(self, 'volatile') + statusCount(other)", a), "none / absent monster")
    }
}

private fun targetTriggerTests(lab: EngineLab) {
    val setup = doubles(lab)

    // Effect selectors from the owner's point of view.
    run {
        val (m, _) = field(setup, team(member("plain"), member("weak")), team(member("leaf"), member("backup")))
        val a = m.mon(0)
        val w = m.mon(0, 1)
        val leaf = m.mon(1)
        val backup = m.mon(1, 1)
        fun resolve(selector: TargetSelector, ctx: EventContext = ctxOf(a)) = m.effects.resolveTargets(selector, ctx)
        Check.eq(listOf(a), resolve(TargetSelector.LOWEST_HP_ALLY), "lowestHpAlly: ties go to field order (the owner first)")
        Check.eq(listOf(leaf), resolve(TargetSelector.LOWEST_HP_ENEMY), "lowestHpEnemy tie: field order")
        Check.eq(listOf(leaf), resolve(TargetSelector.HIGHEST_HP_ENEMY), "highestHpEnemy tie: field order")
        a.hp = 100
        w.hp = 9
        Check.eq(listOf(a), resolve(TargetSelector.LOWEST_HP_ALLY), "lowestHpAlly includes the owner (50% < 90%)")
        w.hp = 1
        Check.eq(listOf(w), resolve(TargetSelector.LOWEST_HP_ALLY), "lowestHpAlly by percent")
        backup.hp = 10
        Check.eq(listOf(backup), resolve(TargetSelector.LOWEST_HP_ENEMY), "lowestHpEnemy by percent")
        Check.eq(listOf(leaf), resolve(TargetSelector.HIGHEST_HP_ENEMY), "highestHpEnemy by percent")
        Check.eq(listOf(w), resolve(TargetSelector.RANDOM_ALLY), "randomAlly: the only ally")
        Check.eq(listOf(leaf), resolve(TargetSelector.OTHER_TARGET, ctxOf(a, w, w, leaf)), "otherTarget: the target of the other's move")
        Check.eq(emptyList<Battler>(), resolve(TargetSelector.OTHER_TARGET, ctxOf(a, w, a, leaf)), "otherTarget: nothing when other is not the move user")
        w.hp = 0
        Check.eq(emptyList<Battler>(), resolve(TargetSelector.RANDOM_ALLY), "randomAlly: nobody when alone")
    }

    // lowestHpAlly / lowestHpEnemy moves choose automatically.
    run {
        val (m, rec) = field(setup, team(member("plain", "patch", "snipe"), member("weak")), team(member("leaf"), member("backup")))
        val w = m.mon(0, 1)
        w.hp = 2
        Check.eq(listOf(w), m.battle.targetOptions(m.mon(0), m.setup.moves.require("patch")), "targetOptions: the one it will heal")
        m.use(m.mon(0), "patch")
        Check.ok(rec.has("Weak restored 8 HP."), "heals the ally with the lowest HP (${rec.texts()})")
        m.mon(1, 1).hp = 20
        m.use(m.mon(0), "snipe")
        Check.ok(rec.has("Backup took 10 damage."), "hits the enemy with the lowest HP")
    }

    // randomEnemies: every hit a new random enemy, retargeting when one faints.
    run {
        var sawBoth = false
        for (seed in 1L..12L) {
            val (m, rec) = field(setup, team(member("plain", "sweep")), team(member("puddle", ability = "watchful"), member("weak")), seed = seed)
            m.use(m.mon(0), "sweep")
            val tookLines = rec.texts().filter { it.endsWith(" took 10 damage.") }
            Check.eq(4, tookLines.size, "seed $seed: four hits in all (${rec.texts()})")
            Check.ok(rec.has("Hit 4 times!"), "seed $seed: Hit 4 times!")
            Check.ok(tookLines.count { it.startsWith("Weak") } <= 1, "seed $seed: no hit on the fainted enemy")
            Check.ok(rec.count("Puddle watches Plain's Sweep.") <= 1, "seed $seed: targeted once per move use")
            if (tookLines.any { it.startsWith("Weak") } && tookLines.any { it.startsWith("Puddle") }) sawBoth = true
        }
        Check.ok(sawBoth, "both enemies get hit across seeds")
    }
}

private fun eventTriggerTests(lab: EngineLab) {
    val setup = lab.triggerSetup()

    // targeted: before the accuracy check, once per move use, never for the user's own moves.
    run {
        val (m, rec) = field(setup, team(member("plain", "whiff", "kick3", "wait")), team(member("leaf", ability = "watchful")))
        m.use(m.mon(0), "whiff")
        val watched = rec.index("Leaf watches Plain's Whiff.")
        Check.ok(watched >= 0 && watched < rec.index("Leaf avoided the attack!"), "targeted before the miss (${rec.texts()})")
        m.use(m.mon(0), "kick3")
        Check.eq(1, rec.count("Leaf watches Plain's Kick3."), "once for three hits")
        val (m2, rec2) = field(setup, team(member("plain", "wait", ability = "watchful")), team(member("leaf")))
        m2.use(m2.mon(0), "wait")
        Check.ok(rec2.texts().none { "watches" in it }, "not for a move aimed at itself")
    }

    // attacked: damage = all the hits, landed; also when it missed; after the move.
    run {
        val (m, rec) = field(setup, team(member("plain", "kick3", "whiff")), team(member("leaf", ability = "noter")))
        m.use(m.mon(0), "kick3")
        Check.ok(rec.has("Hit for 120."), "attacked: damage of all the hits (${rec.texts()})")
        Check.ok(rec.index("Hit 3 times!") < rec.index("Hit for 120."), "attacked comes after the move")
        m.use(m.mon(0), "whiff")
        Check.ok(rec.has("Leaf dodged Plain."), "attacked also after a miss (landed 0)")
    }

    // allyDamaged / enemyDamaged.
    run {
        val (m, rec) = field(doubles(lab), team(member("plain", ability = "guardian"), member("weak")), team(member("leaf", ability = "gloat"), member("backup")))
        m.dealDamage(m.mon(0, 1), 3, null, null)
        Check.ok(rec.has("Plain saw Weak lose 3."), "allyDamaged (${rec.texts()})")
        Check.ok(rec.has("Leaf enjoys Weak losing 3."), "enemyDamaged")
        Check.ok(rec.texts().none { it.startsWith("Plain saw Plain") }, "not for its own damage")
    }

    // allyUsedMove / enemyUsedMove: an ally joins the attack on the same target (otherTarget) at 50%.
    run {
        val (m, rec) = field(doubles(lab), team(member("plain", "tackle"), member("blaze", "tackle", ability = "assist")), team(member("leaf", ability = "spy"), member("backup")))
        val leaf = m.mon(1)
        m.use(m.mon(0), "tackle", leaf)
        Check.ok(rec.has("Leaf took 60 damage."), "the attack (${rec.texts()})")
        Check.ok(rec.index("Blaze used Tackle!") > rec.index("Leaf took 60 damage."), "the ally joins after the move")
        Check.ok(rec.has("Leaf took 20 damage."), "on the same target, at 50% power")
        Check.ok(rec.has("Leaf saw Plain use Tackle.") && rec.has("Leaf saw Blaze use Tackle."), "enemyUsedMove for both moves")
        Check.ok(rec.texts().none { it.startsWith("Backup took") }, "the joined attack did not pick another target")
    }

    // statusInflicted: only for a status given to another monster.
    run {
        val (m, rec) = field(setup, team(member("plain", "burn_it", "guard_up", ability = "inflictor")), team(member("leaf")))
        m.use(m.mon(0), "burn_it")
        Check.ok(rec.has("Plain gave Leaf Burn."), "statusInflicted (${rec.texts()})")
        m.use(m.mon(0), "guard_up")
        Check.ok(rec.texts().none { it.startsWith("Plain gave Plain") }, "not for a status on itself")
    }
}

private fun useMoveTriggerTests(lab: EngineLab) {
    val setup = lab.triggerSetup()
    fun usedCount(rec: Recorder, suffix: String) = rec.texts().count { it.endsWith(suffix) }

    // Counterattack: attacked -> useMove (first move, at the attacker).
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle")), team(member("leaf", "tackle", ability = "counter")))
        m.use(m.mon(0), "tackle")
        Check.ok(rec.has("Leaf used Tackle!") && rec.has("Plain took 40 damage."), "the counterattack hits the attacker (${rec.texts()})")
        Check.eq(40, m.mon(0).maxHp - m.mon(0).hp, "once")
    }
    // Counter loops stop: at most 3 nested moves used by useMove.
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle", ability = "counter")), team(member("leaf", "tackle", ability = "counter")))
        m.use(m.mon(0), "tackle")
        Check.eq(4, usedCount(rec, " used Tackle!"), "the attack + 3 nested counters (${rec.texts()})")
    }
    // A fainted actor does not counter; a fainted / benched actor cannot be made to use a move.
    run {
        val (m, rec) = field(setup, team(member("plain", "tackle")), team(member("weak", "tackle", ability = "counter")))
        m.use(m.mon(0), "tackle")
        Check.ok(!rec.has("Weak used Tackle!"), "no counter from a fainted monster")
        m.useMoveFromEffect(m.mon(1), m.setup.moves.require("tackle"), null, m.mon(0), 1.0, "should not appear")
        Check.ok(!rec.has("should not appear"), "useMove of a fainted actor does nothing")
    }
    // "Use this move again": the depth limit ends the loop.
    run {
        val (m, rec) = field(setup, team(member("plain", "again")), team(member("leaf")))
        m.use(m.mon(0), "again")
        Check.eq(4, usedCount(rec, "Plain used Again!"), "used, then again 3 times (${rec.texts()})")
    }
    // free: false pays PP and fails without it.
    run {
        val (m, rec) = field(setup, team(member("plain", "jab_twice", "jab")), team(member("leaf")))
        repeat(3) { m.use(m.mon(0), "jab_twice") }
        Check.eq(2, usedCount(rec, "Plain used Jab!"), "jab (2 PP) used twice through useMove (${rec.texts()})")
        Check.eq(0, m.mon(0).moves.first { it.def.id == "jab" }.pp, "its PP were spent")
    }
}

private fun extraTurnTests(lab: EngineLab) {
    // speed_order: one more action at the end of the round, at most once per round.
    run {
        val setup = lab.triggerSetup(lab.rules("turnLimit" to 2))
        val (_, rec) = battle(setup, team(member("plain", "haste")), team(member("slow", "wait")), Script { b, a -> use("haste")(b, a) }, idle)
        Check.eq(listOf(1, 2), rec.turnsOf("Plain gets another turn!"), "one extra turn per round (${rec.texts()})")
        Check.eq(listOf(1, 2), rec.turnsOf("Plain takes its extra turn!"), "taken at the end of each round")
        Check.eq(listOf(1, 2), rec.turnsOf("But it failed!"), "no second extra turn in the same round")
        val first = rec.index("Plain takes its extra turn!")
        Check.ok(first > rec.index("Slow used Wait!"), "after everybody's action")
    }
    // action_bar: it acts next, a full turn, never two extra turns in a row.
    run {
        val setup = lab.triggerSetup(lab.rules("turnSystem" to "action_bar", "turnLimit" to 8))
        val (_, rec) = battle(setup, team(member("plain", "haste")), team(member("slow", "wait")), Script { b, a -> use("haste")(b, a) }, idle)
        val turns = rec.log.filter { it.kind == LogKind.TURN }.map { it.text }
        val extra = turns.indices.filter { turns[it].endsWith(" - extra turn") }
        Check.ok(extra.isNotEmpty(), "extra turns happen ($turns)")
        Check.ok(extra.all { turns[it - 1].contains("Plain (Player 1)") && !turns[it - 1].endsWith(" - extra turn") }, "right after a normal turn of the same monster, never twice ($turns)")
        Check.ok(extra.all { it + 1 >= turns.size || !turns[it + 1].endsWith(" - extra turn") }, "not chained")
    }
}

private fun statusAndHpEffectTests(lab: EngineLab) {
    val setup = lab.triggerSetup()

    // transferStatus: steal (durations and stacks kept), copy, immunity.
    run {
        val (m, _) = field(setup, team(member("plain", "steal_buffs", "copy_buffs")), team(member("leaf")))
        val a = m.mon(0)
        val leaf = m.mon(1)
        m.applyStatus(leaf, "focus", null, stacks = 2)
        m.applyStatus(leaf, "regen", null, duration = 4)
        m.use(a, "copy_buffs")
        Check.ok(a.status("focus")?.stacks == 2 && leaf.hasStatus("focus"), "copy: both have it, stacks kept")
        a.statuses.clear()
        m.use(a, "steal_buffs")
        Check.ok(a.status("focus")?.stacks == 2 && a.status("regen")?.turnsLeft == 4, "steal: stacks and turns kept")
        Check.ok(!leaf.hasStatus("focus") && !leaf.hasStatus("regen"), "steal: the source loses them")
    }
    run {
        val (m, _) = field(setup, team(member("plain", "give_burn", "give_debuffs")), team(member("blaze"), member("leaf")))
        val a = m.mon(0)
        m.applyStatus(a, "burn", null)
        m.use(a, "give_burn", m.mon(1))
        Check.ok(a.hasStatus("burn") && !m.mon(1).hasStatus("burn"), "steal with an immune receiver: the status stays")
        m.forceSwitch(m.mon(1))
        val leaf = m.battle.sides[1].active[0]!!
        Check.eq("leaf", leaf.species.id, "the leaf came in")
        m.use(a, "give_burn", leaf)
        Check.ok(!a.hasStatus("burn") && leaf.hasStatus("burn"), "moved to a receiver that can take it")
        m.applyStatus(a, "dazed", null, duration = 3)
        m.use(a, "give_debuffs", leaf)
        Check.eq(3, leaf.status("dazed")?.turnsLeft, "its turns are kept")
    }

    // copyStages.
    run {
        val (m, rec) = field(setup, team(member("plain", "copy_cat")), team(member("leaf")))
        val a = m.mon(0)
        m.mon(1).stages["atk"] = 2
        a.stages["def"] = -1
        m.use(a, "copy_cat")
        Check.ok(a.stage("atk") == 2 && a.stage("def") == 0, "a copy of the target's stages")
        Check.ok(rec.has("Plain copied Leaf's stat changes!"), "logged")
    }

    // setHp: damage without the shield, healing, fainting.
    run {
        val (m, rec) = field(setup, team(member("plain", "set_half", "set_zero")), team(member("leaf")))
        val leaf = m.mon(1)
        leaf.shield = 30
        m.use(m.mon(0), "set_half")
        Check.ok(leaf.hp == 100 && leaf.shield == 30 && rec.has("Leaf took 100 damage."), "lowered to half, the shield absorbs nothing")
        leaf.hp = 50
        m.use(m.mon(0), "set_half")
        Check.ok(leaf.hp == 100 && rec.has("Leaf restored 50 HP."), "raised to half: healing")
        m.use(m.mon(0), "set_zero")
        Check.ok(leaf.isFainted && rec.has("Leaf fainted!"), "0 HP faints")
    }

    // balanceHp and swapHp.
    run {
        val (m, rec) = field(doubles(lab), team(member("plain", "share"), member("backup")), team(member("leaf"), member("puddle")))
        val a = m.mon(0)
        val b = m.mon(0, 1)
        a.hp = 100
        m.use(a, "share")
        Check.ok(a.hp == 150 && b.hp == 37, "average 75%: ${a.hp} / ${b.hp}")
        Check.ok(rec.has("Plain and Backup shared their HP!"), "logged")
    }
    run {
        val (m, rec) = field(setup, team(member("plain", "pain_swap")), team(member("leaf")))
        m.mon(0).hp = 50
        m.use(m.mon(0), "pain_swap")
        Check.ok(m.mon(0).hp == 200 && m.mon(1).hp == 50, "HP percentages swapped (${rec.texts()})")
    }
}

private fun switchAndDelayTests(lab: EngineLab) {
    val setup = lab.triggerSetup()

    // forceSwitch: random benched teammate; fails without a bench.
    run {
        val (m, rec) = field(setup, team(member("plain", "roar")), team(member("leaf"), member("puddle")))
        m.use(m.mon(0), "roar")
        Check.ok(rec.has("Leaf was dragged out!") && rec.has("Player 2 sends out Puddle!"), "dragged out (${rec.texts()})")
        Check.ok(m.mon(1).slot < 0 && m.mon(1, 1).slot == 0, "replaced")
        m.use(m.mon(0), "roar")
        Check.eq(listOf(1, 0), listOf(m.mon(1).slot, m.mon(1, 1).slot).map { if (it < 0) 0 else 1 }, "the leaf came back")
        val (m2, rec2) = field(setup, team(member("plain", "roar")), team(member("leaf")))
        m2.use(m2.mon(0), "roar")
        Check.ok(rec2.has("But it failed!") && m2.mon(1).slot == 0, "no bench: it fails")
    }

    // switchOut (U-turn): the user leaves after its move, its controller picks who comes in.
    run {
        val (m, rec) = field(setup, team(member("plain", "u_turn"), member("backup")), team(member("leaf")))
        m.use(m.mon(0), "u_turn")
        Check.ok(rec.index("Leaf took 10 damage.") < rec.index("Player 1 withdraws Plain!"), "after the hit (${rec.texts()})")
        Check.ok(rec.has("Player 1 sends out Backup!") && m.mon(0, 1).slot == 0, "the replacement came in")
        val (m2, rec2) = field(setup, team(member("plain", "u_turn")), team(member("leaf")))
        m2.use(m2.mon(0), "u_turn")
        Check.ok(!rec2.has("Player 1 withdraws Plain!") && m2.mon(0).slot == 0, "no bench: it stays")
    }

    // delay (speed_order): Future Sight at the end of the 2nd round, Wish at the end of this one.
    run {
        val s = lab.triggerSetup(lab.rules("turnLimit" to 3))
        val (_, rec) = battle(s, team(member("plain", "future", "wait")), team(member("leaf", "wait")), Script(choose = useThen("future", 1, "wait")), idle)
        Check.eq(listOf(2), rec.turnsOf("Leaf is struck by the future!"), "two rounds, counting the current one (${rec.texts()})")
        Check.eq(listOf(2), rec.turnsOf("Leaf took 50 damage."), "the delayed attack")
    }
    run {
        val s = lab.triggerSetup(lab.rules("turnLimit" to 2))
        val wished = Script(choose = useThen("wish", 1, "wait"))
        val manager = Manager(s, listOf(team(member("plain", "wish", "wait")), team(member("leaf", "wait"))), listOf(wished, idle), Recorder(), 1)
        manager.mon(0).hp = 100
        val rec = manager.observer as Recorder
        manager.run()
        Check.eq(listOf(1), rec.turnsOf("Plain restored 50 HP."), "wish: at the end of the current round")
    }
    run {
        val s = lab.triggerSetup(lab.rules("turnLimit" to 2))
        val (_, rec) = battle(s, team(member("plain", "chain_delay", "wait")), team(member("leaf", "wait")), Script(choose = useThen("chain_delay", 1, "wait")), idle)
        Check.eq(listOf(1), rec.turnsOf("tick"), "the first delay")
        Check.eq(listOf(2), rec.turnsOf("tock"), "a delay scheduled by a delay waits for the next round")
    }
    // The attached monster faints first: nothing happens.
    run {
        val s = lab.triggerSetup(lab.rules("turnLimit" to 3))
        val (_, rec) = battle(s, team(member("plain", "future", "tackle")), team(member("weak", "wait"), member("puddle", "wait")),
            Script(choose = useThen("future", 1, "tackle")), idle)
        Check.ok(rec.has("Weak fainted!"), "the target fainted (${rec.texts()})")
        Check.ok(!rec.has("Weak is struck by the future!") && !rec.has("Puddle is struck by the future!"), "the delay was dropped")
    }
    // action_bar: counted in the attached monster's turns.
    run {
        val s = lab.triggerSetup(lab.rules("turnSystem" to "action_bar", "turnLimit" to 30))
        val (_, rec) = battle(s, team(member("plain", "future", "wait")), team(member("slow", "wait")), Script(choose = useThen("future", 1, "wait")), idle)
        val struck = rec.index("Slow is struck by the future!")
        Check.ok(struck > 0, "struck (${rec.texts()})")
        val lastTurn = rec.log.subList(0, struck).last { it.kind == LogKind.TURN }.text
        Check.ok(lastTurn.contains("Slow (Player 2)"), "at the end of a turn of the attached monster ($lastTurn)")
        val used = rec.index("Plain used Future!")
        Check.eq(2, rec.log.subList(used, struck).count { it.kind == LogKind.TURN && it.text.contains("Slow (Player 2)") }, "its 2nd turn from then")
    }
}

private fun attackAndMoveKeyTests(lab: EngineLab) {
    val setup = lab.triggerSetup()

    // attack: an extra strike that does not fire hit (no loop), with type immunity.
    run {
        val (m, rec) = field(setup, team(member("plain", "strike_plus")), team(member("leaf")))
        m.use(m.mon(0), "strike_plus")
        Check.ok(rec.has("Leaf took 10 damage.") && rec.has("Leaf took 30 damage."), "move hit + extra strike (${rec.texts()})")
        Check.eq(1, rec.count("Struck!"), "the strike does not fire hit")
        val (m2, rec2) = field(setup, team(member("plain", "ghost_strike")), team(member("spook")))
        m2.use(m2.mon(0), "ghost_strike")
        Check.ok(rec2.has("Spook took 10 damage.") && rec2.has("It doesn't affect Spook..."), "a normal strike does not affect a ghost")
    }

    // ignoreDefense: 50% halves the defense; 100% makes it 1 (no division by zero).
    run {
        val (m, rec) = field(setup, team(member("plain", "half_pierce", "pierce")), team(member("leaf")))
        m.use(m.mon(0), "half_pierce")
        Check.ok(rec.has("Leaf took 80 damage."), "ignoreDefense 50 (${rec.texts()})")
        m.use(m.mon(0), "pierce")
        Check.ok(m.mon(1).isFainted, "ignoreDefense 100: the defense counts as 1")
    }

    // hitAccuracy: "once" hits all or nothing, "each" stops at the first miss.
    run {
        val onceCounts = mutableSetOf<Int>()
        val eachCounts = mutableSetOf<Int>()
        for (seed in 1L..40L) {
            for ((move, counts) in listOf("once3" to onceCounts, "each3" to eachCounts)) {
                val (m, rec) = field(setup, team(member("plain", move)), team(member("leaf")), seed = seed)
                m.use(m.mon(0), move)
                val texts = rec.texts()
                counts += texts.count { it == "Leaf took 10 damage." }
                val miss = texts.indexOf("Leaf avoided the attack!")
                if (miss >= 0) Check.ok(texts.drop(miss).none { it == "Leaf took 10 damage." }, "$move: no hit after a miss ($texts)")
            }
        }
        Check.ok(onceCounts.all { it == 0 || it == 3 }, "once: all or nothing ($onceCounts)")
        Check.ok(eachCounts.any { it == 1 || it == 2 }, "each: a miss can end the move part-way ($eachCounts)")
    }
}

private fun chargeTests(lab: EngineLab) {
    val setup = lab.triggerSetup()

    // Round loop: charge, out of reach (even for moves that never miss), then a locked release.
    run {
        val s = lab.triggerSetup(lab.rules("turnLimit" to 2))
        var asked = 0
        val charger = Script { b, a -> asked++; use("beam")(b, a) }
        val (m, rec) = battle(s, team(member("swift", "beam")), team(member("slow", "tackle")), charger, Script { b, a -> use("tackle")(b, a) })
        Check.eq(listOf(1), rec.turnsOf("Swift is charging up!"), "charging in round 1 (${rec.texts()})")
        Check.eq(listOf(1), rec.turnsOf("Swift can't be reached!"), "out of reach while charging (tackle never misses)")
        Check.eq(listOf(2), rec.turnsOf("Swift used Beam!"), "released in round 2")
        Check.ok(rec.has("Slow took 80 damage."), "the release hits")
        Check.eq(1, asked, "the controller is not asked while locked")
        Check.eq(4, m.mon(0).moves.first().pp, "PP paid once, when charging")
        Check.ok(!m.mon(0).isCharging, "the charge is over")
    }
    // Field operations: m.charging, stun, switch, faint, effects still reach it.
    run {
        val (m, rec) = field(setup, team(member("plain", "beam"), member("backup")), team(member("leaf", "tackle")))
        val a = m.mon(0)
        m.use(a, "beam")
        Check.ok(a.isCharging && a.isInvulnerable, "charging")
        Check.near(1.0, m.eval("self.charging", a), "m.charging")
        m.dealDamage(a, 5, m.mon(1), null)
        Check.eq(195, a.hp, "effects still reach a charging monster")
        m.applyStatus(a, "doze", null)
        m.use(a, "beam")
        Check.ok(rec.has("Plain dozes.") && !a.isCharging, "a cancelled release ends the charge")
        m.removeStatus(a, a.status("doze")!!)
        m.use(a, "beam")
        Check.ok(a.isCharging, "charging again")
        m.forceSwitch(a)
        Check.ok(!a.isCharging && a.slot < 0, "switching out ends the charge")
    }
    run {
        val (m, _) = field(setup, team(member("plain", "beam")), team(member("leaf", "tackle")))
        val a = m.mon(0)
        m.use(a, "beam")
        m.dealDamage(a, 1000, null, null)
        Check.ok(a.isFainted && !a.isCharging, "fainting ends the charge")
    }
    // skipIf and several charging turns.
    run {
        val (m, rec) = field(setup, team(member("plain", "solar")), team(member("leaf")))
        val a = m.mon(0)
        m.use(a, "solar")
        m.use(a, "solar")
        Check.eq(2, rec.count("Plain soaks up light!"), "two charging turns")
        Check.ok(a.isCharging && !a.isInvulnerable, "still charging, reachable")
        m.use(a, "solar")
        Check.ok(rec.has("Plain used Solar!") && !a.isCharging, "released on the third")
        m.setField("sun")
        m.use(a, "solar")
        Check.ok(rec.count("Plain used Solar!") == 2 && !a.isCharging, "skipIf: used at once")
    }
    // action_bar: locked turns are not chosen; the battle screen shows the charge.
    run {
        val s = lab.triggerSetup(lab.rules("turnSystem" to "action_bar", "turnLimit" to 6))
        var asked = 0
        val (_, rec) = battle(s, team(member("plain", "beam")), team(member("slow", "wait")), Script { b, a -> asked++; use("beam")(b, a) }, idle)
        val plainTurns = rec.log.count { it.kind == LogKind.TURN && it.text.contains("Plain (Player 1)") }
        Check.ok(asked in 1 until plainTurns, "action_bar: the locked turns are not chosen ($asked of $plainTurns)")
        Check.ok(rec.has("Plain used Beam!"), "released")
    }
    run {
        val (m, _) = field(setup, team(member("plain", "beam")), team(member("leaf")))
        m.use(m.mon(0), "beam")
        val screen = ui.FieldView.render(m.battle)
        Check.ok(screen.contains("Charging Beam (out of reach)"), "the battle screen shows the charge:\n$screen")
    }
}
