package com.filelogger.consumer

import com.filelogger.okhttp.FileLoggerInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient

object KotlinConsumer {
    fun interceptor(): Interceptor = FileLoggerInterceptor()

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(interceptor())
        .build()
}
