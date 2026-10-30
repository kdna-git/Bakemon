package ui

import bakemon.MoveDef
import bakemon.Setup
import bakemon.Species
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Small terminal toolkit shared by the screens: coloured output, numbered menus and validated input.
 * Output is plain ASCII (safe on any Windows console); colours are ANSI escape codes, used only when [colors] is on.
 * Input is decoded with the console's charset (never failing on a byte it cannot decode).
 * End of input (Ctrl+Z / closed stdin) throws [InputClosed] so the app can exit cleanly.
 */
object Terminal {
    class InputClosed : RuntimeException("Input closed")

    /** Auto-detected by [detectColors]; can be forced with --color / --no-color. */
    var colors: Boolean = false

    private const val ESC = "\u001B["
    private val ANSI_CODE = Regex("\u001B\\[[0-9;]*m")

    private val COLOR_CODES: Map<String, Int> = buildMap {
        val names = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")
        names.forEachIndexed { i, name ->
            put(name, 30 + i)
            put("bright$name", 90 + i)
        }
        put("gray", 90)
        put("grey", 90)
        // "Bright gray" = the light gray of ANSI "white" (Types.json accepts bright<Name> for every colour name).
        put("brightgray", 37)
        put("brightgrey", 37)
    }

    /** True if [color] renders as a colour (same names as Types.json accepts, case-insensitive). */
    internal fun knowsColor(color: String): Boolean = color.lowercase() in COLOR_CODES

    /**
     * On if the program runs in an interactive console (input and output not redirected) and WT_SESSION, TERM,
     * ANSICON or ConEmuANSI=ON is set (Windows Terminal, most modern terminals). Piped output stays plain text.
     */
    fun detectColors(): Boolean {
        if (!interactiveConsole()) return false
        val env = System.getenv()
        val term = env["TERM"]
        return env["WT_SESSION"] != null ||
            (!term.isNullOrBlank() && term != "dumb") ||
            env["ANSICON"] != null ||
            env["ConEmuANSI"].equals("ON", ignoreCase = true)
    }

