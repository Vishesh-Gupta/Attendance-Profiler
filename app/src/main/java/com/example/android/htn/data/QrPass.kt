package com.example.android.htn.data

/**
 * The contents of a person's check-in QR code. It only identifies the person; whoever scans it must
 * be a volunteer or organizer, which the backend enforces.
 */
object QrPass {
    private const val PREFIX = "attendance-profiler:v1:"
    private val UID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")

    fun encode(uid: String): String {
        require(UID_REGEX.matches(uid)) { "Invalid uid" }
        return PREFIX + uid
    }

    /** Returns the uid in [payload], or null if it isn't one of our passes. */
    fun decode(payload: String?): String? =
        payload?.trim()?.removePrefix(PREFIX)?.takeIf { it.length < payload.trim().length && UID_REGEX.matches(it) }
}
