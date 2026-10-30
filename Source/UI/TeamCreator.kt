package ui

import bakemon.Bakemon
import bakemon.MoveDef
import bakemon.Setup
import bakemon.Species
import bakemon.Team
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * Terminal team editor for one setup:
 *  - list saved teams (with validity), create, edit, rename, copy, delete, create a random team
 *  - edit a team: add a member (browse/search the Bakedex), configure, remove, reorder, validate, save
 *  - configure a member: nickname, level, ability (from the species list), moves (from learnable moves,
 *    up to Rules.maxMoves, showing type/category/power/accuracy/description), held item (if allowed),
 *    stat points (if enabled), with a live preview of the resulting stats
 *  - browse the Bakedex: species with types and base stats, plus moves / abilities / items / statuses / type chart,
 *    and fields when the setup has a Fields.json
 */
class TeamCreator(private val setup: Setup) {
    private val rules get() = setup.rules

    /** A file in Teams/: its name without .json, the team if it could be read, and its problems. */
    private class SavedTeam(val file: String, val team: Team?, val problems: List<String>) {
        val label: String get() = team?.name ?: file
    }

    /** Interactive entry point from the main menu. Returns when the user goes back. */
    fun run() {
        while (true) {
            Terminal.header("Team creator - ${setup.displayName}")
            val saved = loadSaved()
            printTeamList(saved)
            val actions = listOf("New team", "Random team", "Edit team", "Rename team", "Copy team", "Delete team", "Browse the Bakedex")
            when (Terminal.menu("", actions)) {
                -1 -> return
                0 -> newTeam()
                1 -> newRandomTeam()
                2 -> pickTeam(saved, "Edit which team?")?.let { s -> readable(s)?.let { editTeam(it, s.file) } }
                3 -> pickTeam(saved, "Rename which team?")?.let { renameTeam(it) }
                4 -> pickTeam(saved, "Copy which team?")?.let { copyTeam(it) }
                5 -> pickTeam(saved, "Delete which team?")?.let { deleteTeam(it) }
                6 -> browse()
            }
        }
    }

    /**
     * Edits [team] interactively; returns the edited team (saved or not is decided inside). [file] is the stem of the
     * file it was loaded from (Setup.listTeams): saving writes back to that file, whatever the team's name maps to.
     */
    fun editTeam(team: Team, file: String? = null): Team = edit(team, alreadySaved = true, file = file)

    /** Configures one member; returns the new configuration, or null if the user cancelled. */
    fun configureMember(member: Bakemon): Bakemon? {
        val species = setup.dex[member.species]
        if (species == null) {
            Terminal.error("Unknown species '${member.species}': remove this member from the team.")
            return null
        }
        var m = member
        while (true) {
            Terminal.header("Configure ${m.displayName(setup)}" + if (m.nickname != null) " (${species.name})" else "")
            println("Types: ${Describe.types(setup, species.types)}")
            printStatPreview(m, species)
            problems(m).forEach { Terminal.warn("  ! $it") }
            val options = mutableListOf<Pair<String, () -> Unit>>()
            options += "Done" to {}
            options += "Nickname: ${m.nickname ?: Terminal.dim("none")}" to { m = askNickname(m) }
            options += "Level: ${m.level}" to { m = m.copy(level = Terminal.askInt("Level", rules.levelMin..rules.levelMax, m.level)) }
            val abilityDescription = describeBelow(m.ability?.let { setup.abilities[it]?.description })
            options += "Ability: ${abilityName(m.ability)}$abilityDescription" to { m = chooseAbility(m, species) }
            options += "Moves: ${m.moves.joinToString(", ") { setup.moves[it]?.name ?: it }.ifEmpty { "none" }}" to {
                m = chooseMoves(m, species)
            }
            if (rules.heldItems) {
                val itemDescription = describeBelow(m.item?.let { setup.items[it]?.description })
                options += "Item: ${m.item?.let { setup.items[it]?.name ?: it } ?: "none"}$itemDescription" to { m = chooseItem(m) }
            }
            if (rules.statPointsEnabled) {
                options += "Stat points: ${m.points.values.sum()}/${rules.statPointsTotal} used" to { m = editPoints(m, species) }
            }
            val index = Terminal.menu("", options.map { it.first }, "Cancel")
            when {
                index == 0 -> return m
                index > 0 -> options[index].second()
                m == member || Terminal.confirm("Discard the changes to ${m.displayName(setup)}?", true) -> return null
            }
        }
    }

