package sh.edm.s1kiosk.auth

import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.edm.s1kiosk.auth.SignInServer.Companion.Look
import java.io.File

/**
 * The pages the panel serves from its QR codes (#63), as HTML files to render in a browser at phone and
 * desktop sizes. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SignInPageMockups*' -Ps1MockupDir=<dir>
 */
class SignInPageMockups {
    private val ha = "http://homeassistant.local:8123"

    private fun save(name: String, r: SignInServer.Response) {
        val out = File(System.getProperty("s1.mockupDir")!!, "qr").apply { mkdirs() }
        File(out, "$name.html").writeBytes(r.body)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", System.getProperty("s1.mockupDir") != null)
        val server = SignInServer(0, { ha }, HaTokenClient(UrlConnectionPoster()), { _, _ -> })
        save("01-sign-in", server.handle(SignInServer.Request("GET", "/signin", emptyMap(), emptyMap())))
        save("02-signed-in", SignInServer.page(200, "OK", "Panel signed in", "It's loading your dashboard now. You can close this page.", Look.DONE,
            tile = SignInServer.haTile(ha, dot = true)))
        save("03-thread", SignInServer.page(200, "OK", "Share your Thread network", "The panel joins it as a Thread router.", Look.START,
            tile = SignInServer.infoTile("Sign in as a Home Assistant <b>administrator</b>. The panel uses it once to copy the network's settings, then signs out."),
            button = SignInServer.button("Continue to Home Assistant", SignInServer.THREAD_CONTINUE), foot = "Your network key is never shown or logged."))
        save("04-joining", SignInServer.page(200, "OK", "Joining Example Thread", "The panel has the network's settings and is joining as a router. You can close this page.",
            Look.DONE, tile = SignInServer.networkTile("Example Thread", 15)))
        save("05-expired", server.handle(SignInServer.Request("GET", "/callback", mapOf("state" to "nope", "code" to "x"), emptyMap())))
        save("06-ha-not-found", SignInServer(0, { null }, HaTokenClient(UrlConnectionPoster()), { _, _ -> })
            .handle(SignInServer.Request("GET", "/signin", emptyMap(), emptyMap())))
        save("07-cancelled", server.handle(SignInServer.Request("GET", "/callback", mapOf("error" to "access_denied"), emptyMap())))
        save("08-not-waiting", SignInServer(0, { ha }, HaTokenClient(UrlConnectionPoster()), { _, _ -> }, thread = null)
            .handle(SignInServer.Request("GET", "/nope", emptyMap(), emptyMap())))
    }
}
