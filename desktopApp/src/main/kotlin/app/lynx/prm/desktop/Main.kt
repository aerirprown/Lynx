package app.lynx.prm.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.lynx.prm.match.sanitizePrefix
import app.lynx.prm.ui.FolderPicker
import app.lynx.prm.ui.InstallPrefs
import app.lynx.prm.ui.LynxApp
import app.lynx.prm.ui.LynxModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.Properties
import java.util.UUID
import javax.swing.JFileChooser

fun main() = application {
    val model = LynxModel(DesktopPrefs(), FolderPicker { chooseFolder() })
    model.start()
    Window(
        onCloseRequest = {
            model.shutdown()
            exitApplication()
        },
        state = rememberWindowState(width = 1100.dp, height = 760.dp),
        title = "Lynx",
    ) {
        window.background = java.awt.Color(0x16, 0x19, 0x1D)
        LynxApp(model)
    }
}

private fun chooseFolder(): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose the Lynx folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else {
        null
    }
}

private class DesktopPrefs : InstallPrefs {
    private val file: Path = configDir().resolve("library.properties")
    private val properties = Properties()

    init {
        Files.createDirectories(file.parent)
        if (Files.exists(file)) {
            Files.newInputStream(file).use { properties.load(it) }
        }
        if (properties.getProperty("device_id").isNullOrBlank()) {
            properties.setProperty("device_id", UUID.randomUUID().toString())
            save()
        }
    }

    override val deviceId: String get() = properties.getProperty("device_id")
    override val deviceName: String
        get() = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("desktop")

    override var libraryFolder: String?
        get() = properties.getProperty("library_folder")?.takeIf { it.isNotBlank() }
        set(value) = put("library_folder", value)

    override var prefix: String
        get() = properties.getProperty("prefix")?.takeIf { it.isNotBlank() } ?: sanitizePrefix(deviceName)
        set(value) = put("prefix", sanitizePrefix(value))

    override var libraryId: String?
        get() = properties.getProperty("library_id")?.takeIf { it.isNotBlank() }
        set(value) = put("library_id", value)

    override var phoneRegion: String
        get() = properties.getProperty("phone_region")?.takeIf { it.isNotBlank() } ?: defaultRegion()
        set(value) = put("phone_region", value.ifBlank { defaultRegion() })

    private fun put(key: String, value: String?) {
        if (value.isNullOrBlank()) properties.remove(key) else properties.setProperty(key, value)
        save()
    }

    private fun save() {
        Files.newOutputStream(file).use { properties.store(it, "Lynx") }
    }
}

private fun configDir(): Path {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    return if (os.contains("win")) {
        Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Lynx")
    } else {
        val xdg = System.getenv("XDG_CONFIG_HOME")
        if (!xdg.isNullOrBlank()) Path.of(xdg, "lynx") else Path.of(System.getProperty("user.home"), ".config", "lynx")
    }
}

private fun defaultRegion(): String {
    val country = Locale.getDefault().country
    return if (country.length == 2) country else "US"
}
