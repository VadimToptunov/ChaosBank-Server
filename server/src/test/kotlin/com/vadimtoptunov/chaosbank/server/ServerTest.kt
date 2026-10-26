package com.vadimtoptunov.chaosbank.server

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Responses as JSON trees, so the comparison is about content, not formatting. */
private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

private fun golden(name: String): JsonElement =
    parse(CleanParityTest::class.java.getResource("/golden/$name.json")!!.readText())

private suspend fun HttpResponse.json(): JsonElement = parse(bodyAsText())

private fun app(slowDelayMs: Long = SLOW_DELAY_MS, test: suspend ApplicationTestBuilder.() -> Unit) =
    testApplication {
        application { module(slowDelayMs = slowDelayMs) }
        test()
    }

private suspend fun HttpClient.newSession(body: String = "{}"): String =
    post("/chaos/sessions") { contentType(ContentType.Application.Json); setBody(body) }
        .json().jsonObject["id"]!!.jsonPrimitive.content

/** The stage-1 gate: a clean session answers exactly what the apps' embedded mock does. */
class CleanParityTest {
    @Test
    fun `accounts match the embedded mock`() = app {
        assertEquals(golden("accounts"), client.get("/v1/accounts").json())
    }

    @Test
    fun `each account by currency matches, case-insensitively`() = app {
        for (account in golden("accounts").jsonArray) {
            val ccy = account.jsonObject["currency"]!!.jsonPrimitive.content
            assertEquals(account, client.get("/v1/accounts/${ccy.lowercase()}").json())
        }
    }

    @Test
    fun `history matches the embedded mock, newest first`() = app {
        val page = client.get("/v1/transactions").json().jsonObject
        assertEquals(golden("transactions"), page["items"])
        assertEquals("null", page["nextCursor"].toString())
    }

    @Test
    fun `pages stitch back into the whole history`() = app {
        val rows = mutableListOf<JsonElement>()
        var cursor: String? = null
        do {
            val page = client.get("/v1/transactions?limit=5" + (cursor?.let { "&cursor=$it" } ?: "")).json().jsonObject
            rows.addAll(page["items"]!!.jsonArray)
            cursor = page["nextCursor"]!!.jsonPrimitive.takeIf { it.isString }?.content
        } while (cursor != null)
        assertEquals(golden("transactions"), JsonArray(rows))
    }

    @Test
    fun `money is a decimal string, never a float`() = app {
        val eur = client.get("/v1/accounts/EUR").json().jsonObject
        assertTrue(eur["balance"]!!.jsonPrimitive.isString)
        assertEquals("4820.55", eur["balance"]!!.jsonPrimitive.content)
    }
}

/** Each server defect: green on clean, red under exactly its own defect. */
class ServerDefectTest {
    private suspend fun HttpClient.balances(defects: String? = null): List<String> =
        get("/v1/accounts") { defects?.let { header(DEFECTS_HEADER, it) } }
            .json().jsonArray.map { it.jsonObject["balance"]!!.jsonPrimitive.content }

    private suspend fun HttpClient.historyIds(defects: String? = null): List<String> =
        get("/v1/transactions") { defects?.let { header(DEFECTS_HEADER, it) } }
            .json().jsonObject["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `balanceReadReturnsZero zeroes balances`() = app {
        assertTrue(client.balances().none { it == "0" })
        assertTrue(client.balances(ServerDefects.balanceReadReturnsZero).all { it == "0" })
        assertEquals(16, client.historyIds(ServerDefects.balanceReadReturnsZero).size, "and nothing else")
    }

    @Test
    fun `transactionsDupOnFetch doubles every row`() = app {
        assertEquals(16, client.historyIds().toSet().size)
        assertEquals(16, client.historyIds().size)
        assertEquals(32, client.historyIds(ServerDefects.transactionsDupOnFetch).size)
        assertTrue(client.balances(ServerDefects.transactionsDupOnFetch).none { it == "0" }, "and nothing else")
    }
}

class ChaosApiTest {
    @Test
    fun `the catalog lists every server defect and the clean profile`() = app {
        val catalog = client.get("/chaos/catalog").json().jsonObject
        val ids = catalog["defects"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(ServerDefects.catalog.specs.map { it.id }, ids)
        assertEquals(JsonArray(emptyList()), catalog["profiles"]!!.jsonObject["clean"])
    }

    @Test
    fun `a session's defects apply only to requests that carry it`() = app {
        val id = client.newSession("""{"defects":["balanceReadReturnsZero"]}""")
        val withSession = client.get("/v1/accounts/EUR") { header(SESSION_HEADER, id) }.json().jsonObject
        val without = client.get("/v1/accounts/EUR").json().jsonObject
        assertEquals("0", withSession["balance"]!!.jsonPrimitive.content)
        assertEquals("4820.55", without["balance"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update switches defects on a live session, reset keeps them`() = app {
        val id = client.newSession()
        client.put("/chaos/sessions/$id") { contentType(ContentType.Application.Json); setBody("""{"defects":["transactionsDupOnFetch"]}""") }
        client.post("/chaos/sessions/$id/reset")
        val session = client.get("/chaos/sessions/$id").json().jsonObject
        assertEquals("""["transactionsDupOnFetch"]""", session["defects"].toString())
    }

    @Test
    fun `mistakes come back as problem+json`() = app {
        suspend fun problem(r: HttpResponse, status: HttpStatusCode, type: String) {
            assertEquals(status, r.status)
            assertEquals("application/problem+json", r.contentType()?.withoutParameters().toString())
            assertEquals(type, r.json().jsonObject["type"]!!.jsonPrimitive.content)
        }
        problem(client.get("/v1/accounts/JPY"), HttpStatusCode.NotFound, "unknownAccount")
        problem(client.get("/v1/accounts") { header(SESSION_HEADER, "nope") }, HttpStatusCode.NotFound, "unknownSession")
        problem(client.get("/v1/accounts") { header(DEFECTS_HEADER, "typo") }, HttpStatusCode.BadRequest, "unknownChaosValue")
        problem(client.get("/v1/transactions?cursor=-1"), HttpStatusCode.BadRequest, "invalidCursor")
        problem(client.get("/v1/transactions?limit=0"), HttpStatusCode.BadRequest, "invalidLimit")
        problem(
            client.post("/chaos/sessions") { contentType(ContentType.Application.Json); setBody("""{"network":"offline"}""") },
            HttpStatusCode.BadRequest, "unknownChaosValue",
        )
        problem(
            client.post("/chaos/sessions") { contentType(ContentType.Application.Json); setBody("""{"profile":"nope"}""") },
            HttpStatusCode.BadRequest, "unknownChaosValue",
        )
        problem(client.delete("/chaos/sessions/default"), HttpStatusCode.Conflict, "defaultSession")
    }

    @Test
    fun `a deleted session is gone`() = app {
        val id = client.newSession()
        assertEquals(HttpStatusCode.NoContent, client.delete("/chaos/sessions/$id").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/chaos/sessions/$id").status)
    }

    @Test
    fun `the Slow network delays every bank call`() = app(slowDelayMs = 400) {
        val id = client.newSession("""{"network":"slow"}""")
        val started = System.nanoTime()
        client.get("/v1/accounts") { header(SESSION_HEADER, id) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs >= 400, "took $tookMs ms")
    }

    @Test
    fun `health answers without a session`() = app {
        val body = client.get("/health").json() as JsonObject
        assertNull(body["error"])
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
    }
}
