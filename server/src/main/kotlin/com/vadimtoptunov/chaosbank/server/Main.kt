package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.SessionStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * `PORT` (default 8080). `CHAOS_DEFAULT_PROFILE` / `CHAOS_DEFAULT_DEFECTS` (comma-separated)
 * switch defects on for the default session, for demos and take-home assignments.
 */
fun main() {
    val catalog = ServerDefects.catalog
    val sessions = SessionStore(catalog, { BankState() })
    val profile = System.getenv("CHAOS_DEFAULT_PROFILE")?.takeIf { it.isNotBlank() }
    val defects = System.getenv("CHAOS_DEFAULT_DEFECTS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    if (profile != null || defects.isNotEmpty()) sessions.update(SessionStore.DEFAULT, profile, defects, null)

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(catalog, sessions) }.start(wait = true)
}
