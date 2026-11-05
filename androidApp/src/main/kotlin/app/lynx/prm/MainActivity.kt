package app.lynx.prm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.lynx.prm.ui.LynxApp
import app.lynx.prm.ui.LynxModel
import app.lynx.prm.ui.PhoneAccess
import app.lynx.prm.ui.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val app: LynxApplication get() = application as LynxApplication
    private val model: LynxModel get() = app.model

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
        model.acceptFolder(uri.toString())
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.phoneAccess?.contactsGranted = granted
        if (granted) importContacts()
    }

    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.phoneAccess?.notificationsGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.activity = this
        if (model.phoneAccess == null) {
            model.phoneAccess = PhoneAccess(
                contactsGranted = permissionGranted(Manifest.permission.READ_CONTACTS),
                notificationsGranted = notificationsGranted(),
                notificationAccessGranted = notificationAccessGranted(),
                requestContacts = { app.activity?.requestContacts() },
                requestNotifications = { app.activity?.requestNotifications() },
                openNotificationAccess = { app.activity?.openNotificationAccess() },
            )
        }
        app.startOnce()
        intent.getStringExtra(EXTRA_CONTACT)?.let { model.openFromNotification(it) }
        setContent {
            val reminders = model.reminders
            val place = model.place
            androidx.compose.runtime.LaunchedEffect(reminders, place) {
                if (place != Place.Setup) {
                    ReminderScheduler(this@MainActivity).sync(reminders) { model.contactName(it) }
                }
            }
            LynxApp(model)
        }
    }

    override fun onStart() {
        super.onStart()
        model.phoneAccess?.contactsGranted = permissionGranted(Manifest.permission.READ_CONTACTS)
        model.phoneAccess?.notificationsGranted = notificationsGranted()
        model.phoneAccess?.notificationAccessGranted = notificationAccessGranted()
        importContacts()
    }

    override fun onDestroy() {
        if (app.activity === this) app.activity = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_CONTACT)?.let { model.openFromNotification(it) }
    }

    fun pickFolder() {
        folderPicker.launch(null)
    }

    fun importContacts() {
        if (!permissionGranted(Manifest.permission.READ_CONTACTS)) return
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { readDeviceContacts(this@MainActivity) }
            model.importDeviceContacts(rows)
        }
    }

    private fun requestContacts() {
        contactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            model.phoneAccess?.notificationsGranted = true
        }
    }

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun notificationAccessGranted(): Boolean {
        return packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
    }

    private fun permissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return permissionGranted(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_CONTACT = "contactId"
    }
}
