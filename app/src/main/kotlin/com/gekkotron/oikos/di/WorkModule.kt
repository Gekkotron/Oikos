package com.gekkotron.oikos.di

import android.content.Context
import androidx.work.WorkManager
import com.gekkotron.oikos.push.RegistrationQueue
import com.gekkotron.oikos.push.ReplyQueue
import com.gekkotron.oikos.push.WorkManagerRegistrationQueue
import com.gekkotron.oikos.push.WorkManagerReplyQueue
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The WorkManager-backed queues, apart from `PushModule` so a test can replace just them. */
@Module
@InstallIn(SingletonComponent::class)
object WorkModule {
    @Provides
    @Singleton
    fun registrationQueue(@ApplicationContext context: Context): RegistrationQueue =
        WorkManagerRegistrationQueue { WorkManager.getInstance(context) }

    @Provides
    @Singleton
    fun replyQueue(@ApplicationContext context: Context): ReplyQueue = WorkManagerReplyQueue({ WorkManager.getInstance(context) })
}
