package ui

import bakemon.FormulaScope
import creator.BakemonForm
import creator.BakemonForms
import creator.ChargeForm
import creator.ChargeForms
import creator.ConditionRecipes
import creator.Durations
import creator.EffectRecipes
import creator.EntryKind
import creator.Events
import creator.FieldForm
import creator.FieldForms
import creator.HolderForm
import creator.HolderForms
import creator.MoveForm
import creator.MoveForms
import creator.Option
import creator.OwnerKind
import creator.PowerRecipes
import creator.Presets
import creator.Recipe
import creator.RecipeContext
import creator.RecipeUse
import creator.SetupDraft
import creator.StatMods
import creator.StatusForm
import creator.StatusForms
import creator.TriggerForm
import creator.TriggerSpec
import creator.Triggers
import creator.TypeForm
import creator.TypeForms
import ui.CreatorInput.Row

/**
 * The entry screens of the setup creator: one form per entry kind (every field in plain words, triggers as one-line
 * summaries), the add wizards (a ready-made behaviour first where presets exist, then the form), the trigger editor
 * (WHEN / IF / chance / how often / message / DO) and the type chart editor. Forms work on a copy: Done writes the
 * entry into the [draft] (in memory; the setup editor saves the files), Cancel leaves it as it was.
 */
internal class EntryForms(private val draft: SetupDraft) {
    private fun ctx(owner: OwnerKind, entryId: String = ""): RecipeContext = RecipeContext.of(draft, owner, null, entryId)

    /** The context of a status being edited: it can refer to itself even before it is added ("wake up when hit"). */
    private fun statusCtx(id: String, name: String, kind: String): RecipeContext {
        val c = ctx(OwnerKind.STATUS, id)
        return if (id.isEmpty() || c.statuses.any { it.value == id }) c else c.copy(statuses = c.statuses + Option(id, name, kind))
    }

    /** The context of a field being edited: it can refer to itself even before it is added ("end this field"). */
    private fun fieldCtx(id: String, name: String, side: Boolean, group: String?): RecipeContext {
        val c = ctx(OwnerKind.FIELD, id).copy(sideField = side)
        return if (id.isEmpty() || c.fields.any { it.id == id }) c else c.copy(fields = c.fields + creator.FieldInfo(id, name, side, group))
    }

    // ---- names and small pickers ----------------------------------------------------------------------------------

    private fun entryString(e: Map<String, Any?>, key: String): String? = e[key] as? String

    /** A type name in its colour. */
    fun typeName(id: String): String {
        val e = draft.entry(EntryKind.TYPE, id) ?: return "$id (unknown type)"
        return Terminal.color(entryString(e, "name") ?: id, entryString(e, "color"))
    }

    fun typeNames(ids: List<String>): String = if (ids.isEmpty()) "none" else ids.joinToString("/") { typeName(it) }

    private fun typeOptions(): List<Option> = draft.infos(EntryKind.TYPE).map { Option(it.id, it.name) }

    private fun names(kind: EntryKind, ids: List<String>): String = if (ids.isEmpty()) "none" else ids.joinToString(", ") { draft.nameOf(kind, it) }

    private fun statOptions(): List<Option> = ctx(OwnerKind.ABILITY).stats

    private fun statName(id: String): String = statOptions().firstOrNull { it.value == id }?.label ?: id

    /** Picks one type (or none when [allowNone]); returns [current] if cancelled. */
    private fun pickType(current: String?, allowNone: Boolean): String? {
        val options = typeOptions() + if (allowNone) listOf(Option(NO_TYPE, "No type", "typeless: neutral against every type")) else emptyList()
        if (options.isEmpty()) {
            Terminal.warn("There are no types yet: add them in Types first.")
            return current
        }
        val chosen = CreatorInput.chooseValue("Type", options, current ?: if (allowNone) NO_TYPE else null) ?: return current
        return if (chosen == NO_TYPE) null else chosen
    }

    /** "until removed", "3 turns", "1 to 3 turns (random)" or "advanced formula: ...". */
    fun durationText(value: Any?): String {
        if (value == null) return "until removed"
        val range = Durations.rangeOf(value)
        return when {
            range == null -> "advanced formula: $value"
            range.first == range.second -> CreatorInput.count(range.first, "turn")
            else -> "${range.first} to ${range.second} turns (random)"
        }
    }

    /** Asks a duration; returns [current] when cancelled. */
    private fun askDuration(current: Any?, owner: OwnerKind): Any? {
        val options = listOf(
            Option("forever", "Until removed", "it lasts until an effect removes it (or the battle ends)"),
            Option("turns", "A number of turns", "counted on the turns of the monster (or field) that has it"),
            Option("random", "A random number of turns", "e.g. 1 to 3 turns, rolled when it starts"),
            Option("formula", "Advanced: a formula", "type a formula for the number of turns"),
        )
        val range = Durations.rangeOf(current)
        val now = when {
            current == null -> "forever"
            range == null -> "formula"
            range.first == range.second -> "turns"
            else -> "random"
        }
        return when (CreatorInput.chooseValue("How long does it last?", options, now)) {
            null -> current
            "forever" -> null
            "turns" -> Terminal.askInt("Turns", 1..999, range?.first ?: 3).toDouble()
            "random" -> {
                val min = Terminal.askInt("At least (turns)", 1..999, range?.first ?: 1)
                val max = Terminal.askInt("At most (turns)", min..999, maxOf(min, range?.second ?: (min + 2)))
                Durations.range(min, max)
            }
            else -> CreatorInput.formula(current as? String) { ctx(owner).formulaProblems(it, FormulaScope.TRIGGER, recipient = false) } ?: current
        }
    }

    /** "+20%", "-50%" or "advanced formula: ..." for a statModifiers value. */
    private fun modText(value: Any?): String = StatMods.percent(value)?.let { (if (it > 0) "+" else "") + Describe.num(it) + "%" }
        ?: "advanced formula: $value"

    private fun modsText(mods: Map<String, Any?>): String =
        if (mods.isEmpty()) "none" else mods.entries.joinToString(", ") { (k, v) -> "${statName(k)} ${modText(v)}" }

    /** Edits stat changes (statModifiers): stat -> +X% (or an advanced formula in [scope]). */
    private fun editStatMods(title: String, start: Map<String, Any?>, owner: OwnerKind, scope: FormulaScope): Map<String, Any?> {
        val mods = LinkedHashMap(start)
        val c = ctx(owner)
        while (true) {
            Terminal.header(title)
            CreatorInput.help("A stat is multiplied while this is active: +50% = x1.5, -50% = half. HP cannot be changed this way.")
            val keys = mods.keys.toList()
            val options = keys.map { "${statName(it)}: ${modText(mods[it])}" } + "Add a stat change"
            val index = Terminal.menu("", options, "Back")
            when {
                index < 0 -> return mods
                index == keys.size -> {
                    val stat = CreatorInput.chooseValue("Which stat?", c.stageStats.filter { it.value !in mods }, null) ?: continue
                    askMod(stat, null, c, scope)?.let { mods[stat] = it }
                }
                else -> {
                    val stat = keys[index]
                    when (Terminal.menu(statName(stat), listOf("Change it", "Remove it"), "Back")) {
                        0 -> askMod(stat, mods[stat], c, scope)?.let { mods[stat] = it }
                        1 -> mods.remove(stat)
                    }
                }
            }
        }
    }

    private fun askMod(stat: String, current: Any?, c: RecipeContext, scope: FormulaScope): Any? {
        val shown = StatMods.percent(current) ?: if (current is String) current else null
        return when (val a = CreatorInput.number("Change of ${statName(stat)} in % (+20 = 20% more, -50 = half)", shown, -100.0, null, false, false) { c.formulaProblems(it, scope) }) {
            CreatorInput.NumberAnswer.Keep -> current
            CreatorInput.NumberAnswer.Clear -> null
            is CreatorInput.NumberAnswer.Value -> (a.value as? Double)?.let { StatMods.multiplier(it) } ?: a.value
        }
    }

    // ---- triggers -------------------------------------------------------------------------------------------------

    /** One line per trigger. */
    fun triggerLines(triggers: List<TriggerForm>, c: RecipeContext): List<String> = triggers.map { Triggers.describe(it, c) }

