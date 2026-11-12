package com.mg.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mg.player.ui.detail.TrailerLocalServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ServerSocket(...) binden ist für Android ab API 11 immer eine Netzwerkoperation, auch auf Loopback - auf dem
 * Hauptthread wirft das eine NetworkOnMainThreadException, unabhängig von StrictMode. TrailerScreen ruft
 * TrailerLocalServer.start() aus einem DisposableEffect auf, also vom Compose-Hauptthread aus; genau das
 * gemeldete Problem (Fehlerkarte bei jedem Trailer) kam von dieser verschluckten Exception. Robolectric erzwingt
 * diese Regel nicht - dieser Test läuft deshalb auf dem Gerät.
 */
@RunWith(AndroidJUnit4::class)
class TrailerLocalServerDeviceTest {

    @Test
    fun startWorksWhenCalledFromTheMainThread() {
        val server = TrailerLocalServer("abc123XYZ_-")
        var url: String? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { url = server.start() }
            assertNotNull("start() auf dem Hauptthread darf nicht lautlos scheitern", url)
            assertTrue(url!!.startsWith("http://127.0.0.1:"))
        } finally {
            server.stop()
        }
    }
}
