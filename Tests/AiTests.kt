import bakemon.Bakemon
import bakemon.MoveDef
import bakemon.Setup
import engine.Action
import engine.AiController
import engine.AiLevel
import engine.Battle
import engine.BattleObserver
import engine.BattleResult
import engine.Battler
import engine.ChargeState
import engine.Controller
import engine.DamageCalculator
import engine.FieldInstance
import engine.LogEntry
import engine.Manager
import engine.Side
import engine.StatusInstance
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** Tests for engine.AiController. [root] is the project folder. */
fun runAiTests(root: File) {
    Check.section("ai")
    val tmp = Files.createTempDirectory("bakemon-ai-tests").toFile()
    try {
        val classic = aiLoadSetup(File(tmp, "AiClassic"), AI_CLASSIC_FILES)
        val actionBar = aiLoadSetup(File(tmp, "AiActionBar"), AI_ACTION_BAR_FILES)
        if (classic != null) {
            Check.guard("ai: speed_order choices") { aiClassicChoiceTests(classic) }
            Check.guard("ai: fields") { aiFieldTests(classic) }
            Check.guard("ai: speed_order random states") { aiFuzzTests(classic, 150, 11L) }
            Check.guard("ai: speed_order battles") { aiBattleTests(classic) }
        }
        if (actionBar != null) {
            Check.guard("ai: action_bar choices") { aiActionBarChoiceTests(actionBar) }
            Check.guard("ai: action_bar random states") { aiFuzzTests(actionBar, 150, 23L) }
            Check.guard("ai: action_bar battles") { aiBattleTests(actionBar) }
        }
        Check.guard("ai: triggers") { runAiTriggerTests(tmp) }
    } finally {
        tmp.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Crafted situations

private fun aiClassicChoiceTests(setup: Setup) {
    Check.section("ai: speed_order choices")
    val normal = AiController("N", AiLevel.NORMAL)
    val hard = AiController("H", AiLevel.HARD)
    val easy = AiController("E", AiLevel.EASY)

    // A super-effective finishing blow beats a weak neutral move.
    run {
        val battle = aiCraft(setup, listOf(aiMon("leafling", "tackle", "vine_whip")), listOf(aiMon("puddlepup", "water_gun", "tackle")))
        val me = battle.sides[0].team[0]
        val foe = battle.sides[1].team[0]
        val calc = DamageCalculator(setup)
        val vine = calc.calculate(battle, me, foe, me.moveDef("vine_whip"), 1, estimate = true).damage
        val tackle = calc.calculate(battle, me, foe, me.moveDef("tackle"), 1, estimate = true).damage
        Check.ok(vine > tackle + 1, "vine whip ($vine) should out-damage tackle ($tackle) against water")
        for (ai in listOf(normal, hard)) {
            Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("vine_whip", foe) }, "${ai.level.id}: uses the super-effective move at full HP")
        }
        foe.hp = (tackle + 1).coerceIn(1, maxOf(1, vine))
        for (ai in listOf(normal, hard)) {
            Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("vine_whip", foe) }, "${ai.level.id}: picks the super-effective KO move")
        }
    }

    // Heals when low, never at full HP.
    run {
        val battle = aiCraft(setup, listOf(aiMon("puddlepup", "tackle", "recover")), listOf(aiMon("boulderbun", "tackle")))
        val me = battle.sides[0].team[0]
        me.hp = maxOf(1, me.maxHp / 5)
        for (ai in listOf(normal, hard)) {
            Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("recover") }, "${ai.level.id}: heals at 20% HP")
        }
        me.hp = me.maxHp
        for (ai in listOf(normal, hard)) {
            Check.ok(aiPicks(ai, battle, me, 20).none { it.isMove("recover") }, "${ai.level.id}: does not heal at full HP")
        }
    }

    // Status moves: used on a healthy, unstatused target; never on a target that has it, another major status, or is immune.
    run {
        val battle = aiCraft(setup, listOf(aiMon("wispurr", "will_o_wisp", "quick_attack")), listOf(aiMon("boulderbun", "tackle", "double_edge")))
        val me = battle.sides[0].team[0]
        val foe = battle.sides[1].team[0]
        Check.ok(aiPicks(normal, battle, me, 20).any { it.isMove("will_o_wisp", foe) }, "normal: burns a physical attacker when its own attack is weak")
        foe.statuses += StatusInstance(setup.statuses.require("burn"), null, 1, null, false)
        for (ai in listOf(normal, hard)) {
            Check.ok(aiPicks(ai, battle, me, 20).none { it.isMove("will_o_wisp") }, "${ai.level.id}: does not burn a burned target")
        }
        foe.statuses.clear()
        foe.statuses += StatusInstance(setup.statuses.require("poison"), null, 1, null, false)
        for (ai in listOf(normal, hard)) {
            Check.ok(aiPicks(ai, battle, me, 20).none { it.isMove("will_o_wisp") }, "${ai.level.id}: does not burn a poisoned target")
        }
        val fire = aiCraft(setup, listOf(aiMon("wispurr", "will_o_wisp", "quick_attack")), listOf(aiMon("embercub", "tackle")))
        for (ai in listOf(normal, hard)) {
            Check.ok(aiPicks(ai, fire, fire.sides[0].team[0], 20).none { it.isMove("will_o_wisp") }, "${ai.level.id}: does not burn an immune target")
        }
    }

    // Type immunity only stops damaging moves: a Normal status move still works on a Ghost, an immune Tackle does not.
    run {
        val battle = aiCraft(setup, listOf(aiMon("boulderbun", "growl", "tackle")), listOf(aiMon("wispurr", "shadow_ball", "quick_attack")))
        val me = battle.sides[0].team[0]
        for (ai in listOf(normal, hard)) {
            Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("growl") }, "${ai.level.id}: uses the status move the immune type does not stop")
        }
    }

    // Stat boosts are useless at the cap.
    run {
        val battle = aiCraft(setup, listOf(aiMon("boulderbun", "tackle", "swords_dance")), listOf(aiMon("leafling", "tackle")))
        val me = battle.sides[0].team[0]
        me.stages["atk"] = setup.rules.stageMax
        for (ai in listOf(normal, hard)) {
            Check.ok(aiPicks(ai, battle, me, 20).none { it.isMove("swords_dance") }, "${ai.level.id}: no stat boost at the cap")
        }
    }

    // Out of PP: the fallback move, with a legal target.
    run {
        val battle = aiCraft(setup, listOf(aiMon("leafling", "tackle", "vine_whip")), listOf(aiMon("puddlepup", "tackle")))
        val me = battle.sides[0].team[0]
        val foe = battle.sides[1].team[0]
        me.moves.forEach { it.pp = 0 }
        for (ai in listOf(easy, normal, hard)) {
            val action = ai.chooseAction(battle, me)
            Check.ok(action is Action.UseMove && action.slot == null && action.isMove("desperate_strike", foe), "${ai.level.id}: uses the fallback move without PP (got $action)")
        }
    }

    // HARD switches out of a terrible matchup; NORMAL and EASY never switch.
    run {
        val battle = aiCraft(
            setup,
            listOf(aiMon("leafling", "vine_whip", "tackle"), aiMon("puddlepup", "water_gun", "tackle")),
            listOf(aiMon("embercub", "ember", "flamethrower")),
        )
        val me = battle.sides[0].team[0]
        val puddle = battle.sides[0].team[1]
        val action = AiController("H", AiLevel.HARD).chooseAction(battle, me)
        Check.ok(action is Action.Switch && action.replacement === puddle, "hard: switches the grass monster out for the water one (got $action)")
        Check.ok(aiPicks(normal, battle, me, 10).all { it is Action.UseMove }, "normal: never switches")
        Check.ok(aiPicks(easy, battle, me, 20).all { it is Action.UseMove }, "easy: never switches")

        val fine = aiCraft(
            setup,
            listOf(aiMon("leafling", "vine_whip", "tackle"), aiMon("puddlepup", "water_gun", "tackle")),
            listOf(aiMon("puddlepup", "water_gun", "tackle")),
        )
        Check.ok(aiPicks(AiController("H", AiLevel.HARD), fine, fine.sides[0].team[0], 10).all { it is Action.UseMove },
            "hard: stays in when the matchup is good")

        // A nearly fainted monster is not worth saving: it attacks instead.
        me.hp = maxOf(1, me.maxHp / 5)
        Check.ok(AiController("H", AiLevel.HARD).chooseAction(battle, me) is Action.UseMove, "hard: does not switch out at 20% HP")

        // Walled (normal moves against a ghost): switch to a teammate that can actually hit it.
        val walled = aiCraft(
            setup,
            listOf(aiMon("boulderbun", "tackle", "double_edge"), aiMon("embercub", "ember", "tackle")),
            listOf(aiMon("wispurr", "shadow_ball")),
        )
        val ember = walled.sides[0].team[1]
        val escape = AiController("H", AiLevel.HARD).chooseAction(walled, walled.sides[0].team[0])
        Check.ok(escape is Action.Switch && escape.replacement === ember, "hard: switches out when it cannot hurt the enemy (got $escape)")

        // A monster that was just switched in acts at least once before leaving again (no endless switching).
        val flow = aiCraft(
            setup,
            listOf(aiMon("leafling", "vine_whip", "tackle"), aiMon("puddlepup", "water_gun", "tackle")),
            listOf(aiMon("embercub", "ember", "flamethrower"), aiMon("leafling", "vine_whip")),
        )
        val hardAi = AiController("H", AiLevel.HARD)
        val (leaf, pup) = flow.sides[0].team
        val (foeEmber, foeLeaf) = flow.sides[1].team
        val out = hardAi.chooseAction(flow, leaf)
        Check.ok(out is Action.Switch && out.replacement === pup, "hard: leaves the fire matchup (got $out)")
        fun swap(side: Side, outgoing: Battler, incoming: Battler) {
            outgoing.slot = -1
            side.active[0] = incoming
            incoming.slot = 0
        }
        swap(flow.sides[0], leaf, pup)
        swap(flow.sides[1], foeEmber, foeLeaf)
        Check.ok(hardAi.chooseAction(flow, pup) is Action.UseMove, "hard: the newcomer acts once even in a bad matchup")
        val later = hardAi.chooseAction(flow, pup)
        Check.ok(later is Action.Switch && later.replacement === leaf, "hard: then it may switch again (got $later)")
    }

    // Replacements: always one of the options; NORMAL/HARD pick the best matchup, ties go to the healthiest.
    run {
        val battle = aiCraft(
            setup,
            listOf(aiMon("wispurr", "shadow_ball"), aiMon("leafling", "vine_whip", "tackle"), aiMon("puddlepup", "water_gun", "tackle")),
            listOf(aiMon("embercub", "ember", "flamethrower")),
        )
        val side = battle.sides[0]
        side.team[0].hp = 0
        val options = side.bench()
        Check.eq(2, options.size, "two benched options")
        val puddle = side.team[2]
        for (ai in listOf(normal, hard)) {
            Check.ok(ai.chooseReplacement(battle, side, 0, options) === puddle, "${ai.level.id}: sends in the monster with the best matchup")
        }
        repeat(20) {
            val pick = easy.chooseReplacement(battle, side, 0, options)
            Check.ok(options.any { it === pick }, "easy: replacement is one of the options")
        }

        val twins = aiCraft(
            setup,
            listOf(aiMon("wispurr", "shadow_ball"), aiMon("puddlepup", "water_gun"), aiMon("puddlepup", "water_gun")),
            listOf(aiMon("embercub", "ember")),
        )
        val twinSide = twins.sides[0]
        twinSide.team[0].hp = 0
        twinSide.team[1].hp = twinSide.team[1].maxHp / 2
        Check.ok(normal.chooseReplacement(twins, twinSide, 0, twinSide.bench()) === twinSide.team[2], "normal: tie goes to the healthiest option")
    }
}

