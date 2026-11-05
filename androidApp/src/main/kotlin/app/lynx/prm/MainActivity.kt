package app.lynx.prm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.lynx.prm.library.AndroidPlatformFiles
import app.lynx.prm.ui.FolderPicker
import app.lynx.prm.ui.LynxApp
import app.lynx.prm.ui.LynxModel
import app.lynx.prm.ui.PhoneAccess
import app.lynx.prm.ui.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var model: LynxModel

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
        val preferences = AndroidPrefs(this)
        if (preferences.libraryFolder?.let { it.startsWith("content:") && !folderStillGranted(it) } == true) {
            preferences.libraryFolder = null
        }
        model = LynxModel(
            prefs = preferences,
            pickFolder = FolderPicker {
                folderPicker.launch(null)
                null
            },
            files = AndroidPlatformFiles(this),
        )
        model.dialNumber = { raw ->
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", raw, null)))
        }
        model.phoneAccess = PhoneAccess(
            contactsGranted = permissionGranted(Manifest.permission.READ_CONTACTS),
            notificationsGranted = notificationsGranted(),
            requestContacts = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    model.phoneAccess?.notificationsGranted = true
                }
            },
        )
        model.start()
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
        if (model.phoneAccess?.contactsGranted == true) importContacts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_CONTACT)?.let { model.openFromNotification(it) }
    }

    private fun importContacts() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { readDeviceContacts(this@MainActivity) }
            model.importDeviceContacts(rows)
        }
    }

    private fun permissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return permissionGranted(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun folderStillGranted(folder: String): Boolean {
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri.toString() == folder && permission.isReadPermission && permission.isWritePermission
        }
    }

    companion object {
        const val EXTRA_CONTACT = "contactId"
    }
}
