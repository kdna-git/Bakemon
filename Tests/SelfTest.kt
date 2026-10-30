import java.io.File

/**
 * Tiny dependency-free test harness. Run with `Bakemon.bat test`.
 * Each area has its own file with a `run...Tests()` function; this file only runs them and reports.
 */
object Check {
    var passed = 0
    var failed = 0
    private var section = ""

    fun section(name: String) {
        section = name
        println("-- $name")
    }

    fun ok(condition: Boolean, message: String) {
        if (condition) passed++ else {
            failed++
            println("  FAIL [$section] $message")
        }
    }

    fun eq(expected: Any?, actual: Any?, message: String) =
        ok(expected == actual, "$message: expected <$expected> but was <$actual>")

    fun near(expected: Double, actual: Double, message: String, eps: Double = 1e-9) =
        ok(Math.abs(expected - actual) <= eps, "$message: expected <$expected> but was <$actual>")

    /** Passes if [block] throws an exception of type [T]. */
    inline fun <reified T : Throwable> throws(message: String, block: () -> Unit) {
        try {
            block()
            ok(false, "$message: expected ${T::class.simpleName} but nothing was thrown")
        } catch (t: Throwable) {
            ok(t is T, "$message: expected ${T::class.simpleName} but got ${t::class.simpleName}: ${t.message}")
        }
    }

    /** Runs [block], counting an unexpected exception as one failure (so one broken area doesn't stop the rest). */
    fun guard(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failed++
            println("  FAIL [$name] crashed: $t")
            t.stackTrace.take(8).forEach { println("      at $it") }
        }
    }
}

fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: ".").absoluteFile
    Check.guard("json/expression") { runJsonExpressionTests() }
    Check.guard("setup") { runSetupTests(root) }
    Check.guard("engine") { runEngineTests(root) }
    Check.guard("ai") { runAiTests(root) }
    Check.guard("ui") { runUiTests(root) }
    Check.guard("creator") { runCreatorTests(root) }
    println()
    println("Passed: ${Check.passed}  Failed: ${Check.failed}")
    if (Check.failed > 0) System.exit(1)
}
