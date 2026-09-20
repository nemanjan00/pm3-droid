package io.github.nemanjan00.pm3.ui

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One entry from the firmware build matrix. */
data class FirmwareVariant(
    val id: String,
    val platform: String,
    val extras: String,
    val description: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Local image, or null when only the manifest entry is known. */
    val image: File?,
) {
    val isAvailable: Boolean get() = image?.isFile == true
}

/**
 * Firmware images available to flash.
 *
 * Images are not bundled in the APK. Ten variants of fullimage plus bootroms
 * is several megabytes that almost every user never touches, and firmware
 * moves on a different cadence to the app -- a user should be able to flash a
 * newer build without waiting for an app release.
 *
 * Instead the app reads a manifest.json produced by firmware/build-matrix.sh
 * (published on each release) and pairs it with whatever images have been
 * downloaded into the firmware cache. Entries with no local image are still
 * listed, so the UI can show what exists and offer to fetch it.
 */
object FirmwareCatalog {

    /** Where downloaded images live: <files>/firmware/<id>/fullimage.elf */
    fun cacheDir(context: Context): File = File(context.filesDir, "firmware")

    fun manifestFile(context: Context): File = File(cacheDir(context), "manifest.json")

    /**
     * Variants known locally.
     *
     * Returns an empty list when no manifest has been fetched yet; the Flash
     * screen renders that as an explicit "no firmware downloaded" state rather
     * than an empty list with no explanation.
     */
    fun load(context: Context): List<FirmwareVariant> {
        val manifest = manifestFile(context)
        if (!manifest.isFile) return emptyList()

        val root = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return emptyList()
        val variants = root.optJSONArray("variants") ?: return emptyList()

        return (0 until variants.length()).mapNotNull { i ->
            val o = variants.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            val image = File(cacheDir(context), "$id/fullimage.elf")
            FirmwareVariant(
                id = id,
                platform = o.optString("platform"),
                extras = o.optString("extras"),
                description = o.optString("description"),
                sizeBytes = o.optLong("size"),
                sha256 = o.optString("sha256"),
                image = if (image.isFile) image else null,
            )
        }
    }

    /**
     * Verifies a downloaded image against the manifest's digest.
     *
     * Worth doing: a truncated download that still parses as an ELF would be
     * written to the device and brick it.
     */
    fun verify(variant: FirmwareVariant): Boolean {
        val file = variant.image ?: return false
        if (variant.sha256.isEmpty()) return true
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
            .equals(variant.sha256, ignoreCase = true)
    }
}
