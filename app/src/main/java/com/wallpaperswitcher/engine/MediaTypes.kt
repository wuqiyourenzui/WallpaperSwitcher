package com.wallpaperswitcher.engine

import android.content.Context
import android.net.Uri

/**
 * Single source of truth for "what kind of media is this?".
 *
 * The classification used to be duplicated in four places (the MediaStore
 * scanner, the ViewModel's add-media path, the static applier and the engine's
 * self-heal) and they drifted apart: one of them compared the resolved type
 * against itself, so every file passed the "supported" test, and some ignored
 * the provider's MIME type - which is what made SAF-imported videos show as a
 * black wallpaper.
 *
 * Kept free of Android types so every rule is unit-testable.
 */
object MediaTypes {

    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
    const val GIF = "GIF"

    /** Extensions the pickers/importers accept. */
    val supportedExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif",
        "mp4", "mkv", "webm", "avi", "mov", "3gp"
    )

    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp")

    private fun extensionOf(name: String): String =
        name.lowercase().substringAfterLast('.', "")

    /** True when [name]'s extension is a media type this app can show. */
    fun isSupportedName(name: String): Boolean = extensionOf(name) in supportedExtensions

    /** Media kind from a file name (defaults to [IMAGE] when unsure). */
    fun fromName(name: String): String = when (extensionOf(name)) {
        "gif" -> GIF
        in videoExtensions -> VIDEO
        else -> IMAGE
    }

    /**
     * True for media that only the live wallpaper engine can animate. The lock
     * screen is static, so motion media must never be written there.
     */
    fun isMotion(type: String): Boolean = type == VIDEO || type == GIF

    /**
     * Media kind from the provider's MIME type, falling back to the file name
     * when the provider reports nothing useful (SAF hands back extension-less
     * display names on non-Xiaomi devices).
     */
    fun fromMimeOrName(mime: String?, name: String): String {
        val lower = mime?.lowercase()
        return when {
            lower == null -> fromName(name)
            lower.startsWith("video/") -> VIDEO
            lower.startsWith("image/gif") -> GIF
            lower.startsWith("image/") -> IMAGE
            else -> fromName(name)
        }
    }

    /**
     * Repair value for a row whose stored type disagrees with the provider
     * MIME type, or null when nothing is wrong / the provider says nothing
     * useful.
     *
     * Used by the engine's self-heal: media imported by an older build was
     * stored as [IMAGE] while the file really is a video/GIF.
     */
    fun repairFromMime(storedType: String, mime: String?): String? {
        if (isMotion(storedType)) return null
        val lower = mime?.lowercase() ?: return null
        return when {
            lower.startsWith("video/") -> VIDEO
            lower.startsWith("image/gif") -> GIF
            else -> null
        }
    }

    /**
     * Final type of a stored row, honouring the provider MIME type when the
     * stored value is not already motion media.
     */
    fun resolveStored(storedType: String, mime: String?): String =
        if (isMotion(storedType)) storedType else repairFromMime(storedType, mime) ?: storedType

    /**
     * The provider's MIME type for [uri], or null when it cannot be resolved.
     * Single copy of the probe: the import path, the static applier and the
     * engine's self-heal all need it.
     */
    fun mimeOf(context: Context, uri: Uri): String? = try {
        context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
