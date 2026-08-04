package cn.termux.ubuntumanager.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.Selection
import android.util.Log
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import cn.termux.ubuntumanager.command.TerminalKeys
import cn.termux.ubuntumanager.command.TerminalShortcuts
import cn.termux.ubuntumanager.command.actionSummary
import cn.termux.ubuntumanager.model.CommandTag
import cn.termux.ubuntumanager.model.LocalSessionBackend
import cn.termux.ubuntumanager.model.LocalSessionPhase
import cn.termux.ubuntumanager.model.LocalSessionState
import cn.termux.ubuntumanager.model.TerminalShortcutAction
import cn.termux.ubuntumanager.model.TerminalShortcutPreference
import cn.termux.ubuntumanager.model.TerminalKeyStroke
import cn.termux.ubuntumanager.model.UserCommand
import cn.termux.ubuntumanager.model.UserCommandType
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener

@Composable
fun LocalSessionScreen(
    instanceName: String,
    sessionState: LocalSessionState,
    commands: List<UserCommand>,
    commandTags: List<CommandTag>,
    terminalShortcuts: List<TerminalShortcutPreference>,
    pendingKeyActionId: String?,
    onNavigateBack: () -> Unit,
    onOpenSession: () -> Unit,
    onCloseSession: () -> Unit,
    onEndSession: () -> Unit,
    onPendingKeyActionConsumed: () -> Unit,
) {
    var terminalWebView by remember(instanceName) { mutableStateOf<TerminalInputWebView?>(null) }
    var showCommands by rememberSaveable { mutableStateOf(false) }
    var ctrlMode by remember { mutableStateOf(TerminalModifierMode.OFF) }
    var altMode by remember { mutableStateOf(TerminalModifierMode.OFF) }
    var scrollState by remember { mutableStateOf(TerminalScrollState()) }
    var lastTerminalInput by remember { mutableStateOf<TerminalInputFingerprint?>(null) }
    var confirmEndSession by remember { mutableStateOf(false) }
    var pageLoading by remember(sessionState.port) { mutableStateOf(true) }
    var pageError by remember(sessionState.port) { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(instanceName) {
        onOpenSession()
    }
    LaunchedEffect(ctrlMode, altMode) {
        terminalWebView?.updateModifiers(
            ctrlActive = ctrlMode != TerminalModifierMode.OFF,
            altActive = altMode != TerminalModifierMode.OFF,
        )
        terminalWebView?.evaluateJavascript(terminalModifierScript(ctrlMode, altMode), null)
    }
    DisposableEffect(instanceName) {
        onDispose {
            terminalWebView?.releaseTerminal()
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
              if (!window.__ubuntuSendRaw) return 'missing';
              window.__ubuntuSendRaw($encoded);
              return 'ok';
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

    fun sendRawToTerminal(text: String) {
        val webView = terminalWebView ?: return
        if (pageLoading) return
        webView.evaluateJavascript(
            "window.__ubuntuSendRaw && window.__ubuntuSendRaw(" +
                JSONObject.quote(text) + ");",
        ) { result ->
            if (cn.termux.ubuntumanager.BuildConfig.DEBUG) {
                Log.d(
                    "CnTerminalIme",
                    "terminalSend=$result chars=${text.length} " +
                        "nonAscii=${text.any { it.code > 127 }}",
                )
            }
        }
    }

    fun sendTerminalKey(
        key: String,
        code: String,
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) {
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
                ctrlKey: $ctrl,
                altKey: $alt,
                shiftKey: $shift,
                bubbles: true,
                cancelable: true
              };
              target.dispatchEvent(new KeyboardEvent('keydown', options));
              return 'ok';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    fun sendAdaptiveInput(value: String, source: String) {
        if (value.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val previous = lastTerminalInput
        if (
            previous?.value == value &&
            previous.source != source &&
            now - previous.timestamp < TERMINAL_CROSS_SOURCE_DEDUPE_MILLIS
        ) {
            return
        }
        lastTerminalInput = TerminalInputFingerprint(value, source, now)
        val ctrlActive = ctrlMode != TerminalModifierMode.OFF
        val altActive = altMode != TerminalModifierMode.OFF
        val modifierCharacter = value.singleOrNull()?.takeIf { it.code in 32..126 }
        if (!ctrlActive && !altActive || modifierCharacter == null) {
            if (modifierCharacter == null) {
                ctrlMode = TerminalModifierMode.OFF
                altMode = TerminalModifierMode.OFF
            }
            sendRawToTerminal(value)
            return
        }
        sendRawToTerminal(
            TerminalKeys.applyModifiers(
                modifierCharacter.toString(),
                ctrlActive,
                altActive,
            ),
        )
        if (ctrlMode == TerminalModifierMode.ONCE) ctrlMode = TerminalModifierMode.OFF
        if (altMode == TerminalModifierMode.ONCE) altMode = TerminalModifierMode.OFF
    }

    fun sendKeyStroke(stroke: TerminalKeyStroke) {
        if (
            stroke.key == TerminalKeys.ESCAPE &&
            terminalWebView?.dismissNativeSelection(resumeInput = true) == true
        ) {
            return
        }
        val option = TerminalKeys.option(stroke.key) ?: return
        val ctrlActive = stroke.ctrl || ctrlMode != TerminalModifierMode.OFF
        val altActive = stroke.alt || altMode != TerminalModifierMode.OFF
        val printable = TerminalKeys.printableText(stroke)
        if (printable != null) {
            val value = TerminalKeys.applyModifiers(printable, ctrlActive, altActive)
            sendRawToTerminal(value)
        } else {
            sendTerminalKey(
                key = option.javascriptKey,
                code = option.javascriptCode,
                keyCode = option.keyCode,
                ctrl = ctrlActive,
                alt = altActive,
                shift = stroke.shift,
            )
        }
        if (ctrlMode == TerminalModifierMode.ONCE) ctrlMode = TerminalModifierMode.OFF
        if (altMode == TerminalModifierMode.ONCE) altMode = TerminalModifierMode.OFF
        if (stroke.key == TerminalKeys.ENTER) {
            terminalWebView?.resetInputLine()
        }
    }

    LaunchedEffect(pendingKeyActionId, pageLoading, terminalWebView) {
        val actionId = pendingKeyActionId ?: return@LaunchedEffect
        val webView = terminalWebView ?: return@LaunchedEffect
        if (pageLoading) return@LaunchedEffect
        val command = commands.firstOrNull {
            it.id == actionId && it.type == UserCommandType.KEY
        }
        if (command?.keyStroke != null) {
            if (webView.awaitTerminalInput()) {
                sendKeyStroke(command.keyStroke)
            } else {
                Toast.makeText(context, "终端未就绪，按键没有发送", Toast.LENGTH_SHORT).show()
            }
        }
        onPendingKeyActionConsumed()
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
                            if (it) {
                                pageError = null
                            }
                        },
                        onPageError = {
                            pageLoading = false
                            pageError = it
                        },
                        ctrlMode = ctrlMode,
                        altMode = altMode,
                        historySnapshot = sessionState.historySnapshot,
                        onModifiedInput = ::sendAdaptiveInput,
                        onCopySelection = { selected ->
                            copyCommand(context, selected)
                            Toast.makeText(
                                context,
                                "已复制选中内容",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
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
                        if (shouldShowReturnToLatest(scrollState.atBottom)) {
                            ReturnToLatestBadge(
                                onClick = {
                                    terminalWebView?.evaluateJavascript(
                                        "window.__ubuntuScrollToLatest && " +
                                            "window.__ubuntuScrollToLatest();",
                                        null,
                                    )
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 18.dp, bottom = 10.dp)
                                    .zIndex(3f),
                            )
                        }
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
                                                        sendKeyStroke(
                                                            TerminalKeyStroke(TerminalKeys.ESCAPE),
                                                        )
                                                    }
                                                TerminalShortcuts.TAB ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendKeyStroke(
                                                            TerminalKeyStroke(TerminalKeys.TAB),
                                                        )
                                                    }
                                                TerminalShortcuts.BACKSPACE ->
                                                    TerminalRepeatingKey(
                                                        label = shortcut.label,
                                                        modifier = modifier,
                                                        contentDescription = "退格",
                                                    ) {
                                                        sendKeyStroke(
                                                            TerminalKeyStroke(
                                                                TerminalKeys.BACKSPACE,
                                                            ),
                                                        )
                                                    }
                                                TerminalShortcuts.DELETE ->
                                                    TerminalRepeatingKey(
                                                        label = shortcut.label,
                                                        modifier = modifier,
                                                        contentDescription = "向前删除",
                                                    ) {
                                                        sendKeyStroke(
                                                            TerminalKeyStroke(TerminalKeys.DELETE),
                                                        )
                                                    }
                                                TerminalShortcuts.ENTER ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendKeyStroke(
                                                            TerminalKeyStroke(TerminalKeys.ENTER),
                                                        )
                                                    }
                                                TerminalShortcuts.LEFT ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendKeyStroke(
                                                            TerminalKeyStroke(TerminalKeys.LEFT),
                                                        )
                                                    }
                                                TerminalShortcuts.UP ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendKeyStroke(
                                                            TerminalKeyStroke(TerminalKeys.UP),
                                                        )
                                                    }
                                                TerminalShortcuts.DOWN ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendKeyStroke(
                                                            TerminalKeyStroke(TerminalKeys.DOWN),
                                                        )
                                                    }
                                                TerminalShortcuts.RIGHT ->
                                                    TerminalKey(shortcut.label, modifier) {
                                                        sendKeyStroke(
                                                            TerminalKeyStroke(TerminalKeys.RIGHT),
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
                                                    if (it.type == UserCommandType.KEY) {
                                                        it.keyStroke?.let(::sendKeyStroke)
                                                    } else {
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
    }

    if (showCommands) {
        SessionCommandSheet(
            commands = commands,
            commandTags = commandTags,
            onDismiss = { showCommands = false },
            onActivate = { command ->
                showCommands = false
                if (command.type == UserCommandType.KEY) {
                    command.keyStroke?.let(::sendKeyStroke)
                } else {
                    sendToTerminal(command.asTerminalLine() + "\r")
                }
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

private data class TerminalInputFingerprint(
    val value: String,
    val source: String,
    val timestamp: Long,
)

private const val TERMINAL_CROSS_SOURCE_DEDUPE_MILLIS = 90L

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

internal fun shouldShowReturnToLatest(atBottom: Boolean): Boolean = !atBottom

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
private fun ReturnToLatestBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xC47A5313), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "回到最新",
            color = Color.White,
            fontSize = 11.sp,
        )
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

private class TerminalInputWebView(context: Context) : WebView(context) {
    var onNativeInput: ((String, String) -> Unit)? = null
    var onDismissNativeSelection: ((Boolean) -> Boolean)? = null
    var appliedPageState: String? = null
    var inputProxy: TerminalImeEditText? = null

    private var ctrlActive = false
    private var altActive = false
    private var released = false

    fun updateModifiers(ctrlActive: Boolean, altActive: Boolean) {
        if (this.ctrlActive == ctrlActive && this.altActive == altActive) return
        this.ctrlActive = ctrlActive
        this.altActive = altActive
        evaluateJavascript(
            "window.__ubuntuResetImeAfterModifier && " +
                "window.__ubuntuResetImeAfterModifier();",
            null,
        )
        inputProxy?.setModifierInputActive(ctrlActive || altActive)
    }

    fun dismissNativeSelection(resumeInput: Boolean): Boolean =
        onDismissNativeSelection?.invoke(resumeInput) == true

    fun resetInputLine() {
        inputProxy?.resetInputLine()
    }

    fun releaseTerminal() {
        if (released) return
        released = true
        onDismissNativeSelection = null
        stopLoading()
        removeJavascriptInterface(TERMINAL_BRIDGE_NAME)
        destroy()
    }
}

private class TerminalImeEditText(context: Context) : EditText(context) {
    var onTerminalInput: ((String, String) -> Unit)? = null

    private var deletingInCodePoints = false
    private var modifierInputActive = false
    private var modifierRestartPending = false
    private var inputLineResetPending = false

    init {
        setRawInputType(TEXT_INPUT_TYPE)
        imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSingleLine = false
        isFocusable = true
        isFocusableInTouchMode = true
        setTextColor(android.graphics.Color.TRANSPARENT)
        setHintTextColor(android.graphics.Color.TRANSPARENT)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isCursorVisible = false
        setPadding(0, 0, 0, 0)
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        alpha = 0.02f
        contentDescription = "终端输入代理"
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        val modifierGate = TerminalModifierInputGate()
        val compositionTracker = TerminalImeCompositionTracker()
        return object : InputConnectionWrapper(base, false) {
            override fun setComposingText(
                text: CharSequence?,
                newCursorPosition: Int,
            ): Boolean {
                if (!modifierGate.intercepts(modifierInputActive)) {
                    val accepted = super.setComposingText(text, newCursorPosition)
                    if (accepted) {
                        compositionTracker.update(currentComposingText())
                    }
                    return accepted
                }
                compositionTracker.clear()
                modifierInputCharacter(text?.toString().orEmpty())?.let { value ->
                    if (modifierGate.consume(modifierInputActive, value) != null) {
                        dispatchTerminalInput(value, "ime-modifier-compose")
                        scheduleModifierInputRestart()
                    }
                }
                return true
            }

            override fun finishComposingText(): Boolean {
                if (modifierGate.intercepts(modifierInputActive)) {
                    compositionTracker.clear()
                    return super.finishComposingText()
                }
                val finalValue = currentComposingText()
                val accepted = super.finishComposingText()
                if (accepted) {
                    compositionTracker.finish(
                        value = finalValue,
                        timestamp = SystemClock.uptimeMillis(),
                    )?.let { value ->
                        dispatchTerminalInput(
                            if (value == "\n") "\r" else value,
                            "ime-compose-finish",
                        )
                        if (value == "\n" || value == "\r") resetInputLine()
                    }
                    trimEditorBuffer()
                }
                return accepted
            }

            override fun commitText(
                text: CharSequence?,
                newCursorPosition: Int,
            ): Boolean {
                val value = text?.toString().orEmpty()
                if (modifierGate.intercepts(modifierInputActive)) {
                    compositionTracker.clear()
                    val normalized = modifierInputCharacter(value)
                        ?: if (value == "\n") "\r" else value
                    if (modifierGate.consume(modifierInputActive, normalized) != null) {
                        dispatchTerminalInput(normalized, "ime-modifier-commit")
                        scheduleModifierInputRestart()
                    }
                    return true
                }
                val selectedSuffixCodePoints = selectedSuffixCodePointCount()
                val accepted = super.commitText(text, newCursorPosition)
                if (accepted && selectedSuffixCodePoints > 0) {
                    dispatchTerminalInput(
                        "\u007f".repeat(selectedSuffixCodePoints),
                        "ime-selection-replace",
                    )
                }
                if (
                    accepted &&
                    compositionTracker.shouldDispatchCommit(
                        value = value,
                        timestamp = SystemClock.uptimeMillis(),
                    )
                ) {
                    dispatchTerminalInput(
                        if (value == "\n") "\r" else value,
                        "ime-commit",
                    )
                }
                if (accepted && (value == "\n" || value == "\r")) resetInputLine()
                trimEditorBuffer()
                return accepted
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val value = terminalValueFor(event)
                if (value == null) return super.sendKeyEvent(event)
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (!modifierGate.intercepts(modifierInputActive)) {
                        dispatchTerminalInput(value, "ime-key-event")
                    } else if (modifierGate.consume(modifierInputActive, value) != null) {
                        dispatchTerminalInput(value, "ime-modifier-key-event")
                        scheduleModifierInputRestart()
                    }
                    if (value == "\r") resetInputLine()
                }
                return true
            }

            override fun deleteSurroundingText(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                val bounded = boundedDeletion(
                    beforeLength = beforeLength,
                    afterLength = afterLength,
                    lengthsAreCodePoints = false,
                )
                if (modifierGate.intercepts(modifierInputActive)) {
                    val value = deletionValue(
                        bounded.terminalBeforeCodePoints,
                        bounded.terminalAfterCodePoints,
                    )
                    if (modifierGate.consume(modifierInputActive, value) != null) {
                        dispatchTerminalInput(value, "ime-modifier-delete")
                        scheduleModifierInputRestart()
                    }
                    return true
                }
                if (!deletingInCodePoints && !hasComposingText()) {
                    dispatchDeletion(bounded, "ime-delete")
                }
                return super.deleteSurroundingText(
                    bounded.connectionBeforeLength,
                    bounded.connectionAfterLength,
                )
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                val bounded = boundedDeletion(
                    beforeLength = beforeLength,
                    afterLength = afterLength,
                    lengthsAreCodePoints = true,
                )
                if (modifierGate.intercepts(modifierInputActive)) {
                    val value = deletionValue(
                        bounded.terminalBeforeCodePoints,
                        bounded.terminalAfterCodePoints,
                    )
                    if (modifierGate.consume(modifierInputActive, value) != null) {
                        dispatchTerminalInput(value, "ime-modifier-delete-codepoint")
                        scheduleModifierInputRestart()
                    }
                    return true
                }
                if (!hasComposingText()) {
                    dispatchDeletion(bounded, "ime-delete-codepoint")
                }
                deletingInCodePoints = true
                return try {
                    super.deleteSurroundingTextInCodePoints(
                        bounded.connectionBeforeLength,
                        bounded.connectionAfterLength,
                    )
                } finally {
                    deletingInCodePoints = false
                }
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                if (!modifierGate.intercepts(modifierInputActive)) {
                    dispatchTerminalInput("\r", "ime-editor-action")
                } else if (modifierGate.consume(modifierInputActive, "\r") != null) {
                    dispatchTerminalInput("\r", "ime-modifier-editor-action")
                    scheduleModifierInputRestart()
                }
                resetInputLine()
                return true
            }

            override fun performContextMenuAction(id: Int): Boolean {
                if (id != android.R.id.paste) return super.performContextMenuAction(id)
                val clipboard = context.getSystemService(
                    Context.CLIPBOARD_SERVICE,
                ) as ClipboardManager
                val value = clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                    .orEmpty()
                if (value.isNotEmpty()) commitText(value, 1)
                return true
            }

            override fun closeConnection() {
                compositionTracker.clear()
                super.closeConnection()
            }
        }
    }

    fun setModifierInputActive(active: Boolean) {
        if (modifierInputActive == active) return
        modifierInputActive = active
        setRawInputType(if (active) MODIFIER_INPUT_TYPE else TEXT_INPUT_TYPE)
        resetTerminalInput(clearBuffer = true)
    }

    fun resetInputLine() {
        if (inputLineResetPending) return
        inputLineResetPending = true
        post {
            inputLineResetPending = false
            resetTerminalInput(clearBuffer = true)
        }
    }

    private fun resetTerminalInput(clearBuffer: Boolean) {
        val editable = text
        BaseInputConnection.removeComposingSpans(editable)
        if (clearBuffer) {
            editable?.clear()
            Selection.setSelection(editable, 0)
        }
        val inputManager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.restartInput(this)
    }

    private fun scheduleModifierInputRestart() {
        if (modifierRestartPending) return
        modifierRestartPending = true
        post {
            modifierRestartPending = false
            if (modifierInputActive) resetTerminalInput(clearBuffer = true)
        }
    }

    private fun hasComposingText(): Boolean =
        BaseInputConnection.getComposingSpanStart(text) >= 0

    private fun currentComposingText(): String? {
        val editable = text ?: return null
        val first = BaseInputConnection.getComposingSpanStart(editable)
        val second = BaseInputConnection.getComposingSpanEnd(editable)
        if (first < 0 || second < 0 || first == second) return null
        val start = minOf(first, second)
        val end = maxOf(first, second)
        if (start !in 0..editable.length || end !in 0..editable.length) return null
        return editable.subSequence(start, end).toString()
    }

    private fun selectedSuffixCodePointCount(): Int {
        val editable = text ?: return 0
        return terminalImeSelectedSuffixCodePointCount(
            value = editable.toString(),
            selectionStart = Selection.getSelectionStart(editable),
            selectionEnd = Selection.getSelectionEnd(editable),
            composingStart = BaseInputConnection.getComposingSpanStart(editable),
            composingEnd = BaseInputConnection.getComposingSpanEnd(editable),
        )
    }

    private fun boundedDeletion(
        beforeLength: Int,
        afterLength: Int,
        lengthsAreCodePoints: Boolean,
    ): TerminalImeDeletionBounds {
        val editable = text ?: return TerminalImeDeletionBounds(0, 0, 0, 0)
        val bounded = terminalImeDeletionBounds(
            value = editable.toString(),
            selectionStart = Selection.getSelectionStart(editable),
            selectionEnd = Selection.getSelectionEnd(editable),
            requestedBeforeLength = beforeLength,
            requestedAfterLength = afterLength,
            lengthsAreCodePoints = lengthsAreCodePoints,
        )
        if (
            cn.termux.ubuntumanager.BuildConfig.DEBUG &&
            (
                bounded.connectionBeforeLength != beforeLength ||
                    bounded.connectionAfterLength != afterLength
                )
        ) {
            Log.d(
                TERMINAL_IME_LOG_TAG,
                "boundedDeletion requested=$beforeLength,$afterLength " +
                    "accepted=${bounded.connectionBeforeLength}," +
                    "${bounded.connectionAfterLength} " +
                    "codePoints=$lengthsAreCodePoints",
            )
        }
        return bounded
    }

    private fun dispatchDeletion(bounded: TerminalImeDeletionBounds, source: String) {
        dispatchTerminalInput(
            deletionValue(
                bounded.terminalBeforeCodePoints,
                bounded.terminalAfterCodePoints,
            ),
            source,
        )
    }

    private fun deletionValue(beforeLength: Int, afterLength: Int): String =
        buildString {
            val safeBefore = beforeLength.coerceIn(0, MAX_EDITOR_BUFFER_CHARS)
            val safeAfter = afterLength.coerceIn(0, MAX_EDITOR_BUFFER_CHARS)
            if (safeBefore > 0) append("\u007f".repeat(safeBefore))
            if (safeAfter > 0) append("\u001b[3~".repeat(safeAfter))
        }

    private fun modifierInputCharacter(value: String): String? =
        value.firstOrNull { it.code in 32..126 }?.toString()

    private fun terminalValueFor(event: KeyEvent): String? = when (event.keyCode) {
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        -> "\r"
        KeyEvent.KEYCODE_DEL -> "\u007f"
        KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
        KeyEvent.KEYCODE_TAB -> "\t"
        KeyEvent.KEYCODE_ESCAPE -> "\u001b"
        KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
        KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
        KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
        KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
        KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
        KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
        KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
        else -> event.unicodeChar
            .takeIf { it > 0 }
            ?.let { String(Character.toChars(it)) }
    }

    private fun dispatchTerminalInput(value: String, source: String) {
        if (value.isEmpty()) return
        if (cn.termux.ubuntumanager.BuildConfig.DEBUG) {
            Log.d(
                TERMINAL_IME_LOG_TAG,
                "source=$source chars=${value.length} " +
                    "codePoints=${value.codePointCount(0, value.length)} " +
                    "nonAscii=${value.any { it.code > 127 }}",
            )
        }
        post { onTerminalInput?.invoke(value, source) }
    }

    private fun trimEditorBuffer() {
        val editable = text ?: return
        if (editable.length <= MAX_EDITOR_BUFFER_CHARS) return
        if (BaseInputConnection.getComposingSpanStart(editable) >= 0) return
        editable.delete(0, editable.length - RETAINED_EDITOR_BUFFER_CHARS)
        Selection.setSelection(editable, editable.length)
    }

    private companion object {
        const val TEXT_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        const val MODIFIER_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        const val MAX_EDITOR_BUFFER_CHARS = 512
        const val RETAINED_EDITOR_BUFFER_CHARS = 128
        const val TERMINAL_IME_LOG_TAG = "CnTerminalIme"
    }
}

private class TerminalHostView(context: Context) : FrameLayout(context) {
    val terminal = TerminalInputWebView(context)
    private val inputProxy = TerminalImeEditText(context)

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAt = 0L
    private var longPressTask: Runnable? = null
    private var nativeSelectionRequested = false
    private var selectionOverlay: TerminalSelectionTextView? = null

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        terminal.isFocusable = false
        terminal.isFocusableInTouchMode = false
        addView(
            terminal,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            inputProxy,
            LayoutParams(2, 2, Gravity.START or Gravity.BOTTOM),
        )
        terminal.inputProxy = inputProxy
        terminal.onDismissNativeSelection = ::dismissNativeSelection
        inputProxy.onTerminalInput = { value, source ->
            terminal.onNativeInput?.invoke(value, source)
        }
        terminal.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelLongPressDetection()
                    nativeSelectionRequested = false
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDownAt = event.eventTime
                    scheduleNativeSelection()
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = kotlin.math.hypot(
                        event.x - touchDownX,
                        event.y - touchDownY,
                    )
                    if (moved >= INPUT_TAP_SLOP_PX) cancelLongPressDetection()
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPressDetection()
                    val elapsed = event.eventTime - touchDownAt
                    val moved = kotlin.math.hypot(
                        event.x - touchDownX,
                        event.y - touchDownY,
                    )
                    if (
                        !nativeSelectionRequested &&
                        elapsed < INPUT_TAP_TIMEOUT_MILLIS &&
                        moved < INPUT_TAP_SLOP_PX
                    ) {
                        post { requestTerminalInput() }
                    }
                }
                MotionEvent.ACTION_CANCEL -> cancelLongPressDetection()
            }
            false
        }
    }

    fun requestTerminalInput() {
        terminal.evaluateJavascript(
            "(function(){" +
                "var active=document.activeElement;" +
                "if(active&&active.blur){active.blur();}" +
                "var helper=document.querySelector('.xterm-helper-textarea');" +
                "if(helper&&helper.blur){helper.blur();}" +
                "return true;" +
                "})();",
        ) {
            post {
                terminal.clearFocus()
                inputProxy.requestFocusFromTouch()
                inputProxy.setSelection(inputProxy.text?.length ?: 0)
                val inputManager =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.restartInput(inputProxy)
                inputManager.showSoftInput(inputProxy, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun releaseTerminal() {
        cancelLongPressDetection()
        selectionOverlay?.releaseSelection()
        selectionOverlay = null
        terminal.onDismissNativeSelection = null
        inputProxy.onTerminalInput = null
        terminal.releaseTerminal()
        removeAllViews()
    }

    private fun scheduleNativeSelection() {
        val task = Runnable {
            longPressTask = null
            nativeSelectionRequested = true
            val now = SystemClock.uptimeMillis()
            val cancelEvent = MotionEvent.obtain(
                touchDownAt,
                now,
                MotionEvent.ACTION_CANCEL,
                touchDownX,
                touchDownY,
                0,
            )
            terminal.dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
            terminal.evaluateJavascript(
                "window.__ubuntuSelectionSnapshot && " +
                    "window.__ubuntuSelectionSnapshot();",
            ) { encoded ->
                val snapshot = parseSelectionSnapshot(encoded)
                if (nativeSelectionRequested && snapshot.text.isNotEmpty()) {
                    showNativeSelection(snapshot, touchDownX, touchDownY)
                }
            }
        }
        longPressTask = task
        postDelayed(task, NATIVE_SELECTION_LONG_PRESS_MILLIS)
    }

    private fun cancelLongPressDetection() {
        longPressTask?.let(::removeCallbacks)
        longPressTask = null
    }

    private fun dismissNativeSelection(resumeInput: Boolean): Boolean {
        val overlay = selectionOverlay ?: return false
        overlay.requestExit(resumeInput)
        return true
    }

    private fun parseSelectionSnapshot(encoded: String): TerminalSelectionSnapshot {
        return runCatching {
            val value = JSONTokener(encoded).nextValue()
            when (value) {
                is JSONObject -> TerminalSelectionSnapshot(
                    text = value.optString("text"),
                    prefixLines = value.optInt("prefixLines", 0).coerceAtLeast(0),
                    viewportRows = value.optInt("viewportRows", 1).coerceAtLeast(1),
                    columns = value.optInt("columns", 1).coerceAtLeast(1),
                )
                is String -> TerminalSelectionSnapshot(text = value)
                else -> TerminalSelectionSnapshot()
            }
        }.getOrDefault(TerminalSelectionSnapshot())
    }

    private fun showNativeSelection(
        snapshot: TerminalSelectionSnapshot,
        x: Float,
        y: Float,
    ) {
        selectionOverlay?.let { existing ->
            existing.releaseSelection()
            removeView(existing)
        }
        val overlay = TerminalSelectionTextView(context) { view, resumeInput ->
            post {
                if (selectionOverlay === view) {
                    view.releaseSelection()
                    removeView(view)
                    selectionOverlay = null
                    nativeSelectionRequested = false
                    if (resumeInput) {
                        post { requestTerminalInput() }
                    } else {
                        inputProxy.clearFocus()
                        val inputManager = context.getSystemService(
                            Context.INPUT_METHOD_SERVICE,
                        ) as InputMethodManager
                        inputManager.hideSoftInputFromWindow(windowToken, 0)
                    }
                }
            }
        }
        selectionOverlay = overlay
        addView(
            overlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        overlay.setTerminalText(snapshot)
        overlay.postDelayed({ overlay.beginSelectionAt(x, y) }, 120L)
    }

    private companion object {
        const val INPUT_TAP_TIMEOUT_MILLIS = 320L
        const val INPUT_TAP_SLOP_PX = 24f
        const val NATIVE_SELECTION_LONG_PRESS_MILLIS = 360L
    }
}

private data class TerminalSelectionSnapshot(
    val text: String = "",
    val prefixLines: Int = 0,
    val viewportRows: Int = 1,
    val columns: Int = 1,
)

private class TerminalSelectionTextView(
    context: Context,
    private val onSelectionFinished: (TerminalSelectionTextView, Boolean) -> Unit,
) : TextView(context) {
    private var snapshot = TerminalSelectionSnapshot()
    private var terminalMetricsApplied = false
    private var selectionActionMode: ActionMode? = null
    private var selectionActionModeGeneration = 0
    private var pendingActionModeDestroy: Runnable? = null
    private val selectionLifecycle = TerminalSelectionLifecycle()
    private val actionModeGeneration = TerminalActionModeGeneration()
    private val exitGesture = TerminalSelectionExitGesture(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
    )

    init {
        setBackgroundColor(android.graphics.Color.rgb(42, 42, 42))
        setTextColor(android.graphics.Color.rgb(242, 242, 242))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = android.graphics.Typeface.MONOSPACE
        includeFontPadding = false
        setLineSpacing(0f, 1f)
        setPadding(2, 2, 5, 2)
        setHorizontallyScrolling(false)
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        isVerticalScrollBarEnabled = true
        scrollBarStyle = SCROLLBARS_INSIDE_OVERLAY
        setTextIsSelectable(true)
        val actionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                cancelPendingActionModeDestroy()
                selectionActionMode = mode
                selectionActionModeGeneration = actionModeGeneration.onCreated()
                selectionLifecycle.activate()
                menu?.add(Menu.NONE, SELECTION_CANCEL_MENU_ID, Menu.NONE, "取消")
                    ?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                logSelectionLifecycle(
                    "create generation=$selectionActionModeGeneration " +
                        "mode=${mode?.let(System::identityHashCode)}",
                )
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                if (item?.itemId == SELECTION_CANCEL_MENU_ID) {
                    finishSelection(resumeInput = false)
                    return true
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                if (selectionActionMode !== mode) return
                selectionActionMode = null
                val destroyedGeneration = selectionActionModeGeneration
                if (!actionModeGeneration.onDestroyed(destroyedGeneration)) return
                if (!selectionLifecycle.isActive) return
                logSelectionLifecycle(
                    "destroy generation=$destroyedGeneration; waiting for replacement",
                )
                scheduleActionModeDestroyCheck(destroyedGeneration)
            }
        }
        customSelectionActionModeCallback = actionModeCallback
        customInsertionActionModeCallback = actionModeCallback
    }

    fun setTerminalText(value: TerminalSelectionSnapshot) {
        snapshot = value
        terminalMetricsApplied = false
        setText(value.text, BufferType.SPANNABLE)
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        val handled = super.onTextContextMenuItem(id)
        if (handled && id == android.R.id.copy) {
            postDelayed({ finishSelection(resumeInput = false) }, 120L)
        }
        return handled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selectionLifecycle.isActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> exitGesture.start(
                    x = event.x,
                    y = event.y,
                    outsideSelection = !isPointInsideSelection(event.x, event.y),
                )
                MotionEvent.ACTION_MOVE -> exitGesture.move(event.x, event.y)
                MotionEvent.ACTION_CANCEL -> exitGesture.cancel()
            }
        }
        val handled = super.onTouchEvent(event)
        if (
            selectionLifecycle.isActive &&
            event.actionMasked == MotionEvent.ACTION_UP &&
            exitGesture.finish(event.x, event.y)
        ) {
            post { finishSelection(resumeInput = true) }
        }
        return handled
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) finishSelection(resumeInput = true)
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finishSelection(resumeInput = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyTerminalMetrics(width, height)
    }

    private fun applyTerminalMetrics(width: Int, height: Int) {
        if (terminalMetricsApplied || width <= 0 || height <= 0) return
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val targetCellWidth = contentWidth.toFloat() / snapshot.columns
        val referenceSizePx = 24f
        paint.textSize = referenceSizePx
        val referenceCellWidth = paint.measureText("M").coerceAtLeast(1f)
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            referenceSizePx * targetCellWidth / referenceCellWidth,
        )
        val targetRowHeight = height.toFloat() / snapshot.viewportRows
        setLineSpacing(targetRowHeight - paint.fontSpacing, 1f)
        terminalMetricsApplied = true
        post {
            scrollTo(
                0,
                (snapshot.prefixLines * targetRowHeight).toInt().coerceAtLeast(0),
            )
        }
    }

    fun beginSelectionAt(x: Float, y: Float) {
        if (text.isEmpty()) return
        if (!isLaidOut || width <= 0 || height <= 0 || layout == null) {
            postDelayed({ beginSelectionAt(x, y) }, 32L)
            return
        }
        requestFocus()
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        dispatchTouchEvent(down)
        down.recycle()
        val handled = performLongClick(x, y)
        val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
        dispatchTouchEvent(up)
        up.recycle()
        if (cn.termux.ubuntumanager.BuildConfig.DEBUG) {
            Log.d(
                "CnTerminalSelection",
                "handled=$handled point=${x.toInt()},${y.toInt()} " +
                    "size=${width}x${height} lines=${layout?.lineCount ?: 0} " +
                    "selection=$selectionStart..$selectionEnd",
            )
        }
        if (handled) {
            selectionLifecycle.activate()
        } else {
            post { finishSelection(resumeInput = false) }
        }
    }

    fun requestExit(resumeInput: Boolean) {
        finishSelection(resumeInput)
    }

    fun releaseSelection() {
        cancelPendingActionModeDestroy()
        selectionLifecycle.release()
        exitGesture.cancel()
        val mode = selectionActionMode
        selectionActionMode = null
        mode?.finish()
    }

    private fun finishSelection(resumeInput: Boolean) {
        val finishResumeInput = selectionLifecycle.requestExit(resumeInput) ?: return
        dispatchSelectionFinished(finishResumeInput)
    }

    private fun dispatchSelectionFinished(resumeInput: Boolean) {
        cancelPendingActionModeDestroy()
        val mode = selectionActionMode
        selectionActionMode = null
        mode?.finish()
        onSelectionFinished(this, resumeInput)
    }

    private fun scheduleActionModeDestroyCheck(destroyedGeneration: Int) {
        cancelPendingActionModeDestroy()
        val task = Runnable {
            pendingActionModeDestroy = null
            if (!actionModeGeneration.isStillDestroyed(destroyedGeneration)) return@Runnable
            logSelectionLifecycle("final destroy generation=$destroyedGeneration")
            selectionLifecycle.onActionModeDestroyed(
                resumeInput = true,
            )?.let(::dispatchSelectionFinished)
        }
        pendingActionModeDestroy = task
        postDelayed(task, SELECTION_ACTION_MODE_DESTROY_GRACE_MILLIS)
    }

    private fun cancelPendingActionModeDestroy() {
        pendingActionModeDestroy?.let(::removeCallbacks)
        pendingActionModeDestroy = null
    }

    private fun logSelectionLifecycle(message: String) {
        if (cn.termux.ubuntumanager.BuildConfig.DEBUG) {
            Log.d("CnTerminalSelection", message)
        }
    }

    private fun isPointInsideSelection(x: Float, y: Float): Boolean {
        val textLayout = layout ?: return false
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(0)
        if (start == end) return false
        val selectionMin = minOf(start, end)
        val selectionMax = maxOf(start, end)
        val vertical = (y - totalPaddingTop + scrollY).roundToInt()
        if (vertical !in 0 until textLayout.height) return false
        val line = textLayout.getLineForVertical(vertical)
        val horizontal = x - totalPaddingLeft + scrollX
        val offset = textLayout.getOffsetForHorizontal(line, horizontal)
        return offset in selectionMin until selectionMax
    }

    private companion object {
        const val SELECTION_CANCEL_MENU_ID = 0x434E5458
        const val SELECTION_ACTION_MODE_DESTROY_GRACE_MILLIS = 360L
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun LocalTerminalWebView(
    url: String,
    port: Int,
    backend: LocalSessionBackend,
    historySnapshot: String,
    onWebViewReady: (TerminalInputWebView) -> Unit,
    onPageLoading: (Boolean) -> Unit,
    onPageError: (String) -> Unit,
    ctrlMode: TerminalModifierMode,
    altMode: TerminalModifierMode,
    onModifiedInput: (String, String) -> Unit,
    onCopySelection: (String) -> Unit,
    onScrollChanged: (Int, Int, Int, Boolean, Float, Float, Boolean) -> Unit,
) {
    val currentModifiedInput = rememberUpdatedState(onModifiedInput)
    val currentCopySelection = rememberUpdatedState(onCopySelection)
    val currentScrollChanged = rememberUpdatedState(onScrollChanged)
    var rendererGeneration by remember(url) { mutableIntStateOf(0) }
    val bridge: TerminalJavascriptBridge = remember {
        TerminalJavascriptBridge(
            onModifiedInput = { value ->
                currentModifiedInput.value(value, "web-keydown")
            },
            onCopySelection = { currentCopySelection.value(it) },
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
    key(rendererGeneration) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TerminalHostView(context).apply {
                    terminal.apply {
                    setBackgroundColor(Color.Black.toArgb())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.safeBrowsingEnabled = true
                    onNativeInput = { value, source ->
                        currentModifiedInput.value(value, source)
                    }
                    updateModifiers(
                        ctrlActive = ctrlMode != TerminalModifierMode.OFF,
                        altActive = altMode != TerminalModifierMode.OFF,
                    )
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

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            (view as? TerminalInputWebView)?.appliedPageState = null
                            onPageLoading(true)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            onPageLoading(false)
                            val stateScript = terminalPageStateScript(
                                ctrlMode,
                                altMode,
                                backend,
                                historySnapshot,
                            )
                            view?.evaluateJavascript(stateScript, null)
                            (view as? TerminalInputWebView)?.appliedPageState = stateScript
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

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?,
                        ): Boolean {
                            (view as? TerminalInputWebView)?.appliedPageState = null
                            onPageLoading(true)
                            view?.post { rendererGeneration += 1 }
                            return true
                        }
                    }
                    onWebViewReady(this)
                    loadUrl(url)
                    }
                }
            },
            update = { host ->
                val view = host.terminal
                if (view.url != url) view.loadUrl(url)
                val stateScript = terminalPageStateScript(
                    ctrlMode,
                    altMode,
                    backend,
                    historySnapshot,
                )
                if (view.appliedPageState != stateScript) {
                    view.updateModifiers(
                        ctrlActive = ctrlMode != TerminalModifierMode.OFF,
                        altActive = altMode != TerminalModifierMode.OFF,
                    )
                    view.evaluateJavascript(stateScript, null)
                    view.appliedPageState = stateScript
                }
            },
            onRelease = { host ->
                host.releaseTerminal()
            },
        )
    }
}

