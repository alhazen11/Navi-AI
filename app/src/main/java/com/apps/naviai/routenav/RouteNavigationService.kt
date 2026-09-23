package com.apps.naviai.routenav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.apps.naviai.MainActivity
import com.apps.naviai.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Thin foreground-service wrapper: its only job is to keep the process
 * alive with elevated priority (a persistent, low-importance notification)
 * while navigation is active -- required for continuous background
 * GPS use on modern Android. All the actual GPS/compass/TTS logic lives in
 * [NavigationController] (a plain Hilt singleton, independent of this
 * Service's own lifecycle), so navigation state isn't lost even if the
 * system were to recreate this Service.
 */
@AndroidEntryPoint
class RouteNavigationService : Service() {

    @Inject lateinit var navigationController: NavigationController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val routeName = intent?.getStringExtra(EXTRA_ROUTE_NAME)
        if (intent?.action == ACTION_STOP || routeName == null) {
            // Defaults to true (the UI Stop button/notification action's
            // path -- nothing else has spoken a confirmation yet). When
            // NavigationController itself re-enters here via
            // stopAndDismissService() (voice "stop navigasi", or arrival)
            // it passes false, since it already spoke its own confirmation
            // (or the "arrived" announcement) before asking this service to
            // shut down -- otherwise the user would hear two announcements.
            val spokenConfirmation = intent?.getBooleanExtra(EXTRA_SPOKEN_CONFIRMATION, true) ?: true
            navigationController.stop(spokenConfirmation = spokenConfirmation)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(routeName))
        navigationController.start(routeName)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        navigationController.stop(spokenConfirmation = false)
        super.onDestroy()
    }

    private fun buildNotification(routeName: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Route navigation", NotificationManager.IMPORTANCE_LOW)
            manager?.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, RouteNavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Navigating: $routeName")
            .setContentText("NAVI is guiding you along this route.")
            .setSmallIcon(R.drawable.logo)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        const val EXTRA_ROUTE_NAME = "route_name"
        const val EXTRA_SPOKEN_CONFIRMATION = "spoken_confirmation"
        const val ACTION_STOP = "com.apps.naviai.routenav.ACTION_STOP"
        private const val CHANNEL_ID = "route_navigation"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context, routeName: String) {
            val intent = Intent(context, RouteNavigationService::class.java).putExtra(EXTRA_ROUTE_NAME, routeName)
            context.startForegroundService(intent)
        }

        fun stop(context: Context, spokenConfirmation: Boolean = true) {
            val intent = Intent(context, RouteNavigationService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SPOKEN_CONFIRMATION, spokenConfirmation)
            context.startService(intent)
        }
    }
}
