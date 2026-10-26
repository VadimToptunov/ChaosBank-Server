package com.vadimtoptunov.chaosbank.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * One test per server defect, each a pair: the requirement holds on a clean session, and
 * breaks with exactly that defect on (sent as X-Chaos-Defects, so both halves share a session).
 */
class DefectTest {
    @Test
    fun `balanceReadReturnsZero zeroes balances`() = app {
        val api = Api.fresh(client)
        assertEquals("4820.55", api.balance("EUR"))
        assertEquals("0", api.get("/v1/accounts/EUR", defects = ServerDefects.balanceReadReturnsZero).str("balance"))
    }

    @Test
    fun `transactionsDupOnFetch doubles every row`() = app {
        val api = Api.fresh(client)
        assertEquals(16, api.historyIds().toSet().size)
        assertEquals(16, api.historyIds().size)
        assertEquals(32, api.get("/v1/transactions", defects = ServerDefects.transactionsDupOnFetch).obj["items"]!!.jsonArray.size)
    }

    @Test
    fun `staleHoldingsAfterOrder hides a filled buy`() = app {
        val api = Api.fresh(client)
        api.write("/v1/orders", """{"symbol":"AAPL","side":"buy","quantity":"2","referencePrice":"189.50"}""")
        fun aapl(r: Res) = r.json!!.jsonArray.first { it.jsonObject["symbol"]!!.jsonPrimitive.content == "AAPL" }.jsonObject["quantity"]!!.jsonPrimitive.content
        assertEquals("14", aapl(api.get("/v1/holdings")))
        assertEquals("12", aapl(api.get("/v1/holdings", defects = ServerDefects.staleHoldingsAfterOrder)))
    }

    private suspend fun parallelTransfers(api: Api, n: Int, defects: String?) = coroutineScope {
        (1..n).map { async { api.write("/v1/transfers", """{"from":"EUR","amount":"1.00"}""", defects) } }.awaitAll()
    }

    @Test
    fun `lostUpdateNoLock loses concurrent debits`() = app {
        val clean = Api.fresh(client)
        parallelTransfers(clean, 10, null)
        assertEquals("4810.55", clean.balance("EUR"), "ten debits of 1.00")

        val buggy = Api.fresh(client)
        parallelTransfers(buggy, 10, ServerDefects.lostUpdateNoLock)
        assertTrue(BigDecimal(buggy.balance("EUR")) > BigDecimal("4810.55"), "some debits were lost")
        assertEquals(26, buggy.historyIds().size, "yet all ten transactions were recorded")
    }