    /** Read-only browser of the setup's data. */
    fun browse() {
        while (true) {
            Terminal.header("Browse - ${setup.displayName}")
            val sections = listOf(
                "Bakedex (${setup.dex.all().size} species)",
                "Moves (${setup.moves.all().size})",
                "Abilities (${setup.abilities.all().size})",
                "Items (${setup.items.all().size})",
                "Statuses (${setup.statuses.all().size})",
                "Type chart (${setup.types.all().size} types)",
            ) + if (setup.fields.all().isEmpty()) emptyList() else listOf("Fields (${setup.fields.all().size})")
            when (Terminal.menu("", sections)) {
                -1 -> return
                0 -> browseDex()
                1 -> listMoves()
                2 -> listNamed("Abilities", setup.abilities.all().map { Triple(it.name, "", it.description) })
                3 -> listNamed("Items", setup.items.all().map { Triple(it.name, "", it.description) })
                4 -> listStatuses()
                5 -> typeChart()
                6 -> listFields()
            }
        }
    }

    // ---- team list --------------------------------------------------------------------------------------------

    private fun loadSaved(): List<SavedTeam> = setup.listTeams().map { file ->
        try {
            val team = setup.loadTeam(file)
            SavedTeam(file, team, problems(team))
        } catch (e: Exception) {
            SavedTeam(file, null, listOf("cannot be read: ${e.message}"))
        }
    }

    private fun printTeamList(saved: List<SavedTeam>) {
        if (saved.isEmpty()) {
            println(Terminal.dim("No saved teams yet (folder: ${setup.teamsDir.path})."))
            return
        }
        println("Saved teams:")
        val rows = saved.map { s ->
            val size = s.team?.members?.size?.let { if (it == 1) "1 member" else "$it members" } ?: ""
            val state = if (s.problems.isEmpty()) Terminal.color("OK", "green")
            else Terminal.color("INVALID: " + s.problems.first(), "yellow")
            listOf("  - " + s.label, size, state)
        }
        Terminal.table(rows).forEach { println(Terminal.pad(it, 110).trimEnd()) }
    }

    private fun pickTeam(saved: List<SavedTeam>, title: String): SavedTeam? {
        if (saved.isEmpty()) {
            Terminal.warn("There are no saved teams.")
            return null
        }
        val index = Terminal.menu(title, saved.map { it.label })
        return if (index < 0) null else saved[index]
    }

    /** The team of [saved], or null (with a message) if its file cannot be read. */
    private fun readable(saved: SavedTeam): Team? {
        if (saved.team == null) Terminal.error("'${saved.file}' ${saved.problems.first()}. Fix or delete the file.")
        return saved.team
    }

    private fun newTeam() {
        val name = askTeamName("Name of the new team", uniqueName("Team")) ?: return
        edit(Team(name, emptyList()), alreadySaved = false)
    }

    private fun newRandomTeam() {
        val name = askTeamName("Name of the random team", uniqueName("Random team")) ?: return
        val team = setup.randomTeam(Random.Default, name = name)
        println("Generated: " + team.members.joinToString(", ") { it.displayName(setup) })
        edit(team, alreadySaved = false, startDirty = true)
    }

    private fun renameTeam(saved: SavedTeam) {
        val team = readable(saved) ?: return
        val name = askTeamName("New name", team.name, current = saved.file) ?: return
        if (name == team.name) return
        val file = writeTeam(team.copy(name = name)) ?: return
        // Canonical paths: on Windows a case-only rename overwrites the same file, which must not be deleted.
        val old = File(setup.teamsDir, saved.file + ".json")
        if (file.canonicalFile != old.canonicalFile) {
            try {
                setup.deleteTeam(saved.file)
            } catch (e: SecurityException) {
                Terminal.error("The old file '${old.name}' could not be deleted: ${e.message}")
            }
        }
        println("Renamed '${team.name}' to '$name'.")
    }

