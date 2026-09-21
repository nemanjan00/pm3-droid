package io.github.nemanjan00.pm3.client

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Locates the bundled client and lays out the private filesystem it expects.
 *
 * Two Android constraints shape this:
 *
 *  - Since API 29 an app may only exec() binaries from its nativeLibraryDir,
 *    so the client ships as jniLibs/<abi>/libproxmark3.so. It is a normal
 *    PIE executable that the packager never loads as a library.
 *
 *  - That directory is read-only, and the client resolves dictionaries and
 *    scripts relative to either its own location or $HOME/.proxmark3 (see
 *    PM3_USER_DIRECTORY in the pm3 tree's include/common.h). Only the second
 *    is writable here, so we unpack the resources there and set HOME.
 */
class Pm3Runtime(private val context: Context) {

    /** The client executable inside the APK's native library directory. */
    val binary: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    /** Private HOME for the client; the client appends /.proxmark3 itself. */
    val home: File
        get() = File(context.filesDir, "pm3home")

    /** Where the unpacked dictionaries/scripts/resources live. */
    val pm3Dir: File
        get() = File(home, ".proxmark3")

    val isInstalled: Boolean
        get() = binary.canExecute() && File(pm3Dir, STAMP_NAME).exists()

    /**
     * Unpacks resources into [pm3Dir] if they are missing or stale.
     *
     * Staleness is keyed on the APK's version, so an app update refreshes the
     * dictionaries without the user clearing data.
     */
    @Throws(IOException::class)
    fun install(force: Boolean = false) {
        val stamp = File(pm3Dir, STAMP_NAME)
        val version = appVersion()
        if (!force && stamp.exists() && stamp.readText() == version) return

        pm3Dir.mkdirs()
        unzipAsset(RESOURCES_ASSET, pm3Dir)

        // The RPC shim lives with the other Lua scripts so `script run pm3_rpc`
        // finds it by name.
        File(pm3Dir, "luascripts").mkdirs()
        context.assets.open(RPC_ASSET).use { input ->
            File(pm3Dir, "luascripts/$RPC_ASSET").outputStream().use(input::copyTo)
        }

        stamp.writeText(version)
    }

    private fun appVersion(): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName}/${info.longVersionCode}"
    } catch (_: Exception) {
        "unknown"
    }

    @Throws(IOException::class)
    private fun unzipAsset(assetName: String, destination: File) {
        val root = destination.canonicalFile
        context.assets.open(assetName).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(root, entry.name).canonicalFile
                    // Reject entries that escape the destination (zip slip).
                    // The archive is ours, but a path check is cheap and this
                    // unpacks with the app's own permissions.
                    if (!target.path.startsWith(root.path + File.separator)) {
                        throw IOException("Refusing entry outside destination: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use(zip::copyTo)
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Environment for a client process: HOME plus a sane terminal. */
    fun environment(): Map<String, String> = mapOf(
        "HOME" to home.absolutePath,
        // The client enables ANSI colour only when stdin *and* stdout are
        // both TTYs (proxmark3.c), and we always spawn it on pipes -- so its
        // output is plain ASCII regardless of this, which is what lets
        // TagParser scrape it. TERM is set only to keep the client off its
        // "dumb terminal" fallbacks.
        "TERM" to "xterm-256color",
        // Used for progress-bar width; the console re-sends this on resize.
        "COLUMNS" to "80",
        "LANG" to "C.UTF-8",
    )

    companion object {
        /**
         * The packager only extracts files matching lib*.so, which is why the
         * client executable is named this way rather than "proxmark3".
         */
        private const val BINARY_NAME = "libproxmark3.so"

        private const val RESOURCES_ASSET = "pm3-resources.zip"
        private const val RPC_ASSET = "pm3_rpc.lua"
        private const val STAMP_NAME = ".installed"
    }
}
