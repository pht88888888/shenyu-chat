package com.shenyu.chat

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程入口。
 *
 * 除了常规初始化，这里挂一个崩溃记录器：未捕获异常先落盘再交给系统默认处理。
 * 真机闪退时用户可以在「设置 → 数据管理 → 崩溃日志」里看到最近几次的堆栈，
 * 不用连电脑抓 logcat。
 */
class ShenyuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(throwable, thread) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun recordCrash(throwable: Throwable, thread: Thread) {
        val dir = File(filesDir, "crash").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val text = buildString {
            appendLine("时间：$stamp")
            appendLine("线程：${thread.name}")
            appendLine("版本：${packageManager.getPackageInfo(packageName, 0).versionName}")
            appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
            appendLine()
            val writer = StringWriter()
            PrintWriter(writer).use { throwable.printStackTrace(it) }
            append(writer.toString())
        }
        File(dir, "crash-$stamp.txt").writeText(text)
        // 只保留最近 5 份，避免无限增长
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { runCatching { it.delete() } }
    }

    companion object {
        /** 读取最近的崩溃记录（新的在前）。 */
        fun recentCrashes(filesDir: File, limit: Int = 5): List<Pair<String, String>> {
            val dir = File(filesDir, "crash")
            if (!dir.isDirectory) return emptyList()
            return dir.listFiles()
                ?.sortedByDescending { it.name }
                ?.take(limit)
                ?.mapNotNull { file -> runCatching { file.name to file.readText() }.getOrNull() }
                ?: emptyList()
        }
    }
}
