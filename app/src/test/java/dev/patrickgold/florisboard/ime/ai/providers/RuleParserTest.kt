package dev.patrickgold.florisboard.ime.ai.providers

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RuleParser (parse + evaluate) and RuleExpr AST.
 *
 * Covers:
 *   1. Parse string, int, bool literals
 *   2. Parse dotted-path identifiers
 *   3. Parse comparison operators (==, !=, >, <)
 *   4. Parse boolean operators (&&, ||)
 *   5. Parenthesized expressions and operator precedence
 *   6. Evaluate with RuleContext — trigger fields
 *   7. Evaluate with RuleContext — provider health fields
 *   8. Evaluate with empty/missing context (identifier resolves to false)
 *   9. Error handling — malformed expressions and unclosed strings
 *  10. Comments (#) in expressions
 *  11. String escape sequences
 */
class RuleParserTest {

    private lateinit var parser: RuleParser

    @Before
    fun setup() {
        parser = RuleParser()
    }

    // ── Test 1: Parse literals ───────────────────────────────────────────

    @Test
    fun `parse string literal`() {
        val expr = parser.parse("'hello world'")
        assertTrue("Expected Str literal", expr is Expr.Str)
        assertEquals("hello world", (expr as Expr.Str).value)

        val expr2 = parser.parse("\"double quoted\"")
        assertTrue("Expected Str literal", expr2 is Expr.Str)
        assertEquals("double quoted", (expr2 as Expr.Str).value)
    }

    @Test
    fun `parse int literal`() {
        val expr = parser.parse("42")
        assertTrue("Expected Int literal", expr is Expr.Int)
        assertEquals(42, (expr as Expr.Int).value)
    }

    @Test
    fun `parse bool literal`() {
        val trueExpr = parser.parse("true")
        assertTrue("Expected Bool literal", trueExpr is Expr.Bool)
        assertTrue((trueExpr as Expr.Bool).value)

        val falseExpr = parser.parse("false")
        assertTrue("Expected Bool literal", falseExpr is Expr.Bool)
        assertFalse((falseExpr as Expr.Bool).value)
    }

    // ── Test 2: Parse dotted-path identifiers ────────────────────────────

    @Test
    fun `parse dotted path identifier`() {
        val expr = parser.parse("trigger.pipeline")
        assertTrue("Expected Ident", expr is Expr.Ident)
        assertEquals(listOf("trigger", "pipeline"), (expr as Expr.Ident).path)
    }

    @Test
    fun `parse deep dotted path identifier`() {
        val expr = parser.parse("provider.local.unreachable")
        assertTrue("Expected Ident", expr is Expr.Ident)
        assertEquals(listOf("provider", "local", "unreachable"), (expr as Expr.Ident).path)
    }

    @Test
    fun `parse simple identifier`() {
        val expr = parser.parse("budget")
        assertTrue("Expected Ident", expr is Expr.Ident)
        assertEquals(listOf("budget"), (expr as Expr.Ident).path)
    }

    @Test
    fun `parse identifier with underscore`() {
        val expr = parser.parse("trigger.maxTokens")
        assertTrue("Expected Ident", expr is Expr.Ident)
        assertEquals(listOf("trigger", "maxTokens"), (expr as Expr.Ident).path)
    }

    // ── Test 3: Parse comparison operators ────────────────────────────────

    @Test
    fun `parse equality comparison`() {
        val expr = parser.parse("trigger.pipeline == 'tot'")
        assertTrue("Expected Eq", expr is Expr.Eq)
        val eq = expr as Expr.Eq
        assertTrue("Left should be Ident", eq.left is Expr.Ident)
        assertTrue("Right should be Str", eq.right is Expr.Str)
        assertEquals(listOf("trigger", "pipeline"), (eq.left as Expr.Ident).path)
        assertEquals("tot", (eq.right as Expr.Str).value)
    }

    @Test
    fun `parse inequality comparison`() {
        val expr = parser.parse("trigger.pipeline != 'cot'")
        assertTrue("Expected Neq", expr is Expr.Neq)
    }

    @Test
    fun `parse greater-than comparison`() {
        val expr = parser.parse("trigger.maxTokens > 1024")
        assertTrue("Expected Gt", expr is Expr.Gt)
        val gt = expr as Expr.Gt
        assertTrue("Left should be Ident", gt.left is Expr.Ident)
        assertTrue("Right should be Int", gt.right is Expr.Int)
        assertEquals(listOf("trigger", "maxTokens"), (gt.left as Expr.Ident).path)
        assertEquals(1024, (gt.right as Expr.Int).value)
    }

    @Test
    fun `parse less-than comparison`() {
        val expr = parser.parse("trigger.maxTokens < 500")
        assertTrue("Expected Lt", expr is Expr.Lt)
    }

    // ── Test 4: Parse boolean operators ────────────────────────────────────

    @Test
    fun `parse logical AND`() {
        val expr = parser.parse("trigger.pipeline == 'tot' && trigger.maxTokens > 500")
        assertTrue("Expected And", expr is Expr.And)
        val and = expr as Expr.And
        assertTrue("Left should be Eq", and.left is Expr.Eq)
        assertTrue("Right should be Gt", and.right is Expr.Gt)
    }

    @Test
    fun `parse logical OR`() {
        val expr = parser.parse("provider.local.unreachable || provider.gemini.unreachable")
        assertTrue("Expected Or", expr is Expr.Or)
    }

    @Test
    fun `parse chained AND`() {
        val expr = parser.parse("a == 'x' && b == 'y' && c > 1")
        assertTrue("Expected And", expr is Expr.And)
        // Left of outer And is itself an And (left-associative)
        val and = expr as Expr.And
        assertTrue("Right should be Gt", and.right is Expr.Gt)
        assertTrue("Left should be And", and.left is Expr.And)
    }

    @Test
    fun `parse OR with higher precedence than AND`() {
        // Grammar: AND binds tighter than OR, so "a || b && c" parses as "a || (b && c)"
        val expr = parser.parse("a > 0 || b < 5 && c == 1")
        // OR is the root
        assertTrue("Expected Or at root", expr is Expr.Or)
        val or = expr as Expr.Or
        // Right should be And (b < 5 && c == 1)
        assertTrue("Right of OR should be And", or.right is Expr.And)
    }

    // ── Test 5: Parenthesized expressions ─────────────────────────────────

    @Test
    fun `parse parenthesized expression`() {
        val expr = parser.parse("(trigger.pipeline == 'tot')")
        assertTrue("Parenthesized should be Eq", expr is Expr.Eq)
    }

    @Test
    fun `parentheses override precedence`() {
        // Without parens: "a == 'x' || b == 'y' && c == 'z'" parses as a || (b && c)
        // With parens: "(a == 'x' || b == 'y') && c == 'z'" parses as (a || b) && c
        val expr = parser.parse("(a == 'x' || b == 'y') && c == 'z'")
        assertTrue("Root should be And", expr is Expr.And)
        val and = expr as Expr.And
        assertTrue("Left should be Or (parenthesized)", and.left is Expr.Or)
        assertTrue("Right should be Eq", and.right is Expr.Eq)
    }

    @Test
    fun `parse nested parentheses`() {
        val expr = parser.parse("((trigger.pipeline == 'tot'))")
        assertTrue("Nested parens should still be Eq", expr is Expr.Eq)
    }

    // ── Test 6: Evaluate with RuleContext — trigger fields ────────────────

    @Test
    fun `evaluate trigger pipeline equality`() {
        val expr = parser.parse("trigger.pipeline == 'tot'")
        val context = RuleContext(triggerPipeline = "tot")
        assertTrue("Pipeline == 'tot' should be true", parser.evaluate(expr, context))

        val context2 = RuleContext(triggerPipeline = "cot")
        assertFalse("Pipeline == 'tot' should be false for cot", parser.evaluate(expr, context2))
    }

    @Test
    fun `evaluate trigger pipeline inequality`() {
        val expr = parser.parse("trigger.pipeline != 'cot'")
        val context = RuleContext(triggerPipeline = "tot")
        assertTrue("Pipeline != 'cot' should be true for tot", parser.evaluate(expr, context))

        val context2 = RuleContext(triggerPipeline = "cot")
        assertFalse("Pipeline != 'cot' should be false for cot", parser.evaluate(expr, context2))
    }

    @Test
    fun `evaluate trigger maxTokens greater-than`() {
        val expr = parser.parse("trigger.maxTokens > 1024")
        assertTrue("maxTokens > 1024 with 2048", parser.evaluate(expr, RuleContext(triggerMaxTokens = 2048)))
        assertFalse("maxTokens > 1024 with 512", parser.evaluate(expr, RuleContext(triggerMaxTokens = 512)))
        assertFalse("maxTokens > 1024 with 1024", parser.evaluate(expr, RuleContext(triggerMaxTokens = 1024)))
    }

    @Test
    fun `evaluate trigger maxTokens less-than`() {
        val expr = parser.parse("trigger.maxTokens < 500")
        assertTrue("maxTokens < 500 with 256", parser.evaluate(expr, RuleContext(triggerMaxTokens = 256)))
        assertFalse("maxTokens < 500 with 1024", parser.evaluate(expr, RuleContext(triggerMaxTokens = 1024)))
    }

    @Test
    fun `evaluate trigger budget`() {
        val expr = parser.parse("trigger.budget == 'cheap'")
        assertTrue("budget == 'cheap'", parser.evaluate(expr, RuleContext(triggerBudget = "cheap")))
        assertFalse("budget == 'cheap' with premium", parser.evaluate(expr, RuleContext(triggerBudget = "premium")))
    }

    // ── Test 7: Evaluate with RuleContext — provider health fields ────────

    @Test
    fun `evaluate provider unreachable`() {
        val expr = parser.parse("provider.local.unreachable")
        val healthy = RuleContext(providerHealth = mapOf("local" to HealthTracker.ProviderHealth(unreachable = false)))
        assertFalse("Healthy provider should not be unreachable", parser.evaluate(expr, healthy))

        val down = RuleContext(providerHealth = mapOf("local" to HealthTracker.ProviderHealth(unreachable = true)))
        assertTrue("Unreachable provider should be true", parser.evaluate(expr, down))
    }

    @Test
    fun `evaluate provider rateLimited`() {
        val expr = parser.parse("provider.gemini.rateLimited")
        val context = RuleContext(providerHealth = mapOf("gemini" to HealthTracker.ProviderHealth(rateLimited = true)))
        assertTrue("Rate-limited provider", parser.evaluate(expr, context))
    }

    @Test
    fun `evaluate provider avgLatencyMs`() {
        val expr = parser.parse("provider.local.avgLatencyMs > 500")
        val context = RuleContext(providerHealth = mapOf("local" to HealthTracker.ProviderHealth(avgLatencyMs = 600)))
        assertTrue("Latency > 500", parser.evaluate(expr, context))

        val context2 = RuleContext(providerHealth = mapOf("local" to HealthTracker.ProviderHealth(avgLatencyMs = 200)))
        assertFalse("Latency <= 500", parser.evaluate(expr, context2))
    }

    @Test
    fun `evaluate provider consecutiveFailures`() {
        val expr = parser.parse("provider.anthropic.consecutiveFailures > 5")
        val context = RuleContext(
            providerHealth = mapOf("anthropic" to HealthTracker.ProviderHealth(consecutiveFailures = 10))
        )
        assertTrue("Failures > 5", parser.evaluate(expr, context))
    }

    @Test
    fun `evaluate combined trigger and provider conditions with AND`() {
        val expr = parser.parse("trigger.pipeline == 'tot' && provider.local.unreachable")
        val context = RuleContext(
            triggerPipeline = "tot",
            providerHealth = mapOf("local" to HealthTracker.ProviderHealth(unreachable = true)),
        )
        assertTrue("tot AND local unreachable", parser.evaluate(expr, context))

        val context2 = RuleContext(
            triggerPipeline = "tot",
            providerHealth = mapOf("local" to HealthTracker.ProviderHealth(unreachable = false)),
        )
        assertFalse("tot AND local reachable", parser.evaluate(expr, context2))
    }

    @Test
    fun `evaluate combined conditions with OR`() {
        val expr = parser.parse("provider.local.unreachable || provider.gemini.unreachable")
        val context = RuleContext(
            providerHealth = mapOf(
                "local" to HealthTracker.ProviderHealth(unreachable = true),
                "gemini" to HealthTracker.ProviderHealth(unreachable = false),
            ),
        )
        assertTrue("local unreachable OR gemini unreachable", parser.evaluate(expr, context))

        val context2 = RuleContext(
            providerHealth = mapOf(
                "local" to HealthTracker.ProviderHealth(unreachable = false),
                "gemini" to HealthTracker.ProviderHealth(unreachable = false),
            ),
        )
        assertFalse("neither unreachable", parser.evaluate(expr, context2))
    }

    // ── Test 8: Evaluate with empty / missing context ─────────────────────

    @Test
    fun `missing identifier resolves to false`() {
        val expr = parser.parse("trigger.pipeline")
        val context = RuleContext() // pipeline not set
        assertFalse("Missing identifier should evaluate to false", parser.evaluate(expr, context))
    }

    @Test
    fun `unknown provider health field resolves to false`() {
        val expr = parser.parse("provider.unknown.unreachable")
        val context = RuleContext(providerHealth = mapOf("local" to HealthTracker.ProviderHealth()))
        assertFalse("Unknown provider should evaluate to false", parser.evaluate(expr, context))
    }

    @Test
    fun `bare true literal evaluates to true`() {
        assertTrue("bare true", parser.evaluate(parser.parse("true"), RuleContext()))
    }

    @Test
    fun `bare false literal evaluates to false`() {
        assertFalse("bare false", parser.evaluate(parser.parse("false"), RuleContext()))
    }

    @Test
    fun `non-zero integer literal evaluates to true`() {
        assertTrue("non-zero int", parser.evaluate(parser.parse("1"), RuleContext()))
    }

    @Test
    fun `zero integer literal evaluates to false`() {
        assertFalse("zero int", parser.evaluate(parser.parse("0"), RuleContext()))
    }

    // ── Test 9: Error handling ────────────────────────────────────────────

    @Test(expected = RuleParseException::class)
    fun `unclosed string throws exception`() {
        parser.parse("'unclosed string")
    }

    @Test(expected = RuleParseException::class)
    fun `unexpected token throws exception`() {
        parser.parse("trigger.pipeline == ")
    }

    @Test(expected = RuleParseException::class)
    fun `unexpected character throws exception`() {
        parser.parse("trigger.pipeline == 'tot' @")
    }

    @Test(expected = RuleParseException::class)
    fun `mismatched parenthesis throws exception`() {
        parser.parse("(trigger.pipeline == 'tot'")
    }

    // ── Test 10: Comments in expressions ──────────────────────────────────

    @Test
    fun `hash comments are ignored`() {
        val expr = parser.parse("trigger.pipeline == 'tot' # this is a comment")
        assertTrue("Comment should be stripped", expr is Expr.Eq)
    }

    @Test
    fun `hash comment after comparison`() {
        val expr = parser.parse("""
            trigger.maxTokens > 500 # larger tasks need stronger models
        """.trimIndent())
        assertTrue("Comment should be stripped", expr is Expr.Gt)
    }

    // ── Test 11: String escape sequences ──────────────────────────────────

    @Test
    fun `string with escaped quote`() {
        val expr = parser.parse("'it\\'s working'")
        assertTrue("Expected Str", expr is Expr.Str)
        assertEquals("it's working", (expr as Expr.Str).value)
    }

    @Test
    fun `string with escaped newline`() {
        val expr = parser.parse("'line1\\nline2'")
        assertTrue("Expected Str", expr is Expr.Str)
        assertEquals("line1\nline2", (expr as Expr.Str).value)
    }

    @Test
    fun `string with escaped backslash`() {
        val expr = parser.parse("'path\\\\to\\\\file'")
        assertTrue("Expected Str", expr is Expr.Str)
        assertEquals("path\\to\\file", (expr as Expr.Str).value)
    }

    // ── Test 12: Real-world routing rule examples from triggers.json ───────

    @Test
    fun `realworld tot pipeline routing rule`() {
        // From triggers.json: trigger.pipeline == 'tot' -> use anthropic
        val expr = parser.parse("trigger.pipeline == 'tot'")
        val context = RuleContext(triggerPipeline = "tot")
        assertTrue("tot pipeline should match", parser.evaluate(expr, context))
    }

    @Test
    fun `realworld maxTokens routing rule`() {
        // From triggers.json: trigger.maxTokens > 1024 -> use anthropic
        val expr = parser.parse("trigger.maxTokens > 1024")
        assertTrue("2048 > 1024", parser.evaluate(expr, RuleContext(triggerMaxTokens = 2048)))
        assertFalse("512 > 1024", parser.evaluate(expr, RuleContext(triggerMaxTokens = 512)))
    }

    @Test
    fun `realworld provider failover chain`() {
        // From triggers.json: provider.local.unreachable -> use gemini_1
        val expr = parser.parse("provider.local.unreachable")
        val context = RuleContext(
            providerHealth = mapOf("local" to HealthTracker.ProviderHealth(unreachable = true)),
        )
        assertTrue("local unreachable should trigger failover", parser.evaluate(expr, context))

        val expr2 = parser.parse("provider.gemini_1.rateLimited")
        val context2 = RuleContext(
            providerHealth = mapOf("gemini_1" to HealthTracker.ProviderHealth(rateLimited = true)),
        )
        assertTrue("gemini_1 rateLimited should trigger failover", parser.evaluate(expr2, context2))
    }

    @Test
    fun `realworld budget routing rule`() {
        // From triggers.json: trigger.budget == 'cheap' -> use deepseek
        val expr = parser.parse("trigger.budget == 'cheap'")
        assertTrue("cheap budget", parser.evaluate(expr, RuleContext(triggerBudget = "cheap")))
        assertFalse("premium budget", parser.evaluate(expr, RuleContext(triggerBudget = "premium")))
    }
}
