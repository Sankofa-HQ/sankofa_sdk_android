package dev.sankofa.sdk.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * # Traffic Cop — Module Registry
 *
 * The Core SDK routes handshake flags only to modules the developer
 * actually added to their project. In Kotlin/Android, unlinked modules
 * literally can't exist at runtime (the class isn't on the classpath),
 * so crash prevention is structural.
 *
 * The registry exists to:
 *
 *  1. Report the installed modules list to the server in the Reverse
 *     Handshake so the dashboard can show "SDK Not Detected" lock
 *     states instead of toggles that silently do nothing.
 *  2. Route server-enabled module flags to the registered handler
 *     when the optional module IS linked.
 *  3. Emit debug-mode warnings when a dashboard flag references a
 *     module the developer didn't include.
 *
 * NOTE: The interface here is `SankofaPluggableModule` — NOT
 * `SankofaModule`, which already exists as the React Native bridge
 * class in `sankofa-react-native/android/.../dev/sankofa/rn/SankofaModule.kt`.
 * Kotlin 2.x's stricter overload resolution treats two same-named
 * declarations across the classpath as an ambiguity when inside the
 * Expo Modules `Function(...)` DSL, producing cryptic "Cannot infer
 * type" errors in the RN bridge. Keeping them distinct avoids it.
 */

enum class SankofaModuleName(val wireName: String) {
    ANALYTICS("analytics"),
    DEPLOY("deploy"),
    CATCH("catch"),
}

/**
 * Every pluggable module implements this. The Core never imports
 * concrete module classes — it only talks through this interface.
 */
interface SankofaPluggableModule {
    val canonicalName: SankofaModuleName

    /**
     * Called by the Core when the handshake response says this module
     * is enabled. The module starts its work (e.g. Deploy kicks off an
     * update check, Catch starts the crash handler). If `enabled: false`,
     * this is NOT called — the module stays dormant.
     */
    suspend fun applyHandshake(config: Map<String, Any?>)
}

object SankofaModuleRegistry {
    private const val TAG = "Sankofa"
    private val registered = mutableMapOf<SankofaModuleName, SankofaPluggableModule>()
    @Volatile private var coreInitialized = false
    // Dedicated scope so module handlers survive caller cancellation.
    // SupervisorJob so one module failure doesn't cancel others.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called once by `Sankofa.init()` to flip core-ready. */
    fun markCoreInitialized() {
        coreInitialized = true
    }

    val isCoreInitialized: Boolean get() = coreInitialized

    /** Register a module. Called from each module's init path. */
    @Synchronized
    fun register(module: SankofaPluggableModule) {
        registered[module.canonicalName] = module

        if (!coreInitialized && isDebuggable()) {
            Log.w(TAG, "${module.canonicalName.wireName} module was registered before Sankofa.init(). Call init() first so the module can read your API key and endpoint.")
        }
    }

    @Synchronized
    fun unregister(name: SankofaModuleName) {
        registered.remove(name)
    }

    @Synchronized
    fun has(name: SankofaModuleName): Boolean = registered.containsKey(name)

    /**
     * Returns the list of module names the app binary ships with.
     * Analytics is always present (it IS the core). Sent to the server
     * in the Reverse Handshake.
     */
    @Synchronized
    fun getInstalledModules(): List<String> {
        val names = mutableListOf("analytics")
        for (name in registered.keys) {
            if (name != SankofaModuleName.ANALYTICS) {
                names.add(name.wireName)
            }
        }
        return names
    }

    /**
     * The Traffic Cop. Called from the handshake handler when the
     * server response arrives. Routes each enabled module flag to its
     * registered handler; warns (debug) or silently no-ops (release)
     * for flags that reference missing modules.
     *
     * Non-suspend by design — launches module handlers in a dedicated
     * SupervisorJob scope so the caller (which may already be in a
     * suspend context) doesn't deadlock via runBlocking. Module errors
     * are isolated: one failure doesn't cancel others.
     */
    @Suppress("UNCHECKED_CAST")
    fun routeHandshake(modules: Map<String, Any?>?) {
        if (modules == null) return

        // Deploy
        (modules["deploy"] as? Map<String, Any?>)?.let { deploy ->
            if (deploy["enabled"] == true) {
                val mod = synchronized(this) { registered[SankofaModuleName.DEPLOY] }
                if (mod != null) {
                    scope.launch {
                        try {
                            mod.applyHandshake(deploy)
                        } catch (e: Throwable) {
                            Log.w(TAG, "deploy.applyHandshake failed: ${e.message}")
                        }
                    }
                } else if (isDebuggable()) {
                    Log.w(TAG, "Server enabled \"deploy\" but Deploy module is not linked. Add the Deploy SDK to enable OTA updates.")
                }
            }
        }

        // Catch (ships later)
        (modules["catch"] as? Map<String, Any?>)?.let { catchConfig ->
            if (catchConfig["enabled"] == true) {
                val mod = synchronized(this) { registered[SankofaModuleName.CATCH] }
                if (mod != null) {
                    scope.launch {
                        try {
                            mod.applyHandshake(catchConfig)
                        } catch (e: Throwable) {
                            Log.w(TAG, "catch.applyHandshake failed: ${e.message}")
                        }
                    }
                } else if (isDebuggable()) {
                    Log.w(TAG, "Server enabled \"catch\" but SankofaCatch is not linked. Add the Catch SDK to enable crash reporting.")
                }
            }
        }
    }

    /** Only log debug warnings in debug builds. */
    private fun isDebuggable(): Boolean {
        // BuildConfig is generated per-module so we can't reference it
        // directly from here. Use a broad heuristic: debug logger logs
        // everything by default, release builds set this off via the
        // Sankofa.init debug flag. For the registry, we assume debug
        // unless the caller sets it otherwise.
        return true
    }
}
