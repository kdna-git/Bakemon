package bakemon

import engine.BattleFunctions
import engine.Effect
import engine.EventType
import engine.Formula
import engine.TargetSelector
import engine.Trigger
import engine.formula
import util.Json
import util.JsonObj
import util.JsonParseException
import util.SetupException
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** Collects problems found while loading a setup. Errors make the setup unusable; warnings do not. */
class LoadReport {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    fun error(message: String) { errors += message }
    fun warn(message: String) { warnings += message }
    val ok: Boolean get() = errors.isEmpty()

    /** Location of every loaded entry, keyed "<kind>:<id>" (e.g. "move:ember" -> "Moves.json: moves[3] (ember)"). */
    internal val locations = mutableMapOf<String, String>()

    /** Entries that were declared but failed to load, keyed "<kind>:<id>". References to them are not reported again. */
    internal val failed = mutableSetOf<String>()
}

/**
 * Where a formula is used, which decides the variables it may reference.
 * Setup validation checks every formula's variables()/functions() against its scope (README "Formulas").
 */
enum class FormulaScope {
    /** Rules.statFormulas: base, level, points (no random functions: stats must be the same every time). */
    STAT,
    /** Rules.statStages.multiplier: stage. */
    STAGE,
    /**
     * Rules.damage: battle vars + user/target/self/other + move + attack, defense, effectiveness, sameType, crit, isCrit,
     * targets, hitType.
     */
    DAMAGE,
    /** Rules.hitTypes chance / multiplier: like [DAMAGE] without attack, defense and hitType (they decide the hit type). */
    HIT_TYPE,
    /**
     * Rules.hitChance / critChance / critMultiplier and move critBonus: battle vars + user/target/self/other + move
     * + effectiveness, sameType (against the current target).
     */
    HIT,
    /**
     * Move power / hits / ignoreDefense / charge.skipIf: battle vars + user/target/self/other + move (+ hitIndex,
     * hitCount: the hit in progress; they read 0 where no hit is in progress).
     */
    MOVE,
    /** Rules.actionBar.fill / start: battle vars + self + speed. */
    ACTION_BAR,
    /** Ability/item statModifiers: battle vars + self (no random functions: reading a stat must not roll dice). */
    MODIFIER,
    /** Status statModifiers: battle vars + self + stacks, turnsLeft (no random functions, like [MODIFIER]). */
    STATUS_MODIFIER,
    /**
     * Trigger conditions/chances and effect values: battle vars + all monster refs + move + event vars
     * (+ stacks, turnsLeft in statuses and fields). `recipient` is accepted here; setup validation reports it where it
     * has no value (conditions, chances, durations, effects without a target: [FormulaNames.EFFECT_ONLY_REFS]).
     */
    TRIGGER,
    /** A field's "blockMove" rule: like [MOVE] (+ stacks, turnsLeft), no random functions (read when choosing). */
    BLOCK_MOVE,
    /** A field's "blockStatus" rule: battle vars + all monster refs + status, statusKind (+ stacks, turnsLeft). */
    BLOCK_STATUS,
    /** A field's "blockStatStage" rule: battle vars + all monster refs + stat, stages (+ stacks, turnsLeft). */
    BLOCK_STAT_STAGE,
}

/**
 * A fully loaded game: rules, types, monsters, moves, abilities, items and statuses of one folder
 * under Setups/, plus team storage in its Teams/ folder.
 */
