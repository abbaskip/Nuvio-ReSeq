package com.nuvio.tv.core.tmdb

import com.nuvio.tv.core.profile.ProfileManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Existing metadata/ID lookups also honor the active profile's locally entered key. */
class TmdbLocalKeyInterceptor internal constructor(
    private val apiKey: () -> String,
    private val host: String = "api.themoviedb.org"
) : Interceptor {
    @Inject constructor(credentials: TmdbCredentialStore, profiles: ProfileManager) : this(
        { credentials.access(profiles.activeProfileId.value).apiKey }
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != host) return chain.proceed(request)
        val localKey = apiKey()
        if (localKey.isBlank()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(request.url.newBuilder()
            .removeAllQueryParameters("api_key").addQueryParameter("api_key", localKey).build()).build())
    }
}

/** No URL logging, diagnostic breadcrumbs, redirects or URL-keyed disk cache for personal keys. */
internal fun tmdbPrivateClient(interceptor: Interceptor? = null): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false)
    .apply { if (interceptor != null) addInterceptor(interceptor) }
    .build()
