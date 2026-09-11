package com.anydoor

import android.os.IBinder
import com.anydoor.internal.AnyDoorConnection
import com.anydoor.internal.ipc.CallPayload
import com.anydoor.internal.ipc.CallResultProtocol
import com.anydoor.internal.ipc.ICallCallback
import com.anydoor.internal.ipc.ICallHandler
import com.anydoor.internal.ipc.IAnyDoorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy as ReflectionProxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class AnyDoorTest {
    private class Endpoint : IAnyDoorService.Stub() {
        var alive = true
        var registrations = 0
        var removed = 0
        var failRegistration = false
        var callbackWasNull = false
        var handler: ICallHandler? = null
        private val identity = ReflectionProxy.newProxyInstance(
            IBinder::class.java.classLoader, arrayOf(IBinder::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> alive
                "queryLocalInterface" -> this
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> null
            }
        } as IBinder

        override fun asBinder() = identity
        override fun call(id: String, arg: CallPayload?) = arg
        override fun callAsync(id: String, arg: CallPayload?, callback: ICallCallback?): Boolean {
            callbackWasNull = callback == null
            callback?.onResult(id, arg)
            return true
        }
        override fun registerHandler(id: String, callback: ICallHandler) {
            if (failRegistration) throw IllegalStateException("测试注册失败")
            registrations++
            handler = callback
        }
        override fun unregisterHandler(id: String, callback: ICallHandler) {
            assertSame(handler, callback)
            removed++
            handler = null
        }
    }

    private val connectionField = AnyDoor::class.java.getDeclaredField("connection").apply { isAccessible = true }
    private val bindingsField = AnyDoor::class.java.getDeclaredField("bindings").apply { isAccessible = true }

    @Before
    fun reset() {
        connectionField.set(null, null)
        (bindingsField.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun cleanup() = reset()

    @Test
    fun initializeIsLazyAndIdempotent() {
        AnyDoor.initialize(RuntimeEnvironment.getApplication())
        val connection = connectionField.get(null)
        AnyDoor.initialize(RuntimeEnvironment.getApplication())
        assertSame(connection, connectionField.get(null))
    }

    @Test(expected = IllegalStateException::class)
    fun callBeforeInitializationFails() { AnyDoor.call("echo", null) }

    @Test
    fun facadeAdaptsPayloadAndOptionalCallback() {
        val endpoint = Endpoint()
        connectionField.set(null, AnyDoorConnection { endpoint.asBinder() })
        assertEquals("参数", AnyDoor.call("echo", "参数"))
        var received: Any? = null
        assertTrue(AnyDoor.callAsync("echo", 42) { id, result ->
            assertEquals("echo", id)
            received = result
        })
        assertEquals(42, received)
        assertTrue(AnyDoor.callAsync("echo", null, null))
        assertTrue(endpoint.callbackWasNull)
        assertTrue(AnyDoor.callAsync("echo", null))
        assertTrue(endpoint.callbackWasNull)
    }

    @Test
    fun explicitRegistrationAfterReconnectReusesHandlerBinder() {
        val first = Endpoint()
        var endpoint = first
        connectionField.set(null, AnyDoorConnection { endpoint.asBinder() })
        val handler = CallHandler { _, arg -> CallResult.DoneWith(arg) }
        AnyDoor.registerHandler("echo", handler)
        AnyDoor.registerHandler("echo", handler)
        assertEquals(1, first.registrations)
        val callback = first.handler
        assertEquals(
            CallResultProtocol.encode(CallResult.DoneWith(7)),
            callback!!.onCall("echo", CallPayload(7))
        )
        first.alive = false
        endpoint = Endpoint()
        AnyDoor.call("echo", null)
        assertEquals(0, endpoint.registrations)
        AnyDoor.registerHandler("echo", handler)
        assertSame(callback, endpoint.handler)
        AnyDoor.unregisterHandler("echo", handler)
        assertEquals(1, endpoint.removed)
    }

    @Test
    fun deadRegistrationCanBeReleasedWithoutDiscovery() {
        val endpoint = Endpoint()
        var discoveries = 0
        connectionField.set(null, AnyDoorConnection { discoveries++; endpoint.asBinder() })
        val handler = CallHandler { _, _ -> CallResult.Done }
        AnyDoor.registerHandler("echo", handler)
        endpoint.alive = false
        AnyDoor.unregisterHandler("echo", handler)
        assertEquals(1, discoveries)
        assertEquals(0, endpoint.removed)
        assertTrue((bindingsField.get(null) as Map<*, *>).isEmpty())
    }

    @Test
    fun failedRegistrationDoesNotCreateLocalBinding() {
        val endpoint = Endpoint().apply { failRegistration = true }
        connectionField.set(null, AnyDoorConnection { endpoint.asBinder() })
        val handler = CallHandler { _, _ -> CallResult.Done }
        AnyDoor.registerHandler("echo", handler)
        assertTrue((bindingsField.get(null) as Map<*, *>).isEmpty())
        endpoint.failRegistration = false
        AnyDoor.registerHandler("echo", handler)
        assertEquals(1, endpoint.registrations)
    }
}
