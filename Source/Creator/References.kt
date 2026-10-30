package creator

import bakemon.FormulaNames

/** What an id names: an entry kind, or a stat (Rules.json "stats"). */
internal enum class RefKind {
    TYPE, STATUS, MOVE, ABILITY, ITEM, FIELD, BAKEMON, STAT;

    companion object {
        fun of(kind: EntryKind): RefKind = valueOf(kind.name)
    }
}

/**
 * One place where an id is used.
 * [file] is the file ("Moves.json", "Teams/x.json"), [where] says where in plain words
 * (e.g. "move 'ember' > triggers[0].effects[0].status"). [inFormula] references are string literals or names inside a
 * formula: rename updates them, but delete cannot remove them ([removable] is false).
 */
data class Reference(val file: String, val where: String, val inFormula: Boolean, val removable: Boolean) {
    override fun toString(): String = "$file: $where"
}

/** Finds, renames and removes the references to an id in every file of a draft (README formats). */
internal object References {
    /** Marks a list element or map value for removal; swept away after all removals ran. */
    private object Removed

    private class Site(val ref: Reference, val rename: (String) -> Unit, val remove: (() -> Unit)?)

    /** The files that references can be in (loaded, readable files). */
    fun files(draft: SetupDraft): List<String> = draft.fileNames().filter { draft.liveValue(it) != null }

    fun find(draft: SetupDraft, kind: RefKind, id: String): List<Reference> = sites(draft, kind, id).map { it.ref }

    fun rename(draft: SetupDraft, kind: RefKind, oldId: String, newId: String): List<Reference> {
        // Formulas first: a key rename (statModifiers.atk) must not run before the formula stored under that key.
        val sites = sites(draft, kind, oldId).sortedBy { !it.ref.inFormula }
        sites.forEach { it.rename(newId) }
        return sites.map { it.ref }
    }