    private fun copyTeam(saved: SavedTeam) {
        val team = readable(saved) ?: return
        val name = askTeamName("Name of the copy", uniqueName(team.name, " copy")) ?: return
        val file = writeTeam(team.copy(name = name)) ?: return
        println("Copied to '$name' (${file.name}).")
    }

    private fun deleteTeam(saved: SavedTeam) {
        if (!Terminal.confirm("Delete the team '${saved.label}'? This cannot be undone", false)) return
        val deleted = try {
            setup.deleteTeam(saved.file)
        } catch (e: SecurityException) {
            Terminal.error("The team could not be deleted: ${e.message}")
            return
        }
        if (deleted) println("Deleted '${saved.label}'.") else Terminal.warn("It could not be deleted (is the file gone, or open in another program?).")
    }

    /**
     * Saves [team] ([file] = the stem of the file to overwrite, see Setup.saveTeam). Returns the file, or null after
     * telling the user why it could not be written (read-only folder, file locked by another program...).
     */
    private fun writeTeam(team: Team, file: String? = null): File? = try {
        setup.saveTeam(team, file)
    } catch (e: IOException) {
        Terminal.error("The team could not be saved: ${e.message ?: e::class.simpleName}")
        null
    } catch (e: SecurityException) {
        Terminal.error("The team could not be saved: ${e.message ?: e::class.simpleName}")
        null
    }

    /**
     * Asks for a team name (at most [MAX_TEAM_NAME] characters); Enter accepts [default]. If saving it would overwrite
     * the file of another saved team (not [current], a file stem), asks to overwrite: names are compared by the file they
     * are saved to, since different names can share one ("Trio." and "Trio", "A?B" and "A_B").
     * Returns null when the user cancels by typing [CANCEL_INPUT].
     */
    private fun askTeamName(prompt: String, default: String, current: String? = null): String? {
        while (true) {
            val name = Terminal.ask("$prompt ($CANCEL_INPUT = cancel)", default).trim()
            if (name.isEmpty() || name == CANCEL_INPUT) return null
            // The current name of a hand-edited team may be longer: keeping it unchanged is fine (nothing is renamed).
            if (name.length > MAX_TEAM_NAME && name != default) {
                Terminal.warn("Please use at most $MAX_TEAM_NAME characters (that name has ${name.length}).")
                continue
            }
            val existing = setup.teamFileTaken(name)?.takeUnless { it.equals(current, ignoreCase = true) }
                ?: return name
            val question = if (existing.equals(name, ignoreCase = true)) "A team called '$name' already exists."
            else "'$name' would be saved as '$existing.json', which already holds a saved team."
            if (Terminal.confirm("$question Overwrite it when saving?", false)) return name
        }
    }

    /**
     * [base] + [suffix], or "[base][suffix] 2", "... 3"..., whichever does not share a file name with a saved team. [base]
     * is shortened so that the name fits in [MAX_TEAM_NAME] characters (askTeamName must accept its own suggestion).
     */
    private fun uniqueName(base: String, suffix: String = ""): String {
        fun fit(number: String): String {
            val end = suffix + number
            return base.take((MAX_TEAM_NAME - end.length).coerceAtLeast(0)).trimEnd() + end
        }
        val first = fit("")
        if (setup.teamFileTaken(first) == null) return first
        return generateSequence(2) { it + 1 }.map { fit(" $it") }.first { setup.teamFileTaken(it) == null }
    }

    // ---- team editor ------------------------------------------------------------------------------------------

