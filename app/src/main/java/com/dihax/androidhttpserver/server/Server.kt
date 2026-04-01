package com.dihax.androidhttpserver.server

import android.os.Environment
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.Conflict
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.content.PartData
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.cio.CIOApplicationEngine.Configuration
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.utils.io.toByteArray
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.slf4j.event.Level
import java.io.File

typealias CIOEmbeddedServer = EmbeddedServer<CIOApplicationEngine, Configuration>

fun buildServer(port: Int): CIOEmbeddedServer {
    return embeddedServer(CIO, port = port) {
        configureServer()
        configureRouting()
    }
}

private fun resolveAndValidate(path: String): Pair<File, File>? {
    val rootDir = Environment.getExternalStorageDirectory().canonicalFile
    val target = File(path).canonicalFile
    if (!target.absolutePath.startsWith(rootDir.absolutePath)) return null
    return rootDir to target
}

fun Application.configureServer() {
    install(CORS) {
//        anyHost()
//        allowMethod(HttpMethod.Get)
//        allowMethod(HttpMethod.Post)
//        allowMethod(HttpMethod.Put)
//        allowMethod(HttpMethod.Patch)
//        allowMethod(HttpMethod.Delete)
//        allowMethod(HttpMethod.Options)
//        allowHeader(HttpHeaders.ContentType)
//        allowHeader(HttpHeaders.Authorization)
//        allowCredentials = true
    }

    install(OpenApi)

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(BadRequest, Failure(error = cause.message ?: "Invalid request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(
                Conflict,
                Failure(error = cause.message ?: "Request could not be completed")
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                InternalServerError,
                Failure(
                    error = mapOf(
                        "type" to cause::class.simpleName,
                        "message" to cause.message,
                        "localizedMessage" to cause.localizedMessage,
                        "stackTrace" to cause.stackTrace.take(5).map { it.toString() },
                        "cause" to cause.cause?.let {
                            mapOf(
                                "type" to it::class.simpleName,
                                "message" to it.message
                            )
                        }
                    ).toString()
                )
            )
        }
        status(NotFound) { call, status ->
            call.respond(status, Failure(error = "Not Found"))
        }
    }

    install(ContentNegotiation) {
        val module = SerializersModule {}
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            serializersModule = module
        })
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            call.request.path().startsWith("/api")
        }
        format { call ->
            val userAgent = call.request.headers["User-Agent"] ?: "unknown"
            "Method: ${call.request.httpMethod.value}, Path: ${call.request.path()}, User-Agent: $userAgent"
        }
    }
}