    private fun triggersText(triggers: List<TriggerForm>, c: RecipeContext): String =
        if (triggers.isEmpty()) "none" else CreatorInput.count(triggers.size, "trigger") + "\n" +
            triggerLines(triggers, c).joinToString("\n") { Terminal.dim("- " + CreatorInput.short(it, 100)) }

    /** The list of triggers of an entry: open one to edit, move or delete it; add a new one. */
    private fun editTriggers(title: String, start: List<TriggerForm>, c: RecipeContext): List<TriggerForm> {
        val list = start.toMutableList()
        while (true) {
            Terminal.header(title)
            CreatorInput.help("A trigger says WHEN something happens, IF it should work, and what it DOES.")
            if (c.owner == OwnerKind.MOVE) CreatorInput.help("Tip: for one hit of a multi-hit move only, add the condition \"It is hit number N\" (in \"The move and the hit\").")
            val options = list.map { CreatorInput.short(Triggers.describe(it, c), 110) } + "Add a trigger"
            val index = Terminal.menu("", options, "Back")
            when {
                index < 0 -> return list
                index == list.size -> newTrigger(c)?.let { list += it }
                else -> triggerActions(list, index, c)
            }
        }
    }

    private fun triggerActions(list: MutableList<TriggerForm>, index: Int, c: RecipeContext) {
        val t = list[index]
        Terminal.header("Trigger ${index + 1}")
        println(Terminal.wrap(Triggers.describe(t, c), 78, "  "))
        if (t is TriggerForm.Custom) CreatorInput.help("This trigger cannot be shown as a form: it is kept exactly as it is unless you replace its JSON.")
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (t is TriggerForm.Guided) actions += "Edit" to { editTrigger(t.spec, c)?.let { list[index] = TriggerForm.Guided(it, t.original) } }
        if (index > 0) actions += "Move up" to { list.add(index - 1, list.removeAt(index)) }
        if (index < list.size - 1) actions += "Move down" to { list.add(index + 1, list.removeAt(index)) }
        actions += "View the raw JSON" to { CreatorInput.printJson(Triggers.buildAll(listOf(t), c).first()); Terminal.pause() }
        actions += "Replace with raw JSON (advanced)" to {
            CreatorInput.json()?.let { json -> list[index] = Triggers.read(json, c) }
        }
        actions += "Delete" to { if (Terminal.confirm("Delete this trigger?", false)) list.removeAt(index) }
        val chosen = Terminal.menu("", actions.map { it.first }, "Back")
        if (chosen >= 0) CreatorInput.guard { actions[chosen].second() }
    }

    /** WHEN, then a first effect, then the trigger form. */
    private fun newTrigger(c: RecipeContext): TriggerForm? {
        val events = Events.whenChoices(c.owner)
        Terminal.header("New trigger: WHEN does it happen?")
        val default = if (c.owner == OwnerKind.MOVE) 0 else null
        val index = CreatorInput.choose("", events, default, "Cancel")
        if (index < 0) return null
        var spec = TriggerSpec(events[index].value)
        if (spec.event == Events.HP_DROP) spec = Events.withHpDrop(spec, askDropPercent(null) ?: return null)
        val effect = CreatorInput.pickEffect(c.withEvent(spec.event), null, "New trigger: what does it DO?") ?: return null
        if (effect.recipe == "counterattack") {
            spec = spec.copy(conditions = spec.conditions + RecipeUse("other_is_enemy", emptyMap()))
            println(Terminal.dim("Added the condition \"The other monster is an enemy\" (an ally's move can aim at it too); remove it under IF if you want."))
        }
        spec = editTrigger(spec.copy(effects = listOf(effect)), c, isNew = true) ?: return null
        return TriggerForm.Guided(spec)
    }

    /** The % of "when its HP drops below X%"; null if cancelled. */
    private fun askDropPercent(current: Double?): Double? {
        CreatorInput.help("It works when a hit takes its HP from above this line to below it.")
        return when (val a = CreatorInput.number("Below what % of its max HP?", current ?: 50.0, 1.0, 100.0)) {
            is CreatorInput.NumberAnswer.Value -> a.value as? Double ?: current
            else -> current ?: 50.0
        }
    }

    private fun chanceText(chance: Any?): String = when (chance) {
        null -> "always"
        is Number -> Describe.num(chance.toDouble()) + "%"
        else -> "advanced formula: $chance"
    }

    private fun limitText(limit: Int): String = when (limit) {
        0 -> "every time"
        1 -> "once per battle"
        else -> "at most $limit times per battle"
    }

    /** The trigger form; returns the new spec, or null if cancelled. */
    fun editTrigger(start: TriggerSpec, c: RecipeContext, isNew: Boolean = false): TriggerSpec? {
        var s = start
        fun ec() = c.withEvent(s.event)
        val done = CreatorInput.form(
            title = { if (isNew) "New trigger" else "Edit trigger" },
            intro = { println(Terminal.dim(Terminal.wrap(Triggers.describe(TriggerForm.Guided(s), c), 78, "  "))) },
            rows = {
                buildList {
                    add(Row("WHEN", Events.whenText(s, c.owner)) {
                        val drop = Events.hpDrop(s)
                        val chosen = CreatorInput.chooseValue("When does it happen?", Events.whenChoices(c.owner), if (drop != null) Events.HP_DROP else s.event)
                        when (chosen) {
                            null -> {}
                            Events.HP_DROP -> askDropPercent((drop?.values?.get("percent") as? Number)?.toDouble())?.let { s = Events.withHpDrop(s, it) }
                            else -> s = s.copy(event = chosen, conditions = s.conditions.filter { it !== drop })
                        }
                    })
                    if (c.owner == OwnerKind.FIELD) add(Row("For", Events.audiences(c.sideField).firstOrNull { it.value == (s.audience ?: "covered") }?.label ?: (s.audience ?: "")) {
                        CreatorInput.chooseValue("Whose events does the field listen to?", Events.audiences(c.sideField), s.audience ?: "covered")?.let {
                            s = s.copy(audience = if (it == "covered") null else it)
                        }
                    })
                    val drop = Events.hpDrop(s)
                    val others = s.conditions.filter { it !== drop }
                    add(Row("IF", if (others.isEmpty()) "always" else others.joinToString(" and ") { Triggers.describe(it, ConditionRecipes.byId(it.recipe), ec()) }.replaceFirstChar { it.uppercase() }) {
                        s = s.copy(conditions = listOfNotNull(drop) + CreatorInput.editUses("Conditions (all must be true)", others, ec(), false))
                    })
                    add(Row("Chance", chanceText(s.chance)) {
                        CreatorInput.help("The chance that it works each time (empty = always).")
                        when (val a = CreatorInput.number("Chance %", s.chance, 0.0, 100.0, false, true, noneLabel = "always") { ec().formulaProblems(it, FormulaScope.TRIGGER, recipient = false) }) {
                            CreatorInput.NumberAnswer.Keep -> {}
                            CreatorInput.NumberAnswer.Clear -> s = s.copy(chance = null)
                            is CreatorInput.NumberAnswer.Value -> s = s.copy(chance = if (a.value == 100.0) null else a.value)
                        }
                    })
                    add(Row("How often", limitText(s.limit)) {
                        val options = listOf(Option("0", "Every time"), Option("1", "Once per battle"), Option("n", "At most a number of times per battle"))
                        when (CreatorInput.chooseValue("How often can it work?", options, if (s.limit > 1) "n" else s.limit.toString())) {
                            "0" -> s = s.copy(limit = 0)
                            "1" -> s = s.copy(limit = 1)
                            "n" -> s = s.copy(limit = Terminal.askInt("Times per battle", 1..999, maxOf(2, s.limit)))
                        }
                    })
                    add(Row("Message", s.message?.let { "\"$it\"" } ?: "none") {
                        val text = CreatorInput.text("Message shown when it works", s.message, allowNone = true,
                            helpText = "Logged in the battle; {self}, {other}, {user}, {target}, {move} are filled in.")
                        s = s.copy(message = text?.takeIf { it.isNotEmpty() })
                    })
                    add(Row("DO", if (s.effects.isEmpty()) "nothing" else s.effects.joinToString("; ") { Triggers.describe(it, EffectRecipes.byId(it.recipe), ec()) }.replaceFirstChar { it.uppercase() }) {
                        s = s.copy(effects = CreatorInput.editUses("Effects (in order)", s.effects, ec(), true))
                    })
                }
            },
            changed = { s != start },
            problems = { Triggers.problems(s, c) },
        )
        return if (done) s else null
    }

