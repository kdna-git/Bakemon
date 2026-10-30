import engine.ExprContext
import engine.ExpressionException
import engine.Formula
import engine.MapContext
import engine.formula
import engine.formulaOrNull
import util.Json
import util.JsonArr
import util.JsonObj
import util.JsonParseException
import util.SetupException
import java.io.File
import kotlin.random.Random

/** Tests for util.Json and engine.Formula. */
fun runJsonExpressionTests() {
    Check.guard("json: parse values") { jsonParseValues() }
    Check.guard("json: comments and trailing commas") { jsonCommentsAndCommas() }
    Check.guard("json: string escapes") { jsonEscapes() }
    Check.guard("json: errors") { jsonErrors() }
    Check.guard("json: stringify") { jsonStringify() }
    Check.guard("json: files") { jsonFiles() }
    Check.guard("json: JsonObj") { jsonObjAccessors() }
    Check.guard("json: JsonArr") { jsonArrAccessors() }
    Check.guard("formula: precedence") { formulaPrecedence() }
    Check.guard("formula: booleans and logic") { formulaLogic() }
    Check.guard("formula: strings and equality") { formulaStrings() }
    Check.guard("formula: short-circuit") { formulaShortCircuit() }
    Check.guard("formula: built-ins") { formulaBuiltins() }
    Check.guard("formula: randomness") { formulaRandomness() }
    Check.guard("formula: estimate mode") { formulaEstimate() }
    Check.guard("formula: context functions") { formulaContextFunctions() }
    Check.guard("formula: parse errors") { formulaParseErrors() }
    Check.guard("formula: evaluation errors") { formulaEvalErrors() }
    Check.guard("formula: introspection") { formulaIntrospection() }
    Check.guard("formula: MapContext and setup formulas") { formulaMapContext() }
    Check.guard("formula: JsonObj.formula") { formulaFromJson() }
}

// ---------------------------------------------------------------------------------------------------------------------
// Helpers

/** The message of the [T] thrown by [block], or a description of what happened instead. */
private inline fun <reified T : Throwable> thrownMessage(block: () -> Unit): String {
    try {
        block()
    } catch (t: Throwable) {
        return if (t is T) t.message ?: "<no message>" else "<${t::class.simpleName}: ${t.message}>"
    }
    return "<nothing thrown>"
}

private inline fun setupErr(block: () -> Unit): String = thrownMessage<SetupException>(block)

private fun ev(src: String, vars: Map<String, Any?> = emptyMap()): Double = Formula.parse(src).eval(MapContext(vars))

private fun evAny(src: String, vars: Map<String, Any?> = emptyMap()): Any? = Formula.parse(src).evalAny(MapContext(vars))

private fun parseErr(src: String): String = thrownMessage<ExpressionException> { Formula.parse(src) }

private fun evalErr(src: String, vars: Map<String, Any?> = emptyMap()): String =
    thrownMessage<ExpressionException> { Formula.parse(src).eval(MapContext(vars)) }

private fun jsonErr(text: String, source: String = "t.json"): String = thrownMessage<JsonParseException> { Json.parse(text, source) }

// ---------------------------------------------------------------------------------------------------------------------
// JSON

private fun jsonParseValues() {
    Check.section("json: parse values")
    val v = Json.parse(
        """{"a": 1, "b": [true, false, null], "c": {"d": "x"}, "e": -2.5e2, "f": 0, "g": 1E3, "h": 0.125, "i": -0}"""
    )
    Check.ok(v is LinkedHashMap<*, *>, "objects are LinkedHashMaps")
    val m = v as Map<*, *>
    Check.eq(listOf("a", "b", "c", "e", "f", "g", "h", "i"), m.keys.toList(), "key order is kept")
    Check.eq(1.0, m["a"], "integers become doubles")
    Check.ok(m["a"] is Double, "number type is Double")
    Check.eq(listOf(true, false, null), m["b"], "literals")
    Check.eq(mapOf("d" to "x"), m["c"], "nested object")
    Check.eq(-250.0, m["e"], "negative with exponent")
    Check.eq(0.0, m["f"], "zero")
    Check.eq(1000.0, m["g"], "capital exponent")
    Check.eq(0.125, m["h"], "fraction")
    Check.near(0.0, m["i"] as Double, "-0")
    Check.eq(42.0, Json.parse("42"), "top-level number")
    Check.eq("s", Json.parse("\"s\""), "top-level string")
    Check.eq(null, Json.parse("null"), "top-level null")
    Check.eq(true, Json.parse(" true "), "top-level true")
    Check.eq(emptyList<Any?>(), Json.parse(" [ ] "), "empty array")
    Check.eq(emptyMap<String, Any?>(), Json.parse("{\n}"), "empty object")
    Check.eq(listOf(listOf(1.0, listOf(2.0)), emptyList<Any?>()), Json.parse("[[1,[2]],[]]"), "nested arrays")
    Check.eq(mapOf("a" to listOf(1.0, 2.0)), Json.parse("{\r\n\t\"a\" :\t[ 1 ,2 ]\r\n}"), "tabs and CRLF")
    Check.eq(1.5e-7, Json.parse("1.5e-7"), "negative exponent")
    Check.eq(12.0, Json.parse("1.2E+1"), "explicit + exponent")
    Check.eq(mapOf("a" to mapOf("a" to 1.0)), Json.parse("{\"a\": {\"a\": 1}}"), "same key in different objects")
    Check.eq("", Json.parse("\"\""), "empty string")
    Check.eq(mapOf("a" to 1.0), Json.parse("\uFEFF{\"a\": 1}"), "BOM is ignored")
}

private fun jsonCommentsAndCommas() {
    Check.section("json: comments and trailing commas")
    val text = "// header\n{ /* block\n comment */ \"a\": 1, // trailing\n \"b\": [1, 2, /* in array */ 3,],\n}\n// end"
    Check.eq(mapOf("a" to 1.0, "b" to listOf(1.0, 2.0, 3.0)), Json.parse(text), "comments and trailing commas")
    Check.eq(listOf(1.0), Json.parse("[1,]"), "trailing comma in array")
    Check.eq(mapOf("a" to 1.0), Json.parse("{\"a\":1,}"), "trailing comma in object")
    Check.eq(listOf(1.0), Json.parse("[1,\n// comment before bracket\n]"), "comment after trailing comma")
    Check.eq(1.0, Json.parse("/**/1/***/"), "empty block comments")
    Check.eq(1.0, Json.parse("1 // no newline at the end"), "line comment at end of input")
    Check.eq("http://x /* y */", Json.parse("\"http://x /* y */\""), "comment markers inside strings are text")
    Check.ok(jsonErr("[1 /* open").contains("unterminated comment"), "unterminated block comment")
    Check.ok(jsonErr("[1 / 2]").startsWith("t.json:1:4:"), "a single slash is an error")
    Check.ok(jsonErr("[1,,2]").contains("unexpected ','"), "double comma in array")
    Check.ok(jsonErr("{\"a\":1,,}").contains("expected a string key but found ','"), "double comma in object")
    Check.ok(jsonErr("[,]").contains("unexpected ','"), "lone comma in array")
    Check.ok(jsonErr("{,}").contains("expected a string key but found ','"), "lone comma in object")
    Check.eq("t.json:1:23: unexpected end of input (expected a value)", jsonErr("// only a comment here"), "comment only")
}

