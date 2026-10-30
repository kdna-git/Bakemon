package bakemon

import engine.ExpressionException
import engine.MapContext
import util.JsonObj
import util.SetupException
import kotlin.math.floor

/**
 * A configured monster: one team member built from a Bakedex species.
 * The Bakedex says what is available (abilities, learnable moves, stats); this says what was chosen.
 */
data class Bakemon(
    /** Species id in the Bakedex. */
    val species: String,
    /** Display name override, or null to use the species name. */
    val nickname: String? = null,
    val level: Int,
    /** Ability id, or null (allowed only when the species has no abilities). */
    val ability: String? = null,
    /** Held item id, or null. */
    val item: String? = null,
    /** Chosen move ids (1..Rules.maxMoves, no duplicates, all learnable). */
    val moves: List<String> = emptyList(),
    /** Stat points invested per stat id (only when Rules.statPointsEnabled). Missing = 0. */
    val points: Map<String, Int> = emptyMap(),
) {
    /** Nickname, or the species name (or the raw species id if unknown). */
    fun displayName(setup: Setup): String = nickname?.takeIf { it.isNotBlank() } ?: setup.dex[species]?.name ?: species

    /**
     * Raw stats computed with Rules.statFormulas (vars: base, level, points).
     * Contains every stat id of Rules.stats. HP is rounded down to an integer value, at least 1.
     */
    fun stats(setup: Setup): Map<String, Double> {
        val sp = setup.dex.require(species)
        val rules = setup.rules
        val result = LinkedHashMap<String, Double>()
        for (stat in rules.stats) {
            val ctx = MapContext(
                mapOf(
                    "base" to (sp.baseStats[stat.id] ?: 0.0),
                    "level" to level.toDouble(),
                    "points" to (points[stat.id] ?: 0).toDouble(),
                ),
            )
            val value = try {
                rules.statFormula(stat.id).eval(ctx)
            } catch (e: ExpressionException) {
                throw SetupException("The stat formula for ${stat.name} failed: ${e.message}")
            }
            val finite = if (value.isNaN() || value.isInfinite()) 0.0 else value
            result[stat.id] = if (stat.id == rules.hpStat) maxOf(1.0, floor(finite)) else maxOf(0.0, finite)
        }
        return result
    }

    /**
     * Every problem with this configuration against the setup (unknown species, level out of range,
     * ability not allowed, too many / unknown / duplicate / unlearnable moves, unknown item or items disabled,
     * bad stat points...). Empty list = valid. Messages are user-facing.
     */
    fun validate(setup: Setup): List<String> {
        val rules = setup.rules
        val problems = mutableListOf<String>()
        val sp = setup.dex[species]
        if (sp == null) problems += "Unknown bakemon '$species'"
        if (nickname != null && nickname.length > MAX_NICKNAME) {
            problems += "Nickname '$nickname' is too long (${nickname.length} characters, max $MAX_NICKNAME)"
        }
        if (level !in rules.levelMin..rules.levelMax) {
            problems += "Level $level is out of range (${rules.levelMin}-${rules.levelMax})"
        }

        // Ability
        if (ability != null && setup.abilities[ability] == null) {
            problems += "Unknown ability '$ability'"
        } else if (sp != null) {
            val allowed = sp.abilities.joinToString(", ") { setup.abilities[it]?.name ?: it }
            when {
                sp.abilities.isEmpty() && ability != null ->
                    problems += "${sp.name} cannot have an ability (it has ${setup.abilities[ability]?.name ?: ability})"
                sp.abilities.isNotEmpty() && ability == null -> problems += "No ability chosen (${sp.name} can have: $allowed)"
                ability != null && ability !in sp.abilities ->
                    problems += "${sp.name} cannot have the ability ${setup.abilities[ability]?.name ?: ability} (allowed: $allowed)"
            }
        }

        // Moves
        if (moves.isEmpty()) problems += "No moves chosen (choose 1 to ${rules.maxMoves})"
        if (moves.size > rules.maxMoves) problems += "Too many moves: ${moves.size} (max ${rules.maxMoves})"
        moves.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach {
            problems += "Move ${setup.moves[it]?.name ?: it} is chosen more than once"
        }
        for (id in moves.distinct()) {
            val move = setup.moves[id]
            when {
                move == null -> problems += "Unknown move '$id'"
                sp != null && id !in sp.moves -> problems += "${sp.name} cannot learn ${move.name}"
            }
        }

        // Held item
        if (item != null) {
            when {
                !rules.heldItems -> problems += "Held items are disabled in this setup (holding ${setup.items[item]?.name ?: item})"
                setup.items[item] == null -> problems += "Unknown item '$item'"
            }
        }

        // Stat points
        if (!rules.statPointsEnabled) {
            if (points.values.any { it != 0 }) problems += "Stat points are disabled in this setup"
        } else {
            for ((stat, value) in points) {
                when {
                    rules.stats.none { it.id == stat } -> problems += "Unknown stat '$stat' in stat points"
                    value < 0 || value > rules.statPointsMax ->
                        problems += "Stat points for ${rules.statName(stat)} must be between 0 and ${rules.statPointsMax} (has $value)"
                }
            }
            val total = points.values.sumOf { it.toLong() }
            if (total > rules.statPointsTotal) problems += "Too many stat points: $total (max ${rules.statPointsTotal})"
        }
        return problems
    }

    /** JSON object for team files. Omits null/empty fields. */
    fun toJson(): Map<String, Any?> {
        val json = LinkedHashMap<String, Any?>()
        json["species"] = species
        if (!nickname.isNullOrBlank()) json["nickname"] = nickname
        json["level"] = level
        if (ability != null) json["ability"] = ability
        if (item != null) json["item"] = item
        if (moves.isNotEmpty()) json["moves"] = moves.toList()
        val invested = points.filterValues { it != 0 }
        if (invested.isNotEmpty()) json["points"] = LinkedHashMap(invested)
        return json
    }

    companion object {
        /** Longest nickname accepted by [validate]. */
        const val MAX_NICKNAME = 20

        /** Reads a team member object. Throws util.SetupException on wrong field types. */
        fun fromJson(obj: JsonObj): Bakemon {
            val pointsObj = obj.objOrNull("points")
            val points = LinkedHashMap<String, Int>()
            if (pointsObj != null) for (stat in pointsObj.keys) points[stat] = pointsObj.int(stat)
            return Bakemon(
                species = obj.string("species"),
                nickname = obj.stringOrNull("nickname")?.takeIf { it.isNotBlank() },
                level = obj.int("level"),
                ability = obj.stringOrNull("ability")?.takeIf { it.isNotBlank() },
                item = obj.stringOrNull("item")?.takeIf { it.isNotBlank() },
                moves = obj.strings("moves"),
                points = points,
            )
        }

        /** Default configuration: default level, first ability, first maxMoves learnable moves, no item, no points. */
        fun default(species: Species, setup: Setup): Bakemon = Bakemon(
            species = species.id,
            level = setup.rules.levelDefault,
            ability = species.abilities.firstOrNull(),
            moves = species.moves.distinct().take(setup.rules.maxMoves),
        )
    }
}

