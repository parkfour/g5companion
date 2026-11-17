package dev.g5.companion.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import dev.g5.companion.IntentRuleStore
import dev.g5.companion.MusicAppManager
import dev.g5.companion.MusicAppOption
import dev.g5.companion.NotificationReader
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.g5.companion.ActionDispatcher
import dev.g5.companion.Assistant
import dev.g5.companion.CompanionDeviceHelper
import dev.g5.companion.CustomVerb
import dev.g5.companion.DEFAULT_SYSTEM_PROMPT
import dev.g5.companion.Firmware
import dev.g5.companion.GeminiLive
import dev.g5.companion.Glasses
import dev.g5.companion.MODEL_LIVE
import dev.g5.companion.ParsedAction
import dev.g5.companion.Prefs
import dev.g5.companion.Provider
import dev.g5.companion.ThemeMode
import dev.g5.companion.ThemeState
import dev.g5.companion.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DryRunInfo(
    val phrase: String,
    val action: String,
    val resolvedUri: String?,
    val packageName: String?,
    val attachPhoto: Boolean,
    val extras: Map<String, String>,
    val feedback: String,
    val isLocked: Boolean,
    val requiresUnlock: Boolean,
)

@Composable
fun AiScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val history by Assistant.history.collectAsStateWithLifecycle()
    val status by Assistant.status.collectAsStateWithLifecycle()
    val live by GeminiLive.active.collectAsStateWithLifecycle()

    var provider by remember { mutableStateOf(prefs.provider) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember(provider) { mutableStateOf(prefs.apiKey(provider) != null) }
    var model by remember(provider) { mutableStateOf(prefs.model(provider)) }
    var customUrl by remember { mutableStateOf(prefs.customUrl) }
    var liveMode by remember { mutableStateOf(prefs.liveMode) }
    val canLive = provider == Provider.GEMINI
    var prompt by remember { mutableStateOf(prefs.systemPrompt) }
    var autoDelete by remember { mutableStateOf(prefs.autoDelete) }
    var videoLoop by remember { mutableStateOf(prefs.videoLoop) }
    var wakeWord by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var readNotifs by remember { mutableStateOf(prefs.readNotifications) }
    var announceCalls by remember { mutableStateOf(prefs.announceCalls) }
    var voiceCallsTexts by remember { mutableStateOf(prefs.voiceCallsTexts) }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { !it }) { voiceCallsTexts = false; prefs.voiceCallsTexts = false }
    }
    fun ensureNotificationAccess() {
        if (!NotificationReader.hasAccess(ctx)) {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
    var question by remember { mutableStateOf("") }
    var withPhoto by remember { mutableStateOf(true) }

    // Settings start open only until the selected service has a key; history starts collapsed.
    var settingsOpen by rememberSaveable { mutableStateOf(prefs.apiKey(prefs.provider) == null && prefs.provider != Provider.CUSTOM) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var verbsOpen by rememberSaveable { mutableStateOf(false) }
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    var customVerbs by remember { mutableStateOf(prefs.customVerbs()) }
    var showAddVerbDialog by remember { mutableStateOf(false) }
    var editingVerb by remember { mutableStateOf<CustomVerb?>(null) }
    var dryRunInfo by remember { mutableStateOf<DryRunInfo?>(null) }
    val ruleStore = remember { IntentRuleStore(ctx) }
    var showMusicPicker by remember { mutableStateOf(false) }
    var currentMusicApp by remember { mutableStateOf(MusicAppManager.getSelectedMusicApp(ctx, prefs)) }
    val glasses by Glasses.state.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---------- AI service ----------
        item {
            Text("AI service", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Provider.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = provider == p,
                        onClick = { if (live) GeminiLive.stop(ctx); provider = p; prefs.provider = p; key = "" },
                        shape = SegmentedButtonDefaults.itemShape(i, Provider.entries.size),
                    ) { Text(p.label, maxLines = 1) }
                }
            }
            SectionHeader("${provider.label} settings", settingsOpen, { settingsOpen = !settingsOpen }, subtitle = describe(provider)) {
                if (!hasKey && provider != Provider.CUSTOM) Text("No key", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (settingsOpen) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (provider == Provider.CUSTOM) {
                    OutlinedTextField(
                        customUrl, { customUrl = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Base URL (…/v1)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                OutlinedTextField(
                    key, { key = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (hasKey) "${provider.label} key saved (enter to replace)" else "${provider.label} API key" + if (provider == Provider.CUSTOM) " (optional)" else "") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    model, { model = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Model") },
                    placeholder = { Text(provider.defaultModel.ifBlank { "e.g. llama-3.3-70b" }) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (key.isNotBlank()) { prefs.setApiKey(provider, key); key = "" }
                        prefs.setModel(provider, model)
                        if (provider == Provider.CUSTOM) prefs.customUrl = customUrl
                        hasKey = prefs.apiKey(provider) != null
                        model = prefs.model(provider)
                    }) { Text("Save") }
                    if (hasKey) TextButton(onClick = { prefs.setApiKey(provider, null); hasKey = false }) { Text("Remove key") }
                }
                if (canLive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Live conversation mode")
                            Text("AI button starts/stops a continuous voice chat ($MODEL_LIVE) with periodic POV snapshots", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(liveMode, { if (live) GeminiLive.stop(ctx); liveMode = it; prefs.liveMode = it })
                    }
                }
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("System prompt (all services)") }, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { prefs.systemPrompt = prompt }) { Text("Save prompt") }
                    TextButton(onClick = { prompt = DEFAULT_SYSTEM_PROMPT; prefs.systemPrompt = DEFAULT_SYSTEM_PROMPT }) { Text("Reset") }
                }
            }
        }

        // ---------- Ask ----------
        item {
            HorizontalDivider()
            Text("Ask", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (liveMode && canLive) {
                Button(onClick = { GeminiLive.toggle(ctx) }, Modifier.fillMaxWidth()) { Text(if (live) "Stop live conversation" else "Start live conversation") }
            } else {
                OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("Type a question") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withPhoto, { withPhoto = it })
                    Text("Include POV photo", Modifier.weight(1f))
                    Button(onClick = { Assistant.askTyped(question, withPhoto); question = "" }, enabled = question.isNotBlank()) { Text("Send") }
                }
                OutlinedButton(onClick = { Assistant.onAiButton() }, Modifier.fillMaxWidth()) { Text("Same as AI button (photo + voice)") }
            }
        }

        // ---------- History ----------
        item {
            HorizontalDivider()
            SectionHeader("History (${history.size})", historyOpen, { historyOpen = !historyOpen }) {
                if (historyOpen && history.isNotEmpty()) TextButton(onClick = { Assistant.clearHistory() }) { Text("Clear") }
            }
        }
        if (historyOpen) items(history, key = { it.time }) { TurnCard(it) }

        // ---------- Action Verbs & Intents ----------
        item {
            HorizontalDivider()
            SectionHeader(
                title = "Action Verbs & Intents",
                expanded = verbsOpen,
                onToggle = { verbsOpen = !verbsOpen },
                subtitle = "Voice commands linking actions to Android Intents (${customVerbs.size} custom)",
            ) {
                if (verbsOpen) {
                    IconButton(onClick = { showAddVerbDialog = true }) {
                        Icon(Icons.Filled.Add, "Add custom verb")
                    }
                }
            }
        }
        if (verbsOpen) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Built-in Voice Verbs", style = MaterialTheme.typography.titleSmall)
                        Text("• \"open <any-app>\" — Launches any installed app (e.g. \"open Spotify\", \"open Maps\")", style = MaterialTheme.typography.bodySmall)
                        Text("• \"share with <any-app>\" / \"share it\" — Shares the latest POV photo with any app or opens the share sheet", style = MaterialTheme.typography.bodySmall)
                        Text("• \"display it\" / \"show it\" — Displays the latest POV photo in the default viewer", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { catalogOpen = !catalogOpen },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Supported Intent Catalog (${ActionDispatcher.INTENT_TEMPLATES.size} presets)",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (catalogOpen) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = if (catalogOpen) "Collapse" else "Expand",
                            )
                        }
                        if (catalogOpen) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                ActionDispatcher.INTENT_TEMPLATES.forEach { tpl ->
                                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Text(tpl.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                        Text(tpl.description, style = MaterialTheme.typography.bodySmall)
                                        Text("Action: ${tpl.action}" + (tpl.dataUriTemplate?.let { " · Data: $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Voice example: \"${tpl.defaultPhrase}\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showMusicPicker = true },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Default Music Player", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = currentMusicApp.name + if (currentMusicApp.isInstalled && currentMusicApp.packageName != null) " (Installed)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Voice command \"play <song/artist>\" routes to this app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { showMusicPicker = true }) {
                            Text("Change")
                        }
                    }
                }

                val isAssociated = remember(glasses.mac) { CompanionDeviceHelper.isDeviceAssociated(ctx, glasses.mac) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Companion Device Manager Status", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (isAssociated) "✓ Associated: App holds Background Activity Launch (BAL) exemption to launch intents while phone screen is off and locked."
                            else "⚠️ Not Associated: Android BAL restrictions may block intents with the screen locked in pocket. Tap below to associate DPVR G5.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAssociated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        if (!isAssociated && ctx is android.app.Activity) {
                            Button(
                                onClick = {
                                    CompanionDeviceHelper.requestAssociation(ctx, glasses.name, glasses.mac)
                                },
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                Text("Associate DPVR G5 with Phone")
                            }
                        }
                    }
                }

                Text("Custom Verbs & Intent Rules", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                if (customVerbs.isEmpty()) {
                    Text("No custom verbs configured. Tap + above to add one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    customVerbs.forEach { verb ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("\"${verb.phrase}\"", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    Text("Action: ${verb.action}", style = MaterialTheme.typography.bodySmall)
                                    verb.packageName?.takeIf { it.isNotBlank() }?.let {
                                        Text("Package: $it", style = MaterialTheme.typography.bodySmall)
                                    }
                                    verb.dataUriTemplate?.takeIf { it.isNotBlank() }?.let {
                                        Text("Data: $it", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (verb.attachLatestPhoto) {
                                        Text("Photo: Attaches 768x768 POV photo (FileProvider)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                    if (verb.extras.isNotEmpty()) {
                                        Text("Extras: ${verb.extras.entries.joinToString { "${it.key}=${it.value}" }}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    verb.feedback?.takeIf { it.isNotBlank() }?.let {
                                        Text("Feedback: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        val testParam = ActionDispatcher.sampleParameterFor(verb)
                                        val res = ActionDispatcher.execute(ctx, ParsedAction.Custom(verb, parameter = testParam), null)
                                        val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
                                        val locked = km?.isKeyguardLocked == true
                                        val isAuthGated = verb.action == android.content.Intent.ACTION_SEND ||
                                            verb.action == android.content.Intent.ACTION_SENDTO ||
                                            verb.packageName?.contains("messaging") == true ||
                                            verb.packageName?.contains("whatsapp") == true
                                        dryRunInfo = DryRunInfo(
                                            phrase = verb.phrase + (testParam?.let { " (target: $it)" } ?: ""),
                                            action = res.intent?.action ?: verb.action,
                                            resolvedUri = res.intent?.dataString ?: verb.dataUriTemplate,
                                            packageName = res.intent?.`package` ?: verb.packageName,
                                            attachPhoto = verb.attachLatestPhoto || res.intent?.hasExtra(android.content.Intent.EXTRA_STREAM) == true,
                                            extras = verb.extras,
                                            feedback = res.spokenFeedback,
                                            isLocked = locked,
                                            requiresUnlock = locked && isAuthGated,
                                        )
                                        Assistant.say(res.spokenFeedback)
                                    }
                                }) {
                                    Icon(Icons.Filled.PlayArrow, "Dry-run test verb")
                                }
                                IconButton(onClick = { editingVerb = verb }) {
                                    Icon(Icons.Filled.Edit, "Edit verb")
                                }
                                IconButton(onClick = {
                                    val updated = customVerbs.filter { it.id != verb.id }
                                    customVerbs = updated
                                    prefs.setCustomVerbs(updated)
                                }) {
                                    Icon(Icons.Filled.Delete, "Delete verb", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------- Other settings ----------
        item {
            HorizontalDivider()
            Text("Other settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Delete from glasses after sync")
                    Text("Each file only after its phone copy is downloaded and size-verified", style = MaterialTheme.typography.bodySmall)
                }
                Switch(autoDelete, { autoDelete = it; prefs.autoDelete = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Continuous video loop")
                    Text("Starts a new clip whenever the glasses end one at their length limit", style = MaterialTheme.typography.bodySmall)
                }
                Switch(videoLoop, { videoLoop = it; prefs.videoLoop = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Hands-free wake word")
                    Text("On-device \"hey …\" trigger. Turn off if it fires on the TV or other voices; the AI button still works", style = MaterialTheme.typography.bodySmall)
                }
                Switch(wakeWord, { wakeWord = it; prefs.wakeWordEnabled = it; Glasses.refreshVoiceStatus() })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Read notifications aloud")
                    Text("Speaks new alerts through the glasses while they're connected. Silent ones and Do Not Disturb are respected", style = MaterialTheme.typography.bodySmall)
                }
                Switch(readNotifs, {
                    readNotifs = it; prefs.readNotifications = it
                    if (it) ensureNotificationAccess()
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Announce incoming calls")
                    Text("Says \"Call from …\" through the glasses when the phone rings. Uses notification access", style = MaterialTheme.typography.bodySmall)
                }
                Switch(announceCalls, {
                    announceCalls = it; prefs.announceCalls = it
                    if (it) ensureNotificationAccess()
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Voice calls and texts")
                    Text("\"Call Alex\", \"text Alex saying running late\". Texts are read back and only sent after you say yes. Needs contacts, phone and SMS access", style = MaterialTheme.typography.bodySmall)
                }
                Switch(voiceCallsTexts, {
                    voiceCallsTexts = it; prefs.voiceCallsTexts = it
                    if (it) callPerms.launch(CALL_TEXT_PERMISSIONS)
                })
            }
            FirmwareRow(glasses)
            Text("Theme", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = ThemeState.mode == m,
                        onClick = { ThemeState.mode = m; prefs.theme = m },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(m.label) }
                }
            }
            if (glasses.mac != null) {
                Button(
                    onClick = { confirmForget = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828), // solid red in both themes; M3 dark "error" is pale pink
                        contentColor = Color.White,
                    ),
                ) { Text("Forget ${glasses.name ?: "these glasses"}") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget ${glasses.name ?: "these glasses"}?") },
            text = { Text("Disconnects and stops auto-reconnecting. You'll need to scan and pair again on the Glasses tab. Synced media stays on the phone.") },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; Glasses.forget() }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }

    if (showAddVerbDialog || editingVerb != null) {
        val targetVerb = editingVerb
        var newPhrase by remember(targetVerb) { mutableStateOf(targetVerb?.phrase.orEmpty()) }
        var newAction by remember(targetVerb) { mutableStateOf(targetVerb?.action ?: android.content.Intent.ACTION_VIEW) }
        var newPkg by remember(targetVerb) { mutableStateOf(targetVerb?.packageName.orEmpty()) }
        var newUri by remember(targetVerb) { mutableStateOf(targetVerb?.dataUriTemplate.orEmpty()) }
        var newFeedback by remember(targetVerb) { mutableStateOf(targetVerb?.feedback.orEmpty()) }
        var attachPhoto by remember(targetVerb) { mutableStateOf(targetVerb?.attachLatestPhoto ?: false) }
        var extrasStr by remember(targetVerb) {
            mutableStateOf(targetVerb?.extras?.entries?.joinToString(",") { "${it.key}=${it.value}" }.orEmpty())
        }

        AlertDialog(
            onDismissRequest = {
                showAddVerbDialog = false
                editingVerb = null
            },
            title = { Text(if (targetVerb != null) "Edit Custom Verb" else "Add Custom Verb") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (targetVerb == null) {
                        Text("Preset Templates (tap to populate):", style = MaterialTheme.typography.labelSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(ActionDispatcher.INTENT_TEMPLATES) { tpl ->
                                AssistChip(
                                    onClick = {
                                        newPhrase = tpl.defaultPhrase
                                        newAction = tpl.action
                                        newPkg = tpl.packageName.orEmpty()
                                        newUri = tpl.dataUriTemplate.orEmpty()
                                        newFeedback = tpl.feedback.orEmpty()
                                        if (tpl.action == android.content.Intent.ACTION_SEND) {
                                            attachPhoto = true
                                        }
                                    },
                                    label = { Text(tpl.title, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                    Text(
                        "Tip: Use {target} or {query} in phrase and URI for dynamic arguments (e.g. \"navigate to {target}\").",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = newPhrase,
                        onValueChange = { newPhrase = it },
                        label = { Text("Voice phrase (e.g. \"navigate to {target}\")") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newAction,
                        onValueChange = { newAction = it },
                        label = { Text("Intent Action") },
                        placeholder = { Text("android.intent.action.VIEW") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newPkg,
                        onValueChange = { newPkg = it },
                        label = { Text("Target Package (optional)") },
                        placeholder = { Text("e.g. com.google.android.apps.maps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newUri,
                        onValueChange = { newUri = it },
                        label = { Text("Data URI / template (optional)") },
                        placeholder = { Text("e.g. google.navigation:q={target}&mode=d") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { attachPhoto = !attachPhoto },
                    ) {
                        Checkbox(checked = attachPhoto, onCheckedChange = { attachPhoto = it })
                        Text("Attach latest POV photo (768x768 FileProvider)", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = extrasStr,
                        onValueChange = { extrasStr = it },
                        label = { Text("Custom Extras (optional, key=value)") },
                        placeholder = { Text("e.g. source=glasses,mode=walk") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newFeedback,
                        onValueChange = { newFeedback = it },
                        label = { Text("Spoken Feedback (optional)") },
                        placeholder = { Text("e.g. Navigating to {target}.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPhrase.isNotBlank()) {
                            val parsedExtras = mutableMapOf<String, String>()
                            if (extrasStr.isNotBlank()) {
                                extrasStr.split(",").forEach { part ->
                                    val pair = part.split("=", limit = 2)
                                    if (pair.size == 2 && pair[0].isNotBlank()) {
                                        parsedExtras[pair[0].trim()] = pair[1].trim()
                                    }
                                }
                            }
                            val verb = CustomVerb(
                                id = targetVerb?.id ?: java.util.UUID.randomUUID().toString(),
                                phrase = newPhrase.trim(),
                                action = newAction.ifBlank { android.content.Intent.ACTION_VIEW }.trim(),
                                packageName = newPkg.trim().ifBlank { null },
                                dataUriTemplate = newUri.trim().ifBlank { null },
                                feedback = newFeedback.trim().ifBlank { null },
                                attachLatestPhoto = attachPhoto,
                                extras = parsedExtras,
                            )
                            val updated = if (targetVerb != null) {
                                customVerbs.map { if (it.id == targetVerb.id) verb else it }
                            } else {
                                customVerbs + verb
                            }
                            customVerbs = updated
                            prefs.setCustomVerbs(updated)
                            showAddVerbDialog = false
                            editingVerb = null
                        }
                    },
                    enabled = newPhrase.isNotBlank(),
                ) { Text(if (targetVerb != null) "Save" else "Add") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddVerbDialog = false
                    editingVerb = null
                }) { Text("Cancel") }
            },
        )
    }

    dryRunInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { dryRunInfo = null },
            title = { Text("Dry-Run Intent Test") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Command: \"${info.phrase}\"", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider()
                    Text("Intent Action: ${info.action}", style = MaterialTheme.typography.bodySmall)
                    info.resolvedUri?.let { Text("Resolved URI: $it", style = MaterialTheme.typography.bodySmall) }
                    info.packageName?.let { Text("Target Package: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Attach Photo: ${if (info.attachPhoto) "Yes (768x768 FileProvider)" else "No"}", style = MaterialTheme.typography.bodySmall)
                    if (info.extras.isNotEmpty()) {
                        Text("Extras: ${info.extras.entries.joinToString { "${it.key}=${it.value}" }}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Text(
                        if (info.requiresUnlock) "Lockscreen: Auth-gated app (requires phone unlock to send)"
                        else if (info.isLocked) "Lockscreen: Permitted with screen off (BAL compliant)"
                        else "Device Status: Phone is unlocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (info.requiresUnlock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    )
                    Text("Spoken Feedback: \"${info.feedback}\"", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { dryRunInfo = null }) { Text("OK") }
            },
        )
    }

    if (showMusicPicker) {
        val availableApps = remember { MusicAppManager.getAvailableMusicApps(ctx) }
        AlertDialog(
            onDismissRequest = { showMusicPicker = false },
            title = { Text("Select Music Player") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Choose which player opens when you say \"play <song or artist>\" from your glasses:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(availableApps, key = { it.id }) { option ->
                            val isSelected = option.id == currentMusicApp.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        MusicAppManager.selectMusicApp(ctx, option, ruleStore, prefs)
                                        currentMusicApp = option
                                        customVerbs = prefs.customVerbs()
                                        showMusicPicker = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        MusicAppManager.selectMusicApp(ctx, option, ruleStore, prefs)
                                        currentMusicApp = option
                                        customVerbs = prefs.customVerbs()
                                        showMusicPicker = false
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            option.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        if (option.isInstalled && option.packageName != null) {
                                            Text(
                                                "Installed",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    Text(
                                        option.packageName ?: "System Default Media Search",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMusicPicker = false }) {
                    Text("Close")
                }
            },
        )
    }
}

/** Installed firmware + a manual "Check for update" against DPVR's server (only version + model code are sent). */
@Composable
private fun FirmwareRow(g: Glasses.State) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Firmware")
            Text(
                g.btVersion?.let { "Bluetooth $it · camera ${g.ispVersion ?: "–"}" } ?: "Connect the glasses to read the version",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(
            enabled = !checking && g.btVersion != null && g.modelCode != null,
            onClick = {
                checking = true
                result = null
                scope.launch {
                    result = runCatching { Firmware.check(g.btVersion!!, g.modelCode!!) }.fold(
                        onSuccess = {
                            when (it) {
                                Firmware.Result.UpToDate -> "Up to date."
                                is Firmware.Result.Available -> "Version ${it.version} is available. Install it with the DPVR app." +
                                    it.notes.takeIf { n -> n.isNotBlank() }?.let { n -> "\n$n" }.orEmpty()
                            }
                        },
                        onFailure = { "Couldn't check: ${it.message}" },
                    )
                    checking = false
                }
            },
        ) { Text(if (checking) "Checking…" else "Check for update") }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
}

private fun describe(p: Provider) = when (p) {
    Provider.GEMINI -> "Hears your voice directly · Google Search"
    Provider.CLAUDE -> "Voice transcribed on the phone · web search"
    Provider.OPENAI -> "Voice transcribed on the phone · web search"
    Provider.CUSTOM -> "Any OpenAI-compatible URL (OpenRouter, Groq, Ollama…) · no web search"
}

/** Tappable title row with an expand/collapse chevron and optional trailing content. */
@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}

@Composable
private fun TurnCard(t: Turn) {
    val img by produceState<ImageBitmap?>(null, t.imagePath) {
        value = t.imagePath?.let { p -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p)?.asImageBitmap() } }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            img?.let { Image(it, null, Modifier.size(72.dp), contentScale = ContentScale.Crop) }
            Column {
                Text(DateUtils.getRelativeTimeSpanString(t.time).toString(), style = MaterialTheme.typography.labelSmall)
                Text(t.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(t.answer, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val CALL_TEXT_PERMISSIONS = arrayOf(
    android.Manifest.permission.READ_CONTACTS,
    android.Manifest.permission.CALL_PHONE,
    android.Manifest.permission.SEND_SMS,
)
