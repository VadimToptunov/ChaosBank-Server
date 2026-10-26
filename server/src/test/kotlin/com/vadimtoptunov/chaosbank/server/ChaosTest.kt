package com.vadimtoptunov.chaosbank.server

import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each server defect: green on clean, red under exactly its own defect. */
class ServerDefectTest {
    @Test
    fun `balanceReadReturnsZero zeroes balances and nothing else`() = app {
        val api = Api.fresh(client)
        assertEquals("4820.55", api.balance("EUR"))
        assertEquals("0", api.get("/v1/accounts/EUR", defects = ServerDefects.balanceReadReturnsZero).str("balance"))
        assertEquals(16, api.get("/v1/transactions", defects = ServerDefects.balanceReadReturnsZero).obj["items"]!!.jsonArray.size)
    }

    @Test
    fun `transactionsDupOnFetch doubles every row and nothing else`() = app {
        val api = Api.fresh(client)
        assertEquals(16, api.historyIds().toSet().size)
        assertEquals(16, api.historyIds().size)
        assertEquals(32, api.get("/v1/transactions", defects = ServerDefects.transactionsDupOnFetch).obj["items"]!!.jsonArray.size)
        assertEquals("4820.55", api.get("/v1/accounts/EUR", defects = ServerDefects.transactionsDupOnFetch).str("balance"))
    }
}

class ChaosApiTest {
    @Test
    fun `the catalog lists every server defect and the clean profile`() = app {
        val catalog = Api(client).get("/chaos/catalog").obj
        val ids = catalog["defects"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(ServerDefects.catalog.specs.map { it.id }, ids)
        assertEquals(JsonArray(emptyList()), catalog["profiles"]!!.jsonObject["clean"])
    }

    @Test
    fun `sessions keep their money apart`() = app {
        val one = Api.fresh(client)
        val two = Api.fresh(client)
        one.write("/v1/transfers", """{"from":"EUR","amount":"100.00"}""")
        assertEquals("4720.55", one.balance("EUR"))
        assertEquals("4820.55", two.balance("EUR"))
    }

    @Test
    fun `a session's defects apply only to requests that carry it`() = app {
        val buggy = Api.fresh(client, """{"defects":["balanceReadReturnsZero"]}""")
        val clean = Api.fresh(client)
        assertEquals("0", buggy.balance("EUR"))
        assertEquals("4820.55", clean.balance("EUR"))
    }

    @Test
    fun `reset brings the seed back and keeps the defects`() = app {
        val api = Api.fresh(client, """{"defects":["transactionsDupOnFetch"]}""")
        api.write("/v1/transfers", """{"from":"EUR","amount":"100.00"}""")
        val reset = api.post("/chaos/sessions/${api.session}/reset")
        assertEquals("""["transactionsDupOnFetch"]""", reset.obj["defects"].toString())
        api.signIn()
        assertEquals("4820.55", api.balance("EUR"))
    }

    @Test
    fun `update replaces the defects on a live session`() = app {
        val api = Api.fresh(client)
        api.call(HttpMethod.Put, "/chaos/sessions/${api.session}", """{"defects":["balanceReadReturnsZero"]}""")
        assertEquals("0", api.balance("EUR"))
    }

    @Test
    fun `mistakes come back as documented problems`() = app {
        val api = Api.fresh(client)
        assertEquals("unknownAccount", api.get("/v1/accounts/JPY").type)
        assertEquals("unknownSession", Api(client, "nope").get("/v1/accounts").type)
        assertEquals("unknownChaosValue", api.get("/v1/accounts", defects = "typo").type)
        assertEquals("invalidCursor", api.get("/v1/transactions?cursor=-1").type)
        assertEquals("invalidLimit", api.get("/v1/transactions?limit=0").type)
        assertEquals("unknownChaosValue", Api(client).post("/chaos/sessions", """{"network":"offline"}""").type)
        assertEquals("unknownChaosValue", Api(client).post("/chaos/sessions", """{"profile":"nope"}""").type)
        assertEquals("invalidClock", api.post("/chaos/sessions/${api.session}/clock", """{"advanceSeconds":-1}""").type)
        assertEquals("invalidBody", api.write("/v1/transfers", """{"from":"EUR"}""").type)
        assertEquals("defaultSession", Api(client).call(HttpMethod.Delete, "/chaos/sessions/default").type)
    }

    @Test
    fun `a deleted session is gone`() = app {
        val api = Api.fresh(client)
        assertEquals(204, api.call(HttpMethod.Delete, "/chaos/sessions/${api.session}").status)
        assertEquals(404, api.call(HttpMethod.Get, "/chaos/sessions/${api.session}").status)
    }

    @Test
    fun `the Slow network delays every call`() = app(slowDelayMs = 300) {
        val api = Api.fresh(client, """{"network":"slow"}""")
        val started = System.nanoTime()
        api.get("/v1/accounts")
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs >= 300, "took $tookMs ms")
    }

    @Test
    fun `health, the spec and the docs page are served`() = app {
        assertEquals("ok", Api(client).get("/health").str("status"))
        assertEquals(200, Api(client).get("/openapi.yaml").status)
        assertEquals(200, Api(client).get("/docs").status)
    }
}

/** The spec and the router describe the same API: no undocumented route, no dead entry. */
class ContractCoverageTest {
    @Test
    fun `every route is in openapi_yaml and every documented operation exists`() = testApplication {
        lateinit var app: Application
        application { module(); app = this }
        startApplication()
        val routes = app.plugin(RoutingRoot).getAllRoutes().mapNotNull { route ->
            val method = (route.selector as? HttpMethodRouteSelector)?.method ?: return@mapNotNull null
            "${method.value} ${path(route)}"
        }.toSet()
        assertEquals(Contract.operations, routes)
    }

    private fun path(route: RoutingNode): String {
        val parts = generateSequence(route.parent) { it.parent }.toList().reversed()
            .map { it.selector.toString() }.filter { it.isNotEmpty() && it != "/" }
        return "/" + parts.joinToString("/").trim('/')
    }
}
