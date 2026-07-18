package org.flossware.hotspot.client.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports captured logs to a shareable text file.
 *
 * The exported file includes a device-info header, current connection state,
 * and all buffered log entries. Sensitive data (Wi-Fi passwords, SOCKS5
 * credentials) is stripped before writing.
 */
object LogExporter {

    private val SENSITIVE_PATTERNS = listOf(
        Regex("""(?i)(password|passphrase|passwd|psk)\s*[=:]\s*\S+""")
            to { m: MatchResult -> "${m.groupValues[1]}=***" },
        Regex("""(?i)(username|user)\s*[=:]\s*'[^']*'""")
            to { m: MatchResult -> "${m.groupValues[1]}='***'" },
        Regex("""(?i)(username|user)\s*[=:]\s*\S+""")
            to { m: MatchResult -> "${m.groupValues[1]}=***" },
        Regex("""P:[^;]+""")
            to { _: MatchResult -> "P:***" },
        Regex("""(?i)(credential|secret|token|api[_-]?key)\s*[=:]\s*\S+""")
            to { m: MatchResult -> "${m.groupValues[1]}=***" },
        Regex("""(?i)(auth\w*)\s+(succeeded|failed)\s+for\s+user\s+'[^']*'""")
            to { m: MatchResult -> "${m.groupValues[1]} ${m.groupValues[2]} for user '***'" },
    )

    /**
     * Removes sensitive data from a log line.
     */
    internal fun sanitize(text: String): String {
        var result = text
        for ((pattern, replacement) in SENSITIVE_PATTERNS) {
            result = pattern.replace(result) { replacement(it) }
        }
        return result
    }

    /**
     * Writes buffered logs to a file in the app's cache directory and returns
     * a content [Uri] suitable for sharing via [FileProvider].
     *
     * @param context       Application or Activity context.
     * @param connectionInfo Pre-formatted string describing the current connection state.
     * @return A content URI for the log file, or `null` on failure.
     */
    fun export(context: Context, connectionInfo: String): Uri? {
        return try {
            val entries = LogCollector.getEntries()
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val logDir = File(context.cacheDir, "logs")
            logDir.mkdirs()

            // Clean up old exports, keeping the 5 most recent
            logDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }

            val logFile = File(logDir, "hotspot_client_logs_$timestamp.txt")

            @Suppress("DEPRECATION")
            val versionCode = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    pInfo.versionCode.toLong()
                }
            } catch (_: Exception) {
                -1L
            }

            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }

            val header = buildString {
                appendLine("=== FlossWare Hotspot Client Debug Log ===")
                appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())}")
                appendLine("App: ${context.applicationInfo.loadLabel(context.packageManager)} v$versionName (build $versionCode)")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Architecture: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
                appendLine()
                appendLine("--- Connection State ---")
                appendLine(connectionInfo)
                appendLine()
                appendLine("--- Logs (${entries.size} entries) ---")
            }

            logFile.writeText(header)
            logFile.appendText(entries.joinToString("\n") { sanitize(it.format()) })
            logFile.appendText("\n")

            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, logFile)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Exports logs and opens the Android ShareSheet so the user can send
     * the file via email, messaging apps, etc.
     *
     * @param context       Application or Activity context.
     * @param connectionInfo Pre-formatted string describing the current connection state.
     */
    fun shareLogs(context: Context, connectionInfo: String) {
        val uri = export(context, connectionInfo) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FlossWare Hotspot Client Debug Logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, null)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
