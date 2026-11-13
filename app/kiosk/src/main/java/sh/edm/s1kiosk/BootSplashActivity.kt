package sh.edm.s1kiosk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.setup.Backdrop
import sh.edm.s1kiosk.setup.LoadingOverlay

/**
 * Our look from the boot animation to the kiosk. Until the user is unlocked after a boot (no PIN, so
 * Android does it by itself a few seconds in), credential-encrypted storage isn't available and Android
 * only considers direct-boot-aware HOME activities: MainActivity isn't one, so Aqara's FallbackHome
 * (which looks like Aqara's launcher) used to show for ~4 s. This direct-boot-aware HOME shows the
 * loading screen instead (dithered backdrop, "Starting up"), and once the user is unlocked starts the
 * real HOME, MainActivity, and finishes. Rules and the AOSP details: [BootSplash].
 *
 * Runs in its own process (":boot") and touches nothing but the APK's code and resources: no prefs,
 * no files, no GeckoView. After unlock it never stays: it forwards in onCreate/onResume without drawing.
 */
class BootSplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var done = false

    private val tick = object : Runnable {
        override fun run() {
            advance()
            if (!done) handler.postDelayed(this, RETRY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (unlocked()) {
            advance() // after unlock this is never a HOME to stay on
            if (done) return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFormat(PixelFormat.RGBA_8888) // 8 bits per channel for the dithered backdrop
        val real = Point().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(it) }
        Backdrop.prewarm(maxOf(real.x, real.y), minOf(real.x, real.y))
        setContentView(LoadingOverlay(this).apply { show(LoadingOverlay.State.Connecting("Starting up", "", host = false)) })
        hideSystemUi()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { advance() }
        }.also {
            val f = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            Platform.registerExportedReceiver(this, it, f)
        }
        Log.i(TAG, "boot splash shown (user locked)")
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        advance()
        handler.removeCallbacks(tick)
        if (!done) handler.postDelayed(tick, RETRY_MS) // also catches a missed broadcast, retries a failed start
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit // HOME: nothing to go back to

    private fun unlocked(): Boolean =
        runCatching { getSystemService(UserManager::class.java).isUserUnlocked }.getOrDefault(true)

    private fun resolvedHome(): BootSplash.Home? = runCatching {
        val ri = packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY) ?: return@runCatching null
        BootSplash.Home(ri.activityInfo.packageName, ri.activityInfo.name, ri.priority)
    }.getOrNull()

    private fun advance() {
        if (done) return
        when (BootSplash.step(unlocked(), resolvedHome(), packageName, MainActivity::class.java.name)) {
            BootSplash.Step.WAIT -> Unit
            BootSplash.Step.OPEN_KIOSK -> try {
                // Implicit HOME intent limited to our package: Android 10 only gives an explicitly named
                // activity the HOME type when the system starts it (ActivityRecord.setActivityType), and
                // MainActivity must stay HOME (home stack, the HOME button). Our two HOME activities
                // differ in priority, so this resolves to MainActivity without asking anyone.
                startActivity(homeIntent().setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                done = true
                Log.i(TAG, "user unlocked: starting the kiosk")
                finish()
                @Suppress("DEPRECATION") overridePendingTransition(0, 0) // same backdrop: no visible switch
            } catch (e: Exception) {
                Log.w(TAG, "could not start the kiosk yet; retrying in ${RETRY_MS} ms", e) // keeps the splash up
            }
            BootSplash.Step.LEAVE -> {
                done = true
                Log.i(TAG, "user unlocked and the kiosk isn't HOME: splash off, leaving HOME to Android")
                setEnabled(this, false)
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    companion object {
        private const val TAG = "S1Kiosk.Boot"
        private const val RETRY_MS = 1000L

        private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

        /**
         * MainActivity (unlocked, every start): the splash on only while that's safe
         * ([BootSplash.safeToEnable]), off as soon as another app's HOME could tie with ours.
         */
        fun sync(context: Context) {
            runCatching {
                val homes = context.packageManager.queryIntentActivities(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
                    .map { BootSplash.Home(it.activityInfo.packageName, it.activityInfo.name, it.priority) }
                setEnabled(context, BootSplash.safeToEnable(homes, context.packageName, MainActivity::class.java.name))
            }.onFailure { Log.w(TAG, "boot splash: could not check HOME candidates", it) }
        }

        private fun setEnabled(context: Context, on: Boolean) {
            val pm = context.packageManager
            val cn = ComponentName(context, BootSplashActivity::class.java)
            val now = pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED // default = off
            if (now == on) return
            runCatching {
                pm.setComponentEnabledSetting(
                    cn,
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
                Log.i(TAG, "boot splash ${if (on) "enabled" else "disabled"}")
            }.onFailure { Log.w(TAG, "boot splash: could not ${if (on) "enable" else "disable"} it", it) }
        }
    }
}
