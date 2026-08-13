// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.yuzu.yuzu_emu.features.input.NativeInput
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.content.res.Configuration
import android.os.LocaleList
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.utils.DirectoryInitialization
import org.yuzu.yuzu_emu.utils.DocumentsTree
import org.yuzu.yuzu_emu.utils.GpuDriverHelper
import org.yuzu.yuzu_emu.utils.Log
import org.yuzu.yuzu_emu.utils.PowerStateUpdater
import org.yuzu.yuzu_emu.utils.ControllerNavigationGlobalHook
import java.util.Locale

fun Context.getPublicFilesDir(): File = getExternalFilesDir(null) ?: filesDir

class YuzuApplication : Application() {
    private fun createNotificationChannels() {
        val name: CharSequence = getString(R.string.app_notification_channel_name)
        val description = getString(R.string.app_notification_channel_description)
        val foregroundService = NotificationChannel(
            getString(R.string.app_notification_channel_id),
            name,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        foregroundService.description = description
        foregroundService.setSound(null, null)
        foregroundService.vibrationPattern = null

        val noticeChannel = NotificationChannel(
            getString(R.string.notice_notification_channel_id),
            getString(R.string.notice_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        noticeChannel.description = getString(R.string.notice_notification_channel_description)
        noticeChannel.setSound(null, null)

        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(noticeChannel)
        notificationManager.createNotificationChannel(foregroundService)
    }

    /**
     * Write the crash to a file before the process dies.
     *
     * WHY THIS EXISTS
     *   Seven builds have died as a black screen with nothing to go on. The
     *   user has no adb, so logcat is out of reach, and every diagnosis so far
     *   has been a guess costing one build each.
     *
     *   Android's default handler prints the stack trace to logcat and exits.
     *   This one writes it somewhere reachable from the phone's own file
     *   manager first, then hands over to the default handler so the crash
     *   still behaves exactly as before.
     *
     *   Written to getExternalFilesDir - the app's own folder on shared
     *   storage. No permission is needed for it, on any Android version, and
     *   it survives the process dying. Path:
     *     Android/data/dev.eden.eden_emulator/files/symbiosis-crash.txt
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = java.io.File(dir, "symbiosis-crash.txt")
                val text = StringBuilder()
                text.append("Symbiosis crash report\n")
                text.append("время: ").append(java.util.Date().toString()).append('\n')
                text.append("поток: ").append(thread.name).append('\n')
                text.append("устройство: ")
                    .append(android.os.Build.MANUFACTURER).append(' ')
                    .append(android.os.Build.MODEL)
                    .append(", Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n")

                // The whole chain: the useful line is often in a cause, not in
                // the exception that was thrown.
                var e: Throwable? = error
                var depth = 0
                while (e != null && depth < 8) {
                    text.append(if (depth == 0) "ИСКЛЮЧЕНИЕ: " else "ПРИЧИНА: ")
                    text.append(e.javaClass.name).append(": ").append(e.message).append('\n')
                    e.stackTrace.take(24).forEach {
                        text.append("    at ").append(it.toString()).append('\n')
                    }
                    text.append('\n')
                    e = e.cause
                    depth++
                }
                file.writeText(text.toString())
                android.util.Log.e("Symbiosis", "crash written to ${file.absolutePath}")
            } catch (ignored: Throwable) {
                // Never let the reporter replace the crash it is reporting.
            }
            // ── Show it, do not just file it ────────────────────────
            //
            // The file was the right idea and still writes, but it asks the
            // user to go hunting through Android/data with a file manager -
            // and if they cannot find it, we both learn nothing. This puts
            // the first line of the failure on the screen instead, long
            // enough to read or photograph, before the process goes.
            //
            // A Toast from a dying process is not guaranteed to render, so
            // the message is also put in the crash file above; between the
            // two, one of them reaches me.
            try {
                val first = error.stackTrace.firstOrNull()?.toString() ?: "?"
                var root: Throwable = error
                while (root.cause != null) root = root.cause!!
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        this,
                        "Symbiosis: " + root.javaClass.simpleName + "\n" +
                            (root.message ?: "") + "\n" + first,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                // Give the toast a moment to actually appear.
                Thread.sleep(1200)
            } catch (ignored: Throwable) {
            }

            // Hand back to Android so behaviour is otherwise unchanged.
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // First thing, before anything else can throw.
        installCrashLogger()
        application = this
        documentsTree = DocumentsTree()
        DirectoryInitialization.start()

        // Initialize Freedreno config BEFORE loading native library
        // This ensures GPU driver environment variables are set before adrenotools initializes
        GpuDriverHelper.initializeFreedrenoConfigEarly()

        NativeLibrary.playTimeManagerInit()
        GpuDriverHelper.initializeDriverParameters()
        NativeInput.reloadInputDevices()
        NativeLibrary.logDeviceInfo()
        PowerStateUpdater.start()
        Log.logDeviceInfo()
        ControllerNavigationGlobalHook.install(this)

        createNotificationChannels()
    }

    companion object {
        var documentsTree: DocumentsTree? = null
        lateinit var application: YuzuApplication

        val appContext: Context
            get() = application.applicationContext

        private val LANGUAGE_CODES = arrayOf(
            "system", "en", "es", "fr", "de", "it", "pt", "pt-BR", "ru", "ja", "ko",
            "zh-CN", "zh-TW", "pl", "cs", "nb", "hu", "uk", "vi", "id", "ar", "ckb", "fa", "he", "sr"
        )

        fun applyLanguage(context: Context): Context {
            val languageIndex = IntSetting.APP_LANGUAGE.getInt()
            val langCode = if (languageIndex in LANGUAGE_CODES.indices) {
                LANGUAGE_CODES[languageIndex]
            } else {
                "system"
            }

            if (langCode == "system") {
                return context
            }

            val locale = when {
                langCode.contains("-") -> {
                    val parts = langCode.split("-")
                    Locale.Builder().setLanguage(parts[0]).setRegion(parts[1]).build()
                }
                else -> Locale.Builder().setLanguage(langCode).build()
            }

            Locale.setDefault(locale)

            val config = Configuration(context.resources.configuration)
            config.setLocales(LocaleList(locale))

            return context.createConfigurationContext(config)
        }
    }
}