private fun jsonEscapes() {
    Check.section("json: string escapes")
    Check.eq("a\"b\\c/d\b\u000C\n\r\t", Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""), "simple escapes")
    Check.eq("\u00e9A\u20AC", Json.parse("\"\\u00e9\\u0041\\u20ac\""), "\\u escapes (any hex case)")
    val emoji = Json.parse("\"\\uD83D\\uDE00\"") as String
    Check.eq(2, emoji.length, "surrogate pair is two UTF-16 units")
    Check.eq(0x1F600, emoji.codePointAt(0), "surrogate pair forms one code point")
    Check.eq("x\u00e9y", Json.parse("\"x\u00e9y\""), "raw non-ASCII characters")
    Check.eq("t.json:1:3: invalid \\u escape (expected 4 hex digits)", jsonErr("\"a\\u12G4\""), "bad hex digit")
    Check.eq("t.json:1:2: invalid \\u escape (expected 4 hex digits)", jsonErr("\"\\u12\""), "short \\u escape")
    Check.eq("t.json:1:3: invalid escape '\\x' in string", jsonErr("\"a\\x\""), "unknown escape")
    Check.eq("t.json:1:4: line break inside a string (missing closing quote?)", jsonErr("\"ab\ncd\""), "raw newline")
    Check.ok(jsonErr("\"a\u0001\"").contains("unescaped control character"), "raw control character")
    Check.eq("t.json:1:2: unterminated string", jsonErr("[\"abc"), "unterminated string")
    Check.eq("t.json:1:1: unterminated string", jsonErr("\"abc\\"), "backslash at the end")
}

private fun jsonErrors() {
    Check.section("json: errors")
    Check.eq(
        "test.json:3:3: expected ',' or '}' but found '\"'",
        jsonErr("{\n  \"a\": 1\n  \"b\": 2\n}", "test.json"),
        "missing comma: line and column",
    )
    Check.eq("dup.json:1:10: duplicate key 'a'", jsonErr("{\"a\": 1, \"a\": 2}", "dup.json"), "duplicate key")
    Check.eq("t.json:3:1: duplicate key 'a'", jsonErr("{\r\n\"a\":1,\r\n\"a\":2}"), "duplicate key after CRLF")
    Check.eq(
        "f:1:6: unexpected end of input (the array that starts at line 1, column 1 is not closed)",
        jsonErr("[1, 2", "f"),
        "unclosed array",
    )
    Check.eq(
        "f:2:10: unexpected end of input (the object that starts at line 2, column 3 is not closed)",
        jsonErr("[\n  {\"a\": 1", "f"),
        "unclosed object",
    )
    Check.eq("t.json:1:4: unexpected 'x' after the end of the JSON value", jsonErr("{} x"), "trailing garbage")
    Check.ok(jsonErr("[1] ]").contains("after the end of the JSON value"), "extra bracket")
    Check.ok(jsonErr("1 2").contains("after the end of the JSON value"), "two values")
    Check.eq("e:1:1: unexpected end of input (expected a value)", jsonErr("", "e"), "empty input")
    Check.eq("t.json:1:4: unexpected end of input (expected a value)", jsonErr("   "), "blank input")
    Check.ok(jsonErr("01").contains("leading zeros"), "leading zero")
    Check.ok(jsonErr("-").contains("invalid number"), "lone minus")
    Check.ok(jsonErr("1.").contains("invalid number"), "dot without digits")
    Check.ok(jsonErr(".5").startsWith("t.json:1:1: unexpected '.'"), "leading dot")
    Check.ok(jsonErr("+1").startsWith("t.json:1:1: unexpected '+'"), "leading plus")
    Check.ok(jsonErr("1e").contains("invalid number"), "empty exponent")
    Check.ok(jsonErr("0x10").contains("unexpected 'x'"), "hex numbers")
    Check.eq("t.json:1:1: number out of range", jsonErr("1e400"), "huge number")
    Check.eq("t.json:1:1: strings must use double quotes", jsonErr("'abc'"), "single quotes")
    Check.eq("t.json:1:2: object keys must be strings in double quotes", jsonErr("{a: 1}"), "bare key")
    Check.eq("t.json:1:2: object keys must use double quotes", jsonErr("{'a': 1}"), "single-quoted key")
    Check.eq("t.json:1:6: expected ':' after key 'a' but found '1'", jsonErr("{\"a\" 1}"), "missing colon")
    Check.eq("t.json:1:4: expected ',' or ']' but found '2'", jsonErr("[1 2]"), "missing comma in array")
    Check.eq("t.json:1:2: 'NaN' is not valid JSON", jsonErr("[NaN]"), "NaN")
    Check.eq("t.json:1:1: 'Infinity' is not valid JSON", jsonErr("Infinity"), "Infinity")
    Check.eq("t.json:1:1: unexpected 'undefined' (strings must be in double quotes)", jsonErr("undefined"), "bare word")
    Check.ok(jsonErr("trueish").contains("unexpected 'trueish'"), "literal prefix of a word")
    Check.eq("t.json:1:3: expected ',' or ']' but found '}'", jsonErr("[1}"), "mismatched bracket")
    Check.eq("t.json:2:2: unexpected '#' (expected a value)", jsonErr("[\n\t#]"), "tab counts as one column")
    Check.ok(jsonErr("[\uFEFF1]").contains("character U+FEFF"), "BOM in the middle is an error")
    Check.ok(jsonErr("[".repeat(600) + "]".repeat(600)).contains("nesting is too deep"), "deep nesting")
    Check.throws<JsonParseException>("errors are JsonParseException") { Json.parse("{") }
}

