package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.ActiveDefects
import com.vadimtoptunov.chaosbank.chaos.ChaosSession
import com.vadimtoptunov.chaosbank.chaos.DefectCatalog
import com.vadimtoptunov.chaosbank.chaos.NetworkCondition
import com.vadimtoptunov.chaosbank.chaos.SessionStore
import com.vadimtoptunov.chaosbank.chaos.UnknownChaosValue
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val SESSION_HEADER = "X-Chaos-Session"
const val DEFECTS_HEADER = "X-Chaos-Defects"

/** The extra latency of the "Slow" network, as in the apps' `MockBackend`. */
const val SLOW_DELAY_MS = 3_000L

class ApiError(val status: HttpStatusCode, val type: String, val detail: String) : Exception(detail)

private val json = Json { encodeDefaults = true }

fun Application.module(
    catalog: DefectCatalog = ServerDefects.catalog,
    sessions: SessionStore<BankState> = SessionStore(catalog, { BankState() }),
    slowDelayMs: Long = SLOW_DELAY_MS,
) {
    install(ContentNegotiation) { json(json) }
    install(CallLogging)

    /** Runs [block] against the caller's session, mapping failures to problem+json. */
    suspend fun RoutingContext.banking(block: suspend (BankState, ActiveDefects) -> Unit) = guarded {
        val id = call.request.headers[SESSION_HEADER] ?: SessionStore.DEFAULT
        val session = sessions.get(id) ?: throw ApiError(HttpStatusCode.NotFound, "unknownSession", "no chaos session '$id'")
        val chaos = ActiveDefects.of(catalog, session.config, call.request.headers[DEFECTS_HEADER])
        if (session.config.network == NetworkCondition.slow) delay(slowDelayMs)
        block(session.state, chaos)
    }

    suspend fun RoutingContext.session(): ChaosSession<BankState> {
        val id = call.parameters["id"]!!
        return sessions.get(id) ?: throw ApiError(HttpStatusCode.NotFound, "unknownSession", "no chaos session '$id'")
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        // --- bank ---------------------------------------------------------------

        get("/v1/accounts") {
            banking { bank, chaos -> call.respond(bank.accounts(chaos).map { it.toDto() }) }
        }

        get("/v1/accounts/{currency}") {
            banking { bank, chaos ->
                val raw = call.parameters["currency"]!!
                val currency = Currency.entries.firstOrNull { it.name == raw.uppercase() }
                val account = currency?.let { bank.account(it, chaos) }
                    ?: throw ApiError(HttpStatusCode.NotFound, "unknownAccount", "no account in '$raw'")
                call.respond(account.toDto())
            }
        }

        get("/v1/transactions") {
            banking { bank, chaos ->
                val limit = call.request.queryParameters["limit"]?.let {
                    it.toIntOrNull()?.takeIf { n -> n in 1..100 }
                        ?: throw ApiError(HttpStatusCode.BadRequest, "invalidLimit", "limit must be 1…100")
                }
                val cursor = call.request.queryParameters["cursor"]?.let {
                    it.toIntOrNull()?.takeIf { n -> n >= 0 }
                        ?: throw ApiError(HttpStatusCode.BadRequest, "invalidCursor", "cursor '$it' was not handed out by this API")
                }
                val page = bank.transactions(limit, cursor, chaos)
                call.respond(TransactionPageDto(page.items.map { it.toDto() }, page.nextOffset?.toString()))
            }
        }

        // --- chaos control ------------------------------------------------------

        get("/chaos/catalog") {
            call.respond(
                CatalogDto(
                    catalog.specs.map { DefectDto(it.id, it.title, it.area) },
                    catalog.profiles.mapValues { it.value.sorted() },
                ),
            )
        }

        post("/chaos/sessions") {
            guarded {
                val req = call.receive<SessionRequest>()
                val network = req.network?.let(NetworkCondition::parse) ?: NetworkCondition.normal
                call.respond(HttpStatusCode.Created, sessions.create(req.profile, req.defects, req.seed, network).toDto())
            }
        }

        get("/chaos/sessions/{id}") { guarded { call.respond(session().toDto()) } }

        put("/chaos/sessions/{id}") {
            guarded {
                val req = call.receive<SessionRequest>()
                val id = session().id
                val updated = sessions.update(id, req.profile, req.defects, req.network?.let(NetworkCondition::parse))!!
                call.respond(updated.toDto())
            }
        }

        post("/chaos/sessions/{id}/reset") { guarded { call.respond(sessions.reset(session().id)!!.toDto()) } }

        delete("/chaos/sessions/{id}") {
            guarded {
                val id = session().id
                if (!sessions.delete(id)) throw ApiError(HttpStatusCode.Conflict, "defaultSession", "the default session can't be deleted")
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private suspend fun RoutingContext.guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ApiError) {
        call.respondProblem(e.status, e.type, e.detail)
    } catch (e: UnknownChaosValue) {
        call.respondProblem(HttpStatusCode.BadRequest, "unknownChaosValue", e.message!!)
    } catch (e: SerializationException) {
        call.respondProblem(HttpStatusCode.BadRequest, "invalidBody", e.message ?: "malformed JSON")
    } catch (e: io.ktor.server.plugins.BadRequestException) {
        call.respondProblem(HttpStatusCode.BadRequest, "invalidBody", e.cause?.message ?: e.message ?: "malformed JSON")
    }
}

suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, type: String, detail: String) =
    respondText(
        json.encodeToString(Problem.serializer(), Problem(type, status.description, status.value, detail)),
        ContentType.parse("application/problem+json"),
        status,
    )
