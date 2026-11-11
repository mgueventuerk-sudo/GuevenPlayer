package com.mg.player.data.combined

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringWriter

/** Nur von Hand: echte `channels.json` (`./gradlew … -PcatalogJson=/pfad/channels.json`) umwandeln und mit dem mitgelieferten Katalog vergleichen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnlineCatalogCheckTest {
    @Test
    fun realDownloadConverts() = runBlocking {
        val path = System.getProperty("catalogJson")?.takeIf(String::isNotBlank)
        assumeTrue(path != null && File(path).isFile)
        val out = StringWriter()
        val count = ChannelCatalogUpdate.convert(File(path!!).reader(), out)
        val catalog = ChannelCatalog.parse(out.toString().byteInputStream())
        println("ONLINE $count Sender, Katalog ${catalog.size}, TRT1=${catalog.byNameKey(ChannelCatalog.keyFor("TRT 1", "TR"))}")
    }
}