private fun jsonStringify() {
    Check.section("json: stringify")
    val sample = linkedMapOf(
        "a" to 1.0, "b" to listOf(1.5, "x", true, null), "c" to emptyMap<String, Any?>(), "d" to emptyList<Any?>(),
    )
    Check.eq("{\"a\":1,\"b\":[1.5,\"x\",true,null],\"c\":{},\"d\":[]}", Json.stringify(sample, pretty = false), "compact")

    val team = linkedMapOf(
        "name" to "My team",
        "members" to listOf(
            linkedMapOf(
                "species" to "embercub",
                "level" to 50.0,
                "moves" to listOf("ember", "tackle"),
                "points" to linkedMapOf("atk" to 252.0, "spe" to 252.0),
            )
        ),
        "empty" to emptyList<Any?>(),
        "obj" to emptyMap<String, Any?>(),
    )
    val expected = """
        {
          "name": "My team",
          "members": [
            {
              "species": "embercub",
              "level": 50,
              "moves": ["ember", "tackle"],
              "points": {
                "atk": 252,
                "spe": 252
              }
            }
          ],
          "empty": [],
          "obj": {}
        }
    """.trimIndent()
    Check.eq(expected, Json.stringify(team), "pretty (2 spaces, short scalar arrays inline)")
    Check.eq("[\n  [1],\n  [2]\n]", Json.stringify(listOf(listOf(1.0), listOf(2.0))), "arrays of arrays are not inline")
    Check.eq("[\n  {\n    \"a\": 1\n  }\n]", Json.stringify(listOf(mapOf("a" to 1))), "arrays of objects are not inline")
    val long = (1..30).map { "element_$it" }
    val longText = Json.stringify(mapOf("list" to long))
    Check.ok(longText.contains("\n    \"element_1\",\n"), "long scalar arrays get one element per line")
    Check.eq(long, (Json.parse(longText) as Map<*, *>)["list"], "long array round trip")

    Check.eq("50", Json.stringify(50.0), "whole double")
    Check.eq("-3", Json.stringify(-3.0), "negative whole double")
    Check.eq("0", Json.stringify(0.0), "zero")
    Check.eq("0.5", Json.stringify(0.5), "fraction")
    Check.eq("0.1", Json.stringify(0.1), "shortest representation")
    Check.eq("1E-7", Json.stringify(1e-7), "small exponent")
    Check.eq("1E20", Json.stringify(1e20), "large whole number")
    Check.eq("123456789012", Json.stringify(123456789012.0), "big whole number without exponent")
    Check.eq("7", Json.stringify(7), "Int")
    Check.eq("9000000000", Json.stringify(9000000000L), "Long")
    Check.eq("0.5", Json.stringify(0.5f), "Float")
    Check.eq("[1,2]", Json.stringify(arrayOf(1, 2), pretty = false), "Array")
    Check.eq("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001\u00e9/\"", Json.stringify("a\"b\\c\n\r\t\b\u000C\u0001\u00e9/"), "escaping")
    Check.eq("{\"k\\\"\":null}", Json.stringify(mapOf("k\"" to null), pretty = false), "escaped key")
    Check.eq("2", Json.formatNumber(2.0), "formatNumber")
    Check.eq("2.25", Json.formatNumber(2.25), "formatNumber fraction")
    Check.throws<IllegalArgumentException>("NaN") { Json.stringify(Double.NaN) }
    Check.throws<IllegalArgumentException>("Infinity") { Json.stringify(listOf(Double.POSITIVE_INFINITY)) }
    Check.throws<IllegalArgumentException>("unsupported type") { Json.stringify(mapOf("x" to Any())) }

    val complex = Json.parse(
        """
        {"name": "Fire \"cub\"\n\u00e9\uD83D\uDE00", "nums": [0, -1, 0.1, 1e-7, -0.5, 1e21, 123.456, 2.5e-300],
         "nested": {"a": [[], {}, [{"b": null}]], "t": true, "f": false}, "tab": "a\tb", "ctl": "\u0002"}
        """
    )
    Check.eq(complex, Json.parse(Json.stringify(complex)), "pretty round trip")
    Check.eq(complex, Json.parse(Json.stringify(complex, pretty = false)), "compact round trip")
    Check.eq(Json.stringify(complex), Json.stringify(Json.parse(Json.stringify(complex))), "stringify is stable")
    Check.ok(!Json.stringify(team, pretty = false).contains(": "), "compact has no spaces after colons")
}

private fun jsonFiles() {
    Check.section("json: files")
    val dir = kotlin.io.path.createTempDirectory("bakemon-json-test").toFile()
    try {
        val file = File(dir, "sub/folder/team.json")
        val value = linkedMapOf("name" to "T\u00e9am", "members" to listOf(linkedMapOf("level" to 50.0)))
        Json.writeFile(file, value)
        Check.ok(file.isFile, "writeFile creates parent folders")
        val text = file.readText(Charsets.UTF_8)
        Check.ok(text.endsWith("}\n"), "trailing newline")
        Check.ok(text.contains("\"level\": 50"), "pretty and whole numbers")
        Check.eq(value, Json.parseFile(file), "parseFile reads back")

        val bom = File(dir, "bom.json")
        bom.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{\"a\": 1}".toByteArray())
        Check.eq(mapOf("a" to 1.0), Json.parseFile(bom), "UTF-8 BOM is ignored")

        val bad = File(dir, "bad.json")
        bad.writeText("{\n  \"a\": }")
        Check.eq(
            "bad.json:2:8: unexpected '}' (expected a value)",
            thrownMessage<JsonParseException> { Json.parseFile(bad) },
            "file name in errors",
        )
    } finally {
        dir.deleteRecursively()
    }
}

private fun jsonObjAccessors() {
    Check.section("json: JsonObj")
    val p = "Moves.json: moves[0]"
    val o = JsonObj.of(
        Json.parse(
            """{"s": "x", "n": 3, "f": 2.5, "b": true, "nul": null, "o": {"k": 1}, "a": [1, 2], "ss": ["p", "q"],
               "bad": [1, "x"], "big": 1e12}"""
        ),
        p,
    )
    Check.eq(p, o.path, "path")
    Check.eq(listOf("s", "n", "f", "b", "nul", "o", "a", "ss", "bad", "big"), o.keys.toList(), "keys")
    Check.ok(o.has("s") && !o.has("nul") && !o.has("zz"), "has: present and not null")
    Check.ok(o.isNull("nul") && o.isNull("zz") && !o.isNull("s"), "isNull")
    Check.eq(3.0, o.raw("n"), "raw")
    Check.eq(null, o.raw("zz"), "raw missing")

    Check.eq("x", o.string("s"), "string")
    Check.eq("d", o.string("zz", "d"), "string default when missing")
    Check.eq("d", o.string("nul", "d"), "string default when null")
    Check.eq(null, o.stringOrNull("zz"), "stringOrNull")
    Check.eq("x", o.stringOrNull("s"), "stringOrNull present")
    Check.eq("$p: missing required field 'zz'", setupErr { o.string("zz") }, "missing field")
    Check.eq("$p.n: expected a string but found a number", setupErr { o.string("n") }, "wrong type")
    Check.eq("$p.nul: expected a string but found null", setupErr { o.string("nul") }, "explicit null")
    Check.eq("$p.n: expected a string but found a number", setupErr { o.string("n", "d") }, "default does not hide wrong types")
    Check.eq("$p.n: expected a string but found a number", setupErr { o.stringOrNull("n") }, "OrNull does not hide wrong types")

    Check.eq(3.0, o.double("n"), "double")
    Check.eq(2.5, o.double("f"), "double fraction")
    Check.eq(1.5, o.double("zz", 1.5), "double default")
    Check.eq(null, o.doubleOrNull("nul"), "doubleOrNull null")
    Check.eq("$p.s: expected a number but found a string", setupErr { o.double("s") }, "double wrong type")
    Check.eq("$p.b: expected a number but found a boolean", setupErr { o.doubleOrNull("b") }, "doubleOrNull wrong type")
    Check.eq("$p: missing required field 'power'", setupErr { o.double("power") }, "double missing")

    Check.eq(3, o.int("n"), "int")
    Check.eq(7, o.int("zz", 7), "int default")
    Check.eq(null, o.intOrNull("zz"), "intOrNull missing")
    Check.eq(3, o.intOrNull("n"), "intOrNull present")
    Check.eq(4, JsonObj.of(mapOf("x" to 4.0), "p").int("x"), "int from 4.0")
    Check.eq(-2, JsonObj.of(mapOf("x" to -2), "p").int("x"), "int from a Kotlin Int")
    Check.eq("$p.f: expected a whole number but found 2.5", setupErr { o.int("f") }, "int rejects fractions")
    Check.eq("$p.f: expected a whole number but found 2.5", setupErr { o.int("f", 1) }, "int default rejects fractions")
    Check.eq("$p.big: the number 1000000000000 is too large", setupErr { o.int("big") }, "int range")
    Check.eq("$p.s: expected a number but found a string", setupErr { o.int("s") }, "int wrong type")

    Check.eq(true, o.bool("b", false), "bool")
    Check.eq(true, o.bool("zz", true), "bool default")
    Check.eq(false, o.bool("nul", false), "bool null -> default")
    Check.eq("$p.n: expected true or false but found a number", setupErr { o.bool("n", false) }, "bool wrong type")

    val child = o.obj("o")
    Check.eq("$p.o", child.path, "child object path")
    Check.eq(1, child.int("k"), "child value")
    Check.eq("$p.o: missing required field 'z'", setupErr { child.int("z") }, "child error path")
    Check.eq("$p.s: expected an object but found a string", setupErr { o.obj("s") }, "obj wrong type")
    Check.eq("$p: missing required field 'zz'", setupErr { o.obj("zz") }, "obj missing")
    Check.eq(null, o.objOrNull("zz"), "objOrNull missing")
    Check.eq(null, o.objOrNull("nul"), "objOrNull null")
    Check.eq("$p.o", o.objOrNull("o")?.path, "objOrNull present")

    Check.eq(2, o.arr("a").size, "arr")
    Check.eq("$p.a", o.arr("a").path, "arr path")
    Check.eq("$p.s: expected an array but found a string", setupErr { o.arr("s") }, "arr wrong type")
    Check.eq("$p: missing required field 'zz'", setupErr { o.arr("zz") }, "arr missing")
    Check.eq(0, o.arrOrEmpty("zz").size, "arrOrEmpty missing")
    Check.eq(0, o.arrOrEmpty("nul").size, "arrOrEmpty null")
    Check.eq("$p.zz", o.arrOrEmpty("zz").path, "arrOrEmpty path")
    Check.eq("$p.o: expected an array but found an object", setupErr { o.arrOrEmpty("o") }, "arrOrEmpty wrong type")

    Check.eq(listOf("p", "q"), o.strings("ss"), "strings")
    Check.eq(emptyList<String>(), o.strings("zz"), "strings missing")
    Check.eq("$p.bad[0]: expected a string but found a number", setupErr { o.strings("bad") }, "strings element type")
    Check.eq("$p.s: expected an array but found a string", setupErr { o.strings("s") }, "strings wrong type")

    Check.eq("$p.x", o.pathOf("x"), "pathOf")
    Check.eq("x", JsonObj(emptyMap(), "").pathOf("x"), "pathOf with empty path")
    Check.eq("$p: boom", setupErr { o.fail("boom") }, "fail")
    Check.eq(listOf("b", "nul", "big"), o.unknownKeys(setOf("s", "n", "f", "o", "a", "ss", "bad")), "unknownKeys")
    Check.eq(emptyList<String>(), o.unknownKeys(o.keys), "no unknown keys")

    Check.eq("p: expected an object but found a string", setupErr { JsonObj.of("str", "p") }, "of: string")
    Check.eq("p: expected an object but found an array", setupErr { JsonObj.of(listOf(1), "p") }, "of: array")
    Check.eq("p: expected an object but found null", setupErr { JsonObj.of(null, "p") }, "of: null")
}

