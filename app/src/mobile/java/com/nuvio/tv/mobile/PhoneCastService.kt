package com.nuvio.tv.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nuvio.tv.R
import java.io.Closeable

/** Keep serving when the user locks the phone or opens another app. Ownership lives on the main thread. */
internal class PhoneCastService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "phone_cast"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channel, "Casting from this phone", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(this, MobileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Casting from this phone").setContentText("Open ReSeq for playback controls")
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setSilent(true).setOngoing(true).build()
        startForeground(9530, notification)
        running = this
        if (owners == 0) stopSelf(startId)
        else if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReSeq:PhoneCast").apply { acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private var owners = 0
        private var running: PhoneCastService? = null

        fun acquire(context: Context): Closeable {
            if (owners == 0) ContextCompat.startForegroundService(context, Intent(context, PhoneCastService::class.java))
            owners++
            var released = false
            return Closeable {
                if (!released) {
                    released = true
                    if (--owners == 0) running?.stopSelf()
                }
            }
        }
    }
}
