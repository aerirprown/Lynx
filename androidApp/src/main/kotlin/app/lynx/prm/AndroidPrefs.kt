package app.lynx.prm

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.lynx.prm.match.sanitizePrefix
import app.lynx.prm.ui.InstallPrefs
import java.io.File
import java.util.Locale
import java.util.UUID

class AndroidPrefs(context: Context) : InstallPrefs {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("lynx", Context.MODE_PRIVATE)

    override val deviceId: String
        get() {
            val existing = preferences.getString(KEY_DEVICE, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            preferences.edit().putString(KEY_DEVICE, created).apply()
            return created
        }

    override val deviceName: String
        get() {
            val named = try {
                Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            } catch (_: SecurityException) {
                null
            }
            if (!named.isNullOrBlank()) return named
            return Build.MODEL?.takeIf { it.isNotBlank() } ?: "android"
        }

    override val workFolder: String
        get() = File(appContext.filesDir, "library").also { it.mkdirs() }.absolutePath

    override var libraryFolder: String?
        get() = preferences.getString(KEY_FOLDER, null)?.takeIf { it.isNotBlank() }
        set(value) = put(KEY_FOLDER, value)

    override var prefix: String
        get() = preferences.getString(KEY_PREFIX, null)?.takeIf { it.isNotBlank() } ?: sanitizePrefix(deviceName)
        set(value) = put(KEY_PREFIX, sanitizePrefix(value))

    override var libraryId: String?
        get() = preferences.getString(KEY_LIBRARY, null)?.takeIf { it.isNotBlank() }
        set(value) = put(KEY_LIBRARY, value)

    override var phoneRegion: String
        get() = preferences.getString(KEY_REGION, null)?.takeIf { it.isNotBlank() } ?: defaultRegion()
        set(value) = put(KEY_REGION, value.ifBlank { defaultRegion() })

    override var importRecord: String?
        get() = recordFile.takeIf { it.exists() }?.readText()
        set(value) {
            if (value == null) recordFile.delete() else recordFile.writeText(value)
        }

    private val recordFile: File get() = File(appContext.filesDir, "import_record.txt")

    private fun put(key: String, value: String?) {
        val editor = preferences.edit()
        if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    private fun defaultRegion(): String {
        val country = Locale.getDefault().country
        return if (country.length == 2) country else "US"
    }

    private companion object {
        const val KEY_DEVICE = "device_id"
        const val KEY_FOLDER = "library_folder"
        const val KEY_PREFIX = "prefix"
        const val KEY_LIBRARY = "library_id"
        const val KEY_REGION = "phone_region"
    }
}
