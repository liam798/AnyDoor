package com.anydoor.internal

import android.os.IBinder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ConnectionTest {
    @Test
    fun deadConnectionIsReplacedAndUnavailableDiscoveryCanRetry() {
        var alive = true
        val firstBinder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, method, _ ->
            when (method.name) {
                "isBinderAlive" -> alive
                else -> null
            }
        } as IBinder
        var discoveries = 0
        var next: IBinder? = firstBinder
        val connection = AnyDoorConnection { discoveries++; next }
        val first = connection.service()
        assertSame(first, connection.service())
        assertEquals(1, discoveries)
        alive = false
        next = null
        try {
            connection.service()
            fail("不可用服务不能返回旧连接")
        } catch (_: IllegalStateException) { }
        next = AnyDoorService()
        assertNotSame(first, connection.service())
        assertEquals(3, discoveries)
    }

    @Test
    fun concurrentColdCallsDiscoverOnlyOnce() {
        var discoveries = 0
        val connection = AnyDoorConnection { discoveries++; AnyDoorService() }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val clients = executor.invokeAll(List(20) { Callable { connection.service() } })
                .map { it.get(5, TimeUnit.SECONDS) }
            clients.forEach { assertSame(clients.first(), it) }
            assertEquals(1, discoveries)
        } finally {
            executor.shutdownNow()
        }
    }
}
