import bakemon.FormulaScope
import bakemon.Setup
import creator.BakemonForm
import creator.BakemonForms
import creator.ConditionRecipes
import creator.CritMode
import creator.DamagePreset
import creator.DraftException
import creator.EffectRecipes
import creator.EntryKind
import creator.Events
import creator.FieldForm
import creator.FieldForms
import creator.Growth
import creator.HolderForm
import creator.HolderForms
import creator.JsonTools
import creator.MoveForm
import creator.MoveForms
import creator.MoveLimit
import creator.OwnerKind
import creator.Param
import creator.ParamType
import creator.PowerRecipes
import creator.Presets
import creator.Recipe
import creator.RecipeContext
import creator.RecipeUse
import creator.RuleAnswers
import creator.RulePart
import creator.RuleTemplate
import creator.SetupDraft
import creator.StageStyle
import creator.StatMods
import creator.StatPreset
import creator.StatusForm
import creator.StatusForms
import creator.TriggerForm
import creator.TriggerSpec
import creator.Triggers
import creator.TurnStyle
import creator.TypeForm
import creator.TypeForms
import engine.AiController
import engine.AiLevel
import engine.Manager
import util.Json
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** Tests for the setup creator's core layer (package creator). [root] is the project folder. */
fun runCreatorTests(root: File) {
    Check.section("creator")
    val temp = Files.createTempDirectory("bakemon-creator-tests").toFile()
    try {
        Check.guard("creator: json splice") { testSplice() }
        Check.guard("creator: json splice on shipped files") { testSpliceFuzz(root) }
        Check.guard("creator: shipped setups unchanged") { testShippedUnchanged(root, temp) }
        Check.guard("creator: shipped setups through forms") { testShippedForms(root) }
        Check.guard("creator: draft operations") { testDraftOperations(temp) }
        Check.guard("creator: broken and missing files") { testBrokenFiles(temp) }
        Check.guard("creator: formula checks") { testFormulaChecks(temp) }
        Check.guard("creator: rule templates") { testTemplates(temp) }
        Check.guard("creator: rule template update") { testTemplateUpdate(root) }
        Check.guard("creator: recipes speed_order") { testRecipes(temp, TurnStyle.SPEED_ORDER) }
        Check.guard("creator: recipes action_bar") { testRecipes(temp, TurnStyle.ACTION_BAR) }
        Check.guard("creator: per-target effects and new triggers speed_order") { testNewTriggers(temp, TurnStyle.SPEED_ORDER) }
        Check.guard("creator: per-target effects and new triggers action_bar") { testNewTriggers(temp, TurnStyle.ACTION_BAR) }
        Check.guard("creator: multi-hit and charge moves") { testMoveBehaviours(temp) }
        Check.guard("creator: rename with references") { testRename(root, temp) }
        Check.guard("creator: rename a stat") { testRenameStat(root, temp) }
        Check.guard("creator: delete with references") { testDelete(root, temp) }
        Check.guard("creator: end to end") { testEndToEnd(temp) }
    } finally {
        temp.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Helpers

private fun copySetup(root: File, name: String, temp: File, as_: String = name): File {
    val target = File(temp, as_)
    target.deleteRecursively()
    File(root, "Setups/$name").copyRecursively(target)
    return target
}

private fun shippedSetups(root: File): List<String> = Setup.discover(File(root, "Setups")).map { it.name }

/** Tests of a local setup that is not in the repository are skipped without it. */
private fun hasSetup(root: File, name: String): Boolean {
    val found = File(root, "Setups/$name").isDirectory
    if (!found) println("  (no $name setup under ${root.path}, test skipped)")
    return found
}

/** Same values: missing == null, numbers by value. */
private fun sameValues(a: Map<String, Any?>, b: Map<String, Any?>): Boolean =
    (a.keys + b.keys).all { k -> JsonTools.equal(a[k], b[k]) }

/** A value for an optional parameter in tests. */
private fun sample(p: Param): Any? = when (val t = p.type) {
    is ParamType.Number -> when (p.id) {
        "chance" -> 50.0
        else -> (p.default as? Double) ?: maxOf(t.min ?: 2.0, 2.0)
    }
    is ParamType.Choice -> p.default ?: t.options.firstOrNull()?.value
    ParamType.Text -> "{self} test"
    ParamType.YesNo -> true
    is ParamType.Formula -> p.default
    ParamType.Json -> p.default
    is ParamType.Effects -> p.default
}

/** The defaults of [r], and the defaults plus every optional parameter filled in. */
private fun variants(r: Recipe, ctx: RecipeContext): List<Map<String, Any?>> {
    val base = r.defaults(ctx)
    val full = base + r.params(ctx).filter { it.optional }.mapNotNull { p -> sample(p)?.let { p.id to it } }
    return if (full == base) listOf(base) else listOf(base, full)
}

/** Checks that [r] reads back what it builds; returns the built JSON of every variant. */
private fun roundTrip(r: Recipe, ctx: RecipeContext, label: String): List<Any?> = variants(r, ctx).map { values ->
    val json = r.build(values, ctx)
    val back = r.recognise(json, ctx)
    // A value may only be missed when it makes no difference (e.g. the amount of a "user's level" fixed damage).
    val same = back != null && (values.keys + back.keys).all { k ->
        JsonTools.equal(values[k], back[k]) || JsonTools.equal(r.build(values - k, ctx), json)
    }
    Check.ok(same, "$label: ${r.id} round trip: $values -> ${Json.stringify(json, false)} -> $back")
    json
}

private val PREFERRED_EVENTS = listOf(
    "hit", "turnEnd", "beforeMove", "modifyDamageDealt", "modifyDamageTaken", "dealtDamage", "damaged", "statusApplied", "fainted",
)

/** The first event of [owner] where [r] is available, or null. */
private fun eventFor(r: Recipe, owner: OwnerKind, ctx: RecipeContext): String? {
    val events = Events.forOwner(owner).map { it.value }
    return (PREFERRED_EVENTS.filter { it in events } + events).firstOrNull { r.available(ctx.withEvent(it)) }
}

/** A setup made from the template of [style], with a few types, statuses, fields, a move and a bakemon. */
private fun contentDraft(temp: File, style: TurnStyle, name: String): SetupDraft {
    val answers = RuleAnswers.forStyle(style).copy(name = name)
    val draft = SetupDraft.create(temp, name, answers)
    for ((typeName, color) in listOf("Fire" to "red", "Water" to "blue", "Rock" to "gray")) {
        draft.add(EntryKind.TYPE, TypeForms.build(TypeForm(draft.idFromName(EntryKind.TYPE, typeName), typeName, color)))
    }
    draft.setEffectiveness("fire", "rock", 0.5)
    draft.setEffectiveness("water", "fire", 2.0)
    val statusCtx = draft.recipeContext(OwnerKind.STATUS)
    draft.add(EntryKind.STATUS, StatusForms.build(StatusForm("burn", "Burn", kind = "major", statModifiers = mapOf("atk" to 0.5)), statusCtx))
    draft.add(EntryKind.STATUS, StatusForms.build(StatusForm("guard", "Guard", kind = "buff", duration = 2.0), statusCtx))
    val fieldCtx = draft.recipeContext(OwnerKind.FIELD)
    draft.add(EntryKind.FIELD, FieldForms.build(FieldForm("wall", "Wall", scope = "side", duration = 5.0), fieldCtx))
    draft.add(EntryKind.FIELD, FieldForms.build(FieldForm("rain", "Rain", group = "weather", duration = 5.0, selectable = true), fieldCtx))
    val moveCtx = draft.recipeContext(OwnerKind.MOVE)
    draft.add(EntryKind.MOVE, MoveForms.build(MoveForms.blank(moveCtx, "Tackle", "tackle"), moveCtx))
    val species = BakemonForms.blank(draft, "Pebble").copy(types = listOf("rock"), moves = listOf("tackle"), image = null)
    draft.add(EntryKind.BAKEMON, BakemonForms.build(species))
    return draft
}

private fun guided(spec: TriggerSpec) = TriggerForm.Guided(spec)

/** Adds an entry of [owner] holding [triggers] (a move for MOVE). */
private fun addOwnerEntry(draft: SetupDraft, owner: OwnerKind, id: String, triggers: List<TriggerForm>) {
    val ctx = draft.recipeContext(owner, entryId = id)
    val entry = when (owner) {
        OwnerKind.MOVE -> MoveForms.build(MoveForms.blank(ctx, id, id).copy(triggers = triggers), ctx)
        OwnerKind.ABILITY, OwnerKind.ITEM -> HolderForms.build(HolderForm(id, id, triggers = triggers), ctx)
        OwnerKind.STATUS -> StatusForms.build(StatusForm(id, id, kind = "volatile", triggers = triggers), ctx)
        OwnerKind.FIELD -> FieldForms.build(FieldForm(id, id, triggers = triggers), ctx)
    }
    draft.add(owner.entryKind, entry)
}

private fun SetupDraft.recipeContext(owner: OwnerKind, entryId: String): RecipeContext = RecipeContext.of(this, owner, null, entryId)

// ---------------------------------------------------------------------------------------------------------------
// JSON splicing

@Suppress("UNCHECKED_CAST")
private fun testSplice() {
    fun render(text: String, change: (MutableMap<String, Any?>) -> Unit): String {
        val original = Json.parse(text)
        val value = JsonTools.copyMap(original as Map<String, Any?>)
        change(value)
        val out = creatorRender(text, original, value)
        Check.ok(JsonTools.equal(Json.parse(out), value), "splice output parses back to the edited value: $out")
        return out
    }
    val text = "{\n  \"moves\": [\n    { \"id\": \"a\", \"power\": 40 },\n\n    {\n      \"id\": \"b\", // comment\n      \"flags\": [\"x\"]\n    }\n  ]\n}\n"
    Check.eq(text, render(text) {}, "unchanged text is kept")
    val power = render(text) { (it["moves"] as MutableList<Any?>).let { l -> (l[0] as MutableMap<String, Any?>)["power"] = 50.0 } }
    Check.ok(power.contains("{ \"id\": \"a\", \"power\": 50 }") && power.contains("// comment") && power.contains("},\n\n    {"), "a changed inline value keeps the rest: $power")
    val added = render(text) { (it["moves"] as MutableList<Any?>).add(linkedMapOf("id" to "c", "power" to 10.0)) }
    Check.ok(added.contains("\"id\": \"b\", // comment") && added.contains("    {\n      \"id\": \"c\",\n      \"power\": 10\n    }\n  ]"), "a new entry uses the pretty format at the right indent: $added")
    val removed = render(text) { (it["moves"] as MutableList<Any?>).removeAt(0) }
    Check.ok(removed.startsWith("{\n  \"moves\": [\n    {\n      \"id\": \"b\", // comment"), "removing the first entry keeps the second: $removed")
    val crlf = text.replace("\n", "\r\n")
    val crlfOut = render(crlf) { it["extra"] = listOf(1.0, 2.0) }
    Check.ok(!Regex("(?<!\r)\n").containsMatchIn(crlfOut), "CRLF files stay CRLF")
}

/** JsonSplice is internal: go through a draft file to exercise it. */
private fun creatorRender(text: String, original: Any?, value: Any?): String {
    val dir = Files.createTempDirectory("bakemon-splice").toFile()
    try {
        File(dir, "Rules.json").writeText(text)
        val draft = SetupDraft.load(dir)
        Check.ok(JsonTools.equal(draft.fileValue("Rules.json"), original), "draft reads the file")
        draft.setFileValue("Rules.json", value)
        return draft.fileText("Rules.json")!!
    } finally {
        dir.deleteRecursively()
    }
}

/** Random edits of every shipped JSON file: the spliced text must always parse back to the edited value. */
private fun testSpliceFuzz(root: File) {
    val rnd = Random(20261026)
    val files = File(root, "Setups").walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toList()
    Check.ok(files.isNotEmpty(), "shipped JSON files found: ${files.size}")
    for (file in files) {
        val text = file.readText()
        val original = Json.parse(text)
        Check.eq(text, creator.JsonSplice.render(text, original, JsonTools.copy(original)), "${file.name}: unchanged value keeps the text")
        repeat(12) { round ->
            val value = JsonTools.copy(original)
            repeat(1 + rnd.nextInt(3)) { mutate(value, rnd) }
            val out = creator.JsonSplice.render(text, original, value)
            val back = Json.parse(out)
            Check.ok(JsonTools.equal(back, value), "${file.parentFile.name}/${file.name} edit $round: spliced text parses back to the edited value")
        }
    }
}

/** One random edit somewhere in [root]: change, add or remove a member, or swap two array elements. */
private fun mutate(root: Any?, rnd: Random) {
    val containers = mutableListOf<Any>()
    fun collect(v: Any?) {
        when (v) {
            is MutableMap<*, *> -> { containers += v; v.values.forEach(::collect) }
            is MutableList<*> -> { containers += v; v.forEach(::collect) }
        }
    }
    collect(root)
    val target = containers[rnd.nextInt(containers.size)]
    val newValue: Any? = JsonTools.copy(when (rnd.nextInt(4)) {
        0 -> rnd.nextInt(100).toDouble()
        1 -> "edited ${rnd.nextInt(100)}"
        2 -> linkedMapOf<String, Any?>("id" to "new_${rnd.nextInt(1000)}", "list" to listOf(1.0, "a"), "nested" to linkedMapOf("x" to true))
        else -> listOf(linkedMapOf("type" to "message", "text" to "hi"))
    })
    @Suppress("UNCHECKED_CAST")
    when (target) {
        is MutableMap<*, *> -> {
            val map = target as MutableMap<String, Any?>
            val keys = map.keys.toList()
            when {
                keys.isEmpty() || rnd.nextInt(3) == 0 -> map["zz_${rnd.nextInt(1000)}"] = newValue
                rnd.nextBoolean() -> map[keys[rnd.nextInt(keys.size)]] = newValue
                else -> map.remove(keys[rnd.nextInt(keys.size)])
            }
        }
        is MutableList<*> -> {
            val list = target as MutableList<Any?>
            when {
                list.isEmpty() || rnd.nextInt(3) == 0 -> list.add(rnd.nextInt(list.size + 1), newValue)
                list.size >= 2 && rnd.nextBoolean() -> {
                    val i = rnd.nextInt(list.size)
                    val j = rnd.nextInt(list.size)
                    val t = list[i]; list[i] = list[j]; list[j] = t
                }
                else -> list.removeAt(rnd.nextInt(list.size))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Shipped setups

private fun snapshot(dir: File): Map<String, ByteArray> =
    dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes() }

private fun testShippedUnchanged(root: File, temp: File) {
    for (name in shippedSetups(root)) {
        val dir = copySetup(root, name, temp)
        val before = snapshot(dir)
        val draft = SetupDraft.load(dir)
        Check.ok(draft.problems.isEmpty(), "$name loads without problems: ${draft.problems}")
        Check.ok(!draft.isDirty, "$name: a loaded draft is not dirty")
        Check.eq(emptyList<Any>(), draft.changes(), "$name: no changes")
        val result = draft.save()
        Check.eq(emptyList<String>(), result.written, "$name: saving an unchanged draft writes nothing")
        Check.ok(result.validation.ok && result.validation.warnings.isEmpty(), "$name: still valid: ${result.validation.errors.take(3)} ${result.validation.warnings.take(3)}")
        val after = snapshot(dir)
        Check.eq(before.keys, after.keys, "$name: same files")
        Check.ok(before.all { (k, v) -> after[k]?.contentEquals(v) == true }, "$name: every file is byte-identical")
        // Every file's text is reproduced by the splicer when its value is unchanged.
        for (file in draft.fileNames()) {
            val f = File(dir, file)
            if (f.isFile) Check.eq(f.readText(), draft.fileText(file), "$name/$file: text reproduced")
        }
        // Edit one entry: only that entry's text changes, the rest of the file stays as it was.
        val moves = draft.ids(EntryKind.MOVE)
        val first = draft.entry(EntryKind.MOVE, moves[1])!!
        first["description"] = "Changed."
        draft.replace(EntryKind.MOVE, moves[1], first)
        val original = File(dir, "Moves.json").readText()
        val edited = draft.fileText("Moves.json")!!
        Check.ok(edited.length < original.length + 400 && edited.contains("\"Changed.\""), "$name: a small edit gives a small change")
        val lastId = moves.last()
        val lastStart = original.indexOf("\"id\": \"$lastId\"")
        Check.ok(lastStart > 0 && edited.contains(original.substring(lastStart, minOf(original.length, lastStart + 300))), "$name: other entries keep their text")
        Check.eq(listOf(moves[1]), draft.changes().single().changed, "$name: the change summary names the edited move")
        val v = draft.validate()
        Check.ok(v.ok, "$name: still valid after the edit: ${v.errors.take(3)}")
    }
}

private fun testShippedForms(root: File) {
    for (name in shippedSetups(root)) {
        val draft = SetupDraft.load(File(root, "Setups/$name"))
        var guidedCount = 0
        var plainCount = 0
        var total = 0
        for (owner in OwnerKind.entries) {
            for (entry in draft.entries(owner.entryKind)) {
                val id = entry["id"] as String
                val ctx = draft.recipeContext(owner, entryId = id)
                val rebuilt = when (owner) {
                    OwnerKind.MOVE -> MoveForms.build(MoveForms.read(entry, ctx), ctx, entry)
                    OwnerKind.ABILITY, OwnerKind.ITEM -> HolderForms.build(HolderForms.read(entry, ctx), ctx, entry)
                    OwnerKind.STATUS -> StatusForms.build(StatusForms.read(entry, ctx), ctx, entry)
                    OwnerKind.FIELD -> FieldForms.build(FieldForms.read(entry, ctx), ctx, entry)
                }
                Check.ok(JsonTools.equal(rebuilt, entry), "$name: ${owner.label} '$id' is unchanged by its form")
                val fieldSide = entry["scope"] == "side"
                for (trigger in (entry["triggers"] as? List<*>).orEmpty()) {
                    total++
                    val form = Triggers.read(trigger, ctx.copy(sideField = fieldSide))
                    if (form is TriggerForm.Guided) {
                        guidedCount++
                        if (!form.spec.usesAdvanced) plainCount++
                        Check.ok(JsonTools.equal(Triggers.build(form.spec, ctx), trigger, ordered = false), "$name: guided trigger of '$id' rebuilds")
                    }
                }
            }
        }
        for (entry in draft.entries(EntryKind.BAKEMON)) {
            Check.ok(JsonTools.equal(BakemonForms.build(BakemonForms.read(entry), entry), entry), "$name: bakemon '${entry["id"]}' unchanged by its form")
        }
        for (entry in draft.entries(EntryKind.TYPE)) {
            Check.ok(JsonTools.equal(TypeForms.build(TypeForms.read(entry), entry), entry), "$name: type '${entry["id"]}' unchanged by its form")
        }
        println("  $name: $guidedCount of $total triggers shown as guided forms ($plainCount without any advanced part)")
        Check.ok(total == 0 || guidedCount > 0, "$name: some triggers are recognised")
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Draft operations

private fun testDraftOperations(temp: File) {
    val draft = contentDraft(temp, TurnStyle.SPEED_ORDER, "Ops Test")
    Check.eq("Ops Test", draft.folderName, "folder name from the setup name")
    draft.save()
    Check.eq("Ops Test 2", SetupDraft.folderNameFor(temp, "Ops Test"), "unique folder names")
    Check.eq("ops test 2", SetupDraft.folderNameFor(temp, "ops test"), "unique folder names ignore case")
    Check.ok(File(temp, "Ops Test/Teams").isDirectory && File(temp, "Ops Test/Assets/README.txt").isFile, "a new setup gets Teams/ and Assets/README.txt")
    Check.eq("fire_fang", draft.idFromName(EntryKind.MOVE, "Fire Fang!"), "idFromName")
    Check.eq("tackle_2", draft.idFromName(EntryKind.MOVE, "Tackle"), "idFromName is unique")
    Check.eq("cafe", SetupDraft.snakeCase("Caf" + 233.toChar()), "idFromName is ASCII")
    Check.ok(draft.idProblem(EntryKind.STATUS, "all") != null, "'all' is reserved for statuses")
    Check.throws<DraftException>("duplicate id") { draft.add(EntryKind.MOVE, mapOf("id" to "tackle")) }
    val copy = draft.copy(EntryKind.MOVE, "tackle", "Big Tackle")
    Check.eq("big_tackle", copy, "copy gets an id from the new name")
    Check.eq(listOf("struggle", "tackle", "big_tackle"), draft.ids(EntryKind.MOVE), "copy goes right after the original")
    draft.moveTo(EntryKind.MOVE, "big_tackle", 0)
    Check.eq("big_tackle", draft.ids(EntryKind.MOVE).first(), "reorder")
    Check.ok(draft.isDirty, "dirty after edits")
    val change = draft.changes().single { it.file == "Moves.json" }
    Check.eq(listOf("big_tackle"), change.added, "change summary: added")
    Check.ok(change.summary().contains("1 added (big_tackle)"), "summary text: ${change.summary()}")
    Check.throws<DraftException>("replace must keep the id") { draft.replace(EntryKind.MOVE, "tackle", mapOf("id" to "other")) }
    draft.remove(EntryKind.MOVE, "big_tackle")
    Check.ok(draft.changes().none { it.file == "Moves.json" }, "removing the copy again leaves Moves.json unchanged")
    val checklist = draft.checklist()
    Check.ok(checklist.all { it.done }, "checklist complete: ${checklist.filter { !it.done }}")
    val empty = SetupDraft.create(temp, "Empty", RuleAnswers())
    Check.ok(empty.checklist().count { !it.done } >= 2, "an empty setup lists what is missing")
    Check.ok(empty.checklist().none { it.optional }, "the template's fallback move is required (it exists): ${empty.checklist()}")
    val noFallback = SetupDraft.create(temp, "No Fallback", RuleAnswers(fallbackMove = null))
    val advice = noFallback.checklist().filter { it.optional }
    Check.ok(advice.size == 1 && !advice[0].done && "optional" in advice[0].text, "a missing fallback move is only advice: ${noFallback.checklist()}")
    // Summaries keep abbreviations ("HP is not full") but lower-case ordinary labels ("apply a status").
    val itemCtx = RecipeContext.of(draft, OwnerKind.ITEM)
    val hpText = Triggers.describe(RecipeUse("not_full_hp", mapOf("who" to "self")), ConditionRecipes.byId("not_full_hp"), itemCtx)
    Check.ok(hpText.startsWith("HP is not full"), "abbreviations keep their case: $hpText")
    Check.ok(Triggers.describe(RecipeUse("message", mapOf("text" to "Hi")), EffectRecipes.byId("message"), itemCtx).startsWith("show a message"), "labels are lower-cased in summaries")
    val saved = empty.save()
    Check.ok(!saved.validation.ok, "an incomplete setup can be saved and reports errors")
    Check.ok(File(temp, "Empty/Moves.json").readText().contains("\"struggle\""), "the template's fallback move is created")
}

private fun testBrokenFiles(temp: File) {
    val dir = File(temp, "Broken")
    dir.mkdirs()
    File(dir, "Rules.json").writeText(Json.stringify(RuleTemplate.build(RuleAnswers(name = "Broken"))))
    File(dir, "Moves.json").writeText("{ \"moves\": [ { \"id\": \"a\" ")
    val draft = SetupDraft.load(dir)
    Check.eq(1, draft.problems.size, "a syntax error is reported: ${draft.problems}")
    Check.eq(emptyList<String>(), draft.ids(EntryKind.MOVE), "a broken file has no entries")
    Check.throws<DraftException>("a broken file is read-only") { draft.add(EntryKind.MOVE, mapOf("id" to "b")) }
    Check.eq(emptyList<String>(), draft.ids(EntryKind.BAKEMON), "a missing file is empty")
    draft.setFileText("Moves.json", "{ \"moves\": [ { \"id\": \"a\", \"name\": \"A\", \"category\": \"physical\", \"power\": 40 } ] }")
    Check.eq(listOf("a"), draft.ids(EntryKind.MOVE), "a repaired file is editable")
    Check.throws<DraftException>("bad JSON text is refused") { draft.setFileText("Moves.json", "[") }
    val result = draft.save()
    Check.ok("Moves.json" in result.written, "the repaired file is written")
    Check.ok(Json.parse(File(dir, "Moves.json").readText()) is Map<*, *>, "the repaired file is valid JSON")
}

private fun testFormulaChecks(temp: File) {
    val draft = contentDraft(temp, TurnStyle.SPEED_ORDER, "Formula Test")
    Check.eq(emptyList<String>(), draft.checkFormula("user.atk * 2", FormulaScope.MOVE), "valid power formula")
    val typo = draft.checkFormula("user.atkk * 2", FormulaScope.MOVE)
    Check.ok(typo.size == 1 && typo[0].contains("did you mean 'user.atk'") && !typo[0].contains("formula '"), "typo explained: $typo")
    Check.ok(draft.checkFormula("1 +", FormulaScope.MOVE).isNotEmpty(), "syntax error")
    Check.ok(draft.checkFormula("damage * 0.5", FormulaScope.TRIGGER, event = "turnEnd").any { it.contains("only set when") }, "event variables are checked")
    Check.eq(emptyList<String>(), draft.checkFormula("damage * 0.5", FormulaScope.TRIGGER, event = "hit"), "damage in a hit trigger")
    Check.ok(draft.checkFormula("fieldActive('snow')", FormulaScope.TRIGGER).isNotEmpty(), "unknown field literal")
    Check.ok(draft.checkFormula("random()", FormulaScope.MODIFIER).isNotEmpty(), "no dice in statModifiers")
    val ctx = draft.recipeContext(OwnerKind.ABILITY, "turnEnd")
    val damage = EffectRecipes.byId("damage")!!
    Check.ok(damage.problems(mapOf("amount" to "self.maxHp / 8"), ctx).isEmpty(), "advanced amount accepted")
    Check.ok(damage.problems(mapOf("amount" to "self.maxhp / 8"), ctx).isNotEmpty(), "advanced amount checked")
    Check.ok(damage.problems(mapOf("amount" to -3.0), ctx).isNotEmpty(), "range checked")
}

// ---------------------------------------------------------------------------------------------------------------
// Rule templates

/** [a] as recognise() gives it back (fields that make no difference are reset to their defaults). */
private fun normalized(a: RuleAnswers): RuleAnswers {
    val d = RuleAnswers()
    var n = a
    if (!n.levels) n = n.copy(levelMin = d.levelMin, levelMax = d.levelMax, levelDefault = d.levelDefault)
    if (!n.statPoints) n = n.copy(statPointsTotal = d.statPointsTotal, statPointsMax = d.statPointsMax)
    if (n.moveLimit != MoveLimit.PP) n = n.copy(fallbackMove = null)
    if (n.crits != CritMode.FIXED) n = n.copy(critChancePercent = d.critChancePercent, critMultiplier = d.critMultiplier)
    if (n.crits != CritMode.FROM_STATS) n = n.copy(critRateStat = d.critRateStat, critDamageStat = d.critDamageStat)
    if (n.turnSystem != TurnStyle.ACTION_BAR) n = n.copy(barFill = d.barFill, barRandomStart = false)
    if (n.categories == creator.CategoryStyle.ATTACK_SUPPORT) n = n.copy(specialAttackStat = d.specialAttackStat, specialDefenseStat = d.specialDefenseStat)
    if (n.growth == Growth.FLAT) n = n.copy(stats = n.stats.map { it.copy(grows = true) })
    return n
}

private fun testTemplates(temp: File) {
    var i = 0
    for (style in TurnStyle.entries) for (preset in listOf(StatPreset.POKEMON, StatPreset.GACHA, StatPreset.SIMPLE)) {
        for (growth in Growth.entries) for (damage in DamagePreset.entries) {
            var a = RuleAnswers.forStyle(style).withStatPreset(preset).copy(name = "Template $i", growth = growth, damage = damage)
            val crit = CritMode.entries[i % 3]
            a = a.copy(
                crits = if (crit == CritMode.FROM_STATS && preset != StatPreset.GACHA) CritMode.FIXED else crit,
                moveLimit = MoveLimit.entries[(i / 3) % 3],
                fallbackMove = if (i % 2 == 0) "struggle" else null,
                stages = StageStyle.entries[i % 2],
                statPoints = i % 2 == 0,
                hitTypes = i % 3 == 0,
                movesCanMiss = (i / 2) % 2 == 0,
                typeEffectiveness = (i / 4) % 2 == 0,
                randomSpread = (i / 5) % 2 == 0,
                sameTypeBonus = if (i % 4 == 0) 1.0 else 1.5,
                levels = i % 5 != 0,
                barRandomStart = i % 2 == 1,
                barFill = creator.BarFill.entries[i % 2],
                turnLimit = if (i % 7 == 0) 0 else 200,
            )
            Check.eq(emptyList<String>(), RuleTemplate.problems(a), "template $i answers are consistent")
            val rules = RuleTemplate.build(a)
            val recognised = RuleTemplate.recognise(rules)
            Check.eq(emptySet<RulePart>(), recognised.custom, "template $i: nothing looks hand-edited")
            Check.eq(normalized(a), normalized(recognised.answers), "template $i: answers recognised")
            // Minimal content through the API, then validate the saved folder.
            val draft = SetupDraft.create(temp, a.name, a)
            draft.add(EntryKind.TYPE, TypeForms.build(TypeForm("normal", "Normal", "white")))
            val ctx = draft.recipeContext(OwnerKind.MOVE)
            draft.add(EntryKind.MOVE, MoveForms.build(MoveForms.blank(ctx, "Hit", "hit").copy(type = "normal"), ctx))
            val stats = BakemonForms.blank(draft, "Blob", 60.0)
            draft.add(EntryKind.BAKEMON, BakemonForms.build(stats.copy(types = listOf("normal"), moves = listOf("hit"), image = null)))
            val result = draft.save()
            Check.ok(result.validation.ok && result.validation.warnings.isEmpty(),
                "template $i ($style $preset $growth $damage) validates: ${result.validation.errors.take(3)} ${result.validation.warnings.take(3)}")
            i++
        }
    }
    Check.ok(RuleTemplate.problems(RuleAnswers().copy(stats = emptyList())).isNotEmpty(), "no stats is a problem")
    Check.ok(RuleTemplate.problems(RuleAnswers().copy(crits = CritMode.FROM_STATS)).isNotEmpty(), "crits from missing stats is a problem")
    val rules = RuleTemplate.build(RuleAnswers())
    Check.eq("floor(floor(floor(2 * user.level / 5 + 2) * move.power * attack / defense) / 50 + 2) * (targets > 1 ? 0.75 : 1) * (sameType ? 1.5 : 1) * effectiveness * crit * random(0.85, 1)",
        rules["damage"], "Pokemon damage formula")
    Check.eq("floor(base * (0.5 + level / 80))", RuleTemplate.build(RuleAnswers.forStyle(TurnStyle.ACTION_BAR))["statFormulas"].let { (it as Map<*, *>)["hp"] }, "linear growth formula")
}

private fun testTemplateUpdate(root: File) {
    if (!hasSetup(root, "Classic")) return
    val classic = SetupDraft.load(File(root, "Setups/Classic"))
    val rules = classic.rules()
    val rec = classic.recogniseRules()
    Check.ok(RulePart.CRITS in rec.custom, "Classic's hand-written crit chance is detected: ${rec.custom}")
    Check.ok(RulePart.DAMAGE !in rec.custom && RulePart.GROWTH !in rec.custom, "Classic's damage and growth match the Pokemon template")
    val newAnswers = rec.answers.copy(turnLimit = 100)
    val parts = RuleTemplate.changedParts(rec.answers, newAnswers)
    Check.eq(setOf(RulePart.TURN_LIMIT), parts, "only the turn limit changes")
    val updated = RuleTemplate.apply(rules, newAnswers, parts)
    Check.eq(100.0, updated["turnLimit"], "turn limit regenerated")
    Check.ok(rules.keys.filter { it != "turnLimit" }.all { JsonTools.equal(rules[it], updated[it]) }, "everything else is kept (hand-written crits included)")
    Check.eq(rules.keys.toList(), updated.keys.toList(), "key order kept")
}

// ---------------------------------------------------------------------------------------------------------------
// Recipes

private fun testRecipes(temp: File, style: TurnStyle) {
    val label = style.id
    val draft = contentDraft(temp, style, "Recipes $label")
    var n = 0
    // Effects and conditions, for every owner kind, in an event where they make sense.
    for (owner in OwnerKind.entries) {
        val base = draft.recipeContext(owner, entryId = "e_${owner.label}_x")
        for (r in EffectRecipes.all) {
            val event = eventFor(r, owner, base) ?: continue
            val ctx = base.withEvent(event).copy(entryId = "e_${owner.label}_${n}")
            for (json in roundTrip(r, ctx, "$label ${owner.label} $event")) {
                val use = if (r.id == "advanced") RecipeUse("advanced", mapOf("json" to json)) else EffectRecipes.recognise(json, ctx)
                if (r.id != "advanced") Check.eq(r.id, use.recipe, "$label: the effect catalog picks ${r.id} for its own JSON")
                val spec = TriggerSpec(event, effects = listOf(use))
                Check.eq(emptyList<String>(), Triggers.problems(spec, ctx), "$label ${owner.label}: ${r.id} trigger has no problems")
                val built = Triggers.build(spec, ctx)
                val read = Triggers.read(built, ctx)
                Check.ok(read is TriggerForm.Guided && (read.spec == spec || (r.id == "advanced" && JsonTools.equal(Triggers.build(read.spec, ctx), built))),
                    "$label ${owner.label}: ${r.id} trigger reads back: $read")
                addOwnerEntry(draft, owner, "e_${owner.label}_${n++}", listOf(guided(spec)))
            }
        }
        for (r in ConditionRecipes.all) {
            val event = eventFor(r, owner, base) ?: continue
            val ctx = base.withEvent(event)
            for (json in roundTrip(r, ctx, "$label ${owner.label} $event")) {
                val conditions = listOf(RecipeUse(r.id, r.recognise(json, ctx) ?: emptyMap()), RecipeUse("advanced", mapOf("formula" to "turn > 1 || allies > 0")))
                val spec = TriggerSpec(event, conditions = conditions, chance = 50.0, limit = 2, message = "{self} checks.",
                    effects = listOf(RecipeUse("message", mapOf("text" to "{self} reacts."))))
                val read = Triggers.read(Triggers.build(spec, ctx), ctx)
                Check.ok(read is TriggerForm.Guided && read.spec == spec, "$label ${owner.label}: condition ${r.id} reads back: $read / ${Triggers.build(spec, ctx)}")
                addOwnerEntry(draft, owner, "c_${owner.label}_${n++}", listOf(guided(spec)))
            }
        }
    }
    // Move power.
    val moveCtx = draft.recipeContext(OwnerKind.MOVE)
    for (r in PowerRecipes.available(moveCtx)) {
        for (json in roundTrip(r, moveCtx, "$label power")) {
            val use = RecipeUse(r.id, r.recognise(json, moveCtx)!!)
            val id = "p_${n++}"
            val form = MoveForms.blank(moveCtx, id, id).copy(power = use, hits = "randint(2, 3)", critBonus = MoveForms.HIGH_CRIT_BONUS)
            val entry = MoveForms.build(form, moveCtx)
            Check.eq(form, MoveForms.read(entry, moveCtx), "$label: move with power ${r.id} reads back")
            draft.add(EntryKind.MOVE, entry)
        }
    }
    // Presets.
    for ((owner, presets) in listOf(OwnerKind.STATUS to Presets.statuses, OwnerKind.ABILITY to Presets.holders, OwnerKind.ITEM to Presets.holders, OwnerKind.FIELD to Presets.fields)) {
        for (r in presets) {
            val id = "s_${owner.label}_${n++}"
            val ctx = draft.recipeContext(owner, entryId = id)
            if (!r.available(ctx)) continue
            for ((k, json) in roundTrip(r, ctx, "$label preset ${owner.label}").withIndex()) {
                val entryId = if (k == 0) id else "${id}_$k"
                val c = ctx.copy(entryId = entryId)
                @Suppress("UNCHECKED_CAST")
                val fragment = r.build(r.recognise(json, ctx)!!, c) as Map<String, Any?>
                val entry: MutableMap<String, Any?> = when (owner) {
                    OwnerKind.STATUS -> StatusForms.build(StatusForm(entryId, entryId, kind = "debuff", duration = 3.0), c)
                    OwnerKind.FIELD -> FieldForms.build(FieldForm(entryId, entryId, duration = 5.0), c)
                    else -> HolderForms.build(HolderForm(entryId, entryId), c)
                }
                Presets.apply(entry, fragment)
                if (k == 0) Check.eq(r.id, Presets.recognise(entry, c)?.recipe, "$label: preset ${r.id} recognised in its entry")
                draft.add(owner.entryKind, entry)
            }
        }
    }
    val result = draft.save()
    Check.ok(result.validation.ok, "$label: every recipe validates: ${result.validation.errors.take(5)}")
    Check.eq(emptyList<String>(), result.validation.warnings, "$label: no warnings")
    // Readable generated formulas.
    val text = File(draft.dir, "Abilities.json").readText() + File(draft.dir, "Moves.json").readText()
    Check.ok(!Regex("\\d\\.\\d{7,}").containsMatchIn(text), "$label: generated numbers are short")
    Check.ok(text.contains("\"self.maxHp * 0.25\"") || text.contains("\"target.maxHp * 0.25\""), "$label: percent formulas look like 'self.maxHp * 0.25'")
}

/** The effects of the first trigger of [json] (a built trigger). */
private fun effectsOf(json: Map<String, Any?>): List<Map<*, *>> = (json["effects"] as List<*>).map { it as Map<*, *> }

/**
 * The recipes added for the new engine features: per-target ("each target's own") values with group targets, the
 * "when its HP drops below X%" trigger, recipient checks of conditions and chances, use-a-move presets and their
 * recognition, nested delayed effects, the new events, conditions and targets. Everything validates with 0 warnings.
 */
private fun testNewTriggers(temp: File, style: TurnStyle) {
    val label = style.id
    val draft = contentDraft(temp, style, "New Triggers $label")
    val ability = draft.recipeContext(OwnerKind.ABILITY, "x")
    val turnEnd = ability.withEvent("turnEnd")
    val triggers = mutableListOf<TriggerSpec>()
    fun check(spec: TriggerSpec, ctx: RecipeContext, what: String): Map<String, Any?> {
        Check.eq(emptyList<String>(), Triggers.problems(spec, ctx), "$label: $what has no problems")
        val built = Triggers.build(spec, ctx)
        val read = Triggers.read(built, ctx)
        Check.ok(read is TriggerForm.Guided && read.spec == spec, "$label: $what reads back: $read")
        return built
    }

    // Heal every ally 20% of ITS max HP: recipient for a group, the monster itself for one monster.
    val heal = TriggerSpec("turnEnd", effects = listOf(RecipeUse("heal_max_hp", mapOf("percent" to 20.0, "base" to "maxHp", "target" to "allies"))))
    val healJson = effectsOf(check(heal, ability, "heal every ally"))
    Check.ok(JsonTools.equal(mapOf("type" to "heal", "target" to "allies", "amount" to "recipient.maxHp * 0.2"), healJson[0]), "$label: heal every ally 20% of its max HP: ${healJson[0]}")
    triggers += heal
    val own = Triggers.build(TriggerSpec("turnEnd", effects = listOf(RecipeUse("heal_max_hp", mapOf("percent" to 25.0, "base" to "maxHp")))), ability)
    Check.eq("self.maxHp * 0.25", effectsOf(own)[0]["amount"], "$label: one monster keeps the readable formula")
    val lost = Triggers.build(TriggerSpec("turnEnd", effects = listOf(RecipeUse("heal_max_hp", mapOf("percent" to 50.0, "base" to "missing", "target" to "team")))), ability)
    Check.eq("(recipient.maxHp - recipient.hp) * 0.5", effectsOf(lost)[0]["amount"], "$label: heal half of the HP each one lost")
    for ((recipe, type) in listOf("damage_max_hp" to "damage", "shield_max_hp" to "shield", "set_hp" to "setHp")) {
        val spec = TriggerSpec("turnEnd", effects = listOf(RecipeUse(recipe, mapOf("percent" to 10.0, "base" to "hp", "target" to "enemies"))))
        val effect = effectsOf(check(spec, ability, recipe))[0]
        Check.ok(effect["type"] == type && effect["amount"] == "recipient.hp * 0.1", "$label: $recipe of each enemy's current HP: $effect")
        triggers += spec
    }
    // A hand-written per-target formula of another shape stays exactly as it is (advanced amount).
    val odd = mapOf("on" to "turnEnd", "effects" to listOf(mapOf("type" to "heal", "target" to "allies", "amount" to "self.maxHp * 0.1")))
    val oddForm = Triggers.read(odd, ability)
    Check.ok(oddForm is TriggerForm.Guided && oddForm.spec.effects[0].recipe == "heal" && JsonTools.equal(Triggers.buildAll(listOf(oddForm), ability)[0], odd), "$label: other formulas are kept: $oddForm")

    // When its HP drops below 50%: damaged + the crossing condition, once per battle, recognised back.
    val drop = Events.withHpDrop(TriggerSpec("turnEnd", effects = listOf(RecipeUse("stat_stage", mapOf("stat" to "atk", "stages" to 2.0)))), 50.0)
    Check.ok(drop.event == "damaged" && drop.limit == 1, "$label: the HP threshold is a damaged trigger, once per battle: $drop")
    val dropJson = check(drop, ability, "HP threshold")
    Check.eq("self.hpPercent < 50 && hpBefore * 100 / self.maxHp >= 50", dropJson["condition"], "$label: the crossing condition")
    val dropRead = (Triggers.read(dropJson, ability) as TriggerForm.Guided).spec
    Check.ok(Events.hpDrop(dropRead) != null, "$label: recognised as an HP threshold")
    Check.eq("when its HP drops below 50%", Events.whenText(dropRead, OwnerKind.ABILITY), "$label: WHEN in plain words")
    Check.ok(Triggers.describe(TriggerForm.Guided(dropRead), ability).startsWith("When its HP drops below 50%, once per battle: raise or lower a stat"),
        "$label: summary: ${Triggers.describe(TriggerForm.Guided(dropRead), ability)}")
    Check.eq("when a covered monster's HP drops below 50%", Events.whenText(dropRead, OwnerKind.FIELD), "$label: fields say whose HP")
    val dropMore = drop.copy(conditions = drop.conditions + RecipeUse("has_status", mapOf("who" to "self", "status" to "burn")), limit = 0)
    check(dropMore, ability, "HP threshold with another condition, every time")
    Check.eq(0, Events.withHpDrop(dropMore, 25.0).limit, "$label: changing the % keeps \"every time\"")
    Check.ok(Events.whenChoices(OwnerKind.ABILITY).any { it.value == Events.HP_DROP } && Events.whenChoices(OwnerKind.MOVE).none { it.value == Events.HP_DROP },
        "$label: the HP threshold is offered to monsters, not to moves")
    triggers += drop
    triggers += dropMore

    // recipient has no value in conditions and chances.
    Check.ok(draft.checkFormula("recipient.hp > 5", FormulaScope.TRIGGER, event = "turnEnd", recipient = false).any { "recipient" in it }, "$label: recipient in a condition is a problem")
    Check.eq(emptyList<String>(), draft.checkFormula("recipient.maxHp * 0.2", FormulaScope.TRIGGER, event = "turnEnd"), "$label: recipient in an effect value is fine")
    val badChance = TriggerSpec("turnEnd", chance = "recipient.hpPercent", effects = heal.effects)
    Check.ok(Triggers.problems(badChance, ability).any { it.startsWith("Chance:") && "recipient" in it }, "$label: recipient in a chance: ${Triggers.problems(badChance, ability)}")
    val badCondition = TriggerSpec("turnEnd", conditions = listOf(RecipeUse("advanced", mapOf("formula" to "recipient.hp > 1"))), effects = heal.effects)
    Check.ok(Triggers.problems(badCondition, ability).any { "recipient" in it }, "$label: recipient in a condition: ${Triggers.problems(badCondition, ability)}")

    // Counterattack, joining an ally's attack, using a move again; a plain useMove elsewhere is not a "strike back".
    val attacked = ability.withEvent("attacked")
    val counter = TriggerSpec("attacked", conditions = listOf(RecipeUse("other_is_enemy", emptyMap()), RecipeUse("landed", emptyMap())),
        effects = listOf(RecipeUse("counterattack", mapOf("power" to 50.0, "message" to "{self} strikes back!"))))
    val counterJson = check(counter, ability, "counterattack")
    Check.eq("!sameSide(self, other) && landed", counterJson["condition"], "$label: counterattack condition")
    Check.ok(JsonTools.equal(mapOf("type" to "useMove", "move" to "first", "power" to 50.0, "message" to "{self} strikes back!"), effectsOf(counterJson)[0]), "$label: counterattack effect")
    triggers += counter
    val join = TriggerSpec("allyUsedMove", effects = listOf(RecipeUse("join_attack", emptyMap())))
    Check.ok(JsonTools.equal(mapOf("type" to "useMove", "move" to "first", "moveTarget" to "otherTarget"), effectsOf(check(join, ability, "join the attack"))[0]), "$label: join the attack")
    triggers += join
    val plain = mapOf("on" to "turnEnd", "effects" to listOf(mapOf("type" to "useMove", "move" to "first")))
    Check.eq("use_move", (Triggers.read(plain, ability) as TriggerForm.Guided).spec.effects[0].recipe, "$label: a useMove in turnEnd is the general recipe")
    Check.eq("counterattack", EffectRecipes.recognise(mapOf("type" to "useMove", "move" to "first"), attacked).recipe, "$label: and a counterattack where it fits")
    val free = mapOf("on" to "turnEnd", "effects" to listOf(mapOf("type" to "useMove", "free" to true)))
    val freeForm = Triggers.read(free, ability) as TriggerForm.Guided
    Check.ok(freeForm.spec.effects[0].recipe == "advanced" && JsonTools.equal(Triggers.buildAll(listOf(freeForm), ability)[0], free), "$label: unknown shapes stay raw JSON")
    Check.ok(P_targets(attacked).contains("otherTarget") && !P_targets(turnEnd).contains("otherTarget"), "$label: 'the target of that move' only where the other monster used a move")
    Check.ok(P_targets(turnEnd).containsAll(listOf("lowestHpAlly", "randomAlly", "lowestHpEnemy", "highestHpEnemy")), "$label: the new targets are offered")

    // A Wish-like delayed heal (ability) and a Future-Sight-like move (nested effects recognised).
    val wish = TriggerSpec("turnEnd", effects = listOf(RecipeUse("delay", mapOf("turns" to 1.0, "message" to "{self}'s wish came true!",
        "effects" to listOf(RecipeUse("heal_max_hp", mapOf("percent" to 50.0, "base" to "maxHp")))))))
    val wishJson = effectsOf(check(wish, ability, "delayed heal"))[0]
    Check.ok(JsonTools.equal(listOf(mapOf("type" to "heal", "amount" to "self.maxHp * 0.5")), wishJson["effects"]), "$label: nested effects: $wishJson")
    triggers += wish
    val emptyDelay = TriggerSpec("turnEnd", effects = listOf(RecipeUse("delay", mapOf("turns" to 1.0, "effects" to emptyList<RecipeUse>()))))
    Check.ok(Triggers.problems(emptyDelay, ability).any { "at least one effect" in it }, "$label: a delay needs effects")
    val noDamage = TriggerSpec("turnEnd", effects = listOf(RecipeUse("delay", mapOf("turns" to 1.0, "effects" to listOf(RecipeUse("multiply_damage", mapOf("value" to 2.0)))))))
    Check.ok(Triggers.problems(noDamage, ability.withEvent("modifyDamageTaken")).any { "does nothing" in it }, "$label: no multiply-the-damage in a delay")
    val moveCtx = draft.recipeContext(OwnerKind.MOVE)
    val future = TriggerSpec("hit", effects = listOf(RecipeUse("delay", mapOf("turns" to 2.0, "effects" to listOf(RecipeUse("attack", mapOf("power" to 120.0)))))))
    val futureJson = effectsOf(check(future, moveCtx, "Future Sight"))[0]
    Check.ok(JsonTools.equal(mapOf("type" to "delay", "turns" to 2.0, "effects" to listOf(mapOf("type" to "attack", "power" to 120.0))), futureJson), "$label: Future Sight: $futureJson")
    draft.add(EntryKind.MOVE, MoveForms.build(MoveForms.blank(moveCtx, "Future", "future").copy(power = RecipeUse("none", emptyMap()), triggers = listOf(guided(future))), moveCtx))

    // New conditions and events in plain words.
    val hit = ability.withEvent("hit")
    for ((formula, recipe) in listOf("hitIndex == 3" to "hit_number", "hitIndex >= 2" to "hit_at_least", "hitIndex == hitCount" to "last_hit",
        "statusCount(other, 'buff') >= 2" to "status_count", "statusCount(self) <= 0" to "status_count", "other.acted" to "acted", "!self.acted" to "acted",
        "self.${hit.speedStat} > other.${hit.speedStat}" to "faster", "allies >= 1" to "allies_count", "other.charging" to "charging")) {
        val parts = creator.ConditionRecipes.split(formula, hit)
        Check.ok(parts.size == 1 && parts[0].recipe == recipe, "$label: '$formula' is '$recipe': $parts")
        triggers += TriggerSpec("hit", conditions = parts, effects = listOf(RecipeUse("message", mapOf("text" to "{self}!"))))
    }
    for (event in listOf("targeted", "attacked", "allyDamaged", "enemyDamaged", "allyUsedMove", "enemyUsedMove", "statusInflicted")) {
        Check.ok(Events.label(event, OwnerKind.ABILITY).startsWith("when "), "$label: $event in plain words")
        Check.ok(Events.forOwner(OwnerKind.MOVE).none { it.value == event }, "$label: $event is not a move event")
    }
    Check.eq("when an ally of a covered monster takes damage", Events.label("allyDamaged", OwnerKind.FIELD), "$label: field label")

    for ((i, spec) in triggers.withIndex()) addOwnerEntry(draft, OwnerKind.ABILITY, "t_$i", listOf(guided(spec)))
    val result = draft.save()
    Check.ok(result.validation.ok, "$label: the new triggers validate: ${result.validation.errors.take(5)}")
    Check.eq(emptyList<String>(), result.validation.warnings, "$label: no warnings")
}

/** Effect target values offered in [ctx]. */
private fun P_targets(ctx: RecipeContext): List<String> =
    (EffectRecipes.byId("stat_stage")!!.params(ctx).first { it.id == "target" }.type as ParamType.Choice).options.map { it.value }

/** Multi-hit forms (growing power, accuracy per hit, random enemies, ignore defense) and charge forms. */
private fun testMoveBehaviours(temp: File) {
    for (style in TurnStyle.entries) {
        val label = style.id
        val draft = contentDraft(temp, style, "Moves $label")
        val m = draft.recipeContext(OwnerKind.MOVE)
        val blank = MoveForms.blank(m, "Kick", "kick").copy(power = RecipeUse("fixed", mapOf("power" to 20.0)))
        // Triple Kick: 3 hits, power x hit number, every hit rolls the accuracy, the 3rd hit burns.
        val grown = MoveForms.withGrowingPower(blank.copy(hits = 3.0, hitAccuracy = "each", accuracy = 90.0), true)!!
        Check.eq(RecipeUse("per_hit", mapOf("power" to 20.0)), grown.power, "$label: the power grows with each hit")
        Check.eq(blank.power, MoveForms.withGrowingPower(grown, false)!!.power, "$label: and back to a fixed power")
        Check.eq(null, MoveForms.withGrowingPower(blank.copy(power = RecipeUse("user_low_hp", mapOf("min" to 20.0, "max" to 200.0))), true), "$label: only a fixed power can grow")
        val third = TriggerSpec("hit", conditions = listOf(RecipeUse("hit_number", mapOf("number" to 3.0))),
            effects = listOf(RecipeUse("apply_status", mapOf("status" to "burn"))))
        val kick = grown.copy(triggers = listOf(guided(third)))
        val kickJson = MoveForms.build(kick, m)
        Check.ok(kickJson["power"] == "20 * hitIndex" && kickJson["hits"] == 3.0 && kickJson["hitAccuracy"] == "each", "$label: Triple Kick JSON: $kickJson")
        Check.eq(listOf("id", "name", "category", "power", "accuracy", "pp", "cooldown", "target", "hits", "hitAccuracy", "triggers").filter { it in kickJson },
            kickJson.keys.toList(), "$label: README key order")
        Check.eq("hitIndex == 3", ((kickJson["triggers"] as List<*>)[0] as Map<*, *>)["condition"], "$label: on hit number 3")
        val kickRead = MoveForms.read(kickJson, m)
        Check.ok(kickRead.copy(triggers = kick.triggers) == kick && (kickRead.triggers.single() as TriggerForm.Guided).spec == third, "$label: Triple Kick reads back: $kickRead")
        draft.add(EntryKind.MOVE, kickJson)
        // 2-5 hits spread over random enemies, ignoring half of the defense.
        val spread = blank.copy(id = "spread", name = "Spread", hits = MoveForms.hits(2, 5), target = "randomEnemies", ignoreDefense = 50.0)
        val spreadJson = MoveForms.build(spread, m)
        Check.ok(spreadJson["hits"] == "randint(2, 5)" && spreadJson["target"] == "randomEnemies" && spreadJson["ignoreDefense"] == 50.0, "$label: spread JSON: $spreadJson")
        Check.eq(spread, MoveForms.read(spreadJson, m), "$label: spread reads back")
        draft.add(EntryKind.MOVE, spreadJson)
        // The new automatic move targets.
        for (target in listOf("lowestHpAlly", "lowestHpEnemy")) {
            Check.ok(MoveForms.TARGETS.any { it.value == target }, "$label: move target $target is offered")
        }
        val heal = blank.copy(id = "mend", name = "Mend", category = m.categories.first { it.value !in m.damagingCategories }.value, power = RecipeUse("none", emptyMap()),
            accuracy = null, target = "lowestHpAlly", triggers = listOf(guided(TriggerSpec("hit", effects = listOf(RecipeUse("heal_max_hp", mapOf("percent" to 30.0, "base" to "maxHp")))))))
        draft.add(EntryKind.MOVE, MoveForms.build(heal, m))
        // Charge moves: a form for the known keys, the rest kept as it is.
        val charge = creator.ChargeForm(turns = 2, invulnerable = true, skipIf = creator.ConditionRecipes.join(listOf(RecipeUse("field_active", mapOf("field" to "rain"))), m))
        val chargeJson = creator.ChargeForms.build(charge, null)
        Check.ok(JsonTools.equal(mapOf("turns" to 2.0, "invulnerable" to true, "skipIf" to "fieldActive('rain')"), chargeJson), "$label: charge JSON: $chargeJson")
        Check.eq(charge, creator.ChargeForms.read(chargeJson), "$label: charge reads back")
        Check.ok(JsonTools.equal(emptyMap<String, Any?>(), creator.ChargeForms.build(creator.ChargeForm(), null)), "$label: a default charge is {}")
        val dive = blank.copy(id = "dive", name = "Dive", power = RecipeUse("fixed", mapOf("power" to 80.0)), charge = chargeJson)
        val diveJson = MoveForms.build(dive, m)
        Check.ok(JsonTools.equal(chargeJson, diveJson["charge"]), "$label: the move has its charge")
        Check.eq(dive, MoveForms.read(diveJson, m), "$label: charge move reads back")
        draft.add(EntryKind.MOVE, diveJson)
        val customCharge = linkedMapOf<String, Any?>("turns" to "randint(1, 2)", "message" to "{user} hides!")
        Check.eq(null, creator.ChargeForms.read(customCharge), "$label: a formula for the turns is not a form")
        val edited = creator.ChargeForms.build(creator.ChargeForm(turns = 1, message = "{user} glows!"), linkedMapOf("turns" to 1.0, "message" to "x", "extra" to 1.0))
        Check.ok(edited["extra"] == 1.0 && edited["message"] == "{user} glows!" && edited["turns"] == 1.0, "$label: unknown charge keys are kept: $edited")
        val customMove = MoveForms.build(blank.copy(id = "custom", name = "Custom", charge = customCharge), m)
        Check.ok(JsonTools.equal(customCharge, MoveForms.build(MoveForms.read(customMove, m), m, customMove)["charge"]), "$label: a custom charge is untouched")
        // A U-turn and a "use this move again" move.
        val uTurn = blank.copy(id = "u_turn", name = "U-turn", triggers = listOf(guided(TriggerSpec("afterMove", effects = listOf(RecipeUse("switch_out", emptyMap()))))))
        Check.ok(JsonTools.equal(listOf(mapOf("type" to "switchOut")), ((MoveForms.build(uTurn, m)["triggers"] as List<*>)[0] as Map<*, *>)["effects"]), "$label: U-turn")
        draft.add(EntryKind.MOVE, MoveForms.build(uTurn, m))
        val again = blank.copy(id = "again", name = "Again", triggers = listOf(guided(TriggerSpec("afterMove", chance = 30.0, effects = listOf(RecipeUse("use_again", emptyMap()))))))
        draft.add(EntryKind.MOVE, MoveForms.build(again, m))
        val result = draft.save()
        Check.ok(result.validation.ok, "$label: multi-hit and charge moves validate: ${result.validation.errors.take(5)}")
        Check.eq(emptyList<String>(), result.validation.warnings, "$label: no warnings")
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Rename and delete

private fun allText(dir: File): String = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.joinToString("\n") { it.readText() }

private fun teamsValid(dir: File, label: String) {
    val setup = Setup.load(dir)
    for (team in setup.listTeams()) {
        val problems = setup.loadTeam(team).validate(setup)
        Check.eq(emptyList<String>(), problems, "$label: team '$team' is valid")
    }
}

private fun testRename(root: File, temp: File) {
    if (!hasSetup(root, "Classic")) return
    val dir = copySetup(root, "Classic", temp, "Classic Rename")
    val draft = SetupDraft.load(dir)
    val refs = draft.references(EntryKind.STATUS, "burn")
    Check.ok(refs.any { it.inFormula } && refs.any { !it.inFormula }, "burn is used by effects and formulas: $refs")
    draft.rename(EntryKind.STATUS, "burn", "scorch")
    Check.eq(emptyList<Any>(), draft.references(EntryKind.STATUS, "burn"), "no reference to the old id is left")
    Check.eq(refs.size, draft.references(EntryKind.STATUS, "scorch").size, "every reference points to the new id")
    // A type: chart, species, moves, statuses, formulas.
    val typeRefs = draft.references(EntryKind.TYPE, "fire")
    Check.ok(typeRefs.any { it.file == "Types.json" } && typeRefs.any { it.inFormula } && typeRefs.any { it.file == "Bakemons.json" }, "fire is used everywhere: ${typeRefs.size}")
    draft.rename(EntryKind.TYPE, "fire", "flame")
    // A species, a move, an ability and an item used by team files.
    val team = Json.parse(File(dir, "Teams/Starter Trio.json").readText()) as Map<*, *>
    val member = (team["members"] as List<*>)[0] as Map<*, *>
    val species = member["species"] as String
    val move = (member["moves"] as List<*>)[0] as String
    val ability = member["ability"] as String
    val item = member["item"] as String
    Check.ok(draft.references(EntryKind.BAKEMON, species).any { it.file.startsWith("Teams/") }, "species used by a team")
    draft.rename(EntryKind.BAKEMON, species, "${species}_x")
    draft.rename(EntryKind.MOVE, move, "${move}_x")
    draft.rename(EntryKind.ABILITY, ability, "${ability}_x")
    draft.rename(EntryKind.ITEM, item, "${item}_x")
    draft.rename(EntryKind.MOVE, "struggle", "flail")
    Check.eq("flail", draft.rules()["fallbackMove"], "the fallback move follows the rename")
    Check.throws<DraftException>("rename to a taken id") { draft.rename(EntryKind.MOVE, "tackle", "flail") }
    val result = draft.save()
    Check.ok(result.validation.ok && result.validation.warnings.isEmpty(), "renamed setup is valid: ${result.validation.errors.take(3)} ${result.validation.warnings.take(3)}")
    val text = allText(dir)
    Check.ok(!text.contains("'burn'") && !text.contains("\"burn\""), "no 'burn' left in the files")
    Check.ok(text.contains("hasStatus(target, 'scorch')") && text.contains("move.type == 'flame'"), "formulas were updated")
    Check.ok(!text.contains("\"$species\""), "no old species id left")
    teamsValid(dir, "rename")
}

private fun testRenameStat(root: File, temp: File) {
    if (!hasSetup(root, "Gacha")) return
    val dir = copySetup(root, "Gacha", temp, "Gacha Stat")
    val draft = SetupDraft.load(dir)
    val refs = draft.statReferences("crate")
    Check.ok(refs.any { it.inFormula } && refs.any { it.file == "Bakemons.json" }, "crate is used by formulas and base stats: ${refs.size}")
    draft.renameStat("crate", "crit_rate")
    draft.renameStat("spd", "speed")
    Check.eq("speed", draft.rules()["speedStat"], "speedStat follows")
    Check.throws<DraftException>("bad stat id") { draft.renameStat("atk", "1x") }
    val result = draft.save()
    Check.ok(result.validation.ok && result.validation.warnings.isEmpty(), "Gacha with renamed stats is valid: ${result.validation.errors.take(3)} ${result.validation.warnings.take(3)}")
    Check.ok(!allText(dir).contains("crate"), "no 'crate' left")
    teamsValid(dir, "stat rename")
}

private fun testDelete(root: File, temp: File) {
    if (!hasSetup(root, "Classic")) return
    val dir = copySetup(root, "Classic", temp, "Classic Delete")
    val draft = SetupDraft.load(dir)
    // A status used by moves and formulas: effects are removed, formula references are reported.
    val left = draft.delete(EntryKind.STATUS, "burn", removeReferences = true)
    Check.ok(left.isNotEmpty() && left.all { it.inFormula }, "only formula references are left: $left")
    Check.ok(!draft.contains(EntryKind.STATUS, "burn"), "the status is gone")
    Check.ok(draft.references(EntryKind.STATUS, "burn").all { it.inFormula }, "effects using it were removed")
    // A move used by species and teams.
    val team = Json.parse(File(dir, "Teams/Starter Trio.json").readText()) as Map<*, *>
    val move = (((team["members"] as List<*>)[0] as Map<*, *>)["moves"] as List<*>)[0] as String
    Check.eq(emptyList<Any>(), draft.delete(EntryKind.MOVE, move, removeReferences = true), "nothing left after deleting a move")
    // A type: chart rows and cells, species types, move types, immunities.
    draft.delete(EntryKind.TYPE, "ghost", removeReferences = true)
    Check.ok(draft.typeChart().values.none { "ghost" in it } && "ghost" !in draft.typeChart(), "the chart forgets the type")
    // A bakemon used by a team: the team member goes.
    val species = ((team["members"] as List<*>)[1] as Map<*, *>)["species"] as String
    draft.delete(EntryKind.BAKEMON, species, removeReferences = true)
    // An item and an ability used by teams.
    draft.delete(EntryKind.ITEM, draft.ids(EntryKind.ITEM).first(), removeReferences = true)
    val result = draft.save()
    Check.ok(result.validation.ok, "the setup is still valid after deleting with references: ${result.validation.errors.take(5)}")
    Check.ok(!allText(dir).contains("\"$move\""), "the deleted move is gone everywhere")
    teamsValid(dir, "delete")
    // Without removing references, they are reported and stay.
    val other = SetupDraft.load(dir)
    val status = other.ids(EntryKind.STATUS).first { other.references(EntryKind.STATUS, it).any { r -> !r.inFormula } }
    val kept = other.delete(EntryKind.STATUS, status, removeReferences = false)
    Check.ok(kept.isNotEmpty(), "references are reported when they are not removed")
    Check.ok(!other.validate().ok, "and the setup is invalid until they are fixed")
}

// ---------------------------------------------------------------------------------------------------------------
// End to end

@Suppress("UNCHECKED_CAST")
private fun testEndToEnd(temp: File) {
    for ((style, battles) in listOf(TurnStyle.SPEED_ORDER to 50, TurnStyle.ACTION_BAR to 30)) {
        val label = style.id
        val answers = RuleAnswers.forStyle(style).copy(name = "E2E $label", teamMax = 3, active = if (style == TurnStyle.ACTION_BAR) 2 else 1)
        val draft = SetupDraft.create(temp, answers.name, answers)
        // Types and chart.
        for ((name, color) in listOf("Fire" to "red", "Water" to "blue", "Grass" to "green")) {
            draft.add(EntryKind.TYPE, TypeForms.build(TypeForm(draft.idFromName(EntryKind.TYPE, name), name, color)))
        }
        draft.setEffectiveness("fire", "grass", 2.0)
        draft.setEffectiveness("grass", "water", 2.0)
        draft.setEffectiveness("water", "fire", 2.0)
        draft.setEffectiveness("fire", "water", 0.5)
        draft.setTypeMessage("strong", "Super effective!")
        // A status: damage over time plus an attack drop.
        val burnCtx = draft.recipeContext(OwnerKind.STATUS, entryId = "burn")
        val burn = StatusForms.build(StatusForm("burn", "Burn", kind = "major", immuneTypes = listOf("fire"), applyMessage = "{target} was burned!"), burnCtx)
        Presets.apply(burn, Presets.statuses.first { it.id == "dot" }.let { r -> Presets.fragment(r, mapOf("percent" to 6.25), burnCtx) }, StatusForms.ORDER)
        Presets.apply(burn, Presets.statuses.first { it.id == "stat_change" }.let { r -> Presets.fragment(r, mapOf("stat" to "atk", "percent" to -50.0), burnCtx) }, StatusForms.ORDER)
        draft.add(EntryKind.STATUS, burn)
        // A field.
        val fieldCtx = draft.recipeContext(OwnerKind.FIELD, entryId = "rain")
        val rain = FieldForms.build(FieldForm("rain", "Rain", group = "weather", duration = 5.0, selectable = true), fieldCtx)
        Presets.apply(rain, Presets.fields.first { it.id == "weather" }.let { r -> Presets.fragment(r, mapOf("boost" to "water", "weaken" to "fire"), fieldCtx) })
        draft.add(EntryKind.FIELD, rain)
        // Moves with effects.
        val m = draft.recipeContext(OwnerKind.MOVE)
        fun move(form: MoveForm) = draft.add(EntryKind.MOVE, MoveForms.build(form, m))
        val hit = m.withEvent("hit")
        move(MoveForms.blank(m, "Ember", "ember").copy(type = "fire", triggers = listOf(guided(TriggerSpec("hit",
            conditions = listOf(RecipeUse("dealt_damage", emptyMap())),
            effects = listOf(RecipeUse("apply_status", mapOf("status" to "burn", "chance" to 30.0))))))))
        move(MoveForms.blank(m, "Bubble", "bubble").copy(type = "water"))
        move(MoveForms.blank(m, "Leaf Drain", "leaf_drain").copy(type = "grass", triggers = listOf(guided(TriggerSpec("hit",
            effects = listOf(RecipeUse("drain", EffectRecipes.byId("drain")!!.defaults(hit))))))))
        move(MoveForms.blank(m, "Recover", "recover").copy(category = m.categories.first { it.value !in m.damagingCategories }.value,
            power = RecipeUse("none", emptyMap()), target = "self", accuracy = null,
            triggers = listOf(guided(TriggerSpec("hit", effects = listOf(RecipeUse("heal_max_hp", mapOf("percent" to 50.0, "target" to "user"))))))))
        move(MoveForms.blank(m, "Rain Dance", "rain_dance").copy(category = m.categories.first { it.value !in m.damagingCategories }.value,
            power = RecipeUse("none", emptyMap()), target = "self", accuracy = null,
            triggers = listOf(guided(TriggerSpec("hit", effects = listOf(RecipeUse("set_field", mapOf("field" to "rain"))))))))
        move(MoveForms.blank(m, "Sonic Strike", "sonic_strike").copy(power = RecipeUse("fixed_damage", mapOf("level" to false, "amount" to 20.0))))
        // An ability and an item.
        val a = draft.recipeContext(OwnerKind.ABILITY)
        val blaze = HolderForms.build(HolderForm("blaze", "Blaze"), a)
        Presets.apply(blaze, Presets.holders.first { it.id == "type_boost" }.let { r -> Presets.fragment(r, mapOf("type" to "fire", "percent" to 50.0), a) }, creator.HolderForms.ORDER)
        draft.add(EntryKind.ABILITY, blaze)
        val i = draft.recipeContext(OwnerKind.ITEM)
        val berry = HolderForms.build(HolderForm("berry", "Berry", triggers = listOf(guided(TriggerSpec("turnEnd",
            conditions = listOf(RecipeUse("hp_below", mapOf("who" to "self", "percent" to 50.0))), limit = 1,
            effects = listOf(RecipeUse("heal_max_hp", mapOf("percent" to 25.0)), RecipeUse("consume_item", emptyMap())))))), i)
        draft.add(EntryKind.ITEM, berry)
        // Three bakemons.
        for ((name, type) in listOf("Cinder" to "fire", "Splash" to "water", "Sprout" to "grass")) {
            val blank = BakemonForms.blank(draft, name, 60.0)
            val moves = BakemonForms.movesOfTypes(draft, listOf(type)) + listOf("recover", "rain_dance", "sonic_strike")
            draft.add(EntryKind.BAKEMON, BakemonForms.build(blank.copy(types = listOf(type), abilities = listOf("blaze"), moves = moves,
                baseStats = blank.baseStats.mapValues { (k, v) -> if (k == "crate") 15.0 else if (k == "cdmg") 50.0 else v * 1.5 })))
        }
        val result = draft.save()
        Check.ok(result.validation.ok, "$label: the API-built setup is valid: ${result.validation.errors}")
        Check.ok(result.validation.warnings.all { it.contains("image") }, "$label: no warnings but missing images: ${result.validation.warnings}")
        Check.ok(draft.checklist().all { it.done }, "$label: checklist complete")
        val setup = result.validation.setup ?: return
        var crashes = 0
        var finished = 0
        repeat(battles) { n ->
            try {
                val rnd = Random(500L + n)
                val teams = listOf(setup.randomTeam(rnd, name = "A"), setup.randomTeam(rnd, name = "B"))
                val res = Manager(setup, teams, listOf(AiController("A", AiLevel.NORMAL), AiController("B", AiLevel.HARD)), seed = 500L + n).run()
                if (res.turns > 0) finished++
            } catch (e: Throwable) {
                crashes++
                if (crashes == 1) println("  battle crashed: ${e.stackTraceToString().take(800)}")
            }
        }
        Check.eq(0, crashes, "$label: $battles AI battles without exceptions")
        Check.eq(battles, finished, "$label: every battle ran")
        // A guided move read back from the saved file.
        val ember = MoveForms.read(SetupDraft.load(draft.dir).entry(EntryKind.MOVE, "ember")!!, m)
        Check.ok(ember.triggers.single() is TriggerForm.Guided, "$label: ember's trigger is shown as a form")
        Check.eq(StatMods.multiplier(-50.0), 0.5, "stat modifier helper")
    }
}