    /**
     * The team editor loop. [file] is the stem of the file the team was loaded from (saving writes back to it); null for
     * a new team, which is saved under its name (askTeamName already confirmed overwriting) and then keeps that file.
     */
    private fun edit(team: Team, alreadySaved: Boolean, startDirty: Boolean = false, file: String? = null): Team {
        var current = team
        var lastSaved: Team? = if (alreadySaved) team else null
        var dirty = startDirty
        var target = file
        fun saveNow(): Boolean {
            val written = save(current, target) ?: return false
            target = written.name.dropLast(".json".length)
            lastSaved = current
            dirty = false
            return true
        }
        while (true) {
            val unsaved = dirty || (lastSaved != null && current != lastSaved) || (lastSaved == null && current.members.isNotEmpty())
            Terminal.header("Team: ${current.name}" + if (unsaved) Terminal.color(" (unsaved changes)", "yellow") else "")
            printMembers(current)
            val problems = problems(current)
            if (problems.isEmpty()) println(Terminal.color("The team is valid.", "green"))
            else Terminal.warn("${problems.size} problem(s), e.g.: ${problems.first()}")

            val members = current.members
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            if (members.size < rules.teamMax) actions += "Add member" to { current = addMember(current) }
            if (members.isNotEmpty()) {
                actions += "Configure member" to { current = configureIn(current) }
                actions += "Remove member" to { current = removeFrom(current) }
            }
            if (members.size > 1) {
                actions += "Move member up" to { current = moveIn(current, -1) }
                actions += "Move member down" to { current = moveIn(current, +1) }
            }
            actions += "Validate" to { showProblems(current) }
            actions += "Save" to { saveNow() }
            val index = Terminal.menu("", actions.map { it.first })
            if (index >= 0) {
                actions[index].second()
                continue
            }
            // A failed save keeps the editor open, so the unsaved team is not lost.
            if (unsaved && Terminal.confirm("Save the changes to '${current.name}' before leaving?", true) && !saveNow()) continue
            return current
        }
    }

    private fun printMembers(team: Team) {
        if (team.members.isEmpty()) {
            println(Terminal.dim("No members yet (${rules.teamMin}-${rules.teamMax} allowed, the first ${rules.activePerSide} start on the field)."))
            return
        }
        val rows = team.members.mapIndexed { i, m -> listOf("${i + 1}.") + memberCells(m) }
        Terminal.table(rows, right = setOf(0)).forEach { println("  $it") }
    }

    private fun memberCells(m: Bakemon): List<String> {
        val species = setup.dex[m.species]
        val name = if (m.nickname != null && species != null) "${m.nickname} (${species.name})" else m.displayName(setup)
        return listOf(
            name,
            "Lv${m.level}",
            Describe.types(setup, species?.types ?: emptyList()),
            abilityName(m.ability),
            if (rules.heldItems) "@" + (m.item?.let { setup.items[it]?.name ?: it } ?: "-") else "",
            m.moves.joinToString(", ") { setup.moves[it]?.name ?: it },
        )
    }

    private fun showProblems(team: Team) {
        val problems = problems(team)
        if (problems.isEmpty()) println(Terminal.color("The team is valid and ready for battle.", "green"))
        else problems.forEach { Terminal.warn("  ! $it") }
        Terminal.pause()
    }

    /** Saves [team] to the file with stem [file] (or under its name); returns the file, or null if it failed (reported). */
    private fun save(team: Team, file: String?): File? {
        val written = writeTeam(team, file) ?: return null
        println("Saved to ${written.path}.")
        val problems = problems(team)
        if (problems.isNotEmpty()) {
            Terminal.warn("Warning: the team has ${problems.size} problem(s) and can't be used in battle until they are fixed.")
        }
        return written
    }

    private fun addMember(team: Team): Team {
        val species = pickSpecies(team) ?: return team
        val member = configureMember(Bakemon.default(species, setup))
        if (member == null) {
            println("${species.name} was not added.")
            return team
        }
        return team.copy(members = team.members + member)
    }

    private fun chooseMember(team: Team, title: String): Int =
        Terminal.menu(title, Terminal.table(team.members.map { memberCells(it).take(3) }))

