package com.anydoor.sample

import android.app.Application
import com.anydoor.AnyDoor

/** 创建时间：2026-09-11；创建人：Codex。 */
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AnyDoor.initialize(this)
    }
}