    private fun triggerProblems(triggers: List<TriggerForm>, c: RecipeContext): List<String> =
        triggers.mapIndexedNotNull { i, t -> (t as? TriggerForm.Guided)?.let { Triggers.problems(it.spec, c).map { p -> "Trigger ${i + 1}: $p" } } }.flatten()

    // ---- presets --------------------------------------------------------------------------------------------------

    /**
     * Offers the ready-made behaviours of [owner] and merges the chosen one into [json] (stat changes and rules are
     * merged, triggers added). Returns false if nothing was added.
     */
    private fun addPreset(json: MutableMap<String, Any?>, c: RecipeContext, order: List<String>, title: String): Boolean {
        val presets = Presets.forOwner(c.owner).filter { it.available(c) }
        if (presets.isEmpty()) {
            Terminal.warn("No ready-made behaviour fits yet (some need types, statuses or categories first).")
            return false
        }
        val use = CreatorInput.pickRecipe(title, presets, c) ?: return false
        val recipe = presets.first { it.id == use.recipe }
        Presets.apply(json, Presets.fragment(recipe, use.values, c), order)
        println("Added: ${recipe.label}.")
        return true
    }

    /** Asks whether to start from a preset (for a new status, ability, item or field). */
    private fun offerPreset(json: MutableMap<String, Any?>, c: RecipeContext, order: List<String>, what: String) {
        val presets = Presets.forOwner(c.owner).filter { it.available(c) }
        if (presets.isEmpty()) return
        Terminal.header("Start from a ready-made behaviour?")
        val options = listOf(Option("", "No, start empty", "add triggers and stat changes yourself in the form")) +
            presets.map { Option(it.id, it.label, it.help) }
        val chosen = CreatorInput.choose("", options, 0) .takeIf { it > 0 } ?: return
        val recipe = presets[chosen - 1]
        val values = if (CreatorInput.visibleParams(recipe, c).isEmpty()) recipe.defaults(c) else {
            println(Terminal.bold(recipe.label) + Terminal.dim("  (Enter keeps the value in brackets)"))
            CreatorInput.askParams(recipe, recipe.defaults(c), c)
        }
        Presets.apply(json, Presets.fragment(recipe, values, c), order)
        println("The $what starts as: ${recipe.label}. You can tweak everything in the form.")
    }

    // ---- editors --------------------------------------------------------------------------------------------------

    /** A form over one entry: its rows, problems and the JSON it builds. */
    abstract inner class Editor {
        abstract val kind: EntryKind
        abstract fun title(): String
        abstract fun rows(): List<Row>
        abstract fun changed(): Boolean
        open fun problems(): List<String> = emptyList()
        abstract fun json(): MutableMap<String, Any?>
        open fun intro() {}

        /** The form loop; the built JSON on Done, null on Cancel. */
        fun run(doneLabel: String = "Done"): MutableMap<String, Any?>? =
            if (CreatorInput.form(::title, ::rows, ::changed, ::problems, ::intro, doneLabel)) json() else null

        /** The rows as "Label: value" lines (the entry screen's summary). */
        fun summary(): List<String> = rows().filter { it.value.isNotEmpty() }.map { r -> "${r.label}: ${r.value}" }
    }

    private fun nameRow(get: () -> String, set: (String) -> Unit) = Row("Name", get()) { set(CreatorInput.name("Name", get())) }

    private fun descriptionRow(get: () -> String, set: (String) -> Unit) = Row("Description", get().ifEmpty { "none" }.let { CreatorInput.short(it) }) {
        set(CreatorInput.text("Description", get().ifEmpty { null }, allowNone = true, helpText = "One or two sentences shown in the Bakedex.") ?: "")
    }

    // Types ----------------------------------------------------------------------------------------------------------

    inner class TypeEditor(private val base: Map<String, Any?>) : Editor() {
        override val kind = EntryKind.TYPE
        private val start = TypeForms.read(base)
        private var f = start
        override fun title() = "Type: ${f.name} (${f.id})"
        override fun changed() = f != start
        override fun json() = TypeForms.build(f, base)
        override fun intro() = CreatorInput.help("Matchup changes are made at once (Cancel does not undo them).")
        override fun rows() = listOf(
            nameRow({ f.name }) { f = f.copy(name = it) },
            Row("Colour", f.color?.let { Terminal.color(it, it) } ?: "none") { f = f.copy(color = pickColor(f.color)) },
            Row("When it attacks", chartText(f.id, attacking = true)) { editChart(f.id, attacking = true) },
            Row("When it is attacked", chartText(f.id, attacking = false)) { editChart(f.id, attacking = false) },
        )
    }

    private fun pickColor(current: String?): String? {
        val options = TypeForms.COLORS.map { Option(it, Terminal.color(it, it)) } + Option(NO_TYPE, "No colour")
        val chosen = CreatorInput.chooseValue("Colour (used for its name in battles)", options, current ?: NO_TYPE) ?: return current
        return if (chosen == NO_TYPE) null else chosen
    }

    private fun multiplier(attacker: String, defender: String): Double = draft.typeChart()[attacker]?.get(defender) ?: 1.0

    /** "strong against Grass, Ice; weak against Water" for a type's row (or column). */
    fun chartText(type: String, attacking: Boolean): String {
        val groups = linkedMapOf<String, MutableList<String>>()
        for (other in draft.ids(EntryKind.TYPE)) {
            val m = if (attacking) multiplier(type, other) else multiplier(other, type)
            if (m == 1.0) continue
            val label = when {
                m == 0.0 -> if (attacking) "no effect on" else "not affected by"
                m > 1.0 -> (if (attacking) "strong against" else "weak to") + if (m != 2.0) " (x${Describe.num(m)})" else ""
                else -> (if (attacking) "weak against" else "resists") + if (m != 0.5) " (x${Describe.num(m)})" else ""
            }
            groups.getOrPut(label) { mutableListOf() } += draft.nameOf(EntryKind.TYPE, other)
        }
        return if (groups.isEmpty()) "neutral against every type" else groups.entries.joinToString("; ") { (k, v) -> "$k ${v.joinToString(", ")}" }
    }

    /** The chart row of [type] (how it attacks each type) or its column (how each type attacks it). */
    fun editChart(type: String, attacking: Boolean) {
        val state = CreatorInput.ListState()
        while (true) {
            val name = draft.nameOf(EntryKind.TYPE, type)
            Terminal.header(if (attacking) "$name attacking other types" else "Other types attacking $name")
            CreatorInput.help("Pick a type to change the damage multiplier (x2 strong, x0.5 weak, x0 no effect, x1 neutral).")
            val others = draft.infos(EntryKind.TYPE).filter { CreatorInput.matches(state.query, it.name, it.id) }
            val rows = Terminal.table(others.map { o ->
                val m = if (attacking) multiplier(type, o.id) else multiplier(o.id, type)
                listOf(typeName(o.id), Describe.effectivenessHint(m))
            })
            when (val p = CreatorInput.page(rows, emptyList(), state)) {
                is CreatorInput.Picked.Row -> {
                    val other = others[p.index].id
                    val (a, d) = if (attacking) type to other else other to type
                    val current = Describe.num(multiplier(a, d))
                    val choices = TypeForms.multiplierChoices(draft)
                    val chosen = CreatorInput.chooseValue("${draft.nameOf(EntryKind.TYPE, a)} attacking ${draft.nameOf(EntryKind.TYPE, d)}", choices, current) ?: continue
                    val value = if (chosen == "custom") {
                        (CreatorInput.number("Multiplier (e.g. 1.3)", null, 0.0, 100.0) as? CreatorInput.NumberAnswer.Value)?.value as? Double ?: continue
                    } else chosen.toDouble()
                    draft.setEffectiveness(a, d, value)
                }
                CreatorInput.Picked.Back -> return
                else -> {}
            }
        }
    }

    // Moves ----------------------------------------------------------------------------------------------------------

