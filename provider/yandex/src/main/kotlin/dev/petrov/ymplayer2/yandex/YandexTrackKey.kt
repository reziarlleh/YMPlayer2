package dev.petrov.ymplayer2.yandex

/** Track IDs are opaque (UGC uploads use strings); album/user/artist IDs remain numeric.
 * Accept only a bounded URI segment, without interpreting the track ID as a number. */
internal fun validYandexTrackId(value: String): Boolean =
    value != "null" && value.matches(Regex("[A-Za-z0-9_-]{1,256}"))

internal fun validYandexTrackKey(value: String): Boolean {
    val parts = value.split(':')
    return parts.size in 1..2 && validYandexTrackId(parts[0]) &&
        (parts.size == 1 || parts[1].matches(Regex("[0-9]{1,30}")))
}
