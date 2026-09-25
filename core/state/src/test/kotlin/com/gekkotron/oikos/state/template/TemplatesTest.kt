package com.gekkotron.oikos.state.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TemplatesTest {
    private fun v(template: String, payload: String): Any? {
        val r = Templates.renderValue(template, payload)
        assertIs<TemplateResult.Value>(r, "expected a value for $template, got $r")
        return r.value
    }

    @Test
    fun `value is the raw payload`() {
        assertEquals("21.5", v("{{ value }}", "21.5"))
        assertEquals("online", v("{{value}}", "online"))
    }

    @Test
    fun `value_json attribute and nested access`() {
        assertEquals(21.5, v("{{ value_json.temperature }}", """{"temperature":21.5}"""))
        assertEquals(3L, v("{{ value_json.a.b }}", """{"a":{"b":3}}"""))
        assertEquals(true, v("{{ value_json.contact }}", """{"contact":true}"""))
    }

    @Test
    fun `bracket access with spaces and unicode, mixed with dots`() {
        assertEquals(22.5, v("{{ value_json['température_int.'] }}", """{"température_int.":22.5}"""))
        assertEquals("x", v("""{{ value_json.data["k k"].v }}""", """{"data":{"k k":{"v":"x"}}}"""))
        assertEquals(20L, v("{{ value_json.list[1] }}", """{"list":[10,20]}"""))
    }

    @Test
    fun `missing keys are undefined and default rescues them`() {
        assertEquals(null, v("{{ value_json.nope }}", """{"a":1}"""))
        assertEquals(null, v("{{ value_json.nope.deeper }}", """{"a":1}"""))
        assertEquals("x", v("{{ value_json.nope | default('x') }}", """{"a":1}"""))
        assertEquals(1L, v("{{ value_json.a | default('x') }}", """{"a":1}"""))
    }

    @Test
    fun `json null stays null and is not rescued by default`() {
        assertEquals(null, v("{{ value_json.a | default('x') }}", """{"a":null}"""))
    }

    @Test
    fun `numeric filters`() {
        assertEquals(21L, v("{{ value | int }}", "21.7"))
        assertEquals(0L, v("{{ value | int }}", "abc"))
        assertEquals(21.0, v("{{ value | float }}", "21"))
        assertEquals(21.5, v("{{ value_json.t | round(1) }}", """{"t":21.456}"""))
        assertEquals(22.0, v("{{ value_json.t | round }}", """{"t":21.5}"""))
        assertEquals(21.5, v("{{ value_json.t | float | round(1) }}", """{"t":"21.46"}"""))
    }

    @Test
    fun `string filters`() {
        assertEquals("on", v("{{ value | lower }}", "ON"))
        assertEquals("OFF", v("{{ value_json.state | upper }}", """{"state":"off"}"""))
        assertEquals("true", v("{{ value_json.b | lower }}", """{"b":true}"""))
    }

    @Test
    fun `boolean default also rescues null and empty values`() {
        assertEquals("", v("{{ value_json.a | default('', true) }}", """{"a":null}"""))
        assertEquals("x", v("{{ value_json.a | default('x', True) }}", """{"a":""}"""))
        assertEquals("x", v("{{ value_json.nope | default('x', true) }}", """{"a":1}"""))
        assertEquals("v", v("{{ value_json.a | default('x', true) }}", """{"a":"v"}"""))
        assertEquals(null, v("{{ value_json.a | default('x', false) }}", """{"a":null}"""))
    }

    @Test
    fun `string and truncate filters`() {
        assertEquals("21", v("{{ value_json.a | string }}", """{"a":21}"""))
        assertEquals("True", v("{{ value_json.a | string }}", """{"a":true}"""))
        assertEquals("None", v("{{ value_json.a | string }}", """{"a":null}"""))
        assertEquals("abc", v("{{ value | truncate(5) }}", "abc"))
        assertEquals("ab", v("{{ value | truncate(2, True, '', 0) }}", "abcdef"))
        assertEquals("abcd...", v("{{ value | truncate(7, True, '...', 0) }}", "abcdefghij"))
        assertEquals("abcdefghij", v("{{ value | truncate(7, True) }}", "abcdefghij"))
        assertEquals("one...", v("{{ value | truncate(9, False, '...', 0) }}", "one two three"))
    }

    @Test
    fun `zigbee2mqtt string sensor template`() {
        val t = """{{ value_json["timestamp_a"] | default('',True) | string | truncate(254, True, '', 0) }}"""
        assertEquals("2026-09-25T07:35:49.426Z", v(t, """{"timestamp_a":"2026-09-25T07:35:49.426Z"}"""))
        assertEquals("", v(t, """{"timestamp_a":null}"""))
    }

    @Test
    fun `if else statement renders the branch for the condition's truthiness`() {
        val t = """{% if value_json["alarm"] %}true{% else %}false{% endif %}"""
        assertEquals("true", v(t, """{"alarm":true}"""))
        assertEquals("false", v(t, """{"alarm":false}"""))
        assertEquals("false", v(t, """{"alarm":null}"""))
        assertEquals("false", v(t, """{"other":1}"""))
        assertEquals("false", v(t, """{"alarm":0}"""))
        assertEquals("false", v(t, """{"alarm":""}"""))
        assertEquals("true", v(t, """{"alarm":"x"}"""))
    }

    @Test
    fun `if without else and with expressions in branches`() {
        assertEquals("", v("{% if value_json.a %}on{% endif %}", """{"a":false}"""))
        assertEquals("s=ON!", v("s={% if value_json.a %}{{ value_json.a | upper }}{% endif %}!", """{"a":"on"}"""))
    }

    @Test
    fun `literals`() {
        assertEquals("a", v("{{ 'a' }}", "x"))
        assertEquals(-1L, v("{{ -1 }}", "x"))
        assertEquals(null, v("{{ none }}", "x"))
    }

    @Test
    fun `text around expressions is concatenated`() {
        val r = Templates.render("""{ "position": {{ position }} }""", mapOf("position" to 42L))
        assertEquals(TemplateResult.Value("""{ "position": 42 }"""), r)
        assertEquals(TemplateResult.Value("""{"a": 1}"""), Templates.render("""{"a": {{ value }}}""", mapOf("value" to 1L)))
    }

    @Test
    fun `a template without expressions is its own text`() {
        assertEquals("ON", v("ON", "ignored"))
    }

    @Test
    fun `surrounding whitespace keeps the typed value`() {
        assertEquals(21.5, v("  {{ value_json.t }} ", """{"t":21.5}"""))
    }

    @Test
    fun `command variables`() {
        assertEquals(
            TemplateResult.Value("""{"action":"ARM_AWAY","code":"1234"}"""),
            Templates.render("""{"action":"{{ action }}","code":"{{ code }}"}""", mapOf("action" to "ARM_AWAY", "code" to "1234")),
        )
    }

    @Test
    fun `unsupported syntax is reported, not thrown`() {
        listOf(
            "{% if value_json.a %}x",
            "{% else %}x{% endif %}",
            "{% elif value_json.a %}x{% endif %}",
            "{% if value_json.a %}x{% else %}y{% elif value_json.b %}z{% endif %}",
            "{% for x in value_json %}x{% endfor %}",
            "{%- if value_json.a %}x{% endif %}",
            "{% if value_json.a or value_json.b %}x{% endif %}",
            "{% if not value_json.a %}x{% endif %}",
            "{% if value_json.a != 1 %}x{% endif %}",
            "{% if value_json.a < 1 %}x{% endif %}",
            "{# comment #}",
            "{{ value_json.x == 1 }}",
            "{{ value_json.a and value_json.b }}",
            "{{ 'a' in value_json }}",
            "{{ now() }}",
            "{{ value | tojson }}",
            "{{ value",
            "{{ 'a' if value else 'b' }}",
            "{{ value_json.x + 1 }}",
            "{{ }}",
        ).forEach { t -> assertIs<TemplateResult.Unsupported>(Templates.renderValue(t, "1"), t) }
    }

    @Test
    fun `'in' checks key membership, false unless the target is a JSON object`() {
        val t = """{% if "a" in value_json %}yes{% else %}no{% endif %}"""
        assertEquals("yes", v(t, """{"a":1}"""))
        assertEquals("no", v(t, """{"b":1}"""))
        assertEquals("no", v(t, "1"))
    }

    @Test
    fun `'and' is left-to-right and short-circuits`() {
        // if "a" is missing, evaluating the right side (round on undefined) would raise; short-circuit avoids it.
        val t = """{% if "a" in value_json and value_json["a"] | round == 1 %}yes{% else %}no{% endif %}"""
        assertEquals("yes", v(t, """{"a":1}"""))
        assertEquals("no", v(t, """{"a":2}"""))
        assertEquals("no", v(t, """{"b":1}"""))
    }

    @Test
    fun `'==' compares strings and numbers, never undefined`() {
        val t = """{% if value_json["a"] == value_json["b"] %}eq{% else %}neq{% endif %}"""
        assertEquals("eq", v(t, """{"a":"x","b":"x"}"""))
        assertEquals("neq", v(t, """{"a":"x","b":"y"}"""))
        assertEquals("eq", v(t, """{"a":0,"b":0.0}"""))
        assertEquals("neq", v(t, """{"a":0,"b":"0"}"""))
        assertEquals("neq", v(t, """{"a":1}"""))
    }

    @Test
    fun `elif chain picks the first true branch, else is the fallback`() {
        val t = """{% if value_json.a == 1 %}one{% elif value_json.a == 2 %}two{% elif value_json.a == 3 %}three{% else %}other{% endif %}"""
        assertEquals("one", v(t, """{"a":1}"""))
        assertEquals("two", v(t, """{"a":2}"""))
        assertEquals("three", v(t, """{"a":3}"""))
        assertEquals("other", v(t, """{"a":4}"""))
    }

    @Test
    fun `nested if inside a branch`() {
        val t = """{% if value_json.a %}{% if value_json.b %}ab{% else %}a{% endif %}{% else %}none{% endif %}"""
        assertEquals("ab", v(t, """{"a":true,"b":true}"""))
        assertEquals("a", v(t, """{"a":true,"b":false}"""))
        assertEquals("none", v(t, """{"a":false,"b":true}"""))
    }

    @Test
    fun `evaluation errors are reported, not thrown`() {
        assertIs<TemplateResult.Error>(Templates.renderValue("{{ value_json.state }}", "online"))
        assertIs<TemplateResult.Error>(Templates.renderValue("{{ value | round(1) }}", "abc"))
    }

    @Test
    fun `payload string helpers`() {
        assertEquals("True", true.asPayloadString())
        assertEquals("False", false.asPayloadString())
        assertEquals("21", 21L.asPayloadString())
        assertEquals(null, null.asPayloadString())
        assertEquals(21.5, "21.5".asDouble())
        assertEquals(null, "abc".asDouble())
        assertEquals("21", formatNumber(21.0))
        assertEquals("21.5", formatNumber(21.5))
        assertEquals("-3", formatNumber(-3.0))
    }
}