/** A named list of configured monsters. The first Rules.activePerSide members start on the field. */
data class Team(val name: String, val members: List<Bakemon>) {
    /**
     * Problems with the team: size outside team.min..team.max, species/item clauses,
     * plus each member's problems prefixed with "#<n> <name>: ". Empty = valid.
     */
    fun validate(setup: Setup): List<String> {
        val rules = setup.rules
        val problems = mutableListOf<String>()
        if (members.size < rules.teamMin) problems += "The team needs at least ${rules.teamMin} member(s) (it has ${members.size})"
        if (members.size > rules.teamMax) problems += "The team can have at most ${rules.teamMax} member(s) (it has ${members.size})"
        if (rules.speciesClause) {
            members.groupingBy { it.species }.eachCount().filter { it.value > 1 }.keys.forEach {
                problems += "Species clause: ${setup.dex[it]?.name ?: it} is in the team more than once"
            }
        }
        if (rules.itemClause) {
            members.mapNotNull { it.item }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach {
                problems += "Item clause: ${setup.items[it]?.name ?: it} is held by more than one member"
            }
        }
        members.forEachIndexed { i, member ->
            member.validate(setup).forEach { problems += "#${i + 1} ${member.displayName(setup)}: $it" }
        }
        return problems
    }

    fun toJson(): Map<String, Any?> = linkedMapOf("name" to name, "members" to members.map { it.toJson() })

    companion object {
        fun fromJson(obj: JsonObj): Team = Team(
            name = obj.string("name", ""),
            members = obj.arrOrEmpty("members").objects().map { Bakemon.fromJson(it) },
        )
    }
}
