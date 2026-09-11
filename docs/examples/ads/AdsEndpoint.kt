package com.example.ads.internal

import com.anydoor.AnyDoor
import com.anydoor.CallHandler
import com.anydoor.CallResult

/**
 * SDK 内部接入参考，类名和组织方式仅作演示。
 * 状态查询必须线程安全、快速返回，不能在此触发广告加载。
 */
class AdsEndpoint(private val queryReady: (String) -> Boolean) {
    private val handler = CallHandler { _, arg ->
        require(arg is String && arg.isNotBlank()) { "广告查询参数不符合协议" }
        CallResult.DoneWith(queryReady(arg))
    }

    /** 由 SDK 生命周期调用；AnyDoor 当前没有注册成功确认返回值。 */
    fun register() = AnyDoor.registerHandler(IS_READY, handler)

    fun unregister() = AnyDoor.unregisterHandler(IS_READY, handler)

    private companion object {
        const val IS_READY = "example.ads.v1.isReady"
    }
}
