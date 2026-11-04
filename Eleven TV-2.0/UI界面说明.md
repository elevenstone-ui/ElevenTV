# ElevenTV 2.0 — UI 界面说明

> 配套设计稿：`ElevenTV-UI.html`（3840×2160 dp 1:1）。本文档描述实际 App 的每个屏幕及其交互，与设计稿章节一一对应。

---

## 0. 启动流程

**两段衔接**：

1. **系统启动窗口**（`Theme.ElevenTV.Launcher` → `drawable/splash_brand`）：从点图标到 Compose 首帧，静态居中 LOGO。
2. **Compose 启动层**（`BrandSplash` + `SplashMotion` + `SplashSound`）：
   - LOGO 位移滑入（SLIDE 0.06→0.74）+ 字母逐个淡入（LETTER 0.10 起，72ms 间隔，8 个字母）
   - 动画固定 1000ms（`M.SPLASH_ANIM`），不随设备快慢伸缩
   - 主相位 0.72 处首页开始挂载，避免卡顿
   - 音效：8 个逐字母「叮」（C5→C6 上行）+ 收尾 thump，1s 内结束
   - 设置里可关（`splashSound`），默认开

**兜底**：`M.SPLASH_CEILING` 到点强制放行，避免纯色空屏。

---

## 1. 引导页（Onboarding）

**首次安装 / 无任何服务器时**自动进入。

- 表单：服务器名 / 主机 IP / 端口 / 共享名 / 用户名 / 密码 / 协议（SMB / WebDAV）
- 「测试连接」按钮：实时验证，失败给具体原因（连接超时 / 认证失败 / 共享不存在）
- 测试通过后选择根目录（浏览服务器目录树）
- 保存 → 自动扫描 → 自动刮削 → 跳首页

**对应 Compose**：`ui/screens/OnboardingScreen.kt`

---

## 2. 主界面（Home）

**对应设计稿 01 Home，Compose `ui/screens/HomeScreen.kt`。**

### 布局（自上而下）

1. **顶栏（NavBar）**：左侧品牌锁定组合（LOGO + "ElevenTV"，3:2 比例），中间分类（推荐/电影/剧集/综艺/动漫/我的），右侧搜索图标 + 时间 + 用户头像
2. **Hero 横幅**：占屏 ~59%，背景大图 + 渐变遮罩，叠加标题 / 年份 / 类型 / 集数 / 评分 / 简介 / 「继续播放 · 第 N 集」主按钮 + 「加入片单」次按钮，底部 4 个轮播指示点
3. **继续观看行**：横向海报卡片（带进度条），焦点默认落在此行
4. **今晚热门行**：横向海报卡片
5. **底部提示栏**：遥控器按键提示（OK 选择 / ← → 浏览 / ↑ ↓ 切换分类 / 返回）

### 焦点逻辑

- 首屏焦点默认给「继续播放」主按钮，按一次 OK 直接回到上次位置
- Hero 轮播：左右方向键切换，指示点同步

### 空态

- 无服务器：大字 LOGO + "添加服务器" CTA
- 服务器已加但库为空：进度条 + "扫描中…" 文案

---

## 3. 影视海报瀑布流（Browse）

**对应设计稿 02，Compose `ui/screens/BrowseScreen.kt`。**

### 布局

- 顶部：返回箭头 + 分类标题（如"电影"）+ 条目数 + 筛选 chips（全部 / 4K HDR / 科幻 / 悬疑 / 剧情 / 年份 / 评分）
- 主体：瀑布流混排 2:3 / 3:4 / 1:1.15 三种比例海报，6 列 / 52px 间距
- 底部：按键提示

### 焦点逻辑

- 卡片 ← → 浏览，↑ 直达筛选 chips，OK 进详情
- 多季剧集跨季归并为一条（集号集合合并）

### 卡片

- 海报 + 标题 + 年份 + 评分角标
- 多季剧：季数角标
- 有进度：底部进度条

---

## 4. 影片详情（Detail）

**对应设计稿 03，Compose `ui/screens/DetailScreen.kt`。**

### 布局

- 背景：影片背景图（刮削）+ 模糊 + 暗化
- 左侧：竖版海报（600×900 等效比例）
- 右侧：
  - 标题（中文优先）+ 英文原名
  - 标签组（4K HDR / 杜比 / 类型 / 年份 / 集数）
  - 评分 + 评价人数 + 上线日期 + 已看进度
  - 简介
  - 按钮：「继续播放 · 第 N 集」（主）/「追剧」（次）/ 更多（⋮）
- 下方：**选集网格**（5×2，已看集数 Mint 色标记，点击跳对应集）

### 焦点逻辑

- 返回后焦点回到原海报位置
- 选集按 OK 直接播放

### 电影 vs 剧集

- 电影：无选集，直接「播放」
- 剧集：选集网格 + 「继续播放」按钮

---

## 5. 播放器（Player）

**Compose `ui/screens/PlayerScreen.kt` + `ui/player/PlayerState.kt` + `PlayerActivity.kt`。**