class Setup(
    /** Folder name. */
    val name: String,
    val dir: File,
    val rules: Rules,
    val types: TypeChart,
    val dex: Bakedex,
    val moves: MoveManager,
    val abilities: AbilityManager,
    val items: ItemManager,
    val statuses: StatusManager,
    /** Non-fatal problems found while loading (show them to the user). */
    val warnings: List<String>,
    /** Fields.json (empty when the setup has none). */
    val fields: FieldManager = FieldManager(emptyMap()),
) {
    val teamsDir: File get() = File(dir, "Teams")
    val assetsDir: File get() = File(dir, "Assets")

    /** Display name: Rules.name, or the folder name if blank. */
    val displayName: String get() = rules.name.ifBlank { name }

    /** Team file names without .json (the "stems") in Teams/, sorted. Empty if the folder does not exist. */
    fun listTeams(): List<String> = teamFiles().map { stem(it) }.sortedWith(String.CASE_INSENSITIVE_ORDER)

    /**
     * Loads a team: [name] is a file stem returned by [listTeams] (Teams/<name>.json exactly), or else a team name
     * (Teams/<its safe file name>.json). Throws util.SetupException if missing or malformed (not if invalid: call validate).
     */
    fun loadTeam(name: String): Team {
        val file = teamFileOf(name)
        if (!file.isFile) throw SetupException("Team '$name' not found (${file.path})")
        val value = LoadSupport.readJson(file)
        val team = Team.fromJson(JsonObj.of(value, "Teams/${file.name}"))
        return if (team.name.isBlank()) team.copy(name = stem(file)) else team
    }

    /**
     * Saves [team] (creating the folder) and returns the file. With [file] (a stem from [listTeams], e.g. the file the
     * team was loaded from) Teams/<file>.json is written, overwritten if it exists and created again if it was renamed
     * or deleted meanwhile (never another team's file); otherwise Teams/<safe file name of the team name>.json.
     * Throws java.io.IOException if the file cannot be written.
     */
    fun saveTeam(team: Team, file: String? = null): File {
        val target = if (file != null) existingTeamFile(file) ?: File(teamsDir, "$file.json") else teamFile(team.name)
        Json.writeFile(target, team.toJson())
        return target
    }

    /** Deletes a team ([name] as for [loadTeam]); false if it did not exist. */
    fun deleteTeam(name: String): Boolean {
        val file = teamFileOf(name)
        return file.isFile && file.delete()
    }

    /**
     * The stem (as [listTeams] returns it) of the saved team that saving a team called [teamName] would overwrite,
     * or null when that file does not exist yet. Windows file names ignore case, so this does too.
     */
    fun teamFileTaken(teamName: String): String? {
        val wanted = teamFileStem(teamName)
        return listTeams().firstOrNull { it.equals(wanted, ignoreCase = true) }
    }

    private fun teamFiles(): List<File> =
        teamsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) } ?: emptyList()

    private fun stem(file: File): String = file.name.dropLast(".json".length)

    /** The file whose stem is exactly [stem], if it exists. */
    private fun existingTeamFile(stem: String): File? = teamFiles().firstOrNull { stem(it) == stem }

    /** A listed stem names its file as is (even "x." or " y"); any other name goes through [teamFileStem]. */
    private fun teamFileOf(name: String): File = existingTeamFile(name) ?: teamFile(name)

    private fun teamFile(teamName: String): File = File(teamsDir, teamFileStem(teamName) + ".json")

    /**
     * A random but valid team of [size] members (clamped to team.min..team.max): random species
     * (respecting the species clause), random allowed ability, random learnable moves (at least one damaging
     * move when possible), random item if held items are enabled (respecting the item clause), default level,
     * and a random valid stat point spread when stat points are enabled.
     */
    fun randomTeam(random: Random, size: Int = rules.teamMax, name: String = "Random team"): Team {
        val count = size.coerceIn(rules.teamMin, maxOf(rules.teamMin, rules.teamMax))
        // Only species that can form a valid member (at least one existing learnable move).
        val pool = dex.all().filter { sp -> sp.moves.any { moves[it] != null } }
        if (pool.isEmpty()) return Team(name, emptyList())
        var best: Team? = null
        var bestProblems = Int.MAX_VALUE
        repeat(25) {
            val team = buildRandomTeam(random, count, name, pool)
            val problems = team.validate(this).size
            if (problems == 0) return team
            if (problems < bestProblems) {
                best = team
                bestProblems = problems
            }
        }
        return best!!
    }

    private fun buildRandomTeam(random: Random, count: Int, name: String, pool: List<Species>): Team {
        val picked = if (rules.speciesClause) pool.shuffled(random).take(count) else List(count) { pool.random(random) }
        val allItems = if (rules.heldItems) items.all() else emptyList()
        val itemQueue = allItems.shuffled(random).toMutableList()
        val members = picked.map { sp ->
            val item = when {
                allItems.isEmpty() -> null
                rules.itemClause -> itemQueue.removeFirstOrNull()?.id
                else -> allItems.random(random).id
            }
            Bakemon(
                species = sp.id,
                level = rules.levelDefault,
                ability = sp.abilities.filter { abilities[it] != null }.randomOrNull(random),
                item = item,
                moves = randomMoves(sp, random),
                points = randomPoints(random),
            )
        }
        return Team(name, members)
    }

    private fun randomMoves(species: Species, random: Random): List<String> {
        val learnable = species.moves.distinct().filter { moves[it] != null }
        val chosen = learnable.shuffled(random).take(minOf(rules.maxMoves, learnable.size)).toMutableList()
        val damaging = learnable.filter { isDamaging(moves.require(it)) }
        if (chosen.isNotEmpty() && damaging.isNotEmpty() && chosen.none { it in damaging }) {
            chosen[random.nextInt(chosen.size)] = damaging.random(random)
        }
        return chosen
    }

    private fun randomPoints(random: Random): Map<String, Int> {
        if (!rules.statPointsEnabled || rules.statPointsMax <= 0) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        val ids = rules.stats.map { it.id }
        var remaining = rules.statPointsTotal
        val chunk = maxOf(1, rules.statPointsMax / 4)
        var guard = 0
        while (remaining > 0 && guard++ < 10_000) {
            val open = ids.filter { (result[it] ?: 0) < rules.statPointsMax }
            if (open.isEmpty()) break
            val stat = open.random(random)
            val current = result[stat] ?: 0
            val add = minOf(remaining, rules.statPointsMax - current, 1 + random.nextInt(chunk))
            result[stat] = current + add
            remaining -= add
        }
        return result
    }

    /** True if moves of this category deal damage and the move has a power. */
    fun isDamaging(move: MoveDef): Boolean = move.power != null && rules.categories[move.category]?.dealsDamage == true

    companion object {
        /**
         * Loads every file of [dir] and cross-validates them: types/abilities/moves referenced by species exist,
         * base stats cover Rules.stats, move categories/types exist, status ids used by effects exist,
         * stat ids used by statStage/statModifiers exist, fallbackMove exists, every formula only uses variables
         * and functions valid for its [FormulaScope], trigger events are valid for their owner, etc.
         *
         * Throws util.SetupException whose message lists every error (one per line) if anything is wrong.
         * Warnings are kept in [Setup.warnings].
         */
        fun load(dir: File): Setup {
            val (setup, report) = check(dir)
            if (setup == null) {
                throw SetupException(buildString {
                    append("Setup '${dir.absoluteFile.normalize().name}' has ${report.errors.size} error(s):")
                    report.errors.forEach { append('\n').append(it) }
                })
            }
            return setup
        }

        /**
         * Like [load] but never throws for problems of the setup: returns the loaded setup (null when there are errors)
         * together with the full report, so errors and warnings can be shown together (e.g. by the setup creator).
         */
        fun check(dir: File): Pair<Setup?, LoadReport> {
            val folder = dir.absoluteFile.normalize()
            val name = folder.name
            val report = LoadReport()
            if (!folder.isDirectory) {
                report.error("Setup folder not found: ${folder.path}")
                return null to report
            }
            val rules = attempt(report, "Rules.json") { Rules.load(File(folder, "Rules.json"), report) }
            val types = attempt(report, "Types.json") { TypeChart.load(File(folder, "Types.json"), report) }
            val dex = Bakedex.load(File(folder, "Bakemons.json"), report)
            val moves = MoveManager.load(File(folder, "Moves.json"), report)
            val abilities = AbilityManager.load(File(folder, "Abilities.json"), report)
            val items = ItemManager.load(File(folder, "Items.json"), report)
            val statuses = StatusManager.load(File(folder, "Statuses.json"), report)
            val fields = FieldManager.load(File(folder, "Fields.json"), report)

            SetupValidator(report, folder, rules, types, dex, moves, abilities, items, statuses, fields).run()

            if (!report.ok || rules == null || types == null) return null to report
            return Setup(name, folder, rules, types, dex, moves, abilities, items, statuses, report.warnings.toList(), fields) to report
        }

        private fun <T> attempt(report: LoadReport, fileName: String, block: () -> T): T? = try {
            block()
        } catch (e: SetupException) {
            report.error(e.message ?: "$fileName: invalid file")
            null
        } catch (e: Exception) {
            report.error("$fileName: ${e.message ?: e.toString()}")
            null
        }

        /** Sub-folders of [root] that contain a Rules.json, sorted by name. */
        fun discover(root: File): List<File> {
            val children = root.listFiles() ?: return emptyList()
            return children
                .filter { it.isDirectory && File(it, "Rules.json").isFile }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

        /**
         * Finds the folder that contains the setups:
         * [explicit] if given; else ./Setups; else walks up (max 6 levels) from the folder of the
         * running jar looking for a Setups folder. Returns null if nothing exists.
         */
        fun findSetupsRoot(explicit: String?): File? {
            try {
                if (!explicit.isNullOrBlank()) {
                    val f = File(explicit).absoluteFile
                    return if (f.isDirectory) f else null
                }
                val local = File("Setups").absoluteFile
                if (local.isDirectory) return local
                val location = try {
                    Setup::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { File(it) }
                } catch (e: Exception) {
                    null
                } ?: return null
                var current: File? = if (location.isFile) location.parentFile else location
                var level = 0
                while (current != null && level <= 6) {
                    val f = File(current, "Setups")
                    if (f.isDirectory) return f.absoluteFile
                    current = current.parentFile
                    level++
                }
            } catch (e: Exception) {
                // Never throw: an unreadable location simply means "not found".
            }
            return null
        }

        /**
         * Checks [formula] against [scope]: returns one message per unknown variable or function
         * ("<where>: unknown variable 'user.atkk' in formula '...'"), per battle function called with the wrong number
         * of arguments ("<where>: hasType() takes 2 arguments but got 1 in formula '...'") and per random function in a
         * stat formula or a statModifiers formula. [where] prefixes the messages.
         * Stat ids come from [rules]; [statusContext] adds `stacks`/`turnsLeft` (formulas of statuses and fields).
         * With [fields] (and [types]), a field id (type id) written as a string literal in a field function
         * (typeMultiplier) must exist: "fieldActive('rian')" is reported.
         */
        fun checkFormula(
            formula: Formula,
            scope: FormulaScope,
            rules: Rules,
            where: String,
            statusContext: Boolean = false,
            fields: FieldManager? = null,
            types: TypeChart? = null,
        ): List<String> {
            val stats = rules.stats.map { it.id }.toSet()
            val problems = mutableListOf<String>()
            for (name in formula.variables().sorted()) {
                if (FormulaNames.allowsVariable(name, scope, stats, statusContext)) continue
                val hint = when {
                    scope == FormulaScope.STAT -> " (available here: base, level, points)"
                    scope == FormulaScope.STAGE -> " (available here: stage)"
                    else -> LoadSupport.suggest(name, FormulaNames.candidates(scope, stats, statusContext)).ifEmpty {
                        if (FormulaNames.knownAnywhere(name, stats)) " (not available in this formula)" else ""
                    }
                }
                problems += "$where: unknown variable '$name' in formula '$formula'$hint"
            }
            val functions = FormulaNames.functions(scope)
            for (name in formula.functions().sorted()) {
                if (name in functions) {
                    // Built-in arities are checked when parsing; battle functions are checked here, not mid-battle.
                    val arities = BattleFunctions.ARITIES[name] ?: continue
                    for (count in formula.functionCalls()[name].orEmpty().sorted()) {
                        if (count !in arities) problems += "$where: ${BattleFunctions.arityProblem(name, count)} in formula '$formula'"
                    }
                    continue
                }
                if (name in FormulaNames.RANDOM_FUNCTIONS && FormulaNames.withoutDice(scope)) {
                    problems += if (scope == FormulaScope.STAT) {
                        "$where: $name() cannot be used in a stat formula (a monster's stats must be the same " +
                            "every time they are computed) in formula '$formula'"
                    } else if (scope == FormulaScope.BLOCK_MOVE) {
                        "$where: $name() cannot be used in a blockMove rule (it is checked whenever the usable moves are " +
                            "listed: the battle screen and the AI read it too) in formula '$formula'"
                    } else {
                        "$where: $name() cannot be used in statModifiers (an effective stat must be the same every time " +
                            "it is read: the turn order preview, the battle screen and the AI read it too) in formula '$formula'"
                    }
                    continue
                }
                val hint = LoadSupport.suggest(name, functions).ifEmpty {
                    if (name in FormulaNames.BATTLE_FUNCTIONS) " (not available in this formula)" else ""
                }
                problems += "$where: unknown function '$name' in formula '$formula'$hint"
            }
            if (fields != null) {
                for ((function, index) in BattleFunctions.FIELD_ID_ARGUMENTS) {
                    for ((i, id) in formula.stringArguments(function)) {
                        if (i != index || fields[id] != null) continue
                        problems += "$where: unknown field '$id' in $function() in formula '$formula'${LoadSupport.suggest(id, fields.fields.keys)}"
                    }
                }
            }
            if (types != null) {
                for ((i, id) in formula.stringArguments("typeMultiplier")) {
                    if (i != 0 || types.types.containsKey(id)) continue
                    problems += "$where: unknown type '$id' in typeMultiplier() in formula '$formula'${LoadSupport.suggest(id, types.types.keys)}"
                }
            }
            for ((i, kind) in formula.stringArguments("statusCount")) {
                if (i != 1 || StatusKind.fromId(kind) != null) continue
                val kinds = StatusKind.entries.map { it.id }
                problems += "$where: unknown status kind '$kind' in statusCount() in formula '$formula'" +
                    LoadSupport.suggest(kind, kinds).ifEmpty { " (use one of: ${kinds.joinToString(", ")})" }
            }
            return problems
        }

        /**
         * Problems of a formula that has no `recipient` (a trigger condition or chance, a duration, the value of an
         * effect without a target): one message per use of [FormulaNames.EFFECT_ONLY_REFS]. [place] names the formula.
         */
        fun recipientProblems(formula: Formula, where: String, place: String): List<String> =
            formula.variables().filter { it.substringBefore('.') in FormulaNames.EFFECT_ONLY_REFS }.sorted().map {
                "$where: '$it' has no value in $place ('recipient' is the monster an effect is applied to: it only exists in " +
                    "the values of effects that have a target) in formula '$formula'"
            }

        private val RESERVED_FILE_NAMES = setOf(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
        )

        /** Longest file name stem a team is saved under (Windows allows 255 characters per name, with ".json"). */
        const val MAX_TEAM_FILE_STEM = 100

        /**
         * Team name -> the file name (without .json) it is saved under: characters invalid on Windows become '_',
         * spaces and trailing dots are trimmed, reserved names get a '_', and long names are cut to
         * [MAX_TEAM_FILE_STEM] characters. Never empty. Different names can map to the same file (compare stems).
         */
        fun teamFileStem(teamName: String): String = safeFileName(teamName)

        internal fun safeFileName(teamName: String): String {
            val replaced = teamName.map { c -> if (c in "\\/:*?\"<>|" || c.code < 32) '_' else c }.joinToString("")
            val trimmed = clean(clean(replaced).take(MAX_TEAM_FILE_STEM))
            return when {
                trimmed.isEmpty() -> "Team"
                trimmed.lowercase() in RESERVED_FILE_NAMES -> trimmed + "_"
                else -> trimmed
            }
        }

        /** Windows drops trailing dots and spaces from file names: remove them (and leading spaces) up front. */
        private fun clean(name: String): String = name.trimStart().trimEnd { it == '.' || it.isWhitespace() }
    }
}

