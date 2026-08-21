package com.kdrive.tv.data

import android.content.Context
import coil.ImageLoader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Posters are served from TMDb's public CDN (image.tmdb.org), not our
 * server — only attach the device-key header on requests to our own host,
 * so it's never sent to a third party. */
fun authenticatedImageLoader(context: Context, credentials: Credentials): ImageLoader {
    val serverHost = credentials.serverUrl.toHttpUrlOrNull()?.host

    val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val request = if (original.url.host == serverHost) {
                original.newBuilder().header("x-kdrive-device-key", credentials.deviceKey).build()
            } else {
                original
            }
            chain.proceed(request)
        }
        .build()

    return ImageLoader.Builder(context)
        .okHttpClient(client)
        .build()
}
