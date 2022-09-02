package me.lucky.fix.unknownnumber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.widget.doAfterTextChanged

import me.lucky.fix.unknownnumber.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    companion object {
        private val PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()
        setup()
    }

    private fun init() {
        prefs = Preferences(this)
        binding.apply {
            delay.editText?.setText(prefs.delay.toString())
            remake.isChecked = prefs.isRemakeCallEntry
            toggle.isChecked = prefs.isEnabled
        }
    }

    private fun setup() = binding.apply {
        delay.editText?.doAfterTextChanged {
            prefs.delay = it?.toString()?.toLongOrNull() ?: return@doAfterTextChanged
        }
        remake.setOnCheckedChangeListener { _, isChecked ->
            prefs.isRemakeCallEntry = isChecked
        }
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasPermissions()) {
                toggle.isChecked = false
                requestPermissions()
                return@setOnCheckedChangeListener
            }
            prefs.isEnabled = isChecked
        }
    }

    private fun hasBasePermissions() =
        !PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun requestBasePermissions() = registerForBasePermissions.launch(PERMISSIONS)

    private fun hasNotificationListener() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun requestNotificationListener() =
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    private fun hasPermissions() = hasBasePermissions() && hasNotificationListener()

    private fun requestPermissions() = when {
        !hasBasePermissions() -> requestBasePermissions()
        !hasNotificationListener() -> requestNotificationListener()
        else -> {}
    }

    private val registerForBasePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
}