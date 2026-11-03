package com.nuvio.tv.core.di

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.core.tmdb.tmdbPrivateClient
import com.nuvio.tv.data.remote.api.TmdbApi
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TmdbCredentialModule {
    @Provides @IntoSet
    fun credentialStore(store: TmdbCredentialStore): ProfileScopedCredentialStore = store

    // Discovery captures a profile/revision for each operation and supplies that exact key.
    // A separate client prevents the active-profile interceptor changing it mid-request.
    @Provides @Singleton @Named("discoveryTmdb")
    fun discoveryApi(moshi: Moshi): TmdbApi = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/").client(tmdbPrivateClient())
        .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(TmdbApi::class.java)
}
