package com.gekkotron.oikos.voice

/**
 * NFD-normalize [s] and strip the combining marks (Unicode class Mn) so visually equivalent
 * strings compare as the same bare letters. Backed by java.text.Normalizer on JVM; iOS target
 * will supply its own actual (NSString.precomposedStringWithCanonicalMapping or ICU bridge).
 */
internal expect fun stripDiacritics(s: String): String
