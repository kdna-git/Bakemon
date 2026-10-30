package engine

import util.Json
import util.JsonObj
import util.SetupException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/** Thrown when a formula cannot be parsed or evaluated. Messages quote the formula source. */
class ExpressionException(message: String) : Exception(message)

/**
 * Supplies variables, extra functions and randomness to a [Formula] while it is evaluated.
 *
 * Values exchanged with the evaluator are: Double (numbers and booleans as 1.0/0.0), String,
 * or an opaque object (e.g. a monster reference, passed through to functions like `hasType(user, 'fire')`).
 */
interface ExprContext {
    /**
     * Resolves a (possibly dotted) variable name such as `user.atk`, `move.type`, `damage` or `user`.
     * Returns null if the name is unknown, which makes the evaluator throw an ExpressionException.
     * Known-but-absent values (e.g. `other.hp` when there is no other) must return 0.0 / "" instead of null.
     */
    fun variable(name: String): Any?

    /**
     * Context-specific functions (e.g. `hasType(m, 't')`). Called only for names that are not built-ins.
     * Return null if the function is unknown (the evaluator then throws).
     */
    fun function(name: String, args: List<Any?>): Any? = null

    /** Randomness for random(), randint(), chance(). */
    val random: Random

    /**
     * Estimate mode (used by the AI to predict damage without rolling dice):
     * random() -> 0.5, random(a, b) -> (a + b) / 2, randint(a, b) -> (a + b) / 2, chance(p) -> p / 100.
     */
    val estimate: Boolean get() = false
}

/** A simple context backed by a map of variables. Handy for stat formulas and tests. */
class MapContext(
    private val vars: Map<String, Any?>,
    override val random: Random = Random.Default,
    override val estimate: Boolean = false,
) : ExprContext {
    override fun variable(name: String): Any? = vars[name]
}

/**
 * A compiled formula. JSON values that accept "a number or a formula" become a Formula.
 *
 * Grammar (lowest to highest precedence):
 *   ternary   := or ('?' ternary ':' ternary)?
 *   or        := and ('||' and)*
 *   and       := equality ('&&' equality)*
 *   equality  := compare (('==' | '!=') compare)*
 *   compare   := additive (('<' | '<=' | '>' | '>=') additive)*
 *   additive  := mult (('+' | '-') mult)*
 *   mult      := unary (('*' | '/' | '%') unary)*
 *   unary     := ('-' | '!' | '+') unary | power
 *   power     := primary ('^' unary)?            (right-associative)
 *   primary   := number | string | 'true' | 'false' | name '(' args ')' | name | '(' ternary ')'
 *   name      := identifier ('.' identifier)*     (identifier: letters, digits, '_', not starting with a digit)
 *
 * Semantics: booleans are numbers (true = 1, false = 0, anything non-zero is truthy); `==`/`!=` compare
 * numbers or strings; arithmetic on strings is an error; division or modulo by zero yields 0;
 * `&&`, `||` and `?:` short-circuit.
 *
 * Built-in functions: min, max (1+ args), floor, ceil, round, abs, sqrt, pow(a,b), clamp(x,lo,hi),
 * if(cond,a,b), random() / random(a,b), randint(a,b), chance(p). See [BUILTIN_FUNCTIONS].
 *
 * Details: numbers may have an exponent (`1e-3`); strings accept backslash escapes (`\'`, `\\`, `\n`, `\t`);
 * `round` rounds halves up (`round(-2.5) = -2`); `clamp` returns `lo` when `x < lo`, else `hi` when `x > hi`;
 * `randint` swaps reversed bounds and returns `ceil(min)` when the range holds no integer; in estimate mode
 * `chance(p)` gives `p / 100` clamped to 0..1. Built-in arity is checked when parsing.
 * `==` on two opaque objects uses `equals`. Formulas are equal when their sources are equal.
 *
 * A Formula is immutable and thread-safe. Evaluation walks a pre-built tree and does not box numbers
 * except where values cross the [ExprContext] boundary.
 */
class Formula private constructor(val source: String) {

