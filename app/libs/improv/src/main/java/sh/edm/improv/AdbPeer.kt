package sh.edm.improv

/**
 * Who may talk to an [AdbSocketServer]: only adb. A client opens it with adb's
 * `localabstract:<name>` service, so the peer is adbd as the shell user (uid 2000), or root (uid 0)
 * after `adb root`. Any other app on the device is refused. Pure (AdbPeerTest).
 */
object AdbPeer {
    const val ROOT_UID = 0
    const val SHELL_UID = 2000

    fun allowed(uid: Int): Boolean = uid == ROOT_UID || uid == SHELL_UID
}
