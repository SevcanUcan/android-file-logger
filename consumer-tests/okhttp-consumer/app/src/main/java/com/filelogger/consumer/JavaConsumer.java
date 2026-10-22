package com.filelogger.consumer;

import com.filelogger.okhttp.FileLoggerInterceptor;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

public final class JavaConsumer {
    private JavaConsumer() {
    }

    public static Interceptor interceptor() {
        return new FileLoggerInterceptor();
    }

    public static OkHttpClient client() {
        return new OkHttpClient.Builder()
                .addInterceptor(interceptor())
                .build();
    }
}