    private lateinit var root: FormulaImpl.Node
    private var variableNames: Set<String> = emptySet()
    private var functionNames: Set<String> = emptySet()
    private var callArities: Map<String, Set<Int>> = emptyMap()
    private var literalArgs: Map<String, Set<Pair<Int, String>>> = emptyMap()
    private var constantValue: Double? = null

    /** Evaluates to a number (booleans give 1.0/0.0). A string result throws ExpressionException. */
    fun eval(ctx: ExprContext): Double = try {
        root.num(ctx, "the result")
    } catch (e: FormulaImpl.EvalFailure) {
        throw ExpressionException(e.render(source))
    }

    /** Evaluates to whatever the expression produces (Double, String or an opaque object). */
    fun evalAny(ctx: ExprContext): Any? = try {
        root.any(ctx)
    } catch (e: FormulaImpl.EvalFailure) {
        throw ExpressionException(e.render(source))
    }

    /** Evaluates as a condition: true if the numeric result is non-zero. */
    fun test(ctx: ExprContext): Boolean = eval(ctx).let { it != 0.0 && !it.isNaN() }

    /** The value if this formula is a plain number literal (e.g. "40" or 40 in JSON), else null. */
    val constant: Double? get() = constantValue

    /** Every variable name referenced (e.g. "user.atk", "damage", "user" when used as a function argument). */
    fun variables(): Set<String> = variableNames

    /** Every function name called (built-in or not). */
    fun functions(): Set<String> = functionNames

    /**
     * Every function called, with each number of arguments it is called with (e.g. "hasItem" -> {1, 2}).
     * Built-in arities are already checked by [parse]; setups check the battle functions with this.
     */
    fun functionCalls(): Map<String, Set<Int>> = callArities

    /**
     * The string literals passed to calls of function [name], as (argument index, text) pairs
     * (e.g. fieldActive('rain') gives (0, "rain")). Setups use it to check ids written in formulas.
     */
    fun stringArguments(name: String): Set<Pair<Int, String>> = literalArgs[name].orEmpty()

    override fun equals(other: Any?): Boolean = other is Formula && other.source == source

    override fun hashCode(): Int = source.hashCode()

    override fun toString(): String = source

    companion object {
        /** Parses [source]; throws ExpressionException with the position of the problem (1-based character index). */
        fun parse(source: String): Formula {
            val parser = FormulaImpl.Parser(source)
            val root = parser.parseAll()
            return create(source, root, parser.variables, parser.functions, parser.constant, parser.calls).also {
                it.literalArgs = parser.literals
            }
        }

        /** A formula that is just the number [value] (its source is the number formatted without ".0"). */
        fun of(value: Double): Formula =
            create(Json.formatNumber(value), FormulaImpl.Num(value), emptySet(), emptySet(), value, emptyMap())

        val BUILTIN_FUNCTIONS: Set<String> = setOf(
            "min", "max", "floor", "ceil", "round", "abs", "sqrt", "pow", "clamp", "if", "random", "randint", "chance",
        )

        private fun create(
            source: String,
            root: FormulaImpl.Node,
            vars: Set<String>,
            funcs: Set<String>,
            constant: Double?,
            calls: Map<String, Set<Int>>,
        ): Formula {
            val f = Formula(source)
            f.root = root
            f.variableNames = vars
            f.functionNames = funcs
            f.callArities = calls
            f.constantValue = constant
            return f
        }
    }
}

/**
 * Reads a "number or formula" field: a JSON number becomes a constant formula, a string is parsed.
 * Parse errors are rethrown as SetupException with the field path. Missing -> SetupException.
 */
fun JsonObj.formula(key: String): Formula {
    if (!map.containsKey(key)) fail("missing required field '$key'")
    return formulaOrNull(key) ?: throw SetupException("${pathOf(key)}: expected a number or formula but found null")
}

