package com.gekkotron.oikos.state.template

internal class UnsupportedTemplate(message: String) : Exception(message)

internal class TemplateEvalError(message: String) : Exception(message)

/** Jinja "undefined": a missing variable or key. Rescued by `default`, rendered as "" in text. */
internal object Undefined

/** Bound to `value_json` when the payload is not JSON; any use raises an evaluation error. */
internal object JsonFailure

internal sealed interface Expression {
    data class Literal(val value: Any?) : Expression
    data class Variable(val name: String) : Expression
    data class Access(val target: Expression, val key: Any) : Expression
    data class Filtered(val target: Expression, val name: String, val args: List<Any?>) : Expression
    data class And(val left: Expression, val right: Expression) : Expression
    data class Or(val left: Expression, val right: Expression) : Expression
    data class Not(val operand: Expression) : Expression

    /** `then if condition else otherwise`; a missing else is undefined. */
    data class IfElse(val then: Expression, val condition: Expression, val otherwise: Expression?) : Expression

    /** `target is [not] none|defined|undefined`. */
    data class Test(val target: Expression, val name: String, val negated: Boolean) : Expression
    data class Eq(val left: Expression, val right: Expression) : Expression

    /** `> < >= <=` between two numbers or two strings. */
    data class Compare(val left: Expression, val op: String, val right: Expression) : Expression

    /** Binary `+ - * /`; `/` is true division. */
    data class Arithmetic(val left: Expression, val op: Char, val right: Expression) : Expression
    data class In(val key: String, val target: Expression) : Expression

    /** `{"key": expr, …}` with string-literal keys, in source order. */
    data class DictLiteral(val entries: List<Pair<String, Expression>>) : Expression
}

internal sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Expr(val expression: Expression) : Segment

    /** `{% if cond %}…{% else %}…{% endif %}`, not nested; branches hold text and expressions only. */
    data class If(val condition: Expression, val then: List<Segment>, val otherwise: List<Segment>) : Segment
}

internal sealed interface Token {
    data class Ident(val name: String) : Token
    data class Num(val value: Any) : Token
    data class Str(val value: String) : Token
    data class Sym(val c: Char) : Token
    data class Op(val op: String) : Token
}

internal object TemplateParser {
    val TESTS = setOf("none", "defined", "undefined")
    val FILTERS = setOf("int", "float", "round", "lower", "upper", "default", "string", "truncate", "tojson")

    fun parse(template: String): List<Segment> {
        if ("{#" in template) throw UnsupportedTemplate("comments are not supported")
        val segments = mutableListOf<Segment>()
        val open = ArrayDeque<OpenIf>()
        val out = { segment: Segment -> (open.lastOrNull()?.current ?: segments).add(segment) }
        var i = 0
        while (i < template.length) {
            val next = listOf(template.indexOf("{{", i), template.indexOf("{%", i)).filter { it >= 0 }.minOrNull()
            if (next == null) {
                out(Segment.Text(template.substring(i)))
                break
            }
            if (next > i) out(Segment.Text(template.substring(i, next)))
            val isStatement = template[next + 1] == '%'
            val close = closingIndex(template, next + 2, isStatement)
            if (close < 0) throw UnsupportedTemplate("unclosed '${template.substring(next, next + 2)}'")
            val body = template.substring(next + 2, close)
            if (!isStatement) {
                out(Segment.Expr(ExpressionParser(tokenize(body)).parseAll()))
            } else {
                val tokens = tokenize(body)
                when ((tokens.firstOrNull() as? Token.Ident)?.name) {
                    "if" -> open.addLast(OpenIf(ExpressionParser(tokens.drop(1)).parseAll()))
                    "elif" -> {
                        val o = open.lastOrNull() ?: throw UnsupportedTemplate("'elif' outside 'if'")
                        if (o.inElse) throw UnsupportedTemplate("'elif' after 'else'")
                        o.branches += OpenIf.Branch(ExpressionParser(tokens.drop(1)).parseAll())
                    }
                    "else" -> {
                        val o = open.lastOrNull() ?: throw UnsupportedTemplate("'else' outside 'if'")
                        if (tokens.size != 1 || o.inElse) throw UnsupportedTemplate("unexpected 'else'")
                        o.inElse = true
                    }
                    "endif" -> {
                        val o = open.removeLastOrNull() ?: throw UnsupportedTemplate("'endif' outside 'if'")
                        if (tokens.size != 1) throw UnsupportedTemplate("unexpected tokens after 'endif'")
                        out(o.build())
                    }
                    else -> throw UnsupportedTemplate("only if/elif/else/endif statements are supported")
                }
            }
            i = close + 2
        }
        if (open.isNotEmpty()) throw UnsupportedTemplate("missing 'endif'")
        return segments
    }

    /**
     * Where the `{{`/`{%` opened before [from] closes: the first `}}` (or `%}`) outside string literals and,
     * for expressions, outside dict-literal braces, so `{{ {"a": {"b": 1}} | tojson }}` closes at the end.
     */
    private fun closingIndex(template: String, from: Int, isStatement: Boolean): Int {
        var depth = 0
        var quote: Char? = null
        var i = from
        while (i < template.length) {
            val c = template[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                isStatement -> if (c == '%' && template.getOrNull(i + 1) == '}') return i
                c == '{' -> depth++
                c == '}' && depth > 0 -> depth--
                c == '}' && template.getOrNull(i + 1) == '}' -> return i
            }
            i++
        }
        return -1
    }

