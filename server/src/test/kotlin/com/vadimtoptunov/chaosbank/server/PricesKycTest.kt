package com.vadimtoptunov.chaosbank.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Reads [count] frames from the price stream and returns their seq numbers. */
suspend fun ApplicationTestBuilder.streamSeqs(api: Api, count: Int, defects: String? = null): List<Long> {
    val ws = createClient { install(WebSockets) }
    val seqs = mutableListOf<Long>()
    ws.webSocket("/v1/prices/stream", request = {
        header(SESSION_HEADER, api.session!!)
        header(HttpHeaders.Authorization, "Bearer ${api.token}")
        defects?.let { header(DEFECTS_HEADER, it) }
    }) {
        while (seqs.size < count) {
            val frame = incoming.receive() as? Frame.Text ?: continue
            seqs += Json.parseToJsonElement(frame.readText()).jsonObject["seq"]!!.jsonPrimitive.long
        }
    }
    return seqs
}

class PricesTest {
    @Test
    fun `the feed opens at the seeded base prices`() {
        val tick = PriceFeed(seed = 0, startMillis = 0).current(nowMillis = 0)
        assertEquals(0, tick.seq)
        assertEquals(Seed.assets.map { it.basePrice }, tick.quotes.map { it.price })
    }

    @Test
    fun `the same seed walks the same prices, another seed doesn't`() {
        val at = 50 * PriceFeed.TICK_MS
        val a = PriceFeed(7, 0).current(at).quotes
        assertEquals(a, PriceFeed(7, 0).current(at).quotes)
        assertNotEquals(a, PriceFeed(8, 0).current(at).quotes)
    }

    @Test
    fun `catching up at once equals ticking along`() {
        val stepwise = PriceFeed(3, 0).also { f -> (1..20).forEach { f.current(it * PriceFeed.TICK_MS) } }
        assertEquals(PriceFeed(3, 0).current(20 * PriceFeed.TICK_MS), stepwise.current(20 * PriceFeed.TICK_MS))
    }

    @Test
    fun `quotes stay on the cent and above the floor`() {
        val quotes = PriceFeed(1, 0).current(500 * PriceFeed.TICK_MS).quotes
        assertTrue(quotes.all { it.price.scale() == 2 && it.price.signum() > 0 && it.dayLow <= it.price && it.price <= it.dayHigh })
    }

    @Test
    fun `quotes are served over REST`() = app {
        val tick = Api.fresh(client).get("/v1/quotes").obj
        assertEquals(6, tick["quotes"]!!.jsonArray.size)
    }

    @Test
    fun `the stream starts with the current prices and counts up`() = app {
        val seqs = streamSeqs(Api.fresh(client), 4)
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.toSet().size, seqs.size)
    }

    @Test
    fun `the stream refuses a caller without a token`() = app {
        val api = Api.fresh(client).also { it.token = "nope" }
        val ws = createClient { install(WebSockets) }
        var reason: CloseReason? = null
        ws.webSocket("/v1/prices/stream", request = { header(SESSION_HEADER, api.session!!); header(HttpHeaders.Authorization, "Bearer nope") }) {
            reason = closeReason.await()
        }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
    }
}

class KycTest {
    private fun submission(name: String? = KycReview.ACCOUNT_HOLDER, expiry: String? = "2031-04-30", selfie: String = "match") =
        """{"document":{"kind":"passport","name":${name?.let { "\"$it\"" } ?: "null"},"number":"P4916204","expiry":${expiry?.let { "\"$it\"" } ?: "null"}},"selfie":"$selfie"}"""

    @Test
    fun `a session starts verified, as the apps do`() = app {
        assertEquals("verified", Api.fresh(client).get("/v1/kyc/status").str("status"))
    }

    @Test
    fun `a valid document and a matching face verify`() = app {
        val api = Api.fresh(client)
        val r = api.post("/v1/kyc/submissions", submission())
        assertEquals(201, r.status)
        assertEquals("verified", r.str("status"))
    }

    @Test
    fun `the rules reject in the apps' order`() = app {
        val api = Api.fresh(client)
        assertEquals("Document expired", api.post("/v1/kyc/submissions", submission(name = "ALEX MORGAN", expiry = "2024-01-31")).str("reason"))
        assertEquals("Name doesn't match the account holder", api.post("/v1/kyc/submissions", submission(name = "ALEX MORGAN", selfie = "mismatch")).str("reason"))
        assertEquals("Face doesn't match the document", api.post("/v1/kyc/submissions", submission(selfie = "mismatch")).str("reason"))
        assertEquals("rejected", api.get("/v1/kyc/status").str("status"))
    }

    @Test
    fun `unreadable scans and missing faces are input errors, not decisions`() = app {
        val api = Api.fresh(client)
        assertEquals("invalidDocument", api.post("/v1/kyc/submissions", submission(name = null)).type)
        assertEquals("noFace", api.post("/v1/kyc/submissions", submission(selfie = "noFace")).type)
        assertEquals("invalidDocument", api.post("/v1/kyc/submissions", submission(expiry = "30/04/2031")).type)
        assertEquals("verified", api.get("/v1/kyc/status").str("status"), "nothing was decided")
    }

    @Test
    fun `expiry is judged on the session's clock`() = app {
        val api = Api.fresh(client)
        api.post("/chaos/sessions/${api.session}/clock", """{"advanceSeconds":${6L * 365 * 86_400}}""")
        api.signIn()
        assertEquals("Document expired", api.post("/v1/kyc/submissions", submission()).str("reason"))
    }
}
