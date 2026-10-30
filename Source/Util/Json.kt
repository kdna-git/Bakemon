package util

import java.io.File

/** Thrown when a setup or team file is invalid. The message is meant to be shown to the user as is. */
class SetupException(message: String) : Exception(message)

/** Thrown for malformed JSON text. The message includes the source name, line and column. */
class JsonParseException(message: String) : Exception(message)

/**
 * Minimal JSON reader/writer (no external dependencies).
 *
 * Parsed values are plain Kotlin values:
 *  - object  -> LinkedHashMap<String, Any?> (keeps key order)
 *  - array   -> List<Any?>
 *  - string  -> String
 *  - number  -> Double (always)
 *  - boolean -> Boolean
 *  - null    -> null
 *
 * The parser is strict JSON, except that it also accepts `//` line comments, slash-star block comments
 * and trailing commas, which makes hand-written setup files friendlier.
 */
object Json {
    /**
     * Parses [text]. [source] is used in error messages (e.g. the file name).
     * Errors throw [JsonParseException] with a message like `Moves.json:12:5: expected ',' or '}' but found '"'`.
     * Duplicate keys in one object and anything after the value (except blanks/comments) are errors.
     */
    fun parse(text: String, source: String = "json"): Any? = JsonParser(text, source).parseDocument()

    /**
     * Reads and parses a UTF-8 file (a BOM is ignored). Error messages use the file name as the source.
     * @throws java.io.IOException if the file cannot be read.
     */
    fun parseFile(file: File): Any? = parse(file.readText(Charsets.UTF_8), file.name)

    /**
     * Serializes Maps, Lists, Strings, Numbers, Booleans and null.
     * Whole doubles are written without a decimal part (`50`, not `50.0`).
     * With [pretty], objects/arrays are indented with 2 spaces; short arrays of scalars stay on one line.
     * Without it the output has no whitespace at all.
     * @throws IllegalArgumentException for NaN/Infinity or values of other types.
     */
    fun stringify(value: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        JsonWriter(pretty, sb).write(value, 0)
        return sb.toString()
    }

    /** Writes [value] as pretty JSON (UTF-8, trailing newline), creating parent folders if needed. */
    fun writeFile(file: File, value: Any?) {
        val text = stringify(value, pretty = true) + "\n"   // serialize first so a bad value never truncates the file
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    /**
     * Formats a number the way [stringify] writes it: whole values without ".0" (`50`, `-3`),
     * others in the shortest form that reads back exactly (`0.5`, `1E-7`). NaN/Infinity give "NaN"/"Infinity".
     */
    fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == Math.rint(value) && Math.abs(value) < 1e18) return value.toLong().toString()
        return value.toString().replace(".0E", "E")
    }

    /** Short description of a parsed value's JSON type for messages: "a string", "a number", "an object", "null"... */
    fun typeName(value: Any?): String = when (value) {
        null -> "null"
        is String -> "a string"
        is Number -> "a number"
        is Boolean -> "a boolean"
        is Map<*, *> -> "an object"
        is List<*> -> "an array"
        else -> "a ${value::class.simpleName ?: "value"}"
    }
}

/** Recursive-descent parser over [text]. Positions are converted to line/column only when reporting errors. */
private class JsonParser(private val text: String, private val source: String) {
    private var pos = 0
    private var depth = 0

    fun parseDocument(): Any? {
        if (text.isNotEmpty() && text[0] == '\uFEFF') pos = 1
        skipBlank()
        if (pos >= text.length) fail("unexpected end of input (expected a value)")
        val value = parseValue()
        skipBlank()
        if (pos < text.length) fail("unexpected ${describe(text[pos])} after the end of the JSON value")
        return value
    }

