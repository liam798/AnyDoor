package com.anydoor.internal

import android.os.IBinder
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.DeadObjectException
import android.os.RemoteException
import com.anydoor.AnyDoorProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ConnectionTest {
    private class DiscoveryProvider : ContentProvider() {
        val service = AnyDoorService()
        var calls = 0
        var failuresRemaining = 0
        var failure: Exception = DeadObjectException()
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            assertEquals(AnyDoorProvider.METHOD_GET_SERVICE, method)
            calls++
            if (failuresRemaining-- > 0) throw failure
            return Bundle().apply { putBinder(AnyDoorProvider.KEY_SERVICE, service.asBinder()) }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?) = 0
    }

    private fun withDiscoveryProvider(block: (DiscoveryProvider, AnyDoorConnection) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        val provider = DiscoveryProvider()
        val authority = "${context.packageName}.anydoor"
        provider.attachInfo(context, ProviderInfo().apply {
            this.authority = authority
            applicationInfo = context.applicationInfo
        })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        try {
            block(provider, AnyDoorConnection(context))
        } finally {
            provider.service.shutdown()
        }
    }

    @Test
    fun providerDeathDuringDiscoveryRetriesOnceAndCachesResult() = withDiscoveryProvider { provider, connection ->
        provider.failuresRemaining = 1
        assertSame(provider.service, connection.service())
        assertEquals(2, provider.calls)
        assertSame(provider.service, connection.service())
        assertEquals(2, provider.calls)
    }

    @Test
    fun repeatedProviderDeathIsBoundedAndNextCallCanRecover() = withDiscoveryProvider { provider, connection ->
        provider.failuresRemaining = 2
        assertThrows(DeadObjectException::class.java) { connection.service() }
        assertEquals(2, provider.calls)
        assertSame(provider.service, connection.service())
        assertEquals(3, provider.calls)
    }

    @Test
    fun otherDiscoveryFailuresAreNotRetried() = withDiscoveryProvider { provider, connection ->
        for (failure in listOf(SecurityException("拒绝访问"), RemoteException("其他发现异常"))) {
            provider.failure = failure
            provider.failuresRemaining = 1
            val before = provider.calls
            assertSame(failure, assertThrows(failure.javaClass) { connection.service() })
            assertEquals(before + 1, provider.calls)
        }
        assertSame(provider.service, connection.service())
    }

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