/** The variable and function names each [FormulaScope] accepts (README "Formulas"). */
internal object FormulaNames {
    val BATTLE_VARS = setOf("turn", "allies", "enemies")
    val MONSTER_REFS = setOf("self", "other", "user", "target")
    /** References that only exist in effect values: the monster the effect is applied to. */
    val EFFECT_ONLY_REFS = setOf("recipient")
    val MONSTER_FIELDS = setOf(
        "hp", "maxHp", "hpPercent", "level", "gauge", "shield", "fainted", "revives", "reviveBlocked", "acted", "charging", "id", "name",
    )
    /** The hit in progress: move power and damage / hit type formulas (and the hit events, see EventType.EVENT_VARIABLES). */
    val HIT_COUNTERS = setOf("hitIndex", "hitCount")
    val STAT_GROUPS = setOf("raw", "base", "stage")
    val MOVE_FIELDS = setOf("id", "name", "type", "category", "power", "accuracy", "priority", "pp", "cooldown")
    val DAMAGE_VARS = setOf("attack", "defense", "effectiveness", "sameType", "crit", "isCrit", "targets", "hitType") + HIT_COUNTERS
    /** Rules.hitTypes formulas: the damage formula's variables except those that depend on the hit type's outcome. */
    val HIT_TYPE_VARS = DAMAGE_VARS - setOf("attack", "defense", "hitType")
    /** Hit chance and crit formulas: what is known about the target before the hit (type matchup). */
    val HIT_VARS = setOf("effectiveness", "sameType")
    val STATUS_VARS = setOf("stacks", "turnsLeft")
    /** A field's blockStatus rule: the status being applied. */
    val BLOCK_STATUS_VARS = setOf("status", "statusKind")
    /** A field's blockStatStage rule: the stage change being made. */
    val BLOCK_STAT_STAGE_VARS = setOf("stat", "stages")
    val EVENT_VARS: Set<String> get() = EventType.EVENT_VARIABLES.values.flatten().toSet()
    val BATTLE_FUNCTIONS: Set<String> = BattleFunctions.ARITIES.keys
    /**
     * Built-ins that roll dice: not allowed in stat formulas (stats are computed outside the battle's seeded random)
     * nor in statModifiers (effective stats are read by previews, the UI and the AI, which must not roll the battle's dice).
     */
    val RANDOM_FUNCTIONS = setOf("random", "randint", "chance")

    /** Scopes whose formulas may not use [RANDOM_FUNCTIONS]. */
    fun withoutDice(scope: FormulaScope): Boolean =
        scope == FormulaScope.STAT || scope == FormulaScope.MODIFIER || scope == FormulaScope.STATUS_MODIFIER ||
            scope == FormulaScope.BLOCK_MOVE

    private fun isBattle(scope: FormulaScope) = scope != FormulaScope.STAT && scope != FormulaScope.STAGE

    private fun refs(scope: FormulaScope): Set<String> = when (scope) {
        FormulaScope.TRIGGER -> MONSTER_REFS + EFFECT_ONLY_REFS
        FormulaScope.DAMAGE, FormulaScope.HIT_TYPE, FormulaScope.HIT, FormulaScope.MOVE,
        FormulaScope.BLOCK_MOVE, FormulaScope.BLOCK_STATUS, FormulaScope.BLOCK_STAT_STAGE -> MONSTER_REFS
        FormulaScope.ACTION_BAR, FormulaScope.MODIFIER, FormulaScope.STATUS_MODIFIER -> setOf("self")
        FormulaScope.STAT, FormulaScope.STAGE -> emptySet()
    }

    private fun hasMove(scope: FormulaScope) = when (scope) {
        FormulaScope.DAMAGE, FormulaScope.HIT_TYPE, FormulaScope.HIT, FormulaScope.MOVE, FormulaScope.TRIGGER,
        FormulaScope.BLOCK_MOVE -> true
        else -> false
    }

    /** Plain (non-monster, non-move) names of a scope; [statusContext] adds stacks / turnsLeft (statuses, fields). */
    private fun extras(scope: FormulaScope, statusContext: Boolean): Set<String> {
        val base = when (scope) {
            FormulaScope.STAT -> return setOf("base", "level", "points")
            FormulaScope.STAGE -> return setOf("stage")
            FormulaScope.DAMAGE -> BATTLE_VARS + DAMAGE_VARS
            FormulaScope.HIT_TYPE -> BATTLE_VARS + HIT_TYPE_VARS
            FormulaScope.HIT -> BATTLE_VARS + HIT_VARS
            FormulaScope.MOVE -> BATTLE_VARS + HIT_COUNTERS
            FormulaScope.BLOCK_MOVE -> BATTLE_VARS
            FormulaScope.ACTION_BAR -> BATTLE_VARS + "speed"
            FormulaScope.MODIFIER -> BATTLE_VARS
            FormulaScope.STATUS_MODIFIER -> BATTLE_VARS + STATUS_VARS
            FormulaScope.TRIGGER -> BATTLE_VARS + EVENT_VARS
            FormulaScope.BLOCK_STATUS -> BATTLE_VARS + BLOCK_STATUS_VARS
            FormulaScope.BLOCK_STAT_STAGE -> BATTLE_VARS + BLOCK_STAT_STAGE_VARS
        }
        return if (statusContext) base + STATUS_VARS else base
    }

    fun functions(scope: FormulaScope): Set<String> {
        val all = if (isBattle(scope)) Formula.BUILTIN_FUNCTIONS + BATTLE_FUNCTIONS else Formula.BUILTIN_FUNCTIONS
        return if (withoutDice(scope)) all - RANDOM_FUNCTIONS else all
    }

    fun allowsVariable(name: String, scope: FormulaScope, stats: Set<String>, statusContext: Boolean): Boolean {
        if (name in extras(scope, statusContext)) return true
        val parts = name.split('.')
        val head = parts[0]
        if (head in refs(scope)) return isMonsterField(parts.drop(1), stats)
        if (head == "move" && hasMove(scope)) return parts.size == 2 && parts[1] in MOVE_FIELDS
        return false
    }

    private fun isMonsterField(rest: List<String>, stats: Set<String>): Boolean = when (rest.size) {
        0 -> true
        1 -> rest[0] in MONSTER_FIELDS || rest[0] in stats
        2 -> rest[0] in STAT_GROUPS && rest[1] in stats
        else -> false
    }

    /** True if [name] is valid in at least one scope (used to say "not available here" instead of "typo"). */
    fun knownAnywhere(name: String, stats: Set<String>): Boolean =
        FormulaScope.entries.any { allowsVariable(name, it, stats, statusContext = true) }

    /** Every accepted name of a scope (for "did you mean" hints). */
    fun candidates(scope: FormulaScope, stats: Set<String>, statusContext: Boolean): List<String> {
        val result = mutableListOf<String>()
        result += extras(scope, statusContext)
        for (ref in refs(scope)) {
            result += ref
            (MONSTER_FIELDS + stats).forEach { result += "$ref.$it" }
            for (group in STAT_GROUPS) stats.forEach { result += "$ref.$group.$it" }
        }
        if (hasMove(scope)) MOVE_FIELDS.forEach { result += "move.$it" }
        return result
    }
}

/** Helpers shared by the JSON loaders of this package. */
internal object LoadSupport {
    val TRIGGER_KEYS = setOf("on", "condition", "chance", "message", "limit", "effects")
    val EFFECT_KEYS = setOf(
        "type", "target", "amount", "status", "chance", "duration", "stacks", "kind", "stat", "stages", "value", "message", "text",
        "field", "group", "side", "actor", "move", "moveTarget", "power", "free", "to", "count", "mode", "turns", "attachTo",
        "effects", "moveType", "category",
    )

