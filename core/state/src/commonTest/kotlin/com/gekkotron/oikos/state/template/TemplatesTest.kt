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
            "{{ 2 ** 3 }}",
            "{# comment #}",
            "{{ now() }}",
            "{{ value | sum }}",
            "{{ value",
            "{{ 7 % 2 }}",
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
    fun `inline if-else`() {
        val t = "{{ 'ON' if value_json.state == 1 else 'OFF' }}"
        assertEquals("ON", v(t, """{"state":1}"""))
        assertEquals("OFF", v(t, """{"state":0}"""))
        assertEquals("OFF", v(t, """{}"""))
        assertEquals("ON", v("{{ 'ON' if value_json.muted else 'OFF' }}", """{"muted":true}"""))
        assertEquals("OFF", v("{{ 'ON' if value_json.muted else 'OFF' }}", """{"muted":null}"""))
        assertEquals("Y", v("{{ 'Y' if value_json.a }}", """{"a":1}"""))
        assertEquals(null, v("{{ 'Y' if value_json.a }}", """{"a":0}"""))
        assertEquals("b", v("{{ 'a' if value_json.x == 1 else 'b' if value_json.x == 2 else 'c' }}", """{"x":2}"""))
        assertEquals("up", v("{{ ('up' if value_json.x else 'down') | lower }}", """{"x":1}"""))
        assertEquals("ON", v("{{ 'on' | upper if value_json.x else 'OFF' }}", """{"x":1}"""))
    }

    @Test
    fun `or and not return operands like Python`() {
        assertEquals("", v("{{ value_json.m or '' }}", """{"m":null}"""))
        assertEquals("", v("{{ value_json.m or '' }}", """{}"""))
        assertEquals("FOLLOW", v("{{ value_json.m or '' }}", """{"m":"FOLLOW"}"""))
        assertEquals(2L, v("{{ value_json.a or value_json.b }}", """{"a":0,"b":2}"""))
        assertEquals(0L, v("{{ value_json.a and value_json.b }}", """{"a":0,"b":2}"""))
        assertEquals(2L, v("{{ value_json.a and value_json.b }}", """{"a":1,"b":2}"""))
        assertEquals(true, v("{{ not value_json.a }}", """{"a":0}"""))
        assertEquals("y", v("{{ 'y' if not value_json.a and value_json.b or value_json.c else 'n' }}", """{"a":0,"b":1}"""))
        assertEquals("n", v("{{ 'y' if not (value_json.a or value_json.b) else 'n' }}", """{"a":0,"b":1}"""))
    }

    @Test
    fun `not equal`() {
        assertEquals(true, v("{{ value_json.s != 'x' }}", """{"s":"y"}"""))
        assertEquals(false, v("{{ value_json.s != 'x' }}", """{"s":"x"}"""))
        assertEquals(true, v("{{ value_json.s != 'x' }}", """{}"""))
        assertEquals("a", v("{% if value_json.s != 'x' %}a{% else %}b{% endif %}", """{"s":"y"}"""))
    }

    @Test
    fun `is none and is defined tests`() {
        val p = """{"n":null,"v":1}"""
        assertEquals(true, v("{{ value_json.n is none }}", p))
        assertEquals(false, v("{{ value_json.v is none }}", p))
        assertEquals(false, v("{{ value_json.missing is none }}", p))
        assertEquals(false, v("{{ value_json.n is not none }}", p))
        assertEquals(true, v("{{ value_json.missing is not none }}", p))
        assertEquals(true, v("{{ value_json.n is defined }}", p))
        assertEquals(false, v("{{ value_json.missing is defined }}", p))
        assertEquals(true, v("{{ value_json.missing is not defined }}", p))
        assertEquals(true, v("{{ value_json.missing is undefined }}", p))
        assertEquals(false, v("{{ value_json.v is undefined }}", p))
        assertIs<TemplateResult.Unsupported>(Templates.renderValue("{{ value is odd }}", "1"))
    }

    @Test
    fun `if statements take the full expression grammar`() {
        val t = "{% if value_json.volume is not none %}{{ value_json.volume }}{% endif %}"
        assertEquals("25", v(t, """{"volume":25}"""))
        assertEquals("", v(t, """{"volume":null}"""))
        assertEquals("", v(t, """{}"""))
        val u = "{% if value_json.a or not value_json.b %}x{% else %}y{% endif %}"
        assertEquals("x", v(u, """{"a":0,"b":0}"""))
        assertEquals("y", v(u, """{"a":0,"b":1}"""))
    }

    @Test
    fun `malformed new constructs are unsupported`() {
        assertIs<TemplateResult.Unsupported>(Templates.renderValue("{{ (value }}", "1"))
        assertIs<TemplateResult.Unsupported>(Templates.renderValue("{{ 'a' if }}", "1"))
        assertIs<TemplateResult.Unsupported>(Templates.renderValue("{{ !value }}", "1"))
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

    @Test
    fun `arithmetic follows precedence and true division`() {
        assertEquals(7L, v("{{ 1 + 2 * 3 }}", "{}"))
        assertEquals(9L, v("{{ (1 + 2) * 3 }}", "{}"))
        assertEquals(0.5, v("{{ 1 / 2 }}", "{}"))
        assertEquals(2.0, v("{{ 4 / 2 }}", "{}"))
        assertEquals(-0.65, v("{{ 0.35 - 1 }}", "{}") as Double, 1e-9)
        assertEquals(4L, v("{{ value_json.a -1 }}", """{"a":5}"""))
        assertEquals(-1L, v("{{ (1 - 2) }}", "{}"))
        assertEquals(-1L, v("{{ -1 }}", "{}"))
        assertEquals(-1L, v("{{ value_json.nope | default(-1) }}", "{}"))
        assertEquals("ab", v("{{ 'a' + 'b' }}", "{}"))
    }

    @Test
    fun `filters bind tighter than arithmetic`() {
        assertEquals(3.0, v("{{ 1 + 2.4 | round }}", "{}"))
        assertEquals(4.0, v("{{ (1 + 2.6) | round }}", "{}"))
    }

    @Test
    fun `ordering comparisons`() {
        assertEquals(true, v("{{ 2 > 1 }}", "{}"))
        assertEquals(false, v("{{ 2 < 1 }}", "{}"))
        assertEquals(true, v("{{ 2 >= 2.0 }}", "{}"))
        assertEquals(true, v("{{ 1 <= 2 }}", "{}"))
        val heat = """{{ "heat" if (value_json.a + value_json.b) > 0 else "off" }}"""
        assertEquals("heat", v(heat, """{"a":0,"b":3}"""))
        assertEquals("off", v(heat, """{"a":0,"b":0}"""))
    }

    @Test
    fun `arithmetic on non-numbers is an error`() {
        assertIs<TemplateResult.Error>(Templates.renderValue("{{ value_json.s * 2 }}", """{"s":"x"}"""))
        assertIs<TemplateResult.Error>(Templates.renderValue("{{ value_json.nope + 1 }}", "{}"))
    }
    @Test
    fun `dict literal with tojson`() {
        assertEquals("""{"a":1,"b":"x"}""", v("""{{ {"a": 1, "b": "x"} | tojson }}""", "{}"))
        assertEquals(
            """{"t":17.9,"p":43}""",
            v("""{{ {"t": value_json.t | round(1), "p": (value_json.p / 3) | round | int} | tojson }}""", """{"t":17.94,"p":129}"""),
        )
    }

    @Test
    fun `tojson serializes scalars, null and booleans`() {
        assertEquals("\"a\"", v("{{ 'a' | tojson }}", "{}"))
        assertEquals("1.5", v("{{ 1.5 | tojson }}", "{}"))
        assertEquals("true", v("{{ true | tojson }}", "{}"))
        assertEquals("null", v("{{ none | tojson }}", "{}"))
        assertIs<TemplateResult.Error>(Templates.renderValue("{{ value_json.nope | tojson }}", "{}"))
    }

    @Test
    fun `nested dicts close before the expression does`() {
        assertEquals("""{"a":{"b":1}}""", v("""{{ {"a": {"b": 1}} | tojson }}""", "{}"))
        assertEquals("""{"a":{"b":1}}""", v("""{{ {"a": {"b": 1}}|tojson}}""", "{}"))
        assertEquals("""x}}y""", v("""{{ "x}}y" }}""", "{}"))
    }

    // Spec Appendix B payload; the templates are spec Appendix A verbatim.
    private val heaterData =
        """{"adc_result_mv":604,"triac_0_power":40,"triac_1_power":45,"triac_2_power":44,"temp_0":17.94,"temp_1":41.2,"measuredFrequency":50.02,"frequencyError":0}"""

    @Test
    fun `WaterHeaterController templates render`() {
        assertEquals(58.0, v("{{ (80 + (0.35 - value_json.adc_result_mv / 1000) * 86.67) | round(1) }}", heaterData))
        assertEquals(
            "heat",
            v("""{{ "heat" if (value_json.triac_0_power + value_json.triac_1_power + value_json.triac_2_power) > 0 else "off" }}""", heaterData),
        )
        assertEquals(
            """{"inlet_temperature":17.9,"power":43}""",
            v(
                """{{ {"inlet_temperature": value_json.temp_0 | round(1), "power": ((value_json.triac_0_power + value_json.triac_1_power + value_json.triac_2_power) / 3) | round | int} | tojson }}""",
                heaterData,
            ),
        )
        assertEquals("""{"power0": 40}""", v("""{"power0": {{ value | int }}}""", "40"))
    }
}
