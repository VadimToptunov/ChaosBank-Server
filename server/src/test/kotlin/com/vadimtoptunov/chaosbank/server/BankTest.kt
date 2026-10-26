package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.SeededRng
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parity gate: a clean session answers exactly what the apps' embedded mock does. */
class CleanParityTest {
    @Test
    fun `accounts match the embedded mock`() = app {
        assertEquals(golden("accounts"), Api.fresh(client).get("/v1/accounts").json)
    }

    @Test
    fun `each account by currency matches, case-insensitively`() = app {
        val api = Api.fresh(client)
        for (account in golden("accounts").jsonArray) {
            val ccy = account.jsonObject["currency"]!!.jsonPrimitive.content
            assertEquals(account, api.get("/v1/accounts/${ccy.lowercase()}").json)
        }
    }

    @Test
    fun `history matches the embedded mock, newest first`() = app {
        val page = Api.fresh(client).get("/v1/transactions").obj
        assertEquals(golden("transactions"), page["items"])
        assertEquals("null", page["nextCursor"].toString())
    }

    @Test
    fun `pages stitch back into the whole history`() = app {
        val api = Api.fresh(client)
        val rows = mutableListOf<JsonElement>()
        var cursor: String? = null
        do {
            val page = api.get("/v1/transactions?limit=5" + (cursor?.let { "&cursor=$it" } ?: "")).obj
            rows.addAll(page["items"]!!.jsonArray)
            cursor = page["nextCursor"]!!.jsonPrimitive.takeIf { it.isString }?.content
        } while (cursor != null)
        assertEquals(golden("transactions"), JsonArray(rows))
    }

    @Test
    fun `the portfolio is the seeded one`() = app {
        val api = Api.fresh(client)
        val holdings = api.get("/v1/holdings").json!!.jsonArray.map {
            val h = it.jsonObject
            "${h["symbol"]!!.jsonPrimitive.content} ${h["quantity"]!!.jsonPrimitive.content} @ ${h["avgCost"]!!.jsonPrimitive.content}"
        }
        assertEquals(listOf("AAPL 12 @ 150.00", "TSLA 8 @ 280.00", "ETH 3.5 @ 2400.00"), holdings)
        assertEquals(6, api.get("/v1/assets").json!!.jsonArray.size)
    }
}

class MoneyTest {
    @Test
    fun `a transfer debits exactly the amount and heads the history`() = app {
        val api = Api.fresh(client)
        val tx = api.write("/v1/transfers", """{"from":"EUR","amount":"100.00","recipient":" Alex "}""")
        assertEquals(201, tx.status)
        assertEquals("-100.00", tx.str("amount"))
        assertEquals("Transfer to Alex", tx.str("title"))
        assertEquals("4720.55", api.balance("EUR"))
        assertEquals(tx.str("id"), api.historyIds().first())
    }

    @Test
    fun `a deposit credits exactly the amount`() = app {
        val api = Api.fresh(client)
        val tx = api.write("/v1/deposits", """{"to":"GBP","amount":"59.80"}""")
        assertEquals("Add money", tx.str("title"))
        assertEquals("700.00", api.balance("GBP"))
    }

