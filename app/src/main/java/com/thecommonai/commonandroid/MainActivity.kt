package com.thecommonai.commonandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Ground = Color(0xFF292928)
private val Ink = Color(0xFFF6F6F6)
private val Quiet = Color(0xFF9A988F)
private val Raised = Color(0xFF4C4C4B)
private val Blue = Color(0xFF92B4C8)
private val Red = Color(0xFFC8442A)

data class TranscriptMessage(
    val role: String,
    val text: String,
    val servedBy: String? = null,
    val routeMs: Long? = null,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("common", MODE_PRIVATE)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Ground,
                    surface = Ground,
                    primary = Blue,
                    onPrimary = Ground,
                    onBackground = Ink,
                    onSurface = Ink,
                    error = Red,
                ),
            ) {
                CommonChat(
                    initialGateway = preferences.getString("gateway", NetworkClient.DEFAULT_GATEWAY)
                        ?: NetworkClient.DEFAULT_GATEWAY,
                    onGatewaySaved = { preferences.edit().putString("gateway", it).apply() },
                )
            }
        }
    }
}

@Composable
private fun CommonChat(
    initialGateway: String,
    onGatewaySaved: (String) -> Unit,
) {
    var gateway by rememberSaveable { mutableStateOf(initialGateway) }
    var nodeToken by remember { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var streaming by remember { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val messages = rememberSaveable(
        saver = listSaver(
            save = { current ->
                current.map { message ->
                    JSONObject()
                        .put("role", message.role)
                        .put("text", message.text)
                        .put("servedBy", message.servedBy)
                        .put("routeMs", message.routeMs)
                        .toString()
                }
            },
            restore = { saved ->
                mutableStateListOf<TranscriptMessage>().apply {
                    saved.forEach { json ->
                        val message = JSONObject(json)
                        add(
                            TranscriptMessage(
                                role = message.getString("role"),
                                text = message.getString("text"),
                                servedBy = message.optString("servedBy").takeIf { it.isNotEmpty() },
                                routeMs = message.optLong("routeMs").takeIf { message.has("routeMs") },
                            ),
                        )
                    }
                }
            },
        ),
    ) { mutableStateListOf() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    fun send() {
        val question = draft.trim()
        if (question.isEmpty() || busy) return
        draft = ""
        error = null
        streaming = ""
        messages.add(TranscriptMessage(role = "user", text = question))
        busy = true
        val turns = messages.map { ChatTurn(it.role, it.text) }
        scope.launch {
            try {
                val result = NetworkClient.ask(gateway, nodeToken, turns) { delta ->
                    withContext(kotlinx.coroutines.Dispatchers.Main) { streaming += delta }
                }
                streaming = ""
                messages.add(
                    TranscriptMessage(
                        role = "assistant",
                        text = result.text,
                        servedBy = when {
                            result.panel.isNotEmpty() -> "answered by ${result.panel.joinToString(", ")}"
                            else -> "served by ${result.node ?: "unknown"}"
                        },
                        routeMs = result.routeMs,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "The network could not answer that."
            } finally {
                streaming = ""
                busy = false
            }
        }
        focusManager.clearFocus()
    }

    LaunchedEffect(messages.size, streaming, busy) {
        val lastIndex = if (busy) messages.size else (messages.size - 1).coerceAtLeast(0)
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Ground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Common", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("THE NETWORK", color = Quiet, fontSize = 10.sp, letterSpacing = 1.6.sp)
                }
                HeaderAction("＋", "New chat") {
                    if (!busy) {
                        messages.clear()
                        error = null
                        streaming = ""
                    }
                }
                HeaderAction("⚙", "Settings") { showSettings = true }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    top = 26.dp,
                    end = 20.dp,
                    bottom = 22.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (messages.isEmpty() && streaming.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text("COMMON", color = Blue, fontSize = 12.sp, letterSpacing = 2.2.sp)
                            Spacer(Modifier.size(16.dp))
                            Text(
                                "Hello. Your question goes out to the network, and a machine answers it.",
                                color = Ink,
                                fontSize = 25.sp,
                                lineHeight = 34.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.size(14.dp))
                            Text(
                                "Your question is sent to the selected gateway and the machine it routes to.",
                                color = Quiet,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                            )
                        }
                    }
                }
                itemsIndexed(messages) { _, message ->
                    MessageItem(message)
                }
                if (streaming.isNotEmpty()) {
                    item { MessageItem(TranscriptMessage(role = "assistant", text = streaming)) }
                } else if (busy) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Blue,
                                strokeWidth = 2.dp,
                            )
                            Text("Waiting for the network.....", color = Quiet, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error.orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    color = Color(0xFFFFB7A8),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .weight(1f)
                        .background(Raised, RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 15.dp),
                    enabled = !busy,
                    textStyle = TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 23.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Blue),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { innerTextField ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    if (busy) "Waiting for the network....." else "Type here.....",
                                    color = Quiet,
                                    fontSize = 15.sp,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Spacer(Modifier.width(10.dp))
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            enabled = !busy && draft.isNotBlank(),
                            onClickLabel = "Send message",
                        ) { send() }
                        .semantics {
                            contentDescription = "Send"
                            role = Role.Button
                        },
                    shape = CircleShape,
                    color = if (!busy && draft.isNotBlank()) Blue else Raised,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("➤", color = if (!busy && draft.isNotBlank()) Ground else Quiet, fontSize = 20.sp)
                    }
                }
            }
            Text(
                "Questions are sent to the network.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
                color = Quiet,
                fontSize = 11.sp,
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            gateway = gateway,
            token = nodeToken,
            onDismiss = { showSettings = false },
            onSave = { newGateway, newToken ->
                gateway = newGateway
                nodeToken = newToken
                onGatewaySaved(newGateway)
                showSettings = false
            },
        )
    }
}