private class TerminalJavascriptBridge(
    private val onModifiedInput: (String) -> Unit,
    private val onCopySelection: (String) -> Unit,
    private val onScrollChanged: (Int, Int, Int, Boolean, Float, Float, Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onModifiedInput(value: String) {
        mainHandler.post { onModifiedInput(value) }
    }

    @JavascriptInterface
    fun onCopySelection(value: String) {
        if (value.isNotEmpty()) mainHandler.post { onCopySelection(value) }
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
    historySnapshot: String,
): String = terminalModifierScript(ctrlMode, altMode) +
    "window.__ubuntuSetBackend && window.__ubuntuSetBackend(" +
    JSONObject.quote(backend.name.lowercase()) + ");" +
    "window.__ubuntuSetHistory && window.__ubuntuSetHistory(" +
    JSONObject.quote(historySnapshot) + ");"

private fun Uri.isAllowedLocalSessionUri(port: Int): Boolean =
    scheme == "http" && host == "127.0.0.1" && this.port == port

private suspend fun WebView.awaitTerminalInput(): Boolean {
    repeat(40) {
        val ready = suspendCancellableCoroutine { continuation ->
            evaluateJavascript(
                "document.querySelector('.xterm-helper-textarea') !== null",
            ) { result ->
                if (continuation.isActive) continuation.resume(result == "true")
            }
        }
        if (ready) return true
        delay(100)
    }
    return false
}

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
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
html,
body {
  -webkit-text-size-adjust: none !important;
  text-size-adjust: none !important;
}
.xterm,
.xterm-viewport,
.xterm-screen {
  touch-action: none !important;
  overscroll-behavior-y: contain !important;
  -webkit-user-select: none !important;
  user-select: none !important;
}
.xterm-viewport::-webkit-scrollbar {
  width: 0 !important;
}
.xterm-viewport {
  overflow-x: hidden !important;
}
.terminal-history,
.terminal-history * {
  -webkit-user-select: text !important;
  user-select: text !important;
}
.terminal-history::-webkit-scrollbar {
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
    if (!terminalSocket || terminalSocket.readyState !== WebSocket.OPEN) return false;
    var encoded = new TextEncoder().encode(value);
    var message = new Uint8Array(encoded.length + 1);
    message[0] = 48;
    message.set(encoded, 1);
    nativeWebSocketSend.call(terminalSocket, message);
    return true;
  }
  window.__ubuntuSendRaw = sendTerminalData;

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

  window.__ubuntuResetImeAfterModifier = function () {
    var input = document.querySelector('.xterm-helper-textarea');
    if (!input) return;
    input.value = '';
  };

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
  var activeTouchId = null;
  var touchStartX = 0;
  var touchStartY = 0;
  var touchLastY = 0;
  var scrollGesture = false;
  var scrollbarDragging = false;
  var scrollbarGrabOffset = 0;
  var longPressTimer = null;
  var selectionMode = false;
  var selectionKind = null;
  var selectionGesture = false;
  var selectionAnchor = null;
  var selectionFocus = null;
  var selectionAdjusting = null;
  var selectionStartHandle = null;
  var selectionEndHandle = null;
  var selectionToolbar = null;
  var selectionMagnifier = null;
  var magnifierBefore = null;
  var magnifierCaret = null;
  var magnifierAfter = null;
  var selectionMoveAnimationFrame = null;
  var pendingSelectionX = 0;
  var pendingSelectionY = 0;
  var historySelectionStart = null;
  var historySelectionEnd = null;
  var historyLayer = null;
  var historyPre = null;
  var historyActive = false;
  var historySource = '';
  var historySourceLines = [];
  var historyPrefixLines = [];
  var lastTerminalFrame = null;
  var historyRenderTimer = null;
  var pendingScrollPixels = 0;
  var scrollAnimationFrame = null;
  var scrollReportTimer = null;
  var terminalRenderDisposable = null;
  var observedTerminal = null;
  var terminalTouchHandlersInstalled = false;

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
    historyActive = false;
    if (historyLayer) historyLayer.style.display = 'none';
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

  function readTerminalFrame() {
    var terminal = window.term;
    if (!terminal || !terminal.buffer || !terminal.buffer.active) return null;
    var buffer = terminal.buffer.active;
    var rows = Math.max(1, terminal.rows || 1);
    var start = Math.max(0, buffer.viewportY || 0);
    var frame = [];
    for (var index = 0; index < rows; index++) {
      var line = buffer.getLine(start + index);
      frame.push(line ? line.translateToString(true) : '');
    }
    return frame;
  }

  function ensureHistoryLayer() {
    if (!touchSurface || historyLayer) return;
    var computed = window.getComputedStyle(touchSurface);
    if (computed.position === 'static') touchSurface.style.position = 'relative';
    historyLayer = document.createElement('div');
    historyLayer.className = 'terminal-history';
    historyLayer.style.position = 'absolute';
    historyLayer.style.left = '0';
    historyLayer.style.top = '0';
    historyLayer.style.right = '0';
    historyLayer.style.bottom = '0';
    historyLayer.style.zIndex = '18';
    historyLayer.style.display = 'none';
    historyLayer.style.overflowY = 'auto';
    historyLayer.style.overflowX = 'hidden';
    historyLayer.style.touchAction = 'none';
    historyLayer.style.background = '#000';
    historyLayer.style.color = '#f2f2f2';
    historyLayer.style.webkitOverflowScrolling = 'touch';
    historyPre = document.createElement('pre');
    historyPre.style.boxSizing = 'border-box';
    historyPre.style.minHeight = '100%';
    historyPre.style.width = '100%';
    historyPre.style.margin = '0';
    historyPre.style.padding = '2px 5px 2px 2px';
    historyPre.style.whiteSpace = 'pre-wrap';
    historyPre.style.wordBreak = 'break-all';
    historyPre.style.overflowWrap = 'anywhere';
    historyPre.style.fontFamily =
      'serif-monospace,"Noto Sans CJK SC",sans-serif';
    historyPre.style.fontSize = '10px';
    historyPre.style.lineHeight = '1.2';
    historyPre.style.letterSpacing = '0';
    historyLayer.appendChild(historyPre);
    touchSurface.appendChild(historyLayer);
    historyLayer.addEventListener('scroll', function () {
      historyReviewing = true;
      scheduleScrollReport();
    }, {passive: true});
  }

  function framesMatch(left, leftStart, right, rightEnd) {
    for (var index = 0; index < rightEnd; index++) {
      if (left[leftStart + index] !== right[index]) return false;
    }
    return true;
  }

  function updateHistoryFrame() {
    var frame = readTerminalFrame();
    if (!frame) return;
    if (!lastTerminalFrame) {
      var prefixCount = Math.max(0, historySourceLines.length - frame.length);
      historyPrefixLines = historySourceLines.slice(0, prefixCount);
      lastTerminalFrame = frame;
      return;
    }
    var commonLength = Math.min(lastTerminalFrame.length, frame.length);
    var shift = 0;
    for (var candidate = 1; candidate < commonLength; candidate++) {
      if (framesMatch(
          lastTerminalFrame,
          candidate,
          frame,
          commonLength - candidate
      )) {
        shift = candidate;
        break;
      }
    }
    if (shift > 0) {
      historyPrefixLines = historyPrefixLines.concat(
        lastTerminalFrame.slice(0, shift)
      );
      if (historyPrefixLines.length > 12000) {
        historyPrefixLines = historyPrefixLines.slice(-12000);
      }
    }
    lastTerminalFrame = frame;
  }

  function renderHistoryNow() {
    historyRenderTimer = null;
    if (!historyActive) return;
    ensureHistoryLayer();
    if (!historyLayer || !historyPre) return;
    var selection = window.getSelection ? window.getSelection() : null;
    if (selection && !selection.isCollapsed &&
        historyLayer.contains(selection.anchorNode)) return;
    var distanceFromBottom = Math.max(
      0,
      historyLayer.scrollHeight - historyLayer.scrollTop - historyLayer.clientHeight
    );
    historyPre.textContent = historyPrefixLines.concat(
      lastTerminalFrame || []
    ).join('\n');
    if (distanceFromBottom <= rowHeight() * 1.5) {
      historyLayer.scrollTop = historyLayer.scrollHeight;
    } else {
      historyLayer.scrollTop = Math.max(
        0,
        historyLayer.scrollHeight - historyLayer.clientHeight - distanceFromBottom
      );
    }
  }

  function scheduleHistoryRender() {
    if (!historyActive || historyRenderTimer !== null) return;
    historyRenderTimer = window.setTimeout(renderHistoryNow, 80);
  }

  function openHistoryReview() {
    ensureHistoryLayer();
    if (!historyLayer) return false;
    updateHistoryFrame();
    historyActive = true;
    historyReviewing = true;
    historyLayer.style.display = 'block';
    if (historyPre) {
      historyPre.textContent = historyPrefixLines.concat(
        lastTerminalFrame || []
      ).join('\n');
    }
    historyLayer.scrollTop = historyLayer.scrollHeight;
    scheduleScrollReport();
    return historyLayer.scrollHeight - historyLayer.clientHeight > 1;
  }

  window.__ubuntuSetHistory = function (text) {
    var next = String(text || '');
    if (next === historySource) return;
    historySource = next;
    historySourceLines = next ? next.replace(/\r/g, '').split('\n') : [];
    historyPrefixLines = [];
    lastTerminalFrame = null;
    updateHistoryFrame();
    if (historyActive) renderHistoryNow();
  };

  window.__ubuntuSelectionSnapshot = function () {
    updateHistoryFrame();
    if (historyActive && historyPre) {
      return {
        text: historyPre.textContent || '',
        prefixLines: 0,
        viewportRows: Math.max(1, (window.term && window.term.rows) || 1),
        columns: Math.max(1, (window.term && window.term.cols) || 1)
      };
    }
    var frame = lastTerminalFrame || readTerminalFrame() || [];
    return {
      text: historyPrefixLines.concat(frame).join('\n'),
      prefixLines: historyPrefixLines.length,
      viewportRows: Math.max(1, (window.term && window.term.rows) || frame.length || 1),
      columns: Math.max(1, (window.term && window.term.cols) || 1)
    };
  };

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
    var chunks = Math.floor(remainder / 6);
    remainder = remainder % 6;
    var pageSequence = lines > 0 ? '\u001b[5~' : '\u001b[6~';
    var chunkSequence = lines > 0 ? '\u001b[15~' : '\u001b[17~';
    var lineSequence = lines > 0 ? '\u001b[A' : '\u001b[B';
    sendTerminalData(
      repeatSequence(pageSequence, pages) +
      repeatSequence(chunkSequence, chunks) +
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
    if (historyActive && historyLayer) {
      var historyHeight = Math.max(12, rowHeight());
      var historyTotal = Math.max(
        1,
        Math.round(historyLayer.scrollHeight / historyHeight)
      );
      var historyVisible = Math.max(
        1,
        Math.round(historyLayer.clientHeight / historyHeight)
      );
      var historyMaximum = Math.max(
        0,
        historyLayer.scrollHeight - historyLayer.clientHeight
      );
      var historyAtBottom =
        historyMaximum - historyLayer.scrollTop <= historyHeight;
      var historyFraction = historyMaximum <= 0
        ? 1
        : Math.max(0, Math.min(1, historyLayer.scrollTop / historyMaximum));
      var historyViewportFraction = historyLayer.scrollHeight <= 0
        ? 1
        : Math.max(0, Math.min(
            1,
            historyLayer.clientHeight / historyLayer.scrollHeight
          ));
      var historyPosition = historyAtBottom
        ? historyTotal
        : Math.min(historyTotal, Math.max(historyVisible, Math.round(
            (historyLayer.scrollTop + historyLayer.clientHeight) / historyHeight
          )));
      var historyReport = 'history:' + historyPosition + ':' + historyTotal +
        ':' + historyVisible + ':' + historyAtBottom + ':' +
        historyFraction.toFixed(4) + ':' + historyViewportFraction.toFixed(4);
      if (historyReport === lastReport) return;
      lastReport = historyReport;
      try {
        window.UbuntuTerminalBridge.onScroll(
          historyPosition,
          historyTotal,
          historyVisible,
          historyAtBottom,
          historyFraction,
          historyViewportFraction,
          true
        );
      } catch (ignored) {
      }
      return;
    }
    var virtualVisible = Math.max(1, (window.term && window.term.rows) || 1);
    var virtualTotal = historyPrefixLines.length +
      ((lastTerminalFrame && lastTerminalFrame.length) || virtualVisible);
    if (viewport.scrollHeight - viewport.clientHeight <= 1 &&
        virtualTotal > virtualVisible) {
      var virtualViewportFraction = Math.max(
        0,
        Math.min(1, virtualVisible / virtualTotal)
      );
      var virtualReport = 'virtual:' + virtualTotal + ':' + virtualVisible;
      if (virtualReport === lastReport) return;
      lastReport = virtualReport;
      try {
        window.UbuntuTerminalBridge.onScroll(
          virtualTotal,
          virtualTotal,
          virtualVisible,
          true,
          1,
          virtualViewportFraction,
          false
        );
      } catch (ignored) {
      }
      return;
    }
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

  function scheduleScrollReport() {
    if (scrollReportTimer !== null) return;
    scrollReportTimer = window.setTimeout(function () {
      scrollReportTimer = null;
      reportScroll();
    }, 32);
  }

  function handleTerminalScroll(deltaY) {
    if (!viewport) return;
    if (historyActive && historyLayer) {
      historyReviewing = true;
      historyLayer.scrollTop -= deltaY;
    } else if (tmuxCopyMode || tmuxCopyPending) {
      sendTerminalData('q');
      tmuxCopyMode = false;
      tmuxCopyPending = false;
      if (openHistoryReview() && historyLayer) historyLayer.scrollTop -= deltaY;
    } else if (viewport.scrollHeight - viewport.clientHeight > 1) {
      historyReviewing = true;
      viewport.scrollTop -= deltaY;
      reviewScrollTop = viewport.scrollTop;
    } else if (openHistoryReview() && historyLayer) {
      historyLayer.scrollTop -= deltaY;
    }
    scheduleScrollReport();
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
    if (historyActive || !viewport || !historyReviewing ||
        tmuxCopyMode || tmuxCopyPending ||
        scrollGesture || scrollbarDragging) return;
    var maximum = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
    var target = Math.max(0, Math.min(maximum, reviewScrollTop));
    if (Math.abs(viewport.scrollTop - target) > 1) {
      viewport.scrollTop = target;
    }
  }

  function attachTerminalRenderObserver() {
    if (!window.term || !window.term.onRender) return;
    if (observedTerminal === window.term && terminalRenderDisposable) return;
    if (terminalRenderDisposable && terminalRenderDisposable.dispose) {
      try {
        terminalRenderDisposable.dispose();
      } catch (ignored) {
      }
    }
    observedTerminal = window.term;
    terminalRenderDisposable = window.term.onRender(function () {
      window.requestAnimationFrame(function () {
        updateHistoryFrame();
        scheduleHistoryRender();
        maintainReviewAnchor();
        if (selectionMode) applyTerminalSelection();
        reportScroll();
      });
    });
  }

  function scrollbarMetrics(rect) {
    if (!viewport || rect.height <= 0) return null;
    var viewportFraction;
    var scrollFraction;
    if (historyActive && historyLayer) {
      if (historyLayer.scrollHeight - historyLayer.clientHeight <= 1) return null;
      viewportFraction = Math.min(
        1,
        historyLayer.clientHeight / historyLayer.scrollHeight
      );
      var historyMaxScroll = Math.max(
        1,
        historyLayer.scrollHeight - historyLayer.clientHeight
      );
      scrollFraction = Math.max(0, Math.min(
        1,
        historyLayer.scrollTop / historyMaxScroll
      ));
    } else if (viewport.scrollHeight - viewport.clientHeight <= 1 &&
        historyPrefixLines.length > 0) {
      var virtualRows = Math.max(1, (window.term && window.term.rows) || 1);
      var virtualLines = historyPrefixLines.length +
        ((lastTerminalFrame && lastTerminalFrame.length) || virtualRows);
      viewportFraction = Math.min(1, virtualRows / virtualLines);
      scrollFraction = 1;
    } else if (tmuxCopyMode && tmuxCopyMaximum > 0) {
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

  function clearLongPressTimer() {
    window.clearTimeout(longPressTimer);
    longPressTimer = null;
  }

  function terminalCellAt(clientX, clientY) {
    var terminal = window.term;
    var screen = document.querySelector('.xterm-screen');
    if (!terminal || !screen || !terminal.buffer || !terminal.buffer.active) {
      return null;
    }
    var rect = screen.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return null;
    var column = Math.max(0, Math.min(
      terminal.cols - 1,
      Math.floor((clientX - rect.left) * terminal.cols / rect.width)
    ));
    var screenRow = Math.max(0, Math.min(
      terminal.rows - 1,
      Math.floor((clientY - rect.top) * terminal.rows / rect.height)
    ));
    return {
      column: column,
      row: (terminal.buffer.active.viewportY || 0) + screenRow,
      columns: Math.max(1, terminal.cols || 1)
    };
  }

  function selectionCellIndex(cell) {
    return cell.row * cell.columns + cell.column;
  }

  function ensureSelectionHandles() {
    if (!touchSurface || (selectionStartHandle && selectionEndHandle)) return;
    function createHandle(name) {
      var handle = document.createElement('div');
      handle.setAttribute('data-selection-handle', name);
      handle.style.position = 'absolute';
      handle.style.width = '44px';
      handle.style.height = '44px';
      handle.style.borderRadius = '50%';
      handle.style.background = 'transparent';
      handle.style.boxSizing = 'border-box';
      handle.style.zIndex = '35';
      handle.style.display = 'none';
      handle.style.pointerEvents = 'auto';
      var knob = document.createElement('span');
      knob.style.position = 'absolute';
      knob.style.left = '11px';
      knob.style.top = '11px';
      knob.style.width = '22px';
      knob.style.height = '22px';
      knob.style.borderRadius = '50%';
      knob.style.background = '#58c7d4';
      knob.style.border = '2px solid rgba(255,255,255,0.9)';
      knob.style.boxSizing = 'border-box';
      knob.style.pointerEvents = 'none';
      handle.appendChild(knob);
      touchSurface.appendChild(handle);
      return handle;
    }
    selectionStartHandle = createHandle('start');
    selectionEndHandle = createHandle('end');
    selectionToolbar = document.createElement('div');
    selectionToolbar.setAttribute('data-terminal-selection-action', 'toolbar');
    selectionToolbar.style.position = 'absolute';
    selectionToolbar.style.zIndex = '38';
    selectionToolbar.style.display = 'none';
    selectionToolbar.style.padding = '2px 4px';
    selectionToolbar.style.borderRadius = '8px';
    selectionToolbar.style.background = 'rgba(42,42,42,0.94)';
    selectionToolbar.style.boxShadow = '0 2px 8px rgba(0,0,0,0.45)';
    function toolbarButton(label, color, action) {
      var button = document.createElement('button');
      button.setAttribute('data-terminal-selection-action', action);
      button.textContent = label;
      button.style.border = '0';
      button.style.background = 'transparent';
      button.style.color = color;
      button.style.fontSize = '13px';
      button.style.padding = '7px 10px';
      button.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopPropagation();
        if (action === 'copy') {
          var selected = currentSelectionText();
          if (selected) {
            try {
              window.UbuntuTerminalBridge.onCopySelection(selected);
            } catch (ignored) {
            }
          }
        }
        window.__ubuntuClearSelection();
      });
      return button;
    }
    selectionToolbar.appendChild(toolbarButton('取消', '#d2d2d2', 'cancel'));
    selectionToolbar.appendChild(toolbarButton('复制', '#72d894', 'copy'));
    touchSurface.appendChild(selectionToolbar);

    selectionMagnifier = document.createElement('div');
    selectionMagnifier.style.position = 'absolute';
    selectionMagnifier.style.left = '0';
    selectionMagnifier.style.top = '0';
    selectionMagnifier.style.zIndex = '40';
    selectionMagnifier.style.display = 'none';
    selectionMagnifier.style.width = '118px';
    selectionMagnifier.style.height = '46px';
    selectionMagnifier.style.boxSizing = 'border-box';
    selectionMagnifier.style.overflow = 'hidden';
    selectionMagnifier.style.whiteSpace = 'pre';
    selectionMagnifier.style.fontFamily =
      'serif-monospace,"Noto Sans CJK SC",sans-serif';
    selectionMagnifier.style.fontSize = '18px';
    selectionMagnifier.style.lineHeight = '22px';
    selectionMagnifier.style.color = '#fff';
    selectionMagnifier.style.background = 'rgba(35,35,35,0.96)';
    selectionMagnifier.style.border = '1px solid rgba(255,255,255,0.7)';
    selectionMagnifier.style.borderRadius = '10px';
    selectionMagnifier.style.boxShadow = '0 3px 10px rgba(0,0,0,0.55)';
    selectionMagnifier.style.willChange = 'transform';
    function magnifierTextSide(side) {
      var span = document.createElement('span');
      span.style.position = 'absolute';
      span.style.top = '50%';
      span.style.width = '50%';
      span.style.overflow = 'hidden';
      span.style.transform = 'translateY(-50%)';
      span.style.whiteSpace = 'pre';
      if (side === 'before') {
        span.style.right = '50%';
        span.style.textAlign = 'right';
        span.style.paddingRight = '3px';
      } else {
        span.style.left = '50%';
        span.style.textAlign = 'left';
        span.style.paddingLeft = '3px';
      }
      return span;
    }
    magnifierBefore = magnifierTextSide('before');
    magnifierAfter = magnifierTextSide('after');
    magnifierCaret = document.createElement('span');
    magnifierCaret.style.position = 'absolute';
    magnifierCaret.style.left = '50%';
    magnifierCaret.style.top = '8px';
    magnifierCaret.style.bottom = '8px';
    magnifierCaret.style.width = '2px';
    magnifierCaret.style.transform = 'translateX(-1px)';
    magnifierCaret.style.background = '#58c7d4';
    magnifierCaret.style.borderRadius = '2px';
    magnifierCaret.style.boxShadow = '0 0 4px rgba(88,199,212,0.8)';
    selectionMagnifier.appendChild(magnifierBefore);
    selectionMagnifier.appendChild(magnifierAfter);
    selectionMagnifier.appendChild(magnifierCaret);
    touchSurface.appendChild(selectionMagnifier);
  }

  function renderMagnifierText(before, after) {
    if (!magnifierBefore || !magnifierAfter) return;
    magnifierBefore.textContent = String(before || '').replace(/\n/g, ' ');
    magnifierAfter.textContent = String(after || '').replace(/\n/g, ' ');
  }

  function currentSelectionText() {
    if (selectionKind === 'history' && historyPre &&
        historySelectionStart !== null && historySelectionEnd !== null) {
      var historyText = historyPre.textContent || '';
      var historyStart = Math.min(historySelectionStart, historySelectionEnd);
      var historyEnd = Math.max(historySelectionStart, historySelectionEnd);
      return historyText.slice(historyStart, historyEnd + 1);
    }
    return window.term && window.term.getSelection
      ? window.term.getSelection()
      : '';
  }

  function terminalSelectionGeometry() {
    var screen = document.querySelector('.xterm-screen');
    var terminal = window.term;
    if (!screen || !terminal || !touchSurface) return null;
    var rect = screen.getBoundingClientRect();
    var surfaceRect = touchSurface.getBoundingClientRect();
    return {
      terminal: terminal,
      rect: rect,
      surfaceRect: surfaceRect,
      cellWidth: rect.width / Math.max(1, terminal.cols),
      cellHeight: rect.height / Math.max(1, terminal.rows)
    };
  }

  function positionSelectionHandle(handle, cell, atEnd, knownGeometry) {
    var geometry = knownGeometry || terminalSelectionGeometry();
    if (!handle || !geometry || !cell) return;
    var terminal = geometry.terminal;
    var rect = geometry.rect;
    var surfaceRect = geometry.surfaceRect;
    var viewportRow = cell.row - (terminal.buffer.active.viewportY || 0);
    if (viewportRow < 0 || viewportRow >= terminal.rows) {
      handle.style.display = 'none';
      return;
    }
    var x = rect.left - surfaceRect.left +
      (cell.column + (atEnd ? 1 : 0)) * geometry.cellWidth - 22;
    var y = rect.top - surfaceRect.top +
      (viewportRow + 1) * geometry.cellHeight - 22;
    handle.style.left = x + 'px';
    handle.style.top = y + 'px';
    handle.style.display = 'block';
  }

  function positionSelectionMagnifier(clientX, clientY) {
    if (!selectionMagnifier || !touchSurface) return;
    var surfaceRect = touchSurface.getBoundingClientRect();
    var width = 118;
    var height = 46;
    var left = clientX - surfaceRect.left - width / 2;
    var top = clientY - surfaceRect.top - height - 34;
    if (top < 4) top = clientY - surfaceRect.top + 30;
    left = Math.max(4, Math.min(surfaceRect.width - width - 4, left));
    top = Math.max(4, Math.min(surfaceRect.height - height - 4, top));
    selectionMagnifier.style.transform =
      'translate3d(' + left + 'px,' + top + 'px,0)';
    selectionMagnifier.style.display = 'block';
  }

  function positionSelectionToolbar() {
    if (!selectionToolbar || !touchSurface ||
        !selectionAnchor || !selectionFocus) return;
    var screen = document.querySelector('.xterm-screen');
    var terminal = window.term;
    if (!screen || !terminal) return;
    var rect = screen.getBoundingClientRect();
    var surfaceRect = touchSurface.getBoundingClientRect();
    var first = selectionCellIndex(selectionAnchor) <= selectionCellIndex(selectionFocus)
      ? selectionAnchor
      : selectionFocus;
    var last = first === selectionAnchor ? selectionFocus : selectionAnchor;
    var firstViewportRow = first.row - (terminal.buffer.active.viewportY || 0);
    var lastViewportRow = last.row - (terminal.buffer.active.viewportY || 0);
    var cellWidth = rect.width / Math.max(1, terminal.cols);
    var cellHeight = rect.height / Math.max(1, terminal.rows);
    selectionToolbar.style.display = 'block';
    var toolbarWidth = selectionToolbar.offsetWidth || 104;
    var toolbarHeight = selectionToolbar.offsetHeight || 40;
    var anchorX = rect.left - surfaceRect.left + first.column * cellWidth;
    var above = rect.top - surfaceRect.top + firstViewportRow * cellHeight -
      toolbarHeight - 8;
    var below = rect.top - surfaceRect.top + (lastViewportRow + 1) * cellHeight + 16;
    selectionToolbar.style.left = Math.max(
      4,
      Math.min(surfaceRect.width - toolbarWidth - 4, anchorX)
    ) + 'px';
    selectionToolbar.style.top = Math.max(
      4,
      Math.min(surfaceRect.height - toolbarHeight - 4, above >= 4 ? above : below)
    ) + 'px';
  }

  function updateSelectionMagnifier(clientX, clientY, knownCell) {
    ensureSelectionHandles();
    if (!selectionMagnifier || !touchSurface) return;
    var cell = knownCell || terminalCellAt(clientX, clientY);
    var terminal = window.term;
    if (!cell || !terminal || !terminal.buffer || !terminal.buffer.active) return;
    var line = terminal.buffer.active.getLine(cell.row);
    var before = '';
    var after = '';
    if (line) {
      for (var beforeColumn = Math.max(0, cell.column - 5);
          beforeColumn < cell.column; beforeColumn++) {
        var beforeCell = line.getCell(beforeColumn);
        before += beforeCell && beforeCell.getChars()
          ? beforeCell.getChars()
          : ' ';
      }
      for (var afterColumn = cell.column;
          afterColumn < Math.min(cell.columns, cell.column + 5); afterColumn++) {
        var afterCell = line.getCell(afterColumn);
        after += afterCell && afterCell.getChars()
          ? afterCell.getChars()
          : ' ';
      }
    }
    renderMagnifierText(
      before,
      after
    );
    positionSelectionMagnifier(clientX, clientY);
  }

  function historyOffsetAt(clientX, clientY) {
    if (!historyPre || !historyPre.firstChild) return null;
    var range = document.caretRangeFromPoint
      ? document.caretRangeFromPoint(clientX, clientY)
      : null;
    if (!range || !historyPre.contains(range.startContainer)) return null;
    var textLength = (historyPre.textContent || '').length;
    var offset = Math.max(0, Math.min(textLength - 1, range.startOffset));
    var text = historyPre.textContent || '';
    if (offset > 0 && offset < text.length &&
        text.charCodeAt(offset) >= 0xDC00 && text.charCodeAt(offset) <= 0xDFFF &&
        text.charCodeAt(offset - 1) >= 0xD800 &&
        text.charCodeAt(offset - 1) <= 0xDBFF) {
      offset--;
    }
    return offset;
  }

  function historyCharacterRect(offset, after) {
    if (!historyPre || !historyPre.firstChild) return null;
    var node = historyPre.firstChild;
    var length = node.textContent.length;
    if (length <= 0) return null;
    var safe = Math.max(0, Math.min(length - 1, offset));
    var range = document.createRange();
    range.setStart(node, safe);
    range.setEnd(node, Math.min(length, safe + 1));
    var rect = range.getBoundingClientRect();
    if (!rect || rect.height <= 0) return null;
    return {
      left: after ? rect.right : rect.left,
      bottom: rect.bottom,
      top: rect.top
    };
  }

  function applyHistorySelection() {
    if (!historyPre || !historyPre.firstChild ||
        historySelectionStart === null || historySelectionEnd === null) return;
    var node = historyPre.firstChild;
    var length = node.textContent.length;
    if (length <= 0) return;
    var start = Math.max(
      0,
      Math.min(length - 1, Math.min(historySelectionStart, historySelectionEnd))
    );
    var end = Math.max(
      start + 1,
      Math.min(length, Math.max(historySelectionStart, historySelectionEnd) + 1)
    );
    var range = document.createRange();
    range.setStart(node, start);
    range.setEnd(node, end);
    var selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    ensureSelectionHandles();
    var surfaceRect = touchSurface.getBoundingClientRect();
    function placeHistoryHandle(handle, rect) {
      if (!handle || !rect) return;
      handle.style.left = (rect.left - surfaceRect.left - 22) + 'px';
      handle.style.top = (rect.bottom - surfaceRect.top - 22) + 'px';
      handle.style.display = 'block';
    }
    var startRect = null;
    var endRect = null;
    if (selectionGesture && historySelectionStart !== historySelectionEnd) {
      var adjustingAnchor = selectionAdjusting === 'anchor';
      var activeOffset = adjustingAnchor
        ? historySelectionStart
        : historySelectionEnd;
      var activeIsStart = adjustingAnchor
        ? historySelectionStart <= historySelectionEnd
        : historySelectionEnd <= historySelectionStart;
      var activeRect = historyCharacterRect(activeOffset, !activeIsStart);
      placeHistoryHandle(
        activeIsStart ? selectionStartHandle : selectionEndHandle,
        activeRect
      );
    } else {
      startRect = historyCharacterRect(start, false);
      endRect = historyCharacterRect(end - 1, true);
      placeHistoryHandle(selectionStartHandle, startRect);
      placeHistoryHandle(selectionEndHandle, endRect);
    }
    if (selectionToolbar && startRect && endRect && !selectionGesture) {
      selectionToolbar.style.display = 'block';
      var toolbarWidth = selectionToolbar.offsetWidth || 104;
      var toolbarHeight = selectionToolbar.offsetHeight || 40;
      var above = startRect.top - surfaceRect.top - toolbarHeight - 8;
      var below = endRect.bottom - surfaceRect.top + 16;
      selectionToolbar.style.left = Math.max(
        4,
        Math.min(
          surfaceRect.width - toolbarWidth - 4,
          startRect.left - surfaceRect.left
        )
      ) + 'px';
      selectionToolbar.style.top = Math.max(
        4,
        Math.min(
          surfaceRect.height - toolbarHeight - 4,
          above >= 4 ? above : below
        )
      ) + 'px';
    }
  }

  function updateHistorySelectionMagnifier(clientX, clientY, knownOffset) {
    ensureSelectionHandles();
    if (!selectionMagnifier || !historyPre || !touchSurface) return;
    var offset = knownOffset === undefined
      ? historyOffsetAt(clientX, clientY)
      : knownOffset;
    if (offset === null) return;
    var text = historyPre.textContent || '';
    renderMagnifierText(
      text.slice(Math.max(0, offset - 5), offset),
      text.slice(offset, Math.min(text.length, offset + 5))
    );
    positionSelectionMagnifier(clientX, clientY);
  }

  function startHistorySelection(clientX, clientY) {
    var offset = historyOffsetAt(clientX, clientY);
    if (offset === null) return;
    var input = document.querySelector('.xterm-helper-textarea');
    if (input) input.blur();
    selectionMode = true;
    selectionKind = 'history';
    selectionGesture = true;
    historySelectionStart = offset;
    historySelectionEnd = offset;
    selectionAdjusting = 'focus';
    applyHistorySelection();
    updateHistorySelectionMagnifier(clientX, clientY, offset);
  }

  function updateHistorySelection(clientX, clientY) {
    var offset = historyOffsetAt(clientX, clientY);
    if (offset === null) return;
    if (selectionAdjusting === 'anchor') {
      historySelectionStart = offset;
    } else {
      historySelectionEnd = offset;
    }
    applyHistorySelection();
    updateHistorySelectionMagnifier(clientX, clientY, offset);
  }

  function applyTerminalSelection() {
    var terminal = window.term;
    if (!terminal || !selectionAnchor || !selectionFocus || !terminal.select) return;
    var anchorIndex = selectionCellIndex(selectionAnchor);
    var focusIndex = selectionCellIndex(selectionFocus);
    var startIndex = Math.min(anchorIndex, focusIndex);
    var endIndex = Math.max(anchorIndex, focusIndex);
    var columns = selectionAnchor.columns;
    terminal.select(
      startIndex % columns,
      Math.floor(startIndex / columns),
      Math.max(1, endIndex - startIndex + 1)
    );
    ensureSelectionHandles();
    var geometry = terminalSelectionGeometry();
    var startsAtAnchor = anchorIndex <= focusIndex;
    positionSelectionHandle(
      selectionStartHandle,
      startsAtAnchor ? selectionAnchor : selectionFocus,
      false,
      geometry
    );
    positionSelectionHandle(
      selectionEndHandle,
      startsAtAnchor ? selectionFocus : selectionAnchor,
      true,
      geometry
    );
    if (selectionGesture) {
      if (selectionToolbar) selectionToolbar.style.display = 'none';
    } else {
      positionSelectionToolbar();
    }
  }

  function updateTerminalSelection(clientX, clientY) {
    var cell = terminalCellAt(clientX, clientY);
    if (!cell || !selectionAnchor || !selectionFocus) return;
    if (selectionAdjusting === 'anchor') {
      selectionAnchor = cell;
    } else {
      selectionFocus = cell;
    }
    applyTerminalSelection();
    updateSelectionMagnifier(clientX, clientY, cell);
  }

  function reportTerminalSelection() {
    if (!selectionToolbar) return;
    if (selectionGesture) {
      selectionToolbar.style.display = 'none';
      return;
    }
    selectionToolbar.style.display = currentSelectionText()
      ? 'block'
      : 'none';
  }

  function startTerminalSelection(clientX, clientY) {
    var cell = terminalCellAt(clientX, clientY);
    if (!cell || !window.term || !window.term.select) return;
    var input = document.querySelector('.xterm-helper-textarea');
    if (input) input.blur();
    selectionMode = true;
    selectionKind = 'terminal';
    selectionGesture = true;
    selectionAnchor = cell;
    selectionFocus = cell;
    selectionAdjusting = 'focus';
    applyTerminalSelection();
    updateSelectionMagnifier(clientX, clientY);
    reportTerminalSelection();
  }

  window.__ubuntuClearSelection = function () {
    clearLongPressTimer();
    if (selectionMoveAnimationFrame !== null) {
      window.cancelAnimationFrame(selectionMoveAnimationFrame);
      selectionMoveAnimationFrame = null;
    }
    selectionMode = false;
    selectionKind = null;
    selectionGesture = false;
    selectionAnchor = null;
    selectionFocus = null;
    selectionAdjusting = null;
    historySelectionStart = null;
    historySelectionEnd = null;
    if (selectionStartHandle) selectionStartHandle.style.display = 'none';
    if (selectionEndHandle) selectionEndHandle.style.display = 'none';
    if (selectionToolbar) selectionToolbar.style.display = 'none';
    if (selectionMagnifier) selectionMagnifier.style.display = 'none';
    if (window.term && window.term.clearSelection) {
      window.term.clearSelection();
    }
    var documentSelection = window.getSelection ? window.getSelection() : null;
    if (documentSelection) documentSelection.removeAllRanges();
  };

  function resetTerminalTouch() {
    clearLongPressTimer();
    if (selectionMoveAnimationFrame !== null) {
      window.cancelAnimationFrame(selectionMoveAnimationFrame);
      selectionMoveAnimationFrame = null;
    }
    if (scrollAnimationFrame !== null) {
      window.cancelAnimationFrame(scrollAnimationFrame);
      flushPendingScroll();
    }
    if (touchSurface && activeTouchId !== null &&
        touchSurface.hasPointerCapture &&
        touchSurface.hasPointerCapture(activeTouchId)) {
      try {
        touchSurface.releasePointerCapture(activeTouchId);
      } catch (ignored) {
      }
    }
    activeTouchId = null;
    scrollGesture = false;
    scrollbarDragging = false;
    selectionGesture = false;
    tmuxTouchPixels = 0;
    pendingScrollPixels = 0;
  }

  function flushSelectionMove() {
    selectionMoveAnimationFrame = null;
    if (!selectionGesture || activeTouchId === null) return;
    if (selectionKind === 'history') {
      updateHistorySelection(pendingSelectionX, pendingSelectionY);
    } else {
      updateTerminalSelection(pendingSelectionX, pendingSelectionY);
    }
  }

  function queueSelectionMove(clientX, clientY) {
    pendingSelectionX = clientX;
    pendingSelectionY = clientY;
    if (selectionMoveAnimationFrame !== null) return;
    selectionMoveAnimationFrame = window.requestAnimationFrame(flushSelectionMove);
  }

  function beginTerminalPointer(event) {
    if (!touchSurface || !touchSurface.contains(event.target)) return;
    if (event.target && event.target.closest &&
        event.target.closest('[data-terminal-selection-action]')) return;
    // A fresh gesture must always replace stale state left by the IME, a
    // rotation, or an old WebView that failed to deliver touchend.
    resetTerminalTouch();
    markUserInteraction();
    activeTouchId = event.pointerId;
    touchStartX = event.clientX;
    touchStartY = event.clientY;
    touchLastY = event.clientY;
    pendingSelectionX = event.clientX;
    pendingSelectionY = event.clientY;

    var targetSelectionHandle = event.target && event.target.getAttribute
      ? event.target.getAttribute('data-selection-handle')
      : null;
    if (historyActive && historyLayer) {
      if (selectionMode && selectionKind === 'history') {
        var historyOffset = historyOffsetAt(event.clientX, event.clientY);
        if (historyOffset === null || historySelectionStart === null ||
            historySelectionEnd === null) return;
        var historyRequestedHandle = targetSelectionHandle;
        if (historyRequestedHandle === 'start') {
          selectionAdjusting = historySelectionStart <= historySelectionEnd
            ? 'anchor'
            : 'focus';
        } else if (historyRequestedHandle === 'end') {
          selectionAdjusting = historySelectionStart <= historySelectionEnd
            ? 'focus'
            : 'anchor';
        } else {
          selectionAdjusting =
            Math.abs(historyOffset - historySelectionStart) <=
              Math.abs(historyOffset - historySelectionEnd)
            ? 'anchor'
            : 'focus';
        }
        selectionGesture = true;
        if (selectionToolbar) selectionToolbar.style.display = 'none';
        event.preventDefault();
        event.stopImmediatePropagation();
        return;
      }
      var historySurfaceRect = touchSurface.getBoundingClientRect();
      var historyLocalX = event.clientX - historySurfaceRect.left;
      var historyLocalY = event.clientY - historySurfaceRect.top;
      var historyMetrics = scrollbarMetrics(historySurfaceRect);
      if (historyMetrics && historyLocalX >= historySurfaceRect.width - 18 &&
          historyLocalY >= historyMetrics.top - 10 &&
          historyLocalY <= historyMetrics.top + historyMetrics.height + 10) {
        scrollbarDragging = true;
        scrollbarGrabOffset = Math.max(0, Math.min(
          historyMetrics.height,
          historyLocalY - historyMetrics.top
        ));
        if (touchSurface.setPointerCapture) {
          try {
            touchSurface.setPointerCapture(event.pointerId);
          } catch (ignored) {
          }
        }
        event.preventDefault();
        event.stopImmediatePropagation();
        return;
      }
      return;
    }

    if (selectionMode) {
      var selectionCell = terminalCellAt(event.clientX, event.clientY);
      if (!selectionCell || !selectionAnchor || !selectionFocus) return;
      var requestedHandle = event.target && event.target.getAttribute
        ? event.target.getAttribute('data-selection-handle')
        : null;
      var anchorIndex = selectionCellIndex(selectionAnchor);
      var focusIndex = selectionCellIndex(selectionFocus);
      if (requestedHandle === 'start') {
        selectionAdjusting = anchorIndex <= focusIndex ? 'anchor' : 'focus';
      } else if (requestedHandle === 'end') {
        selectionAdjusting = anchorIndex <= focusIndex ? 'focus' : 'anchor';
      } else {
        var touchedIndex = selectionCellIndex(selectionCell);
        var anchorDistance = Math.abs(touchedIndex - anchorIndex);
        var focusDistance = Math.abs(touchedIndex - focusIndex);
        selectionAdjusting = anchorDistance <= focusDistance ? 'anchor' : 'focus';
      }
      selectionGesture = true;
      if (selectionToolbar) selectionToolbar.style.display = 'none';
      event.preventDefault();
      event.stopImmediatePropagation();
      return;
    }

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
      if (touchSurface.setPointerCapture) {
        try {
          touchSurface.setPointerCapture(event.pointerId);
        } catch (ignored) {
        }
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      return;
    }

    if (historyActive) return;
  }

  function moveTerminalPointer(event) {
    if (event.pointerId !== activeTouchId || !touchSurface) return;
    if (selectionGesture) {
      queueSelectionMove(event.clientX, event.clientY);
      event.preventDefault();
      event.stopImmediatePropagation();
      return;
    }

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

    var deltaX = event.clientX - touchStartX;
    var deltaY = event.clientY - touchStartY;
    if (!scrollGesture && Math.abs(deltaY) >= 8 &&
        Math.abs(deltaY) > Math.abs(deltaX)) {
      clearLongPressTimer();
      scrollGesture = true;
      if (touchSurface.setPointerCapture) {
        try {
          touchSurface.setPointerCapture(event.pointerId);
        } catch (ignored) {
        }
      }
      if (window.term && window.term.clearSelection) window.term.clearSelection();
    }
    if (historyActive) {
      if (scrollGesture) {
        queueTerminalScroll(event.clientY - touchLastY);
        event.preventDefault();
        event.stopImmediatePropagation();
      }
      touchLastY = event.clientY;
      return;
    }
    if (scrollGesture) {
      queueTerminalScroll(event.clientY - touchLastY);
      event.preventDefault();
      event.stopImmediatePropagation();
    }
    touchLastY = event.clientY;
  }

  function endTerminalPointer(event) {
    if (event.pointerId !== activeTouchId && activeTouchId !== null &&
        event.type !== 'pointercancel') return;
    var handled = scrollbarDragging || selectionGesture || scrollGesture;
    var completedSelection = selectionGesture;
    if (completedSelection && selectionMoveAnimationFrame !== null) {
      window.cancelAnimationFrame(selectionMoveAnimationFrame);
      selectionMoveAnimationFrame = null;
      flushSelectionMove();
    }
    if (selectionMagnifier) selectionMagnifier.style.display = 'none';
    resetTerminalTouch();
    if (completedSelection) {
      if (selectionKind === 'history') {
        applyHistorySelection();
      } else {
        applyTerminalSelection();
      }
      reportTerminalSelection();
    }
    if (handled) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
    window.setTimeout(reportScroll, 20);
  }

  function installTerminalTouchHandlers() {
    if (terminalTouchHandlersInstalled) return;
    terminalTouchHandlersInstalled = true;
    document.addEventListener(
      'pointerdown', beginTerminalPointer, {capture: true, passive: false}
    );
    document.addEventListener(
      'pointermove', moveTerminalPointer, {capture: true, passive: false}
    );
    document.addEventListener(
      'pointerup', endTerminalPointer, {capture: true, passive: false}
    );
    document.addEventListener(
      'pointercancel', endTerminalPointer, {capture: true, passive: false}
    );
    window.addEventListener('blur', resetTerminalTouch, true);
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) resetTerminalTouch();
    }, true);
  }

  function attachViewport() {
    var found = document.querySelector('.xterm-viewport');
    if (!found) return;
    if (found === viewport) {
      touchSurface = found.closest('.xterm') || found.parentElement || found;
      return;
    }
    resetTerminalTouch();
    viewport = found;
    touchSurface = found.closest('.xterm') || found.parentElement || found;
    selectionStartHandle = null;
    selectionEndHandle = null;
    selectionToolbar = null;
    selectionMagnifier = null;
    magnifierBefore = null;
    magnifierCaret = null;
    magnifierAfter = null;
    historyLayer = null;
    historyPre = null;
    historyActive = false;
    ensureHistoryLayer();
    viewport.addEventListener('scroll', function () {
      if (historyActive) return;
      reviewScrollTop = viewport.scrollTop;
      var height = rowHeight();
      historyReviewing =
        viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight > height;
      scheduleScrollReport();
    }, {passive: true});
    window.__ubuntuScrollToLatest = function () {
      if (!viewport) return;
      historyReviewing = false;
      historyActive = false;
      if (historyLayer) historyLayer.style.display = 'none';
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
      if (historyActive && historyLayer) {
        var historyMaxScroll = Math.max(
          0,
          historyLayer.scrollHeight - historyLayer.clientHeight
        );
        historyLayer.scrollTop = historyMaxScroll * safeFraction;
        window.setTimeout(reportScroll, 20);
        return;
      }
      if (viewport.scrollHeight - viewport.clientHeight <= 1 &&
          historyPrefixLines.length > 0 && openHistoryReview()) {
        var openedMaxScroll = Math.max(
          0,
          historyLayer.scrollHeight - historyLayer.clientHeight
        );
        historyLayer.scrollTop = openedMaxScroll * safeFraction;
        window.setTimeout(reportScroll, 20);
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
    installTerminalTouchHandlers();
    attachViewport();
    attachTerminalRenderObserver();
    window.setTimeout(function () {
      if (readTmuxCopyState()) {
        sendTerminalData('q');
        tmuxCopyMode = false;
        tmuxCopyPending = false;
      }
    }, 120);
    window.setInterval(function () {
      clearStaleTerminalInput();
      attachViewport();
      attachTerminalRenderObserver();
      updateHistoryFrame();
      scheduleHistoryRender();
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

@Composable
private fun SessionCommandSheet(
    commands: List<UserCommand>,
    commandTags: List<CommandTag>,
    onDismiss: () -> Unit,
    onActivate: (UserCommand) -> Unit,
) {
    var selectedTagId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmCandidate by remember { mutableStateOf<UserCommand?>(null) }
    val commandListState = rememberLazyListState()
    val filteredCommands = commands.filter { command ->
        selectedTagId == null || selectedTagId in command.tagIds
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun requestActivation(command: UserCommand) {
        if (command.confirmBeforeRun) {
            confirmCandidate = command
        } else {
            onActivate(command)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.38f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    },
            )

            if (landscape) {
                LandscapeCommandPanel(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    commands = commands,
                    filteredCommands = filteredCommands,
                    commandTags = commandTags,
                    selectedTagId = selectedTagId,
                    onSelectTag = { selectedTagId = it },
                    onActivate = ::requestActivation,
                    onDismiss = onDismiss,
                    commandListState = commandListState,
                )
            } else {
                PortraitCommandPanel(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    commands = commands,
                    filteredCommands = filteredCommands,
                    commandTags = commandTags,
                    selectedTagId = selectedTagId,
                    onSelectTag = { selectedTagId = it },
                    onActivate = ::requestActivation,
                    commandListState = commandListState,
                )
            }
        }
    }

    confirmCandidate?.let { command ->
        AlertDialog(
            onDismissRequest = { confirmCandidate = null },
            title = {
                Text(
                    command.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Text(
                    command.actionSummary(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCandidate = null
                        onActivate(command)
                    },
                ) {
                    Text(if (command.type == UserCommandType.KEY) "发送" else "执行")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCandidate = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun PortraitCommandPanel(
    commands: List<UserCommand>,
    filteredCommands: List<UserCommand>,
    commandTags: List<CommandTag>,
    selectedTagId: String?,
    onSelectTag: (String?) -> Unit,
    onActivate: (UserCommand) -> Unit,
    commandListState: LazyListState,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.50f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            SessionCommandContent(
                commands = commands,
                filteredCommands = filteredCommands,
                commandTags = commandTags,
                selectedTagId = selectedTagId,
                onSelectTag = onSelectTag,
                onActivate = onActivate,
                commandListState = commandListState,
            )
        }
    }
}

@Composable
private fun LandscapeCommandPanel(
    commands: List<UserCommand>,
    filteredCommands: List<UserCommand>,
    commandTags: List<CommandTag>,
    selectedTagId: String?,
    onSelectTag: (String?) -> Unit,
    onActivate: (UserCommand) -> Unit,
    onDismiss: () -> Unit,
    commandListState: LazyListState,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    var panelWidthPx by remember { mutableStateOf(1f) }
    var horizontalOffsetPx by remember { mutableStateOf(0f) }
    val draggableState = rememberDraggableState { delta ->
        horizontalOffsetPx = (horizontalOffsetPx + delta).coerceIn(0f, panelWidthPx)
    }

    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.offset {
            IntOffset(horizontalOffsetPx.roundToInt(), 0)
        },
        enter = slideInHorizontally(initialOffsetX = { it }),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.40f)
                .fillMaxHeight()
                .onSizeChanged { panelWidthPx = it.width.toFloat().coerceAtLeast(1f) },
            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Box {
                SessionCommandContent(
                    commands = commands,
                    filteredCommands = filteredCommands,
                    commandTags = commandTags,
                    selectedTagId = selectedTagId,
                    onSelectTag = onSelectTag,
                    onActivate = onActivate,
                    commandListState = commandListState,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(22.dp)
                        .fillMaxHeight()
                        .draggable(
                            state = draggableState,
                            orientation = Orientation.Horizontal,
                            onDragStopped = { velocity ->
                                val dismiss = horizontalOffsetPx >= panelWidthPx * 0.22f ||
                                    velocity > 900f
                                val destination = if (dismiss) panelWidthPx else 0f
                                Animatable(horizontalOffsetPx).animateTo(
                                    targetValue = destination,
                                    animationSpec = tween(durationMillis = 160),
                                ) {
                                    horizontalOffsetPx = value
                                }
                                if (dismiss) onDismiss()
                            },
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(4.dp)
                            .height(48.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCommandContent(
    commands: List<UserCommand>,
    filteredCommands: List<UserCommand>,
    commandTags: List<CommandTag>,
    selectedTagId: String?,
    onSelectTag: (String?) -> Unit,
    onActivate: (UserCommand) -> Unit,
    commandListState: LazyListState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                FilterChip(
                    selected = selectedTagId == null,
                    onClick = { onSelectTag(null) },
                    label = {
                        Text("全部", style = MaterialTheme.typography.labelSmall)
                    },
                    modifier = Modifier.height(32.dp),
                )
            }
            items(commandTags, key = { it.id }) { tag ->
                FilterChip(
                    selected = selectedTagId == tag.id,
                    onClick = { onSelectTag(tag.id) },
                    label = {
                        Text(
                            tag.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.height(32.dp),
                )
            }
        }
        HorizontalDivider()
        LazyColumn(
            state = commandListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (filteredCommands.isEmpty()) {
                item {
                    Text(
                        if (commands.isEmpty()) {
                            "还没有快捷指令，请先在指令页面创建。"
                        } else {
                            "当前标签下没有指令。"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(filteredCommands, key = { _, command -> command.id }) {
                    index, command ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onActivate(command) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                command.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (command.confirmBeforeRun) {
                                Text(
                                    "需确认",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            command.actionSummary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (index < filteredCommands.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
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
