package app.pocketfoundry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.termux.x11.MainActivity

/** In the foreground while a game runs, so Android doesn't reclaim a paused game. */
class GameService : Service() {
    private val update: () -> Unit = { refresh() }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        Session.addListener(update)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) Session.quit()
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        refresh()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (rootIntent?.component?.className == MainActivity::class.java.name) Session.quit()
    }

    override fun onDestroy() {
        Session.removeListener(update)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refresh() {
        if (Session.state == Session.State.STOPPED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(ID, notification())
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java),
                                             PendingIntent.FLAG_IMMUTABLE)
        val quit = PendingIntent.getService(this, 1, Intent(this, GameService::class.java).setAction(ACTION_QUIT),
                                            PendingIntent.FLAG_IMMUTABLE)
        val text = when (Session.state) {
            Session.State.PAUSED -> R.string.game_paused
            Session.State.QUITTING -> R.string.game_quitting
            else -> R.string.game_playing
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Factorio")
            .setContentText(getString(text))
            .setContentIntent(open)
            .setOngoing(true)
            .apply {
                if (Session.state != Session.State.QUITTING) {
                    addAction(Notification.Action.Builder(null, getString(R.string.quit), quit).build())
                }
            }
            .build()
    }

    companion object {
        private const val CHANNEL = "game"
        private const val ID = 1
        private const val ACTION_QUIT = "app.pocketfoundry.QUIT"
    }
}