    inner class MoveEditor(private val base: Map<String, Any?>?, private val start: MoveForm) : Editor() {
        override val kind = EntryKind.MOVE
        private val c = ctx(OwnerKind.MOVE)
        var f = start
        override fun title() = "Move: ${f.name} (${f.id})"
        override fun changed() = f != start
        override fun json() = MoveForms.build(f, c, base)
        override fun problems(): List<String> = buildList {
            if (f.name.isBlank()) add("The move needs a name.")
            if (c.categories.isNotEmpty() && f.category !in c.categories.map { it.value }) add("Choose a category.")
            f.type?.let { if (!draft.contains(EntryKind.TYPE, it)) add("Unknown type '$it'.") }
            PowerRecipes.byId(f.power.recipe)?.let { r -> r.problems(f.power.values, c).forEach { add("Power: $it") } }
            if (f.category in c.damagingCategories && f.power.recipe == "none" && f.triggers.isEmpty()) add("A damaging move with no power and no effects does nothing.")
            ChargeForms.read(f.charge)?.skipIf?.let { skip -> c.formulaProblems(skip, FormulaScope.MOVE).forEach { add("Charge, skip when: $it") } }
            addAll(triggerProblems(f.triggers, c))
        }

        private fun powerText(): String {
            val text = CreatorInput.describe(f.power, PowerRecipes.byId(f.power.recipe), c)
            return if (f.category.isNotEmpty() && f.category !in c.damagingCategories && f.power.recipe != "none") "$text " + Terminal.dim("(ignored: '${f.category}' deals no damage)") else text
        }

        override fun rows() = buildList {
            add(nameRow({ f.name }) { f = f.copy(name = it) })
            add(descriptionRow({ f.description }) { f = f.copy(description = it) })
            add(Row("Type", f.type?.let { typeName(it) } ?: "no type") { f = f.copy(type = pickType(f.type, allowNone = true)) })
            add(Row("Category", categoryText(f.category)) { pickCategory(f.category)?.let { f = f.copy(category = it) } })
            add(Row("How strong", powerText()) {
                CreatorInput.pickRecipe("How strong is it?", PowerRecipes.available(c), c, f.power)?.let { f = f.copy(power = it) }
            })
            add(Row("Accuracy", f.accuracy?.let { Describe.num(it) + "%" } ?: "never misses") {
                CreatorInput.help("The chance to hit in % (the setup's rules decide whether moves can miss at all).")
                when (val a = CreatorInput.number("Accuracy %", f.accuracy, 0.0, 100.0, false, true, noneLabel = "never misses")) {
                    CreatorInput.NumberAnswer.Keep -> {}
                    CreatorInput.NumberAnswer.Clear -> f = f.copy(accuracy = null)
                    is CreatorInput.NumberAnswer.Value -> f = f.copy(accuracy = a.value as Double)
                }
            })
            add(Row("Priority", if (f.priority == 0) "0 (normal)" else f.priority.toString()) {
                f = f.copy(priority = CreatorInput.int("Priority", -7..7, f.priority, "Higher priority moves act first in a round (e.g. +1 quick attacks, -6 slow moves)."))
            })
            if (c.pp || f.pp != null) add(Row("PP", f.pp?.let { if (it == 0) "unlimited" else it.toString() } ?: "not set") {
                f = f.copy(pp = CreatorInput.int("PP", 0..999, f.pp ?: 20, "How many times it can be used per battle (0 = unlimited)."))
            })
            if (c.cooldowns || f.cooldown != null) add(Row("Cooldown", f.cooldown?.let { if (it == 0) "none" else CreatorInput.count(it, "turn") } ?: "not set") {
                f = f.copy(cooldown = CreatorInput.int("Cooldown (turns)", 0..99, f.cooldown ?: 0, "Turns to wait before it can be used again (0 = always ready)."))
            })
            add(Row("Target", MoveForms.TARGETS.firstOrNull { it.value == f.target }?.label ?: f.target) {
                CreatorInput.chooseValue("Who does it target?", MoveForms.TARGETS, f.target)?.let { f = f.copy(target = it) }
            })
            add(Row("Hits", hitsText(f)) { f = askHits(f, c) })
            add(Row("Critical hits", critText(f.critBonus)) { f = f.copy(critBonus = askCrit(f.critBonus, c)) })
            add(Row("Ignores defense", ignoreText(f.ignoreDefense)) { f = f.copy(ignoreDefense = askIgnore(f.ignoreDefense, c)) })
            add(Row("Charge move", chargeText(f.charge, c)) { f = f.copy(charge = editCharge(f.charge, c)) })
            add(Row("Flags", f.flags.joinToString(", ").ifEmpty { "none" }) {
                val text = CreatorInput.text("Flags, separated by commas", f.flags.joinToString(", ").ifEmpty { null }, allowNone = true,
                    helpText = "Labels other entries can check, e.g. contact (flags used in this setup: ${c.flags.joinToString(", ")}).")
                f = f.copy(flags = text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList())
            })
            add(Row("Effects", triggersText(f.triggers, c)) { f = f.copy(triggers = editTriggers("Effects of ${f.name}", f.triggers, c)) })
        }
    }

    private fun categoryText(category: String): String {
        val c = ctx(OwnerKind.MOVE)
        if (category.isEmpty()) return "not set"
        return category + Terminal.dim(if (category in c.damagingCategories) " (deals damage)" else if (c.categories.any { it.value == category }) " (no damage)" else " (unknown category)")
    }

    private fun pickCategory(current: String?): String? {
        val c = ctx(OwnerKind.MOVE)
        if (c.categories.isEmpty()) {
            Terminal.warn("The rules define no move categories.")
            return null
        }
        val options = c.categories.map { Option(it.value, it.label, if (it.value in c.damagingCategories) "deals damage with the damage formula" else "deals no direct damage (status / support)") }
        return CreatorInput.chooseValue("Category", options, current)
    }

    /** "2 to 5 hits (random), power grows with each hit, each hit rolls the accuracy" for a move form. */
    private fun hitsText(f: MoveForm): String {
        val range = MoveForms.hitsRange(f.hits)
        val count = when {
            range == null -> "advanced formula: ${f.hits}"
            range.first == range.second -> if (range.first == 1) "1 hit" else "${range.first} hits"
            else -> "${range.first} to ${range.second} hits (random)"
        }
        val extras = listOfNotNull(
            if (f.power.recipe == "per_hit") "power grows with each hit" else null,
            if (f.hitAccuracy == "each") "each hit rolls the accuracy" else null,
            if (f.target == "randomEnemies") "each hit picks a random enemy" else null,
        )
        return (listOf(count) + extras).joinToString(", ")
    }

