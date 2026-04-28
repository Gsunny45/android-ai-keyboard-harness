package dev.patrickgold.florisboard.ime.ai.providers

/**
 * AST nodes for the tiny routing-rule expression DSL.
 *
 * Grammar:
 *   expr     → or
 *   or       → and ('||' and)*
 *   and      → cmp ('&&' cmp)*
 *   cmp      → primary (('==' | '!=' | '>' | '<') primary)?
 *   primary  → IDENT | STRING | INT | BOOL | '(' expr ')'
 *
 * Identifiers are dotted paths like "trigger.pipeline" or
 * "provider.local.unreachable".
 */
sealed class Expr {

    /** A dotted-path identifier: "trigger.pipeline", "provider.local.unreachable" */
    data class Ident(val path: List<String>) : Expr() {
        constructor(name: String) : this(name.split("."))
    }

    /** String literal, e.g. 'tot' or "cheap" */
    data class Str(val value: String) : Expr()

    /** Integer literal */
    data class Int(val value: kotlin.Int) : Expr()

    /** Boolean literal */
    data class Bool(val value: kotlin.Boolean) : Expr()

    /** Equality: == */
    data class Eq(val left: Expr, val right: Expr) : Expr()

    /** Inequality: != */
    data class Neq(val left: Expr, val right: Expr) : Expr()

    /** Greater than: > */
    data class Gt(val left: Expr, val right: Expr) : Expr()

    /** Less than: < */
    data class Lt(val left: Expr, val right: Expr) : Expr()

    /** Logical AND: && */
    data class And(val left: Expr, val right: Expr) : Expr()

    /** Logical OR: || */
    data class Or(val left: Expr, val right: Expr) : Expr()
}
