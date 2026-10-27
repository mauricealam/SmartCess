package com.smartcess.app.di

import android.content.Context
import com.smartcess.app.core.config.ApiKeyProvider
import com.smartcess.app.core.config.ApiKeyProviderImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** App-level Hilt bindings. Module agents add feature bindings in their packages. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApiKeyProvider(
        @ApplicationContext context: Context,
    ): ApiKeyProvider = ApiKeyProviderImpl(context)
}