private fun jsonArrAccessors() {
    Check.section("json: JsonArr")
    val a = JsonArr.of(Json.parse("[{\"power\": \"x\"}, {\"power\": 40}]"), "Moves.json: moves")
    Check.eq(2, a.size, "size")
    val objs = a.objects()
    Check.eq("Moves.json: moves[1]", objs[1].path, "element path")
    Check.eq(40, objs[1].int("power"), "element value")
    Check.eq(
        "Moves.json: moves[0].power: expected a number but found a string",
        setupErr { objs[0].double("power") },
        "nested error path",
    )
    Check.eq(listOf("a", "b"), JsonArr.of(listOf("a", "b"), "p").strings(), "strings")
    Check.eq(
        "p[1]: expected an object but found a number",
        setupErr { JsonArr.of(listOf(mapOf<String, Any?>(), 1.0), "p").objects() },
        "objects element type",
    )
    Check.eq("p[0]: expected a string but found a boolean", setupErr { JsonArr.of(listOf(true), "p").strings() }, "strings element type")
    Check.eq("p: expected an array but found an object", setupErr { JsonArr.of(mapOf("a" to 1), "p") }, "of: object")
    Check.eq("p: expected an array but found a number", setupErr { JsonArr.of(1.0, "p") }, "of: number")
    Check.eq("p: too many", setupErr { JsonArr.of(emptyList<Any?>(), "p").fail("too many") }, "fail")
}

// ---------------------------------------------------------------------------------------------------------------------
// Formulas

private fun formulaPrecedence() {
    Check.section("formula: precedence")
    Check.eq(7.0, ev("1 + 2 * 3"), "* before +")
    Check.eq(9.0, ev("(1 + 2) * 3"), "parentheses")
    Check.eq(3.0, ev("10 - 4 - 3"), "- is left-associative")
    Check.eq(2.0, ev("100 / 10 / 5"), "/ is left-associative")
    Check.eq(4.0, ev("8 % 3 * 2"), "% and * are left-associative")
    Check.eq(512.0, ev("2 ^ 3 ^ 2"), "^ is right-associative")
    Check.eq(-4.0, ev("-2 ^ 2"), "^ binds tighter than unary minus on its left")
    Check.eq(4.0, ev("(-2) ^ 2"), "parenthesized negative base")
    Check.eq(0.5, ev("2 ^ -1"), "unary minus in the exponent")
    Check.eq(-0.25, ev("-2 ^ -2"), "unary minus on both sides")
    Check.eq(18.0, ev("2 * 3 ^ 2"), "^ before *")
    Check.eq(9.0, ev("-3 * -3"), "unary minus operands")
    Check.eq(3.0, ev("--3"), "double unary minus")
    Check.eq(4.0, ev("2--2"), "binary then unary minus")
    Check.eq(3.0, ev("+3"), "unary plus")
    Check.eq(1.0, ev("!1 + 1"), "! binds tighter than +")
    Check.eq(1.0, ev("1 + 2 < 4"), "arithmetic before comparison")
    Check.eq(1.0, ev("1 + 1 == 2"), "arithmetic before equality")
    Check.eq(1.0, ev("1 < 2 == 1"), "comparison before equality")
    Check.eq(1.0, ev("2 > 1 > 0"), "comparisons chain left to right")
    Check.eq(0.0, ev("3 > 2 > 1"), "comparisons chain left to right (2)")
    Check.eq(1.0, ev("1 < 2 && 2 < 3"), "comparison before &&")
    Check.eq(1.0, ev("1 || 0 && 0"), "&& before ||")
    Check.eq(1.0, ev("0 && 1 || 1"), "&& before || (2)")
    Check.eq(2.0, ev("1 ? 2 : 3 + 10"), "?: has the lowest precedence")
    Check.eq(13.0, ev("0 ? 2 : 3 + 10"), "?: has the lowest precedence (2)")
    Check.eq(3.0, ev("(1 ? 2 : 3) + 1"), "parenthesized conditional")
    Check.eq(2.0, ev("1 ? 2 : 3 ? 4 : 5"), "nested conditional in else")
    Check.eq(4.0, ev("0 ? 2 : 3 ? 4 : 5"), "nested conditional in else (2)")
    Check.eq(5.0, ev("0 ? 2 : 0 ? 4 : 5"), "nested conditional in else (3)")
    Check.eq(7.0, ev("1 ? 0 ? 6 : 7 : 8"), "nested conditional in then")
    Check.eq(8.0, ev("0 ? 0 ? 6 : 7 : 8"), "nested conditional in then (2)")
    Check.eq(1000.5, ev("1e3 + .5"), "exponent and leading-dot numbers")
    Check.eq(3.0, ev(" 1+\t2 "), "whitespace")
    Check.near(1.0 / 24, ev("1 / 24"), "constant division")
    Check.eq(1.0, ev("((((((1))))))"), "nested parentheses")
    Check.eq(-1.0, ev("-7 % 3"), "% keeps the sign of the dividend")
    Check.eq(1.5, ev("7.5 % 2"), "% on fractions")
}

