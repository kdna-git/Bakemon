package ui

import creator.ConditionRecipes
import creator.EffectRecipes
import creator.JsonTools
import creator.Option
import creator.Param
import creator.ParamType
import creator.Recipe
import creator.RecipeContext
import creator.RecipeGroups
import creator.RecipeUse
import creator.Triggers
import util.Json

/**
 * Input widgets of the setup creator: choices with a default, paged lists with search, multi-select lists,
 * numbers with an optional "advanced" formula, multi-line JSON, generic forms, and the generic renderer of recipe
 * parameters (Number / Choice / Text / YesNo / Formula / Json). Every prompt accepts Enter for the shown default and
 * never fails on letters or empty input; end of input still ends the program (Terminal.InputClosed).
 */
internal object CreatorInput {
    /** Entries per page in long lists (PokemonGen1 has 343 moves). */
    const val PAGE_SIZE = 20

    /** Typed at a text prompt to clear an optional value. */
    const val NONE_INPUT = "-"

    /** Typed at a number prompt to type a formula instead. */
    private val ADVANCED_INPUTS = setOf("a", "adv", "advanced", "f", "formula")

    // ---- output helpers ---------------------------------------------------------------------------------------

    /** A dimmed help line (wrapped), printed only when [text] is not blank. */
    fun help(text: String?) {
        if (!text.isNullOrBlank()) println(Terminal.dim(Terminal.wrap(text, 78, "  ")))
    }

    /** "label - help" with the help dimmed. */
    fun withHelp(label: String, help: String?): String = if (help.isNullOrBlank()) label else "$label " + Terminal.dim("- $help")

    /** At most [max] characters (with "..." when cut). */
    fun short(text: String, max: Int = 70): String = if (text.length <= max) text else text.take(max - 3).trimEnd() + "..."

    /** Prints at most [max] lines of [lines] (with "... and N more"). */
    fun printLines(lines: List<String>, max: Int = 25, indent: String = "  ", color: String? = null) {
        lines.take(max).forEach { println(Terminal.color(indent + it, color)) }
        if (lines.size > max) println(Terminal.dim("$indent... and ${lines.size - max} more"))
    }

    /** "a move", "an ability". */
    fun article(word: String): String = (if (word.firstOrNull()?.lowercaseChar() in "aeiou".toList()) "an " else "a ") + word

    /** "1 move", "3 moves". */
    fun count(n: Int, singular: String, plural: String = singular + "s"): String = "$n ${if (n == 1) singular else plural}"

    // ---- choices --------------------------------------------------------------------------------------------------

    /**
     * A numbered list of [options] (label and one-line help). Enter picks [default] (an index) when given; with
     * [backLabel] also `0) <backLabel>` (returns -1). The option whose value is [current] is marked with "*".
     */
    fun choose(title: String, options: List<Option>, default: Int? = null, backLabel: String? = null, current: String? = null): Int {
        if (title.isNotEmpty()) println(Terminal.bold(title))
        val width = options.size.toString().length
        options.forEachIndexed { i, o ->
            val mark = if (current != null && o.value == current) "* " else ""
            println("  ${(i + 1).toString().padStart(width)}) " + withHelp(mark + o.label, o.help))
        }
        if (backLabel != null) println("  ${"0".padStart(width)}) $backLabel")
        val min = if (backLabel != null) 0 else 1
        if (options.isEmpty()) return -1
        return Terminal.askInt("Choose", min..options.size, default?.plus(1)) - 1
    }

    /** [choose] returning the chosen value; Enter keeps [current] (when it is one of the options). Null = back. */
    fun chooseValue(title: String, options: List<Option>, current: String?, backLabel: String? = "Cancel"): String? {
        val index = options.indexOfFirst { it.value == current }.takeIf { it >= 0 }
        val chosen = choose(title, options, index, backLabel, current)
        return if (chosen < 0) null else options[chosen].value
    }

    /** Like Terminal.menu (numbered options, `0) <backLabel>` returns -1), with Enter choosing [default] (an index). */
    fun menu(options: List<String>, backLabel: String, default: Int? = null): Int {
        val width = options.size.toString().length
        val indent = Terminal.menuIndent(options.size)
        options.forEachIndexed { i, option ->
            val lines = option.split('\n')
            println("  ${(i + 1).toString().padStart(width)}) ${lines.first()}")
            lines.drop(1).forEach { println(indent + it) }
        }
        println("  ${"0".padStart(width)}) $backLabel")
        return Terminal.askInt("Choose", 0..options.size, default?.plus(1)) - 1
    }

