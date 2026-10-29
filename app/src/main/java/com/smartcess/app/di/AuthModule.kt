package com.smartcess.app.di

import com.smartcess.app.BuildConfig
import com.smartcess.app.features.auth.CredentialManagerIdTokenProvider
import com.smartcess.app.features.auth.GoogleIdTokenProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindIdTokenProvider(
        impl: CredentialManagerIdTokenProvider,
    ): GoogleIdTokenProvider

    companion object {
        /** Web client ID: public identifier baked in at build time, blank when unset. */
        @Provides
        @Singleton
        @Named("googleServerClientId")
        fun provideGoogleServerClientId(): String = BuildConfig.GOOGLE_SERVER_CLIENT_ID
    }
}
