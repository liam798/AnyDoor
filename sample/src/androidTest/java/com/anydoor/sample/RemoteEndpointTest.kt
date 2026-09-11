package com.anydoor.sample

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anydoor.AnyDoor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RemoteEndpointTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withEndpoint(block: (CountDownLatch) -> Unit) {
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { disconnected.countDown() }
        }
        AnyDoor.initialize(context)
        assertTrue(context.bindService(Intent(context, RemoteEndpointService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("子进程连接超时", connected.await(15, TimeUnit.SECONDS))
            block(disconnected)
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun remoteRegistrationPayloadCallbackAndUnregistration() = withEndpoint {
        val pid = AnyDoor.call("endpoint.pid", null) as Int
        assertNotEquals(Process.myPid(), pid)
        val payload = mapOf("嵌套" to mapOf("数值" to 42), "字符串" to "任意门")
        assertEquals(payload, AnyDoor.call("endpoint.echo", payload))
        val completed = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        assertTrue(AnyDoor.callAsync("endpoint.echo", payload) { _, value ->
            result.set(value)
            completed.countDown()
        })
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        assertEquals(payload, result.get())
        AnyDoor.call("endpoint.remove", null)
        assertNull(AnyDoor.call("endpoint.echo", payload))
    }

    @Test
    fun remoteProcessCanRestartAndRegisterAgain() {
        var oldPid = 0
        withEndpoint { disconnected ->
            oldPid = AnyDoor.call("endpoint.pid", null) as Int
            assertNotEquals(Process.myPid(), oldPid)
            Process.killProcess(oldPid)
            assertTrue("未收到进程断开通知", disconnected.await(15, TimeUnit.SECONDS))
        }
        withEndpoint {
            val newPid = AnyDoor.call("endpoint.pid", null) as Int
            assertNotEquals(oldPid, newPid)
            assertEquals("恢复", AnyDoor.call("endpoint.echo", "恢复"))
        }
    }

    @Test
    fun warmedRemoteLatencySample() = withEndpoint {
        repeat(100) { assertEquals(it, AnyDoor.call("endpoint.echo", it)) }
        val nanos = LongArray(1000) {
            val start = System.nanoTime()
            assertEquals(it, AnyDoor.call("endpoint.echo", it))
            System.nanoTime() - start
        }.sorted()
        Log.i("AnyDoor基准", "热调用1000次，含断言，P50=${nanos[499] / 1000}微秒，P95=${nanos[949] / 1000}微秒，P99=${nanos[989] / 1000}微秒")
    }
}
