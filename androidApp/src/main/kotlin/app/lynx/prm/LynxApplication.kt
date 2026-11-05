package app.lynx.prm

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.lynx.prm.library.AndroidPlatformFiles
import app.lynx.prm.ui.FolderPicker
import app.lynx.prm.ui.LynxModel

/**
 * Holds the one LynxModel for the process, so the open library outlives any activity,
 * and publishes when the whole app goes to the background.
 */
class LynxApplication : Application(), DefaultLifecycleObserver {
    lateinit var model: LynxModel
        private set
    var activity: MainActivity? = null
    private var started = false
    private var inBackground = false

    override fun onCreate() {
        super<Application>.onCreate()
        val preferences = AndroidPrefs(this)
        val folder = preferences.libraryFolder
        if (folder != null && folder.startsWith("content:") && !folderStillGranted(folder)) {
            preferences.libraryFolder = null
        }
        model = LynxModel(
            prefs = preferences,
            pickFolder = FolderPicker {
                activity?.pickFolder()
                null
            },
            files = AndroidPlatformFiles(this),
        )
        model.dialNumber = { raw ->
            activity?.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", raw, null)))
        }
        model.onLibraryOpened = { activity?.importContacts() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun startOnce() {
        if (started) return
        started = true
        model.start()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (inBackground) {
            inBackground = false
            model.publish()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        inBackground = true
        model.publish()
    }

    private fun folderStillGranted(folder: String): Boolean {
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri.toString() == folder && permission.isReadPermission && permission.isWritePermission
        }
    }
}
