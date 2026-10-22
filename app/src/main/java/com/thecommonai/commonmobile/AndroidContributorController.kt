package com.thecommonai.commonmobile

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.seconds

private const val RUNTIME_NAME = "llama.cpp-android"
private const val MAX_REQUEST_BYTES = 1024 * 1024
private const val MAX_RESPONSE_BYTES = 1024 * 1024
private const val MAX_MESSAGE_BYTES = 64 * 1024
private const val MAX_CHUNK_BYTES = 8 * 1024
private const val MAX_MODEL_BYTES = 5L * 1024 * 1024 * 1024
private const val PROTOCOL_VERSION = 1
private val advertisedCapabilities = listOf(
    "gguf:q2_0",
    "gguf:q2_0_g64",
    "ternary:hadamard-activations",
)
private val json = Json { ignoreUnknownKeys = true }

private data class Assignment(
    val candidate: ContributorCandidate,
    val sourceUrl: String,
    val sha256: String,
    val capabilityText: String,
    val domainTags: List<String>,
)

private data class Enrollment(
    val gateway: String,
    val nodeId: String,
    val nodeName: String,
    val nodeToken: String,
    val modelId: String,
    val modelFormat: String,
    val modelPath: String,
    val runtimeCapabilities: List<String>,
)

class AndroidContributorController(context: Context) : ContributorController {
    private val appContext = context.applicationContext
    private val store = SecureEnrollmentStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
        install(WebSockets)
    }
    private val downloadClient = HttpClient(OkHttp) {
        followRedirects = true
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = null
            socketTimeoutMillis = 120_000
        }
    }
    private val engineLazy = lazy { LlamaEngine() }
    private val engine by engineLazy
    private val mutableStatus = MutableStateFlow(ContributorStatus())
    override val status: StateFlow<ContributorStatus> = mutableStatus.asStateFlow()
    private val sendMutex = Mutex()
    private val engineLifecycleMutex = Mutex()
    @Volatile
    private var connectionJob: Job? = null
    @Volatile
    private var foregroundJob: Job? = null
    @Volatile
    private var startJob: Job? = null
    @Volatile
    private var foreground = false
    @Volatile
    private var stopRequested = false

    override suspend fun checkCompatibility(gateway: String): ContributorCandidate =
        withContext(Dispatchers.IO) {
            val base = NetworkClient.validateGateway(gateway)
            mutableStatus.value = mutableStatus.value.copy(message = "Checking Android runtime and network catalogue.")
            val assignment = fetchAssignment(base)
            mutableStatus.value = mutableStatus.value.copy(
                message = "Compatible model found: ${assignment.candidate.displayName}. Review it before starting.",
            )
            assignment.candidate
        }

    override suspend fun start(gateway: String, candidate: ContributorCandidate) {
        val operation = currentCoroutineContext()[Job]
            ?: throw IllegalStateException("Contribution must run in a managed coroutine.")
        synchronized(this) {
            check(foreground) { "Bring Common Mobile to the foreground before starting contribution." }
            check(startJob?.isActive != true) { "Android model contribution is already starting." }
            startJob = operation
        }
        try {
            val base = NetworkClient.validateGateway(gateway)
            stopRequested = false
            val previous = store.read()
            if (previous != null && previous.gateway != base) {
                throw IllegalStateException(
                    "Stop contribution at ${previous.gateway} before registering this device with a different gateway.",
                )
            }
            val assignment = fetchAssignment(base)
            if (assignment.candidate != candidate) {
                throw IllegalStateException(
                    "The network assignment changed. Check model compatibility again before starting.",
                )
            }
            mutableStatus.value = ContributorStatus(
                message = "Downloading and verifying ${candidate.displayName}.",
            )
            val modelFile = downloadModel(assignment)
            mutableStatus.value = ContributorStatus(message = "Loading ${candidate.displayName} on this device.")
            engineLifecycleMutex.withLock {
                engine.load(modelFile, candidate.id.startsWith("bonsai2-"))
            }

            val nodeName = previous?.nodeName ?: "android-${UUID.randomUUID()}"
            val node = registerNode(base, nodeName, previous?.nodeToken, assignment)
            val enrollment = Enrollment(
                gateway = base,
                nodeId = node.first,
                nodeName = nodeName,
                nodeToken = node.second,
                modelId = candidate.id,
                modelFormat = candidate.modelFormat,
                modelPath = modelFile.absolutePath,
                runtimeCapabilities = advertisedCapabilities,
            )
            try {
                store.write(enrollment)
            } catch (storageFailure: Exception) {
                var deregistrationFailure: Exception? = null
                try {
                    deregister(enrollment)
                } catch (failure: Exception) {
                    deregistrationFailure = failure
                }
                val detail = if (deregistrationFailure == null) {
                    "Node registration was cancelled."
                } else {
                    "Node registration could not be cancelled: ${deregistrationFailure?.message}"
                }
                throw IllegalStateException(
                    "Could not securely save the contributor credential. $detail",
                    storageFailure,
                )
            }
            mutableStatus.value = ContributorStatus(
                message = "Registered ${candidate.displayName}; connecting to the gateway.",
                enrolled = true,
            )
            if (foreground) launchConnection(enrollment)
        } catch (failure: Exception) {
            if (engineLazy.isInitialized()) {
                withContext(NonCancellable) {
                    engineLifecycleMutex.withLock { engine.free() }
                }
            }
            throw failure
        } finally {
            synchronized(this) {
                if (startJob === operation) startJob = null
            }
        }
    }

    override suspend fun stop() {
        val enrollment = store.read()
            ?: run {
                mutableStatus.value = ContributorStatus(message = "Not contributing.")
                return
            }
        stopRequested = true
        engine.cancel()
        connectionJob?.cancel()
        connectionJob?.join()
        connectionJob = null
        engineLifecycleMutex.withLock { engine.free() }
        try {
            deregister(enrollment)
            store.clear()
            mutableStatus.value = ContributorStatus(message = "Contribution stopped and node deregistered.")
        } catch (failure: Exception) {
            mutableStatus.value = ContributorStatus(
                message = "Could not deregister the node. Its secure credentials are retained for retry.",
                enrolled = true,
            )
            throw failure
        }
    }

    fun onAppForeground() {
        foreground = true
        if (connectionJob?.isActive == true || foregroundJob?.isActive == true || stopRequested) return
        foregroundJob = scope.launch {
            val enrollment = runCatching { store.read() }.getOrElse { failure ->
                mutableStatus.value = ContributorStatus(
                    message = failure.message ?: "Could not read the secure contributor registration.",
                )
                return@launch
            } ?: return@launch
            try {
                mutableStatus.value = ContributorStatus(
                    message = "Loading ${enrollment.modelId}; contribution reconnects while the app is active.",
                    enrolled = true,
                )
                engineLifecycleMutex.withLock {
                    engine.load(File(enrollment.modelPath), enrollment.modelId.startsWith("bonsai2-"))
                }
                if (foreground) launchConnection(enrollment)
            } catch (failure: Exception) {
                mutableStatus.value = ContributorStatus(
                    message = failure.message ?: "Could not restore the local model.",
                    enrolled = true,
                )
            }
        }
    }

    @Synchronized
    fun onAppBackground() {
        foreground = false
        val previous = connectionJob
        connectionJob = null
        val restoring = foregroundJob
        foregroundJob = null
        val starting = startJob
        startJob = null
        starting?.cancel()
        if (engineLazy.isInitialized()) engine.cancel()
        scope.launch {
            starting?.join()
            restoring?.cancelAndJoin()
            previous?.cancel()
            previous?.join()
            if (engineLazy.isInitialized() && !foreground) {
                engineLifecycleMutex.withLock {
                    if (!foreground) {
                        engine.cancel()
                        engine.free()
                    }
                }
            }
            val enrollment = runCatching { store.read() }.getOrNull()
            if (enrollment != null) {
                mutableStatus.value = ContributorStatus(
                    message = "Paused while Common Mobile is not active; the gateway lease will expire.",
                    enrolled = true,
                )
            }
        }
    }

    fun close() {
        foreground = false
        stopRequested = true
        val previous = connectionJob
        connectionJob = null
        val restoring = foregroundJob
        foregroundJob = null
        val starting = startJob
        startJob = null
        starting?.cancel()
        client.close()
        downloadClient.close()
        if (engineLazy.isInitialized()) {
            engine.cancel()
            CoroutineScope(Dispatchers.IO).launch {
                starting?.join()
                restoring?.cancelAndJoin()
                previous?.cancelAndJoin()
                engineLifecycleMutex.withLock { engine.free() }
            }
        } else {
            starting?.cancel()
            restoring?.cancel()
            previous?.cancel()
        }
        scope.cancel()
    }

    private suspend fun fetchAssignment(gateway: String): Assignment {
        val catalogueResponse = client.get("$gateway/catalogue")
        val catalogueText = catalogueResponse.bodyAsText()
        if (catalogueResponse.status.value !in 200..299) {
            throw IllegalStateException("The catalogue request returned HTTP ${catalogueResponse.status.value}.")
        }
        val catalogue = json.parseToJsonElement(catalogueText).jsonArray
        val assignmentBody = buildJsonObject {
            put("platform", "android")
            putJsonArray("runtime_capabilities") {
                advertisedCapabilities.forEach { add(JsonPrimitive(it)) }
            }
            putJsonObject("hardware") {
                val memory = ActivityManager.MemoryInfo()
                (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .getMemoryInfo(memory)
                val stat = StatFs(appContext.filesDir.absolutePath)
                put("total_ram_gb", memory.totalMem / 1_073_741_824.0)
                put("available_ram_gb", memory.availMem / 1_073_741_824.0)
                put("gpu_present", false)
                put("vram_gb", 0)
                put("free_disk_gb", stat.availableBytes / 1_073_741_824.0)
                put("cpu_cores", Runtime.getRuntime().availableProcessors())
                put("os", "Android ${Build.VERSION.RELEASE}")
            }
        }
        val response = client.post("$gateway/assign") {
            contentType(ContentType.Application.Json)
            setBody(assignmentBody.toString())
        }
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw IllegalStateException(
                "Android model assignment failed (HTTP ${response.status.value}): " +
                    responseText.take(512),
            )
        }
        val result = json.parseToJsonElement(responseText).jsonObject
        val id = result.string("catalogue_id")
        val model = catalogue.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == id }
            ?.jsonObject
            ?: throw IllegalStateException("The assigned model is missing from the network catalogue.")
        val format = result.stringOrNull("weight_format")
            ?: throw IllegalStateException("The assignment omitted the model weight format; refusing to guess.")
        val catalogueFormat = model.stringOrNull("weight_format")
        if (format != catalogueFormat) {
            throw IllegalStateException("The assignment and catalogue disagree about the model weight format.")
        }
        val required = result.stringList("runtime_requirements")
        val catalogueRequirements = model.stringList("runtime_requirements")
        if (required != catalogueRequirements) {
            throw IllegalStateException("The assignment and catalogue disagree about runtime requirements.")
        }
        try {
            ContributorModelPolicy.validate(
                catalogueId = id,
                androidSupported = model["android_supported"]?.jsonPrimitive?.contentOrNull == "true",
                modelFormat = format,
                requirements = required,
                runtimeCapabilities = advertisedCapabilities,
            )
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException(failure.message ?: "The selected model is not compatible with this runtime.")
        }
        val source = model.string("source")
        if (!source.startsWith("gguf:")) {
            throw IllegalStateException("Android contribution requires a catalogue-pinned GGUF source.")
        }
        val pinned = source.removePrefix("gguf:").split("#", limit = 2)
        if (pinned.size != 2 || !pinned[1].matches(Regex("[a-fA-F0-9]{64}"))) {
            throw IllegalStateException("The catalogue model source does not contain a valid SHA-256 checksum.")
        }
        val sourceUrl = runCatching { Url(pinned[0]) }.getOrNull()
            ?: throw IllegalStateException("The catalogue model source URL is invalid.")
        if (!isSecureModelUrl(sourceUrl)) {
            throw IllegalStateException("The catalogue model source must use HTTPS, except for localhost development.")
        }
        val displayName = result.string("display_name")
        val candidate = ContributorCandidate(
            id = id,
            displayName = displayName,
            modelFormat = format,
            runtimeRequirements = required,
            runtimeNotes = result.stringOrNull("runtime_notes"),
            reason = result.string("reason"),
        )
        return Assignment(
            candidate = candidate,
            sourceUrl = sourceUrl.toString(),
            sha256 = pinned[1].lowercase(),
            capabilityText = result.string("capability_text"),
            domainTags = result.stringList("domain_tags"),
        )
    }

    private suspend fun downloadModel(assignment: Assignment): File = withContext(Dispatchers.IO) {
        val folder = File(appContext.filesDir, "contributor-models")
        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException("Could not create the private model storage directory.")
        }
        val destination = File(folder, "${assignment.candidate.id}-${assignment.sha256}.gguf")
        if (destination.isFile && destination.length() > 0 && sha256(destination) == assignment.sha256) {
            return@withContext destination
        }
        destination.delete()
        val free = StatFs(folder.absolutePath).availableBytes
        val contentLength = downloadClient.get(assignment.sourceUrl).let { response ->
            if (response.status.value !in 200..299) {
                throw IllegalStateException("Model download returned HTTP ${response.status.value}.")
            }
            if (!isSecureModelUrl(response.call.request.url)) {
                throw IllegalStateException("The model download redirected to an insecure URL.")
            }
            val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (length == null || length <= 0 || length > MAX_MODEL_BYTES) {
                throw IllegalStateException("The model download has a missing or unsupported size.")
            }
            if (free < length + 512L * 1024 * 1024) {
                throw IllegalStateException("There is not enough free storage for the model and download verification.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                FileOutputStream(destination).use { output ->
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = channel.readAvailable(buffer, 0, buffer.size)
                        if (size == -1) break
                        if (size == 0) continue
                        received += size
                        if (received > length || received > MAX_MODEL_BYTES) {
                            throw IllegalStateException("The model response exceeded its declared size.")
                        }
                        digest.update(buffer, 0, size)
                        output.write(buffer, 0, size)
                    }
                    output.fd.sync()
                    if (received != length) {
                        throw IllegalStateException("The model download ended before all bytes arrived.")
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (actual != assignment.sha256) {
                    throw IllegalStateException("The downloaded model failed its catalogue SHA-256 check.")
                }
            } catch (failure: Exception) {
                destination.delete()
                throw failure
            }
            length
        }
        if (contentLength <= 0) {
            destination.delete()
            throw IllegalStateException("The model download was empty.")
        }
        destination
    }

    private suspend fun registerNode(
        gateway: String,
        name: String,
        previousToken: String?,
        assignment: Assignment,
    ): Pair<String, String> {
        val candidate = assignment.candidate
        val body = buildJsonObject {
            put("name", name)
            put("operator", "Common Mobile Android contributor")
            put("model_name", candidate.id)
            put("catalogue_id", candidate.id)
            put("endpoint_url", null as String?)
            put("transport", "android_ws")
            put("capability_text", assignment.capabilityText)
            putJsonArray("domain_tags") { assignment.domainTags.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("runtime_capabilities") {
                advertisedCapabilities.forEach { add(JsonPrimitive(it)) }
            }
            put("runtime_name", RUNTIME_NAME)
            put("runtime_version", engine.version)
            put("model_format", candidate.modelFormat)
            put("client", "common-mobile-android")
            put("region", null as String?)
            put("cost_per_1k", 0)
        }
        val response = client.post("$gateway/nodes") {
            contentType(ContentType.Application.Json)
            header("X-Common-Client", "common-mobile-android/0.1.0")
            if (!previousToken.isNullOrBlank()) header("X-Common-Node-Token", previousToken)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Node registration failed (HTTP ${response.status.value}): ${text.take(512)}")
        }
        val registered = json.parseToJsonElement(text).jsonObject
        if (registered.stringOrNull("transport") != "android_ws" ||
            registered.stringOrNull("model_name") != candidate.id
        ) {
            throw IllegalStateException("The gateway returned a registration for a different model or transport.")
        }
        val nodeId = registered.string("id")
        val token = registered.string("node_token")
        UUID.fromString(nodeId)
        if (token.length !in 24..256) {
            throw IllegalStateException("The gateway returned an invalid node credential.")
        }
        return nodeId to token
    }

    private suspend fun deregister(enrollment: Enrollment) {
        val response = client.delete("${enrollment.gateway}/nodes/${enrollment.nodeId}") {
            header("X-Common-Node-Token", enrollment.nodeToken)
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException(
                "Node deregistration failed (HTTP ${response.status.value}): ${response.bodyAsText().take(512)}",
            )
        }
    }

    @Synchronized
    private fun launchConnection(enrollment: Enrollment) {
        if (!foreground || stopRequested || connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            var retrySeconds = 2L
            while (isActive && foreground && !stopRequested) {
                try {
                    mutableStatus.value = ContributorStatus(
                        message = "Connecting to Common Network as ${enrollment.modelId}.",
                        enrolled = true,
                    )
                    runConnection(enrollment)
                    if (isActive && foreground && !stopRequested) {
                        retrySeconds = 2
                        mutableStatus.value = ContributorStatus(
                            message = "Connection closed; reconnecting while the app is active.",
                            enrolled = true,
                        )
                        delay(retrySeconds.seconds)
                        retrySeconds = (retrySeconds * 2).coerceAtMost(60)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mutableStatus.value = ContributorStatus(
                        message = "Connection lost; reconnecting while the app is active.",
                        enrolled = true,
                    )
                    delay(retrySeconds.seconds)
                    retrySeconds = (retrySeconds * 2).coerceAtMost(60)
                }
            }
        }
    }

    private suspend fun runConnection(enrollment: Enrollment) {
        val gatewayUri = io.ktor.http.Url(enrollment.gateway)
        val protocol = if (gatewayUri.protocol.name == "https") "wss" else "ws"
        val defaultPort = if (protocol == "wss") 443 else 80
        val port = if (gatewayUri.port == defaultPort) "" else ":${gatewayUri.port}"
        val host = if (gatewayUri.host.contains(':')) "[${gatewayUri.host}]" else gatewayUri.host
        val socketUrl = "$protocol://$host$port" +
            "${gatewayUri.encodedPath.trimEnd('/')}/nodes/${enrollment.nodeId}/connect"
        client.webSocket(urlString = socketUrl, request = {
            header(HttpHeaders.Authorization, "Bearer ${enrollment.nodeToken}")
        }) {
            sendJson(buildJsonObject {
                put("type", "hello")
                put("protocol_version", PROTOCOL_VERSION)
                put("model_name", enrollment.modelId)
                put("model_format", enrollment.modelFormat)
                put("runtime_name", RUNTIME_NAME)
                put("runtime_version", engine.version)
                putJsonArray("runtime_capabilities") {
                    enrollment.runtimeCapabilities.forEach { add(JsonPrimitive(it)) }
                }
            })
            val readyFrame = withTimeout(15.seconds) { incoming.receive() }
            val ready = frameObject(readyFrame)
            if (ready.string("type") != "ready" ||
                ready["protocol_version"]?.jsonPrimitive?.intOrNull != PROTOCOL_VERSION ||
                ready["max_concurrent_requests"]?.jsonPrimitive?.intOrNull != 1 ||
                ready["max_message_bytes"]?.jsonPrimitive?.intOrNull?.let { it >= MAX_MESSAGE_BYTES } != true ||
                ready["heartbeat_interval_seconds"]?.jsonPrimitive?.intOrNull?.let { it in 1..20 } != true ||
                ready["lease_seconds"]?.jsonPrimitive?.intOrNull?.let { it >= 20 } != true
            ) {
                throw IllegalStateException("The gateway returned an incompatible mobile worker handshake.")
            }
            mutableStatus.value = ContributorStatus(
                message = "Contributing ${enrollment.modelId} while Common Mobile is active.",
                enrolled = true,
                connected = true,
            )
            val heartbeat = launch {
                while (isActive) {
                    delay(10.seconds)
                    sendJson(buildJsonObject { put("type", "heartbeat") })
                }
            }
            try {
                serveRequests(this, enrollment)
            } finally {
                heartbeat.cancel()
            }
        }
    }

    private suspend fun serveRequests(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        enrollment: Enrollment,
    ) {
        var requestId: String? = null
        var expectedBytes = 0
        var expectedSequence = 0
        var requestStream = false
        var requestBuffer = ByteArrayOutputStream()
        var inferenceJob: Job? = null
        coroutineScope {
            var activeInferenceId: String? = null
            while (true) {
                val raw = session.incomingText() ?: break
                if (raw.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                    throw IllegalStateException("Gateway message exceeded the worker protocol limit.")
                }
                val frame = json.parseToJsonElement(raw).jsonObject
                when (frame.string("type")) {
                    "heartbeat.ack" -> Unit
                    "disconnect" -> break
                    "request.start" -> {
                    val id = frame.string("request_id")
                    UUID.fromString(id)
                    val bytes = frame["body_bytes"]?.jsonPrimitive?.intOrNull
                        ?: throw IllegalStateException("Request start omitted the body length.")
                    if (inferenceJob?.isActive == true || requestId != null) {
                        session.sendError(id, "device is busy")
                        continue
                    }
                    if (frame["model"]?.jsonPrimitive?.contentOrNull != enrollment.modelId ||
                        frame["model_format"]?.jsonPrimitive?.contentOrNull != enrollment.modelFormat ||
                        frame["protocol_version"]?.jsonPrimitive?.intOrNull != PROTOCOL_VERSION ||
                        bytes !in 1..MAX_REQUEST_BYTES
                    ) {
                        throw IllegalStateException("Gateway request does not match this pinned model or size limit.")
                    }
                    requestId = id
                    expectedBytes = bytes
                    expectedSequence = 0
                    requestStream = frame["stream"]?.jsonPrimitive?.contentOrNull == "true"
                    requestBuffer = ByteArrayOutputStream(bytes)
                    }
                    "request.data" -> {
                    val id = frame.string("request_id")
                    if (id != requestId) continue
                    val sequence = frame["sequence"]?.jsonPrimitive?.intOrNull
                    val encoded = frame.string("data")
                    val decoded = try {
                        Base64.decode(encoded, Base64.DEFAULT)
                    } catch (_: IllegalArgumentException) {
                        throw IllegalStateException("Gateway sent invalid base64 request data.")
                    }
                    if (sequence != expectedSequence || decoded.size > MAX_CHUNK_BYTES ||
                        requestBuffer.size() + decoded.size > expectedBytes
                    ) {
                        throw IllegalStateException("Gateway request chunks were malformed or exceeded their bounds.")
                    }
                    requestBuffer.write(decoded)
                    expectedSequence++
                    }
                    "request.end" -> {
                    val id = frame.string("request_id")
                    if (id != requestId) continue
                    if (requestBuffer.size() != expectedBytes) {
                        throw IllegalStateException("Gateway request ended before all declared bytes arrived.")
                    }
                    val body = json.parseToJsonElement(requestBuffer.toByteArray().toString(Charsets.UTF_8)).jsonObject
                    val bodyModel = body["model"]?.jsonPrimitive?.contentOrNull
                    val bodyStream = body["stream"]?.jsonPrimitive?.contentOrNull == "true"
                    if (bodyModel != enrollment.modelId || bodyStream != requestStream) {
                        throw IllegalStateException("Request body does not match the pinned model or stream flag.")
                    }
                        val messages = body["messages"]?.jsonArray
                            ?: throw IllegalStateException("Inference request omitted chat messages.")
                        val roles = mutableListOf<String>()
                        val contents = mutableListOf<String>()
                        var promptBytes = 0
                        for (message in messages) {
                            val item = message.jsonObject
                            val role = item["role"]?.jsonPrimitive?.contentOrNull
                                ?: throw IllegalStateException("Inference message omitted its role.")
                            val content = item["content"]?.jsonPrimitive?.contentOrNull
                                ?: throw IllegalStateException("Only text chat messages are supported.")
                            if (role !in setOf("system", "user", "assistant") || content.length > 262_144) {
                                throw IllegalStateException("Inference message exceeds supported role or size bounds.")
                            }
                            promptBytes += content.toByteArray(Charsets.UTF_8).size
                            roles += role
                            contents += content
                        }
                        if (roles.isEmpty() || promptBytes > MAX_REQUEST_BYTES) {
                            throw IllegalStateException("Inference prompt is empty or too large.")
                        }
                        val maxTokens = body["max_tokens"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 1024) ?: 512
                        val temperature = body["temperature"]?.jsonPrimitive?.content?.toFloatOrNull()
                            ?.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 0.7f
                        val isBonsai2 = enrollment.modelId.startsWith("bonsai2-")
                        val topP = body["top_p"]?.jsonPrimitive?.content?.toFloatOrNull()
                            ?.takeIf { it.isFinite() }?.coerceIn(0.01f, 1f) ?: if (isBonsai2) 0.8f else 0.95f
                        val topK = body["top_k"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100)
                            ?: if (isBonsai2) 20 else 40
                        val presencePenalty = body["presence_penalty"]?.jsonPrimitive?.content?.toFloatOrNull()
                            ?.takeIf { it.isFinite() }?.coerceIn(-2f, 2f) ?: if (isBonsai2) 1.5f else 0f
                        requestId = null
                        engine.resetCancellation()
                        activeInferenceId = id
                        inferenceJob = launch {
                            try {
                                val result = engine.generate(
                                    roles,
                                    contents,
                                    maxTokens,
                                    temperature,
                                    topP,
                                    topK,
                                    presencePenalty,
                                    isBonsai2,
                                )
                                if (isActive && activeInferenceId == id && result.isNotEmpty()) {
                                    if (requestStream) session.sendStreamResult(id, enrollment.modelId, result)
                                    else session.sendNonStreamResult(id, enrollment.modelId, result)
                                } else if (isActive && activeInferenceId == id) {
                                    session.sendError(id, "local inference returned no text")
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (isActive && activeInferenceId == id) {
                                    session.sendError(id, "local inference failed")
                                }
                            } finally {
                                if (activeInferenceId == id) activeInferenceId = null
                            }
                        }
                    }
                    "request.cancel" -> {
                        val id = frame.string("request_id")
                        if (id == requestId) {
                            requestId = null
                            requestBuffer.reset()
                        } else if (id == activeInferenceId && inferenceJob?.isActive == true) {
                            engine.cancel()
                            inferenceJob?.cancelAndJoin()
                            inferenceJob = null
                            activeInferenceId = null
                        }
                    }
                    else -> throw IllegalStateException("Gateway sent an unsupported protocol frame.")
                }
            }
            if (inferenceJob?.isActive == true) {
                engine.cancel()
                inferenceJob?.cancelAndJoin()
            }
        }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.incomingText(): String? {
        val frame = incoming.receiveCatching().getOrNull() ?: return null
        return if (frame is Frame.Text) frame.readText() else null
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.frameObject(frame: Frame): JsonObject {
        if (frame !is Frame.Text) throw IllegalStateException("Gateway handshake must be a text JSON frame.")
        val text = frame.readText()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
            throw IllegalStateException("Gateway handshake exceeded the protocol message limit.")
        }
        return json.parseToJsonElement(text).jsonObject
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendJson(value: JsonObject) {
        val text = value.toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
            throw IllegalStateException("Worker response exceeded the protocol message limit.")
        }
        sendMutex.withLock { outgoing.send(Frame.Text(text)) }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendError(id: String, message: String) {
        sendJson(buildJsonObject {
            put("type", "response.error")
            put("request_id", id)
            put("message", message.take(256))
        })
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendStreamResult(
        id: String,
        model: String,
        text: String,
    ) {
        var sequence = 0
        for (chunk in utf8Chunks(text, 4 * 1024)) {
            sendJson(buildJsonObject {
                put("type", "response.chunk")
                put("request_id", id)
                put("sequence", sequence++)
                putJsonObject("chunk") {
                    put("id", "chatcmpl-$id")
                    put("object", "chat.completion.chunk")
                    put("model", model)
                    putJsonArray("choices") {
                        add(buildJsonObject {
                            put("index", 0)
                            putJsonObject("delta") { put("content", chunk) }
                            put("finish_reason", null as String?)
                        })
                    }
                }
            })
        }
        sendJson(buildJsonObject {
            put("type", "response.chunk")
            put("request_id", id)
            put("sequence", sequence)
            putJsonObject("chunk") {
                put("id", "chatcmpl-$id")
                put("object", "chat.completion.chunk")
                put("model", model)
                putJsonArray("choices") {
                    add(buildJsonObject {
                        put("index", 0)
                        putJsonObject("delta") {}
                        put("finish_reason", "stop")
                    })
                }
            }
        })
        sendJson(buildJsonObject {
            put("type", "response.done")
            put("request_id", id)
        })
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendNonStreamResult(
        id: String,
        model: String,
        text: String,
    ) {
        val result = buildJsonObject {
            put("id", "chatcmpl-$id")
            put("object", "chat.completion")
            put("model", model)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", text)
                    }
                    put("finish_reason", "stop")
                })
            }
        }.toString().toByteArray(Charsets.UTF_8)
        if (result.size > MAX_RESPONSE_BYTES) {
            sendError(id, "local response exceeded the size limit")
            return
        }
        sendJson(buildJsonObject {
            put("type", "response.result.start")
            put("request_id", id)
            put("body_bytes", result.size)
        })
        var sequence = 0
        result.asList().chunked(MAX_CHUNK_BYTES).forEach { bytes ->
            sendJson(buildJsonObject {
                put("type", "response.result.data")
                put("request_id", id)
                put("sequence", sequence++)
                put("data", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
            })
        }
        sendJson(buildJsonObject {
            put("type", "response.result.end")
            put("request_id", id)
        })
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.string(name: String): String =
        stringOrNull(name) ?: throw IllegalStateException("Protocol JSON omitted '$name'.")

    private fun JsonObject.stringOrNull(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.stringList(name: String): List<String> =
        this[name]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw IllegalStateException("Protocol JSON omitted '$name'.")

    private fun isSecureModelUrl(url: Url): Boolean {
        if (url.host.isBlank() || url.user != null || url.password != null || url.fragment.isNotEmpty()) return false
        val isLoopback = url.host.equals("localhost", ignoreCase = true) ||
            url.host == "127.0.0.1" || url.host == "::1"
        return url.protocol.name == "https" || (url.protocol.name == "http" && isLoopback)
    }
}

private fun utf8Chunks(value: String, maxBytes: Int): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    var position = 0
    var bytes = 0
    while (position < value.length) {
        val codePoint = value.codePointAt(position)
        val charCount = Character.charCount(codePoint)
        val encodedSize = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        if (bytes > 0 && bytes + encodedSize > maxBytes) {
            chunks += value.substring(start, position)
            start = position
            bytes = 0
        }
        bytes += encodedSize
        position += charCount
    }
    if (start < value.length) chunks += value.substring(start)
    return chunks
}

private class LlamaEngine {
    val version: String = nativeVersion()

    suspend fun load(model: File, disableThinking: Boolean) = withContext(Dispatchers.IO) {
        if (!model.isFile || !nativeLoad(model.absolutePath, disableThinking)) {
            throw IllegalStateException("The local inference runtime could not load the verified model file.")
        }
    }

    suspend fun generate(
        roles: List<String>,
        contents: List<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        presencePenalty: Float,
        disableThinking: Boolean,
    ): String = withContext(Dispatchers.IO) {
        nativeGenerate(
            roles.toTypedArray(),
            contents.toTypedArray(),
            maxTokens,
            temperature,
            topP,
            topK,
            presencePenalty,
            disableThinking,
        )
            ?: throw IllegalStateException("The local inference runtime returned no response.")
    }

    fun resetCancellation() = nativeResetCancellation()
    fun cancel() = nativeCancel()
    suspend fun free() = withContext(Dispatchers.IO) { nativeFree() }

    private external fun nativeVersion(): String
    private external fun nativeLoad(path: String, requireThinkingSwitch: Boolean): Boolean
    private external fun nativeResetCancellation()
    private external fun nativeGenerate(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        presencePenalty: Float,
        disableThinking: Boolean,
    ): String?
    private external fun nativeCancel()
    private external fun nativeFree()

    companion object {
        init {
            System.loadLibrary("common_mobile_inference")
        }
    }
}

private class SecureEnrollmentStore(context: Context) {
    private val preferences = context.getSharedPreferences("contributor_secure", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): Enrollment? {
        val encoded = preferences.getString("enrollment", null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        if (packed.size <= IV_SIZE) throw IllegalStateException("Secure contributor storage is corrupted.")
        val iv = packed.copyOfRange(0, IV_SIZE)
        val cipherText = packed.copyOfRange(IV_SIZE, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val value = json.parseToJsonElement(String(cipher.doFinal(cipherText), Charsets.UTF_8)).jsonObject
        return Enrollment(
            gateway = value.getValue("gateway").jsonPrimitive.content,
            nodeId = value.getValue("nodeId").jsonPrimitive.content,
            nodeName = value.getValue("nodeName").jsonPrimitive.content,
            nodeToken = value.getValue("nodeToken").jsonPrimitive.content,
            modelId = value.getValue("modelId").jsonPrimitive.content,
            modelFormat = value.getValue("modelFormat").jsonPrimitive.content,
            modelPath = value.getValue("modelPath").jsonPrimitive.content,
            runtimeCapabilities = value.getValue("runtimeCapabilities").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Synchronized
    fun write(enrollment: Enrollment) {
        val plain = buildJsonObject {
            put("gateway", enrollment.gateway)
            put("nodeId", enrollment.nodeId)
            put("nodeName", enrollment.nodeName)
            put("nodeToken", enrollment.nodeToken)
            put("modelId", enrollment.modelId)
            put("modelFormat", enrollment.modelFormat)
            put("modelPath", enrollment.modelPath)
            putJsonArray("runtimeCapabilities") {
                enrollment.runtimeCapabilities.forEach { add(JsonPrimitive(it)) }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(plain)
        if (!preferences.edit().putString("enrollment", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            throw IllegalStateException("Android secure storage could not persist the contributor registration.")
        }
    }

    @Synchronized
    fun clear() {
        if (!preferences.edit().remove("enrollment").commit()) {
            throw IllegalStateException("Android secure storage could not clear the contributor registration.")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "common-mobile-contributor"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
