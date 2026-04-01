package com.dihax.androidhttpserver.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SessionManager {
    private val sessions = ConcurrentHashMap<String, String>()

    private const val DEFAULT_USERNAME = "admin"
    private const val DEFAULT_PASSWORD = "admin"

    fun login(username: String, password: String): String? {
        if (username == DEFAULT_USERNAME && password == DEFAULT_PASSWORD) {
            val token = UUID.randomUUID().toString()
            sessions[token] = username
            return token
        }
        return null
    }

    fun logout(token: String) {
        sessions.remove(token)
    }

    fun isValid(token: String): Boolean = sessions.containsKey(token)

    fun getUsername(token: String): String? = sessions[token]
}

suspend fun ApplicationCall.requireAuth(): String? {
    val token = request.cookies["session"]
    if (token == null || !SessionManager.isValid(token)) {
        respond(HttpStatusCode.Unauthorized, Failure(error = "Not authenticated"))
        return null
    }
    return SessionManager.getUsername(token)
}