    private fun configureIn(team: Team): Team {
        val index = chooseMember(team, "Configure which member?")
        if (index < 0) return team
        val member = configureMember(team.members[index]) ?: return team
        return team.copy(members = team.members.toMutableList().also { it[index] = member })
    }

    private fun removeFrom(team: Team): Team {
        val index = chooseMember(team, "Remove which member?")
        if (index < 0) return team
        val name = team.members[index].displayName(setup)
        if (!Terminal.confirm("Remove $name from the team?", true)) return team
        return team.copy(members = team.members.filterIndexed { i, _ -> i != index })
    }

    private fun moveIn(team: Team, delta: Int): Team {
        val index = chooseMember(team, if (delta < 0) "Move which member up?" else "Move which member down?")
        val other = index + delta
        if (index < 0 || other !in team.members.indices) return team
        val members = team.members.toMutableList()
        members[index] = members[other].also { members[other] = members[index] }
        return team.copy(members = members)
    }

    // ---- member configuration ---------------------------------------------------------------------------------

    private fun problems(member: Bakemon): List<String> =
        try { member.validate(setup) } catch (e: Exception) { listOf(e.message ?: "cannot be validated") }

    private fun problems(team: Team): List<String> =
        try { team.validate(setup) } catch (e: Exception) { listOf(e.message ?: "cannot be validated") }

    private fun abilityName(id: String?): String = id?.let { setup.abilities[it]?.name ?: it } ?: "none"

    /** A dimmed, wrapped description shown on the lines below a menu option ("" if there is none). */
    private fun describeBelow(description: String?): String =
        if (description.isNullOrBlank()) "" else "\n" + Terminal.dim(Terminal.wrap(description, 72, "    "))

    private fun printStatPreview(m: Bakemon, species: Species) {
        val stats = try { m.stats(setup) } catch (e: Exception) { emptyMap() }
        val header = buildList {
            add("Stat"); add("Base")
            if (rules.statPointsEnabled) add("Points")
            add("Value")
        }.map { Terminal.dim(it) }
        val rows = rules.stats.map { stat ->
            buildList {
                add(stat.name)
                add(Describe.num(species.baseStats[stat.id] ?: 0.0))
                if (rules.statPointsEnabled) add((m.points[stat.id] ?: 0).toString())
                add(stats[stat.id]?.let { Describe.num(it) } ?: "?")
            }
        }
        Terminal.table(listOf(header) + rows, right = (1 until header.size).toSet()).forEach { println("  $it") }
    }

    private fun askNickname(m: Bakemon): Bakemon {
        val current = m.nickname?.let { " (now '$it')" } ?: ""
        val name = Terminal.ask("Nickname$current, empty for none").trim()
        return m.copy(nickname = name.ifEmpty { null })
    }

    private fun chooseAbility(m: Bakemon, species: Species): Bakemon {
        if (species.abilities.isEmpty()) {
            Terminal.warn("${species.name} has no abilities.")
            return m
        }
        val options = species.abilities.map { id ->
            val def = setup.abilities[id]
            val mark = if (id == m.ability) "* " else "  "
            mark + (def?.name ?: id) + (def?.description?.let { " - " + Terminal.dim(it) } ?: "")
        }
        val index = Terminal.menu("Ability (* = current)", options)
        return if (index < 0) m else m.copy(ability = species.abilities[index])
    }

