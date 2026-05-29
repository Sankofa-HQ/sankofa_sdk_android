package dev.sankofa.sdk

/**
 * Single source of truth for the SDK version.
 *
 * Keep [SDK_VERSION] in sync with the `version` in `sankofa/build.gradle.kts`.
 * Previously the version was hardcoded in four places with two different values
 * ("android-0.1.0" on events/Catch vs "1.0.0" in device context / integration
 * reports), so the backend saw the same install as two different SDK versions.
 */
internal object SankofaVersion {

    /** Bare semantic version, matching the published Maven artifact. */
    const val SDK_VERSION = "1.0.0"

    /** Platform-prefixed form used as the event `lib_version` and Catch SDK info. */
    const val LIB_VERSION = "android-$SDK_VERSION"
}
