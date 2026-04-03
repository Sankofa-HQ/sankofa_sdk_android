package dev.sankofa.sdk

/**
 * Add this annotation to your `Activity` or `Fragment` classes to automatically
 * set the Sankofa current screen name when they resume.
 *
 * Example:
 * ```kotlin
 * @SankofaScreen("Main Dashboard")
 * class MainActivity : AppCompatActivity() { ... }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SankofaScreen(val name: String)