private fun aiActionBarChoiceTests(setup: Setup) {
    Check.section("ai: action_bar choices")
    val levels = listOf(AiController("N", AiLevel.NORMAL), AiController("H", AiLevel.HARD))
    val battle = aiCraft(
        setup,
        listOf(aiMon("lumina", "radiant_beam", ability = "vigor", level = 30), aiMon("mossling", "mend", "root_slash", ability = "regen", level = 30)),
        listOf(aiMon("umbrat", "shadow_claw", ability = "spite", level = 30), aiMon("aquaturtle", "tide_slash", ability = "regen", level = 30)),
    )
    val lumina = battle.sides[0].team[0]
    val mossling = battle.sides[0].team[1]
    val umbrat = battle.sides[1].team[0]
    val turtle = battle.sides[1].team[1]

    // Multi-active targeting: the enemy weak to the move at full HP, the one it can finish otherwise.
    for (ai in levels) {
        Check.ok(aiPicks(ai, battle, lumina, 20).all { it.isMove("radiant_beam", umbrat) }, "${ai.level.id}: hits the enemy weak to light")
    }
    turtle.hp = 1
    for (ai in levels) {
        Check.ok(aiPicks(ai, battle, lumina, 20).all { it.isMove("radiant_beam", turtle) }, "${ai.level.id}: finishes the enemy it can KO")
    }
    turtle.hp = turtle.maxHp

    // allyOrSelf healing goes to the wounded ally, not to the healthy user.
    lumina.hp = maxOf(1, lumina.maxHp * 15 / 100)
    for (ai in levels) {
        Check.ok(aiPicks(ai, battle, mossling, 20).all { it.isMove("mend", lumina) }, "${ai.level.id}: heals the wounded ally")
    }
    lumina.hp = lumina.maxHp
    for (ai in levels) {
        Check.ok(aiPicks(ai, battle, mossling, 20).none { it.isMove("mend") }, "${ai.level.id}: no healing when everybody is healthy")
    }

    // An ally-only move with no ally on the field is never chosen.
    val alone = aiCraft(
        setup,
        listOf(aiMon("lumina", "radiant_beam", "inspire", ability = "vigor", level = 30), aiMon("mossling", "root_slash", ability = "regen", level = 30)),
        listOf(aiMon("umbrat", "shadow_claw", ability = "spite", level = 30), aiMon("aquaturtle", "tide_slash", ability = "regen", level = 30)),
    )
    alone.sides[0].team[1].hp = 0
    for (ai in levels + AiController("E", AiLevel.EASY)) {
        Check.ok(aiPicks(ai, alone, alone.sides[0].team[0], 20).none { it.isMove("inspire") }, "${ai.level.id}: no ally move without an ally")
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Random states: every answer is legal and the battle is never modified

private fun aiFuzzTests(setup: Setup, count: Int, seed: Long) {
    Check.section("ai: ${setup.rules.turnSystem.id} random states")
    val rnd = Random(seed)
    val problems = mutableListOf<String>()
    var decisions = 0
    repeat(count) { round ->
        val size = setup.rules.teamMax.coerceAtMost(3).coerceAtLeast(setup.rules.teamMin)
        val battle = aiCraft(
            setup,
            setup.randomTeam(rnd, size).members,
            setup.randomTeam(rnd, size).members,
            seed = seed * 1000 + round,
        )
        aiRandomize(battle, setup, rnd)
        for (level in AiLevel.entries) {
            val ai = AiController(level.id, level)
            for (side in battle.sides) {
                for (actor in side.activeBattlers()) {
                    val before = aiSnapshot(battle)
                    val action = ai.chooseAction(battle, actor)
                    decisions++
                    if (aiSnapshot(battle) != before) problems += "${level.id}: chooseAction modified the battle"
                    aiIllegal(battle, actor, action)?.let { problems += "${level.id} ${actor.name}: $it" }
                }
                val bench = side.bench()
                if (bench.isNotEmpty()) {
                    val before = aiSnapshot(battle)
                    val pick = ai.chooseReplacement(battle, side, 0, bench)
                    if (bench.none { it === pick }) problems += "${level.id}: replacement ${pick.name} is not an option"
                    if (aiSnapshot(battle) != before) problems += "${level.id}: chooseReplacement modified the battle"
                }
            }
        }
    }
    Check.ok(decisions > 0, "the AI was asked something")
    Check.ok(problems.isEmpty(), "${problems.size} problems in $decisions decisions: ${problems.take(5)}")
}

/** Random HP, PP, cooldowns, stages, statuses, shields and gauges; active monsters stay alive (except a second slot). */
private fun aiRandomize(battle: Battle, setup: Setup, rnd: Random) {
    val statuses = setup.statuses.all()
    val statIds = setup.rules.stats.map { it.id }.filter { it != setup.rules.hpStat }
    for (side in battle.sides) {
        for (b in side.team) {
            val keepAlive = b.slot == 0
            b.hp = if (!keepAlive && rnd.nextDouble() < 0.2) 0 else rnd.nextInt(1, b.maxHp + 1)
            if (rnd.nextDouble() < 0.3) b.shield = rnd.nextInt(0, b.maxHp / 3 + 1)
            b.gauge = rnd.nextDouble() * setup.rules.actionBarSize
            for (slot in b.moves) {
                if (slot.def.pp > 0) slot.pp = if (rnd.nextDouble() < 0.4) 0 else rnd.nextInt(0, slot.def.pp + 1)
                slot.cooldown = if (rnd.nextDouble() < 0.4) rnd.nextInt(0, 3) else 0
            }
            if (statIds.isNotEmpty() && rnd.nextDouble() < 0.5) {
                b.stages[statIds[rnd.nextInt(statIds.size)]] = rnd.nextInt(setup.rules.stageMin, setup.rules.stageMax + 1)
            }
            if (statuses.isNotEmpty() && rnd.nextDouble() < 0.5) {
                val def = statuses[rnd.nextInt(statuses.size)]
                val turns = if (rnd.nextBoolean()) null else rnd.nextInt(1, 4)
                b.statuses += StatusInstance(def, turns, rnd.nextInt(1, def.maxStacks + 1), null, rnd.nextBoolean())
            }
        }
    }
    battle.turn = rnd.nextInt(1, 30)
}

// ---------------------------------------------------------------------------------------------------------------
// Full AI-vs-AI battles

private fun aiBattleTests(setup: Setup) {
    val system = setup.rules.turnSystem.id
    Check.section("ai: $system battles")
    val size = setup.rules.teamMax.coerceAtMost(3).coerceAtLeast(setup.rules.teamMin)
    for (level in AiLevel.entries) {
        val problems = mutableListOf<String>()
        val first = AiCheckedController(AiController("A", level), problems)
        val second = AiCheckedController(AiController("B", level), problems)
        var crashes = 0
        var firstCrash = ""
        var unfinished = 0
        repeat(50) { i ->
            val seed = 5000L + 100L * level.ordinal + i
            val rnd = Random(seed)
            val teams = listOf(setup.randomTeam(rnd, size), setup.randomTeam(rnd, size))
            try {
                val manager = Manager(setup, teams, listOf(first, second), seed = seed)
                val result = manager.run()
                if (!manager.battle.ended) unfinished++
                if (setup.rules.turnLimit > 0 && result.turns > setup.rules.turnLimit) unfinished++
            } catch (t: Throwable) {
                if (crashes++ == 0) firstCrash = "$t at ${t.stackTrace.take(4).joinToString(" < ")}"
            }
        }
        Check.eq(0, crashes, "${level.id}: battles that crashed (first: $firstCrash)")
        Check.eq(0, unfinished, "${level.id}: battles that did not end properly")
        Check.ok(first.decisions + second.decisions > 0, "${level.id}: the AI made decisions")
        Check.ok(problems.isEmpty(), "${level.id}: ${problems.size} illegal answers: ${problems.take(5)}")
    }

    // NORMAL should beat EASY more often than not (sides alternate so the teams are fair).
    var normalWins = 0
    var easyWins = 0
    repeat(30) { i ->
        val seed = 9000L + i
        val rnd = Random(seed)
        val teams = listOf(setup.randomTeam(rnd, size), setup.randomTeam(rnd, size))
        val normalSide = i % 2
        val controllers = if (normalSide == 0) listOf(AiController("N", AiLevel.NORMAL), AiController("E", AiLevel.EASY))
        else listOf(AiController("E", AiLevel.EASY), AiController("N", AiLevel.NORMAL))
        val result = Manager(setup, teams, controllers, seed = seed).run()
        when (result.winner) {
            normalSide -> normalWins++
            null -> {}
            else -> easyWins++
        }
    }
    Check.ok(normalWins > easyWins, "normal beats easy more often ($normalWins vs $easyWins)")

    // Seeded battles are reproducible (the AI only uses battle.random).
    fun replay(seed: Long): Pair<BattleResult, List<String>> {
        val log = mutableListOf<String>()
        val observer = object : BattleObserver {
            override fun onLog(entry: LogEntry) {
                log += entry.text
            }
        }
        val rnd = Random(seed)
        val teams = listOf(setup.randomTeam(rnd, size), setup.randomTeam(rnd, size))
        val controllers = listOf(AiController("H", AiLevel.HARD), AiController("N", AiLevel.NORMAL))
        return Manager(setup, teams, controllers, observer, seed).run() to log
    }
    val a = replay(4242)
    val b = replay(4242)
    Check.eq(a.first, b.first, "same seed, same result")
    Check.ok(a.second == b.second, "same seed, same battle log")
}

/** Wraps an AI and records illegal answers or state changes instead of letting the manager silently fix them. */
private class AiCheckedController(val ai: AiController, val problems: MutableList<String>) : Controller {
    var decisions = 0
    override val name: String get() = ai.name

    override fun chooseAction(battle: Battle, actor: Battler): Action {
        val before = aiSnapshot(battle)
        val action = ai.chooseAction(battle, actor)
        decisions++
        if (aiSnapshot(battle) != before) problems += "turn ${battle.turn}: chooseAction modified the battle"
        aiIllegal(battle, actor, action)?.let { problems += "turn ${battle.turn} ${actor.name}: $it" }
        return action
    }

    override fun chooseReplacement(battle: Battle, side: Side, slot: Int, options: List<Battler>): Battler {
        val pick = ai.chooseReplacement(battle, side, slot, options)
        if (options.none { it === pick }) problems += "turn ${battle.turn}: replacement ${pick.name} is not an option"
        return pick
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Helpers

/** Why [action] is not legal for [actor] right now, or null if it is. */
private fun aiIllegal(battle: Battle, actor: Battler, action: Action): String? {
    val usable = battle.usableMoves(actor)
    fun hasTarget(move: MoveDef) = !move.target.needsChoice || battle.targetOptions(actor, move).isNotEmpty()
    val canMove = usable.any { hasTarget(it.def) }
    return when (action) {
        is Action.Pass -> {
            val fallback = battle.fallbackMove()
            if (canMove || (fallback != null && hasTarget(fallback))) "passed although it could act" else null
        }
        is Action.Switch -> when {
            !battle.canSwitch(actor) -> "switched although switching is not possible"
            battle.switchOptions(actor).none { it === action.replacement } -> "switched to ${action.replacement}, not an option"
            else -> null
        }
        is Action.UseMove -> {
            val slot = action.slot
            when {
                slot != null && usable.none { it === slot } -> "used ${action.move.id}, which is not usable"
                slot != null && slot.def !== action.move -> "move ${action.move.id} does not match its slot ${slot.def.id}"
                slot == null && action.move.id != battle.fallbackMove()?.id -> "slotless move ${action.move.id} is not the fallback"
                slot == null && canMove -> "used the fallback move although it had usable moves"
                action.move.target.needsChoice && battle.targetOptions(actor, action.move).none { it === action.target } ->
                    "illegal target ${action.target} for ${action.move.id}"
                else -> null
            }
        }
    }
}

/** Everything the AI could accidentally change. */
private fun aiSnapshot(battle: Battle): String = buildString {
    append(battle.turn).append('|').append(battle.ended).append('|').append(battle.currentActor?.name)
    for (side in battle.sides) {
        append('#').append(side.active.joinToString(",") { it?.name ?: "-" })
        for (b in side.team) {
            append(';').append(b.name).append(':').append(b.hp).append('/').append(b.shield).append('/').append(b.gauge)
            append('/').append(b.slot).append('/').append(b.types).append('/').append(b.ability?.id).append('/').append(b.item?.id)
            append('/').append(b.stages.toSortedMap())
            append('/').append(b.statuses.joinToString(",") { "${it.def.id}:${it.stacks}:${it.turnsLeft}:${it.fresh}" })
            append('/').append(b.moves.joinToString(",") { "${it.def.id}:${it.pp}:${it.cooldown}" })
            append('/').append(b.triggerUses.values.sum())
        }
    }
}

private fun aiFieldTests(setup: Setup) {
    Check.section("ai: fields")
    val normal = AiController("N", AiLevel.NORMAL)
    val hard = AiController("H", AiLevel.HARD)
    val levels = listOf(normal, hard)
    fun Battle.add(id: String, side: Side? = null) {
        activeFields += FieldInstance(setup.fields.require(id), side, null, 1, null)
    }

    // A move blocked at choice time is not usable, so the AI never picks it.
    run {
        val battle = aiCraft(setup, listOf(aiMon("embercub", "flamethrower", "ember")), listOf(aiMon("leafling", "tackle")))
        val me = battle.sides[0].team[0]
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("flamethrower") }, "${ai.level.id}: flamethrower without the field")
        battle.add("gravity")
        Check.eq(listOf("ember"), battle.usableMoves(me).map { it.def.id }, "usableMoves excludes the blocked move")
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("ember") }, "${ai.level.id}: never the blocked move")
    }

    // A rule that depends on the target is false when choosing but stops the move when it runs: the AI checks the
    // option's target too and avoids it, unless nothing else is legal.
    run {
        val battle = aiCraft(setup, listOf(aiMon("boulderbun", "quick_attack", "tackle")), listOf(aiMon("leafling", "tackle")))
        val me = battle.sides[0].team[0]
        val foe = battle.sides[1].team[0]
        battle.add("psy")
        Check.eq(listOf("quick_attack", "tackle"), battle.usableMoves(me).map { it.def.id }, "psy: not blocked when choosing")
        Check.ok(battle.isMoveBlocked(me, me.moveDef("quick_attack"), foe, estimate = true), "psy: blocked against the target")
        for (ai in levels + AiController("E", AiLevel.EASY)) {
            Check.ok(aiPicks(ai, battle, me, 30).all { it.isMove("tackle") }, "${ai.level.id}: avoids a move blocked against its target")
        }
        val only = aiCraft(setup, listOf(aiMon("boulderbun", "quick_attack")), listOf(aiMon("leafling", "tackle")))
        only.add("psy")
        val lone = only.sides[0].team[0]
        for (ai in levels) Check.ok(aiPicks(ai, only, lone, 5).all { it.isMove("quick_attack") }, "${ai.level.id}: keeps it when nothing else is legal")
    }

    // Damage estimates include the damage modifiers of the fields.
    run {
        val battle = aiCraft(setup, listOf(aiMon("embercub", "flamethrower", "tackle")), listOf(aiMon("leafling", "tackle")))
        val me = battle.sides[0].team[0]
        battle.add("drench")
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("tackle") }, "${ai.level.id}: a drenched fire move is weak")
    }

    // setField: worth something while it is not active, nothing once it is (stacking ignore).
    run {
        val battle = aiCraft(setup, listOf(aiMon("puddlepup", "light_screen", "splash")), listOf(aiMon("boulderbun", "tackle", "double_edge")))
        val me = battle.sides[0].team[0]
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("light_screen") }, "${ai.level.id}: sets a screen against a physical attacker")
        battle.add("screen", me.side)
        for (ai in levels) Check.ok(!aiPicks(ai, battle, me, 40).all { it.isMove("light_screen") }, "${ai.level.id}: no value once the screen is up")
    }

    // Hazards are worth more with an enemy bench (nothing without one).
    run {
        val alone = aiCraft(setup, listOf(aiMon("puddlepup", "lay_spikes", "splash")), listOf(aiMon("boulderbun", "tackle")))
        for (ai in levels) {
            Check.ok(!aiPicks(ai, alone, alone.sides[0].team[0], 40).all { it.isMove("lay_spikes") }, "${ai.level.id}: no hazard without an enemy bench")
        }
        val bench = aiCraft(
            setup, listOf(aiMon("puddlepup", "lay_spikes", "splash")),
            listOf(aiMon("boulderbun", "tackle"), aiMon("leafling", "tackle"), aiMon("embercub", "tackle")),
        )
        for (ai in levels) {
            Check.ok(aiPicks(ai, bench, bench.sides[0].team[0], 20).all { it.isMove("lay_spikes") }, "${ai.level.id}: hazards against a bench")
        }
    }

    // removeField: clearing the enemy's screen is worth something.
    run {
        val battle = aiCraft(setup, listOf(aiMon("puddlepup", "defog", "splash")), listOf(aiMon("boulderbun", "tackle")))
        val me = battle.sides[0].team[0]
        for (ai in levels) Check.ok(!aiPicks(ai, battle, me, 40).all { it.isMove("defog") }, "${ai.level.id}: nothing to clear")
        battle.add("screen", battle.sides[1])
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("defog") }, "${ai.level.id}: clears the enemy screen")
    }

    // A status a field blocks is worthless.
    run {
        val battle = aiCraft(setup, listOf(aiMon("wispurr", "will_o_wisp", "splash")), listOf(aiMon("boulderbun", "tackle", "double_edge")))
        val me = battle.sides[0].team[0]
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("will_o_wisp") }, "${ai.level.id}: burns a physical attacker")
        battle.add("veil", battle.sides[1])
        for (ai in levels) Check.ok(!aiPicks(ai, battle, me, 40).all { it.isMove("will_o_wisp") }, "${ai.level.id}: not through a veil")
    }
}