    /** Yes/no question with one help line. */
    fun yesNo(prompt: String, current: Boolean, helpText: String? = null): Boolean {
        help(helpText)
        return Terminal.confirm(prompt, current)
    }

    /** Whole number in [range]; Enter keeps [current]. */
    fun int(prompt: String, range: IntRange, current: Int, helpText: String? = null): Int {
        help(helpText)
        return Terminal.askInt(prompt, range, current.coerceIn(range))
    }

    /** Free text; Enter keeps [current]; [NONE_INPUT] clears it when [allowNone] (returns null). */
    fun text(prompt: String, current: String?, allowNone: Boolean = false, helpText: String? = null): String? {
        help(helpText)
        val hint = if (allowNone) " ($NONE_INPUT = none)" else ""
        val answer = Terminal.readLine(if (!current.isNullOrEmpty()) "$prompt$hint [$current]: " else "$prompt$hint: ")
        return when {
            answer.isEmpty() -> current
            allowNone && answer == NONE_INPUT -> null
            else -> answer
        }
    }

    /** A non-empty name (Enter keeps [current] when it is not empty). */
    fun name(prompt: String, current: String?): String {
        while (true) {
            val answer = text(prompt, current)?.trim()
            if (!answer.isNullOrEmpty()) return answer
            Terminal.warn("Please type a name.")
        }
    }

    // ---- numbers --------------------------------------------------------------------------------------------------

    /** What a number prompt returned. */
    sealed class NumberAnswer {
        object Keep : NumberAnswer()
        object Clear : NumberAnswer()
        data class Value(val value: Any) : NumberAnswer()
    }

    /**
     * Asks a number between [min] and [max] ([integer]: whole numbers). Enter keeps [current]; [allowNone]: "-" clears
     * it. With [checkFormula] non-null, "a" lets the user type a formula instead, checked at once (problems are
     * explained and the formula is asked again). A trailing "%" is accepted.
     */
    fun number(
        prompt: String,
        current: Any?,
        min: Double? = null,
        max: Double? = null,
        integer: Boolean = false,
        allowNone: Boolean = false,
        noneLabel: String = "none",
        checkFormula: ((String) -> List<String>)? = null,
    ): NumberAnswer {
        val range = when {
            min != null && max != null -> "${Describe.num(min)}-${Describe.num(max)}"
            min != null -> "${Describe.num(min)} or more"
            max != null -> "up to ${Describe.num(max)}"
            else -> ""
        }
        val hints = listOfNotNull(range.ifEmpty { null }, if (allowNone) "$NONE_INPUT = $noneLabel" else null, if (checkFormula != null) "a = advanced formula" else null)
        val shownCurrent = when (current) {
            null -> null
            is Number -> Describe.num(current.toDouble())
            else -> "formula"
        }
        val full = prompt + (if (hints.isEmpty()) "" else " (${hints.joinToString(", ")})") + (shownCurrent?.let { " [$it]" } ?: "") + ": "
        while (true) {
            val answer = Terminal.readLine(full)
            if (answer.isEmpty()) {
                if (current != null) return NumberAnswer.Keep
                if (allowNone) return NumberAnswer.Clear
                Terminal.warn("Please enter a number.")
                continue
            }
            if (allowNone && answer == NONE_INPUT) return NumberAnswer.Clear
            if (checkFormula != null && answer.lowercase() in ADVANCED_INPUTS) {
                val formula = formula(current as? String, checkFormula) ?: continue
                return formula.toDoubleOrNull()?.let { NumberAnswer.Value(it) } ?: NumberAnswer.Value(formula)
            }
            val value = answer.removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()
            when {
                value == null || value.isNaN() || value.isInfinite() ->
                    Terminal.warn("Please enter a number" + (if (checkFormula != null) " (or a for an advanced formula)." else "."))
                min != null && value < min -> Terminal.warn("Please enter at least ${Describe.num(min)}.")
                max != null && value > max -> Terminal.warn("Please enter at most ${Describe.num(max)}.")
                integer && value != Math.rint(value) -> Terminal.warn("Please enter a whole number.")
                else -> return NumberAnswer.Value(value)
            }
        }
    }

