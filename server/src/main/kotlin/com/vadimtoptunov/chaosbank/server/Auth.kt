package com.vadimtoptunov.chaosbank.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The apps' auth ladder numbers (`AuthFlow`), so the server and the mock agree. */
object AuthRules {
    /** The demo OTP; there is no SMS. */
    const val OTP_CODE = "424242"
    const val OTP_VALIDITY_SECONDS = 20L
    const val OTP_MAX_ATTEMPTS = 3
    const val OTP_RESEND_COOLDOWN_SECONDS = 30L
    const val ACCESS_TTL_SECONDS = 15 * 60L
    const val REFRESH_TTL_SECONDS = 24 * 60 * 60L
}

/**
 * HS256 JWTs on the standard library only. Every token carries its chaos session (`sid`),
 * so a token from one session is refused in another.
 */
class Jwt(private val secret: ByteArray) {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())

    data class Claims(val subject: String, val session: String, val type: String, val id: String, val expiresAt: Long)

    fun sign(subject: String, session: String, type: String, issuedAt: Long, ttlSeconds: Long): Pair<String, Claims> {
        val claims = Claims(subject, session, type, UUID.randomUUID().toString(), issuedAt + ttlSeconds)
        val payload = JsonObject(
            mapOf(
                "sub" to JsonPrimitive(subject), "sid" to JsonPrimitive(session), "typ" to JsonPrimitive(type),
                "jti" to JsonPrimitive(claims.id), "iat" to JsonPrimitive(issuedAt), "exp" to JsonPrimitive(claims.expiresAt),
            ),
        )
        val body = "$header.${enc.encodeToString(payload.toString().toByteArray())}"
        return "$body.${enc.encodeToString(mac(body))}" to claims
    }

    /** The claims of a well-signed HS256 token, or null. Expiry is the caller's check (session clock). */
    fun verify(token: String): Claims? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val headerJson = runCatching { Json.parseToJsonElement(String(dec.decode(parts[0]))).jsonObject }.getOrNull() ?: return null
        if (headerJson["alg"]?.jsonPrimitive?.content != "HS256") return null
        val signature = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(signature, mac("${parts[0]}.${parts[1]}"))) return null
        val p = runCatching { Json.parseToJsonElement(String(dec.decode(parts[1]))).jsonObject }.getOrNull() ?: return null
        return runCatching {
            Claims(
                p["sub"]!!.jsonPrimitive.content, p["sid"]!!.jsonPrimitive.content, p["typ"]!!.jsonPrimitive.content,
                p["jti"]!!.jsonPrimitive.content, p["exp"]!!.jsonPrimitive.long,
            )
        }.getOrNull()
    }

    private fun mac(data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(data.toByteArray())

    companion object {
        fun randomSecret(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
}

enum class AuthError(val status: Int) {
    invalidCredentials(400),
    unknownChallenge(404),
    otpExpired(401),
    invalidOtp(401),
    otpLocked(429),
    resendCooldown(429),
    invalidToken(401),
    unauthorized(401),
}

class AuthException(val error: AuthError, val detail: String, val retryAfterSeconds: Long? = null) : Exception(detail)

/** One session's login state: OTP challenges, live refresh tokens and logged-out access tokens. */
class AuthState {
    private class Challenge(val username: String, var sentAt: Long, var attempts: Int = 0, var locked: Boolean = false)
    private class Refresh(val username: String, val expiresAt: Long, var used: Boolean = false)

    private val challenges = HashMap<String, Challenge>()
    private val refreshTokens = HashMap<String, Refresh>()
    private val revokedAccess = HashSet<String>()

    data class Sent(val challengeId: String, val expiresIn: Long, val resendIn: Long)

    /** Any non-empty username and password pass, as in the apps; the OTP is the real gate. */
    @Synchronized
    fun login(username: String, password: String, now: Long): Sent {
        if (username.isBlank() || password.isEmpty()) throw AuthException(AuthError.invalidCredentials, "enter a username and password")
        val id = UUID.randomUUID().toString()
        challenges[id] = Challenge(username.trim(), now)
        return Sent(id, AuthRules.OTP_VALIDITY_SECONDS, AuthRules.OTP_RESEND_COOLDOWN_SECONDS)
    }

    @Synchronized
    fun resend(challengeId: String, now: Long): Sent {
        val c = challenges[challengeId] ?: throw AuthException(AuthError.unknownChallenge, "no OTP challenge '$challengeId'")
        val wait = c.sentAt + AuthRules.OTP_RESEND_COOLDOWN_SECONDS - now
        if (wait > 0) throw AuthException(AuthError.resendCooldown, "a new code can be sent in $wait s", wait)
        c.sentAt = now; c.attempts = 0; c.locked = false
        return Sent(challengeId, AuthRules.OTP_VALIDITY_SECONDS, AuthRules.OTP_RESEND_COOLDOWN_SECONDS)
    }

    /** Lockout first, then expiry, then the code: the apps' `verifyOtp` order. Returns the username. */
    @Synchronized
    fun verifyOtp(challengeId: String, code: String, now: Long): String {
        val c = challenges[challengeId] ?: throw AuthException(AuthError.unknownChallenge, "no OTP challenge '$challengeId'")
        if (c.locked) throw AuthException(AuthError.otpLocked, "too many attempts; request a new code")
        if (now - c.sentAt > AuthRules.OTP_VALIDITY_SECONDS) throw AuthException(AuthError.otpExpired, "the code expired; request a new one")
        if (code != AuthRules.OTP_CODE) {
            c.attempts += 1
            if (c.attempts >= AuthRules.OTP_MAX_ATTEMPTS) c.locked = true
            throw AuthException(AuthError.invalidOtp, "incorrect code (${c.attempts}/${AuthRules.OTP_MAX_ATTEMPTS})")
        }
        challenges.remove(challengeId)
        return c.username
    }

    @Synchronized
    fun rememberRefresh(claims: Jwt.Claims) { refreshTokens[claims.id] = Refresh(claims.subject, claims.expiresAt) }

    /** Rotation: a refresh token works once. Returns the username it belongs to. */
    @Synchronized
    fun useRefresh(claims: Jwt.Claims, now: Long): String {
        val r = refreshTokens[claims.id]
        if (claims.type != "refresh" || r == null || r.used || now >= r.expiresAt)
            throw AuthException(AuthError.invalidToken, "refresh token is invalid, expired or already used")
        r.used = true
        return r.username
    }

    @Synchronized
    fun isRevoked(accessId: String): Boolean = accessId in revokedAccess

    /** Logout: this access token and every refresh token of the user stop working. */
    @Synchronized
    fun logout(claims: Jwt.Claims) {
        revokedAccess += claims.id
        refreshTokens.values.filter { it.username == claims.subject }.forEach { it.used = true }
    }
}