    /** One open `{% if %}`, its `{% elif %}` branches in order, and an optional `{% else %}` tail. */
    private class OpenIf(condition: Expression) {
        class Branch(val condition: Expression) {
            val body = mutableListOf<Segment>()
        }

        val branches = mutableListOf(Branch(condition))
        val otherwise = mutableListOf<Segment>()
        var inElse = false
        val current: MutableList<Segment> get() = if (inElse) otherwise else branches.last().body

        /** Folds the elif chain into nested [Segment.If]s, innermost `otherwise` first. */
        fun build(): Segment.If {
            var tail: List<Segment> = otherwise
            for (branch in branches.asReversed()) {
                tail = listOf(Segment.If(branch.condition, branch.body, tail))
            }
            return tail.single() as Segment.If
        }
    }

    private fun tokenize(src: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out += Token.Ident(src.substring(start, i))
                }
                c.isDigit() || (c == '-' && i + 1 < src.length && src[i + 1].isDigit() && expectsOperand(out.lastOrNull())) -> {
                    val start = i
                    i++
                    while (i < src.length && src[i].isDigit()) i++
                    var fractional = false
                    if (i + 1 < src.length && src[i] == '.' && src[i + 1].isDigit()) {
                        fractional = true
                        i++
                        while (i < src.length && src[i].isDigit()) i++
                    }
                    val text = src.substring(start, i)
                    out += Token.Num(if (fractional) text.toDouble() else text.toLongOrNull() ?: text.toDouble())
                }
                c == '\'' || c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (true) {
                        if (i >= src.length) throw UnsupportedTemplate("unterminated string")
                        val ch = src[i]
                        if (ch == c) {
                            i++
                            break
                        }
                        if (ch == '\\' && i + 1 < src.length) {
                            sb.append(src[i + 1])
                            i += 2
                        } else {
                            sb.append(ch)
                            i++
                        }
                    }
                    out += Token.Str(sb.toString())
                }
                c in ".[]|(),{}:" -> {
                    out += Token.Sym(c)
                    i++
                }
                (c == '=' || c == '!') && i + 1 < src.length && src[i + 1] == '=' -> {
                    out += Token.Op("$c=")
                    i += 2
                }
                c == '<' || c == '>' -> {
                    val orEqual = i + 1 < src.length && src[i + 1] == '='
                    out += Token.Op(if (orEqual) "$c=" else "$c")
                    i += if (orEqual) 2 else 1
                }
                c in "+-*/" -> {
                    out += Token.Op("$c")
                    i++
                }
                else -> throw UnsupportedTemplate("unsupported syntax '$c'")
            }
        }
        return out
    }

    private val OPERAND_KEYWORDS = setOf("if", "else", "and", "or", "not", "in", "is")

    /** A `-` before a digit is a negative literal only where an operand is expected, else it subtracts. */
    private fun expectsOperand(previous: Token?): Boolean = when (previous) {
        null, is Token.Op -> true
        is Token.Sym -> previous.c in "(,[|:"
        is Token.Ident -> previous.name in OPERAND_KEYWORDS
        else -> false
    }
}

private class ExpressionParser(private val tokens: List<Token>) {
    private var pos = 0

    private fun peek(): Token? = tokens.getOrNull(pos)
    private fun next(): Token = tokens.getOrNull(pos++) ?: throw UnsupportedTemplate("unexpected end of expression")
    private fun isSym(c: Char) = (peek() as? Token.Sym)?.c == c
    private fun expectSym(c: Char) {
        if ((next() as? Token.Sym)?.c != c) throw UnsupportedTemplate("expected '$c'")
    }

    /** Full expression grammar: inline if-else < or < and < not < comparison < additive < multiplicative < filter < postfix. */
    fun parseAll(): Expression {
        if (tokens.isEmpty()) throw UnsupportedTemplate("empty expression")
        val expr = parseInlineIf()
        peek()?.let { throw UnsupportedTemplate("unsupported expression near $it") }
        return expr
    }

    private fun isIdent(name: String) = (peek() as? Token.Ident)?.name == name

    private fun parseInlineIf(): Expression {
        val then = parseOr()
        if (!isIdent("if")) return then
        next()
        val condition = parseOr()
        val otherwise = if (isIdent("else")) {
            next()
            parseInlineIf()
        } else {
            null
        }
        return Expression.IfElse(then, condition, otherwise)
    }

    private fun parseOr(): Expression {
        var expr = parseAnd()
        while (isIdent("or")) {
            next()
            expr = Expression.Or(expr, parseAnd())
        }
        return expr
    }

    private fun parseAnd(): Expression {
        var expr = parseNot()
        while (isIdent("and")) {
            next()
            expr = Expression.And(expr, parseNot())
        }
        return expr
    }

