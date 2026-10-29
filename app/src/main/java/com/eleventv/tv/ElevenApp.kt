package com.eleventv.tv

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.eleventv.tv.data.repo.LocalRepository
import com.eleventv.tv.data.scrape.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用容器。
 *
 * 全局只在这里创建一次 [LocalRepository]：扫描、刮削、播放记录必须共享同一份状态，
 * 否则一边扫描一边播放时，两处看到的媒体库会对不上。
 */
class ElevenApp : Application() {

    lateinit var repository: LocalRepository
        private set

    /** 应用级作用域：跟随进程存活，不随 Activity 销毁而取消 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        repository = LocalRepository(filesDir, appScope)
        appScope.launch { repository.init() }

        // 海报域名与刮削必须共用同一个客户端（共用 DoH 解析缓存），
        // 否则刮削拿到了海报地址、列表却一片灰。
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient { Http.client }
                .crossfade(true)
                .build(),
        )
    }
}