    @Test
    fun `an exchange is priced by the server, fee first, to the cent`() = app {
        val api = Api.fresh(client)
        val fx = api.write("/v1/exchanges", """{"sell":"EUR","get":"USD","amount":"100.00"}""")
        assertEquals("100.00", fx.str("debited"))
        assertEquals("0.50", fx.str("fee"))
        assertEquals("107.46", fx.str("credited"))          // (100 − 0.50) × 1.08
        assertEquals("4720.55", api.balance("EUR"))
        assertEquals("12857.46", api.balance("USD"))
        assertEquals("Exchange EUR → USD", fx.obj["transaction"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a cross rate goes through EUR and rounds half-even`() = app {
        val api = Api.fresh(client)
        val fx = api.write("/v1/exchanges", """{"sell":"GBP","get":"EUR","amount":"100.00"}""")
        assertEquals("1.1764705882", fx.str("rate"))       // 1 / 0.85, to 10 places
        assertEquals("117.06", fx.str("credited"))          // 99.50 × 1.1764705882 = 117.0588…
    }

    @Test
    fun `money can't be overdrawn, negative, zero or finer than a cent`() = app {
        val api = Api.fresh(client)
        assertEquals("insufficientFunds", api.write("/v1/transfers", """{"from":"EUR","amount":"5000.00"}""").type)
        assertEquals("invalidAmount", api.write("/v1/transfers", """{"from":"EUR","amount":"-1.00"}""").type)
        assertEquals("invalidAmount", api.write("/v1/transfers", """{"from":"EUR","amount":"0"}""").type)
        assertEquals("invalidAmount", api.write("/v1/transfers", """{"from":"EUR","amount":"1.001"}""").type)
        assertEquals("unknownAccount", api.write("/v1/transfers", """{"from":"JPY","amount":"1.00"}""").type)
        assertEquals("sameCurrency", api.write("/v1/exchanges", """{"sell":"EUR","get":"EUR","amount":"1.00"}""").type)
        assertEquals("4820.55", api.balance("EUR"), "nothing moved")
    }

    @Test
    fun `a repeated Idempotency-Key replays the first answer and moves money once`() = app {
        val api = Api.fresh(client)
        val body = """{"from":"EUR","amount":"100.00","recipient":"Alex"}"""
        val first = api.post("/v1/transfers", body, key = "k-1")
        val again = api.post("/v1/transfers", body, key = "k-1")
        assertEquals(201, again.status)
        assertEquals(first.json, again.json)
        assertEquals("true", again.headers[REPLAYED_HEADER])
        assertNull(first.headers[REPLAYED_HEADER])
        assertEquals("4720.55", api.balance("EUR"))
    }

    @Test
    fun `the same key on a different request is refused`() = app {
        val api = Api.fresh(client)
        api.post("/v1/transfers", """{"from":"EUR","amount":"100.00"}""", key = "k-2")
        val other = api.post("/v1/transfers", """{"from":"EUR","amount":"200.00"}""", key = "k-2")
        assertEquals(422, other.status)
        assertEquals("idempotencyKeyReused", other.type)
        assertEquals("4720.55", api.balance("EUR"))
    }

    @Test
    fun `a money write without a key is refused`() = app {
        val api = Api.fresh(client)
        assertEquals("idempotencyKeyRequired", api.post("/v1/transfers", """{"from":"EUR","amount":"1.00"}""").type)
    }

    @Test
    fun `the Flaky network fails writes on the seeded sequence and applies none of the failures`() = app {
        val api = Api.fresh(client, """{"network":"flaky","seed":7}""")
        val rng = (0 until 10).map { SeededRng(7L + it).nextDouble() < 0.5 }
        val outcomes = (0 until 10).map { api.write("/v1/deposits", """{"to":"EUR","amount":"1.00"}""").status == 504 }
        assertEquals(rng, outcomes, "same draws as the apps' failIfFlaky")
        val landed = outcomes.count { !it }
        assertEquals((4820.55 + landed).toBigDecimal().setScale(2).toPlainString(), api.balance("EUR"))
        assertTrue(landed in 1..9, "the seed gives a mix")
    }

    @Test
    fun `fx rates are published`() = app {
        val rates = Api.fresh(client).get("/v1/fx/rates").obj
        assertEquals("1.08", rates["rates"]!!.jsonObject["USD"]!!.jsonPrimitive.content)
        assertEquals("0.005", rates["feeRate"]!!.jsonPrimitive.content)
    }
}

class OrderTest {
    private fun holding(holdings: JsonElement, symbol: String) =
        holdings.jsonArray.map { it.jsonObject }.firstOrNull { it["symbol"]!!.jsonPrimitive.content == symbol }

    @Test
    fun `a market buy pays quantity times the shown price and averages the cost`() = app {
        val api = Api.fresh(client)
        val order = api.write("/v1/orders", """{"symbol":"AAPL","side":"buy","quantity":"2","referencePrice":"189.50"}""")
        assertEquals("filled", order.str("status"))
        assertEquals("189.50", order.str("executionPrice"))
        assertEquals("12371.00", api.balance("USD"))                 // 12750.00 − 2 × 189.50
        val aapl = holding(api.get("/v1/holdings").json!!, "AAPL")!!
        assertEquals("14", aapl["quantity"]!!.jsonPrimitive.content)
        assertEquals("155.6428571429", aapl["avgCost"]!!.jsonPrimitive.content)   // (12 × 150 + 379) / 14
        assertEquals("Buy AAPL", api.get("/v1/transactions?limit=1").obj["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(1, api.get("/v1/orders").json!!.jsonArray.size)
    }

    @Test
    fun `a limit order fills at its limit, not the shown price`() = app {
        val api = Api.fresh(client)
        val order = api.write("/v1/orders", """{"symbol":"NVDA","side":"buy","type":"limit","quantity":"1","limitPrice":"800.00","referencePrice":"875.30"}""")
        assertEquals("800.00", order.str("executionPrice"))
        assertEquals("11950.00", api.balance("USD"))
    }

    @Test
    fun `selling everything removes the holding and credits cash`() = app {
        val api = Api.fresh(client)
        api.write("/v1/orders", """{"symbol":"TSLA","side":"sell","quantity":"8","referencePrice":"242.10"}""")
        assertNull(holding(api.get("/v1/holdings").json!!, "TSLA"))
        assertEquals("14686.80", api.balance("USD"))                 // 12750.00 + 8 × 242.10
    }

    @Test
    fun `orders that can't fill are refused`() = app {
        val api = Api.fresh(client)
        assertEquals("insufficientHolding", api.write("/v1/orders", """{"symbol":"TSLA","side":"sell","quantity":"9","referencePrice":"242.10"}""").type)
        assertEquals("insufficientFunds", api.write("/v1/orders", """{"symbol":"BTC","side":"buy","quantity":"1","referencePrice":"64200.00"}""").type)
        assertEquals("unknownAsset", api.write("/v1/orders", """{"symbol":"ZZZZ","side":"buy","quantity":"1","referencePrice":"1.00"}""").type)
        assertEquals("invalidLimitPrice", api.write("/v1/orders", """{"symbol":"AAPL","side":"buy","type":"limit","quantity":"1","referencePrice":"189.50"}""").type)
        assertEquals("invalidSide", api.write("/v1/orders", """{"symbol":"AAPL","side":"hold","quantity":"1","referencePrice":"189.50"}""").type)
        assertEquals("12750.00", api.balance("USD"), "nothing moved")
    }
}
