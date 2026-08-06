package com.mobiledivecontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mobiledivecontrol.DiveControlApp
import com.mobiledivecontrol.MainActivity
import com.mobiledivecontrol.core.BleSignal
import com.mobiledivecontrol.platform.ble.HousingLink
import com.mobiledivecontrol.platform.ble.HousingLinkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Keeps the housing link alive for the whole dive and reports its state honestly.
 *
 * Two jobs only. First, the process must not be frozen or killed while the phone is sealed in the
 * housing — a background app loses its GATT connection and the diver has no way to relaunch it.
 * Second, the ongoing notification is the one surface that shows link state when the app is not
 * in the foreground, so it never says "Connected" unless the link actually reached Ready.
 */
class HousingLinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var link: HousingLink? = null
    private var status: LinkStatus = LinkStatus.Searching

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? DiveControlApp
        if (app == null) {
            Log.e(TAG, "Application is not DiveControlApp; housing link unavailable.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (link == null) {
            val housingLink = app.housingLink
            link = housingLink
            if (!enterForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            observe(housingLink)
            housingLink.start()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        link?.stop()
        link = null
        scope.cancel()
        super.onDestroy()
    }

    private fun observe(housingLink: HousingLink) {
        scope.launch {
            housingLink.events
                .filterIsInstance<HousingLinkEvent.Ble>()
                .collect { event ->
                    val next = statusFor(event.signal) ?: return@collect
                    if (next == status) return@collect
                    status = next
                    updateNotification(next)
                }
        }
    }

    private fun statusFor(signal: BleSignal): LinkStatus? = when (signal) {
        BleSignal.StartScan -> LinkStatus.Searching
        BleSignal.Connect,
        BleSignal.DiscoverServices,
        BleSignal.Subscribe,
            -> LinkStatus.Connecting
        BleSignal.Ready -> LinkStatus.Connected
        BleSignal.Disconnect -> LinkStatus.Reconnecting
        BleSignal.HousingPoweredOff -> LinkStatus.PoweredOff
        BleSignal.Fail -> LinkStatus.Unavailable
        BleSignal.Reset -> LinkStatus.Idle
        BleSignal.Degrade -> LinkStatus.Degraded
    }

    private fun enterForeground(): Boolean = runCatching {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }.onFailure { error ->
        Log.e(TAG, "Could not start housing link foreground service", error)
    }.isSuccess

    private fun updateNotification(next: LinkStatus) {
        runCatching {
            notificationManager()?.notify(NOTIFICATION_ID, buildNotification(next))
        }.onFailure { error ->
            Log.w(TAG, "Could not update housing link notification", error)
        }
    }

    private fun buildNotification(state: LinkStatus): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("MobileDiveControl")
            .setContentText(state.label)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Housing link",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows whether the dive housing is connected."
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager()?.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * Notification wording.
     *
     * Deliberately mirrors the link state one-to-one rather than collapsing everything into
     * "Connected"/"Disconnected" — a diver reading "Housing powered off" knows to press the power
     * button, while "Reconnecting" tells them to wait.
     */
    private enum class LinkStatus(val label: String) {
        Idle("Housing link idle"),
        Searching("Searching for housing"),
        Connecting("Connecting to housing"),
        Connected("Housing connected"),
        Degraded("Housing connected — degraded"),
        Reconnecting("Housing disconnected — reconnecting"),
        PoweredOff("Housing powered off"),
        Unavailable("Housing unavailable — check Bluetooth"),
    }

    private companion object {
        const val TAG = "HousingLinkService"
        const val CHANNEL_ID = "housing_link"
        const val NOTIFICATION_ID = 4201
    }
}
