package com.gekkotron.oikos.state.template

import kotlin.math.floor
import kotlin.math.pow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

sealed interface TemplateResult {
    /** String, Long, Double, Boolean, JsonObject/JsonArray, or null (undefined / none). */
    data class Value(val value: Any?) : TemplateResult
    data class Unsupported(val reason: String) : TemplateResult
    data class Error(val reason: String) : TemplateResult
}

object Templates {
    /** Evaluates [template] with `value` = [payload] and `value_json` = the parsed payload. */
    fun renderValue(template: String, payload: String): TemplateResult =
        render(template, mapOf("value" to payload, "value_json" to parseJsonOrFailure(payload)))

    fun render(template: String, variables: Map<String, Any?>): TemplateResult {
        val segments = try {
            TemplateParser.parse(template)
        } catch (e: UnsupportedTemplate) {
            return TemplateResult.Unsupported(e.message ?: "unsupported template")
        }
        return try {
            TemplateResult.Value(evaluate(segments, variables))
        } catch (e: TemplateEvalError) {
            TemplateResult.Error(e.message ?: "template error")
        }
    }
}

private fun parseJsonOrFailure(payload: String): Any = try {
    val element = Json.parseToJsonElement(payload)
    // kotlinx accepts bare words ("online") as unquoted primitives; JSON does not.
    val bareWord = element is JsonPrimitive && element !is JsonNull && !element.isString &&
        element.booleanOrNull == null && element.doubleOrNull == null
    if (bareWord) JsonFailure else element
} catch (e: SerializationException) {
    JsonFailure
}

private fun evaluate(segments: List<Segment>, vars: Map<String, Any?>): Any? {
    val expressions = segments.filterIsInstance<Segment.Expr>()
    val onlyWhitespaceAround = segments.all { it is Segment.Expr || (it is Segment.Text && it.text.isBlank()) }
    if (expressions.size == 1 && onlyWhitespaceAround) {
        return eval(expressions.single().expression, vars).let { if (it === Undefined) null else it }
    }
    return renderText(segments, vars)
}

private fun renderText(segments: List<Segment>, vars: Map<String, Any?>): String =
    segments.joinToString("") { segment ->
        when (segment) {
            is Segment.Text -> segment.text
            is Segment.Expr -> eval(segment.expression, vars).let { if (it === Undefined) "" else it.asPayloadString() ?: "None" }
            is Segment.If -> renderText(if (truthy(eval(segment.condition, vars))) segment.then else segment.otherwise, vars)
        }
    }

/** Python truthiness; undefined and none are false. */
private fun truthy(v: Any?): Boolean = when (v) {
    null, Undefined -> false
    is Boolean -> v
    is Long -> v != 0L
    is Double -> v != 0.0
    is String -> v.isNotEmpty()
    is JsonObject -> v.isNotEmpty()
    is JsonArray -> v.isNotEmpty()
    else -> true
}

/** JSON primitives become Kotlin values; objects and arrays are kept for further access. */
internal fun normalize(v: Any?): Any? = when (v) {
    is JsonNull -> null
    is JsonPrimitive -> if (v.isString) v.content else v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.content
    else -> v
}

private fun eval(e: Expression, vars: Map<String, Any?>): Any? = when (e) {
    is Expression.Literal -> e.value
    is Expression.Variable -> {
        if (e.name !in vars) {
            Undefined
        } else {
            val value = normalize(vars[e.name])
            if (value === JsonFailure) throw TemplateEvalError("payload is not valid JSON")
            value
        }
    }
    is Expression.Access -> access(eval(e.target, vars), e.key)
    is Expression.Filtered -> applyFilter(e.name, eval(e.target, vars), e.args)
    is Expression.And -> eval(e.left, vars).let { if (truthy(it)) eval(e.right, vars) else it }
    is Expression.Or -> eval(e.left, vars).let { if (truthy(it)) it else eval(e.right, vars) }
    is Expression.Not -> !truthy(eval(e.operand, vars))
    is Expression.IfElse ->
        if (truthy(eval(e.condition, vars))) eval(e.then, vars) else if (e.otherwise == null) Undefined else eval(e.otherwise, vars)
    is Expression.Test -> eval(e.target, vars).let { v ->
        when (e.name) {
            "none" -> v == null
            "defined" -> v !== Undefined
            else -> v === Undefined
        } != e.negated
    }
    is Expression.Eq -> equalsValue(eval(e.left, vars), eval(e.right, vars))
    is Expression.Compare -> compare(e.op, eval(e.left, vars), eval(e.right, vars))
    is Expression.Arithmetic -> arithmetic(e.op, eval(e.left, vars), eval(e.right, vars))
    is Expression.In -> containsKey(e.key, eval(e.target, vars))
}

/** `==`: strings compare to strings, numbers to numbers (0 == 0.0); undefined never equals anything. */
private fun equalsValue(a: Any?, b: Any?): Boolean {
    if (a === Undefined || b === Undefined) return false
    return when {
        a is String && b is String -> a == b
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> false
    }
}