    private fun chooseMoves(start: Bakemon, species: Species): Bakemon {
        var m = start
        while (true) {
            // Learnable moves, plus chosen moves that are not learnable (so they can be removed).
            val ids = (species.moves + m.moves.filter { it !in species.moves }).distinct()
            Terminal.header("Moves of ${m.displayName(setup)} (${m.moves.size}/${rules.maxMoves} chosen)")
            val header = listOf("   ") + Describe.moveHeader(setup).map { Terminal.dim(it) }
            val rows = ids.map { id ->
                val mark = if (id in m.moves) Terminal.color("[x]", "green") else "[ ]"
                val def = setup.moves[id]
                if (def == null) listOf(mark, "$id (unknown move)") else listOf(mark) + Describe.moveCells(setup, def)
            }
            val right = Describe.moveNumberColumns(setup).map { it + 1 }.toSet()
            val table = Terminal.table(listOf(header) + rows, right = right)
            val options = ids.mapIndexed { i, id ->
                val def = setup.moves[id]
                val note = if (id !in species.moves) Terminal.color("  (not learnable)", "yellow") else ""
                val description = def?.description?.takeIf { it.isNotBlank() }?.let { "\n" + Terminal.dim(Terminal.wrap(it, 72, "    ")) } ?: ""
                table[i + 1] + note + description
            }
            println(Terminal.menuIndent(options.size) + table.first())
            val index = Terminal.menu("", options, "Done")
            if (index < 0) return m
            val id = ids[index]
            m = when {
                id in m.moves -> m.copy(moves = m.moves - id)
                m.moves.size >= rules.maxMoves -> {
                    Terminal.warn("At most ${rules.maxMoves} moves: remove one first.")
                    m
                }
                else -> m.copy(moves = m.moves + id)
            }
        }
    }

    private fun chooseItem(m: Bakemon): Bakemon {
        val items = setup.items.all()
        if (items.isEmpty()) {
            Terminal.warn("This setup has no items.")
            return m
        }
        val options = listOf((if (m.item == null) "* " else "  ") + "No item") + items.map { item ->
            val mark = if (item.id == m.item) "* " else "  "
            mark + item.name + " - " + Terminal.dim(item.description)
        }
        val index = Terminal.menu("Held item (* = current)", options)
        return when {
            index < 0 -> m
            index == 0 -> m.copy(item = null)
            else -> m.copy(item = items[index - 1].id)
        }
    }

    private fun editPoints(start: Bakemon, species: Species): Bakemon {
        var m = start
        while (true) {
            val used = m.points.values.sum()
            val remaining = rules.statPointsTotal - used
            Terminal.header("Stat points of ${m.displayName(setup)}: $used/${rules.statPointsTotal} used, $remaining left (max ${rules.statPointsMax} per stat)")
            val stats = try { m.stats(setup) } catch (e: Exception) { emptyMap() }
            val header = listOf("Stat", "Base", "Points", "Value").map { Terminal.dim(it) }
            val rows = rules.stats.map { stat ->
                listOf(stat.name, Describe.num(species.baseStats[stat.id] ?: 0.0), "${m.points[stat.id] ?: 0}",
                    stats[stat.id]?.let { Describe.num(it) } ?: "?")
            }
            val table = Terminal.table(listOf(header) + rows, right = setOf(1, 2, 3))
            val options = table.drop(1) + "Reset all to 0"
            println(Terminal.menuIndent(options.size) + table.first())
            val index = Terminal.menu("", options, "Done")
            when {
                index < 0 -> return m
                index == rules.stats.size -> m = m.copy(points = emptyMap())
                else -> {
                    val stat = rules.stats[index]
                    val current = m.points[stat.id] ?: 0
                    val max = minOf(rules.statPointsMax, remaining + current).coerceAtLeast(0)
                    val value = Terminal.askInt("Points for ${stat.name}", 0..max, minOf(current, max))
                    val points = (m.points + (stat.id to value)).filterValues { it != 0 }
                    m = m.copy(points = points)
                }
            }
        }
    }

    // ---- browsing ---------------------------------------------------------------------------------------------

