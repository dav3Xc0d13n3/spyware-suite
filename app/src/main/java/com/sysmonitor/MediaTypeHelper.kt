package com.sysmonitor

import okhttp3.MediaType

object MediaTypeHelper {
    @Suppress("DEPRECATION")
    fun getMediaType(value: String): MediaType = MediaType.parse(value)!!
}