/** `> < >= <=`: numbers with numbers, strings with strings; anything else is an error, as in Jinja. */
private fun compare(op: String, a: Any?, b: Any?): Boolean {
    val order = when {
        a is String && b is String -> a.compareTo(b)
        else -> {
            val x = strictNumber(a) ?: throw TemplateEvalError("cannot compare ${describe(a)} with '$op'")
            val y = strictNumber(b) ?: throw TemplateEvalError("cannot compare ${describe(b)} with '$op'")
            x.toDouble().compareTo(y.toDouble())
        }
    }
    return when (op) {
        ">" -> order > 0
        "<" -> order < 0
        ">=" -> order >= 0
        else -> order <= 0
    }
}

/** `+ - * /` on numbers (Long stays Long except for `/`), and `+` on two strings. */
private fun arithmetic(op: Char, a: Any?, b: Any?): Any {
    if (op == '+' && a is String && b is String) return a + b
    val x = strictNumber(a) ?: throw TemplateEvalError("cannot apply '$op' to ${describe(a)}")
    val y = strictNumber(b) ?: throw TemplateEvalError("cannot apply '$op' to ${describe(b)}")
    if (op == '/') {
        if (y.toDouble() == 0.0) throw TemplateEvalError("division by zero")
        return x.toDouble() / y.toDouble()
    }
    if (x is Long && y is Long) {
        return when (op) {
            '+' -> x + y
            '-' -> x - y
            else -> x * y
        }
    }
    val dx = x.toDouble()
    val dy = y.toDouble()
    return when (op) {
        '+' -> dx + dy
        '-' -> dx - dy
        else -> dx * dy
    }
}

/** Numbers only (booleans count, as in Python); strings are not coerced, unlike [looseNumber]. */
private fun strictNumber(v: Any?): Number? = when (v) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v
    is Boolean -> if (v) 1L else 0L
    else -> null
}

private fun describe(v: Any?): String = when (v) {
    Undefined -> "an undefined value"
    null -> "none"
    is String -> "a string"
    else -> "a ${v::class.simpleName}"
}

/** `<literal> in <expr>`: key membership, false unless the target is a JSON object. */
private fun containsKey(key: String, target: Any?): Boolean = target is JsonObject && key in target

private fun access(target: Any?, key: Any): Any? = when {
    target is JsonObject && key is String -> if (key in target) normalize(target[key]) else Undefined
    target is JsonArray && key is Long -> if (key >= 0 && key < target.size) normalize(target[key.toInt()]) else Undefined
    target is Map<*, *> && target.containsKey(key) -> normalize(target[key])
    else -> Undefined
}

private fun looseNumber(v: Any?): Number? = when (v) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v
    is Boolean -> if (v) 1L else 0L
    is String -> v.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull() }
    else -> null
}

private fun applyFilter(name: String, v: Any?, args: List<Any?>): Any? = when (name) {
    "default" -> if (v === Undefined || (args.getOrNull(1) == true && !truthy(v))) args.firstOrNull() else v
    "string" -> if (v === Undefined) "" else v.asPayloadString() ?: "None"
    "truncate" -> truncate(if (v === Undefined) "" else v.asPayloadString() ?: "None", args)
    "int" -> looseNumber(v)?.toLong() ?: 0L
    "float" -> looseNumber(v)?.toDouble() ?: 0.0
    "round" -> {
        val precision = (args.firstOrNull() as? Long)?.toInt() ?: 0
        val d = when (v) {
            is Long -> v.toDouble()
            is Double -> v
            else -> throw TemplateEvalError("round expects a number")
        }
        if (d.isNaN() || d.isInfinite()) d else roundHalfUp(d, precision)
    }
    "lower" -> if (v === Undefined) "" else (v.asPayloadString() ?: "None").lowercase()
    "upper" -> if (v === Undefined) "" else (v.asPayloadString() ?: "None").uppercase()
    else -> throw TemplateEvalError("unknown filter '$name'")
}

// Pure-Kotlin HALF_UP (away from zero) to the given decimal precision; matches
// BigDecimal.setScale(_, HALF_UP) for finite doubles across the range templates emit.
private fun roundHalfUp(d: Double, precision: Int): Double {
    val factor = 10.0.pow(precision)
    val scaled = d * factor
    val rounded = if (scaled >= 0.0) floor(scaled + 0.5) else -floor(-scaled + 0.5)
    return rounded / factor
}

/** Jinja `truncate(length=255, killwords=False, end='...', leeway=5)`. */
private fun truncate(s: String, args: List<Any?>): String {
    val length = (args.getOrNull(0) as? Long)?.toInt() ?: 255
    val killWords = args.getOrNull(1) == true
    val end = args.getOrNull(2) as? String ?: "..."
    val leeway = (args.getOrNull(3) as? Long)?.toInt() ?: 5
    if (length < end.length) throw TemplateEvalError("truncate length is shorter than its end")
    if (s.length <= length + leeway) return s
    val cut = s.take(length - end.length)
    return (if (killWords) cut else cut.substringBeforeLast(' ')) + end
}
