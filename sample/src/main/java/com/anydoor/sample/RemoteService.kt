package com.anydoor.sample

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import com.anydoor.AnyDoor

/** 独立进程测试端。创建时间：2026-09-11；创建人：Codex。 */
class RemoteService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val receiver = intent?.getParcelableExtra<ResultReceiver>("receiver")
        Thread {
            try {
                val value = intent?.getStringExtra("input")
                val result = AnyDoor.call("sample.echo", value)
                val accepted = AnyDoor.callAsync("sample.echo", value) { _, asyncResult ->
                    receiver?.send(0, Bundle().apply {
                        putInt("remotePid", Process.myPid())
                        putString("input", value)
                        putString("syncResult", result as? String)
                        putString("asyncResult", asyncResult as? String)
                    })
                    stopSelf(startId)
                }
                check(accepted) { "异步命令投递失败" }
            } catch (e: Exception) {
                receiver?.send(1, Bundle().apply { putString("error", e.toString()) })
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }
}