    /** Removes every removable reference; returns the ones left (in formulas, or that cannot be removed). */
    fun remove(draft: SetupDraft, kind: RefKind, id: String): List<Reference> {
        val sites = sites(draft, kind, id)
        val touched = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        for (site in sites) site.remove?.invoke()
        // Triggers left without effects and entries left without triggers are removed too.
        for (name in files(draft)) {
            val value = draft.liveValue(name)
            collectTouched(value, touched)
            sweep(value)
        }
        for (name in files(draft)) cleanTriggers(draft.liveValue(name), touched)
        return sites.filter { it.remove == null }.map { it.ref }
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Triggers with a removed effect (recorded before the sweep). */
    private fun collectTouched(value: Any?, touched: MutableSet<Any>) {
        when (value) {
            is Map<*, *> -> {
                val effects = value["effects"] as? List<*>
                if (effects != null && effects.any { it === Removed }) touched += value
                value.values.forEach { collectTouched(it, touched) }
            }
            is List<*> -> value.forEach { collectTouched(it, touched) }
        }
    }

    private fun sweep(value: Any?) {
        when (value) {
            is MutableMap<*, *> -> {
                value.entries.removeIf { it.value === Removed }
                value.values.forEach { sweep(it) }
            }
            is MutableList<*> -> {
                value.removeIf { it === Removed }
                value.forEach { sweep(it) }
            }
        }
    }

    private fun cleanTriggers(value: Any?, touched: Set<Any>) {
        when (value) {
            is MutableMap<*, *> -> {
                val triggers = asList(value["triggers"])
                if (triggers != null) {
                    val before = triggers.size
                    triggers.removeIf { t -> t in touched && (t as? Map<*, *>)?.get("effects").let { it is List<*> && it.isEmpty() } }
                    if (before > 0 && triggers.isEmpty()) asMap(value)!!.remove("triggers")
                }
                value.values.forEach { cleanTriggers(it, touched) }
            }
            is List<*> -> value.forEach { cleanTriggers(it, touched) }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Collecting sites

    private fun sites(draft: SetupDraft, kind: RefKind, id: String): List<Site> {
        val out = mutableListOf<Site>()
        for (name in files(draft)) {
            val value = draft.liveValue(name)
            val entryKind = EntryKind.entries.firstOrNull { it.fileName == name }
            when {
                name == SetupDraft.RULES -> asMap(value)?.let { rulesSites(it, kind, id, out) }
                name.startsWith("Teams/") -> asMap(value)?.let { teamSites(name, it, kind, id, out) }
                entryKind != null -> entrySites(name, entryKind, value, kind, id, out)
            }
            if (name == SetupDraft.TYPES && kind == RefKind.TYPE) asMap(value)?.let { chartSites(it, id, out) }
        }
        return out
    }

    private fun ref(file: String, where: String, inFormula: Boolean = false, removable: Boolean = true) =
        Reference(file, where, inFormula, removable && !inFormula)

    private fun valueSite(file: String, where: String, map: MutableMap<String, Any?>, key: String, removable: Boolean = true) =
        Site(ref(file, where, removable = removable), { map[key] = it }, if (removable) ({ map[key] = Removed }) else null)

    private fun elementSite(file: String, where: String, list: MutableList<Any?>, index: Int, removable: Boolean = true) =
        Site(ref(file, where, removable = removable), { list[index] = it }, if (removable) ({ list[index] = Removed }) else null)

    private fun keySite(file: String, where: String, map: MutableMap<String, Any?>, key: String, removable: Boolean) =
        Site(ref(file, where, removable = removable), { JsonTools.renameKey(map, key, it) }, if (removable) ({ map[key] = Removed }) else null)

    private fun stringElements(file: String, where: String, map: MutableMap<String, Any?>, key: String, id: String, out: MutableList<Site>) {
        val list = asList(map[key]) ?: return
        for (i in list.indices) if (list[i] == id) out += elementSite(file, "$where > $key", list, i)
    }

    private fun rulesSites(rules: MutableMap<String, Any?>, kind: RefKind, id: String, out: MutableList<Site>) {
        val file = SetupDraft.RULES
        when (kind) {
            RefKind.MOVE -> if (rules["fallbackMove"] == id) out += valueSite(file, "fallbackMove", rules, "fallbackMove")
            RefKind.FIELD -> asList(rules["startFields"])?.forEachIndexed { i, start ->
                val m = asMap(start) ?: return@forEachIndexed
                if (m["field"] == id) {
                    out += Site(ref(file, "startFields[$i].field"), { m["field"] = it }, { asList(rules["startFields"])!![i] = Removed })
                }
            }
            RefKind.STAT -> {
                asList(rules["stats"])?.forEachIndexed { i, stat ->
                    val m = asMap(stat)
                    if (m != null && m["id"] == id) out += valueSite(file, "stats[$i].id", m, "id", removable = false)
                    if (stat == id) out += elementSite(file, "stats[$i]", asList(rules["stats"])!!, i, removable = false)
                }
                for (key in listOf("hpStat", "speedStat")) if (rules[key] == id) out += valueSite(file, key, rules, key, removable = false)
                asMap(rules["statFormulas"])?.let { if (it.containsKey(id)) out += keySite(file, "statFormulas.$id", it, id, removable = false) }
                asMap(rules["categories"])?.forEach { (name, category) ->
                    val c = asMap(category) ?: return@forEach
                    for (key in listOf("attack", "defense")) {
                        if (c[key] == id) out += valueSite(file, "categories.$name.$key", c, key, removable = false)
                    }
                }
            }
            else -> {}
        }
        formulaSites(file, "Rules.json", rules, kind, id, out)
    }

    private fun chartSites(types: MutableMap<String, Any?>, id: String, out: MutableList<Site>) {
        val chart = asMap(types["chart"]) ?: return
        val file = SetupDraft.TYPES
        if (chart.containsKey(id)) out += keySite(file, "chart: attacks of '$id'", chart, id, removable = true)
        for ((attacker, row) in chart) {
            val r = asMap(row) ?: continue
            if (r.containsKey(id)) out += keySite(file, "chart: '$attacker' against '$id'", r, id, removable = true)
        }
    }

    private fun teamSites(file: String, team: MutableMap<String, Any?>, kind: RefKind, id: String, out: MutableList<Site>) {
        val members = asList(team["members"]) ?: return
        members.forEachIndexed { i, member ->
            val m = asMap(member) ?: return@forEachIndexed
            val where = "member ${i + 1} (${m["species"]})"
            when (kind) {
                RefKind.BAKEMON -> if (m["species"] == id) {
                    out += Site(ref(file, "$where > species"), { m["species"] = it }, { members[i] = Removed })
                }
                RefKind.ABILITY -> if (m["ability"] == id) out += valueSite(file, "$where > ability", m, "ability")
                RefKind.ITEM -> if (m["item"] == id) out += valueSite(file, "$where > item", m, "item")
                RefKind.MOVE -> stringElements(file, where, m, "moves", id, out)
                RefKind.STAT -> asMap(m["points"])?.let { if (it.containsKey(id)) out += keySite(file, "$where > points", it, id, removable = false) }
                else -> {}
            }
        }
    }

    private fun entrySites(file: String, entryKind: EntryKind, value: Any?, kind: RefKind, id: String, out: MutableList<Site>) {
        val list = when (value) {
            is List<*> -> asList(value)
            is Map<*, *> -> asList(value[entryKind.listKey])
            else -> null
        } ?: return
        for (element in list) {
            val e = asMap(element) ?: continue
            val where = "${entryKind.label} '${e["id"]}'"
            when (entryKind) {
                EntryKind.BAKEMON -> when (kind) {
                    RefKind.TYPE -> stringElements(file, where, e, "types", id, out)
                    RefKind.ABILITY -> stringElements(file, where, e, "abilities", id, out)
                    RefKind.MOVE -> stringElements(file, where, e, "moves", id, out)
                    RefKind.STAT -> asMap(e["baseStats"])?.let { if (it.containsKey(id)) out += keySite(file, "$where > baseStats", it, id, removable = false) }
                    else -> {}
                }
                EntryKind.MOVE -> if (kind == RefKind.TYPE && e["type"] == id) out += valueSite(file, "$where > type", e, "type")
                EntryKind.STATUS -> if (kind == RefKind.TYPE) stringElements(file, where, e, "immuneTypes", id, out)
                else -> {}
            }
            if (kind == RefKind.STAT) {
                asMap(e["statModifiers"])?.let { if (it.containsKey(id)) out += keySite(file, "$where > statModifiers", it, id, removable = false) }
            }
            asList(e["triggers"])?.forEachIndexed { t, trigger -> effectSites(file, "$where > triggers[$t]", trigger, kind, id, out) }
            if (entryKind != EntryKind.TYPE && entryKind != EntryKind.BAKEMON) formulaSites(file, where, e, kind, id, out)
        }
    }

    private fun effectSites(file: String, where: String, trigger: Any?, kind: RefKind, id: String, out: MutableList<Site>) {
        val effects = asList(asMap(trigger)?.get("effects")) ?: return
        effects.forEachIndexed { j, effect ->
            val e = asMap(effect) ?: return@forEachIndexed
            val type = e["type"] as? String ?: return@forEachIndexed
            val key = when {
                kind == RefKind.STATUS && (type.equals("applyStatus", true) || type.equals("removeStatus", true)) -> "status"
                kind == RefKind.FIELD && (type.equals("setField", true) || type.equals("removeField", true)) -> "field"
                kind == RefKind.STAT && type.equals("statStage", true) -> "stat"
                else -> return@forEachIndexed
            }
            if (e[key] == id) {
                out += Site(ref(file, "$where.effects[$j] ($type)"), { e[key] = it }, { effects[j] = Removed })
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Formulas

    /** JSON keys whose string values are formulas (README "Formulas"); plus every value of statFormulas / statModifiers. */
    private val FORMULA_KEYS = setOf(
        "power", "hits", "critBonus", "condition", "chance", "amount", "duration", "stacks", "stages", "value",
        "damage", "hitChance", "critChance", "critMultiplier", "multiplier", "fill", "start", "actionBarFill",
        "disableItems", "disableAbilities", "blockMove", "blockStatus", "blockStatStage",
    )
    private val FORMULA_PARENTS = setOf("statFormulas", "statModifiers")

    private fun formulaSites(file: String, where: String, root: MutableMap<String, Any?>, kind: RefKind, id: String, out: MutableList<Site>) {
        walkFormulas(root, null, "") { map, key, text, path ->
            val updated = FormulaText.renameId(text, kind, id, "\u0000") ?: return@walkFormulas
            if (updated == text) return@walkFormulas
            out += Site(
                ref(file, "$where > $path: $text", inFormula = true),
                { newId -> FormulaText.renameId(map[key] as? String ?: text, kind, id, newId)?.let { map[key] = it } },
                null,
            )
        }
    }

    /** Visits every formula string under [value]: (its map, key, text, path). */
    fun walkFormulas(value: Any?, parentKey: String?, path: String, visit: (MutableMap<String, Any?>, String, String, String) -> Unit) {
        when (value) {
            is Map<*, *> -> {
                val map = asMap(value) ?: return
                for ((k, v) in map.entries.toList()) {
                    val p = if (path.isEmpty()) k else "$path.$k"
                    if (v is String && (k in FORMULA_KEYS || parentKey in FORMULA_PARENTS)) visit(map, k, v, p)
                    else walkFormulas(v, k, p, visit)
                }
            }
            is List<*> -> value.forEachIndexed { i, v -> walkFormulas(v, parentKey, "$path[$i]", visit) }
        }
    }
}

/** A lexer for formula text that keeps positions, so ids inside formulas can be found and replaced in place. */
internal object FormulaText {
    class Token(val kind: Char, val text: String, val start: Int, val end: Int, val value: String = text)

    private val MONSTER_REFS = FormulaNames.MONSTER_REFS + FormulaNames.EFFECT_ONLY_REFS
    private val STAT_GROUPS = FormulaNames.STAT_GROUPS

    /** The tokens of [src] (N name, S string, D number, P punctuation), or null if it cannot be read. */
    fun tokens(src: String): List<Token>? {
        val out = mutableListOf<Token>()
        var p = 0
        while (p < src.length) {
            val c = src[p]
            val start = p
            when {
                c.isWhitespace() -> p++
                c.isDigit() || (c == '.' && p + 1 < src.length && src[p + 1].isDigit()) -> {
                    while (p < src.length && (src[p].isLetterOrDigit() || src[p] == '.')) p++
                    out += Token('D', src.substring(start, p), start, p)
                }
                c.isLetter() || c == '_' -> {
                    p++
                    while (p < src.length && (src[p].isLetterOrDigit() || src[p] == '_' ||
                            (src[p] == '.' && p + 1 < src.length && (src[p + 1].isLetter() || src[p + 1] == '_')))) p++
                    out += Token('N', src.substring(start, p), start, p)
                }
                c == '\'' || c == '"' -> {
                    val sb = StringBuilder()
                    p++
                    while (true) {
                        if (p >= src.length) return null
                        val ch = src[p]
                        if (ch == c) break
                        if (ch == '\\' && p + 1 < src.length) {
                            sb.append(when (val e = src[p + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> e })
                            p += 2
                        } else {
                            sb.append(ch)
                            p++
                        }
                    }
                    p++
                    out += Token('S', src.substring(start, p), start, p, sb.toString())
                }
                else -> {
                    val two = if (p + 1 < src.length) src.substring(p, p + 2) else ""
                    if (two in setOf("<=", ">=", "==", "!=", "&&", "||")) p += 2 else p++
                    out += Token('P', src.substring(start, p), start, p)
                }
            }
        }
        return out
    }

    /** Functions whose string argument at an index names an id of a kind. */
    private val FUNCTION_ARGS: Map<Pair<String, Int>, RefKind> = mapOf(
        ("hasStatus" to 1) to RefKind.STATUS, ("statusStacks" to 1) to RefKind.STATUS,
        ("hasAbility" to 1) to RefKind.ABILITY, ("hasItem" to 1) to RefKind.ITEM,
        ("hasType" to 1) to RefKind.TYPE, ("typeMultiplier" to 0) to RefKind.TYPE,
        ("fieldActive" to 0) to RefKind.FIELD, ("fieldStacks" to 0) to RefKind.FIELD, ("fieldTurns" to 0) to RefKind.FIELD,
        ("sideField" to 1) to RefKind.FIELD, ("sideFieldStacks" to 1) to RefKind.FIELD,
    )

    /** What a string compared with this name (`move.type == 'fire'`) names. */
    private fun comparedKind(name: String): RefKind? = when {
        name == "move.type" -> RefKind.TYPE
        name == "move.id" -> RefKind.MOVE
        name == "status" -> RefKind.STATUS
        name == "field" -> RefKind.FIELD
        name == "stat" -> RefKind.STAT
        name.endsWith(".id") && name.substringBefore('.') in MONSTER_REFS && name.count { it == '.' } == 1 -> RefKind.BAKEMON
        else -> null
    }

    /** The kind of id string literal [t] names, or null (a flag, a message...). */
    fun literalKind(tokens: List<Token>, t: Int): RefKind? {
        fun punct(i: Int, vararg texts: String) = i in tokens.indices && tokens[i].kind == 'P' && tokens[i].text in texts
        // Compared with a name: move.type == 'fire', 'fire' == move.type.
        if (punct(t - 1, "==", "!=") && t - 2 >= 0 && tokens[t - 2].kind == 'N') comparedKind(tokens[t - 2].text)?.let { return it }
        if (punct(t + 1, "==", "!=") && t + 2 < tokens.size && tokens[t + 2].kind == 'N') comparedKind(tokens[t + 2].text)?.let { return it }
        // A whole argument of a call: hasStatus(target, 'burn').
        if (!punct(t - 1, "(", ",") || !punct(t + 1, ")", ",")) return null
        var depth = 0
        var index = 0
        var i = t - 1
        while (i >= 0) {
            val tok = tokens[i]
            if (tok.kind == 'P') {
                when (tok.text) {
                    ")" -> depth++
                    "(" -> if (depth == 0) {
                        val fn = tokens.getOrNull(i - 1)?.takeIf { it.kind == 'N' }?.text ?: return null
                        return FUNCTION_ARGS[fn to index]
                    } else depth--
                    "," -> if (depth == 0) index++
                }
            }
            i--
        }
        return null
    }

    /** True if name token [text] reads stat [stat] (user.atk, self.raw.atk, target.base.atk, other.stage.atk). */
    private fun readsStat(text: String, stat: String): Boolean {
        val parts = text.split('.')
        if (parts[0] !in MONSTER_REFS || parts.last() != stat) return false
        return (parts.size == 2 && stat !in FormulaNames.MONSTER_FIELDS) || (parts.size == 3 && parts[1] in STAT_GROUPS)
    }

    /**
     * [src] with every reference to [id] (of [kind]) replaced by [newId], or null if it cannot be tokenized.
     * Returns [src] itself when nothing refers to [id].
     */
    fun renameId(src: String, kind: RefKind, id: String, newId: String): String? {
        val tokens = tokens(src) ?: return null
        val edits = mutableListOf<Pair<Token, String>>()
        for ((t, tok) in tokens.withIndex()) {
            if (tok.kind == 'S' && tok.value == id && literalKind(tokens, t) == kind) {
                val quote = tok.text[0]
                edits += tok to quote + newId.replace("\\", "\\\\").replace(quote.toString(), "\\$quote") + quote
            } else if (kind == RefKind.STAT && tok.kind == 'N' && readsStat(tok.text, id)) {
                edits += tok to tok.text.substringBeforeLast('.') + "." + newId
            }
        }
        if (edits.isEmpty()) return src
        val sb = StringBuilder(src)
        for ((tok, text) in edits.sortedByDescending { it.first.start }) sb.replace(tok.start, tok.end, text)
        return sb.toString()
    }
}
