package com.gekkotron.oikos.model

/** A non-fatal problem worth showing in Settings → Logs. */
data class Diagnostic(val source: String, val message: String)

fun interface DiagnosticSink {
    fun report(diagnostic: Diagnostic)

    companion object {
        val None: DiagnosticSink = DiagnosticSink { }
    }
}
