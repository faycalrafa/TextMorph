package com.textmorph.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.CrashLogManager
import com.textmorph.app.util.NativeFlightRecorder
import com.textmorph.app.util.TextMorphCrashHandler
import okhttp3.OkHttpClient

class TextMorphApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        TextMorphCrashHandler.install(this)
        val crashLogManager = CrashLogManager.getInstance(this)
        NativeFlightRecorder.checkAndRecoverPreviousCrash(this, crashLogManager)
        AppLogger.i("TextMorphApp", "TextMorph Application initialized successfully with CrashHandler and Persistent Logging.")
    }

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .build()
                chain.proceed(request)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }
}