    /**
     * The Hits step (wizard and form): how many hits per target, then for several hits whether the power grows with
     * each hit, whether each hit picks a random enemy and whether each hit rolls the accuracy.
     */
    private fun askHits(start: MoveForm, c: RecipeContext): MoveForm {
        var f = start
        val range = MoveForms.hitsRange(f.hits)
        Terminal.header("Hits")
        CreatorInput.help(
            "How many times it hits each target. For something that happens on one hit only (e.g. the 3rd hit stuns), " +
                "add an effect \"when it hits\" with the condition \"It is hit number N\".",
        )
        val options = listOf(
            Option("1", "One hit", "a normal move"),
            Option("fixed", "A fixed number of hits", "e.g. always 2 (Double Kick)"),
            Option("random", "A random number of hits", "e.g. 2 to 5 (Fury Attack)"),
            Option("formula", "Advanced: a formula", "e.g. user.spe > target.spe ? 3 : 2"),
        )
        val now = when {
            range == null -> "formula"
            range.first != range.second -> "random"
            range.first == 1 -> "1"
            else -> "fixed"
        }
        val hits: Any? = when (CreatorInput.chooseValue("How many hits per target?", options, now)) {
            null -> return start
            "1" -> null
            "fixed" -> Terminal.askInt("Hits", 2..20, maxOf(2, range?.first ?: 2)).toDouble()
            "random" -> {
                val min = Terminal.askInt("At least", 1..20, range?.first?.takeIf { range.first != range.second } ?: 2)
                val max = Terminal.askInt("At most", min..20, maxOf(min, range?.second?.takeIf { range.first != range.second } ?: 5))
                MoveForms.hits(min, max)
            }
            else -> CreatorInput.formula(f.hits as? String) { c.formulaProblems(it, FormulaScope.MOVE) } ?: f.hits
        }
        f = f.copy(hits = hits)
        if (hits == null) return (MoveForms.withGrowingPower(f, false) ?: f).copy(hitAccuracy = null)
        val power = f.power.recipe
        if (power == "fixed" || power == "per_hit") {
            val grows = CreatorInput.yesNo("Does the power grow with each hit (x1, x2, x3...)?", power == "per_hit",
                "Yes: the first hit has the power, the second twice as much, the third three times (Triple Kick).")
            f = MoveForms.withGrowingPower(f, grows) ?: f
        } else if (power != "none") {
            println(Terminal.dim("  (The power can grow with each hit only when it is a fixed power; an advanced power formula can use hitIndex.)"))
        }
        if (f.target in SPREAD_TARGETS) {
            val spread = CreatorInput.yesNo("Does each hit pick a random enemy?", f.target == "randomEnemies",
                "Yes: every hit is aimed at a new random enemy, so the hits are spread over the enemies (the number of hits is the total).")
            f = f.copy(target = if (spread) "randomEnemies" else if (f.target == "randomEnemies") "enemy" else f.target)
        }
        f = if (f.target == "randomEnemies" || f.accuracy == null) {
            f.copy(hitAccuracy = null)
        } else {
            val each = CreatorInput.yesNo("Does each hit roll the accuracy?", f.hitAccuracy == "each",
                "Yes: every hit can miss and the first miss stops the move on that target. No: one roll for all the hits.")
            f.copy(hitAccuracy = if (each) "each" else f.hitAccuracy?.takeIf { it != "each" })
        }
        return f
    }

    private fun ignoreText(value: Any?): String = when (value) {
        null -> "no"
        is Number -> if (value.toDouble() == 0.0) "no" else "${Describe.num(value.toDouble())}% of the target's defense"
        else -> "advanced formula: $value"
    }

    /** "Ignores X% of defense": 0 = a normal move (null). */
    private fun askIgnore(current: Any?, c: RecipeContext): Any? {
        CreatorInput.help("The damage formula uses less of the target's defense: 50 = half of it is ignored, 100 = all of it (0 = a normal move).")
        val a = CreatorInput.number("Ignores what % of the target's defense?", current ?: 0.0, 0.0, 100.0) { c.formulaProblems(it, FormulaScope.MOVE) }
        return when (a) {
            CreatorInput.NumberAnswer.Keep -> current
            CreatorInput.NumberAnswer.Clear -> null
            is CreatorInput.NumberAnswer.Value -> (a.value as? Double)?.let { if (it == 0.0) null else it } ?: a.value
        }
    }

    /** "the target has HP below 50%..." for a skipIf formula. */
    private fun skipText(skip: String, c: RecipeContext): String =
        ConditionRecipes.split(skip, c).joinToString(" and ") { Triggers.describe(it, ConditionRecipes.byId(it.recipe), c) }

    private fun chargeText(charge: Any?, c: RecipeContext): String {
        if (charge == null) return "no"
        val form = ChargeForms.read(charge) ?: return "custom (advanced JSON): " + CreatorInput.short(util.Json.stringify(charge, pretty = false), 60)
        val parts = mutableListOf("charges ${CreatorInput.count(form.turns, "turn")} first")
        if (form.invulnerable) parts += "out of reach meanwhile"
        form.skipIf?.let { parts += "not when " + skipText(it, c) }
        return parts.joinToString(", ")
    }

    /** "Skip the charge when...": conditions joined into a formula (null = always charge). */
    private fun askSkip(current: String?, c: RecipeContext): String? {
        val uses = current?.let { ConditionRecipes.split(it, c) } ?: emptyList()
        val list = CreatorInput.editUses("Skip the charge when (all must be true; none = it always charges)", uses, c, effects = false)
        return ConditionRecipes.join(list, c)
    }

    /**
     * The Charge step (wizard: [current] null asks whether it charges at all) and the Charge row of the move form.
     * Returns the new "charge" value: null = a normal move. A charge the form cannot show is kept unless replaced.
     */
    private fun editCharge(current: Any?, c: RecipeContext): Any? {
        if (current == null) {
            val yes = CreatorInput.yesNo("Does it charge first (like Solar Beam or Fly)?", false,
                "A charge move spends its first turn charging (it can be out of reach meanwhile), then hits on its next turn.")
            if (!yes) return null
        } else {
            when (Terminal.menu("Charge move", listOf("Change the charge", "Make it a normal move (no charge)"), "Back")) {
                0 -> {}
                1 -> return null
                else -> return current
            }
        }
        val start = if (current == null) ChargeForm() else ChargeForms.read(current)
        if (start == null) {
            Terminal.header("Charge (advanced JSON)")
            CreatorInput.help("This charge cannot be shown as a form (see README \"Charge moves\"): it is kept as it is unless you replace it.")
            CreatorInput.printJson(current)
            val json = CreatorInput.json()
            if (json != null && json !is Map<*, *>) Terminal.warn("The charge must be a JSON object { ... }.")
            return json as? Map<*, *> ?: current
        }
        var form: ChargeForm = start
        val done = CreatorInput.form(
            title = { "Charge move" },
            intro = { CreatorInput.help("The first use only charges (PP / cooldown are paid then); the monster's next turn releases the move on the same target.") },
            rows = {
                listOf(
                    Row("Turns of charging", CreatorInput.count(form.turns, "turn")) {
                        form = form.copy(turns = CreatorInput.int("Turns spent charging before it hits", 1..9, form.turns))
                    },
                    Row("Out of reach while charging", if (form.invulnerable) "yes (other monsters' moves miss it, like Fly)" else "no") {
                        form = form.copy(invulnerable = CreatorInput.yesNo("Is it out of reach while charging?", form.invulnerable,
                            "Yes: every other monster's move aimed at it misses (effects such as damage over time still reach it)."))
                    },
                    Row("Skip the charge when", form.skipIf?.let { skipText(it, c) } ?: "never") { form = form.copy(skipIf = askSkip(form.skipIf, c)) },
                    Row("Message while charging", "\"${form.message ?: ChargeForms.DEFAULT_MESSAGE}\"" + if (form.message == null) Terminal.dim(" (default)") else "") {
                        form = form.copy(message = CreatorInput.text("Message while charging", form.message, allowNone = true,
                            helpText = "{user}, {move} and {target} are filled in (- = the default \"${ChargeForms.DEFAULT_MESSAGE}\").")?.takeIf { it.isNotEmpty() })
                    },
                )
            },
            changed = { form != start },
            problems = { form.skipIf?.let { s -> c.formulaProblems(s, FormulaScope.MOVE).map { "Skip the charge when: $it" } }.orEmpty() },
        )
        return if (done) ChargeForms.build(form, current) else current
    }

    private fun critText(bonus: Any?): String = when (bonus) {
        null -> "normal chance"
        is Number -> if (bonus.toDouble() == MoveForms.HIGH_CRIT_BONUS) "high chance (+12.5%)" else "+${Describe.num(bonus.toDouble() * 100)}% chance"
        else -> "advanced formula: $bonus"
    }

    private fun askCrit(current: Any?, c: RecipeContext): Any? {
        val options = listOf(
            Option("normal", "Normal chance", ""), Option("high", "High chance", "+12.5% chance to land a critical hit"),
            Option("other", "Another bonus", "a bonus chance in %"), Option("formula", "Advanced: a formula", "a bonus chance from 0 to 1"),
        )
        val now = when (current) {
            null -> "normal"
            is Number -> if (current.toDouble() == MoveForms.HIGH_CRIT_BONUS) "high" else "other"
            else -> "formula"
        }
        return when (CreatorInput.chooseValue("Critical hit chance", options, now)) {
            null -> current
            "normal" -> null
            "high" -> MoveForms.HIGH_CRIT_BONUS
            "other" -> when (val a = CreatorInput.number("Bonus chance %", (current as? Number)?.toDouble()?.times(100), 0.0, 100.0)) {
                is CreatorInput.NumberAnswer.Value -> CreatorInput.jsonNumber((a.value as Double) / 100).takeIf { it > 0 }
                else -> current
            }
            else -> CreatorInput.formula(current as? String) { c.formulaProblems(it, FormulaScope.HIT) } ?: current
        }
    }