@Composable
private fun HeaderAction(label: String, description: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .size(44.dp)
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(10.dp),
        color = Quiet,
        fontSize = 22.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun MessageItem(message: TranscriptMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start,
    ) {
        Text(
            text = message.text,
            modifier = Modifier
                .then(
                    if (message.role == "user") {
                        Modifier
                            .background(Raised, RoundedCornerShape(18.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    } else {
                        Modifier.padding(vertical = 2.dp)
                    },
                )
                .fillMaxWidth(if (message.role == "user") 0.88f else 1f),
            color = Ink,
            fontSize = 16.sp,
            lineHeight = 25.sp,
        )
        if (message.servedBy != null) {
            Spacer(Modifier.size(7.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message.servedBy, color = Quiet, fontSize = 11.sp)
                message.routeMs?.let { Text("routed in ${it}ms", color = Quiet, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    gateway: String,
    token: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var gatewayDraft by remember(gateway) { mutableStateOf(gateway) }
    var tokenDraft by remember(token) { mutableStateOf(token) }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Raised,
        title = { Text("Network", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gateway URL", color = Quiet, fontSize = 12.sp)
                SettingsField(
                    value = gatewayDraft,
                    onValueChange = { gatewayDraft = it },
                    placeholder = NetworkClient.DEFAULT_GATEWAY,
                )
                Text("Existing node token (optional)", color = Quiet, fontSize = 12.sp)
                SettingsField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    placeholder = "Not stored",
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "The token is kept in memory only and sent to this gateway. " +
                        "This app never registers a node or donates a model.",
                    color = Quiet,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                validationError?.let {
                    Text(it, color = Color(0xFFFFB7A8), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        onSave(NetworkClient.validateGateway(gatewayDraft), tokenDraft.trim())
                    } catch (failure: IllegalArgumentException) {
                        validationError = failure.message
                    }
                },
            ) {
                Text("Save", color = Blue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Quiet) }
        },
    )
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .background(Ground, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 13.dp),
        textStyle = TextStyle(color = Ink, fontSize = 14.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Blue),
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = Quiet, fontSize = 14.sp)
                innerTextField()
            }
        },
    )
}
