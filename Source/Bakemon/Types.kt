package bakemon

import util.JsonObj
import util.SetupException
import java.io.File

/** A type declared in Types.json. [color] is an ANSI colour name for the terminal UI (may be null). */
data class TypeDef(val id: String, val name: String, val color: String?)

/**
 * Types.json: the type chart. Answers "how effective is an attack of type X against a monster of types Y, Z?".
 * Missing attacker/defender pairs are 1.0. Several defender types multiply together.
 */
class TypeChart(
    val types: Map<String, TypeDef>,
    /** attacker type id -> defender type id -> multiplier (only the pairs written in the JSON). */
    val chart: Map<String, Map<String, Double>>,
    /** Logged when the total multiplier is > 1. May contain {target}. */
    val strongMessage: String,
    /** Logged when 0 < multiplier < 1. */
    val weakMessage: String,
    /** Logged when the multiplier is 0. */
    val immuneMessage: String,
) {
    /** Multiplier of one attacking type against one defending type (1.0 if not in the chart). */
    fun single(attackType: String, defenseType: String): Double = chart[attackType]?.get(defenseType) ?: 1.0

    /** Product over the defender's types. A null/blank [attackType] (typeless move) gives 1.0. */
    fun multiplier(attackType: String?, defenderTypes: List<String>): Double {
        if (attackType.isNullOrBlank()) return 1.0
        return defenderTypes.fold(1.0) { acc, defense -> acc * single(attackType, defense) }
    }

    /** The message for a total multiplier, or null when it is exactly 1.0. */
    fun message(multiplier: Double): String? = when {
        multiplier <= 0.0 -> immuneMessage
        Math.abs(multiplier - 1.0) < 1e-9 -> null
        multiplier > 1.0 -> strongMessage
        else -> weakMessage
    }

    /** Display name of a type id (the id itself if unknown). */
    fun name(typeId: String): String = types[typeId]?.name ?: typeId

    /** Types in file order. */
    fun all(): List<TypeDef> = types.values.toList()

    companion object {
        private const val DEFAULT_STRONG = "It's super effective!"
        private const val DEFAULT_WEAK = "It's not very effective..."
        private const val DEFAULT_IMMUNE = "It doesn't affect {target}..."

        /** ANSI colour names the terminal UI understands (each also as bright<Name>, e.g. brightRed; grey = gray). */
        internal val COLORS = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white", "gray", "grey")

        /** True if [color] is one of [COLORS] or its bright variant (case-insensitive). */
        internal fun isKnownColor(color: String): Boolean {
            val c = color.lowercase()
            return c in COLORS || (c.startsWith("bright") && c.removePrefix("bright") in COLORS)
        }

        /** Parses Types.json. Errors/warnings go to [report]; throws util.SetupException if unusable. */
        fun load(file: File, report: LoadReport): TypeChart {
            if (!file.isFile) throw SetupException("Types.json: file not found")
            val raw = LoadSupport.readJson(file)
            val root: JsonObj?
            val list: List<*>
            val base: String
            val shortBase: String
            when (raw) {
                is List<*> -> {
                    root = null
                    list = raw
                    base = "Types.json: "
                    shortBase = ""
                }
                is Map<*, *> -> {
                    root = JsonObj.of(raw, "Types.json")
                    LoadSupport.warnUnknownKeys(root, setOf("types", "chart", "messages"), report)
                    list = when (val t = root.raw("types")) {
                        is List<*> -> t
                        null -> throw SetupException("Types.json: missing \"types\" (an array of {\"id\", \"name\", \"color\"})")
                        else -> throw SetupException("${root.pathOf("types")}: expected an array")
                    }
                    base = "Types.json: types"
                    shortBase = "types"
                }
                else -> throw SetupException("Types.json: expected an object with \"types\", \"chart\" and \"messages\"")
            }
            val types = LoadSupport.parseEntries(list, base, shortBase, "type", report) { parseType(it, report) }
            val chart = if (root == null) emptyMap() else parseChart(root, types.keys, report)

            var strong = DEFAULT_STRONG
            var weak = DEFAULT_WEAK
            var immune = DEFAULT_IMMUNE
            try {
                val messages = root?.objOrNull("messages")
                if (messages != null) {
                    LoadSupport.warnUnknownKeys(messages, setOf("strong", "weak", "immune"), report)
                    strong = messages.string("strong", DEFAULT_STRONG)
                    weak = messages.string("weak", DEFAULT_WEAK)
                    immune = messages.string("immune", DEFAULT_IMMUNE)
                    for ((key, text) in listOf("strong" to strong, "weak" to weak, "immune" to immune)) {
                        LoadSupport.checkPlaceholders(text, messages.pathOf(key), report, LoadSupport.TYPE_MESSAGE_PLACEHOLDERS)
                    }
                }
            } catch (e: SetupException) {
                report.error(e.message ?: "Types.json.messages: invalid value")
            }
            return TypeChart(types, chart, strong, weak, immune)
        }

        private fun parseType(obj: JsonObj, report: LoadReport): TypeDef {
            LoadSupport.warnUnknownKeys(obj, setOf("id", "name", "color"), report)
            val id = obj.string("id")
            val color = obj.stringOrNull("color")?.takeIf { it.isNotBlank() }
            if (color != null && !isKnownColor(color)) {
                report.warn("${obj.pathOf("color")}: unknown colour '$color' (use ${COLORS.joinToString(" ")}, or bright variants like brightRed)")
            }
            return TypeDef(id, obj.string("name", id), color)
        }

        private fun parseChart(root: JsonObj, typeIds: Set<String>, report: LoadReport): Map<String, Map<String, Double>> {
            val chart = try {
                root.objOrNull("chart")
            } catch (e: SetupException) {
                report.error(e.message ?: "Types.json.chart: expected an object")
                null
            } ?: return emptyMap()
            val result = LinkedHashMap<String, Map<String, Double>>()
            for (attacker in chart.keys) {
                if (attacker !in typeIds) {
                    if ("type:$attacker" !in report.failed) {
                        report.error("${chart.pathOf(attacker)}: unknown attacking type '$attacker'${LoadSupport.suggest(attacker, typeIds)}")
                    }
                    continue
                }
                val row = try {
                    chart.obj(attacker)
                } catch (e: SetupException) {
                    report.error(e.message ?: "${chart.pathOf(attacker)}: expected an object")
                    continue
                }
                val values = LinkedHashMap<String, Double>()
                for (defender in row.keys) {
                    if (defender !in typeIds) {
                        if ("type:$defender" !in report.failed) {
                            report.error("${row.pathOf(defender)}: unknown defending type '$defender'${LoadSupport.suggest(defender, typeIds)}")
                        }
                        continue
                    }
                    val value = try {
                        row.double(defender)
                    } catch (e: SetupException) {
                        report.error(e.message ?: "${row.pathOf(defender)}: expected a number")
                        continue
                    }
                    if (value < 0 || value.isNaN()) {
                        report.error("${row.pathOf(defender)}: the multiplier must be 0 or more (got $value)")
                        continue
                    }
                    values[defender] = value
                }
                result[attacker] = values
            }
            return result
        }
    }
}
