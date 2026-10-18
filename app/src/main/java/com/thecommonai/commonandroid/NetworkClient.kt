package com.thecommonai.commonandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

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
    private const val REQUEST_TIMEOUT_MS = 240_000
    private const val CLIENT_VERSION = "common-android/0.1.0"

    fun validateGateway(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("Enter a valid gateway URL.")
        val host = uri.host
        if (host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw IllegalArgumentException("Enter a gateway URL with a host and no credentials or query string.")
        }
        val isLoopback = host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1"
        if (uri.scheme != "https" && !(uri.scheme == "http" && isLoopback)) {
            throw IllegalArgumentException("Use an HTTPS gateway URL. HTTP is allowed only for localhost.")
        }
        return trimmed
    }

    suspend fun ask(
        gateway: String,
        nodeToken: String,
        turns: List<ChatTurn>,
        onDelta: suspend (String) -> Unit,
    ): NetworkAnswer = withContext(Dispatchers.IO) {
        val baseUrl = validateGateway(gateway)
        val connection = (URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = REQUEST_TIMEOUT_MS
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("X-Common-Client", CLIENT_VERSION)
            if (nodeToken.isNotBlank()) {
                setRequestProperty("X-Common-Node-Token", nodeToken)
            }
        }

        try {
            val messages = JSONArray().apply {
                turns.forEach { turn ->
                    put(JSONObject().put("role", turn.role).put("content", turn.content))
                }
            }
            val body = JSONObject()
                .put("model", "auto")
                .put("messages", messages)
                .put("stream", true)
                .toString()

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?.trim()
                    .orEmpty()
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    val extra = if (detail.isBlank()) "" else " Details: $detail"
                    throw IllegalStateException(
                        "This gateway requires contributor access. This app does not register or donate a " +
                            "model. Add an existing node token in Settings, or use a gateway that allows " +
                            "non-contributors.$extra",
                    )
                }
                throw IllegalStateException(
                    "The gateway returned HTTP $status${if (detail.isBlank()) "." else ": $detail"}",
                )
            }

            val node = connection.getHeaderField("X-Common-Node")
            val panel = connection.getHeaderField("X-Common-Panel")
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
            val routeMs = connection.getHeaderField("X-Common-Route-Ms")?.toLongOrNull()
            val answer = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") return@forEach
                    val content = runCatching {
                        val choices = JSONObject(payload).optJSONArray("choices")
                        choices?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            ?.takeIf { it.isNotEmpty() }
                    }.getOrNull()
                    if (content != null) {
                        answer.append(content)
                        onDelta(content)
                    }
                }
            }
            if (answer.isEmpty()) {
                throw IllegalStateException("The network returned no readable answer.")
            }
            NetworkAnswer(answer.toString(), node, panel, routeMs)
        } finally {
            connection.disconnect()
        }
    }
}
