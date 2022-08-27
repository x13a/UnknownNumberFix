package me.lucky.fix.unknownnumber

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class Preferences(ctx: Context) {
    companion object {
        private const val ENABLED = "enabled"
        private const val DELAY = "delay"

        private const val DEFAULT_DELAY = 500L
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

    var isEnabled: Boolean
        get() = prefs.getBoolean(ENABLED, false)
        set(value) = prefs.edit { putBoolean(ENABLED, value) }

    var delay: Long
        get() = prefs.getLong(DELAY, DEFAULT_DELAY)
        set(value) = prefs.edit { putLong(DELAY, value) }
}