    /** False when input or output is redirected (System.console() is null up to JDK 21; isTerminal() from JDK 22). */
    private fun interactiveConsole(): Boolean {
        val console = System.console() ?: return false
        return try {
            console.javaClass.getMethod("isTerminal").invoke(console) as? Boolean ?: true
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Wraps [text] in the ANSI colour [color] (black red green yellow blue magenta cyan white gray,
     * and bright variants like brightRed). Unknown/null colour or colours off -> text unchanged.
     */
    fun color(text: String, color: String?): String {
        if (!colors || color == null || text.isEmpty()) return text
        val code = COLOR_CODES[color.lowercase()] ?: return text
        return "$ESC${code}m$text${ESC}39m"
    }

    fun bold(text: String): String = if (colors && text.isNotEmpty()) "${ESC}1m$text${ESC}22m" else text
    fun dim(text: String): String = if (colors && text.isNotEmpty()) "${ESC}2m$text${ESC}22m" else text

    /** Prints a title line like `== Title ==` (bold). */
    fun header(title: String) {
        println()
        println(bold("== $title =="))
    }

    /** Prints a yellow warning line. */
    fun warn(message: String) = println(color(message, "yellow"))

    /** Prints a red error line. */
    fun error(message: String) = println(color(message, "red"))

    /**
     * Where [readLine] reads from. Standard input decoded with [inputCharset]; bytes that are not valid in that
     * charset become U+FFFD instead of throwing (a Windows console sends its own code page, e.g. cp850, not UTF-8).
     */
    private var input: BufferedReader? = null

    /**
     * The charset of standard input: the console's charset when there is a console (Console.charset(), the console
     * code page on Windows), else the JDK's stdin.encoding property (JDK 25+), else the default charset.
     */
    internal fun inputCharset(): Charset {
        System.console()?.let { console ->
            try {
                return console.charset()
            } catch (e: Exception) {
                // Fall through to the properties below.
            }
        }
        val name = System.getProperty("stdin.encoding")
        if (!name.isNullOrBlank()) {
            try {
                return Charset.forName(name)
            } catch (e: Exception) {
                // Unknown name: use the default charset.
            }
        }
        return Charset.defaultCharset()
    }

    /** Reads from [stream] (decoded with [charset], malformed input replaced) from now on. Used by tests and [readLine]. */
    internal fun useInput(stream: InputStream, charset: Charset = inputCharset()) {
        input = BufferedReader(InputStreamReader(stream, charset))
    }

    /** Reads one trimmed line after printing [prompt]. Throws InputClosed at end of input. */
    fun readLine(prompt: String): String {
        val reader = input ?: BufferedReader(InputStreamReader(System.`in`, inputCharset())).also { input = it }
        while (true) {
            print(prompt)
            System.out.flush()
            val line = try {
                reader.readLine()
            } catch (e: CharacterCodingException) {
                // Not expected (the reader replaces malformed input), but never let a keystroke end the program.
                println()
                warn("That input could not be read. Please type it again.")
                continue
            } catch (e: IOException) {
                throw InputClosed()
            } ?: throw InputClosed()
            return line.trim()
        }
    }

    /** Free text; empty input returns [default] (if given). */
    fun ask(prompt: String, default: String? = null): String {
        val shown = if (!default.isNullOrEmpty()) "$prompt [$default]: " else "$prompt: "
        val answer = readLine(shown)
        return if (answer.isEmpty() && default != null) default else answer
    }

    /** Integer in [range]; re-asks until valid. Empty input returns [default] when given. */
    fun askInt(prompt: String, range: IntRange, default: Int? = null): Int {
        val hint = if (range.first == range.last) "${range.first}" else "${range.first}-${range.last}"
        val shown = if (default != null) "$prompt ($hint) [$default]: " else "$prompt ($hint): "
        while (true) {
            val answer = readLine(shown)
            if (answer.isEmpty() && default != null) return default
            val value = answer.toIntOrNull()
            if (value != null && value in range) return value
            warn("Please enter a number from ${range.first} to ${range.last}.")
        }
    }

    fun confirm(prompt: String, default: Boolean = false): Boolean {
        val shown = "$prompt (${if (default) "Y/n" else "y/N"}): "
        while (true) {
            when (readLine(shown).lowercase()) {
                "" -> return default
                "y", "yes" -> return true
                "n", "no" -> return false
                else -> warn("Please answer y or n.")
            }
        }
    }

    /**
     * Prints a numbered menu (1..n) and returns the chosen index (0-based).
     * With [backLabel] non-null, also shows `0) <backLabel>` and returns -1 for it.
     */
    fun menu(title: String, options: List<String>, backLabel: String? = "Back"): Int {
        require(options.isNotEmpty() || backLabel != null) { "A menu needs at least one option" }
        if (title.isNotEmpty()) println(bold(title))
        val width = options.size.toString().length
        val indent = menuIndent(options.size)
        options.forEachIndexed { i, option ->
            val lines = option.split('\n')
            println("  ${(i + 1).toString().padStart(width)}) ${lines.first()}")
            lines.drop(1).forEach { println(indent + it) }
        }
        if (backLabel != null) println("  ${"0".padStart(width)}) $backLabel")
        val min = if (backLabel != null) 0 else 1
        return askInt("Choose", min..options.size) - 1
    }

    /** Spaces matching the indentation of the option texts of a [menu] with [optionCount] options (for headers). */
    fun menuIndent(optionCount: Int): String = " ".repeat(4 + maxOf(optionCount, 1).toString().length)

    /** `[#########-----------]` coloured green / yellow / red by fraction (>50%, >20%, else). */
    fun hpBar(current: Int, max: Int, width: Int = 20): String {
        val fraction = if (max <= 0) 0.0 else (current.toDouble() / max).coerceIn(0.0, 1.0)
        var filled = (fraction * width).roundToInt()
        if (current > 0 && filled == 0) filled = 1
        if (current < max && filled == width && width > 1) filled = width - 1
        val tone = when {
            fraction > 0.5 -> "green"
            fraction > 0.2 -> "yellow"
            else -> "red"
        }
        return "[" + color("#".repeat(filled), tone) + "-".repeat(width - filled) + "]"
    }

    /** `[=====     ]` for action bars (fraction 0..1). */
    fun gaugeBar(fraction: Double, width: Int = 10): String {
        val f = if (fraction.isNaN()) 0.0 else fraction.coerceIn(0.0, 1.0)
        val filled = floor(f * width).toInt().coerceIn(0, width)
        val bar = "=".repeat(filled)
        return "[" + (if (filled == width) color(bar, "brightCyan") else color(bar, "cyan")) + " ".repeat(width - filled) + "]"
    }

    /** Pads/truncates to a fixed width, ignoring ANSI codes when measuring. */
    fun pad(text: String, width: Int): String {
        val w = maxOf(width, 0)
        val visible = visibleLength(text)
        if (visible <= w) return text + " ".repeat(w - visible)
        // Truncate visible characters but keep every escape code, so colours are still closed.
        val out = StringBuilder()
        var count = 0
        var i = 0
        while (i < text.length) {
            val match = ANSI_CODE.matchAt(text, i)
            if (match != null) {
                out.append(match.value)
                i += match.value.length
                continue
            }
            if (count < w) out.append(text[i])
            count++
            i++
        }
        return out.toString()
    }

    /** Length of [text] as shown on screen (ANSI codes excluded). */
    fun visibleLength(text: String): Int = ANSI_CODE.replace(text, "").length

    /**
     * Aligns [rows] into columns (ANSI-aware). Columns whose index is in [right] are right-aligned.
     * Returns one string per row, without trailing spaces.
     */
    fun table(rows: List<List<String>>, right: Set<Int> = emptySet(), gap: Int = 2): List<String> {
        val columns = rows.maxOfOrNull { it.size } ?: 0
        val widths = (0 until columns).map { c -> rows.maxOf { row -> row.getOrNull(c)?.let(::visibleLength) ?: 0 } }
        return rows.map { row ->
            row.mapIndexed { c, cell ->
                val fill = " ".repeat(widths[c] - visibleLength(cell))
                if (c in right) fill + cell else cell + fill
            }.joinToString(" ".repeat(gap)).trimEnd()
        }
    }

    /** Word-wraps [text] to [width] columns; every line starts with [indent] (included in the width). */
    fun wrap(text: String, width: Int = 78, indent: String = ""): String {
        val max = maxOf(width - indent.length, 20)
        val lines = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            val line = StringBuilder()
            for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                if (line.isNotEmpty() && line.length + 1 + word.length > max) {
                    lines += line.toString()
                    line.clear()
                }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            lines += line.toString()
        }
        return lines.joinToString("\n") { indent + it }
    }

    fun pause(message: String = "Press Enter to continue...") {
        readLine(dim(message))
    }
}

/** Setup-aware text snippets shared by the battle screen and the team creator. */
internal object Describe {
    /** 40.0 -> "40", 1.5 -> "1.5", 0.333 -> "0.33". */
    fun num(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }

