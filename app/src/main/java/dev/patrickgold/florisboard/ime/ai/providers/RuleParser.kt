package dev.patrickgold.florisboard.ime.ai.providers

/**
 * Hand-rolled recursive-descent parser and evaluator for the routing-rule DSL.
 *
 * Grammar (no Antlr, ~200 lines):
 *   expr     → or
 *   or       → and ('||' and)*
 *   and      → cmp ('&&' cmp)*
 *   cmp      → primary (('==' | '!=' | '>' | '<') primary)?
 *   primary  → IDENT | STRING | INT | BOOL | '(' expr ')'
 *
 * Tokens: IDENT, STRING (single/double quoted), INT, BOOL,
 *         OP_EQ, OP_NEQ, OP_GT, OP_LT, OP_AND, OP_OR,
 *         LPAREN, RPAREN, EOF.
 */
class RuleParser {

    // ── Parse ────────────────────────────────────────────────────────────

    fun parse(input: String): Expr {
        val tokens = tokenize(input)
        val parser = ParserState(tokens, 0)
        val result = parseExpr(parser)
        if (parser.pos < tokens.size) {
            throw RuleParseException("Unexpected token '${tokens[parser.pos].lexeme}' at position ${tokens[parser.pos].start}")
        }
        return result
    }

    // ── Evaluate ─────────────────────────────────────────────────────────

    /**
     * Evaluate an expression against a context.
     *
     * Identifiers resolve via the context's known values.
     * Identifiers not found in context resolve to Boolean false.
     */
    fun evaluate(expr: Expr, context: RuleContext): Boolean {
        return when (expr) {
            is Expr.Ident -> resolveIdent(expr, context)?.let { toBoolean(it) } ?: false
            is Expr.Str -> true  // a bare string is truthy
            is Expr.Int -> expr.value != 0
            is Expr.Bool -> expr.value
            is Expr.Eq -> {
                val l = resolveValue(expr.left, context)
                val r = resolveValue(expr.right, context)
                l == r
            }
            is Expr.Neq -> {
                val l = resolveValue(expr.left, context)
                val r = resolveValue(expr.right, context)
                l != r
            }
            is Expr.Gt -> {
                val l = resolveNumeric(expr.left, context)
                val r = resolveNumeric(expr.right, context)
                if (l != null && r != null) l > r else false
            }
            is Expr.Lt -> {
                val l = resolveNumeric(expr.left, context)
                val r = resolveNumeric(expr.right, context)
                if (l != null && r != null) l < r else false
            }
            is Expr.And -> evaluate(expr.left, context) && evaluate(expr.right, context)
            is Expr.Or -> evaluate(expr.left, context) || evaluate(expr.right, context)
        }
    }

    // ── Value resolution ─────────────────────────────────────────────────

    private fun resolveValue(expr: Expr, context: RuleContext): Any? {
        return when (expr) {
            is Expr.Ident -> resolveIdent(expr, context)
            is Expr.Str -> expr.value
            is Expr.Int -> expr.value
            is Expr.Bool -> expr.value
            else -> null
        }
    }

    private fun resolveIdent(expr: Expr.Ident, context: RuleContext): Any? {
        val path = expr.path
        return when {
            path.size >= 2 && path[0] == "trigger" -> resolveTriggerPath(path.drop(1), context)
            path.size >= 2 && path[0] == "provider" -> resolveProviderPath(path.drop(1), context)
            else -> null
        }
    }

    private fun resolveTriggerPath(segments: List<String>, context: RuleContext): Any? {
        return when {
            segments.size == 1 && segments[0] == "pipeline" -> context.triggerPipeline
            segments.size == 1 && segments[0] == "maxTokens" -> context.triggerMaxTokens
            segments.size == 1 && segments[0] == "budget" -> context.triggerBudget
            else -> null
        }
    }

    private fun resolveProviderPath(segments: List<String>, context: RuleContext): Any? {
        if (segments.size < 2) return null
        val providerId = segments[0]
        val property = segments[1]
        val health = context.providerHealth[providerId] ?: return null
        return when (property) {
            "unreachable" -> health.unreachable
            "rateLimited" -> health.rateLimited
            "avgLatencyMs" -> health.avgLatencyMs
            "consecutiveFailures" -> health.consecutiveFailures
            else -> null
        }
    }

    private fun resolveNumeric(expr: Expr, context: RuleContext): Int? {
        return when (expr) {
            is Expr.Int -> expr.value
            is Expr.Ident -> (resolveIdent(expr, context) as? Number)?.toInt()
            else -> null
        }
    }