    // Statuses -------------------------------------------------------------------------------------------------------

    inner class StatusEditor(private var base: Map<String, Any?>?, start: StatusForm) : Editor() {
        override val kind = EntryKind.STATUS
        private val c = statusCtx(start.id, start.name, start.kind)
        private val startBase = base
        private val startForm = start
        var f = start
        override fun title() = "Status: ${f.name} (${f.id})"
        override fun changed() = f != startForm || base !== startBase
        override fun json() = StatusForms.build(f, c, base)
        override fun problems(): List<String> = buildList {
            if (f.name.isBlank()) add("The status needs a name.")
            if (StatusForms.KINDS.none { it.value == f.kind }) add("Choose a kind.")
            addAll(triggerProblems(f.triggers, c))
        }

        override fun rows() = buildList {
            add(nameRow({ f.name }) { f = f.copy(name = it) })
            add(Row("Short tag", f.short ?: "none") {
                f = f.copy(short = CreatorInput.text("Short tag", f.short, allowNone = true, helpText = "Shown next to the monster in battle, e.g. BRN (none = made from the name).")?.takeIf { it.isNotBlank() })
            })
            add(descriptionRow({ f.description }) { f = f.copy(description = it) })
            add(Row("Kind", StatusForms.KINDS.firstOrNull { it.value == f.kind }?.let { "${it.label} - ${it.help}" } ?: f.kind) {
                CreatorInput.chooseValue("Kind", StatusForms.KINDS, f.kind)?.let { f = f.copy(kind = it) }
            })
            add(Row("Duration", durationText(f.duration)) { f = f.copy(duration = askDuration(f.duration, OwnerKind.STATUS)) })
            add(Row("When applied again", StatusForms.STACKING.firstOrNull { it.value == f.stacking }?.help ?: f.stacking) {
                CreatorInput.chooseValue("When it is applied again", StatusForms.STACKING, f.stacking)?.let { f = f.copy(stacking = it, maxStacks = if (it == "stack") maxOf(f.maxStacks, 2) else f.maxStacks) }
            })
            if (f.stacking == "stack" || f.maxStacks > 1) add(Row("Max stacks", f.maxStacks.toString()) {
                f = f.copy(maxStacks = CreatorInput.int("Maximum stacks", 1..99, f.maxStacks))
            })
            add(Row("Immune types", typeNames(f.immuneTypes)) {
                CreatorInput.pickMany("Types that cannot get it", typeOptions(), f.immuneTypes)?.let { f = f.copy(immuneTypes = it) }
            })
            add(Row("Stat changes while active", modsText(f.statModifiers)) {
                f = f.copy(statModifiers = editStatMods("Stat changes of ${f.name}", f.statModifiers, OwnerKind.STATUS, FormulaScope.STATUS_MODIFIER))
            })
            add(Row("Triggers", triggersText(f.triggers, c)) { f = f.copy(triggers = editTriggers("Triggers of ${f.name}", f.triggers, c)) })
            add(Row("Message when applied", f.applyMessage?.let { "\"$it\"" } ?: "default") {
                f = f.copy(applyMessage = CreatorInput.text("Message when applied", f.applyMessage, allowNone = true, helpText = "{target} is the monster that gets it (none = the default line)."))
            })
            add(Row("Message when it ends", f.removeMessage?.let { "\"$it\"" } ?: "default") {
                f = f.copy(removeMessage = CreatorInput.text("Message when it ends", f.removeMessage, allowNone = true, helpText = "{target} is the monster (none = the default line)."))
            })
            add(Row("Add a ready-made behaviour", "") {
                val json = json()
                if (addPreset(json, c, StatusForms.ORDER, "Add a ready-made behaviour")) {
                    base = json
                    f = StatusForms.read(json, c)
                }
            })
        }
    }

    // Abilities and items -------------------------------------------------------------------------------------------

    inner class HolderEditor(override val kind: EntryKind, private var base: Map<String, Any?>?, start: HolderForm) : Editor() {
        private val owner = if (kind == EntryKind.ITEM) OwnerKind.ITEM else OwnerKind.ABILITY
        private val c = ctx(owner, start.id)
        private val startBase = base
        private val startForm = start
        var f = start
        override fun title() = "${kind.label.replaceFirstChar { it.uppercase() }}: ${f.name} (${f.id})"
        override fun changed() = f != startForm || base !== startBase
        override fun json() = HolderForms.build(f, c, base)
        override fun problems(): List<String> = buildList {
            if (f.name.isBlank()) add("It needs a name.")
            addAll(triggerProblems(f.triggers, c))
        }

        override fun rows() = listOf(
            nameRow({ f.name }) { f = f.copy(name = it) },
            descriptionRow({ f.description }) { f = f.copy(description = it) },
            Row("Stat changes", modsText(f.statModifiers)) {
                f = f.copy(statModifiers = editStatMods("Stat changes of ${f.name}", f.statModifiers, owner, FormulaScope.MODIFIER))
            },
            Row("Triggers", triggersText(f.triggers, c)) { f = f.copy(triggers = editTriggers("Triggers of ${f.name}", f.triggers, c)) },
            Row("Add a ready-made behaviour", "") {
                val json = json()
                if (addPreset(json, c, HolderForms.ORDER, "Add a ready-made behaviour")) {
                    base = json
                    f = HolderForms.read(json, c)
                }
            },
        )
    }

    // Fields ---------------------------------------------------------------------------------------------------------

    inner class FieldEditor(private var base: Map<String, Any?>?, start: FieldForm) : Editor() {
        override val kind = EntryKind.FIELD
        private val startBase = base
        private val startForm = start
        var f = start
        private fun c() = fieldCtx(f.id, f.name, f.scope == "side", f.group)
        override fun title() = "Field: ${f.name} (${f.id})"
        override fun changed() = f != startForm || base !== startBase
        override fun json() = FieldForms.build(f, c(), base)
        override fun problems(): List<String> = buildList {
            if (f.name.isBlank()) add("The field needs a name.")
            addAll(triggerProblems(f.triggers, c()))
        }

        override fun rows() = listOf(
            nameRow({ f.name }) { f = f.copy(name = it) },
            Row("Short tag", f.short ?: "none") {
                f = f.copy(short = CreatorInput.text("Short tag", f.short, allowNone = true, helpText = "Shown in the battle display, e.g. RAIN (none = made from the name).")?.takeIf { it.isNotBlank() })
            },
            descriptionRow({ f.description }) { f = f.copy(description = it) },
            Row("Where", if (f.scope == "side") "one side (screens, hazards...)" else "the whole battle (weather, terrain...)") {
                val options = listOf(Option("global", "The whole battle", "weather, terrain, rooms"), Option("side", "One side", "screens, hazards, tailwind"))
                CreatorInput.chooseValue("Where is it active?", options, f.scope)?.let { f = f.copy(scope = it) }
            },
            Row("Group", f.group ?: "none") {
                f = f.copy(group = CreatorInput.text("Group", f.group, allowNone = true, helpText = "Fields of the same group replace each other, e.g. weather (none = no group).")?.takeIf { it.isNotBlank() })
            },
            Row("Duration", durationText(f.duration)) { f = f.copy(duration = askDuration(f.duration, OwnerKind.FIELD)) },
            Row("When started again", StatusForms.STACKING.firstOrNull { it.value == f.stacking }?.help ?: f.stacking) {
                CreatorInput.chooseValue("When it is started again", StatusForms.STACKING, f.stacking)?.let { f = f.copy(stacking = it, maxStacks = if (it == "stack") maxOf(f.maxStacks, 2) else f.maxStacks) }
            },
            Row("Max stacks", f.maxStacks.toString()) { f = f.copy(maxStacks = CreatorInput.int("Maximum stacks (layers)", 1..99, f.maxStacks)) },
            Row("Can be picked at battle start", if (f.selectable) "yes" else "no") {
                f = f.copy(selectable = CreatorInput.yesNo("Offer it in the battle menu's starting fields?", f.selectable))
            },
            Row("Stat changes", modsText(f.statModifiers)) {
                f = f.copy(statModifiers = editStatMods("Stat changes of ${f.name}", f.statModifiers, OwnerKind.FIELD, FormulaScope.STATUS_MODIFIER))
            },
            Row("Triggers", triggersText(f.triggers, c())) { f = f.copy(triggers = editTriggers("Triggers of ${f.name}", f.triggers, c())) },
            Row("Special rules", fieldRulesText(f.rules)) {
                Terminal.header("Special rules of ${f.name} (advanced JSON)")
                CreatorInput.help("These come from the ready-made behaviours (reverse order, block statuses...). See README \"Fields.json\" for every rule.")
                CreatorInput.printJson(f.rules)
                if (Terminal.confirm("Replace them with JSON?", false)) {
                    val value = CreatorInput.json()
                    if (value is Map<*, *>) f = f.copy(rules = value.entries.associate { (k, v) -> k.toString() to v })
                    else if (value != null) Terminal.warn("The special rules must be a JSON object { ... }.")
                }
            },
            Row("Start message", f.startMessage?.let { "\"$it\"" } ?: "default") {
                f = f.copy(startMessage = CreatorInput.text("Start message", f.startMessage, allowNone = true))
            },
            Row("End message", f.endMessage?.let { "\"$it\"" } ?: "default") {
                f = f.copy(endMessage = CreatorInput.text("End message", f.endMessage, allowNone = true))
            },
            Row("Add a ready-made behaviour", "") {
                val json = json()
                if (addPreset(json, c(), FieldForms.ORDER, "Add a ready-made behaviour")) {
                    base = json
                    f = FieldForms.read(json, c())
                }
            },
        )
    }

