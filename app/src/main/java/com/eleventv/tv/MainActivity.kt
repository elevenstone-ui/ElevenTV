package com.eleventv.tv

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.eleventv.tv.ui.AppRoot
import com.eleventv.tv.ui.components.BrandSplash
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.ElevenTheme
import com.eleventv.tv.ui.design.M
import com.eleventv.tv.ui.player.PlayerActivity
import kotlinx.coroutines.delay

/**
 * 唯一的界面容器。
 *
 * 启动页用 [BrandSplash] 叠在内容之上淡出，而不是单独开一个 SplashActivity：
 * 冷启动时读配置只要几十毫秒，多一次 Activity 跳转会看到两次转场动画。
 *
 * 系统启动窗口本身也带着标志（`Theme.ElevenTV.Launcher` → `drawable/splash_brand`），
 * 所以从点图标到首页的整段都有品牌视觉，不会出现"先盯两秒紫屏"。
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /**
         * 启动页的计时基准：用户点图标的时刻。
         *
         * 不能写在 composable 里 `remember` —— 那时首帧已经组合完，
         * 冷启动慢的机型光首帧就要一秒多，这段会被白白算成"已经停留过"。
         */
        val launchedAt = SystemClock.uptimeMillis()

        val repo = (application as ElevenApp).repository

        setContent {
            ElevenTheme {
                val ready by repo.ready.collectAsState()

                /**
                 * 停留是否够了。
                 *
                 * 由 [BrandSplash] 在进场动画跑完时回调，而**不是** `delay(2000)`：
                 * delay 走协程调度，主线程卡住照常计时，动画却停下不动，
                 * 结果就是"时间到、动画没播"——LOGO 只在最后一闪而过，正是之前的现象。
                 */
                var holdDone by remember { mutableStateOf(false) }

                /**
                 * 兜底放行。
                 *
                 * 正常冷启动 [repo.ready] 几十毫秒就 true，走不到这里；
                 * 但如果读配置这一步出了岔子（Keystore 解不开、文件损坏之类），
                 * 没有兜底就永远停在 LOGO 上——那是最糟的失败形态，连设置页都进不去。
                 */
                var forced by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(M.SPLASH_CEILING)
                    forced = true
                }

                val splash = (!ready || !holdDone) && !forced

                /**
                 * 首页推迟一帧挂载。
                 *
                 * 启动层只有一颗标志和两行字，几十毫秒就能上屏；
                 * 首页要组合海报墙、测量几十张卡片、发起图片请求。
                 * 两者塞进同一帧的话，冷启动时用户得盯着启动窗口里的画面等一两秒才看到任何反应——
                 * 那正是"满屏紫色"的由来。错开一帧，品牌画面先出来，首页在后面悄悄搭。
                 */
                var contentMounted by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    contentMounted = true
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(C.Ink0),
                ) {
                    if (contentMounted) {
                        AppRoot(
                            repo = repo,
                            onPlayFile = { entry, file ->
                                startActivity(PlayerActivity.intent(this@MainActivity, entry.id, file.id))
                            },
                            // 用 finishAndRemoveTask 而不是 finish：
                            // 这是"退出应用"的语义，只 finish 的话 Activity 还挂在任务里，
                            // 从最近应用点回来会直接复活在首页——看起来就像"退出只退回了首页"。
                            onExitApp = { finishAndRemoveTask() },
                            // 启动页期间交出返回键，和下面那个 handler 互斥；
                            // 靠状态而不是"谁后注册"来决定谁生效
                            backEnabled = !splash,
                        )
                    }

                    // 配置还没读完、或启动动画没走完时，由这层盖住内容
                    BrandSplash(
                        visible = splash,
                        since = launchedAt,
                        // 可能被回调两次（动画完成 + 超时兜底），直接赋值即幂等
                        onHoldEnd = { holdDone = true },
                    )

                    // 启动页期间把返回键吃掉：LOGO 还没放完就弹出"退出？"甚至退回桌面，
                    // 体感都像应用崩了。
                    //
                    // 与 AppRoot 里的返回处理器**互斥**（那边 backEnabled = !splash），
                    // 谁生效只看状态，不看注册顺序。之前这里靠"写在后面就优先"，
                    // 但 AppRoot 要等第二帧挂载才注册，实际优先级和书写顺序是反的。
                    BackHandler(enabled = splash) { }
                }
            }
        }
    }
}