    /**
     * The "advanced" formula prompt: explains what a formula is, reads one and checks it with [check] (problems in
     * plain words, then it asks again). Empty input = cancel (null); Enter keeps nothing.
     */
    fun formula(current: String?, check: (String) -> List<String>): String? {
        help(
            "Advanced: type a formula, e.g. user.atk * 0.5, target.maxHp / 8 or self.hpPercent < 50 ? 2 : 1. " +
                "It may use numbers, + - * / ( ), comparisons, ? : and the names listed in README.md \"Formulas\". " +
                "It is checked right away. Empty = cancel" + (if (current != null) ", = keeps the current one" else "") + ".",
        )
        if (current != null) println(Terminal.dim("  Current formula: $current"))
        while (true) {
            val text = Terminal.readLine("Formula: ")
            if (text.isEmpty()) return null
            if (text == "=" && current != null) return current
            val problems = check(text)
            if (problems.isEmpty()) return text
            Terminal.warn("That formula cannot be used here:")
            problems.forEach { Terminal.warn("  - $it") }
            println(Terminal.dim("  Type it again (or press Enter to cancel)."))
        }
    }

    // ---- JSON -----------------------------------------------------------------------------------------------------

    /**
     * Reads JSON typed or pasted over one or more lines: it stops as soon as the text parses (or at an empty line /
     * a line "END"). An empty first line cancels (null). Syntax errors are explained and the user may try again.
     */
    fun json(prompt: String = "Paste or type the JSON (an empty line cancels)"): Any? =
        jsonText(prompt)?.let { Json.parse(it, "json") }

    /** Like [json] but returns the JSON text as typed (it is valid JSON), or null if cancelled. */
    fun jsonText(prompt: String = "Paste or type the JSON (an empty line cancels)"): String? {
        while (true) {
            println(Terminal.dim("  $prompt:"))
            val text = StringBuilder()
            while (true) {
                val line = Terminal.readLine(if (text.isEmpty()) "> " else ". ")
                if (text.isEmpty() && line.isEmpty()) return null
                if (line.isEmpty() || line == "END") break
                text.append(line).append('\n')
                if (parseError(text.toString()) == null) return text.toString()
            }
            val error = parseError(text.toString()) ?: return text.toString()
            Terminal.error("That is not valid JSON: $error")
            if (!Terminal.confirm("Try again?", true)) return null
        }
    }

    /** The syntax error of [text], or null if it is valid JSON. */
    private fun parseError(text: String): String? = try {
        Json.parse(text, "json")
        null
    } catch (e: Exception) {
        e.message ?: "invalid JSON"
    }

    /** Pretty JSON, indented. */
    fun printJson(value: Any?, indent: String = "  ") {
        Json.stringify(value, pretty = true).lines().forEach { println(indent + it) }
    }

    // ---- paged lists ----------------------------------------------------------------------------------------------

    /** Search text and page of a list screen (kept while the screen loops). */
    class ListState {
        var query: String = ""
        var page: Int = 0
    }

    /** What the user picked in [page]. */
    sealed class Picked {
        /** A row: its index in the full (filtered) list. */
        data class Row(val index: Int) : Picked()

        /** One of the caller's actions. */
        data class Action(val index: Int) : Picked()
        object Back : Picked()

        /** The page or the search changed: the caller filters again and shows the list again. */
        object Again : Picked()
    }

