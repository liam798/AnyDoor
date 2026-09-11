package com.anydoor.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.anydoor.AnyDoor
import com.anydoor.CallHandler
import com.anydoor.CallResult

/** 跨进程通信示例。创建时间：2026-09-11；创建人：Codex。 */
class MainActivity : Activity() {
    private val echo = CallHandler { _, arg -> CallResult.DoneWith("任意门：$arg") }
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private lateinit var output: TextView
    private lateinit var run: Button
    private val timeout = Runnable {
        running = false
        run.isEnabled = true
        output.text = "通信超时，请重试"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnyDoor.registerHandler("sample.echo", echo)
        val padding = (24 * resources.displayMetrics.density).toInt()
        output = TextView(this).apply {
            text = "主进程 PID：${Process.myPid()}"
            textSize = 16f
        }
        run = Button(this).apply {
            text = "测试跨进程通信"
            setOnClickListener { testConnection() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@MainActivity).apply {
                text = "AnyDoor 任意门"
                textSize = 26f
            })
            addView(run)
            addView(output)
        })
    }

    private fun testConnection() {
        if (running) return
        running = true
        run.isEnabled = false
        output.text = "正在连接远程进程…"
        handler.postDelayed(timeout, 10000)
        val receiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                if (isDestroyed) return
                handler.removeCallbacks(timeout)
                running = false
                run.isEnabled = true
                output.text = if (resultCode == 0) {
                    "主进程：${Process.myPid()}\n远程进程：${resultData.getInt("remotePid")}" +
                        "\n同步返回：${resultData.getString("syncResult")}" +
                        "\n异步返回：${resultData.getString("asyncResult")}"
                } else resultData.getString("error")
            }
        }
        startService(Intent(this, RemoteService::class.java)
            .putExtra("receiver", receiver)
            .putExtra("input", "你好"))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        AnyDoor.unregisterHandler("sample.echo", echo)
        super.onDestroy()
    }
}