/** Like [formula] but returns null when the key is missing or JSON null. */
fun JsonObj.formulaOrNull(key: String): Formula? = when (val v = map[key]) {
    null -> null
    is Number -> Formula.of(v.toDouble())
    is String -> try {
        Formula.parse(v)
    } catch (e: ExpressionException) {
        throw SetupException("${pathOf(key)}: ${e.message}")
    }
    else -> throw SetupException("${pathOf(key)}: expected a number or formula but found ${Json.typeName(v)}")
}

/** Like [formula] but returns a constant [default] when the key is missing or JSON null. */
fun JsonObj.formula(key: String, default: Double): Formula = formulaOrNull(key) ?: Formula.of(default)

/**
 * Implementation of [Formula]: evaluation tree, lexer and parser. Nested in one object so the many small
 * private classes cannot clash with other private classes of the `engine` package on the JVM.
 */
private object FormulaImpl {

    // ----------------------------------------------------------------------------------------------------------------
    // Evaluation tree

    /**
     * Evaluation error raised inside the tree. [Formula] turns it into an ExpressionException that quotes the source,
     * so it never escapes and is created without a stack trace.
     */
    class EvalFailure(private val build: (String) -> String) : RuntimeException(null, null, false, false) {
        fun render(source: String): String = build(source)
    }

    fun failure(problem: String): EvalFailure = EvalFailure { src -> "Formula '$src': $problem" }

    /** Truth value of a number: non-zero is true; NaN (an impossible result) is false. */
    fun truthy(v: Double): Boolean = v != 0.0 && !v.isNaN()

    val ONE: Any = 1.0
    val ZERO: Any = 0.0

    /** Converts a value coming from the context to the evaluator's value space (Double, String or opaque). */
    fun normalize(v: Any?): Any? = when (v) {
        is Double -> v
        is Number -> v.toDouble()
        is Boolean -> if (v) ONE else ZERO
        else -> v
    }

    /** A node of the evaluation tree; [children] are its operands (only used to compute [depth]). */
    abstract class Node(vararg children: Node) {
        /** Height of the tree under this node (1 for a leaf). Evaluation recurses this deep. */
        val depth: Int = 1 + (children.maxOfOrNull { it.depth } ?: 0)

        /** True if this node always yields a number, so [num] never has to convert a boxed value. */
        open val numeric: Boolean get() = true

        /** Evaluates to a number. [op] names the consumer of the value, used only in error messages. */
        abstract fun num(ctx: ExprContext, op: String): Double

        /** Evaluates to any value (Double, String or opaque object). */
        open fun any(ctx: ExprContext): Any? = num(ctx, "the result")

        /** Converts a non-numeric node's value, failing for strings and opaque objects. */
        protected fun toNum(v: Any?, op: String): Double = when (v) {
            is Double -> v
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            is String -> throw failure("expected a number for $op but got the string '$v'")
            else -> throw failure("expected a number for $op but got a non-number value${label()}")
        }

        /** Extra text describing this node in type errors. */
        protected open fun label(): String = ""
    }

    class Num(val value: Double) : Node() {
        private val boxed: Any = value
        override fun num(ctx: ExprContext, op: String): Double = value
        override fun any(ctx: ExprContext): Any? = boxed
    }

    class Str(val value: String) : Node() {
        override val numeric: Boolean get() = false
        override fun num(ctx: ExprContext, op: String): Double = toNum(value, op)
        override fun any(ctx: ExprContext): Any? = value
    }

    class Var(val name: String) : Node() {
        override val numeric: Boolean get() = false

        private fun lookup(ctx: ExprContext): Any =
            ctx.variable(name) ?: throw EvalFailure { src -> "Unknown variable '$name' in formula '$src'" }

        override fun num(ctx: ExprContext, op: String): Double = toNum(lookup(ctx), op)
        override fun any(ctx: ExprContext): Any? = normalize(lookup(ctx))
        override fun label(): String = " ('$name')"
    }

    /** A call to a function supplied by the context. */
    class Call(val name: String, val args: Array<Node>) : Node(*args) {
        override val numeric: Boolean get() = false