    /**
     * Shows one page of [rows] (already filtered by [state].query), then navigation, search and [actions]. Typing
     * text instead of a number searches. [tableHeader] (if any) is printed above the rows, aligned with them.
     */
    fun page(
        rows: List<String>,
        actions: List<String>,
        state: ListState,
        backLabel: String = "Back",
        tableHeader: String? = null,
        searchable: Boolean = true,
        enterAction: Int? = null,
    ): Picked {
        val pages = maxOf(1, (rows.size + PAGE_SIZE - 1) / PAGE_SIZE)
        state.page = state.page.coerceIn(0, pages - 1)
        val from = state.page * PAGE_SIZE
        val shown = rows.subList(from, minOf(rows.size, from + PAGE_SIZE))
        val options = mutableListOf<Pair<String, Picked>>()
        shown.forEachIndexed { i, row -> options += row to Picked.Row(from + i) }
        val canSearch = searchable && (rows.size > 10 || state.query.isNotEmpty())
        actions.forEachIndexed { i, a -> options += a to Picked.Action(i) }
        if (state.page < pages - 1) options += "Next page" to Picked.Again
        if (state.page > 0) options += "Previous page" to Picked.Again
        if (canSearch) options += (if (state.query.isEmpty()) "Search..." else "Show all (clear the search)") to Picked.Again
        if (state.query.isNotEmpty()) println(Terminal.dim("Search '${state.query}': ${count(rows.size, "match", "matches")}"))
        if (pages > 1) println(Terminal.dim("Page ${state.page + 1} of $pages (${rows.size} in all)"))
        if (rows.isEmpty()) println(Terminal.dim(if (state.query.isEmpty()) "  (none yet)" else "  (nothing matches)"))
        val width = options.size.toString().length
        val indent = " ".repeat(4 + width)
        if (tableHeader != null && shown.isNotEmpty()) println(indent + tableHeader)
        options.forEachIndexed { i, (text, _) ->
            val lines = text.split('\n')
            println("  ${(i + 1).toString().padStart(width)}) ${lines.first()}")
            lines.drop(1).forEach { println(indent + it) }
        }
        println("  ${"0".padStart(width)}) $backLabel")
        val enterNumber = enterAction?.let { a -> options.indexOfFirst { it.second == Picked.Action(a) } + 1 }?.takeIf { it > 0 }
        val prompt = "Choose (0-${options.size})" + (if (canSearch) ", or type text to search" else "") + (enterNumber?.let { " [$it]" } ?: "") + ": "
        while (true) {
            val answer = Terminal.readLine(prompt)
            val n = answer.toIntOrNull()
            when {
                answer.isEmpty() && enterAction != null -> return Picked.Action(enterAction)
                answer.isEmpty() -> Terminal.warn("Please enter a number from 0 to ${options.size}.")
                n == 0 -> return Picked.Back
                n != null && n in 1..options.size -> {
                    val (text, picked) = options[n - 1]
                    if (picked === Picked.Again) {
                        when (text) {
                            "Next page" -> state.page++
                            "Previous page" -> state.page--
                            "Search..." -> {
                                state.query = Terminal.ask("Search (part of a name or id)").trim()
                                state.page = 0
                            }
                            else -> {
                                state.query = ""
                                state.page = 0
                            }
                        }
                    }
                    return picked
                }
                n != null -> Terminal.warn("Please enter a number from 0 to ${options.size}.")
                searchable -> {
                    state.query = answer
                    state.page = 0
                    return Picked.Again
                }
                else -> Terminal.warn("Please enter a number from 0 to ${options.size}.")
            }
        }
    }

    /** True if [query] is empty or any of [texts] contains it (ignoring case). */
    fun matches(query: String, vararg texts: String?): Boolean =
        query.isBlank() || texts.any { it != null && it.contains(query.trim(), ignoreCase = true) }

