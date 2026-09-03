package com.openminis.app.telegram

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.backup.remote.TelegramClient
import com.openminis.app.ui.settings.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-telegram-remote] Settings screen for the Telegram remote agent.
 *
 * Flow:
 *  1. Paste the bot token from @BotFather into the field, tap Connect.
 *     The screen verifies the token (getMe) and, if a chat has messaged the
 *     bot, pairs with the most recent one (detectChatId). The chat id is the
 *     authorization anchor — only that chat may drive the agent.
 *  2. Flip the Enabled switch to start the polling foreground service.
 *  3. Optional: Reset to start a fresh conversation session on next message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramRemoteScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var token by remember { mutableStateOf(TelegramRemoteStore.botToken(context)) }
    var enabled by remember { mutableStateOf(TelegramRemoteStore.load(context).enabled) }
    var chatId by remember { mutableStateOf(TelegramRemoteStore.chatId(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun setEnabled(value: Boolean) {
        enabled = value
        TelegramRemoteStore.saveEnabled(context, value)
        if (value) {
            TelegramRemoteService.start(context)
            status = "Service starting…"
        } else {
            TelegramRemoteService.stop(context)
            status = "Service stopped."
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Telegram Remote") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "Control Minis from Telegram — from any device, even when the " +
                    "phone is locked. The bot you supply (from @BotFather) is the " +
                    "only channel; it is not shared with anyone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            SettingsSection("Bot connection") {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bot token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        busy = true
                        status = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val t = TelegramRemoteClient.normalizeBotToken(token.trim())
                                    TelegramRemoteStore.saveBotToken(context, t)
                                    // Verify + find the paired chat.
                                    val client = TelegramClient(context)
                                    val detected = client.detectChatId(t)
                                    if (detected == null) {
                                        "Token valid, but no chat has messaged this bot yet. " +
                                            "Send any message to the bot from your Telegram first, then Connect again."
                                    } else {
                                        TelegramRemoteStore.saveChatId(context, detected.first)
                                        chatId = detected.first
                                        "Connected. Paired with chat ${detected.first} (${detected.second})."
                                    }
                                }
                            }
                            status = result.getOrElse { "Connection failed: ${it.message}" }
                            busy = false
                        }
                    },
                    enabled = !busy && token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Connecting…" else "Connect bot") }
            }

            if (chatId.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SettingsSection("Authorization") {
                    Text(
                        "Only this Telegram chat can drive the agent.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Paired chat: $chatId",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Commands: /new (fresh session)  /stop (cancel turn)  /status",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingsSection("Service") {
                RowToggle(
                    title = "Enabled",
                    subtitle = "Keep polling in the background. Uses a foreground service.",
                    checked = enabled,
                    onCheckedChange = { setEnabled(it) },
                )
            }

            if (status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RowToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = { onCheckedChange(it) })
    }
}
