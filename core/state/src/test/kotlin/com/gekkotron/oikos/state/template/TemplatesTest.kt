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
            "{% if value_json.moving %}MOVING{% endif %}",
            "{# comment #}",
            "{{ value_json.x == 1 }}",
            "{{ now() }}",
            "{{ value | tojson }}",
            "{{ value",
            "{{ 'a' if value else 'b' }}",
            "{{ value_json.x + 1 }}",
            "{{ }}",
        ).forEach { t -> assertIs<TemplateResult.Unsupported>(Templates.renderValue(t, "1"), t) }
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
