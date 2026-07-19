package org.flossware.hotspot.compat

/**
 * Documents an OEM-specific workaround in the codebase.
 *
 * Use this annotation on classes, functions, or properties that exist solely
 * to work around device-specific behaviour from a particular manufacturer.
 *
 * Example:
 * ```
 * @OemWorkaround(
 *     oem = "samsung",
 *     description = "One UI overrides Wi-Fi Direct group name prefix",
 *     apiLevels = [33, 34],
 * )
 * private fun acceptAnyDirectPrefix(name: String): Boolean { ... }
 * ```
 *
 * @property oem Manufacturer name (lowercase), e.g. "samsung", "xiaomi", "oneplus".
 * @property description Human-readable explanation of what the workaround does and why.
 * @property apiLevels API levels where the workaround is known to be needed. Empty means all levels.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class OemWorkaround(
    val oem: String,
    val description: String,
    val apiLevels: IntArray = [],
)