        override fun any(ctx: ExprContext): Any? {
            val values = ArrayList<Any?>(args.size)
            for (a in args) values.add(a.any(ctx))
            val result = try {
                ctx.function(name, values)
            } catch (e: ExpressionException) {
                // A context function that rejects its arguments: quote the formula it was called from.
                val problem = e.message ?: "$name() failed"
                throw EvalFailure { src -> "Formula '$src': $problem" }
            } ?: throw EvalFailure { src -> "Unknown function '$name' in formula '$src'" }
            return normalize(result)
        }

        override fun num(ctx: ExprContext, op: String): Double = toNum(any(ctx), op)
        override fun label(): String = " ('$name(...)')"
    }

    class Neg(val x: Node) : Node(x) {
        override fun num(ctx: ExprContext, op: String): Double = -x.num(ctx, "'-'")
    }

    class Plus(val x: Node) : Node(x) {
        override fun num(ctx: ExprContext, op: String): Double = x.num(ctx, "'+'")
    }

    class Not(val x: Node) : Node(x) {
        override fun num(ctx: ExprContext, op: String): Double = if (truthy(x.num(ctx, "'!'"))) 0.0 else 1.0
    }

    class Add(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double = a.num(ctx, "'+'") + b.num(ctx, "'+'")
    }

    class Sub(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double = a.num(ctx, "'-'") - b.num(ctx, "'-'")
    }

    class Mul(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double = a.num(ctx, "'*'") * b.num(ctx, "'*'")
    }

    class Div(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double {
            val x = a.num(ctx, "'/'")
            val y = b.num(ctx, "'/'")
            return if (y == 0.0) 0.0 else x / y
        }
    }

    class Mod(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double {
            val x = a.num(ctx, "'%'")
            val y = b.num(ctx, "'%'")
            return if (y == 0.0) 0.0 else x % y
        }
    }

    class Pow(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double = Math.pow(a.num(ctx, "'^'"), b.num(ctx, "'^'"))
    }

    class Lt(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double = if (a.num(ctx, "'<'") < b.num(ctx, "'<'")) 1.0 else 0.0
    }

    class Le(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double = if (a.num(ctx, "'<='") <= b.num(ctx, "'<='")) 1.0 else 0.0
    }

    class Gt(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double = if (a.num(ctx, "'>'") > b.num(ctx, "'>'")) 1.0 else 0.0
    }

    class Ge(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double = if (a.num(ctx, "'>='") >= b.num(ctx, "'>='")) 1.0 else 0.0
    }

    /** `==` (or `!=` when [negate]): numbers and strings; a number never equals a string. */
    class Eq(val a: Node, val b: Node, val negate: Boolean) : Node(a, b) {
        private val opName = if (negate) "'!='" else "'=='"

        override fun num(ctx: ExprContext, op: String): Double = if (equal(ctx) != negate) 1.0 else 0.0

        private fun equal(ctx: ExprContext): Boolean {
            if (a.numeric && b.numeric) return a.num(ctx, opName) == b.num(ctx, opName)
            if (a.numeric) return numEquals(a.num(ctx, opName), b.any(ctx))
            if (b.numeric) return numEquals(b.num(ctx, opName), a.any(ctx))
            val x = a.any(ctx)
            val y = b.any(ctx)
            return when (x) {
                is Double -> numEquals(x, y)
                is String -> y is String && x == y
                else -> y !is Double && y !is String && x == y
            }
        }

        private fun numEquals(x: Double, other: Any?): Boolean {
            if (other !is Double) return false
            val y: Double = other
            return x == y
        }
    }

    class And(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double =
            if (truthy(a.num(ctx, "'&&'")) && truthy(b.num(ctx, "'&&'"))) 1.0 else 0.0
    }

    class Or(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double =
            if (truthy(a.num(ctx, "'||'")) || truthy(b.num(ctx, "'||'"))) 1.0 else 0.0
    }

    /** `c ? t : f` and `if(c, t, f)`: only the chosen branch is evaluated. */
    class Cond(val c: Node, val t: Node, val f: Node) : Node(c, t, f) {
        override val numeric: Boolean = t.numeric && f.numeric
        override fun num(ctx: ExprContext, op: String): Double =
            if (truthy(c.num(ctx, "the condition"))) t.num(ctx, op) else f.num(ctx, op)
        override fun any(ctx: ExprContext): Any? =
            if (truthy(c.num(ctx, "the condition"))) t.any(ctx) else f.any(ctx)
    }