    private fun parseValue(): Any? {
        skipBlank()
        if (pos >= text.length) fail("unexpected end of input (expected a value)")
        val c = text[pos]
        return when {
            c == '{' -> parseObject()
            c == '[' -> parseArray()
            c == '"' -> parseString()
            c == '-' || c in '0'..'9' -> parseNumber()
            c.isLetter() || c == '_' -> parseWord()
            c == '\'' -> fail("strings must use double quotes")
            else -> fail("unexpected ${describe(c)} (expected a value)")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val start = pos
        enter()
        pos++ // '{'
        val map = LinkedHashMap<String, Any?>()
        skipBlank()
        if (pos < text.length && text[pos] == '}') {
            pos++
            depth--
            return map
        }
        while (true) {
            skipBlank()
            if (pos >= text.length) unclosed("object", start)
            val c = text[pos]
            if (c != '"') {
                fail(
                    when {
                        c == '\'' -> "object keys must use double quotes"
                        c.isLetter() || c == '_' -> "object keys must be strings in double quotes"
                        else -> "expected a string key but found ${describe(c)}"
                    }
                )
            }
            val keyPos = pos
            val key = parseString()
            if (map.containsKey(key)) fail("duplicate key '$key'", keyPos)
            skipBlank()
            if (pos >= text.length) unclosed("object", start)
            if (text[pos] != ':') fail("expected ':' after key '$key' but found ${describe(text[pos])}")
            pos++
            skipBlank()
            if (pos >= text.length) unclosed("object", start)
            map[key] = parseValue()
            skipBlank()
            if (pos >= text.length) unclosed("object", start)
            when (text[pos]) {
                ',' -> {
                    pos++
                    skipBlank()
                    if (pos < text.length && text[pos] == '}') break // trailing comma
                }
                '}' -> break
                else -> fail("expected ',' or '}' but found ${describe(text[pos])}")
            }
        }
        pos++ // '}'
        depth--
        return map
    }

    private fun parseArray(): List<Any?> {
        val start = pos
        enter()
        pos++ // '['
        val list = ArrayList<Any?>()
        skipBlank()
        if (pos < text.length && text[pos] == ']') {
            pos++
            depth--
            return list
        }
        while (true) {
            skipBlank()
            if (pos >= text.length) unclosed("array", start)
            list += parseValue()
            skipBlank()
            if (pos >= text.length) unclosed("array", start)
            when (text[pos]) {
                ',' -> {
                    pos++
                    skipBlank()
                    if (pos < text.length && text[pos] == ']') break // trailing comma
                }
                ']' -> break
                else -> fail("expected ',' or ']' but found ${describe(text[pos])}")
            }
        }
        pos++ // ']'
        depth--
        return list
    }

    private fun parseString(): String {
        val start = pos
        pos++ // opening quote
        var sb: StringBuilder? = null
        var chunk = pos
        while (true) {
            if (pos >= text.length) fail("unterminated string", start)
            val c = text[pos]
            when {
                c == '"' -> {
                    val result = if (sb == null) text.substring(chunk, pos) else sb.append(text, chunk, pos).toString()
                    pos++
                    return result
                }
                c == '\\' -> {
                    var out = sb
                    if (out == null) {
                        out = StringBuilder()
                        sb = out
                    }
                    out.append(text, chunk, pos)
                    if (pos + 1 >= text.length) fail("unterminated string", start)
                    when (val e = text[pos + 1]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            // A surrogate pair arrives as two \u escapes; appending both UTF-16 units rebuilds it.
                            out.append(hex4(pos + 2))
                            pos += 4
                        }
                        else -> fail("invalid escape '\\$e' in string")
                    }
                    pos += 2
                    chunk = pos
                }
                c == '\n' || c == '\r' -> fail("line break inside a string (missing closing quote?)")
                c < ' ' -> fail("unescaped control character ${describe(c)} in string")
                else -> pos++
            }
        }
    }

    /** Reads the 4 hex digits of a \u escape starting at [at]. */
    private fun hex4(at: Int): Char {
        if (at + 4 > text.length) fail("invalid \\u escape (expected 4 hex digits)", at - 2)
        var code = 0
        for (i in at until at + 4) {
            val d = Character.digit(text[i], 16)
            if (d < 0) fail("invalid \\u escape (expected 4 hex digits)", at - 2)
            code = code * 16 + d
        }
        return code.toChar()
    }

    private fun parseNumber(): Double {
        val start = pos
        if (text[pos] == '-') pos++
        if (pos >= text.length || text[pos] !in '0'..'9') fail("invalid number", start)
        if (text[pos] == '0') {
            pos++
            if (pos < text.length && text[pos] in '0'..'9') fail("invalid number (leading zeros are not allowed)", start)
        } else {
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        if (pos < text.length && text[pos] == '.') {
            pos++
            if (pos >= text.length || text[pos] !in '0'..'9') fail("invalid number (expected a digit after '.')", start)
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            if (pos >= text.length || text[pos] !in '0'..'9') fail("invalid number (expected a digit in the exponent)", start)
            while (pos < text.length && text[pos] in '0'..'9') pos++
        }
        val value = text.substring(start, pos).toDouble()
        if (value.isInfinite()) fail("number out of range", start)
        return value
    }

    /** true / false / null; any other bare word is an error. */
    private fun parseWord(): Any? {
        val start = pos
        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
        return when (val word = text.substring(start, pos)) {
            "true" -> true
            "false" -> false
            "null" -> null
            "NaN", "Infinity" -> fail("'$word' is not valid JSON", start)
            else -> fail("unexpected '$word' (strings must be in double quotes)", start)
        }
    }

    /** Skips whitespace, `//` line comments and block comments. */
    private fun skipBlank() {
        while (pos < text.length) {
            val c = text[pos]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++
            } else if (c == '/' && pos + 1 < text.length && text[pos + 1] == '/') {
                while (pos < text.length && text[pos] != '\n') pos++
            } else if (c == '/' && pos + 1 < text.length && text[pos + 1] == '*') {
                val end = text.indexOf("*/", pos + 2)
                if (end < 0) fail("unterminated comment", pos)
                pos = end + 2
            } else {
                return
            }
        }
    }