### 画面层

- `SurfaceView`（`setVideoSurfaceView`）全屏
- letterbox 按 `videoAspect` 等比缩放，不拉伸
- 异常宽高比（0 / NaN / 超出 0.4~2.5）按铺满处理，不崩

### OSD 层（`OsdMode` 状态机）

- **Main**：顶部（标题 + 进度 + 时间）+ 底部（播放/暂停 / 快退10 / 快进10 / 音频 / 选集 / 字幕 / 解码器），15s 自动隐藏
- **Seek**：进度条放大 + 时间戳，4s 自动隐藏
- **Panel**：音频 / 字幕 / 选集 / 解码器四个面板，可滚动，自动跟随选中项
- 字幕：文本（6 档字号 0.85~2.6×）+ PGS/VOBSUB 位图（随档位放大，上限 100%）

### 播放控制

- 断点续播（`resumePositionOf`，快看完的从头）
- 自动连播（`autoNextEpisode`，结束自动跳下一集）
- 缓冲超时：12s 无数据报错页（可重试 / 自动重试一次）
- 解码器切换：自动 / 硬解 / 软解，切换后回写设置
- 5s 落盘一次进度（断电最多丢 5s）
- 屏幕守卫：反射 `PowerManager.userActivity`（API33+ 静默失败，不影响播放）

### 按键

- 方向键：OSD 导航
- OK：播放/暂停
- 返回：OSD 关闭 / 退出播放
- 长按左/右：快退/快进

---

## 6. 搜索（Search）

**Compose 在 App.kt 内嵌。**

- 输入框 + 实时匹配（标题 / 类型 / 年份）
- 结果网格：与 Browse 卡片同款
- 无结果：空态提示

---

## 7. 设置 / 我的（Settings）

**Compose `ui/screens/SettingsScreen.kt`。**

### 卡片分组

1. **服务器**：列表（名称 / 协议 / 主机 / 共享），每行「编辑 / 删除」，顶部「添加服务器」
2. **播放**：
   - 解码方式（自动/硬解/软解）
   - 记忆播放进度
   - 自动播放下一集
   - 启动音效
   - 字幕字号（0.85/1.0/1.25/1.5/2.0/2.6 六档）
3. **刮削**：
   - 启用刮削
   - 扫描后自动刮削
   - 刮削海报 / 背景 / 季简介 / 单集简介
   - 优先中文标题
4. **关于**：
   - 品牌锁定组合（LOGO + ElevenTV，3:2）
   - 版本号 + 构建时间戳（BuildConfig）
   - 数据来源声明（TMDB / Fanart.tv 署名）
   - 应用说明

### 交互

- 每项独立 ToggleRow / ChoiceRow，整行可聚焦
- 改设置即时持久化（JsonStore 原子写）
- 删服务器：确认后裁剪媒体库 + 清播放记录 + 作废连接

---

## 8. 视觉与交互规范（Tokens）

**设计稿 02 章节，对应 `ui/design/Tokens.kt`。**

| 类别 | 规则 |
|------|------|
| 色彩 | 纯黑底 `#0A0C16` → `#05060A` 渐变；强调色 Mint `#4AE8C4`；焦点环白 2px |
| 字体 | 无衬线，字距 -0.02em（大标题）/ 0.02em（LOGO） |
| Logo 锁定组合 | **图形 H : 字标字号 = 3:2**，**间距 = 0.25×H**，字标基线对齐图形中心；所有场景（导航栏 / 空态 / 引导 / 关于 / 启动页）统一 |
| 焦点 | 白 2px 圆角环，整行高亮（不只是小开关） |
| 间距 | 基准 4dp 倍数，卡片圆角 8dp |
| 4K 细节 | 3840×2160 下所有尺寸 1:1 绘制；焦点环不超出安全区 |

---

## 9. 图标与开关

- 图标：线性 2px，48px 基准，圆角端点
- 开关：自绘拨杆（非 Material Switch），32dp，焦点时整行高亮
- 按键提示：底部常驻，OK / ← → / ↑ ↓ / ≡ / ↩ 五枚

---

## 文件对照

| 屏幕 | Compose 文件 |
|------|-------------|
| 引导 | `ui/screens/OnboardingScreen.kt` |
| 主界面 | `ui/screens/HomeScreen.kt` + `ui/components/NavBar.kt` |
| 瀑布流 | `ui/screens/BrowseScreen.kt` |
| 详情 | `ui/screens/DetailScreen.kt` |
| 播放器 | `ui/screens/PlayerScreen.kt` + `ui/player/PlayerState.kt` + `ui/player/PlayerActivity.kt` |
| 设置 | `ui/screens/SettingsScreen.kt` |
| 启动层 | `ui/components/BrandSplash.kt` + `SplashMotion.kt` + `SplashSound.kt` |
| 视觉 Token | `ui/design/Tokens.kt` + `Theme.kt` + `Brand.kt` |
