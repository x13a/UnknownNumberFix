package me.lucky.fix.unknownnumber

import android.Manifest
import android.content.ContentValues
import android.database.Cursor
import android.os.Build
import android.provider.CallLog
import android.service.notification.NotificationListenerService
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import androidx.core.database.getStringOrNull
import java.lang.ref.WeakReference
import java.util.*
import kotlin.concurrent.timerTask

class NotificationListenerService : NotificationListenerService() {
    private lateinit var prefs: Preferences
    private var telephonyManager: TelephonyManager? = null
    private val phoneStateListener by lazy { Listener(WeakReference(this)) }

    override fun onCreate() {
        super.onCreate()
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        deinit()
    }

    private fun init() {
        prefs = Preferences(this)
        telephonyManager = getSystemService(TelephonyManager::class.java)
        @Suppress("deprecation")
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun deinit() {
        @Suppress("deprecation")
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            migrateNotificationFilter(0, null)
    }

    private fun checkUnknownNumber(): Int? {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1")
                    .build(),
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
                CallLog.Calls.DEFAULT_SORT_ORDER,
            )
        } catch (exc: SecurityException) {}
        var id: Int? = null
        cursor?.apply {
            if (moveToFirst()) {
                if (getStringOrNull(getColumnIndex(CallLog.Calls.NUMBER)).isNullOrEmpty())
                    id = getInt(getColumnIndexOrThrow(CallLog.Calls._ID))
            }
            close()
        }
        return id
    }

    private fun fixUnknownNumber(id: Int, number: String) {
        try {
            contentResolver.update(
                CallLog.Calls.CONTENT_URI,
                ContentValues().apply {
                    put(CallLog.Calls.NUMBER, number)
                },
                "${CallLog.Calls._ID} = ?",
                arrayOf(id.toString()),
            )
        } catch (exc: SecurityException) {}
    }

    @Suppress("deprecation")
    private class Listener(
        private val service: WeakReference<me.lucky.fix.unknownnumber.NotificationListenerService>,
    ) : PhoneStateListener() {
        private var number: String? = null
        private var offhook = false
        private var task: Timer? = null

        @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            val svc = service.get() ?: return
            if (!svc.prefs.isEnabled) return
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    number = phoneNumber
                    offhook = true
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (!offhook) return
                    offhook = false
                    val num = number ?: return
                    if (num.isEmpty()) return
                    task?.cancel()
                    task = Timer()
                    task?.schedule(timerTask {
                        val id = svc.checkUnknownNumber()
                        if (id != null) svc.fixUnknownNumber(id, num)
                    }, svc.prefs.delay)
                    number = null
                }
                else -> {}
            }
        }
    }
}