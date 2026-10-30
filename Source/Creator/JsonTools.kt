package creator

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Small helpers for the raw JSON trees the creator edits (the values produced by util.Json: LinkedHashMap,
 * List, String, Double, Boolean, null).
 */
object JsonTools {
    /** A deep, mutable copy: objects become LinkedHashMap (same key order), arrays ArrayList. */
    fun copy(value: Any?): Any? = when (value) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> value.forEach { (k, v) -> m[k.toString()] = copy(v) } }
        is List<*> -> ArrayList<Any?>(value.size).also { l -> value.forEach { l += copy(it) } }
        else -> value
    }

    /** A deep, mutable copy of an object. */
    fun copyMap(value: Map<String, Any?>): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return copy(value) as MutableMap<String, Any?>
    }

    /**
     * JSON equality: numbers compare by value (3 == 3.0), objects compare their keys in order when [ordered]
     * (so a reordered object counts as a change and gets written), lists element by element.
     */
    fun equal(a: Any?, b: Any?, ordered: Boolean = true): Boolean {
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a is Map<*, *> && b is Map<*, *>) {
            if (a.size != b.size) return false
            if (ordered) {
                val ia = a.entries.iterator()
                val ib = b.entries.iterator()
                while (ia.hasNext()) {
                    val ea = ia.next()
                    val eb = ib.next()
                    if (ea.key != eb.key || !equal(ea.value, eb.value, true)) return false
                }
                return true
            }
            for ((k, v) in a) {
                if (!b.containsKey(k) || !equal(v, b[k], false)) return false
            }
            return true
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            for (i in a.indices) if (!equal(a[i], b[i], ordered)) return false
            return true
        }
        return a == b
    }

    /**
     * A readable number for generated formulas and JSON: at most 6 decimals, no trailing zeros, no exponent
     * (`0.25`, `1.5`, `3`, `0.333333`).
     */
    fun num(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val text = BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    /** [value] rounded the way [num] writes it (so 0.33 * 100 reads back as 33). */
    fun round(value: Double): Double = num(value).toDouble()

    /** A percentage as a multiplier fraction (25 -> "0.25"). */
    fun fraction(percent: Double): String = num(percent / 100.0)

    /** Puts [value] under [key]; a new key is inserted where [order] says (relative to the keys already there). */
    fun putOrdered(map: MutableMap<String, Any?>, key: String, value: Any?, order: List<String>) {
        if (map.containsKey(key)) {
            map[key] = value
            return
        }
        val rank = order.indexOf(key)
        if (rank < 0) {
            map[key] = value
            return
        }
        // Insert before the first existing key that comes later in the canonical order.
        val keys = map.keys.toList()
        val before = keys.indexOfFirst { val r = order.indexOf(it); r > rank }
        if (before < 0) {
            map[key] = value
            return
        }
        val entries = map.entries.map { it.key to it.value }
        map.clear()
        entries.forEachIndexed { i, (k, v) ->
            if (i == before) map[key] = value
            map[k] = v
        }
    }

    /** Renames a key in place, keeping its position. Does nothing if [old] is missing or [new] already exists. */
    fun renameKey(map: MutableMap<String, Any?>, old: String, new: String) {
        if (!map.containsKey(old) || map.containsKey(new) || old == new) return
        val entries = map.entries.map { it.key to it.value }
        map.clear()
        for ((k, v) in entries) map[if (k == old) new else k] = v
    }

    /** The object under [key] of [map], created (and inserted per [order]) if missing. */
    fun objectAt(map: MutableMap<String, Any?>, key: String, order: List<String> = emptyList()): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val existing = map[key] as? MutableMap<String, Any?>
        if (existing != null) return existing
        val created = LinkedHashMap<String, Any?>()
        putOrdered(map, key, created, order)
        return created
    }

    /** The array under [key] of [map], created (and inserted per [order]) if missing. */
    fun listAt(map: MutableMap<String, Any?>, key: String, order: List<String> = emptyList()): MutableList<Any?> {
        @Suppress("UNCHECKED_CAST")
        val existing = map[key] as? MutableList<Any?>
        if (existing != null) return existing
        val created = ArrayList<Any?>()
        putOrdered(map, key, created, order)
        return created
    }

    /** Quotes [text] as a formula string literal ('fire'). */
    fun literal(text: String): String = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

/** Reads a map value as a String (null for anything else). */
internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String

/** Reads a map value as a Double (null for anything else). */
internal fun Map<String, Any?>.dbl(key: String): Double? = (this[key] as? Number)?.toDouble()

/** Reads a map value as a list of strings (non-strings dropped; null when the value is not a list). */
internal fun Map<String, Any?>.strings(key: String): List<String>? = (this[key] as? List<*>)?.filterIsInstance<String>()

/** Casts a JSON value to a map, or null. */
@Suppress("UNCHECKED_CAST")
internal fun asMap(value: Any?): MutableMap<String, Any?>? = value as? MutableMap<String, Any?>

/** Casts a JSON value to a list, or null. */
@Suppress("UNCHECKED_CAST")
internal fun asList(value: Any?): MutableList<Any?>? = value as? MutableList<Any?>