fun Application.configureRouting() {
    val webFiles = AssetsResourceProvider("web")

    routing {
        route("/api") {
            post("/login") {
                val request = call.receive<LoginRequest>()
                val token = SessionManager.login(request.username, request.password)
                if (token != null) {
                    call.response.cookies.append(Cookie("session", token, path = "/"))
                    call.respond(Success(data = mapOf("username" to request.username)))
                } else {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        Failure(error = "Invalid username or password")
                    )
                }
            }

            post("/logout") {
                val token = call.request.cookies["session"]
                if (token != null) SessionManager.logout(token)
                call.response.cookies.append(Cookie("session", "", path = "/", maxAge = 0))
                call.respond(Success(data = mapOf("message" to "Logged out")))
            }

            get("/session") {
                val token = call.request.cookies["session"]
                if (token != null && SessionManager.isValid(token)) {
                    call.respond(
                        Success(
                            data = mapOf("username" to (SessionManager.getUsername(token) ?: ""))
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        Failure(error = "Not authenticated")
                    )
                }
            }

            get("/files") {
                call.requireAuth() ?: return@get

                val rootDir = Environment.getExternalStorageDirectory().canonicalFile
                val requestedPath = call.request.queryParameters["path"] ?: rootDir.absolutePath
                val showHidden = call.request.queryParameters["showHidden"] == "true"
                val targetDir = File(requestedPath).canonicalFile

                if (!targetDir.absolutePath.startsWith(rootDir.absolutePath)) {
                    call.respond(HttpStatusCode.Forbidden, Failure(error = "Access denied"))
                    return@get
                }

                if (!targetDir.exists() || !targetDir.isDirectory) {
                    call.respond(NotFound, Failure(error = "Directory not found"))
                    return@get
                }

                val items = targetDir.listFiles()
                    ?.filter { showHidden || !it.name.startsWith(".") }
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.map { file ->
                        FileItem(
                            name = file.name,
                            path = file.absolutePath,
                            isDirectory = file.isDirectory,
                            size = if (file.isFile) file.length() else 0,
                            lastModified = file.lastModified(),
                        )
                    } ?: emptyList()

                val parent = if (targetDir.absolutePath != rootDir.absolutePath) {
                    targetDir.parent
                } else null

                call.respond(
                    Success(
                        data = FileListResponse(
                            path = targetDir.absolutePath,
                            parent = parent,
                            items = items,
                        )
                    )
                )
            }

            get("/files/download") {
                call.requireAuth() ?: return@get

                val requestedPath = call.request.queryParameters["path"]
                if (requestedPath == null) {
                    call.respond(BadRequest, Failure(error = "Path parameter required"))
                    return@get
                }

                val (_, targetFile) = resolveAndValidate(requestedPath) ?: run {
                    call.respond(HttpStatusCode.Forbidden, Failure(error = "Access denied"))
                    return@get
                }

                if (!targetFile.exists() || !targetFile.isFile) {
                    call.respond(NotFound, Failure(error = "File not found"))
                    return@get
                }

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, targetFile.name
                    ).toString()
                )
                call.respondFile(targetFile)
            }

            // Upload files via multipart
            post("/files/upload") {
                call.requireAuth() ?: return@post

                val uploadPath = call.request.queryParameters["path"]
                    ?: Environment.getExternalStorageDirectory().absolutePath

                val (_, targetDir) = resolveAndValidate(uploadPath) ?: run {
                    call.respond(HttpStatusCode.Forbidden, Failure(error = "Access denied"))
                    return@post
                }

                if (!targetDir.exists() || !targetDir.isDirectory) {
                    call.respond(NotFound, Failure(error = "Directory not found"))
                    return@post
                }

                val uploadedFiles = mutableListOf<String>()

                try {
                    val multipart = call.receiveMultipart()
                    var part = multipart.readPart()
                    while (part != null) {
                        if (part is PartData.FileItem) {
                            val fileName = part.originalFileName ?: "uploaded_file"
                            val file = File(targetDir, fileName)
                            val bytes = part.provider().toByteArray()
                            file.writeBytes(bytes)
                            uploadedFiles.add(fileName)
                        }
                        part.dispose()
                        part = multipart.readPart()
                    }
                } catch (e: Exception) {
                    call.respond(
                        InternalServerError,
                        Failure(error = "Upload failed: ${e::class.simpleName}: ${e.message}")
                    )
                    return@post
                }

                call.respond(
                    Success(data = mapOf("uploaded" to uploadedFiles, "count" to uploadedFiles.size.toString()))
                )
            }

            // Read file content for editing
            get("/files/content") {
                call.requireAuth() ?: return@get

                val requestedPath = call.request.queryParameters["path"]
                if (requestedPath == null) {
                    call.respond(BadRequest, Failure(error = "Path parameter required"))
                    return@get
                }

                val (_, targetFile) = resolveAndValidate(requestedPath) ?: run {
                    call.respond(HttpStatusCode.Forbidden, Failure(error = "Access denied"))
                    return@get
                }

                if (!targetFile.exists() || !targetFile.isFile) {
                    call.respond(NotFound, Failure(error = "File not found"))
                    return@get
                }

                if (targetFile.length() > 2 * 1024 * 1024) {
                    call.respond(BadRequest, Failure(error = "File too large to edit (max 2MB)"))
                    return@get
                }

                val content = targetFile.readText()
                call.respondText(content, ContentType.Text.Plain)
            }

            // Save file content
            put("/files/content") {
                call.requireAuth() ?: return@put

                val requestedPath = call.request.queryParameters["path"]
                if (requestedPath == null) {
                    call.respond(BadRequest, Failure(error = "Path parameter required"))
                    return@put
                }

                val (_, targetFile) = resolveAndValidate(requestedPath) ?: run {
                    call.respond(HttpStatusCode.Forbidden, Failure(error = "Access denied"))
                    return@put
                }

                if (targetFile.parentFile?.exists() != true) {
                    call.respond(NotFound, Failure(error = "Parent directory not found"))
                    return@put
                }

                val body = call.receive<FileContentRequest>()
                targetFile.writeText(body.content)

                call.respond(Success(data = mapOf("path" to targetFile.absolutePath)))
            }

            // Create new file or folder
            post("/files/create") {
                call.requireAuth() ?: return@post

                val body = call.receive<CreateRequest>()

                val (_, target) = resolveAndValidate(body.path) ?: run {
                    call.respond(HttpStatusCode.Forbidden, Failure(error = "Access denied"))
                    return@post
                }

                if (target.exists()) {
                    call.respond(Conflict, Failure(error = "Already exists"))
                    return@post
                }

                if (target.parentFile?.exists() != true) {
                    call.respond(NotFound, Failure(error = "Parent directory not found"))
                    return@post
                }

                if (body.isDirectory) {
                    target.mkdir()
                } else {
                    target.createNewFile()
                }

                call.respond(
                    Success(data = mapOf("path" to target.absolutePath, "isDirectory" to body.isDirectory.toString()))
                )
            }

            // Delete file or folder
            delete("/files") {
                call.requireAuth() ?: return@delete

                val requestedPath = call.request.queryParameters["path"]
                if (requestedPath == null) {
                    call.respond(BadRequest, Failure(error = "Path parameter required"))
                    return@delete
                }

                val (rootDir, target) = resolveAndValidate(requestedPath) ?: run {
                    call.respond(HttpStatusCode.Forbidden, Failure(error = "Access denied"))
                    return@delete
                }

                if (target.absolutePath == rootDir.absolutePath) {
                    call.respond(HttpStatusCode.Forbidden, Failure(error = "Cannot delete root"))
                    return@delete
                }

                if (!target.exists()) {
                    call.respond(NotFound, Failure(error = "Not found"))
                    return@delete
                }

                val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()

                if (deleted) {
                    call.respond(Success(data = mapOf("deleted" to target.absolutePath)))
                } else {
                    call.respond(InternalServerError, Failure(error = "Failed to delete"))
                }
            }

            get("/status", {
                description = "Checks the health/status of the API"
            }) {
                call.respond(
                    Success(
                        data = mapOf(
                            "status" to "ok",
                        )
                    )
                )
            }

            route("json") {
                openApi()
            }

            route("/swagger") {
                swaggerUI("/api/json") {
                }
            }
        }

        get {
            call.respondRedirect("/files.html", permanent = false)
        }

        get("/{file...}") {
            val path = call.parameters.getAll("file")?.joinToString("/")
            if (path == null) {
                call.respond(NotFound)
                return@get
            }
            val resource = webFiles.getResource(path)
            if (resource != null) call.respondAssetNoCache(resource)
            else call.respond(NotFound)
        }
    }
}
