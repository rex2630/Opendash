package com.kooduXA.opendash.di

import android.app.Application
import android.content.Context
import com.kooduXA.opendash.data.repository.ConnectionRepository
import com.kooduXA.opendash.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(application: Application): SettingsRepository {
        return SettingsRepository(application)
    }

    @Provides
    @Singleton
    fun provideConnectionRepository(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository
    ): ConnectionRepository {
        return ConnectionRepository(context, settingsRepository)
    }
}
