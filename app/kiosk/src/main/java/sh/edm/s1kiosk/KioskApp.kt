package sh.edm.s1kiosk

import android.app.Application
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.frame.AqaraS1Plus

class KioskApp : Application() {

    /** Made on first use, when the device is known: the frame is the S1's on its hardware, else the generic one. */
    val glassworks by lazy { GlassworksRuntime(AqaraS1Plus.orElse(Platform.hardware) { GenericAndroid(this) }) }
}