private fun formulaLogic() {
    Check.section("formula: booleans and logic")
    Check.eq(2.0, ev("true + true"), "true is 1")
    Check.eq(0.0, ev("false"), "false is 0")
    Check.eq(1.0, ev("!0"), "!0")
    Check.eq(0.0, ev("!5"), "!5")
    Check.eq(1.0, ev("!!3"), "!!3")
    Check.eq(1.0, ev("3 && 4"), "&& gives 1, not the operand")
    Check.eq(1.0, ev("0 || 0.5"), "|| gives 1, not the operand")
    Check.eq(0.0, ev("true && false"), "true && false")
    Check.eq(1.0, ev("-1 && 1"), "negative numbers are truthy")
    Check.eq(1.0, ev("x == 1", mapOf("x" to true)), "Boolean variable is 1")
    Check.eq(0.0, ev("x", mapOf("x" to false)), "Boolean variable false is 0")
    Check.eq(1.0, evAny("x", mapOf("x" to true)), "evalAny normalizes booleans")
    Check.eq(1.0, ev("1 < 2 == true"), "comparison result equals true")
    Check.eq(1.0, ev("1 <= 1") + ev("2 >= 3") + ev("1 != 1"), "<=, >=, !=")
    Check.eq(0.0, ev("5 / 0"), "division by zero is 0")
    Check.eq(0.0, ev("5 % 0"), "modulo by zero is 0")
    Check.eq(0.0, ev("0 / 0"), "0 / 0 is 0")
    Check.eq(0.0, ev("x / y", mapOf("x" to 3, "y" to 0)), "division by a zero variable is 0")
    Check.eq(1.0, ev("1 / 0 == 0"), "division by zero compares as 0")
    Check.ok(Formula.parse("2 > 1").test(MapContext(emptyMap())), "test true")
    Check.ok(!Formula.parse("2 < 1").test(MapContext(emptyMap())), "test false")
    Check.ok(Formula.parse("0.001").test(MapContext(emptyMap())), "test: any non-zero is true")
}

private fun formulaStrings() {
    Check.section("formula: strings and equality")
    val v = mapOf("move.type" to "fire", "s" to "3", "n" to 3, "t" to "fire")
    Check.eq(1.0, ev("move.type == 'fire'", v), "string equality")
    Check.eq(1.0, ev("move.type != 'water'", v), "string inequality")
    Check.eq(1.0, ev("move.type == \"fire\"", v), "double-quoted strings")
    Check.eq(1.0, ev("move.type == t", v), "two string variables")
    Check.eq(0.0, ev("'1' == 1"), "string vs number is unequal")
    Check.eq(0.0, ev("1 == '1'"), "number vs string is unequal")
    Check.eq(1.0, ev("'a' != 1"), "string != number")
    Check.eq(0.0, ev("s == 3", v), "string variable vs number")
    Check.eq(1.0, ev("n == 3", v), "Int variable vs number")
    Check.eq(1.0, ev("'' == ''"), "empty strings")
    Check.eq(0.0, ev("'Fire' == 'fire'"), "case-sensitive")
    Check.eq("it's", evAny("'it\\'s'"), "escaped quote in a string")
    Check.eq("a\"b", evAny("'a\"b'"), "other quote inside a string")
    Check.eq("fire", evAny("move.type", v), "evalAny returns strings")
    Check.eq("big", evAny("n > 1 ? 'big' : 'small'", v), "conditional with string branches")
    Check.eq(1.0, ev("(n > 1 ? 'a' : 'b') == 'a'", v), "comparing a conditional string")
    Check.eq(1.0, ev("if(n > 5, 'a', 'b') == 'b'", v), "comparing an if() string")
    Check.eq(1.0, ev("(n > 1 ? 2 : 'x') == 2", v), "mixed conditional compares as a number")
    Check.eq(3.0, evAny("n", v), "evalAny converts Int to Double")
    Check.ok(evAny("n", v) is Double, "evalAny number type")
}

private fun formulaShortCircuit() {
    Check.section("formula: short-circuit")
    // 'boom' is not a known variable: evaluating it would throw.
    Check.eq(0.0, ev("0 && boom"), "&& skips the right side")
    Check.eq(1.0, ev("1 || boom"), "|| skips the right side")
    Check.eq(2.0, ev("1 ? 2 : boom"), "?: skips the else branch")
    Check.eq(3.0, ev("0 ? boom : 3"), "?: skips the then branch")
    Check.eq(2.0, ev("if(1, 2, boom)"), "if() skips the else branch")
    Check.eq(3.0, ev("if(0, boom, 3)"), "if() skips the then branch")
    Check.eq(1.0, ev("0 ? boom : 1 ? 1 : boom"), "nested short-circuit")
    Check.ok(evalErr("1 && boom").contains("Unknown variable 'boom'"), "&& evaluates the right side when needed")
    Check.ok(evalErr("0 || boom").contains("Unknown variable 'boom'"), "|| evaluates the right side when needed")

    var calls = 0
    val ctx = object : ExprContext {
        override fun variable(name: String): Any? = null
        override fun function(name: String, args: List<Any?>): Any? {
            calls++
            return 1.0
        }
        override val random: Random = Random(1)
    }
    Formula.parse("0 && f() || 1 || f() ? 1 : f()").eval(ctx)
    Check.eq(0, calls, "functions in skipped branches are not called")
    Formula.parse("1 && f()").eval(ctx)
    Check.eq(1, calls, "functions in taken branches are called")
}

