package com.uchiharepo.streamx

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class StreamxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamxProvider())
    }
}
