package com.gekkotron.oikos.crypto

sealed class EnvelopeException(message: String) : Exception(message) {
    class Malformed(reason: String) : EnvelopeException("malformed envelope: $reason")
    class AuthenticationFailed : EnvelopeException("authentication failed (wrong key or tampered data)")
    class MissingTimestamp : EnvelopeException("envelope has no timestamp")
    class Expired(val skewSeconds: Long) : EnvelopeException("timestamp outside the accepted window (skew ${skewSeconds}s)")
}