    /**
     * Multi-select over [options] (paged, searchable): picking a row adds or removes it. [shortcuts] are extra actions
     * that return a new selection. [ordered]: the order of the selection matters (first = default) and is shown.
     * Returns the new selection, or null if cancelled.
     */
    fun pickMany(
        title: String,
        options: List<Option>,
        selected: List<String>,
        shortcuts: List<Pair<String, (List<String>) -> List<String>>> = emptyList(),
        ordered: Boolean = false,
        max: Int? = null,
        intro: String? = null,
    ): List<String>? {
        var chosen = selected.toMutableList()
        val state = ListState()
        while (true) {
            Terminal.header("$title (${chosen.size} chosen${if (max != null) " of at most $max" else ""})")
            help(intro)
            val unknown = chosen.filter { id -> options.none { it.value == id } }
            if (unknown.isNotEmpty()) Terminal.warn("Not in the list (kept unless you remove them): ${unknown.joinToString(", ")}")
            if (ordered && chosen.isNotEmpty()) {
                println("Chosen, in order: " + chosen.joinToString(", ") { id -> options.firstOrNull { it.value == id }?.label ?: id })
            }
            val all = options + unknown.map { Option(it, "$it (unknown)") }
            val visible = all.filter { matches(state.query, it.label, it.value, it.help) }
            val rows = visible.map { o ->
                val mark = if (o.value in chosen) Terminal.color("[x]", "green") else "[ ]"
                "$mark " + withHelp(o.label, o.help)
            }
            val actions = listOf("Done") + shortcuts.map { it.first } + listOf("Clear the selection")
            when (val p = page(rows, actions, state, "Cancel", enterAction = 0)) {
                is Picked.Row -> {
                    val id = visible[p.index].value
                    when {
                        id in chosen -> chosen.remove(id)
                        max != null && chosen.size >= max -> Terminal.warn("At most $max: remove one first.")
                        else -> chosen.add(id)
                    }
                }
                is Picked.Action -> when (p.index) {
                    0 -> return chosen
                    actions.size - 1 -> chosen.clear()
                    else -> {
                        val result = shortcuts[p.index - 1].second(chosen)
                        val added = result.count { it !in chosen }
                        chosen = result.distinct().toMutableList()
                        if (max != null && chosen.size > max) {
                            chosen = chosen.take(max).toMutableList()
                            Terminal.warn("Only the first $max were kept.")
                        }
                        println("${count(added, "entry", "entries")} added.")
                    }
                }
                Picked.Back -> return if (chosen == selected || Terminal.confirm("Discard the changes to this selection?", true)) null else continue
                Picked.Again -> {}
            }
        }
    }

    // ---- forms ----------------------------------------------------------------------------------------------------

    /** One line of a form: what it shows and what picking it does. */
    class Row(val label: String, val value: String, val action: () -> Unit)

    /**
     * A form loop: `1) Done`, then one numbered row per field ("Label: value"; picking it edits the field), `0) Cancel`.
     * [problems] are shown above the menu. Returns true for Done, false for Cancel (after confirming when [changed]).
     */
    fun form(
        title: () -> String,
        rows: () -> List<Row>,
        changed: () -> Boolean,
        problems: () -> List<String> = { emptyList() },
        intro: (() -> Unit)? = null,
        doneLabel: String = "Done",
        cancelLabel: String = "Cancel",
        confirmProblems: Boolean = true,
    ): Boolean {
        while (true) {
            Terminal.header(title())
            intro?.invoke()
            val issues = problems()
            if (issues.isNotEmpty()) {
                Terminal.warn("To check:")
                printLines(issues, 8, "  - ", "yellow")
            }
            val list = rows()
            val options = listOf(doneLabel) + list.map { row -> if (row.value.isEmpty()) row.label else "${row.label}: ${row.value}" }
            val index = menu(options, cancelLabel, default = 0)
            when {
                index == 0 -> {
                    if (issues.isEmpty() || !confirmProblems || Terminal.confirm("There are things to check (see above). Keep it anyway?", false)) return true
                }
                index > 0 -> guard { list[index - 1].action() }
                !changed() || Terminal.confirm("Discard the changes?", true) -> return false
            }
        }
    }

    /**
     * Runs [block]; an unexpected error is reported and the screen goes on (end of input still ends the program).
     * Returns false if it failed.
     */
    fun guard(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Terminal.InputClosed) {
        throw e
    } catch (e: creator.DraftException) {
        Terminal.error(e.message ?: "That cannot be done.")
        false
    } catch (e: RuntimeException) {
        Terminal.error("Something went wrong: ${e.message ?: e::class.simpleName}")
        false
    }

    // ---- recipe parameters ----------------------------------------------------------------------------------------

