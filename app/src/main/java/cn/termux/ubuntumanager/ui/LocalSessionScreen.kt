package cn.termux.ubuntumanager.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import cn.termux.ubuntumanager.command.TerminalShortcuts
import cn.termux.ubuntumanager.model.CommandTag
import cn.termux.ubuntumanager.model.LocalSessionBackend
import cn.termux.ubuntumanager.model.LocalSessionPhase
import cn.termux.ubuntumanager.model.LocalSessionState
import cn.termux.ubuntumanager.model.TerminalShortcutAction
import cn.termux.ubuntumanager.model.TerminalShortcutPreference
import cn.termux.ubuntumanager.model.UserCommand
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun LocalSessionScreen(
    instanceName: String,
    sessionState: LocalSessionState,
    commands: List<UserCommand>,
    commandTags: List<CommandTag>,
    terminalShortcuts: List<TerminalShortcutPreference>,
    operationInProgress: Boolean,
    onNavigateBack: () -> Unit,
    onOpenSession: () -> Unit,
    onCloseSession: () -> Unit,
    onEndSession: () -> Unit,
    onRunCommand: (String) -> Unit,
) {
    var terminalWebView by remember(instanceName) { mutableStateOf<WebView?>(null) }
    var showCommands by remember { mutableStateOf(false) }
    var ctrlMode by remember { mutableStateOf(TerminalModifierMode.OFF) }
    var altMode by remember { mutableStateOf(TerminalModifierMode.OFF) }
    var scrollState by remember { mutableStateOf(TerminalScrollState()) }
    var confirmEndSession by remember { mutableStateOf(false) }
    var pageLoading by remember(sessionState.port) { mutableStateOf(true) }
    var pageError by remember(sessionState.port) { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(instanceName) {
        onOpenSession()
    }
    LaunchedEffect(ctrlMode, altMode) {
        terminalWebView?.evaluateJavascript(terminalModifierScript(ctrlMode, altMode), null)
    }
    DisposableEffect(instanceName) {
        onDispose {
            terminalWebView?.stopLoading()
            terminalWebView?.destroy()
            terminalWebView = null
            onCloseSession()
        }
    }

    fun sendToTerminal(text: String) {
        val webView = terminalWebView
        if (webView == null || pageLoading) {
            copyCommand(context, text)
            Toast.makeText(context, "终端尚未就绪，内容已复制", Toast.LENGTH_SHORT).show()
            return
        }
        val encoded = JSONObject.quote(text)
        val script = """
            (function() {
              const target = document.querySelector('.xterm-helper-textarea');
              if (!target) return 'missing';
              const value = $encoded;
              window.__ubuntuBypassModifiedInput = true;
              try {
                const setter = Object.getOwnPropertyDescriptor(
                  HTMLTextAreaElement.prototype, 'value'
                ).set;
                setter.call(target, value);
                target.dispatchEvent(new InputEvent('input', {
                  bubbles: true,
                  inputType: 'insertText',
                  data: value
                }));
                return 'ok';
              } finally {
                window.setTimeout(function () {
                  window.__ubuntuBypassModifiedInput = false;
                }, 0);
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            if (result?.contains("missing") == true) {
                copyCommand(context, text)
                Toast.makeText(
                    context,
                    "无法写入终端，内容已复制",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun sendTerminalKey(key: String, code: String, keyCode: Int) {
        val webView = terminalWebView
        if (webView == null || pageLoading) return
        val script = """
            (function() {
              const target = document.querySelector('.xterm-helper-textarea');
              if (!target) return 'missing';
              const options = {
                key: ${JSONObject.quote(key)},
                code: ${JSONObject.quote(code)},
                keyCode: $keyCode,
                which: $keyCode,
                bubbles: true,
                cancelable: true
              };
              target.dispatchEvent(new KeyboardEvent('keydown', options));
              return 'ok';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    fun sendModifiedInput(value: String) {
        val ctrlActive = ctrlMode != TerminalModifierMode.OFF
        val altActive = altMode != TerminalModifierMode.OFF
        if (!ctrlActive && !altActive) {
            sendToTerminal(value)
            return
        }
        var output = if (ctrlActive) value.asControlSequence() else value
        if (altActive) output = "\u001b$output"
        sendToTerminal(output)
        if (ctrlMode == TerminalModifierMode.ONCE) ctrlMode = TerminalModifierMode.OFF
        if (altMode == TerminalModifierMode.ONCE) altMode = TerminalModifierMode.OFF
    }

    when {
        sessionState.instanceName != null &&
            sessionState.instanceName != instanceName -> {
            SessionLoading("正在切换本地会话…")
        }
        sessionState.phase == LocalSessionPhase.ERROR -> {
            SessionError(
                message = sessionState.message ?: "本地会话启动失败",
                onRetry = onOpenSession,
            )
        }
        sessionState.phase != LocalSessionPhase.READY || sessionState.url == null -> {
            SessionLoading(sessionState.message ?: "正在准备本地会话…")
        }
        else -> {
            val sessionUrl = sessionState.url
                ?: return
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF101010))
                        .padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    Text(
                        "● $instanceName · 后台保持",
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF72D894),
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                    TextButton(onClick = { confirmEndSession = true }) {
                        Text("结束会话", color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    LocalTerminalWebView(
                        url = sessionUrl,
                        port = sessionState.port!!,
                        backend = sessionState.backend,
                        onWebViewReady = { terminalWebView = it },
                        onPageLoading = {
                            pageLoading = it
                            if (it) pageError = null
                        },
                        onPageError = {
                            pageLoading = false
                            pageError = it
                        },
                        ctrlMode = ctrlMode,
                        altMode = altMode,
                        onModifiedInput = ::sendModifiedInput,
                        onScrollChanged = {
                                position,
                                total,
                                visible,
                                atBottom,
                                fraction,
                                viewportFraction,
                                reviewing,
                            ->
                            scrollState = TerminalScrollState(
                                position = position,
                                total = total,
                                visible = visible,
                                atBottom = atBottom,
                                fraction = fraction,
                                viewportFraction = viewportFraction,
                                reviewing = reviewing,
                            )
                        },
                    )
                    if (pageLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xCC000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            SessionLoading("正在连接终端…", dark = true)
                        }
                    }
                    pageError?.let { error ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xE6000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(error, color = Color.White)
                                Button(onClick = { terminalWebView?.reload() }) {
                                    Text("重新连接")
                                }
                            }
                        }
                    }
                    if (!pageLoading && pageError == null) {
                        TerminalScrollbar(
                            state = scrollState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(vertical = 6.dp)
                                .zIndex(2f),
                        )
                        TerminalPositionBadge(
                            state = scrollState,
                            onReturnToLatest = {
                                terminalWebView?.evaluateJavascript(
                                    "window.__ubuntuScrollToLatest && " +
                                        "window.__ubuntuScrollToLatest();",
                                    null,
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 18.dp, bottom = 10.dp)
                                .zIndex(1f),
                        )
                    }
                }
                val enabledShortcuts = terminalShortcuts.filter { it.enabled }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF171717)),
                ) {
                    val columnCount = terminalShortcutColumnCount(
                        availableWidthDp = maxWidth.value,
                        enabledCount = enabledShortcuts.size,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        enabledShortcuts.chunked(columnCount).forEach { shortcutRow ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                shortcutRow.forEach { shortcut ->
                                    val modifier = Modifier.weight(1f)
                                    when (shortcut.action) {
                                        TerminalShortcutAction.BUILTIN ->
                                            when (shortcut.payload) {
                                                TerminalShortcuts.CTRL -> TerminalModifierKey(
                                                    name = shortcut.label,
                                                    mode = ctrlMode,
                                                    modifier = modifier,
                                                    onTap = {
                                                        ctrlMode = ctrlMode.nextTapMode()
                                                    },
                                                    onLongPress = {
                                                        ctrlMode = ctrlMode.nextLongPressMode()
                                                    },
                                                )
                                                TerminalShortcuts.ALT -> TerminalModifierKey(
                                                    name = shortcut.label,
                                                    mode = altMode,
                                                    modifier = modifier,
                                                    onTap = {
                                                        altMode = altMode.nextTapMode()
                                                    },
                                                    onLongPress = {
                                                        altMode = altMode.nextLongPressMode()
                                                    },
                                                )
                                                TerminalShortcuts.ESC ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendTerminalKey("Escape", "Escape", 27)
                                                    }
                                                TerminalShortcuts.TAB ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendTerminalKey("Tab", "Tab", 9)
                                                    }
                                                TerminalShortcuts.BACKSPACE ->
                                                    TerminalRepeatingKey(
                                                        label = shortcut.label,
                                                        modifier = modifier,
                                                        contentDescription = "退格",
                                                    ) {
                                                        sendTerminalKey(
                                                            "Backspace",
                                                            "Backspace",
                                                            8,
                                                        )
                                                    }
                                                TerminalShortcuts.DELETE ->
                                                    TerminalRepeatingKey(
                                                        label = shortcut.label,
                                                        modifier = modifier,
                                                        contentDescription = "向前删除",
                                                    ) {
                                                        sendTerminalKey("Delete", "Delete", 46)
                                                    }
                                                TerminalShortcuts.ENTER ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendTerminalKey("Enter", "Enter", 13)
                                                    }
                                                TerminalShortcuts.LEFT ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendTerminalKey(
                                                            "ArrowLeft",
                                                            "ArrowLeft",
                                                            37,
                                                        )
                                                    }
                                                TerminalShortcuts.UP ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendTerminalKey("ArrowUp", "ArrowUp", 38)
                                                    }
                                                TerminalShortcuts.DOWN ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendTerminalKey(
                                                            "ArrowDown",
                                                            "ArrowDown",
                                                            40,
                                                        )
                                                    }
                                                TerminalShortcuts.RIGHT ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendTerminalKey(
                                                            "ArrowRight",
                                                            "ArrowRight",
                                                            39,
                                                        )
                                                    }
                                                TerminalShortcuts.COMMANDS ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        showCommands = true
                                                    }
                                                TerminalShortcuts.LATEST ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        terminalWebView?.evaluateJavascript(
                                                            "window.__ubuntuScrollToLatest && " +
                                                                "window.__ubuntuScrollToLatest();",
                                                            null,
                                                        )
                                                    }
                                            }
                                        TerminalShortcutAction.TEXT ->
                                            TerminalKey(shortcut.label, modifier) {
                                                sendToTerminal(
                                                    shortcut.payload +
                                                        if (shortcut.appendEnter) "\r" else "",
                                                )
                                            }
                                        TerminalShortcutAction.COMMAND -> {
                                            val command = commands.firstOrNull {
                                                it.id == shortcut.payload
                                            }
                                            TerminalKey(
                                                label = shortcut.label,
                                                modifier = modifier,
                                                enabled = command != null,
                                            ) {
                                                command?.let {
                                                    sendToTerminal(
                                                        it.asTerminalLine() +
                                                            if (shortcut.appendEnter) {
                                                                "\r"
                                                            } else {
                                                                ""
                                                            },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCommands) {
        SessionCommandSheet(
            commands = commands,
            commandTags = commandTags,
            operationInProgress = operationInProgress,
            onDismiss = { showCommands = false },
            onInsert = { command ->
                showCommands = false
                sendToTerminal(command.asTerminalLine())
            },
            onRun = { command ->
                showCommands = false
                onRunCommand(command.id)
            },
        )
    }

    if (confirmEndSession) {
        AlertDialog(
            onDismissRequest = { confirmEndSession = false },
            title = { Text("结束后台会话？") },
            text = {
                Text(
                    "这会终止当前后台会话及其中正在运行的命令。普通返回不会结束会话。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmEndSession = false
                        onEndSession()
                    },
                ) {
                    Text("确认结束")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEndSession = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SessionLoading(message: String, dark: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator()
            Text(
                message,
                color = if (dark) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SessionError(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedCard(modifier = Modifier.padding(24.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("无法打开本地会话", fontWeight = FontWeight.Bold)
                Text(message, color = MaterialTheme.colorScheme.error)
                Text(
                    "首次使用需要网络连接，以便在该 Ubuntu 中安装 ttyd 和 dtach。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("重试")
                }
            }
        }
    }
}

private enum class TerminalModifierMode {
    OFF,
    ONCE,
    LOCKED,
}

private fun TerminalModifierMode.nextTapMode(): TerminalModifierMode = when (this) {
    TerminalModifierMode.OFF -> TerminalModifierMode.ONCE
    TerminalModifierMode.ONCE,
    TerminalModifierMode.LOCKED,
    -> TerminalModifierMode.OFF
}

private fun TerminalModifierMode.nextLongPressMode(): TerminalModifierMode =
    if (this == TerminalModifierMode.LOCKED) {
        TerminalModifierMode.OFF
    } else {
        TerminalModifierMode.LOCKED
    }

private data class TerminalScrollState(
    val position: Int = 1,
    val total: Int = 1,
    val visible: Int = 1,
    val atBottom: Boolean = true,
    val fraction: Float = 1f,
    val viewportFraction: Float = 1f,
    val reviewing: Boolean = false,
)

internal fun terminalShortcutColumnCount(
    availableWidthDp: Float,
    enabledCount: Int,
): Int {
    if (enabledCount <= 0) return 1
    val horizontalPaddingDp = 8f
    val minimumButtonWidthDp = 48f
    val spacingDp = 3f
    val usableWidth = (availableWidthDp - horizontalPaddingDp).coerceAtLeast(
        minimumButtonWidthDp,
    )
    val fittingColumns = ((usableWidth + spacingDp) /
        (minimumButtonWidthDp + spacingDp)).toInt().coerceAtLeast(1)
    return fittingColumns.coerceAtMost(enabledCount)
}

@Composable
private fun TerminalKey(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .background(
                color = if (active) Color(0xFF235D45) else Color(0xFF2A2A2A),
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color.Gray,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TerminalRepeatingKey(
    label: String,
    modifier: Modifier = Modifier,
    contentDescription: String,
    enabled: Boolean = true,
    onSend: () -> Unit,
) {
    val currentOnSend by rememberUpdatedState(onSend)
    Box(
        modifier = modifier
            .height(38.dp)
            .background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        coroutineScope {
                            var repeated = false
                            val repeatJob = launch {
                                delay(400)
                                while (true) {
                                    repeated = true
                                    currentOnSend()
                                    delay(70)
                                }
                            }
                            val released = tryAwaitRelease()
                            repeatJob.cancel()
                            if (released && !repeated) currentOnSend()
                        }
                    },
                )
            }
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (enabled) {
                    onClick {
                        currentOnSend()
                        true
                    }
                }
            }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color.Gray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalModifierKey(
    name: String,
    mode: TerminalModifierMode,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val (label, color) = when (mode) {
        TerminalModifierMode.OFF -> name to Color(0xFF2A2A2A)
        TerminalModifierMode.ONCE -> "$name·一次" to Color(0xFF7A5313)
        TerminalModifierMode.LOCKED -> "$name·锁定" to Color(0xFF195C82)
    }
    Box(
        modifier = modifier
            .height(38.dp)
            .background(color, RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TerminalPositionBadge(
    state: TerminalScrollState,
    onReturnToLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = state.position.coerceAtLeast(1)
    val total = state.total.coerceAtLeast(position)
    val text = when {
        state.reviewing && state.atBottom -> "已到最新 · 观察中"
        state.reviewing -> "历史观察 · 距最新 ${total - position} 行"
        else -> "● 当前输入 · $position/$total"
    }
    Box(
        modifier = modifier
            .background(
                if (state.reviewing) Color(0xC47A5313) else Color(0xB81B5E42),
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onReturnToLatest)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun TerminalScrollbar(
    state: TerminalScrollState,
    modifier: Modifier = Modifier,
) {
    val viewportFraction = state.viewportFraction.coerceIn(0f, 1f)
    if (state.total <= state.visible || viewportFraction >= 0.995f) return

    BoxWithConstraints(
        modifier = modifier
            .width(14.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val thumbHeight = (maxHeight * viewportFraction)
            .coerceAtLeast(28.dp)
            .coerceAtMost(maxHeight)
        val thumbOffset = (maxHeight - thumbHeight) * state.fraction.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .offset(y = thumbOffset)
                .width(3.dp)
                .height(thumbHeight)
                .background(
                    color = Color(0x5CDCE6EB),
                    shape = RoundedCornerShape(3.dp),
                ),
        )
    }
}

private fun String.asControlSequence(): String {
    if (isEmpty()) return this
    val character = first()
    val code = when (character.uppercaseChar()) {
        in 'A'..'Z' -> character.uppercaseChar().code - 'A'.code + 1
        '@', ' ' -> 0
        '[' -> 27
        '\\' -> 28
        ']' -> 29
        '^' -> 30
        '_' -> 31
        '?' -> 127
        else -> return this
    }
    return code.toChar().toString()
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun LocalTerminalWebView(
    url: String,
    port: Int,
    backend: LocalSessionBackend,
    onWebViewReady: (WebView) -> Unit,
    onPageLoading: (Boolean) -> Unit,
    onPageError: (String) -> Unit,
    ctrlMode: TerminalModifierMode,
    altMode: TerminalModifierMode,
    onModifiedInput: (String) -> Unit,
    onScrollChanged: (Int, Int, Int, Boolean, Float, Float, Boolean) -> Unit,
) {
    val currentModifiedInput = rememberUpdatedState(onModifiedInput)
    val currentScrollChanged = rememberUpdatedState(onScrollChanged)
    val bridge: TerminalJavascriptBridge = remember {
        TerminalJavascriptBridge(
            onModifiedInput = { currentModifiedInput.value(it) },
            onScrollChanged = {
                    position,
                    total,
                    visible,
                    atBottom,
                    fraction,
                    viewportFraction,
                    reviewing,
                ->
                currentScrollChanged.value(
                    position,
                    total,
                    visible,
                    atBottom,
                    fraction,
                    viewportFraction,
                    reviewing,
                )
            },
        )
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.Black.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = true
                settings.safeBrowsingEnabled = true
                addJavascriptInterface(bridge, TERMINAL_BRIDGE_NAME)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        if (
                            request.method != "GET" ||
                            uri.path != "/" ||
                            !uri.isAllowedLocalSessionUri(port)
                        ) {
                            return null
                        }
                        return loadCompatibleTerminalPage(uri.toString())
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        onPageLoading(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onPageLoading(false)
                        view?.evaluateJavascript(
                            terminalPageStateScript(ctrlMode, altMode, backend),
                            null,
                        )
                        view?.requestFocus()
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val uri = request?.url ?: return true
                        return !uri.isAllowedLocalSessionUri(port)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onPageError(
                                error?.description?.toString()
                                    ?: "本地终端页面加载失败",
                            )
                        }
                    }
                }
                onWebViewReady(this)
                loadUrl(url)
            }
        },
        update = { view ->
            if (view.url != url) view.loadUrl(url)
            view.evaluateJavascript(
                terminalPageStateScript(ctrlMode, altMode, backend),
                null,
            )
        },
    )
}

private class TerminalJavascriptBridge(
    private val onModifiedInput: (String) -> Unit,
    private val onScrollChanged: (Int, Int, Int, Boolean, Float, Float, Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onModifiedInput(value: String) {
        mainHandler.post { onModifiedInput(value) }
    }

    @JavascriptInterface
    fun onScroll(
        position: Int,
        total: Int,
        visible: Int,
        atBottom: Boolean,
        fraction: Float,
        viewportFraction: Float,
        reviewing: Boolean,
    ) {
        mainHandler.post {
            onScrollChanged(
                position,
                total,
                visible,
                atBottom,
                fraction,
                viewportFraction,
                reviewing,
            )
        }
    }
}

private fun terminalModifierScript(
    ctrlMode: TerminalModifierMode,
    altMode: TerminalModifierMode,
): String {
    val ctrlActive = ctrlMode != TerminalModifierMode.OFF
    val altActive = altMode != TerminalModifierMode.OFF
    val ctrlLocked = ctrlMode == TerminalModifierMode.LOCKED
    val altLocked = altMode == TerminalModifierMode.LOCKED
    return "window.__ubuntuSetModifiers && window.__ubuntuSetModifiers(" +
        "$ctrlActive,$altActive,$ctrlLocked,$altLocked);"
}

private fun terminalPageStateScript(
    ctrlMode: TerminalModifierMode,
    altMode: TerminalModifierMode,
    backend: LocalSessionBackend,
): String = terminalModifierScript(ctrlMode, altMode) +
    "window.__ubuntuSetBackend && window.__ubuntuSetBackend(" +
    JSONObject.quote(backend.name.lowercase()) + ");"

private fun Uri.isAllowedLocalSessionUri(port: Int): Boolean =
    scheme == "http" && host == "127.0.0.1" && this.port == port

private fun loadCompatibleTerminalPage(url: String): WebResourceResponse? = runCatching {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 10_000
    connection.instanceFollowRedirects = false
    try {
        val html = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val compatibleHtml = html.replaceFirst(
            "<head>",
            "<head>$LOCAL_WEBVIEW_COMPATIBILITY_SCRIPT",
        )
        WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream(compatibleHtml.toByteArray(StandardCharsets.UTF_8)),
        )
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private const val TERMINAL_BRIDGE_NAME = "UbuntuTerminalBridge"

private const val LOCAL_WEBVIEW_COMPATIBILITY_SCRIPT = """
<style>
.xterm,
.xterm-viewport,
.xterm-screen {
  touch-action: none !important;
  overscroll-behavior-y: contain !important;
}
.xterm-viewport::-webkit-scrollbar {
  width: 0 !important;
}
.xterm-viewport::-webkit-scrollbar-track {
  background: transparent !important;
}
.xterm-viewport::-webkit-scrollbar-thumb {
  background: rgba(220, 230, 235, 0.32) !important;
  border-radius: 6px !important;
}
.xterm-viewport::-webkit-scrollbar-thumb:active {
  background: rgba(220, 230, 235, 0.58) !important;
}
</style>
<script>
(function () {
  /*
   * xterm.js answers OSC 10/11/12 color queries through the same WebSocket
   * used for keyboard input. Some interactive programs redraw and issue such
   * a query while an old dtach session is being reattached. The delayed reply
   * can then reach the program's normal input prompt and appear as
   * "]11;rgb:...". Do not forward those terminal-generated replies. They are
   * display capability probes, not user input.
   */
  var nativeWebSocketSend = WebSocket.prototype.send;
  var terminalSocket = null;
  WebSocket.prototype.send = function (data) {
    if (this.url && /\/ws(?:\?|$)/.test(this.url)) {
      terminalSocket = this;
    }
    try {
      var bytes = null;
      if (data instanceof ArrayBuffer) {
        bytes = new Uint8Array(data);
      } else if (ArrayBuffer.isView(data)) {
        bytes = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
      }
      if (bytes && bytes.length > 8 && bytes[0] === 48) {
        var terminalData = new TextDecoder().decode(bytes.subarray(1));
        if (/^\u001b\](?:10|11|12);rgb:/i.test(terminalData)) {
          return;
        }
      }
    } catch (ignored) {
    }
    return nativeWebSocketSend.call(this, data);
  };

  function sendTerminalData(value) {
    if (!terminalSocket || terminalSocket.readyState !== WebSocket.OPEN) return;
    var encoded = new TextEncoder().encode(value);
    var message = new Uint8Array(encoded.length + 1);
    message[0] = 48;
    message.set(encoded, 1);
    nativeWebSocketSend.call(terminalSocket, message);
  }

  var resizeTimer = null;
  var resizeBurstResetTimer = null;
  var resizeBurstActive = false;
  var releasingResize = false;
  var resizeWasAtBottom = true;
  var resizeDistanceFromBottom = 0;
  var resizeRestoreGeneration = 0;
  var resizeRestoreCancelled = false;

  function captureResizeAnchor() {
    var found = document.querySelector('.xterm-viewport');
    if (!found) return;
    var distance = Math.max(
      0,
      found.scrollHeight - found.scrollTop - found.clientHeight
    );
    resizeWasAtBottom = distance <= 24;
    resizeDistanceFromBottom = distance;
    resizeRestoreCancelled = false;
  }

  function restoreResizeAnchor(generation) {
    if (resizeRestoreCancelled || generation !== resizeRestoreGeneration) return;
    var found = document.querySelector('.xterm-viewport');
    if (!found) return;
    if (resizeWasAtBottom) {
      found.scrollTop = found.scrollHeight;
    } else {
      found.scrollTop = Math.max(
        0,
        found.scrollHeight - found.clientHeight - resizeDistanceFromBottom
      );
    }
  }

  function releaseResize() {
    var generation = ++resizeRestoreGeneration;
    releasingResize = true;
    window.dispatchEvent(new Event('resize'));
    window.requestAnimationFrame(function () {
      window.requestAnimationFrame(function () {
        restoreResizeAnchor(generation);
      });
    });
    [90, 240, 480, 800, 1200].forEach(function (delay) {
      window.setTimeout(function () {
        restoreResizeAnchor(generation);
      }, delay);
    });
    window.clearTimeout(resizeBurstResetTimer);
    resizeBurstResetTimer = window.setTimeout(function () {
      resizeBurstActive = false;
    }, 1300);
  }

  window.addEventListener('resize', function (event) {
    if (releasingResize) {
      releasingResize = false;
      return;
    }
    event.stopImmediatePropagation();
    if (!resizeBurstActive) {
      captureResizeAnchor();
      resizeBurstActive = true;
    }
    window.clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(releaseResize, 180);
  }, true);

  function install(proto) {
    if (!proto || proto.replaceChildren) return;
    proto.replaceChildren = function () {
      while (this.firstChild) this.removeChild(this.firstChild);
      for (var i = 0; i < arguments.length; i++) {
        var node = arguments[i];
        this.appendChild(node instanceof Node ? node : document.createTextNode(String(node)));
      }
    };
  }
  install(Element.prototype);
  install(Document.prototype);
  install(DocumentFragment.prototype);

  var modifiers = {
    ctrl: false,
    alt: false,
    ctrlLocked: false,
    altLocked: false
  };
  var lastModifiedValue = '';
  var lastModifiedAt = 0;
  window.__ubuntuBypassModifiedInput = false;
  window.__ubuntuSetModifiers = function (ctrl, alt, ctrlLocked, altLocked) {
    modifiers.ctrl = !!ctrl;
    modifiers.alt = !!alt;
    modifiers.ctrlLocked = !!ctrlLocked;
    modifiers.altLocked = !!altLocked;
  };

  function isTerminalInput(target) {
    return target && target.classList &&
      target.classList.contains('xterm-helper-textarea');
  }

  function consumeModifiedInput(value, event) {
    var now = Date.now();
    if (event && event.type === 'input' &&
        value === lastModifiedValue && now - lastModifiedAt < 120) {
      event.preventDefault();
      event.stopImmediatePropagation();
      return true;
    }
    if (window.__ubuntuBypassModifiedInput ||
        (!modifiers.ctrl && !modifiers.alt) ||
        !value || value.length !== 1 || value.charCodeAt(0) > 127) {
      return false;
    }
    if (event && (event.isComposing || event.keyCode === 229)) return false;
    if (event) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
    if (!modifiers.ctrlLocked) modifiers.ctrl = false;
    if (!modifiers.altLocked) modifiers.alt = false;
    lastModifiedValue = value;
    lastModifiedAt = now;
    try {
      window.UbuntuTerminalBridge.onModifiedInput(value);
    } catch (ignored) {
      return false;
    }
    return true;
  }

  document.addEventListener('keydown', function (event) {
    if (!isTerminalInput(event.target)) return;
    markUserInteraction();
    prepareForTerminalInput();
    if (event.key && event.key.length === 1) {
      consumeModifiedInput(event.key, event);
    }
  }, true);

  document.addEventListener('beforeinput', function (event) {
    if (!isTerminalInput(event.target)) return;
    markUserInteraction();
    prepareForTerminalInput();
    consumeModifiedInput(event.data, event);
  }, true);

  document.addEventListener('input', function (event) {
    if (!isTerminalInput(event.target)) return;
    prepareForTerminalInput();
    if (consumeModifiedInput(event.data, event)) {
      event.target.value = '';
    }
  }, true);

  var viewport = null;
  var touchSurface = null;
  var sessionBackend = 'unknown';
  var lastReport = '';
  var staleInputCleared = false;
  var tmuxCopyMode = false;
  var tmuxCopyPending = false;
  var tmuxCopyOffset = 0;
  var tmuxCopyMaximum = 0;
  var pendingTmuxLines = 0;
  var pendingTmuxAllowPages = false;
  var tmuxCopyProbeTimer = null;
  var tmuxTouchPixels = 0;
  var tmuxScrollTargetTimer = null;
  var tmuxScrollTargetFraction = 1;
  var initialLatestTimers = [];
  var userHasInteracted = false;
  var historyReviewing = false;
  var reviewScrollTop = 0;
  var activePointerId = null;
  var pointerStartX = 0;
  var pointerStartY = 0;
  var pointerLastY = 0;
  var scrollGesture = false;
  var scrollbarDragging = false;
  var scrollbarGrabOffset = 0;
  var pendingScrollPixels = 0;
  var scrollAnimationFrame = null;
  var terminalRenderDisposable = null;

  window.__ubuntuSetBackend = function (backend) {
    sessionBackend = backend === 'tmux' || backend === 'dtach'
      ? backend
      : 'unknown';
  };

  function cancelInitialLatest() {
    initialLatestTimers.forEach(function (timer) {
      window.clearTimeout(timer);
    });
    initialLatestTimers = [];
  }

  function markUserInteraction() {
    userHasInteracted = true;
    cancelInitialLatest();
    resizeRestoreCancelled = true;
    resizeRestoreGeneration++;
  }

  function prepareForTerminalInput() {
    if (!historyReviewing && !tmuxCopyMode && !tmuxCopyPending) return;
    historyReviewing = false;
    pendingTmuxLines = 0;
    tmuxTouchPixels = 0;
    window.clearTimeout(tmuxCopyProbeTimer);
    if (tmuxCopyMode || tmuxCopyPending) {
      sendTerminalData('q');
    }
    tmuxCopyMode = false;
    tmuxCopyPending = false;
    if (viewport) {
      viewport.scrollTop = viewport.scrollHeight;
      reviewScrollTop = viewport.scrollTop;
    }
    window.setTimeout(reportScroll, 40);
  }

  function clearStaleTerminalInput() {
    if (staleInputCleared) return;
    var input = document.querySelector('.xterm-helper-textarea');
    if (!input) return;
    input.value = '';
    staleInputCleared = true;
  }

  function rowHeight() {
    var row = document.querySelector('.xterm-rows > div');
    if (!row) return 17;
    var height = parseFloat(window.getComputedStyle(row).height);
    return height > 0 ? height : 17;
  }

  function readTmuxCopyState() {
    var terminal = window.term;
    if (!terminal || !terminal.buffer || !terminal.buffer.active) return null;
    var buffer = terminal.buffer.active;
    var rows = Math.max(1, terminal.rows || 1);
    var start = Math.max(0, buffer.viewportY || 0);
    for (var index = 0; index < rows; index++) {
      var line = buffer.getLine(start + index);
      if (!line) continue;
      var match = line.translateToString(true).match(/\[(\d+)\/(\d+)/);
      if (match) {
        return {
          offset: parseInt(match[1], 10) || 0,
          maximum: parseInt(match[2], 10) || 0,
          visible: rows
        };
      }
    }
    return null;
  }

  function repeatSequence(sequence, count) {
    if (count <= 0) return '';
    return new Array(count + 1).join(sequence);
  }

  function sendTmuxCopyLines(lines, allowPages) {
    if (!lines) return;
    var terminalRows = Math.max(3, (window.term && window.term.rows) || 24);
    var pageSize = terminalRows - 2;
    var count = Math.abs(lines);
    var pages = allowPages ? Math.floor(count / pageSize) : 0;
    var remainder = allowPages ? count % pageSize : count;
    var pageSequence = lines > 0 ? '\u001b[5~' : '\u001b[6~';
    var lineSequence = lines > 0 ? '\u001b[A' : '\u001b[B';
    sendTerminalData(
      repeatSequence(pageSequence, pages) +
      repeatSequence(lineSequence, remainder)
    );
  }

  function finishTmuxCopyEntry() {
    var queuedLines = pendingTmuxLines;
    var allowPages = pendingTmuxAllowPages;
    pendingTmuxLines = 0;
    pendingTmuxAllowPages = false;
    tmuxCopyPending = false;
    tmuxCopyMode = true;
    if (queuedLines !== 0) {
      sendTmuxCopyLines(queuedLines, allowPages);
    }
    window.setTimeout(reportScroll, 40);
  }

  function requestTmuxCopyLines(lines, allowPages) {
    if (!lines || sessionBackend !== 'tmux') return;
    historyReviewing = true;
    if (tmuxCopyMode) {
      sendTmuxCopyLines(lines, !!allowPages);
      return;
    }
    pendingTmuxLines += lines;
    pendingTmuxAllowPages = pendingTmuxAllowPages || !!allowPages;
    if (tmuxCopyPending) return;
    tmuxCopyPending = true;
    sendTerminalData('\u0002[');
    var deadline = Date.now() + 350;
    var probe = function () {
      if (!tmuxCopyPending) return;
      var state = readTmuxCopyState();
      if (state) {
        tmuxCopyOffset = state.offset;
        tmuxCopyMaximum = state.maximum;
        finishTmuxCopyEntry();
        return;
      }
      if (Date.now() < deadline) {
        tmuxCopyProbeTimer = window.setTimeout(probe, 24);
        return;
      }
      sendTerminalData('q');
      tmuxCopyPending = false;
      pendingTmuxLines = 0;
      pendingTmuxAllowPages = false;
      historyReviewing = false;
      window.setTimeout(reportScroll, 40);
    };
    tmuxCopyProbeTimer = window.setTimeout(probe, 24);
  }

  function reportScroll() {
    if (!viewport) return;
    var tmuxState = readTmuxCopyState();
    if (tmuxState) {
      tmuxCopyMode = true;
      tmuxCopyPending = false;
      historyReviewing = true;
      tmuxCopyOffset = tmuxState.offset;
      tmuxCopyMaximum = tmuxState.maximum;
      var tmuxTotal = Math.max(
        tmuxState.visible,
        tmuxState.maximum + tmuxState.visible
      );
      var tmuxPosition = Math.max(
        tmuxState.visible,
        tmuxTotal - tmuxState.offset
      );
      var tmuxAtBottom = tmuxState.offset <= 0;
      var tmuxFraction = tmuxState.maximum <= 0
        ? 1
        : 1 - Math.min(1, tmuxState.offset / tmuxState.maximum);
      var tmuxViewportFraction = Math.max(
        0,
        Math.min(1, tmuxState.visible / tmuxTotal)
      );
      var tmuxReport = 'tmux:' + tmuxPosition + ':' + tmuxTotal + ':' +
        tmuxState.visible + ':' + tmuxAtBottom + ':' +
        tmuxFraction.toFixed(4) + ':' + tmuxViewportFraction.toFixed(4);
      if (tmuxReport === lastReport) return;
      lastReport = tmuxReport;
      try {
        window.UbuntuTerminalBridge.onScroll(
          tmuxPosition,
          tmuxTotal,
          tmuxState.visible,
          tmuxAtBottom,
          tmuxFraction,
          tmuxViewportFraction,
          true
        );
      } catch (ignored) {
      }
      return;
    }
    tmuxCopyMode = false;
    tmuxCopyOffset = 0;
    tmuxCopyMaximum = 0;
    var height = rowHeight();
    var total = Math.max(1, Math.round(viewport.scrollHeight / height));
    var visible = Math.max(1, Math.round(viewport.clientHeight / height));
    var atBottom =
      viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight <= height;
    var maxScroll = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
    var scrollFraction = maxScroll <= 0
      ? 1
      : Math.max(0, Math.min(1, viewport.scrollTop / maxScroll));
    var viewportFraction = viewport.scrollHeight <= 0
      ? 1
      : Math.max(0, Math.min(1, viewport.clientHeight / viewport.scrollHeight));
    var position = atBottom
      ? total
      : Math.min(total, Math.max(visible, Math.round(
          (viewport.scrollTop + viewport.clientHeight) / height
        )));
    var report = position + ':' + total + ':' + visible + ':' + atBottom +
      ':' + scrollFraction.toFixed(4) + ':' + viewportFraction.toFixed(4);
    if (report === lastReport) return;
    lastReport = report;
    try {
      window.UbuntuTerminalBridge.onScroll(
        position,
        total,
        visible,
        atBottom,
        scrollFraction,
        viewportFraction,
        historyReviewing
      );
    } catch (ignored) {
    }
  }

  function handleTerminalScroll(deltaY) {
    if (!viewport) return;
    if (tmuxCopyMode) {
      historyReviewing = true;
      tmuxTouchPixels += deltaY;
      var height = Math.max(12, rowHeight());
      var historyLines = tmuxTouchPixels > 0
        ? Math.floor(tmuxTouchPixels / height)
        : Math.ceil(tmuxTouchPixels / height);
      if (historyLines !== 0) {
        requestTmuxCopyLines(historyLines, false);
        tmuxTouchPixels -= historyLines * height;
      }
    } else if (viewport.scrollHeight - viewport.clientHeight > 1) {
      historyReviewing = true;
      viewport.scrollTop -= deltaY;
      reviewScrollTop = viewport.scrollTop;
    } else if (sessionBackend === 'tmux') {
      historyReviewing = true;
      tmuxTouchPixels += deltaY;
      var fallbackHeight = Math.max(12, rowHeight());
      var fallbackLines = tmuxTouchPixels > 0
        ? Math.floor(tmuxTouchPixels / fallbackHeight)
        : Math.ceil(tmuxTouchPixels / fallbackHeight);
      if (fallbackLines !== 0) {
        requestTmuxCopyLines(fallbackLines, false);
        tmuxTouchPixels -= fallbackLines * fallbackHeight;
      }
    }
    reportScroll();
  }

  function flushPendingScroll() {
    scrollAnimationFrame = null;
    var movement = pendingScrollPixels;
    pendingScrollPixels = 0;
    if (movement !== 0) handleTerminalScroll(movement);
  }

  function queueTerminalScroll(deltaY) {
    pendingScrollPixels += deltaY;
    if (scrollAnimationFrame !== null) return;
    scrollAnimationFrame = window.requestAnimationFrame(flushPendingScroll);
  }

  function maintainReviewAnchor() {
    if (!viewport || !historyReviewing || tmuxCopyMode || tmuxCopyPending ||
        scrollGesture || scrollbarDragging) return;
    var maximum = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
    var target = Math.max(0, Math.min(maximum, reviewScrollTop));
    if (Math.abs(viewport.scrollTop - target) > 1) {
      viewport.scrollTop = target;
    }
  }

  function attachTerminalRenderObserver() {
    if (terminalRenderDisposable || !window.term || !window.term.onRender) return;
    terminalRenderDisposable = window.term.onRender(function () {
      window.requestAnimationFrame(function () {
        maintainReviewAnchor();
        reportScroll();
      });
    });
  }

  function scrollbarMetrics(rect) {
    if (!viewport || rect.height <= 0) return null;
    var viewportFraction;
    var scrollFraction;
    if (tmuxCopyMode && tmuxCopyMaximum > 0) {
      var tmuxTotal = Math.max(1, tmuxCopyMaximum +
        ((window.term && window.term.rows) || 24));
      viewportFraction = Math.min(1,
        ((window.term && window.term.rows) || 24) / tmuxTotal);
      scrollFraction = 1 - Math.min(1, tmuxCopyOffset / tmuxCopyMaximum);
    } else {
      if (viewport.scrollHeight - viewport.clientHeight <= 1) return null;
      viewportFraction = Math.min(1,
        viewport.clientHeight / viewport.scrollHeight);
      var maxScroll = Math.max(1,
        viewport.scrollHeight - viewport.clientHeight);
      scrollFraction = Math.max(0, Math.min(1,
        viewport.scrollTop / maxScroll));
    }
    var thumbHeight = Math.max(28, rect.height * viewportFraction);
    thumbHeight = Math.min(rect.height, thumbHeight);
    var thumbTop = (rect.height - thumbHeight) * scrollFraction;
    return {
      height: thumbHeight,
      top: thumbTop,
      travel: Math.max(0, rect.height - thumbHeight)
    };
  }

  function beginTerminalPointer(event) {
    if (event.pointerType !== 'touch' || activePointerId !== null) return;
    markUserInteraction();
    activePointerId = event.pointerId;
    pointerStartX = event.clientX;
    pointerStartY = event.clientY;
    pointerLastY = event.clientY;
    scrollGesture = false;
    scrollbarDragging = false;
    tmuxTouchPixels = 0;
    pendingScrollPixels = 0;

    var rect = touchSurface.getBoundingClientRect();
    var localX = event.clientX - rect.left;
    var localY = event.clientY - rect.top;
    var metrics = scrollbarMetrics(rect);
    if (metrics && localX >= rect.width - 18 &&
        localY >= metrics.top - 10 &&
        localY <= metrics.top + metrics.height + 10) {
      scrollbarDragging = true;
      scrollbarGrabOffset = Math.max(0, Math.min(
        metrics.height,
        localY - metrics.top
      ));
      touchSurface.setPointerCapture(event.pointerId);
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }

  function moveTerminalPointer(event) {
    if (event.pointerId !== activePointerId) return;
    var rect = touchSurface.getBoundingClientRect();
    if (scrollbarDragging) {
      var metrics = scrollbarMetrics(rect);
      if (metrics) {
        var localY = event.clientY - rect.top;
        var fraction = metrics.travel <= 0
          ? 1
          : (localY - scrollbarGrabOffset) / metrics.travel;
        window.__ubuntuScrollToFraction(
          Math.max(0, Math.min(1, fraction)),
          true
        );
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      return;
    }

    var deltaX = event.clientX - pointerStartX;
    var deltaY = event.clientY - pointerStartY;
    if (!scrollGesture && Math.abs(deltaY) >= 8 &&
        Math.abs(deltaY) > Math.abs(deltaX)) {
      scrollGesture = true;
      touchSurface.setPointerCapture(event.pointerId);
    }
    if (scrollGesture) {
      queueTerminalScroll(event.clientY - pointerLastY);
      event.preventDefault();
      event.stopImmediatePropagation();
    }
    pointerLastY = event.clientY;
  }

  function endTerminalPointer(event) {
    if (event.pointerId !== activePointerId) return;
    if (scrollAnimationFrame !== null) {
      window.cancelAnimationFrame(scrollAnimationFrame);
      flushPendingScroll();
    }
    if (scrollGesture || scrollbarDragging) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
    if (touchSurface.hasPointerCapture(event.pointerId)) {
      touchSurface.releasePointerCapture(event.pointerId);
    }
    activePointerId = null;
    scrollGesture = false;
    scrollbarDragging = false;
    tmuxTouchPixels = 0;
    window.setTimeout(reportScroll, 20);
  }

  function attachViewport() {
    var found = document.querySelector('.xterm-viewport');
    if (!found || found === viewport) return;
    if (touchSurface) {
      touchSurface.removeEventListener('pointerdown', beginTerminalPointer, true);
      touchSurface.removeEventListener('pointermove', moveTerminalPointer, true);
      touchSurface.removeEventListener('pointerup', endTerminalPointer, true);
      touchSurface.removeEventListener('pointercancel', endTerminalPointer, true);
    }
    viewport = found;
    touchSurface = found.closest('.xterm') || found.parentElement || found;
    viewport.addEventListener('scroll', function () {
      maintainReviewAnchor();
      reportScroll();
    }, {passive: true});
    touchSurface.addEventListener(
      'pointerdown', beginTerminalPointer, {capture: true, passive: false}
    );
    touchSurface.addEventListener(
      'pointermove', moveTerminalPointer, {capture: true, passive: false}
    );
    touchSurface.addEventListener(
      'pointerup', endTerminalPointer, {capture: true, passive: false}
    );
    touchSurface.addEventListener(
      'pointercancel', endTerminalPointer, {capture: true, passive: false}
    );
    window.__ubuntuScrollToLatest = function () {
      if (!viewport) return;
      historyReviewing = false;
      pendingTmuxLines = 0;
      pendingTmuxAllowPages = false;
      window.clearTimeout(tmuxCopyProbeTimer);
      if (tmuxCopyMode || tmuxCopyPending) {
        sendTerminalData('q');
        tmuxCopyMode = false;
        tmuxCopyPending = false;
        window.setTimeout(reportScroll, 80);
      }
      viewport.scrollTop = viewport.scrollHeight;
      reviewScrollTop = viewport.scrollTop;
      window.setTimeout(reportScroll, 20);
    };
    window.__ubuntuScrollToFraction = function (fraction, immediate) {
      if (!viewport) return;
      historyReviewing = true;
      var safeFraction = Math.max(0, Math.min(1, Number(fraction) || 0));
      if (tmuxCopyMode && tmuxCopyMaximum > 0) {
        tmuxScrollTargetFraction = safeFraction;
        window.clearTimeout(tmuxScrollTargetTimer);
        var applyTmuxFraction = function () {
          var targetOffset = Math.round(
            (1 - tmuxScrollTargetFraction) * tmuxCopyMaximum
          );
          requestTmuxCopyLines(targetOffset - tmuxCopyOffset, true);
          window.setTimeout(reportScroll, 80);
        };
        tmuxScrollTargetTimer = window.setTimeout(
          applyTmuxFraction,
          immediate ? 60 : 120
        );
        return;
      }
      var maxScroll = Math.max(
        0,
        viewport.scrollHeight - viewport.clientHeight
      );
      viewport.scrollTop = maxScroll * safeFraction;
      reviewScrollTop = viewport.scrollTop;
      window.setTimeout(reportScroll, 20);
    };
    [120, 420, 1000].forEach(function (delay) {
      initialLatestTimers.push(window.setTimeout(function () {
        if (!userHasInteracted) window.__ubuntuScrollToLatest();
      }, delay));
    });
    reportScroll();
  }

  function setup() {
    clearStaleTerminalInput();
    attachViewport();
    attachTerminalRenderObserver();
    window.setInterval(function () {
      clearStaleTerminalInput();
      attachViewport();
      attachTerminalRenderObserver();
      maintainReviewAnchor();
      reportScroll();
    }, 400);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', setup);
  } else {
    setup();
  }
})();
</script>
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionCommandSheet(
    commands: List<UserCommand>,
    commandTags: List<CommandTag>,
    operationInProgress: Boolean,
    onDismiss: () -> Unit,
    onInsert: (UserCommand) -> Unit,
    onRun: (UserCommand) -> Unit,
) {
    var selectedTagId by remember { mutableStateOf<String?>(null) }
    val filteredCommands = commands.filter { command ->
        selectedTagId == null || selectedTagId in command.tagIds
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "当前实例快捷指令",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedTagId == null,
                        onClick = { selectedTagId = null },
                        label = { Text("全部") },
                    )
                }
                items(commandTags, key = { it.id }) { tag ->
                    FilterChip(
                        selected = selectedTagId == tag.id,
                        onClick = { selectedTagId = tag.id },
                        label = { Text(tag.name) },
                    )
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredCommands, key = { it.id }) { command ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(command.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                command.script.replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { onInsert(command) }) {
                                    Text("插入终端")
                                }
                                TextButton(
                                    onClick = { onRun(command) },
                                    enabled = !operationInProgress,
                                ) {
                                    Text("后台执行")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun UserCommand.asTerminalLine(): String {
    val encoded = Base64.getEncoder()
        .encodeToString(script.toByteArray(StandardCharsets.UTF_8))
    return "printf '%s' '$encoded' | base64 -d | bash"
}

private fun copyCommand(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Ubuntu 指令", text))
}
