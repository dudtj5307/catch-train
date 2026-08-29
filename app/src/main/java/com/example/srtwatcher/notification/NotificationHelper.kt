package com.example.srtwatcher.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.srtwatcher.MainActivity
import com.example.srtwatcher.R
import com.example.srtwatcher.domain.SeatMatch

/**
 * 좌석 발견 알림. (DESIGN.md §19)
 *
 * 알림을 누르면 [MainActivity] 로 복귀하여 WebView 화면을 볼 수 있다.
 *
 * 채널은 두 개다.
 *  - [CHANNEL_ID] 좌석을 찾았다는 한 번짜리 알림
 *  - [ALERT_CHANNEL_ID] 결제 화면까지 갔는데 사용자가 아직 못 본 경우의 재촉 알림.
 *    좌석이 풀리는 10분까지 10초마다 되풀이되므로 알람 소리를 쓰고, 사용자가 이것만
 *    따로 조절할 수 있도록 채널을 나눴다.
 */
class NotificationHelper(private val context: Context) : MatchNotifier {

    fun createChannel() {
        val manager = NotificationManagerCompat.from(context)

        val match = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(match)

        // 결제를 재촉하는 채널. 알람 소리를 쓰는 이유는 이 알림을 놓치면 잡은 좌석을
        // 그대로 잃기 때문이다. 채널 설정은 한 번 만들면 앱이 바꿀 수 없으므로
        // (사용자가 조절한 값을 앱이 덮어쓰지 못하게 하는 안드로이드 규칙) ID 를 따로 뒀다.
        val alert = NotificationChannel(
            ALERT_CHANNEL_ID,
            context.getString(R.string.reserve_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reserve_alert_channel_description)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setShowBadge(true)
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (sound != null) setSound(sound, alarmAudioAttributes())
        }
        manager.createNotificationChannel(alert)
    }

    override fun notifyMatch(match: SeatMatch, extraCount: Int) {
        if (!hasPermission()) return

        val train = match.train
        // 역 이름은 파서가 읽어내지 못할 수 있다. 그때는 열차 번호로 대신한다.
        val route = if (train.departureStation.isBlank() && train.arrivalStation.isBlank()) {
            train.trainNumber.ifBlank { "SRT" }
        } else {
            "${train.departureStation} → ${train.arrivalStation}"
        }
        val line = buildString {
            append(train.trainNumber)
            append(" ")
            append(train.departureTime.toString())
            append("  ")
            append(match.seatClass.label)
            append(" ")
            append(train.seatStatusOf(match.seatClass).label)
        }
        val extra = if (extraCount > 0) "\n외 ${extraCount}건 더 발견" else ""

        val contentIntent = openAppIntent(REQUEST_OPEN_APP)

        val body = "$route\n$line$extra"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_train)
            .setContentTitle("🚄 SRT 좌석 발견")
            .setContentText("$route  ${train.departureTime} ${match.seatClass.label}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(contentIntent)
            .addAction(0, "예약 화면 열기", contentIntent)
            .build()

        notifySafely(NOTIFICATION_ID, notification)
    }

    /**
     * 결제 재촉 알림. (DESIGN.md §19-3)
     *
     * 같은 [ALERT_NOTIFICATION_ID] 로 매번 다시 올린다. 알림 줄이 계속 쌓이는 대신
     * 한 줄이 갱신되면서 소리와 진동만 다시 울린다.
     *
     * 진동은 알림과 별개로 [vibrate] 로 한 번 더 울린다. 제조사에 따라 같은 ID 를
     * 갱신할 때는 소리/진동을 생략하는 경우가 있어서다. 알림 권한이 없어도 진동은
     * 울리므로, 권한을 거부한 사용자에게도 최소한의 신호는 간다.
     */
    override fun notifyReserveReminder(match: SeatMatch, repeatIndex: Int, elapsedMs: Long) {
        val train = match.train
        val elapsed = formatElapsed(elapsedMs)

        if (hasPermission()) {
            val openIntent = openAppIntent(REQUEST_OPEN_APP)
            val silenceIntent = openAppIntent(REQUEST_SILENCE, stopAlert = true)

            val body = buildString {
                append(train.summary())
                append("\n")
                append(match.seatClass.label)
                append(" · [예약하기] 누른 지 ")
                append(elapsed)
                append(" 지남\n")
                append("SRT 결제 제한 시간이 지나면 좌석이 풀립니다. 지금 결제하세요.")
            }

            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_train)
                .setContentTitle("🎫 결제를 진행하세요 ($elapsed 경과)")
                .setContentText("${train.summary()}  ${match.seatClass.label}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                // 스치기만 해도 사라지면 안 된다. [알림 끄기] 나 감시 종료로만 없앤다.
                .setOngoing(true)
                .setAutoCancel(false)
                // 갱신할 때마다 다시 울려야 재촉이 된다.
                .setOnlyAlertOnce(false)
                .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
                .setContentIntent(openIntent)
                .addAction(0, "결제 화면 열기", openIntent)
                .addAction(0, "알림 끄기", silenceIntent)
                .build()

            notifySafely(ALERT_NOTIFICATION_ID, notification)
        }

        vibrate()
    }

    override fun cancelReserveReminder() {
        NotificationManagerCompat.from(context).cancel(ALERT_NOTIFICATION_ID)
        cancelVibration()
    }

    override fun cancelAll() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        cancelReserveReminder()
    }

    // ---------------------------------------------------------------- 내부

    private fun notifySafely(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // 권한이 도중에 회수된 경우. 감시 자체는 계속한다.
        }
    }

    private fun openAppIntent(requestCode: Int, stopAlert: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_FROM_NOTIFICATION, true)
                if (stopAlert) putExtra(EXTRA_STOP_ALERT, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun vibrator(): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        return vibrator?.takeIf { it.hasVibrator() }
    }

    private fun vibrate() {
        val vibrator = vibrator() ?: return
        try {
            vibrator.vibrate(
                VibrationEffect.createWaveform(VIBRATION_PATTERN, NO_REPEAT),
                alarmAudioAttributes(),
            )
        } catch (_: RuntimeException) {
            // 진동에 실패해도 알림 자체는 이미 올라가 있다.
        }
    }

    private fun cancelVibration() {
        try {
            vibrator()?.cancel()
        } catch (_: RuntimeException) {
        }
    }

    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /** "30초" / "2분" / "2분 30초" */
    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes == 0L -> "${seconds}초"
            seconds == 0L -> "${minutes}분"
            else -> "${minutes}분 ${seconds}초"
        }
    }

    /** Android 13(API 33) 이상에서만 런타임 권한이 필요하다. */
    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_ID = "srt_watcher_match"
        const val ALERT_CHANNEL_ID = "srt_watcher_reserve_alert"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002
        const val EXTRA_FROM_NOTIFICATION = "from_notification"

        /** 알림의 [알림 끄기] 를 눌러 들어온 경우. 재촉 알림을 멈춘다. */
        const val EXTRA_STOP_ALERT = "stop_reserve_alert"

        private const val REQUEST_OPEN_APP = 100
        private const val REQUEST_SILENCE = 101

        /** 주머니 속에서도 알아챌 만큼 길게. (대기-진동-대기-진동…) */
        private val VIBRATION_PATTERN = longArrayOf(0, 600, 250, 600, 250, 900)
        private const val NO_REPEAT = -1
    }
}
