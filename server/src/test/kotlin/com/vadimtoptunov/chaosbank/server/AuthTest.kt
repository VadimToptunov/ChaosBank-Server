package com.vadimtoptunov.chaosbank.server

import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthTest {
    private suspend fun session(client: HttpClient): Api = Api(client, Api(client).post("/chaos/sessions", "{}").str("id"))

    private suspend fun Api.challenge(): String = post("/v1/auth/login", """{"username":"alex","password":"pw"}""").str("challengeId")

    private suspend fun Api.otp(challenge: String, code: String) = post("/v1/auth/otp", """{"challengeId":"$challenge","code":"$code"}""")

    @Test
    fun `bank calls need a token, and say so`() = app {
        val r = Api(client).get("/v1/accounts")
        assertEquals(401, r.status)
        assertEquals("unauthorized", r.type)
        assertEquals("Bearer", r.headers["WWW-Authenticate"])
    }

    @Test
    fun `an empty login is refused`() = app {
        assertEquals("invalidCredentials", Api(client).post("/v1/auth/login", """{"username":" ","password":""}""").type)
    }

    @Test
    fun `three wrong codes lock the challenge, even against the right code`() = app {
        val api = session(client)
        val c = api.challenge()
        repeat(3) { assertEquals("invalidOtp", api.otp(c, "000000").type) }
        val locked = api.otp(c, AuthRules.OTP_CODE)
        assertEquals(429, locked.status)
        assertEquals("otpLocked", locked.type)
    }

    @Test
    fun `a resend waits out the cooldown, then unlocks and works`() = app {
        val api = session(client)
        val c = api.challenge()
        repeat(3) { api.otp(c, "000000") }
        val early = api.post("/v1/auth/otp/resend", """{"challengeId":"$c"}""")
        assertEquals("resendCooldown", early.type)
        assertEquals("30", early.headers["Retry-After"])
        clock(api, 30)
        assertEquals(200, api.post("/v1/auth/otp/resend", """{"challengeId":"$c"}""").status)
        assertEquals(200, api.otp(c, AuthRules.OTP_CODE).status)
    }

    @Test
    fun `a code expires after 20 seconds of session time`() = app {
        val api = session(client)
        val c = api.challenge()
        clock(api, 21)
        assertEquals("otpExpired", api.otp(c, AuthRules.OTP_CODE).type)
    }

    @Test
    fun `a used challenge can't sign in twice`() = app {
        val api = session(client)
        val c = api.challenge()
        assertEquals(200, api.otp(c, AuthRules.OTP_CODE).status)
        assertEquals("unknownChallenge", api.otp(c, AuthRules.OTP_CODE).type)
    }

    @Test
    fun `an access token expires after 15 minutes of session time`() = app {
        val api = session(client)
        api.signIn()
        assertEquals(200, api.get("/v1/accounts").status)
        clock(api, AuthRules.ACCESS_TTL_SECONDS)
        assertEquals(401, api.get("/v1/accounts").status)
    }

    @Test
    fun `a refresh token works once`() = app {
        val api = session(client)
        val refresh = api.signIn().str("refreshToken")
        val renewed = api.post("/v1/auth/refresh", """{"refreshToken":"$refresh"}""")
        assertEquals(200, renewed.status)
        assertEquals("invalidToken", api.post("/v1/auth/refresh", """{"refreshToken":"$refresh"}""").type)
        api.token = renewed.str("accessToken")
        assertEquals(200, api.get("/v1/accounts").status)
    }

    @Test
    fun `logout ends the access token and the refresh tokens`() = app {
        val api = session(client)
        val refresh = api.signIn().str("refreshToken")
        assertEquals(204, api.post("/v1/auth/logout").status)
        assertEquals(401, api.get("/v1/accounts").status)
        assertEquals("invalidToken", api.post("/v1/auth/refresh", """{"refreshToken":"$refresh"}""").type)
    }

    @Test
    fun `a token from one session is refused in another`() = app {
        val one = session(client).also { it.signIn() }
        val two = session(client).also { it.token = one.token }
        assertEquals(401, two.get("/v1/accounts").status)
    }

    @Test
    fun `a forged or garbled token is refused`() = app {
        val api = session(client).also { it.signIn() }
        val (h, p, _) = api.token!!.split('.')
        for (bad in listOf("$h.$p.", "$h.$p.AAAA", "not-a-jwt", "eyJhbGciOiJub25lIn0.$p.")) {
            api.token = bad
            assertEquals(401, api.get("/v1/accounts").status, bad)
        }
    }

    private suspend fun clock(api: Api, seconds: Long) {
        val r = api.post("/chaos/sessions/${api.session}/clock", """{"advanceSeconds":$seconds}""")
        assertEquals(200, r.status)
    }
}
