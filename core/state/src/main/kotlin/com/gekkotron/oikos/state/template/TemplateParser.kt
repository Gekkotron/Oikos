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
}

internal sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Expr(val expression: Expression) : Segment
}

internal sealed interface Token {
    data class Ident(val name: String) : Token
    data class Num(val value: Any) : Token
    data class Str(val value: String) : Token
    data class Sym(val c: Char) : Token
}

internal object TemplateParser {
    val FILTERS = setOf("int", "float", "round", "lower", "upper", "default")

    fun parse(template: String): List<Segment> {
        if ("{%" in template || "{#" in template) throw UnsupportedTemplate("statements and comments are not supported")
        val segments = mutableListOf<Segment>()
        var i = 0
        while (i < template.length) {
            val open = template.indexOf("{{", i)
            if (open < 0) {
                segments += Segment.Text(template.substring(i))
                break
            }
            if (open > i) segments += Segment.Text(template.substring(i, open))
            val close = template.indexOf("}}", open + 2)
            if (close < 0) throw UnsupportedTemplate("unclosed '{{'")
            segments += Segment.Expr(ExpressionParser(tokenize(template.substring(open + 2, close))).parseAll())
            i = close + 2
        }
        return segments
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
                c.isDigit() || (c == '-' && i + 1 < src.length && src[i + 1].isDigit()) -> {
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
                c in ".[]|()," -> {
                    out += Token.Sym(c)
                    i++
                }
                else -> throw UnsupportedTemplate("unsupported syntax '$c'")
            }
        }
        return out
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

    fun parseAll(): Expression {
        if (tokens.isEmpty()) throw UnsupportedTemplate("empty expression")
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
        peek()?.let { throw UnsupportedTemplate("unsupported expression near $it") }
        return expr
    }

    private fun parsePrimary(): Expression = when (val t = next()) {
        is Token.Ident -> keywordLiteral(t.name)?.let { Expression.Literal(it.value) } ?: Expression.Variable(t.name)
        is Token.Num -> Expression.Literal(t.value)
        is Token.Str -> Expression.Literal(t.value)
        is Token.Sym -> throw UnsupportedTemplate("unexpected '${t.c}'")
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
    }

    private class Keyword(val value: Any?)

    private fun keywordLiteral(name: String): Keyword? = when (name) {
        "true", "True" -> Keyword(true)
        "false", "False" -> Keyword(false)
        "none", "None" -> Keyword(null)
        else -> null
    }
}
