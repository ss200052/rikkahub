package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.conversationIntent
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "ChatGenerationFgs"

/**
 * Keeps the app process in the foreground while one or more chat generations are active.
 *
 * Generation itself remains owned by [ChatService]. This service only provides the Android
 * foreground-service lifetime required for streaming to continue after the activity is hidden.
 */
class ChatGenerationForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.CHAT_GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.CHAT_GENERATION_RELEASE"
        private const val EXTRA_GENERATION_ID = "generation_id"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"

        const val NOTIFICATION_ID = 2002

        private const val EXTRA_KEEP_AWAKE = "keep_awake"
        private const val ACTION_SET_KEEP_AWAKE = "me.rerere.rikkahub.action.SET_KEEP_AWAKE"

        fun acquire(
            context: Context,
            generationId: Uuid,
            conversationId: Uuid,
            keepAwakeWhileScreenOff: Boolean = false,
        ): Boolean {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
                putExtra(EXTRA_KEEP_AWAKE, keepAwakeWhileScreenOff)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start chat generation foreground service", it)
            }.getOrDefault(false)
        }

        /**
         * 运行中切换「熄屏也跑」：任务已经跑起来了，只能就地加减唤醒锁。
         */
        fun setKeepAwake(context: Context, enabled: Boolean) {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_SET_KEEP_AWAKE
                putExtra(EXTRA_KEEP_AWAKE, enabled)
            }
            runCatching { context.startService(intent) }.onFailure {
                Log.w(TAG, "Unable to toggle keep awake", it)
            }
        }

        fun release(context: Context, generationId: Uuid) {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release chat generation foreground service", it)
            }
        }
    }

    private val activeGenerations = linkedMapOf<String, String>()
    private var isForeground = false

    /** 批量任务的进度监听：状态一变就刷新常驻通知 */
    private var batchObserver: Job? = null

    /** 熄屏继续跑时持有的 CPU 锁；不需要时保持为 null */
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val appScope: AppScope by inject()
    private val chatService: ChatService by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent, startId)
            ACTION_RELEASE -> release(intent, startId)
            ACTION_SET_KEEP_AWAKE -> {
                if (intent.getBooleanExtra(EXTRA_KEEP_AWAKE, false)) {
                    acquireWakeLock()
                } else {
                    releaseWakeLock()
                }
            }

            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        batchObserver?.cancel()
        batchObserver = null
        activeGenerations.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Foreground service timed out (type=$fgsType)")
        activeGenerations.values
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .distinct()
            .forEach { conversationId ->
                appScope.launch {
                    chatService.stopGeneration(conversationId)
                }
            }
        // Android only allows a few seconds after onTimeout before raising RemoteServiceException.
        stopService()
    }

    private fun acquire(intent: Intent, startId: Int) {
        val generationId = intent.getStringExtra(EXTRA_GENERATION_ID) ?: return stopSelf(startId)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return stopSelf(startId)
        if (intent.getBooleanExtra(EXTRA_KEEP_AWAKE, false)) acquireWakeLock()
        activeGenerations[generationId] = conversationId
        updateForegroundNotification(conversationId)
        startBatchObserver()
    }

    private fun release(intent: Intent, startId: Int) {
        intent.getStringExtra(EXTRA_GENERATION_ID)?.let(activeGenerations::remove)
        if (activeGenerations.isEmpty()) {
            releaseWakeLock()
            // Only stop if no newer start request is queued. A pending ACQUIRE came from
            // startForegroundService(), and stopping before it calls startForeground() crashes
            // with ForegroundServiceDidNotStartInTimeException. onDestroy() removes the notification.
            stopSelf(startId)
        } else {
            updateForegroundNotification(activeGenerations.values.last())
        }
    }

    private fun updateForegroundNotification(conversationId: String) {
        try {
            val notification = buildNotification(conversationId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeGenerations.clear()
            stopSelf()
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    /**
     * 熄屏也继续跑：持有 CPU 唤醒锁，避免系统休眠把网络请求挂起。
     *
     * 只有用户在批量设置里显式打开才会走到这里；任务结束立即释放。
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = power.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "rikkahub:batch-generation",
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // 上限 4 小时，防止忘记释放
            }
        }.onFailure {
            Log.w(TAG, "Unable to acquire wake lock", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * 盯着批量任务：每路进入终态、重 roll 次数变化都要反映到常驻通知上。
     *
     * 服务随生成任务一同启动，所以这里只负责在它活着期间同步进度。
     */
    private fun startBatchObserver() {
        if (batchObserver?.isActive == true) return
        batchObserver = appScope.launch {
            chatService.batchSession.collect {
                if (!isForeground) return@collect
                val conversationId = activeGenerations.values.lastOrNull() ?: return@collect
                updateForegroundNotification(conversationId)
            }
        }
    }

    private fun buildNotification(conversationId: String): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentIntent(getConversationPendingIntent(conversationId))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        // 属于这个对话的批量任务：把进度挂在通知上
        val batch = chatService.batchSession.value
            ?.takeIf { it.conversationId == conversationId }
        if (batch != null) {
            val total = batch.lanes.size
            val done = batch.finishedCount
            if (batch.finished || total == 0) {
                builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.notification_live_update_title))
            } else {
                builder.setContentTitle(
                    getString(R.string.notification_batch_progress_title, done, total),
                )
                builder.setContentText(
                    getString(
                        R.string.notification_batch_progress_text,
                        batch.hitCount,
                        batch.totalRerolls,
                    ),
                )
                builder.setProgress(total, done, false)
            }
        } else {
            builder.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_live_update_title))
        }

        return builder.build()
    }

    private fun getConversationPendingIntent(conversationId: String): PendingIntent {
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            conversationIntent(this, conversationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
