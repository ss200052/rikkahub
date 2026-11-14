package me.rerere.ai.util

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 流式响应在给定时间内没有产出任何内容时抛出。
 *
 * 继承 [IOException]，这样既有的 [me.rerere.ai.agent.RetryPolicy] 会把它当成网络故障重试。
 */
class StreamIdleTimeoutException(message: String) : IOException(message)

private const val WATCH_INTERVAL_MS = 5_000L

/**
 * 在 [block] 执行期间盯着「内容空闲」。
 *
 * 为什么不能用 OkHttp 的 readTimeout：SSE 的注释行（`: keep-alive`）会持续产生网络字节，
 * 连接看起来一直活着，readTimeout 永远不触发；但这些注释不会变成任何内容事件，
 * 于是界面永远停在半截，也不会抛异常去触发重试。这里只看**真正产出元素**的间隔。
 *
 * [block] 每收到一个元素就要调用一次 `onElement`。看门狗是一个独立子协程，
 * 超时直接抛 [StreamIdleTimeoutException]：结构化并发会取消 [block] 里的滞留下游，
 * 并把原始异常抛给调用方，而不是退化成 `CancellationException`——否则重试逻辑会当成用户取消。
 *
 * 两段用不同上限：还没收到第一个元素时可能只是上游在排队，给得宽一些；
 * 一旦开始产出又长时间静默，基本可确定连接已死，判定得紧一些。
 */
suspend fun <T> withIdleTimeout(
    firstElementMillis: Long,
    subsequentMillis: Long,
    block: suspend (onElement: () -> Unit) -> T,
): T = coroutineScope {
    val lastActivity = AtomicLong(System.currentTimeMillis())
    val limit = AtomicLong(firstElementMillis)
    val watchdog = launch {
        while (isActive) {
            delay(WATCH_INTERVAL_MS)
            val idle = System.currentTimeMillis() - lastActivity.get()
            if (idle >= limit.get()) {
                throw StreamIdleTimeoutException(
                    "流式响应已 ${idle / 1000} 秒没有新内容（上限 ${limit.get() / 1000} 秒），判定连接已断开",
                )
            }
        }
    }
    try {
        block {
            lastActivity.set(System.currentTimeMillis())
            limit.set(subsequentMillis)
        }
    } finally {
        watchdog.cancel()
    }
}