/** Builds a battle by hand: the first Rules.activePerSide members of each list are on the field. */
private fun aiCraft(setup: Setup, mine: List<Bakemon>, theirs: List<Bakemon>, seed: Long = 1L): Battle {
    val active = setup.rules.activePerSide
    val sides = listOf(Side(0, "Mine", AiController("Mine"), active), Side(1, "Theirs", AiController("Theirs"), active))
    listOf(mine, theirs).forEachIndexed { index, members ->
        val side = sides[index]
        members.forEachIndexed { i, config -> side.team += Battler.create(setup, config, side, i) }
        for (slot in side.active.indices) {
            val b = side.team.getOrNull(slot) ?: break
            side.active[slot] = b
            b.slot = slot
        }
    }
    return Battle(setup, sides, Random(seed)).also { it.turn = 1 }
}

private fun aiMon(species: String, vararg moves: String, ability: String = "plain", level: Int = 50) =
    Bakemon(species = species, level = level, ability = ability, moves = moves.toList())

private fun aiPicks(ai: AiController, battle: Battle, actor: Battler, times: Int): List<Action> =
    List(times) { ai.chooseAction(battle, actor) }

private fun Battler.moveDef(id: String): MoveDef = moves.first { it.def.id == id }.def

private fun Action.isMove(id: String, target: Battler? = null): Boolean =
    this is Action.UseMove && move.id == id && (target == null || this.target === target)

