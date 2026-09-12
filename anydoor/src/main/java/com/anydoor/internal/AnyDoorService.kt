package com.anydoor.internal

import android.os.RemoteException
import android.util.Log
import com.anydoor.CallResult
import com.anydoor.internal.ipc.CallPayload
import com.anydoor.internal.ipc.CallResultProtocol
import com.anydoor.internal.ipc.ICallCallback
import com.anydoor.internal.ipc.ICallHandler
import com.anydoor.internal.ipc.IAnyDoorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** AnyDoor 服务端入口；当前提供同步分发和有界串行异步调用。 */
internal class AnyDoorService(
    private val registry: HandlerRegistry = HandlerRegistry(),
    capacity: Int = 256
) : IAnyDoorService.Stub() {
    private val executor = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(capacity),
        { task -> Thread(task, "AnyDoor-调用") },
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    override fun callAsync(id: String?, arg: CallPayload?, callback: ICallCallback?): Boolean {
        if (id.isNullOrBlank()) return false
        return try {
            executor.execute {
                val result = try {
                    call(id, arg)
                } catch (e: Exception) {
                    Log.w("AnyDoor", "异步调用失败：$id", e)
                    null
                }
                try {
                    callback?.onResult(id, result)
                } catch (e: Exception) {
                    Log.w("AnyDoor", "回调失败：$id", e)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun call(id: String?, arg: CallPayload?): CallPayload? {
        if (id.isNullOrBlank()) return null
        for (handler in registry.snapshot(id)) {
            try {
                when (val result = CallResultProtocol.decode(handler.onCall(id, arg))) {
                    CallResult.Skip -> Unit
                    CallResult.Done -> return null
                    is CallResult.DoneWith -> return CallPayload(result.value)
                }
            } catch (e: RemoteException) {
                if (!handler.asBinder().isBinderAlive) registry.remove(id, handler.asBinder())
                // 通信失败不能证明业务未执行，不向下一个处理器重复投递。
                throw e
            }
        }
        return null
    }

    override fun registerHandler(id: String?, handler: ICallHandler?) {
        if (id.isNullOrBlank() || handler == null) return
        registry.register(id, handler)
    }

    override fun unregisterHandler(id: String?, handler: ICallHandler?) {
        if (id.isNullOrBlank() || handler == null) return
        registry.remove(id, handler.asBinder())
    }

    internal fun shutdown() = executor.shutdown()
}
