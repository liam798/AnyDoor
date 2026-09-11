package com.anydoor.internal

import android.net.Uri
import android.content.Context
import android.os.IBinder
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
    return {
        resolver.call(uri, AnyDoorProvider.METHOD_GET_SERVICE, null, null)
            ?.getBinder(AnyDoorProvider.KEY_SERVICE)
    }
}
