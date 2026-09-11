package com.example.ads.api

import com.anydoor.AnyDoor

/** APP 侧源码 API；仅同步查询，不加载或展示广告。 */
object AdsApi {
    private const val IS_READY = "example.ads.v1.isReady"

    /** 在工作线程调用；没有有效响应时抛异常，不把通信失败当作未就绪。 */
    @JvmStatic
    fun isReady(placement: String): Boolean {
        require(placement.isNotBlank()) { "广告位不能为空白" }
        val response = AnyDoor.call(IS_READY, placement)
            ?: error("广告查询未获得有效响应")
        return response as? Boolean
            ?: error("广告查询结果类型不符合协议")
    }
}
