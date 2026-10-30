package creator

import util.Json

/**
 * Writes an edited JSON value back into the text it was read from while keeping every unchanged part of that text
 * exactly as it was (formatting, blank lines, comments, inline objects): only the values that changed are written
 * again. Changed values that were written on one line stay on one line; new multi-line values use the util.Json
 * pretty format (2-space indent) at the indentation of the place they go.
 */
internal object JsonSplice {

    /**
     * The text of [newValue], reusing [originalText] (whose parsed value is [originalValue]) wherever the value did not
     * change. With no original text, this is util.Json's pretty format plus a trailing newline.
     */
    fun render(originalText: String?, originalValue: Any?, newValue: Any?): String {
        if (originalText == null) return Json.stringify(newValue, pretty = true) + "\n"
        if (JsonTools.equal(originalValue, newValue)) return originalText
        val root = Scanner(originalText).document() ?: return Json.stringify(newValue, pretty = true) + "\n"
        val body = Renderer(originalText).node(newValue, originalValue, root)
        val text = originalText.substring(0, root.start) + body + originalText.substring(root.end)
        return if (originalText.contains("\r\n")) text.replace(LONE_LF, "\r\n") else text
    }

    private val LONE_LF = Regex("(?<!\r)\n")

    /** Longest line (with its indentation) for a new member written on one line. */
    private const val MAX_LINE = 140

    /** A value's span in the text; containers also list their members. */
    class Node(val start: Int, val end: Int, val container: Char?, val items: List<Item>)

    /** One member of a container: [start] is where the key (objects) or the value (arrays) starts. */
    class Item(val key: String?, val start: Int, val value: Node)

    /** Finds value spans in text that util.Json already accepted (so it can be lenient). */
    private class Scanner(private val text: String) {
        private var pos = 0

        fun document(): Node? = try {
            if (text.isNotEmpty() && text[0].code == 0xFEFF) pos = 1
            blank()
            value()
        } catch (e: RuntimeException) {
            null
        }

        private fun value(): Node {
            blank()
            val start = pos
            return when (text[pos]) {
                '{' -> container('{', '}', start)
                '[' -> container('[', ']', start)
                '"' -> {
                    skipString()
                    Node(start, pos, null, emptyList())
                }
                else -> {
                    while (pos < text.length && text[pos] !in ",]} \t\r\n/") pos++
                    Node(start, pos, null, emptyList())
                }
            }
        }

        private fun container(open: Char, close: Char, start: Int): Node {
            pos++ // open
            val items = mutableListOf<Item>()
            while (true) {
                blank()
                if (text[pos] == close) break
                val itemStart = pos
                var key: String? = null
                if (open == '{') {
                    val keyStart = pos
                    skipString()
                    key = Json.parse(text.substring(keyStart, pos)) as String
                    blank()
                    pos++ // ':'
                }
                items += Item(key, itemStart, value())
                blank()
                if (text[pos] == ',') pos++
            }
            pos++ // close
            return Node(start, pos, open, items)
        }

        private fun skipString() {
            pos++ // opening quote
            while (text[pos] != '"') pos += if (text[pos] == '\\') 2 else 1
            pos++
        }

