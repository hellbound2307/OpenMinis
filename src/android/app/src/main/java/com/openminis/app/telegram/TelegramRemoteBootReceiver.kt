package com.openminis.app.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * [T-android-telegram-remote] Restarts the Telegram remote agent after a
 * system reboot, if the user had enabled it.
 *
 * Broader coverage: the app's own process restart also triggers
 * [TelegramRemoteService] via [MinisApp.onCreate] → background restart
 * pattern, but BOOT_COMPLETED handles the case where the app is not
 * launched immediately after boot.
 */
class TelegramRemoteBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = TelegramRemoteStore.load(context)
        if (cfg.enabled && cfg.isConfigured) {
            TelegramRemoteService.start(context)
        }
    }
}