    /**
     * Lets the user pick a species, with text search. With [team] set (adding a member), species already in the team
     * are refused when the species clause is on.
     */
    private fun pickSpecies(team: Team?): Species? {
        if (setup.dex.all().isEmpty()) {
            Terminal.warn("This setup has no species (Bakemons.json is empty).")
            return null
        }
        var query = ""
        while (true) {
            val list = if (query.isBlank()) setup.dex.all() else setup.dex.search(query)
            if (list.isEmpty()) {
                Terminal.warn("No species match '$query'.")
                query = ""
                continue
            }
            val title = if (query.isBlank()) "Bakedex (${list.size} species)" else "Bakedex: '$query' (${list.size} found)"
            Terminal.header(title)
            val inTeam = team?.members?.map { it.species }?.toSet() ?: emptySet()
            val header = listOf("Name", "Types", "Total").map { Terminal.dim(it) }
            val rows = list.map { s ->
                listOf(s.name, Describe.types(setup, s.types), Describe.baseTotal(s).toString(),
                    if (s.id in inTeam) Terminal.dim("(in team)") else "")
            }
            val table = Terminal.table(listOf(header) + rows, right = setOf(2))
            val extra = if (query.isBlank()) "Search by name or type..." else "Show all"
            val options = table.drop(1) + extra
            println(Terminal.menuIndent(options.size) + table.first())
            val index = Terminal.menu("", options)
            when {
                index < 0 -> return null
                index == list.size -> query = if (query.isBlank()) Terminal.ask("Search (part of a name, id or type)") else ""
                team != null && rules.speciesClause && list[index].id in inTeam ->
                    Terminal.warn("Species clause: ${list[index].name} is already in the team.")
                else -> return list[index]
            }
        }
    }

    private fun browseDex() {
        while (true) {
            val species = pickSpecies(null) ?: return
            showSpecies(species)
            Terminal.pause()
        }
    }

    private fun showSpecies(s: Species) {
        Terminal.header("${s.name} " + Terminal.dim("(${s.id})"))
        println("Types: ${Describe.types(setup, s.types)}")
        if (s.description.isNotBlank()) println(Terminal.wrap(s.description, 78))
        println()
        println("Base stats " + Terminal.dim("(bars compare with the highest value of that stat in the Bakedex)") + ":")
        val rows = rules.stats.map { stat ->
            val value = s.baseStats[stat.id] ?: 0.0
            val best = setup.dex.all().maxOf { it.baseStats[stat.id] ?: 0.0 }.coerceAtLeast(1.0)
            listOf(stat.name, Describe.num(value), Terminal.hpBar((value * 1000 / best).toInt(), 1000, 20))
        } + listOf(listOf("Total", Describe.baseTotal(s).toString(), ""))
        Terminal.table(rows, right = setOf(1)).forEach { println("  $it") }
        println()
        println("Abilities:")
        if (s.abilities.isEmpty()) println("  none")
        s.abilities.forEachIndexed { i, id ->
            val def = setup.abilities[id]
            val default = if (i == 0) Terminal.dim(" (default)") else ""
            println("  ${def?.name ?: id}$default - ${def?.description ?: "unknown ability"}")
        }
        println()
        println("Learnable moves (${s.moves.size}):")
        val defs = s.moves.mapNotNull { setup.moves[it] }
        printMoveTable(defs, withDescriptions = false)
        println()
        val image = s.image?.let { path ->
            val exists = File(setup.dir, path).exists()
            path + if (exists) "" else Terminal.dim(" (file not found)")
        } ?: Terminal.dim("none")
        println("Image: $image")
    }

    private fun printMoveTable(moves: List<MoveDef>, withDescriptions: Boolean) {
        if (moves.isEmpty()) {
            println("  none")
            return
        }
        val header = (Describe.moveHeader(setup) + listOf("Prio", "Target")).map { Terminal.dim(it) }
        val rows = moves.map { Describe.moveCells(setup, it) + listOf(it.priority.toString(), it.target.id) }
        val right = Describe.moveNumberColumns(setup) + (header.size - 2)
        val table = Terminal.table(listOf(header) + rows, right = right)
        println("  " + table.first())
        moves.forEachIndexed { i, move ->
            println("  " + table[i + 1])
            if (withDescriptions && move.description.isNotBlank()) println(Terminal.dim(Terminal.wrap(move.description, 78, "      ")))
        }
    }

    private fun listMoves() {
        Terminal.header("Moves")
        printMoveTable(setup.moves.all(), withDescriptions = true)
        Terminal.pause()
    }