/** Writes [files] into [dir] and loads it; reports a failure and returns null if the setup does not load. */
private fun aiLoadSetup(dir: File, files: Map<String, String>): Setup? {
    dir.mkdirs()
    for ((name, text) in files) File(dir, name).writeText(text.trimIndent())
    return try {
        Setup.load(dir)
    } catch (t: Throwable) {
        Check.ok(false, "test setup ${dir.name} does not load: $t")
        null
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Test setups

/** Classic-style singles (speed_order, PP, switching, fallback move). */
private val AI_CLASSIC_FILES = mapOf(
    "Rules.json" to """
        {
          "name": "AI test classic",
          "description": "Small Classic-style setup for the AI tests",
          "turnSystem": "speed_order",
          "team": { "min": 1, "max": 3, "active": 1, "speciesClause": false, "itemClause": false },
          "switching": true,
          "level": { "min": 1, "max": 100, "default": 50 },
          "maxMoves": 4,
          "heldItems": true,
          "stats": [
            { "id": "hp", "name": "HP" }, { "id": "atk", "name": "Attack" }, { "id": "def", "name": "Defense" },
            { "id": "spa", "name": "Sp. Atk" }, { "id": "spd", "name": "Sp. Def" }, { "id": "spe", "name": "Speed" }
          ],
          "hpStat": "hp",
          "speedStat": "spe",
          "statFormulas": {
            "hp": "floor(2 * base * level / 100) + level + 10",
            "default": "floor(2 * base * level / 100) + 5"
          },
          "statStages": { "min": -6, "max": 6, "multiplier": "stage >= 0 ? (2 + stage) / 2 : 2 / (2 - stage)" },
          "categories": {
            "physical": { "attack": "atk", "defense": "def" },
            "special": { "attack": "spa", "defense": "spd" },
            "status": {}
          },
          "damage": "floor(floor(floor(2 * user.level / 5 + 2) * move.power * attack / defense) / 50 + 2) * (sameType ? 1.5 : 1) * effectiveness * crit * random(0.85, 1)",
          "hitChance": "move.accuracy / 100",
          "critChance": "1 / 24",
          "critMultiplier": "1.5",
          "minDamage": 1,
          "pp": true,
          "cooldowns": false,
          "fallbackMove": "desperate_strike",
          "turnLimit": 150
        }
    """,
    "Types.json" to """
        {
          "types": [
            { "id": "normal", "name": "Normal", "color": "white" },
            { "id": "fire", "name": "Fire", "color": "red" },
            { "id": "water", "name": "Water", "color": "blue" },
            { "id": "grass", "name": "Grass", "color": "green" },
            { "id": "ghost", "name": "Ghost", "color": "magenta" }
          ],
          "chart": {
            "normal": { "ghost": 0 },
            "fire": { "grass": 2, "water": 0.5, "fire": 0.5 },
            "water": { "fire": 2, "grass": 0.5, "water": 0.5 },
            "grass": { "water": 2, "fire": 0.5, "grass": 0.5 },
            "ghost": { "ghost": 2, "normal": 0.5 }
          },
          "messages": { "strong": "It's super effective!", "weak": "It's not very effective...", "immune": "It doesn't affect {target}..." }
        }
    """,
    "Statuses.json" to """
        { "statuses": [
          { "id": "burn", "name": "Burn", "short": "BRN", "description": "Loses 1/16 HP each turn, Attack halved.",
            "kind": "major", "immuneTypes": ["fire"], "statModifiers": { "atk": 0.5 },
            "triggers": [ { "on": "turnEnd", "effects": [ { "type": "damage", "target": "self", "amount": "max(1, floor(self.maxHp / 16))" } ] } ],
            "applyMessage": "{target} was burned!", "removeMessage": "{target}'s burn healed." },
          { "id": "poison", "name": "Poison", "short": "PSN", "description": "Loses 1/8 HP each turn.", "kind": "major",
            "triggers": [ { "on": "turnEnd", "effects": [ { "type": "damage", "target": "self", "amount": "max(1, floor(self.maxHp / 8))" } ] } ] },
          { "id": "sleep", "name": "Sleep", "short": "SLP", "description": "Cannot move.", "kind": "major", "duration": "randint(1, 3)",
            "triggers": [ { "on": "beforeMove", "message": "{self} is fast asleep.", "effects": [ { "type": "cancelAction" } ] } ] },
          { "id": "pumped", "name": "Pumped", "short": "PMP", "description": "Speed x1.5.", "kind": "buff", "duration": 3,
            "stacking": "refresh", "statModifiers": { "spe": 1.5 } }
        ] }
    """,
    "Abilities.json" to """
        { "abilities": [
          { "id": "blaze", "name": "Blaze", "description": "Fire moves are stronger when HP is low.",
            "triggers": [ { "on": "modifyDamageDealt", "condition": "move.type == 'fire' && self.hpPercent <= 33",
              "effects": [ { "type": "multiplyDamage", "value": 1.5 } ] } ] },
          { "id": "thick_skin", "name": "Thick Skin", "description": "Takes less damage.",
            "triggers": [ { "on": "modifyDamageTaken", "effects": [ { "type": "multiplyDamage", "value": 0.8 } ] } ] },
          { "id": "plain", "name": "Plain", "description": "No effect." }
        ] }
    """,
    "Items.json" to """
        { "items": [
          { "id": "leftovers", "name": "Leftovers", "description": "Restores a little HP each turn.",
            "triggers": [ { "on": "turnEnd", "condition": "self.hp < self.maxHp",
              "effects": [ { "type": "heal", "amount": "max(1, floor(self.maxHp / 16))" } ] } ] },
          { "id": "berry", "name": "Berry", "description": "Heals once when HP is low.",
            "triggers": [ { "on": "damaged", "condition": "self.hpPercent <= 50", "limit": 1, "message": "{self} ate its {item}!",
              "effects": [ { "type": "heal", "amount": "floor(self.maxHp / 4)" }, { "type": "consumeItem" } ] } ] }
        ] }
    """,
    "Moves.json" to """
        { "moves": [
          { "id": "tackle", "name": "Tackle", "type": "normal", "category": "physical", "power": 40, "accuracy": 100, "pp": 35,
            "target": "enemy", "flags": ["contact"] },
          { "id": "quick_attack", "name": "Quick Attack", "type": "normal", "category": "physical", "power": 40, "accuracy": 100,
            "priority": 1, "pp": 30, "target": "enemy" },
          { "id": "double_edge", "name": "Double-Edge", "type": "normal", "category": "physical", "power": 120, "accuracy": 100,
            "pp": 15, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "damage", "target": "self", "amount": "max(1, floor(damage / 3))" } ] } ] },
          { "id": "ember", "name": "Ember", "type": "fire", "category": "special", "power": 40, "accuracy": 100, "pp": 25, "target": "enemy",
            "triggers": [ { "on": "hit", "chance": 10, "effects": [ { "type": "applyStatus", "status": "burn" } ] } ] },
          { "id": "flamethrower", "name": "Flamethrower", "type": "fire", "category": "special", "power": 90, "accuracy": 100,
            "pp": 15, "target": "enemy" },
          { "id": "water_gun", "name": "Water Gun", "type": "water", "category": "special", "power": 40, "accuracy": 100,
            "pp": 25, "target": "enemy" },
          { "id": "surf", "name": "Surf", "type": "water", "category": "special", "power": 90, "accuracy": 100, "pp": 15,
            "target": "allEnemies" },
          { "id": "vine_whip", "name": "Vine Whip", "type": "grass", "category": "physical", "power": 45, "accuracy": 100,
            "pp": 25, "target": "enemy" },
          { "id": "giga_drain", "name": "Giga Drain", "type": "grass", "category": "special", "power": 75, "accuracy": 100,
            "pp": 10, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "heal", "target": "user", "amount": "max(1, floor(damage / 2))" } ] } ] },
          { "id": "shadow_ball", "name": "Shadow Ball", "type": "ghost", "category": "special", "power": 80, "accuracy": 100,
            "pp": 15, "target": "enemy" },
          { "id": "will_o_wisp", "name": "Will-O-Wisp", "type": "fire", "category": "status", "accuracy": 85, "pp": 15, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "applyStatus", "status": "burn" } ] } ] },
          { "id": "toxic", "name": "Toxic", "category": "status", "accuracy": 90, "pp": 10, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "applyStatus", "status": "poison" } ] } ] },
          { "id": "hypnosis", "name": "Hypnosis", "category": "status", "accuracy": 60, "pp": 20, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "applyStatus", "status": "sleep" } ] } ] },
          { "id": "recover", "name": "Recover", "category": "status", "pp": 10, "target": "self",
            "triggers": [ { "on": "hit", "effects": [ { "type": "heal", "target": "self", "amount": "floor(self.maxHp / 2)" } ] } ] },
          { "id": "swords_dance", "name": "Swords Dance", "category": "status", "pp": 20, "target": "self",
            "triggers": [ { "on": "hit", "effects": [ { "type": "statStage", "target": "self", "stat": "atk", "stages": 2 } ] } ] },
          { "id": "growl", "name": "Growl", "type": "normal", "category": "status", "accuracy": 100, "pp": 40, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "statStage", "stat": "atk", "stages": -1 } ] } ] },
          { "id": "agility", "name": "Agility", "category": "status", "pp": 30, "target": "self",
            "triggers": [ { "on": "hit", "effects": [ { "type": "applyStatus", "target": "self", "status": "pumped" } ] } ] },
          { "id": "desperate_strike", "name": "Desperate Strike", "category": "physical", "power": 50, "pp": 0, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "damage", "target": "self", "amount": "max(1, floor(self.maxHp / 4))" } ] } ] },
          { "id": "light_screen", "name": "Light Screen", "category": "status", "pp": 20, "target": "self",
            "triggers": [ { "on": "hit", "effects": [ { "type": "setField", "field": "screen" } ] } ] },
          { "id": "lay_spikes", "name": "Lay Spikes", "category": "status", "pp": 20, "target": "self",
            "triggers": [ { "on": "hit", "effects": [ { "type": "setField", "field": "spikes", "side": "enemy" } ] } ] },
          { "id": "defog", "name": "Defog", "category": "status", "pp": 15, "target": "self",
            "triggers": [ { "on": "hit", "effects": [ { "type": "removeField", "field": "screen", "side": "enemy" } ] } ] },
          { "id": "splash", "name": "Splash", "category": "status", "pp": 40, "target": "self" }
        ] }
    """,
    "Fields.json" to """
        { "fields": [
          { "id": "gravity", "name": "Gravity", "rules": { "blockMove": "move.id == 'flamethrower'" } },
          { "id": "psy", "name": "Psy", "rules": { "blockMove": "move.priority > 0 && target.hp > 0" } },
          { "id": "drench", "name": "Drench",
            "triggers": [ { "on": "modifyDamageDealt", "condition": "move.type == 'fire'", "effects": [ { "type": "multiplyDamage", "value": 0.1 } ] } ] },
          { "id": "screen", "name": "Screen", "scope": "side", "duration": 5,
            "triggers": [ { "on": "modifyDamageTaken", "effects": [ { "type": "multiplyDamage", "value": 0.5 } ] } ] },
          { "id": "spikes", "name": "Spikes", "scope": "side", "group": "hazards",
            "triggers": [ { "on": "switchIn", "effects": [ { "type": "damage", "amount": "floor(self.maxHp / 8)" } ] } ] },
          { "id": "veil", "name": "Veil", "scope": "side", "rules": { "blockStatus": "statusKind == 'major' && !sameSide(self, other)" } }
        ] }
    """,
    "Bakemons.json" to """
        { "bakemons": [
          { "id": "embercub", "name": "Embercub", "types": ["fire"],
            "baseStats": { "hp": 45, "atk": 60, "def": 45, "spa": 75, "spd": 50, "spe": 65 },
            "abilities": ["blaze", "plain"],
            "moves": ["ember", "flamethrower", "tackle", "quick_attack", "will_o_wisp", "growl", "agility"] },
          { "id": "puddlepup", "name": "Puddlepup", "types": ["water"],
            "baseStats": { "hp": 55, "atk": 50, "def": 55, "spa": 70, "spd": 60, "spe": 50 },
            "abilities": ["plain", "thick_skin"],
            "moves": ["water_gun", "surf", "tackle", "recover", "toxic", "growl"] },
          { "id": "leafling", "name": "Leafling", "types": ["grass"],
            "baseStats": { "hp": 50, "atk": 55, "def": 50, "spa": 60, "spd": 55, "spe": 45 },
            "abilities": ["plain"],
            "moves": ["vine_whip", "giga_drain", "tackle", "swords_dance", "hypnosis", "growl"] },
          { "id": "boulderbun", "name": "Boulderbun", "types": ["normal"],
            "baseStats": { "hp": 100, "atk": 70, "def": 110, "spa": 40, "spd": 100, "spe": 30 },
            "abilities": ["thick_skin", "plain"],
            "moves": ["tackle", "double_edge", "recover", "swords_dance", "quick_attack"] },
          { "id": "wispurr", "name": "Wispurr", "types": ["ghost"],
            "baseStats": { "hp": 45, "atk": 40, "def": 45, "spa": 85, "spd": 60, "spe": 85 },
            "abilities": ["plain"],
            "moves": ["shadow_ball", "will_o_wisp", "hypnosis", "toxic", "quick_attack"] }
        ] }
    """,
)

/** Action-bar-style 2v2 (action_bar, cooldowns, no PP, no switching, no fallback move). */
private val AI_ACTION_BAR_FILES = mapOf(
    "Rules.json" to """
        {
          "name": "AI test action bar",
          "description": "Small 2v2 action bar setup for the AI tests",
          "turnSystem": "action_bar",
          "team": { "min": 2, "max": 3, "active": 2, "speciesClause": true, "itemClause": false },
          "switching": false,
          "level": { "min": 1, "max": 60, "default": 30 },
          "maxMoves": 3,
          "heldItems": true,
          "stats": [ { "id": "hp", "name": "HP" }, { "id": "atk", "name": "Attack" }, { "id": "def", "name": "Defense" }, { "id": "spd", "name": "Speed" } ],
          "hpStat": "hp",
          "speedStat": "spd",
          "statFormulas": { "hp": "base * 4 + level * 10", "default": "base + level" },
          "statStages": { "min": -3, "max": 3, "multiplier": "1 + stage * 0.25" },
          "categories": { "attack": { "attack": "atk", "defense": "def" }, "support": {} },
          "damage": "move.power / 100 * attack * 300 / (300 + defense * 2) * effectiveness * crit * random(0.9, 1.1)",
          "hitChance": "move.accuracy / 100",
          "critChance": "0.15",
          "critMultiplier": "1.5",
          "minDamage": 1,
          "pp": false,
          "cooldowns": true,
          "actionBar": { "size": 100, "fill": "speed", "start": "speed / 10" },
          "turnLimit": 400
        }
    """,
    "Types.json" to """
        {
          "types": [
            { "id": "fire", "name": "Fire", "color": "red" },
            { "id": "water", "name": "Water", "color": "blue" },
            { "id": "wood", "name": "Wood", "color": "green" },
            { "id": "light", "name": "Light", "color": "yellow" },
            { "id": "dark", "name": "Dark", "color": "magenta" }
          ],
          "chart": {
            "fire": { "wood": 1.5, "water": 0.75 },
            "water": { "fire": 1.5, "wood": 0.75 },
            "wood": { "water": 1.5, "fire": 0.75 },
            "light": { "dark": 1.5 },
            "dark": { "light": 1.5 }
          },
          "messages": { "strong": "Advantage!", "weak": "Disadvantage...", "immune": "{target} is unaffected." }
        }
    """,
    "Statuses.json" to """
        { "statuses": [
          { "id": "bleed", "name": "Bleed", "short": "BLD", "description": "Loses 3% HP per stack each turn.", "kind": "debuff",
            "duration": 3, "stacking": "stack", "maxStacks": 3,
            "triggers": [ { "on": "turnEnd", "effects": [ { "type": "damage", "target": "self", "amount": "max(1, floor(self.maxHp * 0.03 * stacks))" } ] } ] },
          { "id": "empowered", "name": "Empowered", "short": "EMP", "description": "Attack x1.3.", "kind": "buff", "duration": 2,
            "stacking": "refresh", "statModifiers": { "atk": 1.3 } },
          { "id": "weakened", "name": "Weakened", "short": "WKN", "description": "Defense x0.7.", "kind": "debuff", "duration": 2,
            "stacking": "refresh", "statModifiers": { "def": 0.7 } },
          { "id": "stun", "name": "Stun", "short": "STN", "description": "Loses its next turn.", "kind": "volatile", "duration": 1,
            "stacking": "ignore",
            "triggers": [ { "on": "beforeMove", "message": "{self} is stunned!", "effects": [ { "type": "cancelAction" } ] } ] }
        ] }
    """,
    "Abilities.json" to """
        { "abilities": [
          { "id": "vigor", "name": "Vigor", "description": "Attack +10%.", "statModifiers": { "atk": 1.1 } },
          { "id": "regen", "name": "Regen", "description": "Heals 5% at the end of its turns.",
            "triggers": [ { "on": "turnEnd", "condition": "self.hp < self.maxHp",
              "effects": [ { "type": "heal", "amount": "max(1, floor(self.maxHp * 0.05))" } ] } ] },
          { "id": "spite", "name": "Spite", "description": "May hurt attackers.",
            "triggers": [ { "on": "damaged", "chance": 30, "condition": "other.hp > 0",
              "effects": [ { "type": "damage", "target": "other", "amount": "max(1, floor(other.maxHp * 0.03))" } ] } ] }
        ] }
    """,
    "Items.json" to """
        { "items": [
          { "id": "charm", "name": "Swift Charm", "description": "Speed +10%.", "statModifiers": { "spd": 1.1 } },
          { "id": "potion", "name": "Potion", "description": "Heals 30% once when HP drops below 30%.",
            "triggers": [ { "on": "damaged", "condition": "self.hpPercent < 30", "limit": 1,
              "effects": [ { "type": "heal", "amount": "floor(self.maxHp * 0.3)" }, { "type": "consumeItem" } ] } ] }
        ] }
    """,
    "Moves.json" to """
        { "moves": [
          { "id": "flame_slash", "name": "Flame Slash", "type": "fire", "category": "attack", "power": 100, "accuracy": 95, "target": "enemy" },
          { "id": "tide_slash", "name": "Tide Slash", "type": "water", "category": "attack", "power": 100, "accuracy": 95, "target": "enemy" },
          { "id": "root_slash", "name": "Root Slash", "type": "wood", "category": "attack", "power": 100, "accuracy": 95, "target": "enemy" },
          { "id": "radiant_beam", "name": "Radiant Beam", "type": "light", "category": "attack", "power": 100, "target": "enemy" },
          { "id": "shadow_claw", "name": "Shadow Claw", "type": "dark", "category": "attack", "power": 100, "target": "enemy" },
          { "id": "sweep", "name": "Sweep", "category": "attack", "power": 60, "target": "allEnemies", "cooldown": 2 },
          { "id": "quake", "name": "Quake", "category": "attack", "power": 70, "target": "randomEnemy", "cooldown": 1, "hits": "randint(1, 2)",
            "triggers": [ { "on": "hit", "effects": [ { "type": "gauge", "amount": -20 } ] } ] },
          { "id": "mend", "name": "Mend", "category": "support", "target": "allyOrSelf", "cooldown": 2,
            "triggers": [ { "on": "hit", "effects": [ { "type": "heal", "amount": "floor(target.maxHp * 0.3)" } ] } ] },
          { "id": "rally", "name": "Rally", "category": "support", "target": "team", "cooldown": 3,
            "triggers": [ { "on": "hit", "effects": [ { "type": "gauge", "amount": 25 } ] } ] },
          { "id": "barrier", "name": "Barrier", "category": "support", "target": "self", "cooldown": 2,
            "triggers": [ { "on": "hit", "effects": [ { "type": "shield", "amount": "floor(self.maxHp * 0.2)" } ] } ] },
          { "id": "hex", "name": "Hex", "type": "dark", "category": "support", "accuracy": 100, "cooldown": 1, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "applyStatus", "status": "bleed" } ] } ] },
          { "id": "inspire", "name": "Inspire", "category": "support", "target": "ally", "cooldown": 2,
            "triggers": [ { "on": "hit", "effects": [ { "type": "applyStatus", "status": "empowered" } ] } ] },
          { "id": "expose", "name": "Expose", "category": "support", "cooldown": 2, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "applyStatus", "status": "weakened" } ] } ] },
          { "id": "stun_bolt", "name": "Stun Bolt", "type": "light", "category": "attack", "power": 50, "cooldown": 2, "target": "enemy",
            "triggers": [ { "on": "hit", "chance": 30, "effects": [ { "type": "applyStatus", "status": "stun" } ] } ] },
          { "id": "drain_bite", "name": "Drain Bite", "type": "dark", "category": "attack", "power": 70, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "heal", "target": "user", "amount": "max(1, floor(damage * 0.5))" } ] } ] },
          { "id": "reckless_charge", "name": "Reckless Charge", "type": "fire", "category": "attack", "power": 160, "cooldown": 1,
            "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "damage", "target": "self", "amount": "max(1, floor(damage * 0.25))" } ] } ] },
          { "id": "jam", "name": "Jam", "category": "support", "cooldown": 3, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "cooldown", "amount": 1 } ] } ] },
          { "id": "haste", "name": "Haste", "category": "support", "target": "self", "cooldown": 3,
            "triggers": [ { "on": "afterMove", "effects": [ { "type": "gauge", "target": "self", "amount": 50 } ] } ] },
          { "id": "cleanse", "name": "Cleanse", "category": "support", "target": "allyOrSelf", "cooldown": 2,
            "triggers": [ { "on": "hit", "effects": [ { "type": "removeStatus", "kind": "debuff" } ] } ] }
        ] }
    """,
    "Bakemons.json" to """
        { "bakemons": [
          { "id": "pyrofox", "name": "Pyrofox", "types": ["fire"], "baseStats": { "hp": 60, "atk": 70, "def": 50, "spd": 80 },
            "abilities": ["vigor", "spite"], "moves": ["flame_slash", "reckless_charge", "sweep", "haste", "expose", "stun_bolt"] },
          { "id": "aquaturtle", "name": "Aquaturtle", "types": ["water"], "baseStats": { "hp": 80, "atk": 50, "def": 80, "spd": 50 },
            "abilities": ["regen"], "moves": ["tide_slash", "mend", "barrier", "rally", "cleanse", "quake"] },
          { "id": "mossling", "name": "Mossling", "types": ["wood"], "baseStats": { "hp": 70, "atk": 60, "def": 60, "spd": 60 },
            "abilities": ["regen", "vigor"], "moves": ["root_slash", "hex", "inspire", "mend", "quake", "jam"] },
          { "id": "lumina", "name": "Lumina", "types": ["light"], "baseStats": { "hp": 55, "atk": 75, "def": 45, "spd": 90 },
            "abilities": ["vigor"], "moves": ["radiant_beam", "stun_bolt", "inspire", "sweep", "haste"] },
          { "id": "umbrat", "name": "Umbrat", "types": ["dark"], "baseStats": { "hp": 65, "atk": 80, "def": 55, "spd": 70 },
            "abilities": ["spite"], "moves": ["shadow_claw", "drain_bite", "hex", "expose", "jam", "reckless_charge"] }
        ] }
    """,
)

// ---------------------------------------------------------------------------------------------------------------
// More triggers, targets, effects and move behaviours (README "useMove", "charge"...)

/** Runs the AI tests of the new effects, targets and move behaviours on doubles variants of the classic setup. */
private fun runAiTriggerTests(tmp: File) {
    val doubles = aiLoadSetup(File(tmp, "AiTriggers"), AI_TRIGGER_FILES) ?: return
    Check.ok(doubles.warnings.isEmpty(), "the AI trigger setup loads without warnings: ${doubles.warnings}")
    val atbFiles = AI_TRIGGER_FILES + ("Rules.json" to AI_TRIGGER_FILES.getValue("Rules.json").replace("\"speed_order\"", "\"action_bar\""))
    val atb = aiLoadSetup(File(tmp, "AiTriggersAtb"), atbFiles) ?: return
    Check.guard("ai: trigger choices") { aiTriggerChoiceTests(doubles) }
    Check.guard("ai: trigger random states") { aiFuzzTests(doubles, 80, 31L) }
    Check.guard("ai: trigger battles") { aiBattleTests(doubles) }
    Check.guard("ai: trigger action_bar random states") { aiFuzzTests(atb, 80, 37L) }
    Check.guard("ai: trigger action_bar battles") { aiBattleTests(atb) }
}

private fun aiTriggerChoiceTests(setup: Setup) {
    Check.section("ai: trigger choices")
    val normal = AiController("N", AiLevel.NORMAL)
    val levels = listOf(normal, AiController("H", AiLevel.HARD))
    fun duo(vararg moves: String) = listOf(aiMon("embercub", *moves), aiMon("boulderbun", "tackle"))
    fun foes() = listOf(aiMon("leafling", "tackle"), aiMon("boulderbun", "tackle"), aiMon("puddlepup", "tackle"))
    fun prefers(battle: Battle, me: Battler, move: String, label: String) {
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove(move) }, "${ai.level.id}: $label")
    }

    // An enemy charging out of reach cannot be hit: the AI aims at the other one.
    run {
        val battle = aiCraft(setup, duo("tackle"), foes())
        val me = battle.sides[0].team[0]
        val a = battle.sides[1].team[0]
        val b = battle.sides[1].team[1]
        a.charge = ChargeState(setup.moves.require("sky_dive"), me, 0, invulnerable = true)
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("tackle", b) }, "${ai.level.id}: does not aim at an unreachable enemy")
        a.charge = null
        Check.ok(levels.any { ai -> aiPicks(ai, battle, me, 40).any { it.isMove("tackle", a) } }, "aims at it again once it is reachable")
    }

    // lowestHpAlly healing: only when somebody on its side is hurt.
    run {
        val battle = aiCraft(setup, duo("patch", "tackle"), foes())
        val me = battle.sides[0].team[0]
        val ally = battle.sides[0].team[1]
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).none { it.isMove("patch") }, "${ai.level.id}: no healing when everybody is healthy")
        ally.hp = maxOf(1, ally.maxHp / 6)
        prefers(battle, me, "patch", "heals the wounded ally (chosen automatically)")
    }

    // Power that grows with hitIndex is estimated hit by hit (20 + 40 + 60 beats a single 60).
    run {
        val battle = aiCraft(setup, duo("kick3", "flat60"), listOf(aiMon("boulderbun", "tackle"), aiMon("boulderbun", "tackle")))
        prefers(battle, battle.sides[0].team[0], "kick3", "kick3 (20/40/60) beats flat60")
    }

    // Every hit rolling its accuracy is worth less than one roll for all the hits.
    run {
        val battle = aiCraft(setup, duo("each5", "once5"), listOf(aiMon("boulderbun", "tackle"), aiMon("boulderbun", "tackle")))
        prefers(battle, battle.sides[0].team[0], "once5", "one accuracy roll beats one per hit")
    }

    // A charge move pays off later than the same move without a charge.
    run {
        val battle = aiCraft(setup, duo("sky_dive", "slam90"), listOf(aiMon("leafling", "tackle"), aiMon("leafling", "tackle")))
        prefers(battle, battle.sides[0].team[0], "slam90", "no charge beats a charge")
    }

    // The new effects are worth something when they help.
    run {
        val battle = aiCraft(setup, duo("haste", "splash"), foes())
        prefers(battle, battle.sides[0].team[0], "haste", "an extra turn beats splash")
        battle.sides[0].team[0].extraTurnPending = true
        Check.ok(aiPicks(normal, battle, battle.sides[0].team[0], 40).any { it.isMove("splash") }, "no value while an extra turn is already waiting")
    }
    run {
        val battle = aiCraft(setup, duo("echo_strike", "splash"), foes())
        prefers(battle, battle.sides[0].team[0], "echo_strike", "a move used through useMove is worth its damage")
    }
    run {
        val battle = aiCraft(setup, duo("half_cut", "splash"), foes())
        prefers(battle, battle.sides[0].team[0], "half_cut", "halving an enemy's HP is good")
    }
    run {
        val battle = aiCraft(setup, duo("future", "splash"), foes())
        prefers(battle, battle.sides[0].team[0], "future", "a delayed strike is worth something")
    }
    run {
        val battle = aiCraft(setup, duo("steal_boost", "splash"), foes())
        val me = battle.sides[0].team[0]
        val foe = battle.sides[1].team[0]
        Check.ok(aiPicks(normal, battle, me, 40).any { it.isMove("splash") }, "nothing to steal: no better than splash")
        foe.statuses += StatusInstance(setup.statuses.require("pumped"), 3, 1, null, false)
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("steal_boost", foe) }, "${ai.level.id}: steals the enemy's buff")
    }
    run {
        val battle = aiCraft(setup, duo("roar", "splash"), foes())
        val me = battle.sides[0].team[0]
        val foe = battle.sides[1].team[1]
        foe.stages["atk"] = 4
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("roar", foe) }, "${ai.level.id}: forces out the boosted enemy")
    }
    run {
        val battle = aiCraft(setup, duo("copycat", "splash"), foes())
        val me = battle.sides[0].team[0]
        val foe = battle.sides[1].team[0]
        foe.stages["spa"] = 4
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("copycat", foe) }, "${ai.level.id}: copies the enemy's boosts")
    }
    run {
        val battle = aiCraft(setup, duo("share", "splash"), foes())
        val me = battle.sides[0].team[0]
        battle.sides[0].team[1].hp = maxOf(1, battle.sides[0].team[1].maxHp / 6)
        Check.ok(aiPicks(normal, battle, me, 40).count { it.isMove("share") } > 20, "shares HP with a wounded ally")
    }
    run {
        val battle = aiCraft(setup, duo("swap", "splash"), foes())
        val me = battle.sides[0].team[0]
        me.hp = maxOf(1, me.maxHp / 5)
        for (ai in levels) Check.ok(aiPicks(ai, battle, me, 20).all { it.isMove("swap") }, "${ai.level.id}: swaps HP when it is low and the enemy is not")
        me.hp = me.maxHp
        Check.ok(aiPicks(normal, battle, me, 40).any { it.isMove("splash") }, "no swap at full HP")
    }
}

