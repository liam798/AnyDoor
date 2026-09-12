package com.anydoor

import android.content.Context
import android.os.DeadObjectException
import androidx.annotation.Keep
import android.util.Log
import com.anydoor.internal.AnyDoorConnection
import com.anydoor.internal.ipc.CallPayload
import com.anydoor.internal.ipc.CallResultProtocol
import com.anydoor.internal.ipc.ICallCallback
import com.anydoor.internal.ipc.ICallHandler
import com.anydoor.internal.ipc.IAnyDoorService

/**
 * 任意门客户端入口：负责调用适配和本进程处理器绑定。
 * 每个使用 SDK 的进程都应初始化；同步调用应在工作线程执行，回调不保证在主线程。
 */
@Keep
object AnyDoor {
    @Volatile
    private var connection: AnyDoorConnection? = null
    private class Binding(
        val handler: CallHandler,
        val callback: ICallHandler,
        var registeredWith: IAnyDoorService
    )
    private val bindingLock = Any()
    private val bindings = HashMap<String, MutableList<Binding>>()

    /** 幂等初始化，仅保留应用级连接依赖，不发起 IPC。 */
    @JvmStatic
    @Synchronized
    fun initialize(context: Context) {
        if (connection == null) connection = AnyDoorConnection(context)
    }

    private fun service() = checkNotNull(connection) {
        "请先调用 AnyDoor.initialize(context)"
    }.service()

    /** true 只表示成功入队。队满或投递失败返回 false；初始化及发现失败抛异常。 */
    @JvmStatic
    @JvmOverloads
    fun callAsync(callId: String, arg: Any?, callback: ((String, Any?) -> Unit)? = null): Boolean {
        val target = service()
        return try {
            val reply = if (callback == null) null else object : ICallCallback.Stub() {
                override fun onResult(callId: String, result: CallPayload?) {
                    callback(callId, result?.value)
                }
            }
            target.callAsync(callId, arg?.let(::CallPayload), reply)
        } catch (e: Exception) {
            Log.w("AnyDoor", "异步投递失败：$callId", e)
            false
        }
    }

    /** 保留现有契约：无处理器、无返回值及调用失败均可返回 null，不自动重放业务调用。 */
    @JvmStatic
    fun call(callId: String, arg: Any?): Any? {
        val target = service()
        return try {
            target.call(callId, arg?.let(::CallPayload))?.value
        } catch (e: Exception) {
            Log.w("AnyDoor", "同步调用失败：$callId", e)
            null
        }
    }

    /** 同实例重复注册幂等；服务重启后再次显式注册可恢复绑定，不自动恢复。 */
    @JvmStatic
    fun registerHandler(callId: String, handler: CallHandler) {
        val target = service()
        if (callId.isBlank()) return
        synchronized<Unit>(bindingLock) {
            try {
                val existing = bindings[callId]?.firstOrNull { it.handler === handler }
                if (existing != null) {
                    if (existing.registeredWith.asBinder() != target.asBinder()) {
                        target.registerHandler(callId, existing.callback)
                        existing.registeredWith = target
                    }
                    return
                }
                val callback = object : ICallHandler.Stub() {
                    override fun onCall(callId: String, arg: CallPayload?): CallPayload? =
                        CallResultProtocol.encode(handler.onCall(callId, arg?.value))
                }
                target.registerHandler(callId, callback)
                bindings.getOrPut(callId) { ArrayList() }.add(Binding(handler, callback, target))
            } catch (e: Exception) {
                Log.w("AnyDoor", "处理器注册失败：$callId", e)
            }
        }
    }

    /** 仅注销对应实例。旧服务已死亡时直接释放本地绑定，不为注销启动新服务。 */
    @JvmStatic
    fun unregisterHandler(callId: String, handler: CallHandler) {
        checkNotNull(connection) { "请先调用 AnyDoor.initialize(context)" }
        synchronized<Unit>(bindingLock) {
            val registered = bindings[callId] ?: return
            val binding = registered.firstOrNull { it.handler === handler } ?: return
            try {
                if (binding.registeredWith.asBinder().isBinderAlive) {
                    binding.registeredWith.unregisterHandler(callId, binding.callback)
                }
            } catch (e: Exception) {
                Log.w("AnyDoor", "处理器注销失败：$callId", e)
                // 存活检查与事务之间也可能死亡；仅保留仍可重试的绑定。
                if (e !is DeadObjectException && binding.registeredWith.asBinder().isBinderAlive) return
            }
            registered.remove(binding)
            if (registered.isEmpty()) bindings.remove(callId)
        }
    }
}