    class MinFn(val args: Array<Node>) : Node(*args) {
        override fun num(ctx: ExprContext, op: String): Double {
            var m = args[0].num(ctx, "min()")
            for (i in 1 until args.size) m = Math.min(m, args[i].num(ctx, "min()"))
            return m
        }
    }

    class MaxFn(val args: Array<Node>) : Node(*args) {
        override fun num(ctx: ExprContext, op: String): Double {
            var m = args[0].num(ctx, "max()")
            for (i in 1 until args.size) m = Math.max(m, args[i].num(ctx, "max()"))
            return m
        }
    }

    class FloorFn(val x: Node) : Node(x) {
        override fun num(ctx: ExprContext, op: String): Double = floor(x.num(ctx, "floor()"))
    }

    class CeilFn(val x: Node) : Node(x) {
        override fun num(ctx: ExprContext, op: String): Double = ceil(x.num(ctx, "ceil()"))
    }

    /** Rounds halves up (towards +infinity), like Math.round. */
    class RoundFn(val x: Node) : Node(x) {
        override fun num(ctx: ExprContext, op: String): Double {
            val v = x.num(ctx, "round()")
            // Beyond 2^52 every double is already whole; Math.round would overflow its long there.
            return if (Math.abs(v) < 4.5e15) Math.round(v).toDouble() else v
        }
    }

    class AbsFn(val x: Node) : Node(x) {
        override fun num(ctx: ExprContext, op: String): Double = Math.abs(x.num(ctx, "abs()"))
    }

    class SqrtFn(val x: Node) : Node(x) {
        // Like division by zero, an impossible square root gives 0 instead of NaN.
        override fun num(ctx: ExprContext, op: String): Double = x.num(ctx, "sqrt()").let { if (it > 0.0) sqrt(it) else 0.0 }
    }

    class PowFn(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double = Math.pow(a.num(ctx, "pow()"), b.num(ctx, "pow()"))
    }

    class ClampFn(val x: Node, val lo: Node, val hi: Node) : Node(x, lo, hi) {
        override fun num(ctx: ExprContext, op: String): Double {
            val v = x.num(ctx, "clamp()")
            val l = lo.num(ctx, "clamp()")
            val h = hi.num(ctx, "clamp()")
            return if (v < l) l else if (v > h) h else v
        }
    }

    /** random(): 0 <= r < 1. */
    class Random0 : Node() {
        override fun num(ctx: ExprContext, op: String): Double = if (ctx.estimate) 0.5 else ctx.random.nextDouble()
    }

    /** random(a, b): uniform real between a and b (bounds swapped if reversed). */
    class Random2(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double {
            var lo = a.num(ctx, "random()")
            var hi = b.num(ctx, "random()")
            if (lo > hi) lo = hi.also { hi = lo }
            if (ctx.estimate) return (lo + hi) / 2
            if (lo == hi) return lo
            return lo + ctx.random.nextDouble() * (hi - lo)
        }
    }

    /** randint(a, b): integer from ceil(a) to floor(b) inclusive (bounds swapped if reversed). */
    class RandInt(val a: Node, val b: Node) : Node(a, b) {
        override fun num(ctx: ExprContext, op: String): Double {
            val x = a.num(ctx, "randint()")
            val y = b.num(ctx, "randint()")
            val lo = ceil(Math.min(x, y))
            val hi = floor(Math.max(x, y))
            if (lo >= hi) return lo
            if (ctx.estimate) return (lo + hi) / 2
            val v = lo + floor(ctx.random.nextDouble() * (hi - lo + 1))
            return if (v > hi) hi else v
        }
    }