    /** Keys each effect type uses besides "type" (README "Effects"). */
    private val EFFECT_KEYS_BY_TYPE: Map<String, Set<String>> = mapOf(
        "damage" to setOf("target", "amount"),
        "heal" to setOf("target", "amount"),
        "applyStatus" to setOf("target", "status", "chance", "duration", "stacks", "message"),
        "removeStatus" to setOf("target", "status", "kind"),
        "statStage" to setOf("target", "stat", "stages"),
        "multiplyDamage" to setOf("value"),
        "cancelAction" to setOf("message"),
        "gauge" to setOf("target", "amount"),
        "shield" to setOf("target", "amount"),
        "cooldown" to setOf("target", "amount"),
        "consumeItem" to setOf("target", "message"),
        "message" to setOf("text"),
        "setField" to setOf("field", "side", "duration", "stacks", "chance", "message"),
        "removeField" to setOf("field", "group", "side", "message"),
        "revive" to setOf("target", "amount", "message"),
        "preventRevive" to setOf("target", "message"),
        "useMove" to setOf("actor", "move", "moveTarget", "power", "free", "message"),
        "extraTurn" to setOf("target"),
        "transferStatus" to setOf("target", "to", "status", "kind", "count", "mode"),
        "copyStages" to setOf("target", "to"),
        "setHp" to setOf("target", "amount"),
        "balanceHp" to setOf("target"),
        "swapHp" to setOf("target"),
        "forceSwitch" to setOf("target"),
        "switchOut" to setOf("target"),
        "delay" to setOf("turns", "attachTo", "effects", "message"),
        "attack" to setOf("target", "power", "moveType", "category"),
    )

    /** Placeholders of trigger messages and effect messages (filled from the trigger's event). */
    val MESSAGE_PLACEHOLDERS = setOf("self", "other", "user", "target", "move", "damage", "amount", "status", "ability", "item", "field")
    /** Placeholders of a status's applyMessage / removeMessage ({user}/{other} = who applied it). */
    val STATUS_MESSAGE_PLACEHOLDERS = setOf("target", "self", "other", "user", "status", "stacks")
    /** Placeholders of the Types.json messages (strong / weak / immune). */
    val TYPE_MESSAGE_PLACEHOLDERS = setOf("target")
    /** Placeholders of a field's startMessage / endMessage ({user} = who set / removed it). */
    val FIELD_MESSAGE_PLACEHOLDERS = setOf("field", "side", "user", "stacks")
    /** Placeholders of a field's blockMoveMessage. */
    val BLOCK_MOVE_PLACEHOLDERS = setOf("user", "move", "target", "field")
    /** Placeholders of a field's blockStatusMessage. */
    val BLOCK_STATUS_PLACEHOLDERS = setOf("self", "target", "other", "status", "field")
    /** Placeholders of a field's blockStatStageMessage. */
    val BLOCK_STAT_STAGE_PLACEHOLDERS = setOf("self", "target", "other", "stat", "field")
    private val ALL_PLACEHOLDERS = MESSAGE_PLACEHOLDERS + STATUS_MESSAGE_PLACEHOLDERS + TYPE_MESSAGE_PLACEHOLDERS +
        FIELD_MESSAGE_PLACEHOLDERS + BLOCK_MOVE_PLACEHOLDERS + BLOCK_STATUS_PLACEHOLDERS + BLOCK_STAT_STAGE_PLACEHOLDERS
    private val PLACEHOLDER = Regex("\\{([^{}]*)\\}")

    /** What to do when a list file does not exist. */
    enum class Missing { ERROR, WARN, SILENT }

    /** Reads and parses [file] (UTF-8, BOM ignored). Syntax errors become SetupException. */
    fun readJson(file: File): Any? {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw SetupException("${file.name}: cannot read the file (${e.message})")
        }
        return try {
            Json.parse(text.removePrefix("﻿"), file.name)
        } catch (e: JsonParseException) {
            val message = e.message ?: "invalid JSON"
            throw SetupException(if (message.contains(file.name)) message else "${file.name}: $message")
        }
    }

    /**
     * Loads a list file: a bare array or an object with [listKey]. Each entry needs a unique string "id";
     * [parse] receives the entry with a path like "Moves.json: moves[3] (ember)". Bad entries are reported
     * and skipped. [kind] keys [LoadReport.locations] / [LoadReport.failed].
     */
    fun <T> loadList(
        file: File,
        report: LoadReport,
        listKey: String,
        kind: String,
        missing: Missing,
        parse: (JsonObj) -> T,
    ): LinkedHashMap<String, T> {
        if (!file.isFile) {
            when (missing) {
                Missing.ERROR -> report.error("${file.name}: file not found")
                Missing.WARN -> report.warn("${file.name}: file not found (this setup has no $listKey)")
                Missing.SILENT -> {}
            }
            return LinkedHashMap()
        }
        // When the whole file is unusable, references to its ids are not reported one by one.
        fun unusable(message: String): LinkedHashMap<String, T> {
            report.error(message)
            report.failed += "$kind:$ANY_ID"
            return LinkedHashMap()
        }
        val root = try {
            readJson(file)
        } catch (e: SetupException) {
            return unusable(e.message ?: "${file.name}: invalid JSON")
        }
        val shapeError = "${file.name}: expected a JSON array, or an object with a \"$listKey\" array"
        return when (root) {
            is List<*> -> parseEntries(root, "${file.name}: ", "", kind, report, parse)
            is Map<*, *> -> {
                val obj = JsonObj.of(root, file.name)
                warnUnknownKeys(obj, setOf(listKey), report)
                val list = obj.raw(listKey)
                if (list is List<*>) parseEntries(list, "${file.name}: $listKey", listKey, kind, report, parse) else unusable(shapeError)
            }
            else -> unusable(shapeError)
        }
    }

    /** Id used in [LoadReport.failed] ("<kind>:*") when a whole file could not be read. */
    const val ANY_ID = "*"

    /** Parses the entries of a list whose element paths are "$base[i]" ("$shortBase[i]" in duplicate messages). */
    fun <T> parseEntries(
        list: List<*>,
        base: String,
        shortBase: String,
        kind: String,
        report: LoadReport,
        parse: (JsonObj) -> T,
    ): LinkedHashMap<String, T> {
        val result = LinkedHashMap<String, T>()
        val seen = HashMap<String, String>()
        val fileName = base.substringBefore(':')
        for ((i, value) in list.withIndex()) {
            val entryPath = "$base[$i]"
            if (value !is Map<*, *>) {
                report.error("$entryPath: expected an object")
                continue
            }
            val id = value["id"]
            if (id !is String || id.isBlank()) {
                report.error("$entryPath: missing \"id\" (a non-empty string)")
                continue
            }
            val path = "$entryPath ($id)"
            val first = seen[id]
            if (first != null) {
                report.error("$path: duplicate id '$id' (already used by $first)")
                continue
            }
            seen[id] = "$shortBase[$i]"
            report.locations["$kind:$id"] = path
            try {
                result[id] = parse(JsonObj.of(value, path))
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                report.error(if (e is SetupException && message.contains(fileName)) message else "$path: $message")
                report.failed += "$kind:$id"
            }
        }
        return result
    }

    /** Adds a warning for every key of [obj] not in [allowed], with a "did you mean" hint when close. */
    fun warnUnknownKeys(obj: JsonObj, allowed: Set<String>, report: LoadReport) {
        for (key in obj.unknownKeys(allowed)) report.warn("${obj.path}: unknown key '$key'${suggest(key, allowed)}")
    }

    /** Warns about unknown keys in the raw trigger and effect objects under obj[[key]] ([extraKeys]: more trigger keys). */
    fun warnTriggerKeys(obj: JsonObj, report: LoadReport, key: String = "triggers", extraKeys: Set<String> = emptySet()) {
        val triggers = obj.raw(key) as? List<*> ?: return
        val allowedKeys = TRIGGER_KEYS + extraKeys
        for ((i, trigger) in triggers.withIndex()) {
            if (trigger !is Map<*, *>) continue
            val triggerPath = "${obj.pathOf(key)}[$i]"
            for (k in trigger.keys) {
                if (k is String && k !in allowedKeys) report.warn("$triggerPath: unknown key '$k'${suggest(k, allowedKeys)}")
            }
            val effects = trigger["effects"] as? List<*> ?: continue
            warnEffectKeys(effects, "$triggerPath.effects", report)
        }
    }

    /** Warns about unknown keys in the raw effect objects of [effects] (and in the effects of a delay, recursively). */
    private fun warnEffectKeys(effects: List<*>, path: String, report: LoadReport) {
        for ((j, effect) in effects.withIndex()) {
            if (effect !is Map<*, *>) continue
            val effectPath = "$path[$j]"
            val type = effect["type"] as? String
            val typeKey = EFFECT_KEYS_BY_TYPE.keys.firstOrNull { it.equals(type, ignoreCase = true) }
            for (k in effect.keys) {
                if (k !is String || k == "type") continue
                if (typeKey != null) {
                    val allowed = EFFECT_KEYS_BY_TYPE.getValue(typeKey)
                    if (k !in allowed) {
                        val hint = suggest(k, allowed).ifEmpty { " (\"$typeKey\" uses: ${allowed.joinToString(", ")})" }
                        report.warn("$effectPath: unknown key '$k' for effect '$typeKey'$hint")
                    }
                } else if (k !in EFFECT_KEYS) {
                    report.warn("$effectPath: unknown key '$k'${suggest(k, EFFECT_KEYS)}")
                }
            }
            if (typeKey == "delay") (effect["effects"] as? List<*>)?.let { warnEffectKeys(it, "$effectPath.effects", report) }
        }
    }

    /** Reads an optional "statModifiers" object: stat id -> multiplier formula (keys are checked by Setup). */
    fun statModifiers(obj: JsonObj, key: String = "statModifiers"): Map<String, Formula> {
        val mods = obj.objOrNull(key) ?: return emptyMap()
        val result = LinkedHashMap<String, Formula>()
        for (stat in mods.keys) result[stat] = mods.formula(stat)
        return result
    }

    /** Throws a SetupException located at [key] of [obj], in the same format as the JsonObj accessors. */
    fun failAt(obj: JsonObj, key: String, message: String): Nothing = throw SetupException("${obj.pathOf(key)}: $message")

    /**
     * Warns about `{placeholders}` in [text] that this kind of message does not fill ([allowed]): unknown names, and
     * known names that would always print as an empty string here (e.g. `{move}` in a status's applyMessage, or
     * `{damage}` in a turnEnd trigger). [place] names the kind of message in the warning.
     */
    fun checkPlaceholders(
        text: String?,
        where: String,
        report: LoadReport,
        allowed: Set<String> = MESSAGE_PLACEHOLDERS,
        place: String = "this kind of message",
    ) {
        if (text == null) return
        for (match in PLACEHOLDER.findAll(text)) {
            val name = match.groupValues[1]
            if (name in allowed) continue
            val available = allowed.joinToString(", ") { "{$it}" }
            if (name in ALL_PLACEHOLDERS) {
                report.warn("$where: placeholder '{$name}' is not filled in $place (available here: $available)")
            } else {
                report.warn("$where: unknown placeholder '{$name}'${suggest(name, allowed)}")
            }
        }
    }

    /** " (did you mean 'x'?)" when [name] is close to one of [candidates], else "". */
    fun suggest(name: String, candidates: Collection<String>): String {
        val lower = name.lowercase()
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (c in candidates) {
            val d = distance(lower, c.lowercase())
            if (d < bestDistance) {
                best = c
                bestDistance = d
            }
        }
        val limit = if (name.length <= 4) 1 else 2
        return if (best != null && bestDistance <= limit && best != name) " (did you mean '$best'?)" else ""
    }

    /** Levenshtein distance. */
    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}

