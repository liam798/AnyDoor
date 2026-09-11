package com.anydoor.internal

import com.anydoor.internal.ipc.CallResultProtocol


import android.os.IBinder
import android.os.Binder
import android.os.Parcel
import com.anydoor.CallResult
import com.anydoor.internal.ipc.CallPayload
import com.anydoor.internal.ipc.ICallCallback
import com.anydoor.internal.ipc.ICallHandler
import com.anydoor.internal.ipc.IAnyDoorService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class AnyDoorServiceTest {
    private fun handler(block: () -> CallResult) = object : ICallHandler.Stub() {
        override fun onCall(id: String, arg: CallPayload?) = CallResultProtocol.encode(block())
    }

    @Test
    fun invalidRequestsDoNotRegisterOrDispatch() {
        val registry = HandlerRegistry()
        val service = AnyDoorService(registry)
        val target = handler { error("无效请求不应执行业务") }
        try {
            for (id in listOf(null, "", " ")) {
                service.registerHandler(id, target)
                service.unregisterHandler(id, target)
                assertNull(service.call(id, null))
                assertFalse(service.callAsync(id, null, null))
            }
            service.registerHandler("echo", null)
            service.unregisterHandler("echo", null)
            assertTrue(registry.snapshot("echo").isEmpty())
            assertTrue(registry.snapshot("").isEmpty())
            assertTrue(registry.snapshot(" ").isEmpty())
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun serviceProxyPreservesRegisterCallCallbackAndUnregister() {
        val service = AnyDoorService()
        // 不暴露本地接口，强制经过 Proxy、Parcel 和 Stub 分发。
        val relay = object : Binder() {
            override fun queryLocalInterface(descriptor: String): android.os.IInterface? = null

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                service.onTransact(code, data, reply, flags)
        }
        val proxy = IAnyDoorService.Stub.asInterface(relay)
        val target = handler { CallResult.DoneWith(mapOf("结果" to 42)) }
        val completed = CountDownLatch(1)
        val results = java.util.concurrent.atomic.AtomicReference<Any?>()
        try {
            proxy.registerHandler("echo", target)
            assertEquals(mapOf("结果" to 42), proxy.call("echo", null)?.value)
            assertTrue(proxy.callAsync("echo", null, object : ICallCallback.Stub() {
                override fun onResult(id: String, result: CallPayload?) {
                    results.set(result?.value)
                    completed.countDown()
                }
            }))
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(mapOf("结果" to 42), results.get())
            proxy.unregisterHandler("echo", target)
            assertNull(proxy.call("echo", null))
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun deathRemovesAllRegistrationsWithoutCallingHandler() {
        val recipients = ArrayList<IBinder.DeathRecipient>()
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> true
                "linkToDeath" -> { recipients.add(args!![0] as IBinder.DeathRecipient); null }
                "unlinkToDeath" -> recipients.remove(args!![0])
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> null
            }
        } as IBinder
        val registry = HandlerRegistry()
        registry.register("甲", ICallHandler.Proxy(binder))
        registry.register("乙", ICallHandler.Proxy(binder))
        recipients.toList().forEach { it.binderDied() }
        assertTrue(registry.snapshot("甲").isEmpty())
        assertTrue(registry.snapshot("乙").isEmpty())
        assertTrue(recipients.isEmpty())
    }

    @Test
    fun differentProxiesForSameBinderCanUnregister() {
        val registry = HandlerRegistry()
        val target = handler { CallResult.Done }
        registry.register("echo", ICallHandler.Proxy(target))
        registry.register("echo", ICallHandler.Proxy(target))
        assertEquals(1, registry.snapshot("echo").size)
        registry.remove("echo", ICallHandler.Proxy(target).asBinder())
        assertTrue(registry.snapshot("echo").isEmpty())
    }

    @Test
    fun unregisterDuringDispatchDoesNotInvalidateSnapshot() {
        val registry = HandlerRegistry()
        val last = handler { CallResult.DoneWith("完成") }
        registry.register("echo", handler {
            registry.remove("echo", last.asBinder())
            CallResult.Skip
        })
        registry.register("echo", last)
        val worker = AnyDoorService(registry)
        try {
            assertEquals("完成", worker.call("echo", null)?.value)
            assertNull(worker.call("echo", null))
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun queueIsBoundedAndRejectsAfterShutdown() {
        val registry = HandlerRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        registry.register("slow", handler {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            CallResult.Done
        })
        val worker = AnyDoorService(registry, 1)
        try {
            assertTrue(worker.callAsync("slow", null, null))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(worker.callAsync("slow", null, null))
            assertFalse(worker.callAsync("slow", null, null))
        } finally {
            release.countDown()
            worker.shutdown()
        }
        assertFalse(worker.callAsync("slow", null, null))
    }

    @Test
    fun handlerExceptionStillCompletesCallback() {
        val registry = HandlerRegistry()
        registry.register("error", handler { throw IllegalStateException("测试异常") })
        val completed = CountDownLatch(1)
        val worker = AnyDoorService(registry)
        try {
            assertTrue(worker.callAsync("error", null, object : ICallCallback.Stub() {
                override fun onResult(id: String, result: CallPayload?) {
                    if (result == null) completed.countDown()
                }
            }))
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun doneStopsDispatchWithoutCallingLaterHandlers() {
        val registry = HandlerRegistry()
        registry.register("echo", handler { CallResult.Done })
        registry.register("echo", handler { error("不应继续分发") })
        val worker = AnyDoorService(registry)
        try {
            assertNull(worker.call("echo", null))
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun callbackExceptionDoesNotBlockNextRequest() {
        val worker = AnyDoorService()
        val completed = CountDownLatch(1)
        try {
            assertTrue(worker.callAsync("echo", null, object : ICallCallback.Stub() {
                override fun onResult(id: String, result: CallPayload?) {
                    error("测试回调异常")
                }
            }))
            assertTrue(worker.callAsync("echo", null, object : ICallCallback.Stub() {
                override fun onResult(id: String, result: CallPayload?) {
                    completed.countDown()
                }
            }))
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        } finally {
            worker.shutdown()
        }
    }
}
