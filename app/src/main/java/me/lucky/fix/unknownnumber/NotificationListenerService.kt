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
    companion object {
        private val REMAKE_IGNORE_FIELDS = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_LOOKUP_URI,
            CallLog.Calls.CACHED_NORMALIZED_NUMBER,
            CallLog.Calls.CACHED_FORMATTED_NUMBER,
            CallLog.Calls.CACHED_MATCHED_NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_NUMBER_LABEL,
            CallLog.Calls.CACHED_NUMBER_TYPE,
            CallLog.Calls.CACHED_PHOTO_ID,
            CallLog.Calls.CACHED_PHOTO_URI,
        )
    }

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
        val cursor: Cursor?
        try {
            cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1")
                    .build(),
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
                CallLog.Calls.DEFAULT_SORT_ORDER,
            )
        } catch (exc: SecurityException) { return null }
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
        assert(number.isNotEmpty())
        if (prefs.isRemakeCallEntry) remakeCallEntry(id, number)
        else updateCallNumberById(id, number)
    }

    private fun remakeCallEntry(id: Int, number: String) {
        val cursor: Cursor?
        try {
            cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                "${CallLog.Calls._ID} = ?",
                arrayOf(id.toString()),
                null,
            )
        } catch (exc: SecurityException) { return }
        val cv = ContentValues()
        var ok = false
        cursor?.apply {
            if (moveToFirst()) {
                ok = true
                for (name in columnNames
                    .filterNot { REMAKE_IGNORE_FIELDS.contains(it) || it.startsWith('_') })
                {
                    val value = cursor.getStringOrNull(getColumnIndexOrThrow(name))
                    if (value == null) cv.putNull(name) else cv.put(name, value)
                }
            }
            close()
        }
        if (!ok || !deleteCallById(id)) return
        cv.put(CallLog.Calls.NUMBER, number)
        try { contentResolver.insert(CallLog.Calls.CONTENT_URI, cv) }
        catch (exc: SecurityException) {}
    }

    private fun deleteCallById(id: Int) =
        try {
            contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} = ?",
                arrayOf(id.toString()),
            ) > 0
        } catch (exc: SecurityException) { false }

    private fun updateCallNumberById(id: Int, number: String) =
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