    /** Type name coloured by its TypeDef colour. */
    fun type(setup: Setup, typeId: String): String = Terminal.color(setup.types.name(typeId), setup.types.types[typeId]?.color)

    fun types(setup: Setup, typeIds: List<String>): String =
        if (typeIds.isEmpty()) "-" else typeIds.joinToString("/") { type(setup, it) }

    fun moveType(setup: Setup, move: MoveDef): String = move.type?.let { type(setup, it) } ?: "-"

    fun category(move: MoveDef): String = move.category.replaceFirstChar { it.uppercaseChar() }

    /** Power as text: "-" for non-damaging moves, "var" when it is a formula. */
    fun power(setup: Setup, move: MoveDef): String {
        val power = move.power ?: return "-"
        if (!setup.isDamaging(move)) return "-"
        return power.constant?.let(::num) ?: "var"
    }

    /** Accuracy as text: "-" when the move never misses. */
    fun accuracy(move: MoveDef): String = move.accuracy?.let(::num) ?: "-"

    /** Max PP as text ("-" = unlimited). */
    fun pp(move: MoveDef): String = if (move.pp <= 0) "-" else move.pp.toString()

    fun cooldown(move: MoveDef): String = if (move.cooldown <= 0) "-" else move.cooldown.toString()

    /** "x2", "x0.5", "immune" (no colours). */
    fun effectiveness(multiplier: Double): String = if (multiplier == 0.0) "immune" else "x" + num(multiplier)

    /** Like [effectiveness], coloured: green above 1, red below 1, gray when immune; empty for x1 when [hideNeutral]. */
    fun effectivenessHint(multiplier: Double, hideNeutral: Boolean = false): String = when {
        multiplier == 0.0 -> Terminal.color("immune", "gray")
        multiplier > 1.0 -> Terminal.color(effectiveness(multiplier), "green")
        multiplier < 1.0 -> Terminal.color(effectiveness(multiplier), "red")
        hideNeutral -> ""
        else -> "x1"
    }

    /** Sum of the species' base stats. */
    fun baseTotal(species: Species): Int = species.baseStats.values.sum().roundToInt()

    /** Short stat label from the stat id: "Atk", "Spe", "Crate"... (at most 5 letters). */
    fun statShort(statId: String): String =
        statId.take(5).replaceFirstChar { it.uppercaseChar() }

    /** Header cells matching [moveCells]. */
    fun moveHeader(setup: Setup): List<String> = buildList {
        addAll(listOf("Move", "Type", "Cat.", "Pow", "Acc"))
        if (setup.rules.usePP) add("PP")
        if (setup.rules.useCooldowns) add("Cd")
    }

    /** Name, type, category, power, accuracy, then max PP (if Rules.pp) and cooldown (if Rules.cooldowns). */
    fun moveCells(setup: Setup, move: MoveDef): List<String> = buildList {
        addAll(listOf(move.name, moveType(setup, move), category(move), power(setup, move), accuracy(move)))
        if (setup.rules.usePP) add(pp(move))
        if (setup.rules.useCooldowns) add(cooldown(move))
    }

    /** Column indexes of [moveCells] that hold numbers (right-aligned). */
    fun moveNumberColumns(setup: Setup): Set<Int> = (3 until moveHeader(setup).size).toSet()
}