    /** A parameter value in plain words for forms (formulas only as "advanced formula: ..."). */
    fun showValue(p: Param, v: Any?, ctx: RecipeContext? = null): String {
        if (v == null) return if (p.optional) Terminal.dim("default") else Terminal.dim("not set")
        return when (val t = p.type) {
            is ParamType.Number -> when (v) {
                is Number -> Describe.num(v.toDouble()) + if (t.percent) "%" else ""
                else -> v.toString().toDoubleOrNull()?.let { Describe.num(it) + if (t.percent) "%" else "" } ?: "advanced formula: $v"
            }
            is ParamType.Choice -> if (t.multiple) {
                (v as? List<*>)?.joinToString(", ") { x -> t.options.firstOrNull { it.value == x }?.label ?: x.toString() } ?: v.toString()
            } else {
                t.options.firstOrNull { it.value == v }?.label ?: "$v (not in the list)"
            }
            ParamType.YesNo -> if (v == true) "yes" else "no"
            ParamType.Text -> if (v.toString().isEmpty()) Terminal.dim("(empty)") else "\"$v\""
            is ParamType.Formula -> "advanced formula: $v"
            ParamType.Json -> "advanced JSON: " + short(Json.stringify(v, pretty = false), 60)
            is ParamType.Effects -> {
                val uses = (v as? List<*>).orEmpty().filterIsInstance<RecipeUse>()
                when {
                    uses.isEmpty() -> "none"
                    ctx == null -> count(uses.size, "effect")
                    else -> short(uses.joinToString("; ") { Triggers.describe(it, EffectRecipes.byId(it.recipe), ctx) }, 90)
                }
            }
        }
    }

    /**
     * Asks one parameter. Enter keeps [current] (or the default). Returns the new value, or [current] when kept;
     * [CLEARED] when an optional value was cleared.
     */
    fun askParam(p: Param, current: Any?, ctx: RecipeContext): Any? {
        val label = p.label
        when (val t = p.type) {
            is ParamType.Number -> {
                help(p.help)
                val unit = if (t.percent && "%" !in label && !label.contains("percent", ignoreCase = true)) " (%)" else ""
                val check: ((String) -> List<String>)? = if (t.formula) { text -> ctx.formulaProblems(text, t.scope, !t.noRecipient) } else null
                return when (val a = number(label + unit, current, t.min, t.max, t.integer, p.optional, checkFormula = check)) {
                    NumberAnswer.Keep -> current
                    NumberAnswer.Clear -> CLEARED
                    is NumberAnswer.Value -> a.value
                }
            }
            is ParamType.Choice -> {
                if (t.options.isEmpty()) {
                    Terminal.warn("$label: there is nothing to choose from yet.")
                    return current
                }
                help(p.help)
                val options = if (p.optional) t.options + Option(NONE_VALUE, "(default)", defaultHelp(p.help)) else t.options
                if (t.multiple) {
                    val now = (current as? List<*>)?.map { it.toString() } ?: emptyList()
                    return pickMany(label, t.options, now) ?: current
                }
                val now = (current as? String) ?: if (current == null && p.optional) NONE_VALUE else null
                if (now != null && options.none { it.value == now }) println(Terminal.dim("  Current value: $now (not in the list; 0 keeps it)"))
                val chosen = (if (options.size > LONG_CHOICE) pickLong(label, options, now) else chooseValue(label, options, now, backLabel = "Keep the current value")) ?: return current
                return if (chosen == NONE_VALUE) CLEARED else chosen
            }
            ParamType.Text -> {
                help(p.help)
                val hint = if (p.optional) " ($NONE_INPUT = default, \"\" = empty)" else " (\"\" = empty)"
                val shown = (current as? String)?.let { if (it.isEmpty()) "\"\"" else it }
                val answer = Terminal.readLine("$label$hint" + (shown?.let { " [$it]" } ?: "") + ": ")
                return when {
                    answer.isEmpty() -> current ?: if (p.optional) CLEARED else ""
                    answer == "\"\"" -> ""
                    p.optional && answer == NONE_INPUT -> CLEARED
                    else -> answer
                }
            }
            ParamType.YesNo -> return yesNo(label, current == true, p.help)
            is ParamType.Formula -> {
                help(p.help)
                return formula(current as? String) { ctx.formulaProblems(it, t.scope, !t.noRecipient) } ?: current
            }
            ParamType.Json -> {
                help(p.help)
                if (current != null) println(Terminal.dim("  Current: " + Json.stringify(current, pretty = false)))
                return json("Type or paste the JSON (an empty line keeps the current value)") ?: current
            }
            is ParamType.Effects -> {
                help(p.help)
                val now = (current as? List<*>).orEmpty().filterIsInstance<RecipeUse>()
                return editUses(label, now, ctx, effects = true, exclude = t.exclude)
            }
        }
    }