    private fun enter() {
        if (++depth > MAX_DEPTH) fail("nesting is too deep (more than $MAX_DEPTH levels)")
    }

    private fun unclosed(what: String, start: Int): Nothing {
        val (line, col) = lineCol(start)
        fail("unexpected end of input (the $what that starts at line $line, column $col is not closed)", text.length)
    }

    private fun describe(c: Char): String =
        if (c in ' '..'~') "'$c'" else "character U+%04X".format(c.code)

    private fun lineCol(at: Int): Pair<Int, Int> {
        var line = 1
        var lineStart = 0
        for (i in 0 until minOf(at, text.length)) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return line to (at - lineStart + 1)
    }

    private fun fail(problem: String, at: Int = pos): Nothing {
        val (line, col) = lineCol(at)
        throw JsonParseException("$source:$line:$col: $problem")
    }

    private companion object {
        const val MAX_DEPTH = 512
    }
}

/** Pretty or compact serializer. */
private class JsonWriter(private val pretty: Boolean, private val sb: StringBuilder) {

    fun write(value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value)
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Number -> writeNumber(value.toDouble())
            is Map<*, *> -> writeMap(value, indent)
            is Collection<*> -> writeList(value, indent)
            is Array<*> -> writeList(value.asList(), indent)
            else -> throw IllegalArgumentException("Json.stringify: cannot write ${Json.typeName(value)} as JSON")
        }
    }

    private fun writeNumber(d: Double) {
        require(!d.isNaN() && !d.isInfinite()) { "Json.stringify: NaN and Infinity cannot be written as JSON" }
        sb.append(Json.formatNumber(d))
    }

    private fun writeMap(map: Map<*, *>, indent: Int) {
        if (map.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            if (pretty) newline(indent + 1)
            writeString(k.toString())
            sb.append(if (pretty) ": " else ":")
            write(v, indent + 1)
        }
        if (pretty) newline(indent)
        sb.append('}')
    }

    private fun writeList(list: Collection<*>, indent: Int) {
        if (list.isEmpty()) {
            sb.append("[]")
            return
        }
        if (pretty && list.all { it !is Map<*, *> && it !is Collection<*> && it !is Array<*> }) {
            // Short arrays of scalars stay on one line: render inline, keep it if the line stays short.
            val mark = sb.length
            val lineStart = sb.lastIndexOf("\n") + 1
            sb.append('[')
            list.forEachIndexed { i, v ->
                if (i > 0) sb.append(", ")
                write(v, indent + 1)
            }
            sb.append(']')
            if (sb.length - lineStart <= MAX_INLINE_LINE) return
            sb.setLength(mark)
        }
        sb.append('[')
        list.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            if (pretty) newline(indent + 1)
            write(v, indent + 1)
        }
        if (pretty) newline(indent)
        sb.append(']')
    }

    private fun newline(indent: Int) {
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun writeString(s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private companion object {
        /** Maximum line length (including indentation) for an array kept on one line. */
        const val MAX_INLINE_LINE = 100
    }
}

/**
 * Typed, path-aware view of a JSON object. Every failing accessor throws [SetupException]
 * with a message like `Moves.json: moves[3] (ember).power: expected a number or formula`.
 *
 * [path] is a human readable location used as the message prefix.
 *
 * A key whose value is JSON `null` is treated as missing by [has], [raw], [isNull], the `OrNull` accessors
 * and the accessors with a default. The required accessors report a missing key as
 * `"<path>: missing required field 'x'"` and an explicit null as `"<path>.x: expected ... but found null"`.
 */
class JsonObj(val map: Map<String, Any?>, val path: String) {
    companion object {
        /** Wraps [value] if it is a JSON object, otherwise throws a SetupException "<path>: expected an object". */
        fun of(value: Any?, path: String): JsonObj {
            if (value !is Map<*, *>) throw SetupException(prefixed(path, "expected an object but found ${Json.typeName(value)}"))
            @Suppress("UNCHECKED_CAST")
            return JsonObj(value as Map<String, Any?>, path)
        }
    }

    val keys: Set<String> get() = map.keys

    /** True if the key is present with a non-null value. */
    fun has(key: String): Boolean = map[key] != null
    /** The raw value (null if missing or JSON null). */
    fun raw(key: String): Any? = map[key]
    /** True if the key is missing or JSON null. */
    fun isNull(key: String): Boolean = map[key] == null

    fun string(key: String): String = asString(key, present(key))
    fun string(key: String, default: String): String = map[key]?.let { asString(key, it) } ?: default
    fun stringOrNull(key: String): String? = map[key]?.let { asString(key, it) }

    fun double(key: String): Double = asDouble(key, present(key))
    fun double(key: String, default: Double): Double = map[key]?.let { asDouble(key, it) } ?: default
    fun doubleOrNull(key: String): Double? = map[key]?.let { asDouble(key, it) }

    /** Integer accessors accept whole doubles only (`3` or `3.0`, not `3.5`). */
    fun int(key: String): Int = asInt(key, present(key))
    fun int(key: String, default: Int): Int = map[key]?.let { asInt(key, it) } ?: default
    fun intOrNull(key: String): Int? = map[key]?.let { asInt(key, it) }

    fun bool(key: String, default: Boolean): Boolean = when (val v = map[key]) {
        null -> default
        is Boolean -> v
        else -> wrongType(key, "true or false", v)
    }

    fun obj(key: String): JsonObj = JsonObj.of(present(key), pathOf(key))
    fun objOrNull(key: String): JsonObj? = map[key]?.let { JsonObj.of(it, pathOf(key)) }

    fun arr(key: String): JsonArr = JsonArr.of(present(key), pathOf(key))
    /** Missing/null -> empty array. */
    fun arrOrEmpty(key: String): JsonArr = JsonArr.of(map[key] ?: emptyList<Any?>(), pathOf(key))

    /** List of strings; missing/null -> empty list. */
    fun strings(key: String): List<String> = arrOrEmpty(key).strings()

    /** Child path helper: `"$path.$key"` (just `key` when [path] is empty). */
    fun pathOf(key: String): String = if (path.isEmpty()) key else "$path.$key"

    /** Throws a SetupException prefixed with this object's path. */
    fun fail(message: String): Nothing = throw SetupException(prefixed(path, message))

    /** Keys of this object not contained in [allowed] (used to warn about typos). */
    fun unknownKeys(allowed: Set<String>): List<String> = map.keys.filter { it !in allowed }

    override fun toString(): String = "JsonObj($path)"

    /** The value of a required key (possibly an explicit JSON null, which the type checks then report). */
    private fun present(key: String): Any? {
        if (!map.containsKey(key)) fail("missing required field '$key'")
        return map[key]
    }

    private fun asString(key: String, v: Any?): String = v as? String ?: wrongType(key, "a string", v)

    private fun asDouble(key: String, v: Any?): Double = when (v) {
        is Double -> v
        is Number -> v.toDouble()
        else -> wrongType(key, "a number", v)
    }

    private fun asInt(key: String, v: Any?): Int {
        val d = asDouble(key, v)
        if (d != Math.rint(d) || d.isInfinite()) {
            throw SetupException("${pathOf(key)}: expected a whole number but found ${Json.formatNumber(d)}")
        }
        if (d < Int.MIN_VALUE || d > Int.MAX_VALUE) {
            throw SetupException("${pathOf(key)}: the number ${Json.formatNumber(d)} is too large")
        }
        return d.toInt()
    }

    private fun wrongType(key: String, expected: String, v: Any?): Nothing =
        throw SetupException("${pathOf(key)}: expected $expected but found ${Json.typeName(v)}")
}

/** Typed, path-aware view of a JSON array. Element paths are `"$path[i]"`. */
class JsonArr(val list: List<Any?>, val path: String) {
    companion object {
        /** Wraps [value] if it is a JSON array, otherwise throws a SetupException "<path>: expected an array". */
        fun of(value: Any?, path: String): JsonArr {
            if (value !is List<*>) throw SetupException(prefixed(path, "expected an array but found ${Json.typeName(value)}"))
            return JsonArr(value, path)
        }
    }

    val size: Int get() = list.size

    /** Every element must be an object. */
    fun objects(): List<JsonObj> = list.mapIndexed { i, v -> JsonObj.of(v, "$path[$i]") }

    /** Every element must be a string. */
    fun strings(): List<String> = list.mapIndexed { i, v ->
        v as? String ?: throw SetupException("$path[$i]: expected a string but found ${Json.typeName(v)}")
    }

    fun fail(message: String): Nothing = throw SetupException(prefixed(path, message))

    override fun toString(): String = "JsonArr($path)"
}

private fun prefixed(path: String, message: String): String = if (path.isEmpty()) message else "$path: $message"
