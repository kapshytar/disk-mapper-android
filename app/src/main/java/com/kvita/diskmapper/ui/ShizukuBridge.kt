package com.kvita.diskmapper.ui

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.kvita.diskmapper.BuildConfig
import com.kvita.diskmapper.shizuku.IShizukuCleanerService
import com.kvita.diskmapper.shizuku.ShizukuCleanerUserService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuBridge {
    companion object {
        const val REQUEST_CODE = 9901
    }
    private val serviceCallMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class PermissionState {
        READY,
        SHIZUKU_NOT_RUNNING,
        PERMISSION_REQUESTED,
        PERMISSION_DENIED
    }

    fun canUseWithoutRequest(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.isPreV11()) return false
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    fun ensurePermission(): PermissionState {
        if (!Shizuku.pingBinder()) return PermissionState.SHIZUKU_NOT_RUNNING
        if (Shizuku.isPreV11()) return PermissionState.SHIZUKU_NOT_RUNNING

        return when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> PermissionState.READY
            Shizuku.shouldShowRequestPermissionRationale() -> PermissionState.PERMISSION_DENIED
            else -> {
                Shizuku.requestPermission(REQUEST_CODE)
                PermissionState.PERMISSION_REQUESTED
            }
        }
    }

    suspend fun scanAndroidPrivate(context: Context, telegramOnly: Boolean, maxItems: Int = 5000): String {
        return withService(context) { service ->
            service.scanPaths("/storage/emulated/0", telegramOnly, maxItems)
        }
    }

    suspend fun deleteFile(context: Context, path: String): Boolean {
        return withService(context) { service ->
            service.deleteFile(path)
        }
    }

    suspend fun diagnostics(context: Context): String {
        return withService(context) { service ->
            service.diagnostics()
        }
    }

    suspend fun diskStats(context: Context): String {
        return withService(context) { service ->
            service.diskStats()
        }
    }

    private suspend fun <T> withService(
        context: Context,
        block: (IShizukuCleanerService) -> T
    ): T {
        return serviceCallMutex.withLock {
            withServiceInternal(context, block)
        }
    }

    private suspend fun <T> withServiceInternal(
        context: Context,
        block: (IShizukuCleanerService) -> T
    ): T {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuCleanerUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("cleaner")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("diskmapper-cleaner")

        return withTimeout(15000L) {
            suspendCancellableCoroutine { continuation ->
                val consumed = AtomicBoolean(false)
                val unbound = AtomicBoolean(false)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (!consumed.compareAndSet(false, true)) return
                        val service = IShizukuCleanerService.Stub.asInterface(binder)
                        if (service == null) {
                            scheduleUnbind(args, this, unbound)
                            continuation.resumeWithException(IllegalStateException("Shizuku service bind failed"))
                            return
                        }
                        try {
                            val result = block(service)
                            continuation.resume(result)
                        } catch (t: Throwable) {
                            continuation.resumeWithException(t)
                        } finally {
                            // Unbind outside the callback stack to avoid CME in Shizuku internals.
                            scheduleUnbind(args, this, unbound)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                    }
                }

                continuation.invokeOnCancellation {
                    scheduleUnbind(args, connection, unbound)
                }

                runCatching {
                    Shizuku.bindUserService(args, connection)
                }.onFailure {
                    consumed.set(true)
                    continuation.resumeWithException(it)
                }
            }
        }
    }

    private fun scheduleUnbind(
        args: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
        unbound: AtomicBoolean
    ) {
        if (!unbound.compareAndSet(false, true)) return
        mainHandler.post {
            runCatching {
                Shizuku.unbindUserService(args, connection, true)
            }
        }
    }
}