    /** Choices longer than this (e.g. every move of the setup) are shown as a paged list with search. */
    private const val LONG_CHOICE = 25

    /** One value of a long list of [options] (paged, searchable); Enter / 0 keeps [current] (null). */
    private fun pickLong(title: String, options: List<Option>, current: String?): String? {
        val state = ListState()
        while (true) {
            Terminal.header(title)
            options.firstOrNull { it.value == current }?.let { println(Terminal.dim("  Now: " + it.label)) }
            val visible = options.filter { matches(state.query, it.label, it.value, it.help) }
            when (val p = page(visible.map { withHelp(it.label, it.help) }, emptyList(), state, "Keep the current value")) {
                is Picked.Row -> return visible[p.index].value
                Picked.Back -> return null
                else -> {}
            }
        }
    }

    /** What an empty optional value means, from a help text like "... (empty = always)" or "Empty = the target.". */
    private fun defaultHelp(help: String): String =
        Regex("(?i)empty = ([^)]*)").find(help)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: "leave it out"

    /** Returned by [askParam] when an optional value is cleared. */
    val CLEARED = Any()
    private const val NONE_VALUE = "\u0000none"

    /** Sets or removes [id] in [values] from an [askParam] answer. */
    fun put(values: MutableMap<String, Any?>, id: String, answer: Any?) {
        if (answer === CLEARED || answer == null) values.remove(id) else values[id] = answer
    }

    /** The parameters of [recipe] shown to the user (an ability never uses up an item). */
    fun visibleParams(recipe: Recipe, ctx: RecipeContext): List<Param> =
        recipe.params(ctx).filter { !(it.id == "consumable" && ctx.owner != creator.OwnerKind.ITEM) }

    /** Asks every parameter of [recipe] in order (a wizard); Enter keeps each shown value. */
    fun askParams(recipe: Recipe, start: Map<String, Any?>, ctx: RecipeContext): MutableMap<String, Any?> {
        val values = LinkedHashMap(start)
        for (p in visibleParams(recipe, ctx)) put(values, p.id, askParam(p, values[p.id] ?: if (p.optional) null else p.default, ctx))
        return values
    }

    /** A form of the parameters of [recipe]; returns the new values, or null if cancelled. */
    fun editParams(title: String, recipe: Recipe, start: Map<String, Any?>, ctx: RecipeContext): Map<String, Any?>? {
        val params = visibleParams(recipe, ctx)
        if (params.isEmpty()) {
            println(Terminal.dim("\"${recipe.label}\" has nothing to set."))
            return start
        }
        val values = LinkedHashMap(start)
        val done = form(
            title = { title },
            intro = { help(recipe.help) },
            rows = { params.map { p -> Row(p.label, showValue(p, values[p.id] ?: if (p.optional) null else p.default, ctx)) { put(values, p.id, askParam(p, values[p.id] ?: if (p.optional) null else p.default, ctx)) } } },
            changed = { values != start },
            problems = { recipe.problems(values, ctx) },
        )
        return if (done) values else null
    }

    /**
     * Picks a recipe from [recipes] (label and help), then asks its parameters. [current] is the use being replaced
     * (its recipe is the default). Returns null if cancelled.
     */
    fun pickRecipe(title: String, recipes: List<Recipe>, ctx: RecipeContext, current: RecipeUse? = null): RecipeUse? {
        if (recipes.isEmpty()) {
            Terminal.warn("Nothing can be added here yet.")
            return null
        }
        val id = chooseRecipe(title, recipes, current) ?: return null
        val recipe = recipes.first { it.id == id }
        val start = if (current != null && current.recipe == id) current.values else recipe.defaults(ctx)
        if (visibleParams(recipe, ctx).isEmpty()) return RecipeUse(id, start)
        println(Terminal.bold(recipe.label) + Terminal.dim("  (Enter keeps the value in brackets)"))
        val values = askParams(recipe, start, ctx)
        val problems = recipe.problems(values, ctx)
        if (problems.isNotEmpty()) {
            Terminal.warn("To check:")
            printLines(problems, 8, "  - ", "yellow")
            return editParams(recipe.label, recipe, values, ctx)?.let { RecipeUse(id, it) }
        }
        return RecipeUse(id, values)
    }

    /** Lists with more recipes than this are shown in two steps: a group, then a recipe of the group. */
    private const val GROUP_AFTER = 12