    private fun fieldRulesText(rules: Map<String, Any?>): String {
        if (rules.isEmpty()) return "none"
        val words = mapOf(
            "reverseSpeed" to "slower monsters act first", "blockStatus" to "blocks some statuses",
            "blockStatStage" to "blocks some stat drops", "blockMove" to "blocks some moves",
            "disableItems" to "items do not work", "disableAbilities" to "abilities do not work",
        )
        return rules.keys.joinToString(", ") { words[it] ?: it }
    }

    // Bakemons -------------------------------------------------------------------------------------------------------

    inner class BakemonEditor(private val base: Map<String, Any?>?, start: BakemonForm) : Editor() {
        override val kind = EntryKind.BAKEMON
        private val startForm = start
        var f = start
        override fun title() = "Bakemon: ${f.name} (${f.id})"
        override fun changed() = f != startForm
        override fun json() = BakemonForms.build(f, base)
        override fun problems(): List<String> = buildList {
            if (f.name.isBlank()) add("It needs a name.")
            val missing = statOptions().filter { it.value !in f.baseStats }
            if (missing.isNotEmpty()) add("Base stats missing: ${missing.joinToString(", ") { it.label }}.")
            if (f.moves.none { draft.contains(EntryKind.MOVE, it) }) add("It needs at least one learnable move.")
            f.types.filter { !draft.contains(EntryKind.TYPE, it) }.forEach { add("Unknown type '$it'.") }
            f.abilities.filter { !draft.contains(EntryKind.ABILITY, it) }.forEach { add("Unknown ability '$it'.") }
            f.moves.filter { !draft.contains(EntryKind.MOVE, it) }.forEach { add("Unknown move '$it'.") }
        }

        override fun rows() = listOf(
            nameRow({ f.name }) { f = f.copy(name = it) },
            Row("Types", typeNames(f.types)) { pickTypes(f.types)?.let { f = f.copy(types = it) } },
            Row("Base stats", statsText(f.baseStats)) { f = f.copy(baseStats = editStats(f.baseStats)) },
            Row("Abilities", names(EntryKind.ABILITY, f.abilities)) { pickAbilities(f.abilities)?.let { f = f.copy(abilities = it) } },
            Row("Learnable moves", "${f.moves.size}" + if (f.moves.isEmpty()) "" else " (" + CreatorInput.short(names(EntryKind.MOVE, f.moves), 60) + ")") {
                pickMoves(f.moves, f.types)?.let { f = f.copy(moves = it) }
            },
            descriptionRow({ f.description }) { f = f.copy(description = it) },
            Row("Image", f.image ?: "none") {
                f = f.copy(image = CreatorInput.text("Image file (inside the setup folder)", f.image, allowNone = true,
                    helpText = "For a future graphical version; a missing file is fine (only a warning). Default: Assets/${f.id}.png"))
            },
        )
    }

    private fun statsText(stats: Map<String, Double>): String =
        statOptions().joinToString(", ") { "${it.label} ${stats[it.value]?.let(Describe::num) ?: "?"}" }

    private fun pickTypes(current: List<String>): List<String>? {
        if (draft.ids(EntryKind.TYPE).isEmpty()) {
            Terminal.warn("There are no types yet: add them in Types first.")
            return null
        }
        return CreatorInput.pickMany("Types", typeOptions(), current, ordered = true, intro = "Pick one or two types (the first is its main type).")
    }

    private fun pickAbilities(current: List<String>): List<String>? {
        if (draft.ids(EntryKind.ABILITY).isEmpty() && current.isEmpty()) {
            Terminal.warn("There are no abilities yet (they are optional): add them in Abilities.")
            return null
        }
        val options = draft.entries(EntryKind.ABILITY).mapNotNull { e ->
            entryString(e, "id")?.let { Option(it, entryString(e, "name") ?: it, CreatorInput.short(entryString(e, "description") ?: "", 60)) }
        }
        return CreatorInput.pickMany("Abilities", options, current, ordered = true, intro = "The abilities it may have; the first one is its default.")
    }

    /** One line about a move for pickers: "fire, special, power 40". */
    fun moveHint(e: Map<String, Any?>): String {
        val parts = mutableListOf<String>()
        parts += entryString(e, "type")?.let { draft.nameOf(EntryKind.TYPE, it) } ?: "no type"
        entryString(e, "category")?.let { parts += it }
        when (val p = e["power"]) {
            is Number -> if (p.toDouble() > 0) parts += "power ${Describe.num(p.toDouble())}"
            is String -> parts += STAT_POWER.matchEntire(p)?.let { m ->
                val percent = m.groupValues[2].toDoubleOrNull()?.times(100)
                if (percent == null) null else "power ${Describe.num(percent)}% of ${statName(m.groupValues[1])}"
            } ?: "power varies"
        }
        return parts.joinToString(", ")
    }

    private fun pickMoves(current: List<String>, types: List<String>): List<String>? {
        if (draft.ids(EntryKind.MOVE).isEmpty()) {
            Terminal.warn("There are no moves yet: add them in Moves first.")
            return null
        }
        val options = draft.entries(EntryKind.MOVE).mapNotNull { e -> entryString(e, "id")?.let { Option(it, entryString(e, "name") ?: it, moveHint(e)) } }
        val ofTypes = BakemonForms.movesOfTypes(draft, types)
        val typeless = BakemonForms.typelessMoves(draft)
        return CreatorInput.pickMany(
            "Learnable moves", options, current,
            shortcuts = listOf(
                "Add all moves of its types (${ofTypes.size})" to { sel -> sel + ofTypes },
                "Add all moves without a type (${typeless.size})" to { sel -> sel + typeless },
            ),
            intro = "Pick a move to add or remove it; type text to search by name or type.",
        )
    }

    private fun editStats(start: Map<String, Double>): Map<String, Double> {
        val stats = LinkedHashMap(start)
        while (true) {
            Terminal.header("Base stats")
            val list = statOptions()
            val options = list.map { "${it.label}: ${stats[it.value]?.let(Describe::num) ?: "not set"}" } + "Set every stat to the same value"
            val index = Terminal.menu("", options, "Back")
            when {
                index < 0 -> return stats
                index == list.size -> {
                    val v = (CreatorInput.number("Value for every stat", 50.0, 0.0, null) as? CreatorInput.NumberAnswer.Value)?.value as? Double ?: 50.0
                    list.forEach { stats[it.value] = v }
                }
                else -> {
                    val stat = list[index]
                    when (val a = CreatorInput.number(stat.label, stats[stat.value] ?: 50.0, 0.0, null)) {
                        is CreatorInput.NumberAnswer.Value -> stats[stat.value] = a.value as Double
                        else -> {}
                    }
                }
            }
        }
    }

