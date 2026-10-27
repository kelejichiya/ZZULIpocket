package com.zzupocket.app

import android.app.Application
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.work.EnergySamplingWorker

class NieQingApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)

        // 电量定时采样：学校不给历史用电量，只能靠按点采样差分算（尽力而为）
        EnergySamplingWorker.schedule(this)
    }
}
