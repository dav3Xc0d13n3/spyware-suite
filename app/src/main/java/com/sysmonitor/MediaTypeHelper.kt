package com.sysmonitor

import okhttp3.MediaType

@Suppress("DEPRECATION")
object MediaTypeHelper {
    fun getMediaType(value: String): MediaType = MediaType.parse(value)
}
