package com.rcboat.gateway.mavlink.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing application-wide dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    // All dependencies are provided through constructor injection in their respective classes
    // This module serves as a placeholder for future global dependencies
}