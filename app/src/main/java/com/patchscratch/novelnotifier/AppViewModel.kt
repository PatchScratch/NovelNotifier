package com.patchscratch.novelnotifier

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * App-level state (Phase 4b): the navigation [Router], the active site
 * workspace, and the sync-engine status poll. Everything here used to be
 * `remember` blocks in NovelApp that died on every Activity recreation.
 *
 * The 2 s poll also carries two auto-recovery duties from the old
 * LaunchedEffect: resuming a queue whose service died mid-run (only in the
 * foreground — Android 12+ forbids background foreground-service starts)
 * and surfacing the session-expired login request.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    val router = Router()

    var activeSite by mutableStateOf(prefs.getString("active_site", "nu") ?: "nu")
        private set

    /** Which site's section the Settings screen shows (set on open). */
    var settingsSite by mutableStateOf("nu")

    var syncStatus by mutableStateOf(SyncEngineService.statusText(app))
        private set

    /** Mirrors the lifecycle observer — gates the queue auto-resume. */
    var appForeground by mutableStateOf(true)

    /**
     * NSFW mode (SFW/NSFW feature): in-memory only, false at every process
     * start, re-locked by the ON_STOP lifecycle observer. Never persisted —
     * "locked" must be the state every fresh launch wakes up in.
     */
    var nsfwUnlocked by mutableStateOf(false)
        private set

    /** True when the unlock attempt found no lockscreen credential at all. */
    var needsDeviceLock by mutableStateOf(false)
        private set

    /**
     * Runs the system prompt (fingerprint, or the lockscreen PIN/pattern as
     * fallback). When no biometric is enrolled but a device credential
     * exists, a credential-only prompt shows the PIN entry directly — a
     * combined-authenticators call instead errors out silently on some
     * builds. API < 30 can't combine the two at all.
     */
    fun unlockNsfw(activity: androidx.fragment.app.FragmentActivity) {
        val bm = androidx.biometric.BiometricManager.from(activity)
        val biometricOk = bm.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        val credentialOk = bm.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        if (!biometricOk && !credentialOk) {
            needsDeviceLock = true
            return
        }
        val authenticators = when {
            Build.VERSION.SDK_INT >= 30 && biometricOk ->
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            Build.VERSION.SDK_INT >= 30 ->
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            else -> {
                // Pre-30 offers no credential-only prompt; without biometrics
                // there is nothing to authenticate with.
                if (!biometricOk) {
                    needsDeviceLock = true
                    return
                }
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
            }
        }
        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(t("Reveal hidden content", "非表示コンテンツの表示"))
            .setSubtitle(t("Hidden lists and 18+ content stay locked otherwise", "非表示リストと18禁コンテンツはロックされたままです"))
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(authenticators)
            .build()
        val executor = ContextCompat.getMainExecutor(activity)
        androidx.biometric.BiometricPrompt(activity, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    DebugLog.log("NSFW", "unlock succeeded")
                    nsfwUnlocked = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancel is normal; anything else is worth a log line.
                    if (errorCode != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED) {
                        DebugLog.log("NSFW", "unlock error $errorCode: $errString")
                    }
                }
            }
        ).authenticate(promptInfo)
    }

    /** Switching back to SFW is free; ON_STOP re-locking also lands here. */
    fun lockNsfw() {
        nsfwUnlocked = false
    }

    fun dismissNeedsDeviceLock() {
        needsDeviceLock = false
    }

    init {
        // Phase 4a: centralised WebView/queue/pacing (progressive migration).
        SiteClient.initialize(app)
        applySavedCookies(prefs)
        viewModelScope.launch {
            while (true) {
                delay(2.seconds)
                val latest = SyncEngineService.statusText(app)
                if (latest != syncStatus) syncStatus = latest
                if (appForeground && latest.startsWith("Running") && !SyncEngineService.engineAlive) {
                    val pending = withContext(Dispatchers.IO) { SyncQueue.pending(app) }
                    if (pending.isNotEmpty()) SyncEngineService.resume(app)
                }
                // The session-expired notification asks for the login screen.
                // Unlike the flag era this surfaces immediately, even over
                // the reader — an expired session blocks every NU scrape.
                if (MainActivity.openLoginRequested) {
                    MainActivity.openLoginRequested = false
                    router.push(Route.NuLogin)
                }
            }
        }
    }

    fun switchSite(site: String) {
        activeSite = site
        prefs.edit { putString("active_site", site) }
    }

    /** Mirrors the lifecycle observer — gates the queue auto-resume. */
    fun setForeground(foreground: Boolean) {
        appForeground = foreground
    }

    /** Settings sections are per-site; remember which site opened it. */
    fun openSettings(site: String) {
        settingsSite = site
        router.push(Route.Settings)
    }

    fun stopCoverSync() {
        SyncEngineService.stop(getApplication())
        syncStatus = "Stopped"
    }

    fun onNotifyPermissionDenied() {
        syncStatus = "Notification permission needed for background sync"
    }

    /** Settings → Prefetch missing covers: cover-only queue on the sync engine. */
    fun startCoverSync(nu: NuViewModel) {
        viewModelScope.launch {
            val jobs = withContext(Dispatchers.IO) {
                SyncQueue.build(getApplication(), priorityListIds = emptySet(), coversOnly = true)
            }
            if (jobs.isEmpty()) {
                syncStatus = "All cached series already have covers"
            } else {
                SyncEngineService.startWith(getApplication(), jobs)
                syncStatus = SyncEngineService.statusText(getApplication())
                if (syncStatus != "Idle") nu.statusMessage = syncStatus
            }
        }
    }

    /** "Check now" from Settings: runs the check, then re-fetches the feed. */
    fun checkNow(nu: NuViewModel) {
        viewModelScope.launch {
            nu.statusMessage = "Checking feed..."
            nu.statusMessage = runUpdateCheck(getApplication())
            nu.refresh()
        }
    }
}