    // ---- entry points -------------------------------------------------------------------------------------------

    /** The editor of an existing entry. */
    fun editorFor(kind: EntryKind, entry: Map<String, Any?>): Editor {
        val id = entryString(entry, "id") ?: ""
        return when (kind) {
            EntryKind.TYPE -> TypeEditor(entry)
            EntryKind.MOVE -> MoveEditor(entry, MoveForms.read(entry, ctx(OwnerKind.MOVE)))
            EntryKind.STATUS -> StatusEditor(entry, StatusForms.read(entry, ctx(OwnerKind.STATUS, id)))
            EntryKind.ABILITY -> HolderEditor(kind, entry, HolderForms.read(entry, ctx(OwnerKind.ABILITY, id)))
            EntryKind.ITEM -> HolderEditor(kind, entry, HolderForms.read(entry, ctx(OwnerKind.ITEM, id)))
            EntryKind.FIELD -> {
                val c = ctx(OwnerKind.FIELD, id)
                FieldEditor(entry, FieldForms.read(entry, c))
            }
            EntryKind.BAKEMON -> BakemonEditor(entry, BakemonForms.read(entry))
        }
    }

    /** Edits entry [id]; true if it changed. */
    fun edit(kind: EntryKind, id: String): Boolean {
        val entry = draft.entry(kind, id) ?: return false
        val json = editorFor(kind, entry).run() ?: return false
        if (creator.JsonTools.equal(json, entry)) return false
        draft.replace(kind, id, json)
        println("${kind.label.replaceFirstChar { it.uppercase() }} '${draft.nameOf(kind, id)}' updated (not saved yet).")
        return true
    }

    /** The add wizard of [kind]; returns the new id, or null if cancelled. */
    fun add(kind: EntryKind): String? {
        Terminal.header("New ${kind.label}")
        val name = askNewName(kind) ?: return null
        val id = draft.idFromName(kind, name)
        println(Terminal.dim("Its id will be '$id' (used in the files; 'Rename id' changes it later)."))
        val json: MutableMap<String, Any?> = when (kind) {
            EntryKind.TYPE -> {
                val used = draft.entries(EntryKind.TYPE).map { it["color"] }
                val color = pickColor(NICE_COLORS.firstOrNull { it !in used } ?: "white")
                draft.add(kind, TypeForms.build(TypeForm(id, name, color)))
                println("Type '$name' added.")
                if (draft.ids(EntryKind.TYPE).size > 1) {
                    if (Terminal.confirm("Set how $name attacks the other types now?", true)) editChart(id, attacking = true)
                    if (Terminal.confirm("Set how the other types attack $name now?", true)) editChart(id, attacking = false)
                }
                return id
            }
            EntryKind.MOVE -> {
                val c = ctx(OwnerKind.MOVE)
                var form = MoveForms.blank(c, name, id)
                form = form.copy(type = pickType(null, allowNone = true))
                pickCategory(form.category)?.let { form = form.copy(category = it) }
                if (form.category in c.damagingCategories) {
                    CreatorInput.pickRecipe("How strong is it?", PowerRecipes.available(c), c, form.power)?.let { form = form.copy(power = it) }
                    if (form.power.recipe != "none") {
                        form = askHits(form, c)
                        form = form.copy(ignoreDefense = askIgnore(form.ignoreDefense, c))
                    }
                    form = form.copy(charge = editCharge(null, c))
                } else {
                    form = form.copy(power = RecipeUse("none", emptyMap()), accuracy = null)
                    println(Terminal.dim("It deals no direct damage: add what it does under Effects."))
                }
                MoveEditor(null, form).run("Add the move")
            }
            EntryKind.STATUS -> {
                val kindValue = CreatorInput.chooseValue("Kind", StatusForms.KINDS, "debuff", null) ?: "debuff"
                val c = statusCtx(id, name, kindValue)
                val entry = StatusForms.build(StatusForm(id, name, kind = kindValue), c)
                offerPreset(entry, c, StatusForms.ORDER, "status")
                StatusEditor(entry, StatusForms.read(entry, c)).run("Add the status")
            }
            EntryKind.ABILITY, EntryKind.ITEM -> {
                val owner = if (kind == EntryKind.ITEM) OwnerKind.ITEM else OwnerKind.ABILITY
                val c = ctx(owner, id)
                val entry = HolderForms.build(HolderForm(id, name), c)
                offerPreset(entry, c, HolderForms.ORDER, kind.label)
                HolderEditor(kind, entry, HolderForms.read(entry, c)).run("Add the ${kind.label}")
            }
            EntryKind.FIELD -> {
                val options = listOf(Option("global", "The whole battle", "weather, terrain, rooms"), Option("side", "One side", "screens, hazards, tailwind"))
                val scope = CreatorInput.chooseValue("Where is it active?", options, "global", null) ?: "global"
                val c = fieldCtx(id, name, scope == "side", null)
                val entry = FieldForms.build(FieldForm(id, name, scope = scope), c)
                offerPreset(entry, c, FieldForms.ORDER, "field")
                FieldEditor(entry, FieldForms.read(entry, c.copy(sideField = entry["scope"] == "side"))).run("Add the field")
            }
            EntryKind.BAKEMON -> bakemonWizard(name)
        } ?: return null.also { println("Nothing was added.") }
        draft.add(kind, json)
        println("${kind.label.replaceFirstChar { it.uppercase() }} '$name' added (not saved yet).")
        return id
    }

    private fun askNewName(kind: EntryKind): String? {
        val name = Terminal.ask("Name of the new ${kind.label} (empty = cancel)").trim()
        return name.ifEmpty { null }
    }

    private fun bakemonWizard(name: String): MutableMap<String, Any?>? {
        var f = BakemonForms.blank(draft, name)
        if (draft.ids(EntryKind.TYPE).isNotEmpty()) pickTypes(f.types)?.let { f = f.copy(types = it) }
        println(Terminal.bold("Base stats") + Terminal.dim("  (Enter keeps the value in brackets)"))
        val stats = LinkedHashMap<String, Double>()
        for (s in statOptions()) {
            val suggested = STAT_DEFAULTS[s.value] ?: 50.0
            stats[s.value] = when (val a = CreatorInput.number(s.label, suggested, 0.0, null)) {
                is CreatorInput.NumberAnswer.Value -> a.value as Double
                else -> suggested
            }
        }
        f = f.copy(baseStats = stats)
        if (draft.ids(EntryKind.ABILITY).isNotEmpty()) pickAbilities(f.abilities)?.let { f = f.copy(abilities = it) }
        else println(Terminal.dim("No abilities yet (optional): you can add them later."))
        if (draft.ids(EntryKind.MOVE).isNotEmpty()) pickMoves(f.moves, f.types)?.let { f = f.copy(moves = it) }
        else Terminal.warn("There are no moves yet: a bakemon needs at least one learnable move (add moves, then edit it).")
        f = f.copy(description = CreatorInput.text("Description", null, allowNone = true) ?: "")
        f = f.copy(image = CreatorInput.text("Image file", f.image, allowNone = true, helpText = "A missing image is fine (only a warning)."))
        return BakemonEditor(null, f).run("Add the bakemon")
    }

    companion object {
        /** Move targets whose hits can be spread over random enemies (target "randomEnemies"). */
        private val SPREAD_TARGETS = setOf("enemy", "randomEnemy", "allEnemies", "randomEnemies", "lowestHpEnemy")

        private const val NO_TYPE = "\u0000none"

        /** "user.atk * 3.6" (a "Power from a stat" move). */
        private val STAT_POWER = Regex("^user[.]([A-Za-z0-9_]+) [*] ([0-9.]+)$")

        /** Suggested base values of the action bar stats that are percentages (the others start at 50). */
        private val STAT_DEFAULTS = mapOf("crate" to 15.0, "cdmg" to 50.0, "res" to 15.0, "acc" to 0.0)

        /** Colours suggested for new types, most readable first. */
        private val NICE_COLORS = listOf(
            "red", "blue", "green", "yellow", "magenta", "cyan", "brightRed", "brightBlue", "brightGreen", "brightYellow",
            "brightMagenta", "brightCyan", "white", "gray", "brightWhite", "black",
        )
    }
}
