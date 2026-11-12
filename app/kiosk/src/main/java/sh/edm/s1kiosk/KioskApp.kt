package sh.edm.s1kiosk

import android.app.Application
import sh.edm.s1kiosk.frame.AqaraS1Plus

class KioskApp : Application() {

    val glassworks = GlassworksRuntime(AqaraS1Plus())
}