        private fun blank() {
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> pos++
                    c == '/' && text.startsWith("//", pos) -> while (pos < text.length && text[pos] != '\n') pos++
                    c == '/' && text.startsWith("/*", pos) -> pos = text.indexOf("*/", pos + 2) + 2
                    else -> return
                }
            }
        }
    }

    private class Renderer(private val text: String) {

        fun node(new: Any?, old: Any?, span: Node): String {
            if (JsonTools.equal(new, old)) return text.substring(span.start, span.end)
            val inline = !text.substring(span.start, span.end).contains('\n')
            return when {
                new is Map<*, *> && old is Map<*, *> && span.container == '{' -> container(new, old, span, inline)
                new is List<*> && old is List<*> && span.container == '[' -> container(new, old, span, inline)
                inline && span.container != null -> inlineValue(new)
                else -> fresh(new, lineIndent(span.start))
            }
        }

        /** Renders an object or array, keeping the text of unchanged members and of the gaps between kept neighbours. */
        private fun container(new: Any, old: Any, span: Node, inline: Boolean): String {
            val newItems: List<Pair<String?, Any?>> = when (new) {
                is Map<*, *> -> new.entries.map { it.key.toString() to it.value }
                else -> (new as List<*>).map { null to it }
            }
            val oldValues: List<Any?> = when (old) {
                is Map<*, *> -> span.items.map { old[it.key] }
                else -> span.items.indices.map { (old as List<*>).getOrNull(it) }
            }
            val (open, close) = if (span.container == '{') "{" to "}" else "[" to "]"
            if (newItems.isEmpty()) return open + close
            val matches = match(newItems, oldValues, span, isObject = span.container == '{')
            val itemIndent = itemIndent(span)
            // Members written on one line each (like "effects" or a type chart): new members follow that style when short.
            val oneLiners = span.items.isNotEmpty() && span.items.all { !text.substring(it.start, it.value.end).contains('\n') }
            val parts = newItems.mapIndexed { k, (key, value) ->
                val i = matches[k]
                if (i != null) {
                    val item = span.items[i]
                    val head = text.substring(item.start, item.value.start) // key, colon and spaces (objects); "" (arrays)
                    head + node(value, oldValues[i], item.value)
                } else {
                    val head = if (key != null) Json.stringify(key) + ": " else ""
                    val one = head + inlineValue(value)
                    when {
                        inline -> one
                        oneLiners && itemIndent.length + one.length <= MAX_LINE -> one
                        else -> head + fresh(value, itemIndent)
                    }
                }
            }
            val sb = StringBuilder(open)
            if (span.items.isNotEmpty()) {
                sb.append(text, span.start + 1, span.items.first().start)
            } else {
                sb.append(if (inline) (if (open == "{") " " else "") else "\n$itemIndent")
            }
            for (k in parts.indices) {
                if (k > 0) {
                    val prev = matches[k - 1]
                    val cur = matches[k]
                    if (prev != null && cur != null && cur == prev + 1) {
                        sb.append(text, span.items[prev].value.end, span.items[cur].start)
                    } else {
                        sb.append(if (inline) ", " else ",\n$itemIndent")
                    }
                }
                sb.append(parts[k])
            }
            if (span.items.isNotEmpty()) {
                var tail = text.substring(span.items.last().value.end, span.end - 1)
                // The last member moved: drop a trailing comma that followed the old last member.
                if (matches.last() != span.items.lastIndex) tail = tail.replaceFirst(Regex("^\\s*,"), "")
                sb.append(tail)
            } else {
                sb.append(if (inline) (if (open == "{") " " else "") else "\n" + lineIndent(span.start))
            }
            return sb.append(close).toString()
        }

        /**
         * For each new member, the index of the old member whose text it reuses (or null): objects by key; arrays by
         * an equal value first, then by the same "id", then by position when both are containers.
         */
        private fun match(newItems: List<Pair<String?, Any?>>, oldValues: List<Any?>, span: Node, isObject: Boolean): List<Int?> {
            if (isObject) {
                val byKey = span.items.withIndex().associate { it.value.key to it.index }
                return newItems.map { byKey[it.first] }
            }
            val used = BooleanArray(oldValues.size)
            val result = arrayOfNulls<Int>(newItems.size)
            fun claim(k: Int, i: Int) {
                result[k] = i
                used[i] = true
            }
            for ((k, item) in newItems.withIndex()) {
                val same = if (k < oldValues.size && !used[k] && JsonTools.equal(item.second, oldValues[k])) k
                else oldValues.indices.firstOrNull { !used[it] && JsonTools.equal(item.second, oldValues[it]) }
                if (same != null) claim(k, same)
            }
            for ((k, item) in newItems.withIndex()) {
                if (result[k] != null) continue
                val id = (item.second as? Map<*, *>)?.get("id") as? String ?: continue
                val i = oldValues.indices.firstOrNull { !used[it] && (oldValues[it] as? Map<*, *>)?.get("id") == id }
                if (i != null) claim(k, i)
            }
            for ((k, item) in newItems.withIndex()) {
                if (result[k] != null || k >= oldValues.size || used[k]) continue
                val both = (item.second is Map<*, *> && oldValues[k] is Map<*, *>) || (item.second is List<*> && oldValues[k] is List<*>)
                if (both && (item.second as? Map<*, *>)?.get("id") == null) claim(k, k)
            }
            return result.toList()
        }

        /** Indentation of the members of a multi-line container. */
        private fun itemIndent(span: Node): String {
            val first = span.items.firstOrNull() ?: return lineIndent(span.start) + "  "
            val lineStart = text.lastIndexOf('\n', first.start - 1) + 1
            val before = text.substring(lineStart, first.start)
            return if (before.isBlank()) before else lineIndent(span.start) + "  "
        }

        /** The leading whitespace of the line that contains [pos]. */
        private fun lineIndent(pos: Int): String {
            val lineStart = text.lastIndexOf('\n', pos - 1) + 1
            var end = lineStart
            while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
            return text.substring(lineStart, end)
        }

        /** util.Json pretty format, continuation lines indented by [indent]. */
        private fun fresh(value: Any?, indent: String): String =
            Json.stringify(value, pretty = true).replace("\n", "\n$indent")

        /** One-line format like the hand-written setups: `{ "a": 1, "b": [1, 2] }`. */
        fun inlineValue(value: Any?): String = when (value) {
            is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(", ", "{ ", " }") {
                Json.stringify(it.key.toString()) + ": " + inlineValue(it.value)
            }
            is List<*> -> value.joinToString(", ", "[", "]") { inlineValue(it) }
            else -> Json.stringify(value, pretty = false)
        }
    }
}
