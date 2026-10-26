package com.vadimtoptunov.chaosbank.server

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.fail

fun golden(name: String): JsonElement =
    Json.parseToJsonElement(Contract::class.java.getResource("/golden/$name.json")!!.readText())

fun app(slowDelayMs: Long = SLOW_DELAY_MS, test: suspend ApplicationTestBuilder.() -> Unit) =
    testApplication {
        application { module(slowDelayMs = slowDelayMs) }
        test()
    }

/** One response, already checked against openapi.yaml. */
class Res(val status: Int, val json: JsonElement?, val headers: io.ktor.http.Headers) {
    val obj: JsonObject get() = json!!.jsonObject
    fun str(key: String): String = obj[key]!!.jsonPrimitive.content
    val type: String get() = str("type")
}

/**
 * A test client for one chaos session. Every call is validated against the OpenAPI
 * contract, so every test is also a contract test: an undocumented status, field or
 * content type fails it.
 */
class Api(private val client: HttpClient, val session: String? = null) {
    var token: String? = null

    suspend fun call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
        defects: String? = null,
        auth: Boolean = true,
    ): Res {
        val r = client.request(path) {
            this.method = method
            session?.let { header(SESSION_HEADER, it) }
            defects?.let { header(DEFECTS_HEADER, it) }
            idempotencyKey?.let { header(IDEMPOTENCY_HEADER, it) }
            if (auth) token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        val text = r.bodyAsText()
        val ct = r.headers[HttpHeaders.ContentType]
        val json = if (ct != null && "json" in ct && text.isNotEmpty()) Json.parseToJsonElement(text) else null
        val problems = Contract.check(method.value, path, r.status.value, ct, json)
        if (problems.isNotEmpty()) fail("contract: " + problems.joinToString("; ") + "\nbody: $text")
        return Res(r.status.value, json, r.headers)
    }

    suspend fun get(path: String, defects: String? = null) = call(HttpMethod.Get, path, defects = defects)

    suspend fun post(path: String, body: String? = null, key: String? = null, defects: String? = null) =
        call(HttpMethod.Post, path, body, key, defects)

    /** A money write with a fresh Idempotency-Key. */
    suspend fun write(path: String, body: String, defects: String? = null) =
        post(path, body, UUID.randomUUID().toString(), defects)

    /** login → OTP 424242 → keeps the access token. Returns the token pair. */
    suspend fun signIn(username: String = "alex"): Res {
        val challenge = post("/v1/auth/login", """{"username":"$username","password":"pw"}""").str("challengeId")
        val pair = post("/v1/auth/otp", """{"challengeId":"$challenge","code":"${AuthRules.OTP_CODE}"}""")
        token = pair.str("accessToken")
        return pair
    }

    suspend fun balance(ccy: String): String = get("/v1/accounts/$ccy").str("balance")

    suspend fun historyIds(): List<String> =
        get("/v1/transactions").obj["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    companion object {
        /** A signed-in client on a fresh session ([sessionBody] as for POST /chaos/sessions). */
        suspend fun fresh(client: HttpClient, sessionBody: String = "{}"): Api {
            val id = Api(client).post("/chaos/sessions", sessionBody).str("id")
            return Api(client, id).also { it.signIn() }
        }
    }
}