private fun formulaBuiltins() {
    Check.section("formula: built-ins")
    Check.eq(3.0, ev("min(3)"), "min of one")
    Check.eq(1.0, ev("min(3, 1, 2)"), "min")
    Check.eq(5.0, ev("max(1, 5, 2)"), "max")
    Check.eq(-2.0, ev("max(-2)"), "max of one")
    Check.eq(8.0, ev("max(1 + 2, min(10, 4 * 2))"), "nested calls")
    Check.eq(2.0, ev("floor(2.7)"), "floor")
    Check.eq(-3.0, ev("floor(-2.5)"), "floor negative")
    Check.eq(3.0, ev("ceil(2.1)"), "ceil")
    Check.eq(-2.0, ev("ceil(-2.5)"), "ceil negative")
    Check.eq(3.0, ev("round(2.5)"), "round half up")
    Check.eq(2.0, ev("round(2.4)"), "round down")
    Check.eq(-2.0, ev("round(-2.5)"), "round negative half up")
    Check.eq(-3.0, ev("round(-2.6)"), "round negative")
    Check.eq(0.0, ev("round(0.49999999999999994)"), "round just below one half")
    Check.eq(1e300, ev("round(1e300)"), "round huge")
    Check.eq(3.0, ev("abs(-3)"), "abs")
    Check.eq(4.0, ev("sqrt(16)"), "sqrt")
    Check.eq(0.0, ev("sqrt(-4)"), "sqrt of a negative number is 0")
    Check.eq(2.0, ev("pow(-8, 0.5) ? 1 : 2"), "NaN counts as false")
    Check.eq(1.0, ev("!pow(-8, 0.5)"), "not NaN is true")
    Check.eq(0.0, ev("pow(-8, 0.5) || 0"), "NaN || 0 is false")
    Check.eq(1024.0, ev("pow(2, 10)"), "pow")
    Check.eq(-8.0, ev("pow(-2, 3)"), "pow negative base")
    Check.eq(3.0, ev("clamp(5, 0, 3)"), "clamp high")
    Check.eq(0.0, ev("clamp(-1, 0, 3)"), "clamp low")
    Check.eq(2.0, ev("clamp(2, 0, 3)"), "clamp inside")
    Check.eq(2.0, ev("if(1, 2, 3)"), "if true")
    Check.eq(3.0, ev("if(0, 2, 3)"), "if false")
    Check.eq("a", evAny("if(1, 'a', 'b')"), "if with strings")
    Check.eq(3.0, ev("min (3, 4)"), "space before the parenthesis")

    // Arity is checked when parsing.
    for (src in listOf("min()", "max()")) Check.ok(parseErr(src).contains("needs at least 1 argument"), "arity: $src")
    val wrong = listOf(
        "floor()", "floor(1, 2)", "ceil(1, 2)", "round()", "abs(1, 2)", "sqrt()", "pow(1)", "pow(1, 2, 3)",
        "clamp(1, 2)", "if(1, 2)", "if(1, 2, 3, 4)", "randint(1)", "chance()", "chance(1, 2)",
    )
    for (src in wrong) Check.ok(parseErr(src).contains("argument"), "arity: $src -> ${parseErr(src)}")
    Check.eq("Formula 'pow(2)': pow() takes 2 arguments but got 1 at position 1", parseErr("pow(2)"), "arity message")
    Check.eq("Formula '1 + floor()': floor() takes 1 argument but got 0 at position 5", parseErr("1 + floor()"), "arity position")
    Check.eq("Formula 'random(1)': random() takes 0 or 2 arguments but got 1 at position 1", parseErr("random(1)"), "random arity")
    Check.ok(parseErr("random(1, 2, 3)").contains("random() takes 0 or 2 arguments"), "random arity (3)")
}

private fun formulaRandomness() {
    Check.section("formula: randomness")
    val ctx = MapContext(emptyMap(), Random(42))
    val r0 = Formula.parse("random()")
    val samples = List(2000) { r0.eval(ctx) }
    Check.ok(samples.all { it >= 0.0 && it < 1.0 }, "random() in [0, 1)")
    Check.ok(samples.average() in 0.45..0.55, "random() mean is about 0.5 (${samples.average()})")

    val r2 = Formula.parse("random(2, 5)")
    val r2s = List(2000) { r2.eval(ctx) }
    Check.ok(r2s.all { it >= 2.0 && it <= 5.0 }, "random(a, b) in [a, b]")
    Check.ok(r2s.average() in 3.3..3.7, "random(a, b) mean")
    Check.ok(List(200) { ev("random(5, 2)", emptyMap()) }.all { it in 2.0..5.0 }, "random(a, b) swaps reversed bounds")
    Check.eq(3.0, Formula.parse("random(3, 3)").eval(ctx), "random(a, a) is a")

    val ri = Formula.parse("randint(1, 6)")
    val rolls = List(3000) { ri.eval(ctx) }
    Check.ok(rolls.all { it == Math.floor(it) && it in 1.0..6.0 }, "randint gives integers in range")
    Check.eq(setOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), rolls.toSet(), "randint reaches both bounds")
    val reversed = Formula.parse("randint(6, 1)")
    Check.eq(setOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), List(3000) { reversed.eval(ctx) }.toSet(), "randint swaps reversed bounds")
    Check.eq(setOf(2.0, 3.0), List(500) { Formula.parse("randint(1.2, 3.8)").eval(ctx) }.toSet(), "randint rounds bounds inwards")
    Check.eq(4.0, Formula.parse("randint(4, 4)").eval(ctx), "randint(a, a)")
    Check.eq(2.0, Formula.parse("randint(1.2, 1.8)").eval(ctx), "randint without an integer in range")

    val ch = Formula.parse("chance(30)")
    val hits = List(5000) { ch.eval(ctx) }
    Check.ok(hits.all { it == 0.0 || it == 1.0 }, "chance gives 0 or 1")
    Check.ok(hits.average() in 0.27..0.33, "chance(30) about 30% (${hits.average()})")
    Check.ok(List(500) { Formula.parse("chance(0)").eval(ctx) }.all { it == 0.0 }, "chance(0) never")
    Check.ok(List(500) { Formula.parse("chance(100)").eval(ctx) }.all { it == 1.0 }, "chance(100) always")
    Check.ok(List(100) { Formula.parse("chance(-5)").eval(ctx) }.all { it == 0.0 }, "negative chance never")
    Check.ok(List(100) { Formula.parse("chance(150)").eval(ctx) }.all { it == 1.0 }, "chance above 100 always")

    val f = Formula.parse("random() + randint(1, 100) + random(1, 2) + chance(50)")
    val a = MapContext(emptyMap(), Random(7))
    val b = MapContext(emptyMap(), Random(7))
    Check.eq(List(20) { f.eval(a) }, List(20) { f.eval(b) }, "same seed, same results")
}

private fun formulaEstimate() {
    Check.section("formula: estimate mode")
    val noDice = object : Random() {
        override fun nextBits(bitCount: Int): Int = throw IllegalStateException("estimate mode must not roll dice")
    }
    val ctx = MapContext(emptyMap(), noDice, estimate = true)
    Check.eq(0.5, Formula.parse("random()").eval(ctx), "random()")
    Check.eq(3.0, Formula.parse("random(2, 4)").eval(ctx), "random(a, b)")
    Check.eq(3.0, Formula.parse("random(4, 2)").eval(ctx), "random(b, a)")
    Check.eq(3.5, Formula.parse("randint(1, 6)").eval(ctx), "randint(a, b)")
    Check.eq(0.3, Formula.parse("chance(30)").eval(ctx), "chance(p)")
    Check.eq(1.0, Formula.parse("chance(150)").eval(ctx), "chance above 100")
    Check.eq(0.0, Formula.parse("chance(-1)").eval(ctx), "chance below 0")
    Check.near(9.25, Formula.parse("10 * random(0.85, 1)").eval(ctx), "damage roll")
    Check.eq(1.0, MapContext(emptyMap()).let { if (it.estimate) 0.0 else 1.0 }, "MapContext defaults to no estimate")
}