    private fun parseNot(): Expression {
        if (!isIdent("not")) return parseComparison()
        next()
        return Expression.Not(parseNot())
    }

    private fun parseComparison(): Expression {
        val left = parseAdditive()
        val op = (peek() as? Token.Op)?.op
        return when {
            op == "==" -> {
                next()
                Expression.Eq(left, parseAdditive())
            }
            op == "!=" -> {
                next()
                Expression.Not(Expression.Eq(left, parseAdditive()))
            }
            op in ORDERING -> {
                next()
                Expression.Compare(left, op!!, parseAdditive())
            }
            isIdent("in") -> {
                next()
                if (left !is Expression.Literal || left.value !is String) {
                    throw UnsupportedTemplate("'in' requires a string literal on its left")
                }
                Expression.In(left.value, parseAdditive())
            }
            isIdent("is") -> {
                next()
                val negated = isIdent("not").also { if (it) next() }
                val name = (next() as? Token.Ident)?.name
                if (name !in TemplateParser.TESTS) throw UnsupportedTemplate("test '$name' is not supported")
                Expression.Test(left, name!!, negated)
            }
            else -> left
        }
    }

    private fun parseAdditive(): Expression {
        var expr = parseMultiplicative()
        while (true) {
            val op = (peek() as? Token.Op)?.op?.takeIf { it == "+" || it == "-" } ?: return expr
            next()
            expr = Expression.Arithmetic(expr, op[0], parseMultiplicative())
        }
    }

    private fun parseMultiplicative(): Expression {
        var expr = parseFiltered()
        while (true) {
            val op = (peek() as? Token.Op)?.op?.takeIf { it == "*" || it == "/" } ?: return expr
            next()
            expr = Expression.Arithmetic(expr, op[0], parseFiltered())
        }
    }

    private fun parseFiltered(): Expression {
        var expr = parsePostfix(parsePrimary())
        while (isSym('|')) {
            next()
            val name = (next() as? Token.Ident)?.name ?: throw UnsupportedTemplate("expected a filter name")
            if (name !in TemplateParser.FILTERS) throw UnsupportedTemplate("filter '$name' is not supported")
            val args = mutableListOf<Any?>()
            if (isSym('(')) {
                next()
                if (!isSym(')')) {
                    args += literal(next())
                    while (isSym(',')) {
                        next()
                        args += literal(next())
                    }
                }
                expectSym(')')
            }
            expr = Expression.Filtered(expr, name, args)
        }
        return expr
    }

    private fun parsePrimary(): Expression = when (val t = next()) {
        is Token.Ident -> keywordLiteral(t.name)?.let { Expression.Literal(it.value) } ?: Expression.Variable(t.name)
        is Token.Num -> Expression.Literal(t.value)
        is Token.Str -> Expression.Literal(t.value)
        is Token.Sym -> when (t.c) {
            '(' -> parseInlineIf().also { expectSym(')') }
            '{' -> parseDict()
            else -> throw UnsupportedTemplate("unexpected '${t.c}'")
        }
        is Token.Op -> throw UnsupportedTemplate("unexpected '${t.op}'")
    }

    /** After the opening `{`: `"key": expr` entries separated by commas, then `}`. */
    private fun parseDict(): Expression {
        val entries = mutableListOf<Pair<String, Expression>>()
        if (!isSym('}')) {
            do {
                if (entries.isNotEmpty()) next()
                val key = (next() as? Token.Str)?.value ?: throw UnsupportedTemplate("dict keys must be string literals")
                expectSym(':')
                entries += key to parseInlineIf()
            } while (isSym(','))
        }
        expectSym('}')
        return Expression.DictLiteral(entries)
    }

    private fun parsePostfix(start: Expression): Expression {
        var e = start
        while (true) {
            e = when {
                isSym('.') -> {
                    next()
                    when (val k = next()) {
                        is Token.Ident -> Expression.Access(e, k.name)
                        is Token.Num -> Expression.Access(e, k.value)
                        else -> throw UnsupportedTemplate("expected an attribute name")
                    }
                }
                isSym('[') -> {
                    next()
                    val key: Any = when (val k = next()) {
                        is Token.Str -> k.value
                        is Token.Num -> k.value
                        else -> throw UnsupportedTemplate("only literal keys are supported in []")
                    }
                    expectSym(']')
                    Expression.Access(e, key)
                }
                else -> return e
            }
        }
    }

    private fun literal(t: Token): Any? = when (t) {
        is Token.Str -> t.value
        is Token.Num -> t.value
        is Token.Ident -> keywordLiteral(t.name)?.value ?: throw UnsupportedTemplate("filter arguments must be literals")
        is Token.Sym -> throw UnsupportedTemplate("filter arguments must be literals")
        is Token.Op -> throw UnsupportedTemplate("filter arguments must be literals")
    }

    private class Keyword(val value: Any?)

    private companion object {
        val ORDERING = setOf(">", "<", ">=", "<=")
    }

    private fun keywordLiteral(name: String): Keyword? = when (name) {
        "true", "True" -> Keyword(true)
        "false", "False" -> Keyword(false)
        "none", "None" -> Keyword(null)
        else -> null
    }
}
