package com.kvita.diskmapper.ui

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Environment
import com.kvita.diskmapper.BuildConfig
import com.kvita.diskmapper.shizuku.IShizukuCleanerService
import com.kvita.diskmapper.shizuku.ShizukuCleanerUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
        private const val BIND_TIMEOUT_MS = 15_000L
    }
    private val serviceCallMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Runs the blocking Binder calls. Deliberately not a child of the caller's
     * job: a wedged remote call cannot be interrupted, so on timeout we abandon
     * it here instead of letting it hold [serviceCallMutex] forever.
     */
    private val rpcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val closed = AtomicBoolean(false)

    /** Stops accepting new calls; abandoned in-flight ones end on their own. */
    fun close() {
        if (closed.compareAndSet(false, true)) rpcScope.cancel()
    }

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
            service.scanPaths(Environment.getExternalStorageDirectory().absolutePath, telegramOnly, maxItems)
        }
    }

    suspend fun deleteFile(context: Context, path: String): Boolean {
        return withService(context) { service ->
            service.deleteFile(path)
        }
    }

    suspend fun diagnostics(context: Context): String {
        return withService(context) { service ->
            service.diagnostics(Environment.getExternalStorageDirectory().absolutePath)
        }
    }

    suspend fun trimCaches(context: Context): String {
        // Trimming caches on a full disk can take tens of seconds.
        return withService(context, timeoutMs = 120_000L) { service ->
            service.trimCaches()
        }
    }

    private suspend fun <T> withService(
        context: Context,
        timeoutMs: Long = 15000L,
        block: (IShizukuCleanerService) -> T
    ): T {
        check(!closed.get()) { "ShizukuBridge is closed" }
        return serviceCallMutex.withLock {
            withServiceInternal(context, timeoutMs, block)
        }
    }

    private suspend fun <T> withServiceInternal(
        context: Context,
        timeoutMs: Long,
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

        val unbound = AtomicBoolean(false)
        var boundConnection: ServiceConnection? = null
        var call: Deferred<T>? = null
        try {
            // Bind on the main callback, then run [block] off the main thread.
            // Binding and the call get separate deadlines so a long-running
            // call (trimCaches) does not inherit a short bind timeout.
            val service = withTimeout(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine<IShizukuCleanerService> { continuation ->
                    val consumed = AtomicBoolean(false)
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (!consumed.compareAndSet(false, true)) return
                            val service = IShizukuCleanerService.Stub.asInterface(binder)
                            if (service == null) {
                                scheduleUnbind(args, this, unbound)
                                continuation.resumeWithException(IllegalStateException("Shizuku service bind failed"))
                                return
                            }
                            continuation.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                        }
                    }
                    boundConnection = connection

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
            val deferred = rpcScope.async { block(service) }
            call = deferred
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            call?.cancel()
            // Unbind outside the callback stack to avoid CME in Shizuku internals.
            boundConnection?.let { scheduleUnbind(args, it, unbound) }
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
