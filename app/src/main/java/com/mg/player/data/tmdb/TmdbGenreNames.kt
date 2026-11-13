package com.mg.player.data.tmdb

/**
 * TMDBs feste, seit Jahren unveränderte Genre-Kennungen (https://developer.themoviedb.org/reference/genre-movie-list,
 * .../genre-tv-list) - ein eigener Aufruf nur für die Namen lohnt sich nicht, "discover" liefert die Kennungen
 * schon mit. Eine unbekannte Kennung (neues TMDB-Genre) fällt einfach weg, statt die Einordnung zu verfälschen.
 */
object TmdbGenreNames {
    private val names = mapOf(
        28 to "Action",
        12 to "Abenteuer",
        16 to "Animation",
        35 to "Komödie",
        80 to "Krimi",
        99 to "Dokumentarfilm",
        18 to "Drama",
        10751 to "Familie",
        14 to "Fantasy",
        36 to "Historie",
        27 to "Horror",
        10402 to "Musik",
        9648 to "Mystery",
        10749 to "Liebesfilm",
        878 to "Science Fiction",
        10770 to "TV-Film",
        53 to "Thriller",
        10752 to "Kriegsfilm",
        37 to "Western",
        10759 to "Action & Abenteuer",
        10762 to "Kinder",
        10763 to "Nachrichten",
        10764 to "Reality-TV",
        10765 to "Science-Fiction & Fantasy",
        10766 to "Soap",
        10767 to "Talk",
        10768 to "Krieg & Politik",
    )

    fun of(genreIds: List<Int>): List<String> = genreIds.mapNotNull(names::get)
}