    private fun toBoolean(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is String -> value.isNotBlank()
            is Number -> value.toInt() != 0
            null -> false
            else -> true
        }
    }

    // ── Tokenizer ────────────────────────────────────────────────────────

    private data class Token(val type: TokenType, val lexeme: String, val start: Int)

    private enum class TokenType {
        IDENT, STRING, INT, BOOL,
        OP_EQ, OP_NEQ, OP_GT, OP_LT, OP_AND, OP_OR,
        LPAREN, RPAREN, EOF
    }

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val c = input[i]

            // Skip whitespace
            if (c.isWhitespace()) { i++; continue }

            // Single-line comment (# ...)
            if (c == '#') {
                while (i < input.length && input[i] != '\n') i++
                continue
            }

            when {
                c == '(' -> { tokens.add(Token(TokenType.LPAREN, "(", i)); i++ }
                c == ')' -> { tokens.add(Token(TokenType.RPAREN, ")", i)); i++ }
                c == '\'' || c == '"' -> {
                    val quote = c
                    val start = i
                    i++ // skip opening quote
                    val sb = StringBuilder()
                    while (i < input.length && input[i] != quote) {
                        if (input[i] == '\\' && i + 1 < input.length) {
                            i++
                            sb.append(escapeChar(input[i]))
                        } else {
                            sb.append(input[i])
                        }
                        i++
                    }
                    if (i >= input.length) throw RuleParseException("Unclosed string starting at $start")
                    i++ // skip closing quote
                    tokens.add(Token(TokenType.STRING, sb.toString(), start))
                }
                c == '=' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens.add(Token(TokenType.OP_EQ, "==", i)); i += 2
                }
                c == '!' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens.add(Token(TokenType.OP_NEQ, "!=", i)); i += 2
                }
                c == '>' -> { tokens.add(Token(TokenType.OP_GT, ">", i)); i++ }
                c == '<' -> { tokens.add(Token(TokenType.OP_LT, "<", i)); i++ }
                c == '&' && i + 1 < input.length && input[i + 1] == '&' -> {
                    tokens.add(Token(TokenType.OP_AND, "&&", i)); i += 2
                }
                c == '|' && i + 1 < input.length && input[i + 1] == '|' -> {
                    tokens.add(Token(TokenType.OP_OR, "||", i)); i += 2
                }
                c.isLetter() || c == '_' || c == '.' -> {
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_' || input[i] == '.')) i++
                    val word = input.substring(start, i)
                    val type = when (word) {
                        "true" -> TokenType.BOOL
                        "false" -> TokenType.BOOL
                        else -> TokenType.IDENT
                    }
                    tokens.add(Token(type, word, start))
                }
                c.isDigit() -> {
                    val start = i
                    while (i < input.length && input[i].isDigit()) i++
                    tokens.add(Token(TokenType.INT, input.substring(start, i), start))
                }
                else -> throw RuleParseException("Unexpected character '$c' at position $i")
            }
        }
        tokens.add(Token(TokenType.EOF, "", input.length))
        return tokens
    }

    private fun escapeChar(c: Char): Char = when (c) {
        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
        '\\' -> '\\'; '\'' -> '\''; '"' -> '"'
        else -> c
    }

    // ── Parser (recursive descent) ───────────────────────────────────────

    private class ParserState(val tokens: List<Token>, var pos: Int)

    private fun peek(state: ParserState): Token = state.tokens[state.pos]
    private fun advance(state: ParserState): Token = state.tokens[state.pos++]
    private fun expect(state: ParserState, type: TokenType): Token {
        val t = peek(state)
        if (t.type != type) throw RuleParseException("Expected $type but got '${t.lexeme}' at position ${t.start}")
        return advance(state)
    }

    // expr → or
    private fun parseExpr(state: ParserState): Expr = parseOr(state)

    // or → and ('||' and)*
    private fun parseOr(state: ParserState): Expr {
        var left = parseAnd(state)
        while (peek(state).type == TokenType.OP_OR) {
            advance(state)
            val right = parseAnd(state)
            left = Expr.Or(left, right)
        }
        return left
    }

    // and → cmp ('&&' cmp)*
    private fun parseAnd(state: ParserState): Expr {
        var left = parseCmp(state)
        while (peek(state).type == TokenType.OP_AND) {
            advance(state)
            val right = parseCmp(state)
            left = Expr.And(left, right)
        }
        return left
    }

    // cmp → primary (('==' | '!=' | '>' | '<') primary)?
    private fun parseCmp(state: ParserState): Expr {
        val left = parsePrimary(state)
        return when (peek(state).type) {
            TokenType.OP_EQ -> { advance(state); Expr.Eq(left, parsePrimary(state)) }
            TokenType.OP_NEQ -> { advance(state); Expr.Neq(left, parsePrimary(state)) }
            TokenType.OP_GT -> { advance(state); Expr.Gt(left, parsePrimary(state)) }
            TokenType.OP_LT -> { advance(state); Expr.Lt(left, parsePrimary(state)) }
            else -> left
        }
    }

    // primary → IDENT | STRING | INT | BOOL | '(' expr ')'
    private fun parsePrimary(state: ParserState): Expr {
        val token = advance(state)
        return when (token.type) {
            TokenType.IDENT -> Expr.Ident(token.lexeme)
            TokenType.STRING -> Expr.Str(token.lexeme)
            TokenType.INT -> Expr.Int(token.lexeme.toInt())
            TokenType.BOOL -> Expr.Bool(token.lexeme.toBoolean())
            TokenType.LPAREN -> {
                val expr = parseExpr(state)
                expect(state, TokenType.RPAREN)
                expr
            }
            else -> throw RuleParseException("Unexpected token '${token.lexeme}' at position ${token.start}")
        }
    }
}

class RuleParseException(message: String) : Exception(message)

/**
 * Context for evaluating routing rules.
 * Maps identifier paths to runtime values.
 */
data class RuleContext(
    val triggerPipeline: String? = null,
    val triggerMaxTokens: Int? = null,
    val triggerBudget: String? = null,
    val providerHealth: Map<String, HealthTracker.ProviderHealth> = emptyMap(),
)