private fun formulaContextFunctions() {
    Check.section("formula: context functions")
    val monster = object {
        override fun toString() = "Monster"
    }
    var calls = 0
    val seen = mutableListOf<List<Any?>>()
    val ctx = object : ExprContext {
        override fun variable(name: String): Any? = when (name) {
            "user" -> monster
            "move.type" -> "fire"
            "lvl" -> 50
            else -> null
        }

        override fun function(name: String, args: List<Any?>): Any? {
            calls++
            seen.add(args.toList())
            return when (name) {
                "twice" -> (args[0] as Double) * 2
                "isFire" -> args[0] == "fire"
                "same" -> args[0] === args[1]
                "monster" -> monster
                "count" -> args.size
                "name" -> "embercub"
                "min" -> -999.0
                else -> null
            }
        }

        override val random: Random = Random(1)
    }
    fun e(src: String) = Formula.parse(src).eval(ctx)
    Check.eq(6.0, e("twice(3)"), "custom function")
    Check.eq(1.0, e("isFire(move.type)"), "string argument, Boolean result")
    Check.eq(1.0, e("same(user, user)"), "opaque objects pass through unchanged")
    Check.eq(3.0, e("count(lvl, 'a', user)"), "Int result is converted")
    Check.eq(listOf(50.0, "a", monster), seen.last(), "arguments are normalized values")
    Check.eq(0.0, e("count()"), "no arguments")
    Check.eq(1.0, e("name() == 'embercub'"), "string result")
    Check.eq(1.0, e("monster() == user"), "opaque equality")
    Check.eq(0.0, e("user == 1"), "opaque vs number")
    Check.eq(1.0, e("user != 'x'"), "opaque vs string")
    Check.ok(Formula.parse("user").evalAny(ctx) === monster, "evalAny returns opaque objects")
    val before = calls
    Check.eq(1.0, e("min(1, 2)"), "built-ins are not routed to the context")
    Check.eq(before, calls, "no context call for built-ins")
    Check.eq("Unknown function 'nope' in formula 'nope(1)'", thrownMessage<ExpressionException> { e("nope(1)") }, "unknown function")
    Check.eq("Unknown variable 'x' in formula 'x + 1'", thrownMessage<ExpressionException> { e("x + 1") }, "unknown variable")
    val opaqueMsg = thrownMessage<ExpressionException> { e("user + 1") }
    Check.ok(opaqueMsg.startsWith("Formula 'user + 1':") && opaqueMsg.contains("'user'"), "arithmetic on an opaque object")
    val resultMsg = thrownMessage<ExpressionException> { e("monster() * 2") }
    Check.ok(resultMsg.startsWith("Formula 'monster() * 2':"), "arithmetic on an opaque result")
    Check.ok(thrownMessage<ExpressionException> { e("user > 1") }.contains("'>'"), "ordering on an opaque object")
}

private fun formulaParseErrors() {
    Check.section("formula: parse errors")
    Check.eq("Formula '1 +': unexpected end of formula at position 4", parseErr("1 +"), "missing operand")
    Check.eq("Formula '(1 + 2': expected ')' but found end of formula at position 7", parseErr("(1 + 2"), "missing ')'")
    Check.eq("Formula '1 + * 2': unexpected '*' at position 5", parseErr("1 + * 2"), "unexpected operator")
    Check.eq("Formula 'a = 1': unexpected '=' (use '==' to compare) at position 3", parseErr("a = 1"), "single =")
    Check.eq("Formula 'a & b': unexpected '&' (use '&&') at position 3", parseErr("a & b"), "single &")
    Check.eq("Formula 'a | b': unexpected '|' (use '||') at position 3", parseErr("a | b"), "single |")
    Check.eq("Formula '1 # 2': unexpected '#' at position 3", parseErr("1 # 2"), "unknown character")
    Check.eq("Formula '2 3': unexpected number '3' at position 3", parseErr("2 3"), "two numbers")
    Check.eq("Formula '2x': unexpected 'x' at position 2", parseErr("2x"), "number followed by a name")
    Check.eq("Formula 'user.': unexpected '.' at position 5", parseErr("user."), "dangling dot")
    Check.eq("Formula '1 )': unexpected ')' at position 3", parseErr("1 )"), "extra ')'")
    Check.eq(
        "Formula 'min(1 2)': expected ',' or ')' in the call to min() but found number '2' at position 7",
        parseErr("min(1 2)"),
        "missing comma in call",
    )
    Check.eq("Formula 'f(1,)': unexpected ')' at position 5", parseErr("f(1,)"), "trailing comma in call")
    Check.eq(
        "Formula 'x ? 1': expected ':' of the '?' conditional but found end of formula at position 6",
        parseErr("x ? 1"),
        "missing ':'",
    )
    Check.eq("Formula ''abc': unterminated string at position 1", parseErr("'abc"), "unterminated string")
    Check.eq("Formula '': empty formula at position 1", parseErr(""), "empty formula")
    Check.ok(parseErr("   ").contains("empty formula"), "blank formula")
    Check.ok(parseErr("1 ? : 2").contains("unexpected ':'"), "empty then branch")
    Check.ok(parseErr("()").contains("unexpected ')'"), "empty parentheses")
    Check.ok(parseErr("1 2 +").contains("unexpected number '2'"), "trailing tokens")
    Check.ok(parseErr("'a' 'b'").contains("unexpected string 'b'"), "two strings")
    Check.ok(parseErr("(".repeat(300) + "1" + ")".repeat(300)).contains("nested too deeply"), "deep parentheses")
    Check.ok(parseErr("-".repeat(300) + "1").contains("nested too deeply"), "deep unary chain")
    Check.eq(1.0, ev("(".repeat(150) + "1" + ")".repeat(150)), "reasonable nesting is fine")
    // '^' chains recurse too, and long operator chains build deep trees: both are parse errors, not a StackOverflowError.
    Check.ok(parseErr("2" + "^2".repeat(3000)).contains("nested too deeply"), "long '^' chain")
    Check.ok(parseErr("x" + "+x".repeat(20000)).contains("nested too deeply"), "long '+' chain")
    Check.ok(parseErr("x" + " && x".repeat(5000)).contains("nested too deeply"), "long '&&' chain")
    Check.eq(1.0, ev("1" + "^1".repeat(100)), "a reasonable '^' chain is fine")
    Check.eq(301.0, ev("x" + "+x".repeat(300), mapOf("x" to 1)), "a reasonable '+' chain is fine")
    Check.throws<ExpressionException>("parse errors are ExpressionException") { Formula.parse("1 +") }
}

private fun formulaEvalErrors() {
    Check.section("formula: evaluation errors")
    val v = mapOf("move.type" to "fire")
    Check.eq("Unknown variable 'boom' in formula '1 + boom'", evalErr("1 + boom"), "unknown variable")
    Check.eq(
        "Formula 'move.type + 1': expected a number for '+' but got the string 'fire'",
        evalErr("move.type + 1", v),
        "string arithmetic",
    )
    for (op in listOf("+", "-", "*", "/", "%", "^", "<", "<=", ">", ">=", "&&", "||")) {
        val msg = evalErr("'a' $op 1")
        Check.ok(msg.startsWith("Formula ''a' $op 1':") && msg.contains("'$op'"), "'$op' on a string -> $msg")
    }
    Check.ok(evalErr("1 + 'a'").contains("the string 'a'"), "string on the right")
    Check.ok(evalErr("-'a'").contains("'-'"), "unary minus on a string")
    Check.ok(evalErr("!'a'").contains("'!'"), "! on a string")
    Check.ok(evalErr("'a' ? 1 : 2").contains("the condition"), "string condition")
    Check.ok(evalErr("min('a', 1)").contains("min()"), "string argument to a built-in")
    Check.eq(
        "Formula ''fire'': expected a number for the result but got the string 'fire'",
        evalErr("'fire'"),
        "eval of a string result",
    )
    Check.throws<ExpressionException>("test() of a string") { Formula.parse("move.type").test(MapContext(v)) }
    Check.eq("fire", evAny("move.type", v), "evalAny of a string is fine")
    Check.eq("Unknown variable 'x' in formula 'x'", thrownMessage<ExpressionException> { evAny("x") }, "evalAny unknown variable")
}

