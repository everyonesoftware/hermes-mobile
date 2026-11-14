package com.everyonesoftware.hermespocket

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI

private val Charcoal = Color(0xFF191A18)
private val Panel = Color(0xFF232520)
private val Gold = Color(0xFFE5C482)
private val Ink = Color(0xFFF0EEE7)
private val Muted = Color(0xFFA8AA9F)
private val Line = Color(0xFF36392F)
private val WarmTint = Color(0xFF3A3323)
private val PocketColors = darkColorScheme(
    primary = Gold, onPrimary = Charcoal, primaryContainer = WarmTint,
    onPrimaryContainer = Gold, background = Charcoal, onBackground = Ink,
    surface = Charcoal, onSurface = Ink, surfaceVariant = Panel,
    onSurfaceVariant = Muted, outline = Line, secondary = Gold,
    error = Color(0xFFFFB4A9)
)

@Composable
fun PocketApp(state: PocketState, onAction: (PocketAction) -> Unit) {
    MaterialTheme(colorScheme = PocketColors) {
        Surface(color = Charcoal, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                IdentityHeader(state, onAction)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(when (state.screen) {
                        Screen.CHAT -> state.sessionTitle
                        Screen.SESSIONS -> "WORKSPACE"
                        Screen.ACTIVITY -> "RUN INSPECTOR"
                        Screen.CONNECT -> "CONNECTION SETTINGS"
                    }, modifier = Modifier.weight(1f).testTag("screen-context"),
                        fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(12.dp))
                    Text("${state.profile} profile", fontSize = 11.sp, color = Muted)
                }
                HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 20.dp))
                if (state.mode == ConnectionMode.DEMO) {
                    Text("Demo workspace · no server requests or commands", color = Gold, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().background(WarmTint).padding(horizontal = 20.dp, vertical = 9.dp))
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold, trackColor = Panel)
                if (state.error != null) {
                    Row(Modifier.fillMaxWidth().background(Panel).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        // The controller exposes fixed user-safe errors, never raw server exceptions.
                        Text(state.error,
                            modifier = Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        IconButton(onClick = { onAction(PocketAction.DismissError) }) {
                            Icon(Icons.Outlined.Close, "Dismiss error", tint = Muted)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                        when (state.screen) {
                            Screen.SESSIONS -> SessionsView(state, onAction)
                            Screen.CHAT -> ChatView(state, onAction)
                            Screen.ACTIVITY -> ActivityView(state, onAction)
                            Screen.CONNECT -> ConnectView(state, onAction)
                        }
                    }
                }
                HorizontalDivider(color = Line)
                NavigationBar(containerColor = Charcoal, contentColor = Muted, windowInsets = WindowInsets(0, 0, 0, 0)) {
                    Screen.entries.forEach { screen ->
                        NavigationBarItem(selected = state.screen == screen,
                            onClick = { onAction(PocketAction.Navigate(screen)) },
                            icon = { Icon(screen.icon(), contentDescription = null, modifier = Modifier.size(22.dp)) },
                            label = { Text(screen.label, fontSize = 11.sp) },
                            modifier = Modifier.testTag("nav-${screen.name.lowercase()}"),
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Gold, selectedTextColor = Gold,
                                indicatorColor = WarmTint, unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            }
        }
    }
}

private fun Screen.icon(): ImageVector = when (this) {
    Screen.CHAT -> Icons.Outlined.ChatBubbleOutline
    Screen.SESSIONS -> Icons.Outlined.GridView
    Screen.ACTIVITY -> Icons.AutoMirrored.Outlined.ShowChart
    Screen.CONNECT -> Icons.Outlined.Dns
}

@Composable
private fun IdentityHeader(state: PocketState, onAction: (PocketAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = WarmTint) {
            Icon(Icons.Outlined.Bolt, null, tint = Gold, modifier = Modifier.padding(8.dp).size(25.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Hermes", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
            Text(when (state.mode) {
                ConnectionMode.OFFLINE -> "Offline · your workspace awaits"
                ConnectionMode.DEMO -> "Sample workspace · ${state.profile}"
                ConnectionMode.CONNECTED -> "${state.serverName} · ${state.profile}"
            }, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(color = if (state.mode == ConnectionMode.DEMO) WarmTint else Panel, shape = RoundedCornerShape(6.dp)) {
            Text(when (state.mode) {
                ConnectionMode.DEMO -> "DEMO"
                ConnectionMode.OFFLINE -> "OFFLINE"
                ConnectionMode.CONNECTED -> "LIVE"
            }, modifier = Modifier.testTag("mode-badge").padding(horizontal = 8.dp, vertical = 5.dp),
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (state.mode == ConnectionMode.OFFLINE) Muted else Gold)
        }
        IconButton(onClick = { onAction(PocketAction.NewSession) },
            enabled = state.mode != ConnectionMode.OFFLINE && !state.busy,
            modifier = Modifier.testTag("new-session")) {
            Icon(Icons.Outlined.Add, "New conversation")
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Text(title, fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.8).sp, lineHeight = 34.sp)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
}

@Composable
private fun Eyebrow(text: String) {
    Text(text, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
}

@Composable
private fun OfflineCard(onAction: (PocketAction) -> Unit, busy: Boolean) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.Dns, null, tint = Gold, modifier = Modifier.size(30.dp))
            Text("Your agent. Wherever you are.", fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 27.sp)
            Text("Connect to your Hermes gateway to pick up a conversation from your phone. Your agent runs on your server, not here.",
                color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
            Button(onClick = { onAction(PocketAction.Navigate(Screen.CONNECT)) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Connect your server")
            }
            OutlinedButton(onClick = { onAction(PocketAction.Demo) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Text("Explore demo")
            }
            Text("Demo is local and clearly labeled. Nothing is sent or executed.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun SessionsView(state: PocketState, onAction: (PocketAction) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FolderOpen, null, tint = Muted, modifier = Modifier.size(17.dp))
            Text("Personal workspace", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(20.dp))
        Heading("Pick up where you left off.", "Your conversations, wherever you are.")
        Spacer(Modifier.height(24.dp))
        if (state.mode == ConnectionMode.OFFLINE) {
            OfflineCard(onAction, state.busy)
        } else {
            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().testTag("session-search"),
                placeholder = { Text("Search sessions…") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Panel, focusedContainerColor = Panel))
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("RECENT SESSIONS")
                if (state.mode == ConnectionMode.CONNECTED) IconButton(onClick = { onAction(PocketAction.Refresh) }, enabled = !state.busy) {
                    Icon(Icons.Outlined.Refresh, "Refresh sessions", tint = Gold)
                }
            }
            Spacer(Modifier.height(12.dp))
            val sessions = state.sessions.filter { "${it.title} ${it.preview}".contains(search, ignoreCase = true) }
            if (sessions.isEmpty()) {
                Text(if (search.isBlank()) "No conversations yet. Start something new." else "No matching sessions.",
                    color = Muted, modifier = Modifier.padding(vertical = 24.dp))
            }
            sessions.forEach { session ->
                val selected = session.id == state.selectedSessionId
                Surface(onClick = { onAction(PocketAction.SelectSession(session.id)) }, enabled = !state.busy,
                    color = if (selected) Panel else Charcoal, shape = RoundedCornerShape(14.dp),
                    border = if (selected) BorderStroke(1.dp, Line) else null,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = if (selected) Gold else Muted,
                            modifier = Modifier.size(19.dp).padding(top = 2.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(session.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (session.preview.isNotBlank()) Text(session.preview, fontSize = 12.sp, color = Muted,
                                lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                            if (selected) Text("Selected conversation", color = Gold, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
                        }
                        if (session.updatedAt.isNotBlank()) Text(session.updatedAt, fontSize = 10.sp, color = Muted,
                            modifier = Modifier.widthIn(max = 84.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!selected) HorizontalDivider(color = Line)
            }
            Spacer(Modifier.height(22.dp))
            Button(onClick = { onAction(PocketAction.NewSession) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("New conversation")
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Dns, null, tint = Muted, modifier = Modifier.size(20.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(if (state.mode == ConnectionMode.DEMO) "Demo workspace" else state.serverName, fontSize = 12.sp)
                Text(if (state.mode == ConnectionMode.DEMO) "Sample sessions · not connected" else "Session history stays on your server", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ChatView(state: PocketState, onAction: (PocketAction) -> Unit) {
    var draft by rememberSaveable(state.selectedSessionId, state.mode) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            if (state.mode == ConnectionMode.OFFLINE) {
                Heading("A conversation away.", "Connect your workspace to talk with Hermes.")
                Spacer(Modifier.height(24.dp)); OfflineCard(onAction, state.busy)
            } else if (state.messages.isEmpty()) {
                Spacer(Modifier.height(28.dp))
                Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Gold, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(20.dp))
                Heading("What’s on your mind?", if (state.mode == ConnectionMode.DEMO) "Try a local demo conversation. No message leaves this device."
                    else "Start a conversation with your home-server agent.")
            } else {
                Eyebrow(if (state.mode == ConnectionMode.DEMO) "DEMO CONVERSATION" else "CONVERSATION")
                Spacer(Modifier.height(24.dp))
                state.messages.forEach { message ->
                    val user = message.role.equals("user", ignoreCase = true)
                    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                        if (!user) Text(if (state.mode == ConnectionMode.DEMO) "● HERMES · DEMO" else "● HERMES", color = Gold,
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                        Surface(color = if (user) Panel else Charcoal, shape = RoundedCornerShape(16.dp)) {
                            Text(message.text, color = Ink, fontSize = 15.sp, lineHeight = 24.sp,
                                modifier = if (user) Modifier.padding(14.dp) else Modifier)
                        }
                        if (user) Text("You", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (state.busy) Text("Hermes is working…", color = Gold, fontSize = 12.sp)
        }
        val enabled = state.mode != ConnectionMode.OFFLINE && !state.busy
        Surface(color = Panel, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Line),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                TextField(value = draft, onValueChange = { draft = it }, enabled = enabled,
                    placeholder = { Text(if (state.mode == ConnectionMode.OFFLINE) "Connect to message Hermes…" else "Message your agent…", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp).testTag("message-input"), maxLines = 5,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
                FilledIconButton(onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) { onAction(PocketAction.Send(text)); draft = "" }
                }, enabled = enabled && draft.isNotBlank(), modifier = Modifier.size(48.dp).testTag("send-message")) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Gold)
                    else Icon(Icons.Outlined.ArrowUpward, "Send message")
                }
            }
        }
    }
}

@Composable
private fun ActivityView(state: PocketState, onAction: (PocketAction) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Eyebrow(if (state.approval != null) "AWAITING YOUR REVIEW" else "AGENT ACTIVITY")
        Spacer(Modifier.height(16.dp))
        Heading(if (state.mode == ConnectionMode.OFFLINE) "A window into your agent." else state.sessionTitle,
            if (state.mode == ConnectionMode.OFFLINE) "Tools, results, and decisions — together in one place."
            else "Inspect the details. Stay in control.")
        Spacer(Modifier.height(24.dp))
        if (state.mode == ConnectionMode.OFFLINE) OfflineCard(onAction, state.busy)
        else {
            if (state.activity.isEmpty() && state.approval == null) {
                Text("No activity to show yet.", color = Muted)
                Text("Tool activity will appear here when available from your gateway.", color = Muted, fontSize = 12.sp,
                    lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
            }
            state.activity.forEach { activity ->
                var expanded by remember(activity.id) { mutableStateOf(false) }
                Surface(onClick = { expanded = !expanded }, color = Charcoal, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
                        Icon(if (activity.status.equals("completed", true)) Icons.Outlined.Check else Icons.Outlined.Terminal,
                            null, tint = Gold, modifier = Modifier.size(21.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(activity.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(activity.status, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                            if (expanded) SelectionDetail(activity.detail)
                        }
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            if (expanded) "Hide details" else "Show details", tint = Muted)
                    }
                }
                HorizontalDivider(color = Line)
            }
            state.approval?.let { approval ->
                Spacer(Modifier.height(22.dp))
                Surface(color = Panel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Line)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Security, null, tint = Gold, modifier = Modifier.size(21.dp))
                            Text("Review proposed command", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                modifier = Modifier.padding(start = 9.dp))
                        }
                        Text(approval.description, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                        Surface(color = Charcoal, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(approval.command, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp,
                                    modifier = Modifier.padding(14.dp))
                            }
                        }
                        when {
                            state.mode != ConnectionMode.DEMO -> Text("Remote approvals are not supported in this app version.", color = Muted, fontSize = 12.sp)
                            approval.decision != null -> Text(approval.decision, color = Gold, fontSize = 12.sp)
                            else -> {
                                Text("Demo only · this exact command · no execution", color = Muted, fontSize = 11.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { onAction(PocketAction.DecideApproval(approval.id, false)) },
                                        enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) { Text("Deny") }
                                    Button(onClick = { onAction(PocketAction.DecideApproval(approval.id, true)) },
                                        enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) { Text("Allow once") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionDetail(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, color = Muted, fontSize = 12.sp, lineHeight = 20.sp,
            modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun ConnectView(state: PocketState, onAction: (PocketAction) -> Unit) {
    var endpoint by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint) }
    // Intentionally NOT saveable: session tokens must not enter saved instance state.
    var token by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Heading("Your home, connected.", "A secure bridge to your Hermes workspace.\nNo agent runs on your phone.")
        Spacer(Modifier.height(24.dp))
        Surface(color = Panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Line)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, null, tint = Gold, modifier = Modifier.size(25.dp))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(state.serverName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(when (state.mode) {
                        ConnectionMode.OFFLINE -> "Offline · ready when you are"
                        ConnectionMode.DEMO -> "Demo only · no server connected"
                        ConnectionMode.CONNECTED -> "Connected · ${state.profile} profile"
                    }, color = if (state.mode == ConnectionMode.CONNECTED) Gold else Muted,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (state.mode != ConnectionMode.CONNECTED) {
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it; validation = null }, enabled = !state.busy,
                label = { Text("HTTPS gateway address") }, placeholder = { Text("https://hermes.home.example") },
                modifier = Modifier.fillMaxWidth().testTag("gateway-endpoint"), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = token, onValueChange = { token = it; validation = null }, enabled = !state.busy,
                label = { Text("Gateway session token") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("gateway-token"), shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(10.dp))
            Text("Used only for this connection. Not saved on this device.", fontSize = 11.sp, color = Muted, lineHeight = 17.sp)
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lock, null, tint = Gold, modifier = Modifier.size(18.dp))
                Text("Use an authenticated HTTPS gateway over a trusted private network or tunnel. Certificate verification stays on.",
                    fontSize = 12.sp, color = Muted, lineHeight = 20.sp, modifier = Modifier.padding(start = 10.dp))
            }
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                val address = endpoint.trim()
                val uri = runCatching { URI(address) }.getOrNull()
                when {
                    uri == null || !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null ->
                        validation = "Enter a valid HTTPS gateway address."
                    token.isBlank() -> validation = "Enter your gateway session token."
                    else -> {
                        val credential = token
                        token = ""
                        onAction(PocketAction.Connect(address, credential))
                    }
                }
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("connect-submit"),
                shape = RoundedCornerShape(12.dp)) {
                if (state.busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                else { Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                Text(if (state.busy) "Connecting…" else "Connect securely")
            }
            if (state.mode == ConnectionMode.OFFLINE) TextButton(onClick = { onAction(PocketAction.Demo) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Explore demo") }
        }
        if (state.connectionNote != null) {
            Spacer(Modifier.height(18.dp))
            Text(state.connectionNote, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        }
        if (state.mode != ConnectionMode.OFFLINE) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { token = ""; onAction(PocketAction.Disconnect) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Text(if (state.mode == ConnectionMode.DEMO) "Leave demo" else "Disconnect")
            }
        }
        Spacer(Modifier.height(22.dp))
        Eyebrow("PRIVATE BY DESIGN")
        Spacer(Modifier.height(8.dp))
        Text("Your phone is a companion, not an agent host. Connect only to a gateway you trust.", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
    }
}