    /** chance(p): 1 with probability p percent. */
    class ChanceFn(val p: Node) : Node(p) {
        override fun num(ctx: ExprContext, op: String): Double {
            val percent = p.num(ctx, "chance()")
            if (ctx.estimate) return (percent / 100).coerceIn(0.0, 1.0)
            return if (ctx.random.nextDouble() * 100 < percent) 1.0 else 0.0
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Lexer and parser

    enum class Tok { NUM, STR, NAME, PUNCT, END }

    class Token(val kind: Tok, val text: String, val pos: Int, val number: Double = 0.0) {
        fun describe(): String = when (kind) {
            Tok.NUM -> "number '$text'"
            Tok.STR -> "string '$text'"
            Tok.NAME -> "'$text'"
            Tok.PUNCT -> "'$text'"
            Tok.END -> "end of formula"
        }
    }

    fun parseError(src: String, problem: String, pos: Int): ExpressionException =
        ExpressionException("Formula '$src': $problem at position ${pos + 1}")

    fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

    fun tokenize(src: String): List<Token> {
        val tokens = ArrayList<Token>()
        var p = 0
        val n = src.length
        fun isDigit(at: Int) = at < n && src[at] in '0'..'9'
        while (p < n) {
            val c = src[p]
            val start = p
            when {
                c.isWhitespace() -> p++
                c in '0'..'9' || (c == '.' && isDigit(p + 1)) -> {
                    while (isDigit(p)) p++
                    if (p < n && src[p] == '.' && isDigit(p + 1)) {
                        p++
                        while (isDigit(p)) p++
                    }
                    if (p < n && (src[p] == 'e' || src[p] == 'E')) {
                        val sign = p + 1 < n && (src[p + 1] == '+' || src[p + 1] == '-')
                        val digitAt = if (sign) p + 2 else p + 1
                        if (isDigit(digitAt)) {
                            p = digitAt
                            while (isDigit(p)) p++
                        }
                    }
                    val text = src.substring(start, p)
                    tokens += Token(Tok.NUM, text, start, text.toDouble())
                }
                isIdentStart(c) -> {
                    p++
                    while (true) {
                        while (p < n && isIdentPart(src[p])) p++
                        if (p + 1 < n && src[p] == '.' && isIdentStart(src[p + 1])) p++ else break
                    }
                    tokens += Token(Tok.NAME, src.substring(start, p), start)
                }
                c == '\'' || c == '"' -> {
                    val sb = StringBuilder()
                    p++
                    while (true) {
                        if (p >= n) throw parseError(src, "unterminated string", start)
                        val ch = src[p]
                        if (ch == c) break
                        if (ch == '\\' && p + 1 < n) {
                            when (val e = src[p + 1]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                else -> sb.append(e)
                            }
                            p += 2
                        } else {
                            sb.append(ch)
                            p++
                        }
                    }
                    p++ // closing quote
                    tokens += Token(Tok.STR, sb.toString(), start)
                }
                else -> {
                    val two = if (p + 1 < n) src.substring(p, p + 2) else ""
                    when {
                        two in TWO_CHAR_OPS -> {
                            tokens += Token(Tok.PUNCT, two, start)
                            p += 2
                        }
                        c in "+-*/%^<>!?:()," -> {
                            tokens += Token(Tok.PUNCT, c.toString(), start)
                            p++
                        }
                        c == '=' -> throw parseError(src, "unexpected '=' (use '==' to compare)", start)
                        c == '&' -> throw parseError(src, "unexpected '&' (use '&&')", start)
                        c == '|' -> throw parseError(src, "unexpected '|' (use '||')", start)
                        c in ' '..'~' -> throw parseError(src, "unexpected '$c'", start)
                        else -> throw parseError(src, "unexpected character U+%04X".format(c.code), start)
                    }
                }
            }
        }
        tokens += Token(Tok.END, "", n)
        return tokens
    }

    val TWO_CHAR_OPS = setOf("<=", ">=", "==", "!=", "&&", "||")

    /** Recursive-descent parser following the grammar in [Formula]'s KDoc. */
    class Parser(private val src: String) {
        private val tokens = tokenize(src)
        private var index = 0
        private var depth = 0

        val variables = LinkedHashSet<String>()
        val functions = LinkedHashSet<String>()
        /** Function name -> every argument count it is called with. */
        val calls = LinkedHashMap<String, MutableSet<Int>>()
        /** Function name -> (argument index, string literal) of every literal string argument. */
        val literals = LinkedHashMap<String, MutableSet<Pair<Int, String>>>()

        /** Non-null only for a plain number literal, optionally preceded by a unary minus. */
        val constant: Double? = when {
            tokens.size == 2 && tokens[0].kind == Tok.NUM -> tokens[0].number
            tokens.size == 3 && tokens[0].isPunct("-") && tokens[1].kind == Tok.NUM -> -tokens[1].number
            else -> null
        }

        private val peek: Token get() = tokens[index]

        fun parseAll(): Node {
            if (peek.kind == Tok.END) throw parseError(src, "empty formula", peek.pos)
            val node = ternary()
            if (peek.kind != Tok.END) throw parseError(src, "unexpected ${peek.describe()}", peek.pos)
            // Long operator chains ('x + x + x ...') build a deep tree without nesting: evaluating it recurses that deep.
            if (node.depth > MAX_TREE_DEPTH) throw parseError(src, "formula is nested too deeply (too many operators)", 0)
            return node
        }

        private fun Token.isPunct(s: String) = kind == Tok.PUNCT && text == s

        private fun accept(s: String): Boolean {
            if (peek.isPunct(s)) {
                index++
                return true
            }
            return false
        }

        private fun expect(s: String, problem: String) {
            if (!accept(s)) throw parseError(src, "$problem but found ${peek.describe()}", peek.pos)
        }

        private fun enter() {
            if (++depth > MAX_DEPTH) throw parseError(src, "formula is nested too deeply", peek.pos)
        }

        private fun ternary(): Node {
            enter()
            val cond = or()
            val result = if (accept("?")) {
                val t = ternary()
                expect(":", "expected ':' of the '?' conditional")
                val f = ternary()
                Cond(cond, t, f)
            } else cond
            depth--
            return result
        }

        private fun or(): Node {
            var left = and()
            while (accept("||")) left = Or(left, and())
            return left
        }

        private fun and(): Node {
            var left = equality()
            while (accept("&&")) left = And(left, equality())
            return left
        }

        private fun equality(): Node {
            var left = compare()
            while (true) {
                left = when {
                    accept("==") -> Eq(left, compare(), negate = false)
                    accept("!=") -> Eq(left, compare(), negate = true)
                    else -> return left
                }
            }
        }

        private fun compare(): Node {
            var left = additive()
            while (true) {
                left = when {
                    accept("<") -> Lt(left, additive())
                    accept("<=") -> Le(left, additive())
                    accept(">") -> Gt(left, additive())
                    accept(">=") -> Ge(left, additive())
                    else -> return left
                }
            }
        }

        private fun additive(): Node {
            var left = mult()
            while (true) {
                left = when {
                    accept("+") -> fold(Add(left, mult()))
                    accept("-") -> fold(Sub(left, mult()))
                    else -> return left
                }
            }
        }

        private fun mult(): Node {
            var left = unary()
            while (true) {
                left = when {
                    accept("*") -> fold(Mul(left, unary()))
                    accept("/") -> fold(Div(left, unary()))
                    accept("%") -> fold(Mod(left, unary()))
                    else -> return left
                }
            }
        }

        private fun unary(): Node {
            val node = when {
                accept("-") -> { enter(); fold(Neg(unary())).also { depth-- } }
                accept("+") -> { enter(); Plus(unary()).also { depth-- } }
                accept("!") -> { enter(); Not(unary()).also { depth-- } }
                else -> power()
            }
            return node
        }

        private fun power(): Node {
            val base = primary()
            if (!accept("^")) return base
            // 'a ^ b ^ c ...' recurses through unary(): count it like the other nesting levels.
            enter()
            val result = fold(Pow(base, unary()))
            depth--
            return result
        }

        private fun primary(): Node {
            val tok = peek
            when (tok.kind) {
                Tok.NUM -> {
                    index++
                    return Num(tok.number)
                }
                Tok.STR -> {
                    index++
                    return Str(tok.text)
                }
                Tok.NAME -> {
                    index++
                    if (peek.isPunct("(")) return call(tok)
                    return when (tok.text) {
                        "true" -> Num(1.0)
                        "false" -> Num(0.0)
                        else -> {
                            variables += tok.text
                            Var(tok.text)
                        }
                    }
                }
                Tok.PUNCT -> if (tok.text == "(") {
                    index++
                    val inner = ternary()
                    expect(")", "expected ')'")
                    return inner
                }
                Tok.END -> throw parseError(src, "unexpected end of formula", tok.pos)
            }
            throw parseError(src, "unexpected ${tok.describe()}", tok.pos)
        }

        private fun call(nameTok: Token): Node {
            val name = nameTok.text
            index++ // '('
            val args = ArrayList<Node>()
            if (!accept(")")) {
                while (true) {
                    args += ternary()
                    if (accept(")")) break
                    expect(",", "expected ',' or ')' in the call to $name()")
                }
            }
            functions += name
            calls.getOrPut(name) { LinkedHashSet() } += args.size
            args.forEachIndexed { i, arg -> if (arg is Str) literals.getOrPut(name) { LinkedHashSet() } += i to arg.value }
            return builtin(name, args, nameTok.pos) ?: Call(name, args.toTypedArray())
        }

        /** Builds a built-in function node (checking its arity), or returns null for context functions. */
        private fun builtin(name: String, a: List<Node>, pos: Int): Node? {
            fun arity(expected: Int) {
                if (a.size != expected) {
                    val s = if (expected == 1) "" else "s"
                    throw parseError(src, "$name() takes $expected argument$s but got ${a.size}", pos)
                }
            }
            return when (name) {
                "min", "max" -> {
                    if (a.isEmpty()) throw parseError(src, "$name() needs at least 1 argument", pos)
                    if (name == "min") MinFn(a.toTypedArray()) else MaxFn(a.toTypedArray())
                }
                "floor" -> { arity(1); FloorFn(a[0]) }
                "ceil" -> { arity(1); CeilFn(a[0]) }
                "round" -> { arity(1); RoundFn(a[0]) }
                "abs" -> { arity(1); AbsFn(a[0]) }
                "sqrt" -> { arity(1); SqrtFn(a[0]) }
                "pow" -> { arity(2); fold(PowFn(a[0], a[1])) }
                "clamp" -> { arity(3); ClampFn(a[0], a[1], a[2]) }
                "if" -> { arity(3); Cond(a[0], a[1], a[2]) }
                "random" -> when (a.size) {
                    0 -> Random0()
                    2 -> Random2(a[0], a[1])
                    else -> throw parseError(src, "random() takes 0 or 2 arguments but got ${a.size}", pos)
                }
                "randint" -> { arity(2); RandInt(a[0], a[1]) }
                "chance" -> { arity(1); ChanceFn(a[0]) }
                else -> null
            }
        }

        /** Pre-computes pure arithmetic on literals (e.g. "1 / 24", "-5"). */
        private fun fold(node: Node): Node {
            val literal = when (node) {
                is Neg -> node.x is Num
                is Add -> node.a is Num && node.b is Num
                is Sub -> node.a is Num && node.b is Num
                is Mul -> node.a is Num && node.b is Num
                is Div -> node.a is Num && node.b is Num
                is Mod -> node.a is Num && node.b is Num
                is Pow -> node.a is Num && node.b is Num
                is PowFn -> node.a is Num && node.b is Num
                else -> false
            }
            return if (literal) Num(node.num(FoldContext, "")) else node
        }

        private companion object {
            const val MAX_DEPTH = 200
            /** Deepest evaluation tree accepted (far beyond any real formula, far below the JVM stack limit). */
            const val MAX_TREE_DEPTH = 500
        }
    }

    /** Context used only for constant folding (folded nodes never read variables or randomness). */
    object FoldContext : ExprContext {
        override fun variable(name: String): Any? = null
        override val random: Random get() = Random.Default
    }
}
