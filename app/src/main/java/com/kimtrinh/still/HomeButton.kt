package com.kimtrinh.still

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Some OEM ROMs (Formovie/Fengmi, Xiaomi, TCL) refuse third-party Home activities.
 * Like Projectivy, Still then takes the Home button through an accessibility key filter.
 */
class HomeButtonService : AccessibilityService() {
    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (e.action == KeyEvent.ACTION_UP) openHome(this)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        fun openHome(ctx: Context) = ctx.startActivity(
            Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )

        fun enabled(ctx: Context): Boolean {
            val c = ComponentName(ctx, HomeButtonService::class.java)
            val ids = setOf(c.flattenToString(), c.flattenToShortString())
            return Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { it in ids }
        }
    }
}

/** The stock launcher still opens first after power-on; this hands over as soon as boot completes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && HomeButtonService.enabled(ctx)) HomeButtonService.openHome(ctx)
    }
}