    private suspend fun allPagedIds(api: Api, defects: String?): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = api.get("/v1/transactions?limit=5" + (cursor?.let { "&cursor=$it" } ?: ""), defects).obj
            ids += page["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            cursor = page["nextCursor"]!!.jsonPrimitive.takeIf { it.isString }?.content
        } while (cursor != null)
        return ids
    }

    @Test
    fun `paginationCursorSkips loses a row at each page edge`() = app {
        val api = Api.fresh(client)
        assertEquals(api.historyIds(), allPagedIds(api, null))
        val skipped = allPagedIds(api, ServerDefects.paginationCursorSkips)
        assertEquals(14, skipped.size, "16 rows in pages of 5: the rows at offsets 5 and 11 are lost")
    }

    @Test
    fun `retryDuplicate pays again for a repeated key`() = app {
        val api = Api.fresh(client)
        val body = """{"from":"EUR","amount":"100.00"}"""
        api.post("/v1/transfers", body, key = "k")
        api.post("/v1/transfers", body, key = "k")
        assertEquals("4720.55", api.balance("EUR"))
        api.post("/v1/transfers", body, key = "k2", defects = ServerDefects.retryDuplicate)
        api.post("/v1/transfers", body, key = "k2", defects = ServerDefects.retryDuplicate)
        assertEquals("4520.55", api.balance("EUR"), "the repeat paid again")
    }

    @Test
    fun `timeoutAsSuccess moves money behind a 504`() = app {
        val api = Api.fresh(client)
        val body = """{"from":"EUR","amount":"100.00"}"""
        val timedOut = api.post("/v1/transfers", body, key = "t1", defects = ServerDefects.timeoutAsSuccess)
        assertEquals(504, timedOut.status)
        assertEquals("4720.55", api.balance("EUR"), "the 504 transfer was applied")
        val retry = api.post("/v1/transfers", body, key = "t1")
        assertEquals(201, retry.status)
        assertEquals("true", retry.headers[REPLAYED_HEADER])
        assertEquals("4720.55", api.balance("EUR"), "a retry with the key is still safe")
    }

    @Test
    fun `orderFilledTwiceOnRetry fills twice for one key`() = app {
        val api = Api.fresh(client)
        val body = """{"symbol":"AAPL","side":"buy","quantity":"1","referencePrice":"189.50"}"""
        assertEquals(504, api.post("/v1/orders", body, key = "o1", defects = ServerDefects.orderFilledTwiceOnRetry).status)
        assertEquals(201, api.post("/v1/orders", body, key = "o1").status)
        assertEquals("12371.00", api.balance("USD"), "two fills of 189.50 for one intended order")
        assertEquals(2, api.get("/v1/orders").json!!.jsonArray.size)
    }

    @Test
    fun `serverRoundsDown drops the cents of a debit`() = app {
        val api = Api.fresh(client)
        api.write("/v1/transfers", """{"from":"EUR","amount":"100.55"}""")
        assertEquals("4720.00", api.balance("EUR"))
        api.write("/v1/transfers", """{"from":"EUR","amount":"100.55"}""", ServerDefects.serverRoundsDown)
        assertEquals("4620.00", api.balance("EUR"), "only 100 was debited")
    }

    @Test
    fun `negativeAmountCredits lets a negative transfer add money`() = app {
        val api = Api.fresh(client)
        assertEquals("invalidAmount", api.write("/v1/transfers", """{"from":"EUR","amount":"-50.00"}""").type)
        val credit = api.write("/v1/transfers", """{"from":"EUR","amount":"-50.00"}""", ServerDefects.negativeAmountCredits)
        assertEquals(201, credit.status)
        assertEquals("4870.55", api.balance("EUR"))
    }

    private fun expectedCredit(fx: Res): String {
        val amount = BigDecimal(fx.str("debited"))
        return ((amount - amount * BigDecimal("0.005")) * BigDecimal(fx.str("rate"))).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
    }

    @Test
    fun `fxRateStaleOnServer credits at a rate it doesn't report`() = app {
        val api = Api.fresh(client)
        val clean = api.write("/v1/exchanges", """{"sell":"EUR","get":"USD","amount":"100.00"}""")
        assertEquals(expectedCredit(clean), clean.str("credited"))
        val stale = api.write("/v1/exchanges", """{"sell":"EUR","get":"USD","amount":"100.00"}""", ServerDefects.fxRateStaleOnServer)
        assertEquals("1.0800000000", stale.str("rate"), "reports the current rate")
        assertNotEquals(expectedCredit(stale), stale.str("credited"))
        assertEquals("105.47", stale.str("credited"))               // 99.50 × 1.06
    }

    @Test
    fun `contractFieldRenamed breaks the published contract on one endpoint`() = app {
        val api = Api.fresh(client)
        assertEquals("4820.55", api.balance("EUR"))     // Api checks every response against the spec
        val raw = client.get("/v1/accounts/EUR") {
            header(SESSION_HEADER, api.session!!)
            header(HttpHeaders.Authorization, "Bearer ${api.token}")
            header(DEFECTS_HEADER, ServerDefects.contractFieldRenamed)
        }
        val body: JsonElement = Json.parseToJsonElement(raw.bodyAsText())
        val problems = Contract.check("GET", "/v1/accounts/EUR", raw.status.value, raw.headers[HttpHeaders.ContentType], body)
        assertTrue(problems.any { "missing required 'balance'" in it }, problems.toString())
        assertTrue(problems.any { "undocumented property 'amount'" in it }, problems.toString())
    }

    private fun kyc(name: String = KycReview.ACCOUNT_HOLDER, expiry: String = "2031-04-30", selfie: String = "match") =
        """{"document":{"kind":"passport","name":"$name","number":"P4916204","expiry":"$expiry"},"selfie":"$selfie"}"""

    @Test
    fun `kycAcceptsExpiredDocument verifies an expired document`() = app {
        val api = Api.fresh(client)
        assertEquals("rejected", api.post("/v1/kyc/submissions", kyc(expiry = "2024-01-31")).str("status"))
        assertEquals("verified", api.post("/v1/kyc/submissions", kyc(expiry = "2024-01-31"), defects = ServerDefects.kycAcceptsExpiredDocument).str("status"))
    }

    @Test
    fun `kycNameMismatchAccepted verifies someone else's document`() = app {
        val api = Api.fresh(client)
        assertEquals("rejected", api.post("/v1/kyc/submissions", kyc(name = "ALEX MORGAN")).str("status"))
        assertEquals("verified", api.post("/v1/kyc/submissions", kyc(name = "ALEX MORGAN"), defects = ServerDefects.kycNameMismatchAccepted).str("status"))
    }

    @Test
    fun `kycSelfieMismatchAccepted verifies a face that doesn't match`() = app {
        val api = Api.fresh(client)
        assertEquals("rejected", api.post("/v1/kyc/submissions", kyc(selfie = "mismatch")).str("status"))
        assertEquals("verified", api.post("/v1/kyc/submissions", kyc(selfie = "mismatch"), defects = ServerDefects.kycSelfieMismatchAccepted).str("status"))
    }

    @Test
    fun `kycStuckPending leaves a passing check pending`() = app {
        val api = Api.fresh(client)
        assertEquals("verified", api.post("/v1/kyc/submissions", kyc()).str("status"))
        assertEquals("pending", api.post("/v1/kyc/submissions", kyc(), defects = ServerDefects.kycStuckPending).str("status"))
    }

    @Test
    fun `wsPricesOutOfOrder sends ticks out of seq order`() = app {
        val api = Api.fresh(client)
        val clean = streamSeqs(api, 5)
        assertEquals(clean.sorted(), clean)
        val swapped = streamSeqs(api, 5, ServerDefects.wsPricesOutOfOrder)
        assertTrue(swapped.zipWithNext().any { (a, b) -> b < a }, "seq went backwards: $swapped")
    }
}

/** Every defect in the catalog has its test in [DefectTest] (named after its id). */
class DefectCoverageTest {
    @Test
    fun `every catalog defect has a test`() {
        val tests = DefectTest::class.java.declaredMethods.map { it.name }
        val untested = ServerDefects.catalog.specs.map { it.id }.filter { id -> tests.none { it.startsWith("$id ") } }
        assertEquals(emptyList(), untested)
    }

    @Test
    fun `every defect explains itself`() {
        for (spec in ServerDefects.catalog.specs) {
            assertTrue(spec.requirement.isNotBlank() && spec.task.isNotBlank() && spec.category.isNotBlank(), spec.id)
        }
    }
}