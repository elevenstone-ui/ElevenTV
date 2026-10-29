package com.eleventv.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.eleventv.tv.R
import com.eleventv.tv.data.model.CastMember
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.model.PosterBadge
import com.eleventv.tv.data.model.TitleSummary
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T
import com.eleventv.tv.ui.design.focusRing

/**
 * 竖版海报卡（120×180dp，2:3）。
 *
 * 结构：海报（角标 / 评分 / 进度条压在图上）→ 海报**下方**的标题与副标题。
 *
 * ## 标题为什么不在海报里
 *
 * 原来标题压在海报底部，靠一层深色渐变保证可读性。实际上剧照底部的细节本来就很杂，
 * 渐变又只能压暗不能压平，两行中文叠上去之后字和画面糊成一片——三米外基本读不出剧名。
 * 移到海报下方后标题永远是纯色背景上的文字，海报也能完整露出来。
 *
 * ## 焦点环为什么只套海报
 *
 * [focusRing] 会按所挂节点的尺寸画环。挂在整列上，环会把下方的两行标题一起圈进去，
 * 视觉上像个奇怪的方框；挂在海报上则只有封面被高亮，标题保持不动。
 * 缩放同样只作用在海报上，[D.CardTextGap] 预留了放大后向下溢出的那几 dp。
 *
 * @param focusModifier 调用方注入的 `FocusRequester`。它必须挂到**真正可聚焦**的海报节点上，
 * 挂在外层 Column（不可聚焦）会导致从详情页返回时 `requestFocus()` 直接抛异常。
 */
@Composable
fun PosterCard(
    item: TitleSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(D.RadiusPoster)
    Column(modifier = modifier.width(D.PosterW)) {
        Box(
            modifier = Modifier
                .size(D.PosterW, D.PosterH)
                .then(focusModifier)
                .focusRing(onClick = onClick, scale = 1.04f, shape = shape),
        ) {
            // 内层负责裁剪：焦点环画在外层，若与外层共用 clip 会被裁成半个环
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(C.placeholder(item.id)),
            ) {
                val poster = item.images.poster?.url
                if (!poster.isNullOrBlank()) {
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // 角标（左上角）：4K / 杜比视界 / HDR10+ 并排
                if (item.badges.isNotEmpty()) {
                    BadgeRow(
                        badges = item.badges,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(D.Sm),
                    )
                }

                // 评分（右上角，金色）
                item.rating?.takeIf { it > 0 }?.let { score ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(D.Sm)
                            .background(C.Scrim, RoundedCornerShape(D.RadiusBadge))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        TvText(
                            text = fmtScore(score),
                            style = T.Caption.copy(fontWeight = FontWeight(700)),
                            color = C.Gold,
                        )
                    }
                }

                // 观看进度（底部 2.5dp mint 条）
                item.progress?.takeIf { it > 0.005f }?.let { p ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(D.CardBar)
                            .background(Color.White.copy(alpha = 0.18f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(p.coerceIn(0f, 1f))
                                .height(D.CardBar)
                                .background(C.Mint),
                        )
                    }
                }
            }
        }

        VSpace(D.CardTextGap)

        TvText(
            text = item.title,
            style = T.Title2,
            color = C.Text1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.takeIf { it.isNotBlank() }?.let {
            VSpace(2.dp)
            TvText(
                text = it,
                style = T.Caption,
                color = C.Text2,
                maxLines = 1,
            )
        }
    }
}

/**
 * 左上角角标组。
 *
 * HDR / 杜比视界画成矢量标识，不再写「HDR10+」「杜比视界」——120dp 宽的卡上，
 * 一行字读起来费力且容易和剧照抢注意力；标识是形状，扫一眼就分得出是哪一类画质，
 * 且能跟着海报缩放而不糊。分辨率仍用文字「4K」，这四个字本身就是行业写法。
 */
@Composable
private fun BadgeRow(badges: List<PosterBadge>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        badges.forEach { badge ->
            when (badge) {
                PosterBadge.UHD -> TextBadge("4K", C.Brand, C.Text1)
                PosterBadge.ANIME -> TextBadge("动漫", C.Scrim, C.Text2)
                PosterBadge.HDR -> IconBadge(R.drawable.ic_badge_hdr, 26.dp, 11.dp, C.Text1)
                PosterBadge.DOLBY_VISION ->
                    IconBadge(R.drawable.ic_badge_dolby_vision, 15.dp, 11.dp, C.Gold)
            }
        }
    }
}

@Composable
private fun TextBadge(label: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(D.RadiusBadge))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        TvText(
            text = label,
            style = T.Badge,
            color = fg,
            maxLines = 1,
        )
    }
}

/** 深底 + 矢量标识：任何底色的剧照上都能看清轮廓 */
@Composable
private fun IconBadge(iconRes: Int, width: Dp, height: Dp, tint: Color) {
    Box(
        modifier = Modifier
            .background(C.Scrim, RoundedCornerShape(D.RadiusBadge))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(width, height),
        )
    }
}

/**
 * 横版卡（190×107dp，16:9），用于「继续播放」。
 *
 * 结构与海报卡一致：图在上、标题在下，图上只留播放标记、集号与进度条。
 */