    /**
     * The id of a recipe picked from [recipes] (label and help; [current] is the default), or null if cancelled. Long
     * lists whose recipes have groups (RecipeGroups) are shown as a group menu first ("0) Back" returns to it).
     */
    private fun chooseRecipe(title: String, recipes: List<Recipe>, current: RecipeUse?): String? {
        fun options(list: List<Recipe>) = list.map { Option(it.id, it.label, it.help) }
        val groups = (RecipeGroups.ORDER + recipes.map { it.group }).distinct().filter { g -> recipes.any { it.group == g } }
        if (recipes.size <= GROUP_AFTER || groups.size < 2 || recipes.any { it.group.isEmpty() }) {
            Terminal.header(title)
            return chooseValue("", options(recipes), current?.recipe, "Cancel")
        }
        val currentGroup = recipes.firstOrNull { it.id == current?.recipe }?.group
        while (true) {
            Terminal.header(title)
            val group = chooseValue("", groups.map { Option(it, it, RecipeGroups.help(it)) }, currentGroup, "Cancel") ?: return null
            val inGroup = recipes.filter { it.group == group }
            if (inGroup.size == 1) return inGroup[0].id
            Terminal.header("$title ($group)")
            chooseValue("", options(inGroup), current?.recipe, "Back to the groups")?.let { return it }
        }
    }

    /** Picks an effect (DO) that works in [ctx] ([exclude]: effect recipes not offered); null if cancelled. */
    fun pickEffect(ctx: RecipeContext, current: RecipeUse?, title: String = "What does it do?", exclude: Set<String> = emptySet()): RecipeUse? =
        pickRecipe(title, EffectRecipes.available(ctx).filter { it.id !in exclude }, ctx, current)

    /** Picks a condition (IF) that works in [ctx]; null if cancelled. */
    fun pickCondition(ctx: RecipeContext, current: RecipeUse?): RecipeUse? =
        pickRecipe("Only if...", ConditionRecipes.available(ctx), ctx, current)

    /**
     * A list of conditions ([effects] false) or effects: pick one to change, replace, move or remove it; add one. Used
     * for a trigger's IF and DO and for the effects of a delayed effect. Returns the new list.
     */
    fun editUses(title: String, start: List<RecipeUse>, c: RecipeContext, effects: Boolean, exclude: Set<String> = emptySet()): List<RecipeUse> {
        val list = start.toMutableList()
        fun recipe(use: RecipeUse): Recipe? = if (effects) EffectRecipes.byId(use.recipe) else ConditionRecipes.byId(use.recipe)
        fun pick(current: RecipeUse?) = if (effects) pickEffect(c, current, exclude = exclude) else pickCondition(c, current)
        while (true) {
            Terminal.header(title)
            val options = list.map { short(describe(it, recipe(it), c), 110) } + (if (effects) "Add an effect" else "Add a condition")
            val index = Terminal.menu("", options, "Back")
            when {
                index < 0 -> return list
                index == list.size -> pick(null)?.let { list += it }
                else -> {
                    val use = list[index]
                    val actions = mutableListOf<Pair<String, () -> Unit>>()
                    val r = recipe(use)
                    if (r != null && visibleParams(r, c).isNotEmpty()) actions += "Change the values" to {
                        editParams(r.label, r, use.values, c)?.let { list[index] = RecipeUse(use.recipe, it) }
                    }
                    actions += "Replace with another one" to { pick(use)?.let { list[index] = it } }
                    if (index > 0) actions += "Move up" to { list.add(index - 1, list.removeAt(index)) }
                    actions += "Remove" to { list.removeAt(index) }
                    val chosen = Terminal.menu(describe(use, r, c), actions.map { it.first }, "Back")
                    if (chosen >= 0) guard { actions[chosen].second() }
                }
            }
        }
    }

    /** A recipe use in plain words ("Fixed power (power: 40)"). */
    fun describe(use: RecipeUse, recipe: Recipe?, ctx: RecipeContext): String =
        Triggers.describe(use, recipe, ctx).replaceFirstChar { it.uppercase() }

    /** A number for a JSON value written by the creator (always Double). */
    fun jsonNumber(value: Double): Double = JsonTools.round(value)
}