    /** Prints "Name tag - description" entries. */
    private fun listNamed(title: String, entries: List<Triple<String, String, String>>) {
        Terminal.header(title)
        if (entries.isEmpty()) println("  none")
        entries.forEach { (name, tag, description) ->
            println("  " + Terminal.bold(name) + (if (tag.isNotEmpty()) " $tag" else ""))
            if (description.isNotBlank()) println(Terminal.wrap(description, 78, "      "))
        }
        Terminal.pause()
    }

    private fun listStatuses() {
        listNamed("Statuses", setup.statuses.all().map { s ->
            val duration = s.duration?.let { d -> d.constant?.let { "${Describe.num(it)} turns" } ?: "duration ${d.source}" } ?: "until removed"
            val stacking = if (s.maxStacks > 1) ", ${s.stacking.id} up to ${s.maxStacks}" else ""
            Triple(s.name, Terminal.dim("[${s.short}] ${s.kind.id}, $duration$stacking"), s.description)
        })
    }

    /** Fields.json: name, [short] scope, group, duration, stacking, whether the battle menu offers it; description. */
    private fun listFields() {
        listNamed("Fields", setup.fields.all().map { f ->
            val duration = f.duration?.let { d -> d.constant?.let { "${Describe.num(it)} turns" } ?: "duration ${d.source}" } ?: "until removed"
            val notes = listOfNotNull(
                if (f.isGlobal) "global" else "one side",
                f.group?.let { "group $it" },
                duration,
                if (f.maxStacks > 1) "${f.stacking.id} up to ${f.maxStacks}" else null,
                if (f.selectable) "selectable at battle start" else null,
            )
            Triple(f.name, Terminal.dim("[${f.short}] ${notes.joinToString(", ")}"), f.description)
        })
    }

    private fun typeChart() {
        val types = setup.types.all()
        Terminal.header("Type chart (attacker in rows, defender in columns; '.' = x1)")
        if (types.isEmpty()) {
            println("  no types")
            Terminal.pause()
            return
        }
        val abbreviations = abbreviations(types.map { it.name })
        val cell = 4
        println("ATK\\DEF " + types.indices.joinToString("") { Terminal.color(abbreviations[it].padStart(cell), types[it].color) })
        types.forEachIndexed { row, attacker ->
            val cells = types.joinToString("") { defender ->
                val m = setup.types.single(attacker.id, defender.id)
                val text = multiplierText(m).padStart(cell)
                when {
                    m == 1.0 -> Terminal.dim(text)
                    m == 0.0 -> Terminal.color(text, "gray")
                    m > 1.0 -> Terminal.color(text, "green")
                    else -> Terminal.color(text, "red")
                }
            }
            println("  " + Terminal.color(abbreviations[row].padEnd(6), attacker.color) + cells)
        }
        println()
        val legend = types.indices.joinToString(", ") { "${abbreviations[it]} = ${types[it].name}" }
        println(Terminal.dim(Terminal.wrap(legend, 78)))
        Terminal.pause()
    }

    private companion object {
        /** Longest team name the editor accepts (keeps file names well within Windows limits). */
        const val MAX_TEAM_NAME = 60

        /** Typed at a team-name prompt to cancel (like "0" in the menus). */
        const val CANCEL_INPUT = "0"
    }

    /** ".", "2", ".5", ".25", "0"... at most 3 characters when possible. */
    private fun multiplierText(m: Double): String = when (m) {
        1.0 -> "."
        else -> Describe.num(m).let { if (it.startsWith("0.")) it.substring(1) else it }
    }

    /** Unique 3-letter upper-case abbreviations (first letters, falling back to a numbered form on collisions). */
    private fun abbreviations(names: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return names.mapIndexed { i, name ->
            val letters = name.filter { it.isLetterOrDigit() }.uppercase().ifEmpty { "T${i + 1}" }
            val candidates = sequenceOf(letters.take(3)) +
                (2 until letters.length).asSequence().map { letters.take(2) + letters[it] } +
                (1..99).asSequence().map { letters.take(1) + it }
            candidates.first { it !in used }.also { used += it }
        }
    }
}
