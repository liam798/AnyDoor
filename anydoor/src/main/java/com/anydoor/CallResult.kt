package com.anydoor

import androidx.annotation.Keep

@Keep
sealed interface CallResult {

    /**
     * 当前处理器不处理该命令，继续传递给下一个处理器。
     */
    @Keep
    data object Skip : CallResult

    /**
     * 当前处理器已消费该命令，但没有返回内容。
     */
    @Keep
    data object Done : CallResult

    /**
     * 当前处理器已消费该命令，并返回结果。
     */
    @Keep
    data class DoneWith(val value: Any?) : CallResult

    companion object {
        @JvmStatic
        fun skip(): CallResult = Skip

        @JvmStatic
        fun done(): CallResult = Done

        @JvmStatic
        fun doneWith(value: Any?): CallResult = DoneWith(value)
    }
}
