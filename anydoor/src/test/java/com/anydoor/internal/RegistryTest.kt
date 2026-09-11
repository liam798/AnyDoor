package com.anydoor.internal

import android.os.IBinder
import android.os.RemoteException
import com.anydoor.internal.ipc.ICallHandler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class RegistryTest {
    private class Endpoint {
        var alive = true
        var rejectLink = false
        val recipients = ArrayList<IBinder.DeathRecipient>()
        val binder = Proxy.newProxyInstance(
            IBinder::class.java.classLoader, arrayOf(IBinder::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> alive
                "linkToDeath" -> {
                    if (rejectLink) throw RemoteException("注册失败")
                    recipients.add(args!![0] as IBinder.DeathRecipient)
                    null
                }
                "unlinkToDeath" -> recipients.remove(args!![0])
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> null
            }
        } as IBinder
        val handler = ICallHandler.Proxy(binder)
    }

    @Test
    fun delayedDeathDoesNotRemoveNewRegistration() {
        val registry = HandlerRegistry()
        val endpoint = Endpoint()
        registry.register("echo", endpoint.handler)
        val oldDeath = endpoint.recipients.single()
        registry.remove("echo", endpoint.binder)
        registry.register("echo", endpoint.handler)
        oldDeath.binderDied()
        assertEquals(listOf(endpoint.handler), registry.snapshot("echo"))
        assertEquals(1, endpoint.recipients.size)
        endpoint.recipients.single().binderDied()
        assertTrue(registry.snapshot("echo").isEmpty())
        assertTrue(endpoint.recipients.isEmpty())
    }

    @Test
    fun deathDuringRegistrationDoesNotLeaveRecordOrRecipient() {
        val registry = HandlerRegistry()
        val endpoint = Endpoint().apply { alive = false }
        assertThrows(RemoteException::class.java) { registry.register("echo", endpoint.handler) }
        assertTrue(registry.snapshot("echo").isEmpty())
        assertTrue(endpoint.recipients.isEmpty())
        endpoint.alive = true
        registry.register("echo", endpoint.handler)
        assertEquals(1, registry.snapshot("echo").size)
    }

    @Test
    fun failedLinkCanBeRetried() {
        val registry = HandlerRegistry()
        val endpoint = Endpoint().apply { rejectLink = true }
        assertThrows(RemoteException::class.java) { registry.register("echo", endpoint.handler) }
        assertTrue(registry.snapshot("echo").isEmpty())
        endpoint.rejectLink = false
        registry.register("echo", endpoint.handler)
        assertEquals(1, registry.snapshot("echo").size)
        assertEquals(1, endpoint.recipients.size)
    }

    @Test
    fun concurrentRegisterAndRemoveLeaveNoRecordAfterFinalRemoval() {
        val registry = HandlerRegistry()
        val endpoint = Endpoint()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..4).map {
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(200) {
                        registry.register("echo", endpoint.handler)
                        assertTrue(registry.snapshot("echo").size <= 1)
                        registry.remove("echo", endpoint.binder)
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            registry.remove("echo", endpoint.binder)
            assertTrue(registry.snapshot("echo").isEmpty())
            assertTrue(endpoint.recipients.isEmpty())
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
