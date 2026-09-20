package io.github.nemanjan00.pm3.ui

/** One entry from the firmware build matrix. */
data class FirmwareVariant(
    val id: String,
    val platform: String,
    val extras: String,
    val description: String,
    val path: String,
)

/**
 * Firmware images available to flash.
 *
 * Images are not bundled in the APK: ten variants of ~450 KB plus bootroms
 * would add ~5 MB that almost every user never touches, and firmware moves on
 * a different cadence to the app. They are downloaded from the release the
 * matrix build publishes, and cached in app storage.
 */
object FirmwareCatalog {
    // TODO: populate from the downloaded manifest.json produced by
    // firmware/build-matrix.sh. Until the download path lands this returns the
    // matrix contents so the UI can be exercised.
    fun bundled(): List<FirmwareVariant> = emptyList()
}