@Composable
fun WideCard(
    item: TitleSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(D.RadiusWide)
    Column(modifier = modifier.width(D.WideW)) {
        Box(
            modifier = Modifier
                .size(D.WideW, D.WideH)
                .then(focusModifier)
                .focusRing(onClick = onClick, scale = 1.04f, shape = shape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(C.placeholder(item.id)),
            ) {
                val art = item.images.backdrop?.url ?: item.images.poster?.url
                if (!art.isNullOrBlank()) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // 中央播放标记，暗示「点这里继续」
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(com.eleventv.tv.R.drawable.ic_play),
                        contentDescription = null,
                        tint = C.Text1,
                        modifier = Modifier.size(14.dp),
                    )
                }

                // 进度条
                item.progress?.takeIf { it > 0.005f }?.let { p ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(D.CardBar)
                            .background(Color.White.copy(alpha = 0.18f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(p.coerceIn(0f, 1f))
                                .height(D.CardBar)
                                .background(C.Mint),
                        )
                    }
                }
            }
        }

        VSpace(D.CardTextGap)

        TvText(
            text = item.title,
            style = T.Body2.copy(fontWeight = FontWeight(600)),
            color = C.Text1,
            maxLines = 1,
        )
        item.playback?.let { hint ->
            val ep = hint.episode
            val season = hint.season
            // 电影也会带 episode=1（MediaFile 的集号默认值就是 1），不排除就会在
            // 「继续播放」卡上显示「第 1 集」——把电影说成剧集，每次看首页都在误导
            val label = if (ep != null && item.kind != MediaKind.MOVIE) {
                if ((season ?: 1) > 1) "S${season}E$ep" else "第 $ep 集"
            } else {
                null
            }
            label?.let {
                TvText(it, style = T.Caption, color = C.Text2, maxLines = 1)
            }
        }
    }
}

/**
 * 选集卡（208×117dp，16:9 剧照）。
 *
 * 详情页的选集区改成横向一行，就是由这张卡组成的：遥控器左右移动选剧，比在
 * 几十行竖排条目里逐条按「下」快得多。标题规则与海报卡一致——图上的角标只放集号，
 * 真实标题放在卡片下方。
 *
 * @param code 集号角标，如 `E03` / `S2E03`；电影传 null（没有集号这回事）
 * @param title 下方标题：刮削到单集标题就用它，否则退回「第 n 集」
 */
@Composable
fun EpisodePosterCard(
    code: String?,
    title: String,
    stillUrl: String?,
    watched: Boolean,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(D.RadiusPoster)
    Column(modifier = modifier.width(D.EpisodeW)) {
        Box(
            modifier = Modifier
                .size(D.EpisodeW, D.EpisodeH)
                .then(focusModifier)
                .focusRing(onClick = onClick, scale = 1.04f, shape = shape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(C.placeholder(code ?: title)),
            ) {
                if (!stillUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = stillUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // 集号角标（电影没有集号，不画）
                code?.let { label ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(D.Sm)
                            .background(C.Scrim, RoundedCornerShape(D.RadiusBadge))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        TvText(
                            text = label,
                            style = T.Caption.copy(fontWeight = FontWeight(700)),
                            color = if (watched) C.Mint else C.Text1,
                            maxLines = 1,
                        )
                    }
                }

                // 已看完的整条进度会是满的，这里只在「看到一半」时出现
                if (progress > 0.005f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(D.CardBar)
                            .background(Color.White.copy(alpha = 0.18f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(D.CardBar)
                                .background(C.Mint),
                        )
                    }
                }
            }
        }

        VSpace(D.CardTextGap)

        TvText(
            text = title,
            style = T.Body2,
            color = if (watched) C.Text2 else C.Text1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 评分统一一位小数 */
fun fmtScore(v: Double): String {
    val rounded = kotlin.math.round(v * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/**
 * 演员卡（详情页最下方，横向排列）。
 *
 * 结构：圆形头像 → 姓名 → 角色名。
 *
 * ## 为什么用圆头像而不是矩形
 *
 * 演员卡与海报卡在同一屏里共存，矩形会让用户以为「这也是一部片」；
 * 圆形是流媒体里人物条目的通用形态，一眼就能区分「这是人、不是影片」。
 *
 * 头像缺失时退回姓名首字：一排空圆看起来像加载失败，有个字至少知道是谁。
 */
@Composable
fun CastCard(
    member: CastMember,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(D.CastW),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(D.CastAvatar)
                // 焦点环挂在头像节点上：外层 Column 不可聚焦，挂上去 requestFocus 会抛异常
                .focusRing(onClick = onClick, scale = 1.06f, shape = CircleShape)
                .clip(CircleShape)
                .background(C.placeholder(member.name)),
        ) {
            val avatar = member.profileUrl
            if (!avatar.isNullOrBlank()) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                TvText(
                    text = member.name.take(1),
                    style = T.Title1,
                    color = C.Text2,
                )
            }
        }

        VSpace(D.Sm)

        TvText(
            text = member.name,
            style = T.Label,
            color = C.Text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        member.character?.takeIf { it.isNotBlank() }?.let { role ->
            TvText(
                text = role,
                style = T.Caption,
                color = C.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
