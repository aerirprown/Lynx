package app.lynx.prm

import android.service.notification.NotificationListenerService

/**
 * Declared now so notification access can be turned on from Settings. It reads nothing
 * yet. Phase 4 hands call notifications from here to CallPromptController.
 */
class CallNotificationListener : NotificationListenerService()
