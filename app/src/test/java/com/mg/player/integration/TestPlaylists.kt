package com.mg.player.integration

/** Kleine, realistische Test-Playlist: Sender in mehreren Qualitäten, Filme und eine Serie. */
object TestPlaylists {
    val M3U = """
        #EXTM3U url-tvg="http://example.invalid/epg.xml"
        #EXTINF:-1 tvg-id="zdf.de" tvg-name="DE: ZDF HD" tvg-logo="http://logo/zdf.png" group-title="DE | Vollprogramm",DE: ZDF HD
        http://stream.invalid/live/zdf_hd.ts
        #EXTINF:-1 tvg-id="zdf.de" tvg-name="DE: ZDF FHD" group-title="DE | Vollprogramm",DE: ZDF FHD
        http://stream.invalid/live/zdf_fhd.ts
        #EXTINF:-1 tvg-id="zdfneo.de" tvg-name="DE: ZDFneo" group-title="DE | Vollprogramm",DE: ZDFneo
        http://stream.invalid/live/zdfneo.ts
        #EXTINF:-1 tvg-id="das-erste.de" tvg-name="DE: Das Erste HD" group-title="DE | Vollprogramm",DE: Das Erste HD
        http://stream.invalid/live/ard.ts
        #EXTINF:-1 tvg-id="tagesschau24.de" tvg-name="DE: tagesschau24" group-title="DE | Info",DE: tagesschau24
        http://stream.invalid/live/ts24.ts
        #EXTINF:-1 tvg-id="trt1.tr" tvg-name="TR: TRT 1" group-title="TR | Ulusal",TR: TRT 1
        http://stream.invalid/live/trt1.ts
        #EXTINF:-1 tvg-logo="http://poster/inception.jpg" group-title="Filme | Action",Inception (2010)
        http://stream.invalid/movie/inception.mkv
        #EXTINF:-1 tvg-logo="http://poster/heat.jpg" group-title="Filme | Action",Heat (1995)
        http://stream.invalid/movie/heat.mp4
        #EXTINF:-1 tvg-logo="http://poster/kaiser.jpg" group-title="Filme | Drama",Der Kaiser von Kalifornien
        http://stream.invalid/movie/kaiser.mp4
        #EXTINF:-1 tvg-logo="http://poster/dark.jpg" group-title="Serien | Mystery",Dark S01 E01
        http://stream.invalid/series/dark_s01e01.mkv
        #EXTINF:-1 tvg-logo="http://poster/dark.jpg" group-title="Serien | Mystery",Dark S01 E02
        http://stream.invalid/series/dark_s01e02.mkv
    """.trimIndent()
}
