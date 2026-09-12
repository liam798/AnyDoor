package com.anydoor.internal

import android.net.Uri
import android.content.Context
import android.os.IBinder
import android.os.DeadObjectException
import com.anydoor.internal.ipc.IAnyDoorService
import com.anydoor.AnyDoorProvider

/** AnyDoor 服务连接；死亡后按需重新发现，不重放在途调用或恢复注册。 */
internal class AnyDoorConnection(private val discover: () -> IBinder?) {
    constructor(context: Context) : this(serviceDiscovery(context.applicationContext))

    private data class Connection(val binder: IBinder, val service: IAnyDoorService)
    @Volatile private var current: Connection? = null

    fun service(): IAnyDoorService {
        current?.let { if (it.binder.isBinderAlive) return it.service }
        return synchronized(this) {
            current?.let { if (it.binder.isBinderAlive) return@synchronized it.service }
            current = null
            val binder = checkNotNull(discover()) { "AnyDoor 服务不可用，请检查 Provider 声明" }
            check(binder.isBinderAlive) { "AnyDoor 服务已退出" }
            val service = checkNotNull(IAnyDoorService.Stub.asInterface(binder))
            current = Connection(binder, service)
            service
        }
    }
}

private fun serviceDiscovery(context: Context): () -> IBinder? {
    val resolver = context.contentResolver
    val uri = Uri.parse("content://${context.packageName}.anydoor")
    fun discover(): IBinder? {
        val client = resolver.acquireUnstableContentProviderClient(uri) ?: return null
        return try {
            client.call(AnyDoorProvider.METHOD_GET_SERVICE, null, null)
                ?.getBinder(AnyDoorProvider.KEY_SERVICE)
        } finally {
            // 最低 API 23；不持有稳定 Provider 依赖，避免中心死亡连带终止调用进程。
            @Suppress("DEPRECATION")
            client.release()
        }
    }
    return {
        try {
            discover()
        } catch (_: DeadObjectException) {
            // 只重试无副作用的 Binder 发现一次，不重放任何业务请求。
            discover()
        }
    }
}
