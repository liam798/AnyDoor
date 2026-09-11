package com.anydoor.sample

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Process
import com.anydoor.AnyDoor
import com.anydoor.CallHandler
import com.anydoor.CallResult

/** 用绑定生命周期承载子进程处理器，避免异步调用未完成就停止服务。 */
class RemoteEndpointService : Service() {
    private val echo = CallHandler { _, arg -> CallResult.DoneWith(arg) }
    private val pid = CallHandler { _, _ -> CallResult.DoneWith(Process.myPid()) }
    private val remove = CallHandler { _, _ ->
        AnyDoor.unregisterHandler("endpoint.echo", echo)
        CallResult.Done
    }
    override fun onCreate() {
        super.onCreate()
        AnyDoor.registerHandler("endpoint.echo", echo)
        AnyDoor.registerHandler("endpoint.pid", pid)
        AnyDoor.registerHandler("endpoint.remove", remove)
    }

    override fun onBind(intent: Intent?) = Binder()

    override fun onDestroy() {
        AnyDoor.unregisterHandler("endpoint.echo", echo)
        AnyDoor.unregisterHandler("endpoint.pid", pid)
        AnyDoor.unregisterHandler("endpoint.remove", remove)
        super.onDestroy()
    }
}
