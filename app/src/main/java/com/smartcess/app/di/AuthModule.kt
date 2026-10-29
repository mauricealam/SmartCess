package com.smartcess.app.di

import com.smartcess.app.features.auth.CredentialManagerIdTokenProvider
import com.smartcess.app.features.auth.GoogleIdTokenProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindIdTokenProvider(
        impl: CredentialManagerIdTokenProvider,
    ): GoogleIdTokenProvider
}
