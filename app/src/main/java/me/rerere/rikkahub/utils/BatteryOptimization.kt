package me.rerere.rikkahub.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 系统是否已经把这个应用放进 Doze 白名单。
 *
 * 不在白名单时，熄屏静止几分钟后系统会限制网络访问：SSE 连接不会立刻断，
 * 但也不再有数据回来，表现为「进度卡住、通知还在」。
 */
fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return power.isIgnoringBatteryOptimizations(packageName)
}

/**
 * 直接弹出「不受电池优化限制」的授权请求。
 *
 * 部分厂商 ROM 没有这个系统页面，回退到应用详情页，让用户在电池设置里自己找。
 * 返回 true 表示至少拉起了一个页面。
 */
@SuppressLint("BatteryLife")
fun Context.requestIgnoreBatteryOptimizations(): Boolean {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:$packageName"))
    if (runCatching { startActivity(direct) }.isSuccess) return true
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$packageName"))
    return runCatching { startActivity(details) }.isSuccess
}
