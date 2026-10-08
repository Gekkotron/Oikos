package com.gekkotron.oikos

import com.gekkotron.oikos.alerts.AlertRepository
import com.gekkotron.oikos.alerts.media.AlertImageCache
import com.gekkotron.oikos.alerts.media.VideoCache
import com.gekkotron.oikos.push.PushRegistrar
import com.gekkotron.oikos.push.ReplyQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything push- and widget-related that "Set up again" forgets, before the profile is wiped (push alerts spec §5.4, §6.3).
 * Each step is best effort: whatever fails, the next one still runs and the caller still resets the profile.
 */
class SetUpAgainCleanup(
    private val registrar: PushRegistrar,
    private val repository: AlertRepository,
    private val replies: ReplyQueue,
    private val images: AlertImageCache,
    private val videos: VideoCache,
    /** Clears the widgets' display data and redraws them; their config is kept (widgets spec §4). */
    private val resetWidgets: suspend () -> Unit = {},
    private val dismissNotifications: () -> Unit,
) {
    suspend fun run() {
        // First: the unregister needs the install id and the token topic of the profile that is about to go.
        step { registrar.unregister() }
        step { replies.cancelAll() }
        step { dismissNotifications() }
        step { repository.reset() }
        step {
            withContext(Dispatchers.IO) {
                images.clear()
                videos.clear()
            }
        }
        step { resetWidgets() }
    }

    private suspend fun step(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort by design: see the class comment.
        }
    }
}