/** [AI_CLASSIC_FILES] in doubles, with moves and abilities that use the new triggers, targets and effects. */
private val AI_TRIGGER_FILES: Map<String, String> by lazy {
    val newMoves = """
          { "id": "haste", "name": "Haste", "category": "status", "pp": 10, "target": "self",
            "triggers": [ { "on": "hit", "effects": [ { "type": "extraTurn", "target": "self" } ] } ] },
          { "id": "patch", "name": "Patch", "category": "status", "pp": 10, "target": "lowestHpAlly",
            "triggers": [ { "on": "hit", "effects": [ { "type": "heal", "amount": "recipient.maxHp / 2" } ] } ] },
          { "id": "snipe", "name": "Snipe", "type": "normal", "category": "physical", "power": 40, "accuracy": 100, "pp": 20, "target": "lowestHpEnemy" },
          { "id": "spray", "name": "Spray", "type": "water", "category": "special", "power": 25, "accuracy": 90, "pp": 20, "hits": 3, "target": "randomEnemies" },
          { "id": "kick3", "name": "Kick3", "type": "normal", "category": "physical", "power": "20 * hitIndex", "accuracy": 100, "pp": 10, "hits": 3 },
          { "id": "flat60", "name": "Flat60", "type": "normal", "category": "physical", "power": 60, "accuracy": 100, "pp": 10 },
          { "id": "each5", "name": "Each5", "type": "normal", "category": "physical", "power": 20, "accuracy": 50, "pp": 10, "hits": 5, "hitAccuracy": "each" },
          { "id": "once5", "name": "Once5", "type": "normal", "category": "physical", "power": 20, "accuracy": 50, "pp": 10, "hits": 5 },
          { "id": "sky_dive", "name": "Sky Dive", "type": "normal", "category": "physical", "power": 90, "accuracy": 100, "pp": 10,
            "charge": { "invulnerable": true, "message": "{user} flew up high!" } },
          { "id": "slam90", "name": "Slam90", "type": "normal", "category": "physical", "power": 90, "accuracy": 100, "pp": 10 },
          { "id": "steal_boost", "name": "Steal Boost", "category": "status", "pp": 10, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "transferStatus", "kind": "buff", "count": 0 } ] } ] },
          { "id": "half_cut", "name": "Half Cut", "category": "status", "pp": 10, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "setHp", "amount": "recipient.hp / 2" } ] } ] },
          { "id": "roar", "name": "Roar", "category": "status", "pp": 10, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "forceSwitch" } ] } ] },
          { "id": "future", "name": "Future", "category": "status", "pp": 10, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "delay", "turns": 2,
              "effects": [ { "type": "attack", "power": 120, "moveType": "ghost", "category": "special" } ] } ] } ] },
          { "id": "echo_strike", "name": "Echo Strike", "category": "status", "pp": 10, "target": "self",
            "triggers": [ { "on": "hit", "effects": [ { "type": "useMove", "move": "flat60", "moveTarget": "lowestHpEnemy" } ] } ] },
          { "id": "u_turn", "name": "U-turn", "type": "grass", "category": "physical", "power": 70, "accuracy": 100, "pp": 20,
            "triggers": [ { "on": "afterMove", "effects": [ { "type": "switchOut" } ] } ] },
          { "id": "copycat", "name": "Copycat", "category": "status", "pp": 10, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "copyStages", "to": "self" } ] } ] },
          { "id": "share", "name": "Share", "category": "status", "pp": 10, "target": "self",
            "triggers": [ { "on": "hit", "effects": [ { "type": "balanceHp", "target": "team" } ] } ] },
          { "id": "swap", "name": "Swap", "category": "status", "pp": 10, "target": "enemy",
            "triggers": [ { "on": "hit", "effects": [ { "type": "swapHp" } ] } ] },
          { "id": "splash", "name": "Splash", "category": "status", "pp": 40, "target": "self" }
    """
    val newAbilities = """
          { "id": "vengeful", "name": "Vengeful", "triggers": [
            { "on": "attacked", "condition": "landed", "limit": 2, "effects": [ { "type": "useMove", "move": "first", "power": 50 } ] },
            { "on": "allyUsedMove", "chance": 30, "effects": [ { "type": "useMove", "moveTarget": "otherTarget", "power": 30 } ] },
            { "on": "statusInflicted", "effects": [ { "type": "heal", "target": "lowestHpAlly", "amount": "recipient.maxHp / 10" } ] } ] },
          { "id": "plain", "name": "Plain", "description": "No effect." }
    """
    val learn = "\"haste\", \"patch\", \"snipe\", \"spray\", \"kick3\", \"each5\", \"sky_dive\", \"steal_boost\", \"half_cut\", \"roar\", " +
        "\"future\", \"echo_strike\", \"u_turn\", \"copycat\", \"share\", \"swap\", "
    AI_CLASSIC_FILES.mapValues { (name, text) ->
        when (name) {
            "Rules.json" -> text.replace("\"active\": 1", "\"active\": 2")
            "Moves.json" -> text.replace("""{ "id": "splash", "name": "Splash", "category": "status", "pp": 40, "target": "self" }""", newMoves.trim())
            "Abilities.json" -> text.replace("""{ "id": "plain", "name": "Plain", "description": "No effect." }""", newAbilities.trim())
            "Bakemons.json" -> text.replace("\"moves\": [", "\"moves\": [$learn").replace("\"abilities\": [\"plain\"]", "\"abilities\": [\"plain\", \"vengeful\"]")
            else -> text
        }
    }
}
