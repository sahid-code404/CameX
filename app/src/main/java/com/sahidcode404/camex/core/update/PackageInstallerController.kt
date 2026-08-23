package com.sahidcode404.camex.core.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

sealed interface InstallLaunchResult {
    data class Started(val sessionId: Int) : InstallLaunchResult
    data object PermissionRequired : InstallLaunchResult
}

sealed interface InstallResultEvent {
    data class AwaitingUserAction(val sessionId: Int) : InstallResultEvent
    data class Installed(val sessionId: Int) : InstallResultEvent
    data class Failed(
        val sessionId: Int,
        val code: UpdateFailureCode,
        val detail: String,
    ) : InstallResultEvent
}

object InstallResultBus {
    private val mutableEvents = MutableSharedFlow<InstallResultEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<InstallResultEvent> = mutableEvents.asSharedFlow()

    fun emit(event: InstallResultEvent) {
        mutableEvents.tryEmit(event)
    }
}

class PackageInstallerController(context: Context) {
    private val appContext = context.applicationContext

    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()

    suspend fun install(apkFile: File, expectedPackageName: String): InstallLaunchResult =
        withContext(Dispatchers.IO) {
            if (!canRequestPackageInstalls()) return@withContext InstallLaunchResult.PermissionRequired
            if (!apkFile.isFile) {
                throw UpdateException(UpdateFailureCode.STORAGE, "Verified update APK is missing")
            }
            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(expectedPackageName)
                setSize(apkFile.length())
            }
            val sessionId = try {
                installer.createSession(params)
            } catch (error: Throwable) {
                throw UpdateException(UpdateFailureCode.INSTALL_FAILED, "Could not create PackageInstaller session", error)
            }
            try {
                installer.openSession(sessionId).use { session ->
                    session.openWrite("Camera-dev-ota.apk", 0L, apkFile.length()).use { output ->
                        apkFile.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                        session.fsync(output)
                    }
                    val callbackIntent = Intent(appContext, InstallResultReceiver::class.java).apply {
                        action = InstallResultReceiver.ACTION_INSTALL_RESULT
                        putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val sender = PendingIntent.getBroadcast(
                        appContext,
                        sessionId,
                        callbackIntent,
                        flags,
                    ).intentSender
                    session.commit(sender)
                }
                InstallLaunchResult.Started(sessionId)
            } catch (error: Throwable) {
                runCatching { installer.abandonSession(sessionId) }
                if (error is UpdateException) throw error
                throw UpdateException(UpdateFailureCode.INSTALL_FAILED, "Could not submit PackageInstaller session", error)
            }
        }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.take(240)
            .orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                InstallResultBus.emit(InstallResultEvent.AwaitingUserAction(sessionId))
                val confirmation = intent.confirmationIntent()
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmation) }
                        .onFailure {
                            InstallResultBus.emit(
                                InstallResultEvent.Failed(
                                    sessionId,
                                    UpdateFailureCode.INSTALL_FAILED,
                                    "Android install confirmation could not be opened",
                                ),
                            )
                        }
                } else {
                    InstallResultBus.emit(
                        InstallResultEvent.Failed(
                            sessionId,
                            UpdateFailureCode.INSTALL_FAILED,
                            "Android did not provide an install confirmation intent",
                        ),
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                InstallResultBus.emit(InstallResultEvent.Installed(sessionId))
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                InstallResultBus.emit(
                    InstallResultEvent.Failed(
                        sessionId,
                        UpdateFailureCode.INSTALL_CANCELLED,
                        message.ifBlank { "Installation was cancelled" },
                    ),
                )
            else ->
                InstallResultBus.emit(
                    InstallResultEvent.Failed(
                        sessionId,
                        UpdateFailureCode.INSTALL_FAILED,
                        message.ifBlank { "PackageInstaller failed with status $status" },
                    ),
                )
        }
    }

    private fun Intent.confirmationIntent(): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.sahidcode404.camex.action.DEV_OTA_INSTALL_RESULT"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
