package io.github.nemanjan00.pm3.flash

import android.content.Context
import io.github.nemanjan00.pm3.ui.FirmwareCatalog
import io.github.nemanjan00.pm3.ui.FirmwareVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches firmware built by the matrix CI job.
 *
 * Images live in releases rather than in the APK: ten fullimages plus bootroms
 * is several megabytes that most users never touch, and firmware should be
 * updatable without shipping a new app version.
 *
 * Downloads are verified against the manifest's sha256 before they are made
 * visible. A truncated image still parses as an ELF, and writing one to a
 * Proxmark is exactly the failure this app should not cause.
 */
class FirmwareRepository(
    private val context: Context,
    private val releaseBase: String = DEFAULT_RELEASE_BASE,
) {

    sealed interface Progress {
        data class Status(val message: String) : Progress
        /** [fraction] is null while the server sends no Content-Length. */
        data class Downloading(val name: String, val fraction: Float?) : Progress
        data class Done(val variants: List<FirmwareVariant>) : Progress
        data class Failed(val message: String) : Progress
    }

    /**
     * Downloads the manifest, then every image it lists that is missing or
     * fails verification locally.
     *
     * channelFlow rather than flow: the per-chunk progress callback is not a
     * suspending context, so it needs trySend. (And not callbackFlow, which
     * requires an awaitClose on every path -- the early returns below would
     * trip its "please call awaitClose" check.)
     *
     * Milestones use the suspending send() so they cannot be dropped;
     * per-chunk progress uses trySend, where dropping a frame on a full
     * rendezvous channel is harmless.
     */
    fun sync(): Flow<Progress> = channelFlow {
        val dir = FirmwareCatalog.cacheDir(context)
        dir.mkdirs()

        send(Progress.Status("Fetching manifest\u2026"))
        val manifestFile = FirmwareCatalog.manifestFile(context)
        try {
            // Staged via a temp file so an interrupted fetch cannot leave a
            // half-written manifest that parses to a shorter variant list.
            val tmp = File(dir, "manifest.json.part")
            download(URL("$releaseBase/manifest.json"), tmp) { }
            // Parse before committing: a 404 page saved as JSON is worse than
            // no manifest at all.
            JSONObject(tmp.readText()).optJSONArray("variants")
                ?: throw IOException("Manifest has no variants array")
            commit(tmp, manifestFile)
        } catch (e: Exception) {
            send(Progress.Failed("Could not fetch manifest: ${e.message}"))
            return@channelFlow
        }

        val wanted = FirmwareCatalog.load(context)
        if (wanted.isEmpty()) {
            send(Progress.Failed("Manifest lists no firmware variants"))
            return@channelFlow
        }

        for (variant in wanted) {
            val target = File(dir, "${variant.id}/fullimage.elf")
            if (target.isFile && FirmwareCatalog.sha256(target).equals(variant.sha256, ignoreCase = true)) {
                continue // already have a verified copy
            }
            target.parentFile?.mkdirs()

            send(Progress.Downloading(variant.id, 0f))
            val tmp = File(target.parentFile, "fullimage.elf.part")
            try {
                // Release assets are flattened with the variant in the name,
                // matching the workflow's release step.
                download(URL("$releaseBase/fullimage-${variant.id}.elf"), tmp) { fraction ->
                    trySend(Progress.Downloading(variant.id, fraction))
                }
            } catch (e: Exception) {
                tmp.delete()
                send(Progress.Failed("Could not download ${variant.id}: ${e.message}"))
                return@channelFlow
            }

            val digest = FirmwareCatalog.sha256(tmp)
            if (variant.sha256.isNotEmpty() && !digest.equals(variant.sha256, ignoreCase = true)) {
                // A truncated image still parses as an ELF; writing one to a
                // Proxmark is exactly the failure this app must not cause.
                tmp.delete()
                send(
                    Progress.Failed(
                        "${variant.id} failed verification. Expected " +
                            "${variant.sha256.take(16)}\u2026, got ${digest.take(16)}\u2026. " +
                            "Not installing it."
                    )
                )
                return@channelFlow
            }
            commit(tmp, target)
        }

        send(Progress.Done(FirmwareCatalog.load(context)))
    }.flowOn(Dispatchers.IO)

    /** Moves a staged download into place, falling back to a copy across filesystems. */
    private fun commit(staged: File, target: File) {
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
    }

    @Throws(IOException::class)
    private fun download(url: URL, destination: File, onProgress: (Float?) -> Unit) {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")

            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(if (total > 0) (read.toFloat() / total) else null)
                    }
                }
            }
            if (total > 0 && read != total) {
                throw IOException("Short read: got $read of $total bytes")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * The rolling nightly published by .github/workflows/build.yml.
         *
         * GitHub serves release assets from this path without an API call or a
         * token, which keeps the app free of credentials.
         */
        const val DEFAULT_RELEASE_BASE =
            "https://github.com/nemanjan00/pm3-droid/releases/download/nightly"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
