package com.thecommonai.commonmobile

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class NetworkAnswer(
    val text: String,
    val node: String?,
    val panel: List<String>,
    val routeMs: Long?,
)

data class ChatTurn(
    val role: String,
    val content: String,
)

object NetworkClient {
    const val DEFAULT_GATEWAY = "https://gateway-production-b820.up.railway.app"
    private const val REQUEST_TIMEOUT_MS = 240_000L
    private const val CLIENT_VERSION = "common-mobile/0.1.0"
    private val json = Json
    private val client by lazy {
        HttpClient {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
    }

    fun validateGateway(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val uri = runCatching { Url(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("Enter a valid gateway URL.")
        val host = uri.host
        if (host.isBlank() || uri.user != null || uri.password != null ||
            trimmed.substringBefore('#').contains('?') || trimmed.contains('#')
        ) {
            throw IllegalArgumentException("Enter a gateway URL with a host and no credentials or query string.")
        }
        val isLoopback = host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" || host == "::1"
        if (uri.protocol.name != "https" && !(uri.protocol.name == "http" && isLoopback)) {
            throw IllegalArgumentException("Use an HTTPS gateway URL. HTTP is allowed only for localhost.")
        }
        return trimmed
    }

    suspend fun ask(
        gateway: String,
        nodeToken: String,
        turns: List<ChatTurn>,
        onDelta: suspend (String) -> Unit,
    ): NetworkAnswer = withContext(Dispatchers.Default) {
        val baseUrl = validateGateway(gateway)
        val requestBody = buildJsonObject {
            put("model", "auto")
            put("messages", buildJsonArray {
                turns.forEach { turn ->
                    add(buildJsonObject {
                        put("role", turn.role)
                        put("content", turn.content)
                    })
                }
            })
            put("stream", true)
        }
        val response = client.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            header("X-Common-Client", CLIENT_VERSION)
            if (nodeToken.isNotBlank()) {
                header("X-Common-Node-Token", nodeToken)
            }
            setBody(requestBody.toString())
        }

        if (response.status.value !in 200..299) {
            val detail = response.bodyAsText().trim()
            if (response.status == HttpStatusCode.Unauthorized) {
                val extra = if (detail.isBlank()) "" else " Details: $detail"
                throw IllegalStateException(
                    "This gateway requires contributor access. This app does not register or donate a " +
                        "model. Add an existing node token in Settings, or use a gateway that allows " +
                        "non-contributors.$extra",
                )
            }
            throw IllegalStateException(
                "The gateway returned HTTP ${response.status.value}" +
                    if (detail.isBlank()) "." else ": $detail",
            )
        }

        val node = response.headers["X-Common-Node"]
        val panel = response.headers["X-Common-Panel"]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val routeMs = response.headers["X-Common-Route-Ms"]?.toLongOrNull()
        val answer = StringBuilder()
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val content = runCatching {
                json.parseToJsonElement(payload)
                    .jsonObject["choices"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("delta")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
            if (!content.isNullOrEmpty()) {
                answer.append(content)
                onDelta(content)
            }
        }
        if (answer.isEmpty()) {
            throw IllegalStateException("The network returned no readable answer.")
        }
        NetworkAnswer(answer.toString(), node, panel, routeMs)
    }
}
