package nz.mcnabb.atvhabridge

import android.content.Context
import android.os.Build
import java.security.SecureRandom
import java.util.UUID

/**
 * Device identity + pairing. Home Assistant pairs by POSTing the 6-digit code shown
 * on the TV; the bridge mints a long-lived token in return. The code + tokens live in
 * SharedPreferences so the UI (which displays the code) and the server (which validates
 * it) always agree, and both survive restarts. A reinstall clears them — the HA side
 * simply re-pairs (a code entry / QR scan), never ADB.
 */
class Pairing(context: Context) {
    private val prefs = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
    private val rng = SecureRandom()
    private var failedAttempts = 0
    private var lockedUntil = 0L

    val deviceId: String = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_ID, it).apply()
    }

    val defaultName: String get() = Build.MODEL ?: "Android TV"

    /** The active 6-digit code, regenerated when expired. Shown on the TV + in the QR. */
    fun currentCode(): String {
        val stored = prefs.getString(KEY_CODE, null)
        return if (stored != null && now() < prefs.getLong(KEY_CODE_EXP, 0)) stored else rotateCode()
    }

    fun rotateCode(): String {
        val code = (1..6).map { rng.nextInt(10) }.joinToString("")
        prefs.edit().putString(KEY_CODE, code).putLong(KEY_CODE_EXP, now() + CODE_TTL_MS).apply()
        return code
    }

    /** Validate a code; on success mint + persist a token and invalidate the code. */
    @Synchronized
    fun redeem(candidate: String): String? {
        if (now() < lockedUntil) return null
        val stored = prefs.getString(KEY_CODE, null) ?: return null
        if (now() > prefs.getLong(KEY_CODE_EXP, 0) || candidate.trim() != stored) {
            // Throttle guessing: after MAX_FAILED_ATTEMPTS wrong codes, lock out briefly and
            // show a fresh code, so the 6-digit space can't be swept within one code's lifetime.
            if (++failedAttempts >= MAX_FAILED_ATTEMPTS) {
                failedAttempts = 0
                lockedUntil = now() + LOCKOUT_MS
                rotateCode()
            }
            return null
        }
        failedAttempts = 0
        val token = randomToken()
        prefs.edit().putStringSet(KEY_TOKENS, tokens() + token).apply()
        rotateCode() // one-time use
        return token
    }

    fun isValidToken(token: String?): Boolean = token != null && token in tokens()

    fun isPaired(): Boolean = tokens().isNotEmpty()

    fun pairedCount(): Int = tokens().size

    private fun tokens(): Set<String> = prefs.getStringSet(KEY_TOKENS, emptySet()) ?: emptySet()

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val KEY_ID = "device_id"
        private const val KEY_TOKENS = "tokens"
        private const val KEY_CODE = "pair_code"
        private const val KEY_CODE_EXP = "pair_code_exp"
        private const val CODE_TTL_MS = 30 * 60 * 1000L
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_MS = 60 * 1000L
    }
}
