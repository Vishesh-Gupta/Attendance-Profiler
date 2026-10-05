package com.example.android.htn.data

import java.security.SecureRandom

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

/**
 * Team codes are project document ids: 10 characters from an alphabet without look-alikes (no 0/O,
 * 1/I/L), shown as "ABCDE-FGHJK". Teammates type the code or scan the team's QR code to join.
 */
object TeamCode {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 10
    private const val QR_PREFIX = "attendance-profiler:team:v1:"
    private val CODE_REGEX = Regex("^[$ALPHABET]{$LENGTH}$")
    private val EVENT_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val random = SecureRandom()

    fun generate(): String = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** Accepts what people type: any case, with spaces or dashes. Returns null if it can't be a code. */
    fun normalize(input: String): String? =
        input.uppercase().filter { it.isLetterOrDigit() }.takeIf { CODE_REGEX.matches(it) }

    fun display(code: String): String = if (code.length == LENGTH) "${code.take(5)}-${code.drop(5)}" else code

    fun qrPayload(eventId: String, code: String): String = "$QR_PREFIX$eventId:$code"

    /** Returns (eventId, code) from a scanned team QR code, or null. */
    fun decodeQr(payload: String?): Pair<String, String>? {
        val body = payload?.trim()?.takeIf { it.startsWith(QR_PREFIX) }?.removePrefix(QR_PREFIX) ?: return null
        val eventId = body.substringBefore(':', "")
        val code = normalize(body.substringAfter(':', "")) ?: return null
        return if (EVENT_ID_REGEX.matches(eventId)) eventId to code else null
    }
}