private fun formulaIntrospection() {
    Check.section("formula: introspection")
    val f = Formula.parse("user.atk * 2 + hasType(user, 'fire') + min(damage, 3) + user.atk + (true ? x.y.z : 0)")
    Check.eq(setOf("user.atk", "user", "damage", "x.y.z"), f.variables(), "variables")
    Check.eq(setOf("hasType", "min"), f.functions(), "functions")
    Check.eq(mapOf("hasType" to setOf(2), "min" to setOf(2)), f.functionCalls(), "argument counts of each call")
    Check.eq(setOf(1, 2, 0), Formula.parse("hasItem(self) + hasItem(self, 'x') + hasItem()").functionCalls()["hasItem"], "every count is recorded")
    Check.eq(emptySet<String>(), Formula.parse("true + false").variables(), "true/false are not variables")
    Check.eq(setOf("random", "chance"), Formula.parse("random() + 0 * chance(5)").functions(), "built-ins are listed")
    Check.eq(setOf("a", "b"), Formula.parse("0 && a || b").variables(), "names in short-circuited parts are listed")

    Check.eq(40.0, Formula.parse("40").constant, "constant number")
    Check.eq(-5.0, Formula.parse("-5").constant, "constant negative")
    Check.eq(2.5, Formula.parse(" 2.5 ").constant, "constant with spaces")
    Check.eq(null, Formula.parse("2 + 3").constant, "expression is not constant")
    Check.eq(null, Formula.parse("1 / 24").constant, "folded expression is not constant")
    Check.eq(null, Formula.parse("x").constant, "variable is not constant")
    Check.eq(null, Formula.parse("--5").constant, "double minus is not constant")
    Check.eq(null, Formula.parse("true").constant, "true is not a number literal")
    Check.eq(40.0, Formula.of(40.0).constant, "of() is constant")

    Check.eq("50", Formula.of(50.0).source, "of() source without .0")
    Check.eq("0.5", Formula.of(0.5).source, "of() source fraction")
    Check.eq("-3", Formula.of(-3.0).source, "of() source negative")
    Check.eq(1e-7, Formula.parse(Formula.of(1e-7).source).eval(MapContext(emptyMap())), "of() source parses back")
    Check.eq(50.0, Formula.of(50.0).eval(MapContext(emptyMap())), "of() evaluates")
    Check.eq(emptySet<String>(), Formula.of(1.0).variables(), "of() has no variables")
    Check.eq("x + 1", Formula.parse("x + 1").toString(), "toString is the source")
    Check.eq("x + 1", Formula.parse("x + 1").source, "source")
    Check.eq(Formula.of(40.0), Formula.parse("40"), "equal by source")
    Check.eq(Formula.of(40.0).hashCode(), Formula.parse("40").hashCode(), "hashCode by source")
    Check.ok(Formula.parse("1+1") != Formula.parse("1 + 1"), "different sources are different")
}

private fun formulaMapContext() {
    Check.section("formula: MapContext and setup formulas")
    val any = Any()
    val vars = mapOf("i" to 3, "l" to 4L, "f" to 0.5f, "b" to true, "s" to "x", "o" to any, "nul" to null)
    Check.eq(7.0, ev("i + l", vars), "Int and Long variables")
    Check.eq(0.5, ev("f", vars), "Float variable")
    Check.eq("x", evAny("s", vars), "String variable")
    Check.ok(evAny("o", vars) === any, "opaque variable")
    Check.eq("Unknown variable 'nul' in formula 'nul'", evalErr("nul", vars), "null value is unknown")
    Check.eq("Unknown variable 'missing' in formula 'missing * 2'", evalErr("missing * 2", vars), "missing variable")

    val hp = Formula.parse("floor((2 * base + floor(points / 4)) * level / 100) + level + 10")
    Check.eq(105.0, hp.eval(MapContext(mapOf("base" to 45, "points" to 0, "level" to 50))), "README hp stat formula")
    Check.eq(136.0, hp.eval(MapContext(mapOf("base" to 45.0, "points" to 252.0, "level" to 50.0))), "hp with stat points")
    val stage = Formula.parse("stage >= 0 ? (2 + stage) / 2 : 2 / (2 - stage)")
    Check.near(2.0 / 3, stage.eval(MapContext(mapOf("stage" to -1))), "stage -1")
    Check.eq(2.0, stage.eval(MapContext(mapOf("stage" to 2))), "stage +2")
    val damage = Formula.parse(
        "floor(floor(floor(2 * user.level / 5 + 2) * move.power * attack / defense) / 50 + 2) * " +
            "(sameType ? 1.5 : 1) * effectiveness * crit * random(0.85, 1)"
    )
    val dv = mapOf(
        "user.level" to 50, "move.power" to 40, "attack" to 100.0, "defense" to 80.0,
        "sameType" to true, "effectiveness" to 2.0, "crit" to 1.0,
    )
    Check.near(66.6, damage.eval(MapContext(dv, estimate = true)), "README damage formula (estimate)", 1e-9)
    val rolled = damage.eval(MapContext(dv, Random(3)))
    Check.ok(rolled >= 24 * 1.5 * 2 * 0.85 && rolled <= 72.0, "README damage formula (rolled $rolled)")
    Check.eq(
        setOf("user.level", "move.power", "attack", "defense", "sameType", "effectiveness", "crit"),
        damage.variables(),
        "damage formula variables",
    )
}

private fun formulaFromJson() {
    Check.section("formula: JsonObj.formula")
    val p = "Moves.json: moves[0]"
    val o = JsonObj.of(
        Json.parse("""{"power": 40, "acc": "user.spe > target.spe ? 80 : 40", "bad": "1 +", "flag": true, "nul": null}"""),
        p,
    )
    Check.eq(40.0, o.formula("power").constant, "number becomes a constant formula")
    Check.eq("40", o.formula("power").source, "constant source")
    Check.eq("user.spe > target.spe ? 80 : 40", o.formula("acc").source, "string is parsed")
    Check.eq(80.0, o.formula("acc").eval(MapContext(mapOf("user.spe" to 10, "target.spe" to 5))), "parsed formula evaluates")
    Check.eq(
        "$p.bad: Formula '1 +': unexpected end of formula at position 4",
        setupErr { o.formula("bad") },
        "parse errors become SetupException with the path",
    )
    Check.eq("$p: missing required field 'zz'", setupErr { o.formula("zz") }, "missing")
    Check.eq("$p.nul: expected a number or formula but found null", setupErr { o.formula("nul") }, "explicit null")
    Check.eq(
        "$p.flag: expected a number or formula but found a boolean",
        setupErr { o.formula("flag") },
        "wrong type",
    )
    Check.eq(null, o.formulaOrNull("zz"), "formulaOrNull missing")
    Check.eq(null, o.formulaOrNull("nul"), "formulaOrNull null")
    Check.eq(40.0, o.formulaOrNull("power")?.constant, "formulaOrNull present")
    Check.ok(setupErr { o.formulaOrNull("bad") }.startsWith("$p.bad: Formula '1 +'"), "formulaOrNull parse error")
    Check.eq(1.0, o.formula("zz", 1.0).constant, "formula default when missing")
    Check.eq(2.0, o.formula("nul", 2.0).constant, "formula default when null")
    Check.eq("1", o.formula("zz", 1.0).source, "default source")
    Check.eq(40.0, o.formula("power", 1.0).constant, "formula default not used when present")
    Check.ok(setupErr { o.formula("flag", 1.0) }.contains("found a boolean"), "default does not hide wrong types")
}
