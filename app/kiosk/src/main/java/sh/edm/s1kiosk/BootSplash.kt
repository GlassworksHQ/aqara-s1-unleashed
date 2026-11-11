package sh.edm.s1kiosk

/**
 * Rules for [BootSplashActivity], our HOME while the user is still locked after a boot. Pure, for tests.
 *
 * How Android 10 picks HOME (AOSP android10-release, checked in the sources):
 *  - RootActivityContainer.resolveHomeActivity resolves the HOME intent with PackageManagerService
 *    .resolveIntent. While the user is locked, PackageManagerService.updateFlags adds only
 *    MATCH_DIRECT_BOOT_AWARE, so our MainActivity (not direct-boot aware) isn't a candidate and
 *    Aqara's FallbackHome (com.lumi.systemmanager, direct-boot aware, priority -1000) was picked.
 *  - chooseBestActivity takes the first candidate outright when the top two differ in priority; only
 *    on a tie does it look at the preferred HOME (`cmd package set-home-activity`), and with none it
 *    shows the chooser.
 *  - ComponentResolver.adjustPriority caps a non-system app's priority at 0 but keeps negative ones.
 *  - When the set of HOME candidates gains a component, findPreferredActivityNotLocked drops a
 *    preferred HOME ("Result set changed") to a "last chosen" one that a tie no longer uses; and
 *    PackageManagerService.updateDefaultHomeNotLocked runs that check after every package change.
 *
 * So the splash has priority [PRIORITY] = -100: above FallbackHome while locked, below MainActivity (0)
 * once unlocked, so it never ties with MainActivity and never needs the preferred HOME. It ships
 * disabled (manifest) and the app enables it only while no other app has a HOME at that priority or
 * above (Aqara's launcher or factory app left enabled, i.e. not de-bloated, are priority 0 and tie with
 * MainActivity: there the preferred HOME decides, and a new component would make Android drop it).
 */
object BootSplash {
    /** The splash's HOME intent-filter priority; must match AndroidManifest.xml. */
    const val PRIORITY = -100

    /** A HOME candidate as PackageManager lists it (enabled components only). */
    data class Home(val pkg: String, val cls: String, val priority: Int)

    enum class Step {
        /** Still locked (or Android can't say what HOME is): keep showing the splash. */
        WAIT,
        /** Unlocked and our MainActivity is HOME: start it, then finish. */
        OPEN_KIOSK,
        /** Unlocked and another HOME (or the chooser) wins, or only the splash itself: disable the splash, finish. */
        LEAVE,
    }

    fun step(unlocked: Boolean, resolvedHome: Home?, pkg: String, mainCls: String): Step = when {
        !unlocked -> Step.WAIT
        resolvedHome == null -> Step.WAIT
        resolvedHome.pkg == pkg && resolvedHome.cls == mainCls -> Step.OPEN_KIOSK
        // the splash itself (MainActivity gone?) or someone else's HOME: never stay HOME after unlock
        else -> Step.LEAVE
    }

    /**
     * The splash may be enabled: MainActivity is a HOME candidate and no other app's HOME has a priority
     * of [PRIORITY] or more (it would tie with MainActivity or the splash).
     */
    fun safeToEnable(homes: List<Home>, pkg: String, mainCls: String): Boolean =
        homes.any { it.pkg == pkg && it.cls == mainCls } &&
            homes.none { it.pkg != pkg && it.priority >= PRIORITY }
}