/** Cross-file validation of a loaded setup (everything that needs more than one file). */
private class SetupValidator(
    private val report: LoadReport,
    private val folder: File,
    private val rules: Rules?,
    private val types: TypeChart?,
    private val dex: Bakedex,
    private val moves: MoveManager,
    private val abilities: AbilityManager,
    private val items: ItemManager,
    private val statuses: StatusManager,
    private val fields: FieldManager,
) {
    /** Stats and categories exist, so stat/category/formula checks make sense (otherwise they only add noise). */
    private val rulesUsable = rules != null && rules.stats.isNotEmpty() && rules.categories.isNotEmpty()
    private val statIds: Set<String> = rules?.stats?.map { it.id }?.toSet() ?: emptySet()
    private val moveEventIds = EventType.MOVE_EVENTS.joinToString(", ") { it.id }
    /** Field ids known to formula checks (null when Fields.json could not be read at all: no literal checks). */
    private val fieldIds: FieldManager? = fields.takeIf { "field:${LoadSupport.ANY_ID}" !in report.failed }

    /** Species whose image file does not exist (one summary warning, images are optional). */
    private val missingImages = mutableListOf<Species>()

    fun run() {
        checkRules()
        // No species at all (and no Bakemons.json error explaining why): no team can be built, every battle would be empty.
        if (dex.all().isEmpty() && report.errors.none { it.startsWith("Bakemons.json") }) {
            report.error("Bakemons.json: the Bakedex is empty (add at least one species)")
        }
        dex.all().forEach(::checkSpecies)
        moves.all().forEach(::checkMove)
        abilities.all().forEach { checkHolder("ability", it.id, "Abilities.json", it.statModifiers, it.triggers) }
        items.all().forEach { checkHolder("item", it.id, "Items.json", it.statModifiers, it.triggers) }
        statuses.all().forEach(::checkStatus)
        fields.all().forEach(::checkField)
        reportMissingImages()
    }

    private fun reportMissingImages() {
        val first = missingImages.firstOrNull() ?: return
        if (missingImages.size == 1) {
            report.warn("${where("species", first.id, "Bakemons.json")}: image '${first.image}' not found")
        } else {
            val examples = missingImages.take(3).joinToString(", ") { "'${it.image}'" }
            val more = if (missingImages.size > 3) ", ..." else ""
            report.warn("Bakemons.json: ${missingImages.size} image files not found ($examples$more)")
        }
    }

    private fun where(kind: String, id: String, file: String) = report.locations["$kind:$id"] ?: "$file: $id"

    /** True if [id] of [kind] is unknown and was not already reported as a broken entry. */
    private fun missing(kind: String, id: String, known: Boolean) =
        !known && "$kind:$id" !in report.failed && "$kind:${LoadSupport.ANY_ID}" !in report.failed

    private fun formula(f: Formula?, scope: FormulaScope, where: String, statusContext: Boolean = false) {
        if (f == null || !rulesUsable) return
        Setup.checkFormula(f, scope, rules!!, where, statusContext, fieldIds, types).forEach(report::error)
        checkFieldScopes(f, where)
    }

    /** Warns when a global-field function names a side field, or a side-field function a global field. */
    private fun checkFieldScopes(f: Formula, where: String) {
        for ((function, index) in BattleFunctions.FIELD_ID_ARGUMENTS) {
            for ((i, id) in f.stringArguments(function)) {
                val def = fields[id] ?: continue
                if (i != index) continue
                if (function in BattleFunctions.GLOBAL_FIELD_FUNCTIONS && !def.isGlobal) {
                    report.warn("$where: $function('$id') always reads 0 because '$id' is a side field (use side${function.replaceFirstChar { it.uppercase() }}(m, '$id')) in formula '$f'")
                } else if (function in BattleFunctions.SIDE_FIELD_FUNCTIONS && def.isGlobal) {
                    report.warn("$where: $function(m, '$id') always reads 0 because '$id' is a global field in formula '$f'")
                }
            }
        }
    }

    private fun checkRules() {
        val r = rules ?: return
        for ((stat, f) in r.statFormulas) formula(f, FormulaScope.STAT, "Rules.json.statFormulas.$stat")
        formula(r.stageMultiplier, FormulaScope.STAGE, "Rules.json.statStages.multiplier")
        formula(r.damage, FormulaScope.DAMAGE, "Rules.json.damage")
        formula(r.hitChance, FormulaScope.HIT, "Rules.json.hitChance")
        formula(r.critChance, FormulaScope.HIT, "Rules.json.critChance")
        formula(r.critMultiplier, FormulaScope.HIT, "Rules.json.critMultiplier")
        for (type in r.hitTypes) {
            val p = where("hitType", type.id, "Rules.json.hitTypes")
            formula(type.chance, FormulaScope.HIT_TYPE, "$p.chance")
            formula(type.multiplier, FormulaScope.HIT_TYPE, "$p.multiplier")
            LoadSupport.checkPlaceholders(type.message, "$p.message", report, HIT_TYPE_PLACEHOLDERS, "a hit type message")
        }
        formula(r.actionBarFill, FormulaScope.ACTION_BAR, "Rules.json.actionBar.fill")
        formula(r.actionBarStart, FormulaScope.ACTION_BAR, "Rules.json.actionBar.start")
        val fallback = r.fallbackMove
        if (fallback != null && missing("move", fallback, moves[fallback] != null)) {
            report.error("Rules.json.fallbackMove: unknown move '$fallback'${LoadSupport.suggest(fallback, moves.moves.keys)}")
        }
        for ((i, start) in r.startFields.withIndex()) {
            val p = report.locations["startField:$i"] ?: "Rules.json.startFields[$i]"
            val def = fields[start.field]
            if (def == null) {
                if (missing("field", start.field, false)) {
                    report.error("$p: unknown field '${start.field}'${LoadSupport.suggest(start.field, fields.fields.keys)}")
                }
            } else if (def.isGlobal && start.side != null) {
                report.warn("$p.side: ignored because '${def.id}' is a global field")
            }
            start.duration?.let {
                formula(it, FormulaScope.TRIGGER, "$p.duration")
                eventVariables(it, "$p.duration", emptySet(), "a start field duration")
                noRecipient(it, "$p.duration", "a start field duration")
            }
        }
        val speciesCount = dex.species.size
        if (r.speciesClause && speciesCount in 1 until r.teamMin) {
            report.warn("Rules.json.team: the species clause needs ${r.teamMin} different species but Bakemons.json has only $speciesCount")
        }
    }

    private fun checkSpecies(sp: Species) {
        val p = where("species", sp.id, "Bakemons.json")
        if (types != null) {
            for (t in sp.types.distinct()) {
                if (missing("type", t, types.types.containsKey(t))) {
                    report.error("$p: unknown type '$t'${LoadSupport.suggest(t, types.types.keys)}")
                }
            }
        }
        warnDuplicates(sp.types, "$p.types", "type")
        for (a in sp.abilities.distinct()) {
            if (missing("ability", a, abilities[a] != null)) {
                report.error("$p: unknown ability '$a'${LoadSupport.suggest(a, abilities.abilities.keys)}")
            }
        }
        warnDuplicates(sp.abilities, "$p.abilities", "ability")
        if (sp.moves.isEmpty()) report.error("$p: has no moves (\"moves\" must list at least one learnable move)")
        for (m in sp.moves.distinct()) {
            if (missing("move", m, moves[m] != null)) {
                report.error("$p: unknown move '$m'${LoadSupport.suggest(m, moves.moves.keys)}")
            }
        }
        warnDuplicates(sp.moves, "$p.moves", "move")
        if (rulesUsable) {
            val missingStats = rules!!.stats.map { it.id }.filter { it !in sp.baseStats }
            if (missingStats.isNotEmpty()) {
                report.error("$p: baseStats is missing ${missingStats.joinToString(", ") { "'$it'" }} (every stat of Rules.json needs a base value)")
            }
            for (stat in sp.baseStats.keys) {
                if (stat !in statIds) report.warn("$p.baseStats: unknown stat '$stat'${LoadSupport.suggest(stat, statIds)} (ignored)")
            }
        }
        val image = sp.image
        if (image != null && !File(folder, image).isFile) missingImages += sp
    }

    private fun warnDuplicates(list: List<String>, where: String, what: String) {
        list.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach {
            report.warn("$where: $what '$it' is listed more than once")
        }
    }

    private fun checkMove(m: MoveDef) {
        val p = where("move", m.id, "Moves.json")
        val type = m.type
        if (type != null && types != null && missing("type", type, types.types.containsKey(type))) {
            report.error("$p: unknown type '$type'${LoadSupport.suggest(type, types.types.keys)}")
        }
        if (rules != null && rules.categories.isNotEmpty()) {
            val category = rules.categories[m.category]
            if (category == null) {
                val known = rules.categories.keys
                val hint = LoadSupport.suggest(m.category, known).ifEmpty { " (Rules.json categories: ${known.joinToString(", ")})" }
                report.error("$p: unknown category '${m.category}'$hint")
            } else if (m.power != null && !category.dealsDamage) {
                report.warn("$p: \"power\" is ignored because category '${m.category}' deals no damage")
            }
        }
        formula(m.power, FormulaScope.MOVE, "$p.power")
        formula(m.hits, FormulaScope.MOVE, "$p.hits")
        // The number of hits is decided before the first one.
        noHitCounters(m.hits, "$p.hits", "'hits' (the number of hits is decided before the first hit)")
        // Evaluated together with Rules.critChance, so it sees the same variables.
        formula(m.critBonus, FormulaScope.HIT, "$p.critBonus")
        m.ignoreDefense?.let { f ->
            formula(f, FormulaScope.MOVE, "$p.ignoreDefense")
            val value = f.constant
            if (value != null && (value < 0.0 || value > 100.0)) {
                report.warn("$p.ignoreDefense: $value is outside 0-100 (it is a percent of the defense; it counts as ${value.coerceIn(0.0, 100.0)})")
            }
        }
        if (m.hitAccuracy == HitAccuracy.EACH) {
            when {
                m.target == MoveTarget.RANDOM_ENEMIES ->
                    report.warn("$p.hitAccuracy: \"each\" changes nothing for target 'randomEnemies' (every hit already rolls)")
                m.accuracy == null -> report.warn("$p.hitAccuracy: \"each\" does nothing because the move never misses (no \"accuracy\")")
                m.hits.constant == 1.0 -> report.warn("$p.hitAccuracy: \"each\" does nothing for a move with a single hit")
            }
        }
        m.charge?.let { c ->
            formula(c.skipIf, FormulaScope.MOVE, "$p.charge.skipIf")
            c.skipIf?.let { noHitCounters(it, "$p.charge.skipIf", "'charge.skipIf' (it is decided before any hit)") }
            LoadSupport.checkPlaceholders(c.message, "$p.charge.message", report, CHARGE_PLACEHOLDERS, "a charge message")
        }
        checkTriggers(m.triggers, p, isMove = true, statusContext = false)
    }

    /** hitIndex / hitCount are 0 outside a hit: an error in formulas that are evaluated before the hits. */
    private fun noHitCounters(f: Formula, where: String, place: String) {
        if (!rulesUsable) return
        for (name in f.variables().sorted()) {
            if (name in FormulaNames.HIT_COUNTERS) report.error("$where: variable '$name' has no value in $place")
        }
    }

    /** `recipient` has no value in [f] (see [Setup.recipientProblems]). */
    private fun noRecipient(f: Formula?, where: String, place: String) {
        if (f == null || !rulesUsable) return
        Setup.recipientProblems(f, where, place).forEach(report::error)
    }

    private fun checkHolder(kind: String, id: String, file: String, mods: Map<String, Formula>, triggers: List<Trigger>) {
        val p = where(kind, id, file)
        checkModifiers(mods, p, FormulaScope.MODIFIER)
        checkTriggers(triggers, p, isMove = false, statusContext = false)
    }

    private fun checkModifiers(mods: Map<String, Formula>, p: String, scope: FormulaScope) {
        for ((stat, f) in mods) {
            if (rulesUsable && stat !in statIds) {
                report.error("$p.statModifiers: unknown stat '$stat'${LoadSupport.suggest(stat, statIds)}")
            } else if (rules != null && stat == rules.hpStat) {
                report.warn("$p.statModifiers.$stat: statModifiers do not change max HP or HP (the HP stat '$stat' comes from the stat formula only)")
            }
            formula(f, scope, "$p.statModifiers.$stat")
        }
    }

    private fun checkStatus(s: StatusDef) {
        val p = where("status", s.id, "Statuses.json")
        if (types != null) {
            for (t in s.immuneTypes) {
                if (missing("type", t, types.types.containsKey(t))) {
                    report.error("$p.immuneTypes: unknown type '$t'${LoadSupport.suggest(t, types.types.keys)}")
                }
            }
        }
        checkModifiers(s.statModifiers, p, FormulaScope.STATUS_MODIFIER)
        // The engine evaluates the default duration when the status is applied: stacks/turnsLeft, no event variables.
        s.duration?.let {
            formula(it, FormulaScope.TRIGGER, "$p.duration", statusContext = true)
            eventVariables(it, "$p.duration", emptySet(), "a status duration")
            noRecipient(it, "$p.duration", "a status duration")
        }
        LoadSupport.checkPlaceholders(s.applyMessage, "$p.applyMessage", report, LoadSupport.STATUS_MESSAGE_PLACEHOLDERS)
        LoadSupport.checkPlaceholders(s.removeMessage, "$p.removeMessage", report, LoadSupport.STATUS_MESSAGE_PLACEHOLDERS)
        checkTriggers(s.triggers, p, isMove = false, statusContext = true)
    }

    private fun checkField(f: FieldDef) {
        val p = where("field", f.id, "Fields.json")
        checkModifiers(f.statModifiers, p, FormulaScope.STATUS_MODIFIER)
        // Evaluated when the field is set: the setter as self/user, stacks/turnsLeft, no event variables.
        f.duration?.let {
            formula(it, FormulaScope.TRIGGER, "$p.duration", statusContext = true)
            eventVariables(it, "$p.duration", emptySet(), "a field duration")
            noRecipient(it, "$p.duration", "a field duration")
        }
        LoadSupport.checkPlaceholders(f.startMessage, "$p.startMessage", report, LoadSupport.FIELD_MESSAGE_PLACEHOLDERS, "a field message")
        LoadSupport.checkPlaceholders(f.endMessage, "$p.endMessage", report, LoadSupport.FIELD_MESSAGE_PLACEHOLDERS, "a field message")
        checkTriggers(f.triggers.map { it.trigger }, p, isMove = false, statusContext = true, holder = Holder.FIELD)
        val r = f.rules
        val rp = "$p.rules"
        formula(r.damage, FormulaScope.DAMAGE, "$rp.damage", statusContext = true)
        formula(r.hitChance, FormulaScope.HIT, "$rp.hitChance", statusContext = true)
        formula(r.critChance, FormulaScope.HIT, "$rp.critChance", statusContext = true)
        formula(r.critMultiplier, FormulaScope.HIT, "$rp.critMultiplier", statusContext = true)
        formula(r.actionBarFill, FormulaScope.ACTION_BAR, "$rp.actionBarFill", statusContext = true)
        formula(r.disableItems, FormulaScope.STATUS_MODIFIER, "$rp.disableItems")
        formula(r.disableAbilities, FormulaScope.STATUS_MODIFIER, "$rp.disableAbilities")
        formula(r.blockMove, FormulaScope.BLOCK_MOVE, "$rp.blockMove", statusContext = true)
        formula(r.blockStatus, FormulaScope.BLOCK_STATUS, "$rp.blockStatus", statusContext = true)
        formula(r.blockStatStage, FormulaScope.BLOCK_STAT_STAGE, "$rp.blockStatStage", statusContext = true)
        LoadSupport.checkPlaceholders(r.blockMoveMessage, "$rp.blockMoveMessage", report, LoadSupport.BLOCK_MOVE_PLACEHOLDERS, "a blockMoveMessage")
        LoadSupport.checkPlaceholders(r.blockStatusMessage, "$rp.blockStatusMessage", report, LoadSupport.BLOCK_STATUS_PLACEHOLDERS, "a blockStatusMessage")
        LoadSupport.checkPlaceholders(
            r.blockStatStageMessage, "$rp.blockStatStageMessage", report, LoadSupport.BLOCK_STAT_STAGE_PLACEHOLDERS, "a blockStatStageMessage",
        )
        if (r.blockMoveMessage != null && r.blockMove == null) report.warn("$rp.blockMoveMessage: unused without \"blockMove\"")
        if (r.blockStatusMessage != null && r.blockStatus == null) report.warn("$rp.blockStatusMessage: unused without \"blockStatus\"")
        if (r.blockStatStageMessage != null && r.blockStatStage == null) report.warn("$rp.blockStatStageMessage: unused without \"blockStatStage\"")
        if (r.reverseSpeed) {
            if (!f.isGlobal) report.warn("$rp.reverseSpeed: only global fields can reverse the turn order (this side field does nothing with it)")
            if (rules?.turnSystem == TurnSystem.ACTION_BAR) report.warn("$rp.reverseSpeed: does nothing with turnSystem 'action_bar'")
        }
        if (r.actionBarFill != null && rules?.turnSystem == TurnSystem.SPEED_ORDER) {
            report.warn("$rp.actionBarFill: does nothing with turnSystem 'speed_order'")
        }
    }

    private fun checkTriggers(
        triggers: List<Trigger>,
        p: String,
        isMove: Boolean,
        statusContext: Boolean,
        holder: Holder = when {
            isMove -> Holder.MOVE
            statusContext -> Holder.STATUS
            else -> Holder.ABILITY_OR_ITEM
        },
    ) {
        for ((i, t) in triggers.withIndex()) {
            val tp = "$p.triggers[$i]"
            if (isMove && t.on !in EventType.MOVE_EVENTS) {
                report.error("$tp: event '${t.on.id}' cannot be used in a move trigger (use one of: $moveEventIds)")
            }
            triggerFormula(t, t.condition, "$tp.condition", statusContext)
            triggerFormula(t, t.chance, "$tp.chance", statusContext)
            val messages = MessageCheck(messagePlaceholders(t, holder), holder.place(t.on))
            LoadSupport.checkPlaceholders(t.message, "$tp.message", report, messages.allowed, messages.place)
            for ((j, e) in t.effects.withIndex()) checkEffect(t, e, "$tp.effects[$j]", statusContext, messages)
        }
    }

    /** Who holds a trigger: decides which placeholders its messages can fill. */
    private enum class Holder {
        MOVE, STATUS, ABILITY_OR_ITEM, FIELD;

        fun place(event: EventType): String = when (this) {
            MOVE -> "a move's '${event.id}' trigger"
            STATUS -> "a status's '${event.id}' trigger"
            ABILITY_OR_ITEM -> "an ability or item '${event.id}' trigger"
            FIELD -> "a field's '${event.id}' trigger"
        }
    }

    /** The placeholders the messages of one trigger can fill, and how to name that trigger in a warning. */
    private class MessageCheck(val allowed: Set<String>, val place: String)

    /**
     * The placeholders a message of trigger [t] can fill (Manager.format, README "Message placeholders"): `{damage}` and
     * `{amount}` only in the events that set them, `{move}` only when a move can be involved, `{status}` in status events
     * and status triggers, `{field}` in field events and field triggers, `{other}`/`{target}` not in ability/item
     * triggers of events that have no other monster.
     * Placeholders that are only sometimes filled (e.g. `{move}` in `damaged`) are accepted.
     */
    private fun messagePlaceholders(t: Trigger, holder: Holder): Set<String> {
        val vars = EventType.EVENT_VARIABLES[t.on] ?: emptySet()
        val names = mutableSetOf("self", "user", "ability", "item")
        if (holder != Holder.ABILITY_OR_ITEM || t.on !in EVENTS_WITHOUT_OTHER) names += listOf("other", "target")
        if (holder == Holder.MOVE || t.on in EVENTS_WITH_MOVE) names += "move"
        if ("damage" in vars) names += "damage"
        if ("amount" in vars) names += "amount"
        if (holder == Holder.STATUS || "status" in vars) names += "status"
        if (holder == Holder.FIELD || "field" in vars) names += "field"
        return LoadSupport.MESSAGE_PLACEHOLDERS.filterTo(LinkedHashSet()) { it in names }
    }

    /**
     * Checks a formula of trigger [t] (its condition or chance, or an effect value). [recipient] = it is the value of an
     * effect that has a target, so `recipient` is available.
     */
    private fun triggerFormula(t: Trigger, f: Formula?, where: String, statusContext: Boolean, recipient: Boolean = false) {
        if (f == null) return
        formula(f, FormulaScope.TRIGGER, where, statusContext)
        eventVariables(f, where, EventType.EVENT_VARIABLES[t.on] ?: emptySet(), "a '${t.on.id}' trigger")
        if (!recipient) {
            val place = when {
                where.endsWith(".condition") -> "a trigger condition"
                where.endsWith(".chance") && !where.contains(".effects[") -> "a trigger chance"
                else -> "this value (its effect has no target)"
            }
            noRecipient(f, where, place)
        }
    }

    /**
     * The TRIGGER scope accepts every event variable, but the engine only defines the ones the event sets
     * (any other one fails when evaluated), so each use is checked against [provided].
     */
    private fun eventVariables(f: Formula, where: String, provided: Set<String>, place: String) {
        if (!rulesUsable) return
        for (name in f.variables().sorted()) {
            if (name !in FormulaNames.EVENT_VARS || name in provided) continue
            val events = EventType.EVENT_VARIABLES.filterValues { name in it }.keys.joinToString(", ") { it.id }
            report.error("$where: variable '$name' has no value in $place (it is set only in: $events)")
        }
    }

    /** Checks one effect of trigger [t]; [delayed] = it is one of a delay effect's effects (it runs later, at a turn end). */
    private fun checkEffect(t: Trigger, e: Effect, ep: String, statusContext: Boolean, messages: MessageCheck, delayed: Boolean = false) {
        val reviveEffect = e is Effect.Revive || e is Effect.PreventRevive
        val key = when (e) {
            is Effect.UseMove -> "actor"
            is Effect.Delay -> "attachTo"
            else -> "target"
        }
        if (!reviveEffect && e.target in FAINTED_ONLY_TARGETS) {
            report.warn("$ep.$key: '${e.target.id}' only selects fainted monsters, which only revive and preventRevive affect")
        }
        when (e) {
            is Effect.Revive -> {
                if (e.target in LIVING_ONLY_TARGETS) {
                    report.warn("$ep.target: '${e.target.id}' never selects a fainted monster, so this revive can never work")
                }
                triggerFormula(t, e.amount, "$ep.amount", statusContext, recipient = true)
                LoadSupport.checkPlaceholders(e.message, "$ep.message", report, messages.allowed, messages.place)
            }
            is Effect.PreventRevive -> LoadSupport.checkPlaceholders(e.message, "$ep.message", report, messages.allowed, messages.place)
            is Effect.Damage -> triggerFormula(t, e.amount, "$ep.amount", statusContext, recipient = true)
            is Effect.Heal -> triggerFormula(t, e.amount, "$ep.amount", statusContext, recipient = true)
            is Effect.ApplyStatus -> {
                if (missing("status", e.status, statuses[e.status] != null)) {
                    report.error("$ep: unknown status '${e.status}'${LoadSupport.suggest(e.status, statuses.statuses.keys)}")
                }
                triggerFormula(t, e.chance, "$ep.chance", statusContext, recipient = true)
                triggerFormula(t, e.duration, "$ep.duration", statusContext, recipient = true)
                triggerFormula(t, e.stacks, "$ep.stacks", statusContext, recipient = true)
                // Here {status} is the applied status.
                LoadSupport.checkPlaceholders(e.message, "$ep.message", report, messages.allowed + "status", messages.place)
            }
            is Effect.RemoveStatus -> {
                val id = e.status
                if (id != null && !id.equals("all", ignoreCase = true) && missing("status", id, statuses[id] != null)) {
                    report.error("$ep: unknown status '$id'${LoadSupport.suggest(id, statuses.statuses.keys + "all")}")
                }
            }
            is Effect.StatStage -> {
                if (rulesUsable && e.stat !in statIds) {
                    report.error("$ep: unknown stat '${e.stat}'${LoadSupport.suggest(e.stat, statIds)}")
                } else if (rules != null && e.stat == rules.hpStat) {
                    report.warn("$ep: stat stages do not change the HP stat '${e.stat}'")
                }
                triggerFormula(t, e.stages, "$ep.stages", statusContext, recipient = true)
            }
            is Effect.MultiplyDamage -> {
                if (delayed) {
                    report.warn("$ep: multiplyDamage does nothing in a delayed effect (no damage is pending when it runs)")
                } else if (t.on != EventType.MODIFY_DAMAGE_DEALT && t.on != EventType.MODIFY_DAMAGE_TAKEN) {
                    report.warn("$ep: multiplyDamage only works in modifyDamageDealt / modifyDamageTaken triggers (this one is '${t.on.id}')")
                }
                triggerFormula(t, e.value, "$ep.value", statusContext)
            }
            is Effect.CancelAction -> {
                if (delayed) {
                    report.warn("$ep: cancelAction does nothing in a delayed effect (no move is starting when it runs)")
                } else if (t.on != EventType.BEFORE_MOVE) {
                    report.warn("$ep: cancelAction only works in beforeMove triggers (this one is '${t.on.id}')")
                }
                LoadSupport.checkPlaceholders(e.message, "$ep.message", report, messages.allowed, messages.place)
            }
            is Effect.Gauge -> {
                if (rules?.turnSystem == TurnSystem.SPEED_ORDER) {
                    report.warn("$ep: the gauge effect does nothing with turnSystem 'speed_order'")
                }
                triggerFormula(t, e.amount, "$ep.amount", statusContext, recipient = true)
            }
            is Effect.Shield -> triggerFormula(t, e.amount, "$ep.amount", statusContext, recipient = true)
            is Effect.Cooldown -> triggerFormula(t, e.amount, "$ep.amount", statusContext, recipient = true)
            is Effect.ConsumeItem -> LoadSupport.checkPlaceholders(e.message, "$ep.message", report, messages.allowed, messages.place)
            is Effect.Message -> LoadSupport.checkPlaceholders(e.text, "$ep.text", report, messages.allowed, messages.place)
            is Effect.SetField -> {
                val def = fields[e.field]
                if (def == null) {
                    if (missing("field", e.field, false)) {
                        report.error("$ep: unknown field '${e.field}'${LoadSupport.suggest(e.field, fields.fields.keys)}")
                    }
                } else if (def.isGlobal && e.side != null) {
                    report.warn("$ep.side: ignored because '${def.id}' is a global field")
                }
                triggerFormula(t, e.chance, "$ep.chance", statusContext)
                triggerFormula(t, e.duration, "$ep.duration", statusContext)
                triggerFormula(t, e.stacks, "$ep.stacks", statusContext)
                // Here {field} is the field being set and {side} the side it is set on.
                LoadSupport.checkPlaceholders(e.message, "$ep.message", report, messages.allowed + "field" + "side", messages.place)
            }
            is Effect.RemoveField -> {
                val id = e.field
                if (id != null && !id.equals("all", ignoreCase = true)) {
                    val def = fields[id]
                    if (def == null) {
                        if (missing("field", id, false)) {
                            report.error("$ep: unknown field '$id'${LoadSupport.suggest(id, fields.fields.keys + "all")}")
                        }
                    } else if (def.isGlobal && e.side != null) {
                        report.warn("$ep.side: ignored because '${def.id}' is a global field")
                    }
                }
                val group = e.group
                if (group != null && group !in fields.groups && "field:${LoadSupport.ANY_ID}" !in report.failed) {
                    report.error("$ep: no field has the group '$group'${LoadSupport.suggest(group, fields.groups)}")
                }
                // Here {field} is the removed field and {side} its side.
                LoadSupport.checkPlaceholders(e.message, "$ep.message", report, messages.allowed + "field" + "side", messages.place)
            }
            is Effect.UseMove -> {
                val id = e.move
                if (id != Effect.FIRST_MOVE && id != Effect.SAME_MOVE && missing("move", id, moves[id] != null)) {
                    val hint = LoadSupport.suggest(id, moves.moves.keys + Effect.FIRST_MOVE + Effect.SAME_MOVE)
                    report.error("$ep.move: unknown move '$id'$hint (use a move id, \"first\" or \"same\")")
                }
                if (id == Effect.SAME_MOVE && !messages.place.startsWith("a move") && t.on !in EVENTS_WITH_MOVE) {
                    report.warn("$ep.move: \"same\" always means the actor's first move in a '${t.on.id}' trigger (no move is involved)")
                }
                if (e.moveTarget in FAINTED_ONLY_TARGETS) {
                    report.warn("$ep.moveTarget: '${e.moveTarget!!.id}' only selects fainted monsters, which a move cannot be aimed at here")
                }
                // `recipient` is the actor.
                triggerFormula(t, e.power, "$ep.power", statusContext, recipient = true)
                LoadSupport.checkPlaceholders(e.message, "$ep.message", report, messages.allowed, messages.place)
            }
            is Effect.ExtraTurn -> {}
            is Effect.TransferStatus -> {
                val id = e.status
                if (id != null && !id.equals("all", ignoreCase = true) && missing("status", id, statuses[id] != null)) {
                    report.error("$ep: unknown status '$id'${LoadSupport.suggest(id, statuses.statuses.keys + "all")}")
                }
                if (e.to == e.target && e.target in SINGLE_TARGETS) {
                    report.warn("$ep.to: the statuses would go from '${e.target.id}' to itself (it does nothing)")
                }
                triggerFormula(t, e.count, "$ep.count", statusContext, recipient = true)
            }
            is Effect.CopyStages -> if (e.to == e.target && e.target in SINGLE_TARGETS) {
                report.warn("$ep.to: the stat changes would be copied from '${e.target.id}' to itself (it does nothing)")
            }
            is Effect.SetHp -> triggerFormula(t, e.amount, "$ep.amount", statusContext, recipient = true)
            is Effect.BalanceHp -> if (e.target in SINGLE_TARGETS) {
                report.warn("$ep.target: balanceHp shares HP within a group ('team', 'allies', 'enemies', 'all'); '${e.target.id}' selects one monster, so it does nothing")
            }
            is Effect.SwapHp -> if (e.target == TargetSelector.SELF) {
                report.warn("$ep.target: swapping HP with itself does nothing (swapHp swaps the owner's HP with the target's)")
            }
            is Effect.ForceSwitch, is Effect.SwitchOut -> if (rules != null && rules.teamMax <= rules.activePerSide) {
                report.warn("$ep: ${if (e is Effect.ForceSwitch) "forceSwitch" else "switchOut"} never works: with team.max ${rules.teamMax} and team.active ${rules.activePerSide} nobody is ever on the bench")
            }
            is Effect.Delay -> {
                triggerFormula(t, e.turns, "$ep.turns", statusContext)
                e.turns.constant?.let { if (it < 1.0) report.warn("$ep.turns: $it counts as 1 (a delay waits at least one turn)") }
                if (e.effects.isEmpty()) report.warn("$ep.effects: the delay has no effects")
                LoadSupport.checkPlaceholders(e.message, "$ep.message", report, messages.allowed, messages.place)
                for ((k, inner) in e.effects.withIndex()) checkEffect(t, inner, "$ep.effects[$k]", statusContext, messages, delayed = true)
            }
            is Effect.Attack -> {
                triggerFormula(t, e.power, "$ep.power", statusContext, recipient = true)
                val type = e.type
                if (type != null && types != null && missing("type", type, types.types.containsKey(type))) {
                    report.error("$ep.moveType: unknown type '$type'${LoadSupport.suggest(type, types.types.keys)}")
                }
                if (rules != null && rules.categories.isNotEmpty()) {
                    val category = e.category
                    val def = category?.let { rules.categories[it] }
                    when {
                        category != null && def == null -> {
                            val known = rules.categories.keys
                            val hint = LoadSupport.suggest(category, known).ifEmpty { " (Rules.json categories: ${known.joinToString(", ")})" }
                            report.error("$ep.category: unknown category '$category'$hint")
                        }
                        def != null && !def.dealsDamage -> report.error("$ep.category: category '$category' deals no damage, so the attack can never hurt")
                        rules.categories.values.none { it.dealsDamage } -> report.error("$ep: Rules.json has no damaging category, so the attack can never hurt")
                    }
                }
            }
        }
    }

    private companion object {
        /** Effect targets that only ever select fainted monsters. */
        val FAINTED_ONLY_TARGETS = setOf(TargetSelector.FAINTED_ALLIES, TargetSelector.RANDOM_FAINTED_ALLY)

        /** Effect targets that only ever select living monsters on the field (a revive through them can never work). */
        val LIVING_ONLY_TARGETS = setOf(
            TargetSelector.ALLIES, TargetSelector.TEAM, TargetSelector.ENEMIES, TargetSelector.RANDOM_ENEMY, TargetSelector.ALL,
            TargetSelector.LOWEST_HP_ALLY, TargetSelector.LOWEST_HP_ENEMY, TargetSelector.HIGHEST_HP_ENEMY, TargetSelector.RANDOM_ALLY,
        )

        /** Placeholders of a Rules.json hit type's message (README "Hit types"). */
        val HIT_TYPE_PLACEHOLDERS = setOf("user", "target", "move")

        /** Placeholders of a charge move's message (README "Moves.json", charge). */
        val CHARGE_PLACEHOLDERS = setOf("user", "move", "target")

        /** Effect targets that select at most one monster. */
        val SINGLE_TARGETS = setOf(
            TargetSelector.SELF, TargetSelector.OTHER, TargetSelector.USER, TargetSelector.TARGET, TargetSelector.RANDOM_ENEMY,
            TargetSelector.RANDOM_FAINTED_ALLY, TargetSelector.LOWEST_HP_ALLY, TargetSelector.LOWEST_HP_ENEMY,
            TargetSelector.HIGHEST_HP_ENEMY, TargetSelector.RANDOM_ALLY, TargetSelector.OTHER_TARGET,
        )

        /** Events whose `other` is always absent (README "Events"); a status's trigger gets its applier instead. */
        val EVENTS_WITHOUT_OTHER = setOf(
            EventType.BATTLE_START, EventType.SWITCH_IN, EventType.SWITCH_OUT, EventType.TURN_START, EventType.TURN_END,
            EventType.STATUS_REMOVED,
        )

        /** Events that can carry a move (always or only sometimes, like `damaged`). */
        val EVENTS_WITH_MOVE = setOf(
            EventType.BEFORE_MOVE, EventType.AFTER_MOVE, EventType.HIT, EventType.MISSED, EventType.MODIFY_DAMAGE_DEALT,
            EventType.MODIFY_DAMAGE_TAKEN, EventType.DEALT_DAMAGE, EventType.DAMAGED, EventType.FAINTED, EventType.KNOCKED_OUT,
            EventType.TARGETED, EventType.ATTACKED, EventType.ALLY_USED_MOVE, EventType.ENEMY_USED_MOVE, EventType.ALLY_DAMAGED,
            EventType.ENEMY_DAMAGED,
        )
    }
}
