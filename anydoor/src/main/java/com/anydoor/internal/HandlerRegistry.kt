package com.anydoor.internal

import android.os.IBinder
import android.os.RemoteException
import com.anydoor.internal.ipc.ICallHandler

/** 按 Binder 身份注册；执行用户代码时不持有注册表锁。 */
internal class HandlerRegistry {
    private class Record(val handler: ICallHandler, val death: IBinder.DeathRecipient)
    private val handlers = HashMap<String, LinkedHashMap<IBinder, Record>>()

    @Synchronized
    fun register(id: String, handler: ICallHandler) {
        val binder = handler.asBinder()
        if (handlers[id]?.containsKey(binder) == true) return
        val death = object : IBinder.DeathRecipient {
            override fun binderDied() = remove(id, binder, this)
        }
        binder.linkToDeath(death, 0)
        if (!binder.isBinderAlive) {
            binder.unlinkToDeath(death, 0)
            throw RemoteException("处理器进程已退出")
        }
        handlers.getOrPut(id) { LinkedHashMap() }[binder] = Record(handler, death)
    }

    @Synchronized
    fun remove(id: String, binder: IBinder) {
        remove(id, binder, null)
    }

    @Synchronized
    private fun remove(id: String, binder: IBinder, expectedDeath: IBinder.DeathRecipient?) {
        val entries = handlers[id] ?: return
        val record = entries[binder] ?: return
        // 延迟到达的旧死亡通知不能删除同一 Binder 的新注册。
        if (expectedDeath != null && record.death !== expectedDeath) return
        entries.remove(binder)
        if (entries.isEmpty()) handlers.remove(id)
        binder.unlinkToDeath(record.death, 0)
    }

    @Synchronized
    fun snapshot(id: String): List<ICallHandler> =
        handlers[id]?.values?.map { it.handler } ?: emptyList()
}
