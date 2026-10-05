package com.wallpaperswitcher.engine

/**
 * 清晰度增强 modes (SettingsKeys.CLARITY_MODE).
 *
 * The panel's third option used to be 增强 ("strong", a stronger unsharp mask).
 * Per user request it is now 画质增强（超分）("super"): the normal auto clarity
 * (the super-resolution shader does the real work with its capped,
 * contrast-adaptive sharpening) PLUS the upscaling path. A stored legacy
 * "strong" value normalizes to "super", so an install that had 增强 selected
 * upgrades straight into the new feature - which is exactly the requested
 * replacement, not a loss of behaviour.
 */
object ClarityMode {

    const val AUTO = "auto"
    const val OFF = "off"
    const val SUPER = "super"

    /** Stored value of the removed 增强 option. */
    private const val LEGACY_STRONG = "strong"

    /** Renderer sharpness scale: off = no unsharp, otherwise the auto curve. */
    private const val AUTO_SCALE = 1.25f

    fun normalize(stored: String?): String = when (stored) {
        OFF -> OFF
        SUPER, LEGACY_STRONG -> SUPER
        else -> AUTO
    }

    fun sharpnessScale(stored: String?): Float = when (normalize(stored)) {
        OFF -> 0f
        else -> AUTO_SCALE
    }

    /** True when the super-resolution path should be active. */
    fun boostsQuality(stored: String?): Boolean = normalize(stored) == SUPER
}
