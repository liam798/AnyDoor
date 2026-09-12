package com.anydoor.sample

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anydoor.AnyDoor
import com.anydoor.AnyDoorProvider
import com.anydoor.CallHandler
import com.anydoor.CallResult
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 仅在中心独立运行时验证死亡恢复，不终止测试宿主进程。 */
@RunWith(AndroidJUnit4::class)
class CenterRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @Suppress("DEPRECATION")
    fun centerDeathRequiresExplicitRegistrationAfterReconnect() {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, AnyDoorProvider::class.java), 0
        )
        assumeTrue("默认中心与测试宿主同进程，本用例需要 -PanydoorProcess=:anydoor",
            provider.processName != context.packageName)
        val hostPid = Process.myPid()
        val executions = AtomicInteger()
        val handler = CallHandler { _, arg ->
            executions.incrementAndGet()
            CallResult.DoneWith(arg)
        }
        val command = "test.center.recovery"
        AnyDoor.initialize(context)
        AnyDoor.registerHandler(command, handler)
        var oldCenter: IBinder? = null
        val died = CountDownLatch(1)
        val death = IBinder.DeathRecipient { died.countDown() }
        try {
            assertEquals("死亡前", AnyDoor.call(command, "死亡前"))
            oldCenter = centerBinder()
            oldCenter.linkToDeath(death, 0)
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val process = checkNotNull(manager.runningAppProcesses?.singleOrNull {
                it.processName == provider.processName && it.uid == Process.myUid()
            }) { "未找到样例应用自己的中心进程" }
            assertTrue(process.pid > 0)
            assertNotEquals("不能终止测试宿主", hostPid, process.pid)
            Process.killProcess(process.pid)
            assertTrue("未收到中心 Binder 死亡通知", died.await(15, TimeUnit.SECONDS))
            assertFalse(oldCenter.isBinderAlive)

            // 首次恢复调用必须由 AnyDoor 自身重新发现；测试不能提前发现新中心。
            assertNull(AnyDoor.call(command, "尚未重新注册"))
            assertEquals(1, executions.get())
            val newCenter = centerBinder()
            assertTrue(newCenter.isBinderAlive)
            assertNotEquals(oldCenter, newCenter)

            AnyDoor.registerHandler(command, handler)
            AnyDoor.registerHandler(command, handler)
            assertEquals("恢复同步", AnyDoor.call(command, "恢复同步"))
            val completed = CountDownLatch(1)
            val received = AtomicReference<Any?>()
            assertTrue(AnyDoor.callAsync(command, "恢复异步") { _, value ->
                received.set(value)
                completed.countDown()
            })
            assertTrue("恢复后的异步回调超时", completed.await(10, TimeUnit.SECONDS))
            assertEquals("恢复异步", received.get())
            assertEquals(3, executions.get())
            AnyDoor.unregisterHandler(command, handler)
            assertNull(AnyDoor.call(command, "注销后"))
            assertEquals(3, executions.get())
            assertEquals(hostPid, Process.myPid())
        } finally {
            oldCenter?.let { if (it.isBinderAlive) it.unlinkToDeath(death, 0) }
            AnyDoor.unregisterHandler(command, handler)
        }
    }

    /** 直接读取内部发现协议仅用于观测死亡，不在测试中持有稳定 Provider 依赖。 */
    @Suppress("DEPRECATION")
    private fun centerBinder(): IBinder {
        val client = checkNotNull(context.contentResolver.acquireUnstableContentProviderClient(
            Uri.parse("content://${context.packageName}.anydoor")
        )) { "中心 Provider 不可用" }
        return try {
            checkNotNull(client.call("get_call_service", null, null)?.getBinder("call_service")) {
                "中心未返回服务 Binder"
            }
        } finally {
            // release 支持项目最低 API 23。
            client.release()
        }
    }
}
