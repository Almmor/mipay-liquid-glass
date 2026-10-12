# 小米智能卡 · 液态玻璃（Mi Pay Liquid Glass）

让 **小米智能卡（`com.miui.tsmclient`）** 与 **小米钱包（`com.mipay.wallet`）** 的刷卡 / 支付页面
支持 iOS 26 风格的 **Liquid Glass（液态玻璃）** 材质，并对刷卡页面做整体美化。

整套方案由两个可独立工作的部分组成：

| 组成 | 形态 | 作用 |
| --- | --- | --- |
| **LSPosed 模块** | APK（`MiPayLiquidGlass-v2.0.0.apk`） | 运行时把刷卡页、选卡页、卡详情、支付确认弹窗与浮层改造成液态玻璃材质，**完整保留设备卡面**；内置设置界面与「一键重启小米智能卡 / 重启 SystemUI」（Root） |
| **Root 模块** | Magisk / KernelSU zip（`MiPayLiquidGlass-Root-v2.0.0.zip`） | 开启系统级窗口模糊能力，并补齐超级岛能力属性、开机自动安装上面的 APK |

三条核心行为边界：

- **改页面**：刷卡页、选卡页、卡详情、支付确认弹窗、卡种浮层，尽量铺满；
- **保卡面**：所有识别为卡面 / 图片素材的视图原样不动，只在最上层叠一层玻璃镜面；
- **能重启**：应用内直接获取 Root 权限，强停并重新拉起小米智能卡，让配置立即生效。

---

## 效果原理

### 1. 真正的「背景模糊」来自系统能力

Android 12（API 31）起，`Window#setBackgroundBlurRadius()` 可以把窗口**背后**的内容做高斯模糊，
再作为窗口背景绘制出来，而窗口自身的背景 Drawable 决定模糊区域的轮廓
[$TRAE_REF](https://source.android.google.cn/docs/core/display/window-blurs?hl=zh-cn)。

我们把窗口背景替换成玻璃 Drawable，于是「模糊 + 玻璃渐变 + 边缘高光」一次完成。
该能力依赖 SurfaceFlinger 属性 `ro.surface_flinger.supports_background_blur=1`
[$TRAE_REF](https://source.android.google.cn/docs/core/display/window-blurs),
由 Root 模块负责补齐与校验。

### 2. 玻璃材质本体的绘制顺序

`LiquidGlassDrawable` 自下而上叠加 6 层，这是「像玻璃」而不是「像半透明白块」的关键：

1. **基础染色** — 自上而下由亮到暗的半透明蓝白渐变；
2. **顶部膨光** — 左上角径向高光，制造玻璃的体积感；
3. **镜面流光** — 沿对角线扫过的柔和亮带；
4. **底部内阴影** — 带来厚度，避免「贴纸感」；
5. **AGSL 折射层** — 边缘菲涅尔高光 + RGB 三通道分别偏移 SDF 形成的**色散彩边**；
6. **边缘亮线** — 左上来光、右下收暗的 1px 描边 + 左上角高亮弧。

### 3. 为什么用 AGSL 而不是逐帧抓背景

iOS 液态玻璃的招牌特征是**折射**：玻璃边缘把背后的内容扭曲并产生色散。
在 Android 上复刻它需要逐帧抓取背景位图再送入着色器，开销大且兼容性差
[$TRAE_REF](https://habr.com/ru/articles/918030/)。

本项目改用**基于圆角矩形 SDF 的程序化着色器**：不采样背景，只用距离场推导法线，
再算出朝光面的菲涅尔高光与色散彩边。既拿到了最具辨识度的光学特征，
又避免了逐帧位图抓取 —— 视觉效果与性能兼得。着色器仅在 Android 13（API 33）及以上生效，
低版本自动退回纯 Canvas 渐变实现 [$TRAE_REF](https://composeinternals.com/agsl-shaders-jetpack-compose-liquid-glass)。

### 4. 为什么不 Hook 具体类名

小米智能卡内部类名会随版本变化。本项目**完全不依赖任何应用内部类名**，
只 Hook Framework 层的 `Activity` / `Dialog` / `PopupWindow` 生命周期，再依据
「背景 Drawable 是否为带圆角的 `GradientDrawable`」「是否为 CardView」
这些**通用特征**判定哪些是卡片、按钮与面板。因此：

- 抗版本升级，小米改类名不影响；
- 天然不触碰支付业务逻辑，只改 `setBackground` / `setForeground` 与窗口属性。

三条 Hook 路径分别对应三类页面形态，覆盖面尽量做满：

| Hook 目标 | 覆盖的页面 |
| --- | --- |
| `Activity#onCreate / onResume / onContentChanged / onPostResume` | 主页面、刷卡页、卡详情页、设置页 |
| `Dialog#show` | 支付确认弹窗、选卡弹窗、NFC 提示弹窗 |
| `PopupWindow#showAtLocation / showAsDropDown` | 卡种切换浮层、卡详情气泡 |

### 5. 卡面保护：改造页面但不碰你的卡面

这是本项目最需要克制的地方。交通卡封面、银行卡片面是用户最在意的视觉元素，
一旦被玻璃化成半透明白块，体验就是负分。因此引入了 `CardFaceGuard`：

**识别规则（命中任意一条即视为卡面素材）**

1. `ImageView` 且带有 drawable；
2. 背景是 `BitmapDrawable` / `NinePatchDrawable`；
3. 背景是 `LayerDrawable`、`StateListDrawable`、`InsetDrawable` 等「壳」，
   但递归钻取后发现里面含位图；
4. 背景是 **非 Framework 内置**的自定义 Drawable（厂商卡面常是渐变 + 纹理 + 卡号一体的自绘对象）；
5. 视图 ID 资源名带 `card / kaimian / cover / poster / img / tsm / bank` 等语义关键词。

**保护动作**：命中后，该视图**连同其整个子树**都会被跳过，背景与内容零改动；
若它恰好是一个「圆角容器」（说明它就是卡片的框），也整块跳过，
只在最上层用 `View#setForeground` 叠一层**玻璃镜面**。

叠加层用的是 `LiquidGlassDrawable` 的 overlay 模式：铺底染色压到 26%、膨光与内阴影大幅削弱，
**只保留折射高光、色散彩边与流光**。所以卡面图案完全清晰，只是像被罩了一块玻璃。

> 效果就是：页面其他部分变成液态玻璃，卡面还是你熟悉的那张卡面，
> 但卡面上多了一层有厚度、会反光的玻璃罩。

### 6. 精准适配：反编译驱动的 Hook 点（1.0.1 新增）

1.0.0 靠「背景是不是带圆角的 `GradientDrawable`」识别卡片与按钮。用 `aapt2` + `dexdump`
把小米智能卡反编译后发现问题：**刷卡页 `layout/switch_card_page_fragment` 与卡面
`layout/card_view_item` 里，没有任何 View 在 XML 中设置 background** —— 所有视觉都在
自定义 View 内部绘制。因此 1.0.0 在这些页面上的命中数是 **0**。

1.0.1 为此引入 `GlassTargets` 类名白名单通道。这些类名都没有被混淆
（反编译确认的父类见括号），是稳定锚点：

| 目标类 | 父类 | 角色 |
| --- | --- | --- |
| `ui.quick.CardStackLayout` | `FrameLayout` | 刷卡页卡堆容器（id `content`） |
| `quick.nonenfc.SwitchCardFingerLayer` | `LinearLayout` | 刷卡手指层（id `finger_layer`） |
| `ui.widget.MiPayCardItemView` | `RelativeLayout` | 卡列表项 |
| `ui.widget.SwipeCardResourcesView` | `LinearLayout` | 刷卡结果区（id `swipe_card_res_view`） |
| `ui.widget.SwipingAreaDialogView` | `FrameLayout` | 刷卡区域弹窗 |
| `ui.widget.SwitchCardQRView` | `RelativeLayout` | 切卡二维码 |
| `ui.widget.SlideView` | `RelativeLayout` | 列表滑动项 |
| `ui.widget.CardWithMoreItemsLayout` | — | 卡片 + 更多项组合布局 |

命中白名单后**只做一件事**：在 `View#setForeground` 上叠一层玻璃镜面，
背景与绘制内容零改动。同时新增两条保护：

- `ui.widget.CardView`（卡面本体）反编译确认是 `androidx.appcompat.widget.AppCompatImageView`
  的子类 —— **卡面就是一张图片**，因而被 `CardFaceGuard` 天然保护，只能叠加、不能替换；
- `com.airbnb.lottie.LottieAnimationView`（刷卡页 `notice_icon` 动画）与
  `miuipub.hybrid.HybridView`（H5 容器）列入「绝不触碰」清单。

详细的类名 / ID / 布局层级证据见 [docs/hook-targets.md](docs/hook-targets.md)。

### 7. 界面控件玻璃通道与深浅色双形态（1.0.2 新增）

**界面控件通道 `GlassChrome`**。1.0.1 的启发式规则会给大容器整块叠玻璃镜面，
出现「一条玻璃横带横穿页面」的错乱。1.0.2 改为按**反编译得到的真实 id** 精准命中：

| 目标 | id | 处理方式 |
| --- | --- | --- |
| 顶部按钮 | `iv_open_scan` / `swiping_help` / `swiping_settings` | 换玻璃背景，**图标原样保留** |
| 余额显示框 | `layout_balance_reminder` | 换玻璃面板背景，文字与数值不动 |
| 充值按钮 | `btn_balance_reminder_recharge` | 换玻璃按钮背景，文案保留 |

同时收紧叠加规则：只有「短边 ≥ 96dp 的卡面级大图」才叠玻璃罩，
普通图标与高度超过半屏的大容器一律不叠 —— 消除横带错乱。

**深浅色双形态**。玻璃材质跟随系统 `uiMode` 自动切换：

- **浅色模式 · 白玻璃**：亮铺底 + 冷白高光，通透明亮；
- **深色模式 · 烟玻璃**：深蓝黑铺底 + 更亮的边缘高光，深邃有质感。

顶部按钮、余额框、充值按钮的玻璃背景与窗口背景同样跟随深浅色变化。

### 8. 余额区 Apple Wallet 化与卡面圆角自动探测（1.0.3 新增）

1.0.2 给余额框、充值按钮也套了玻璃，实机观感偏「重」。1.0.3 按 Apple Wallet 的
排版语言重做刷卡页卡片下方这一块：

| 控件 | id | 1.0.3 处理 |
| --- | --- | --- |
| 余额显示框 | `layout_balance_reminder` | **去掉背景框**，「余额」小标签 + `balance_text` 大金额直接浮在页面上 |
| 充值按钮 | `btn_balance_reminder_recharge` | **淡蓝色渐变胶囊 + 白色 medium 文字**，按下加深（Apple Pay 式） |
| 顶部按钮 | `iv_open_scan` / `swiping_help` / `swiping_settings` | 保持圆形玻璃底 |

文字颜色跟随深浅色：浅色用 iOS `labelColor` / `secondaryLabelColor`，深色用对应深色版；
充值按钮浅色用 iOS 系统蓝 `#007AFF`、深色用 `#0A84FF`。这些逻辑集中在 `AppleStyle`。

**卡面圆角自动探测（`RadiusProbe`）**。玻璃罩必须与被覆盖的卡面圆角一致，
但卡面的圆角**不在卡面自己身上**：反编译 `layout/card_view_item` 显示，
卡面图片 `ui.widget.CardView` 被套在 MiuiX 的 `miuipub` 圆角容器
`miuix.smooth.SmoothFrameLayout2` 里，圆角写在容器的 `android:radius=@dimen/common_radius`。

因此 `RadiusProbe` 按顺序自动取圆角，命中即返回：

1. 自身背景 `GradientDrawable` 的圆角；
2. 自身 `Outline`（系统真实用于裁剪的圆角矩形，API 21+）；
3. 反射读取圆角容器的私有 `radius` / `corner` getter 或字段（MiuiX 走这条）；
4. 向**上层容器**递归（最多 4 层）重复 1~3 —— 卡面图片正是靠这一步拿到圆角。

探测失败才回退到配置里的默认圆角（26dp）。所有探测都包在 try/catch 中，异常一律按失败处理。

---

## 目录结构

```
mi pay supports liquid glass/
├── MiPayLiquidGlass-v2.0.0.apk         ← 装好的 LSPosed 模块（可直接安装）
├── MiPayLiquidGlass-Root-v2.0.0.zip    ← Root 模块（Magisk / KernelSU 直接刷入）
│
├── MiPayLiquidGlass/                    ← LSPosed 模块源码工程
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradlew / gradlew.bat / gradle/wrapper/
│   ├── local.properties                 （sdk.dir，按需修改）
│   └── app/src/main/
│       ├── AndroidManifest.xml           （xposedmodule / xposedscope 声明）
│       ├── assets/xposed_init            （Xposed 入口类）
│       ├── java/com/liquidglass/mipay/
│       │   ├── LiquidGlassModule.java    ★ Hook 入口（Activity / Dialog / PopupWindow）
│       │   ├── Config.java / XposedPrefs.java / Utils.java
│       │   ├── RootHelper.java           ★ Root 权限 / 强制停止目标应用
│       │   ├── SettingsActivity.java     （模块设置界面 + 一键重启）
│       │   └── glass/
│       │       ├── LiquidGlassDrawable.java   ★ 玻璃材质绘制核心（深浅色双形态 + 叠加模式）
│       │       ├── GlassShader.java           ★ AGSL 折射 / 色散着色器
│       │       ├── CardFaceGuard.java         ★ 卡面 / 图片素材识别与保护
│       │       ├── GlassTargets.java          （自绘控件类名白名单，1.0.1）
│       │       ├── GlassChrome.java           （顶部按钮 / 余额区 / 充值按钮 id 通道，1.0.3 拆分）
│       │       ├── AppleStyle.java            （余额区 Apple Wallet 排版 + 天蓝纯色磨砂玻璃充值按钮，1.7.1）
│       │       ├── RadiusProbe.java           （卡面圆角自动探测，1.0.3）
│       │       ├── GlassWindowStyler.java     （窗口模糊 + 沉浸模式）
│       │       ├── GlassViewStyler.java       （视图树玻璃化 + 卡面剪枝 + 交易条目接管）
│       │       ├── CardFaceGrabber.java       （卡面截获 Hook：ImageView / CardInfo / View#setBackground）
│       │       ├── IslandFaceCache.java       ★ 卡面缓存、按卡名配对、高清匹配、落盘
│       │       ├── IslandOverlay.java         ★ 超级岛动画自绘视图（进出 / 翻转 / 对勾）
│       │       ├── IslandOverlayController.java（岛窗口管理 + 帧探测 + 广播入口）
│       │       ├── IslandBridge.java          （卡面跨进程投递：显式广播，最长边 ≤384px）
│       │       ├── IslandStyler.java          （焦点通知通道：a1 摘除系统岛参数）
│       │       ├── TradeDetailSheet.java      ★ iOS 风格「交易详情」弹层（站点 / 时间 / 金额）
│       │       ├── TradeMapRenderer.java      ★ 交易地图：高德栅格瓦片拼图 + 站点经纬度解析
│       │       └── FaceExportDiagnostic.java  （adb 触发导出卡面候选，诊断用）
│       └── res/                          （设置界面布局、图标、主题）
│
├── magisk-root-module/                  ← Root 模块源码
│   ├── module.prop / system.prop
│   ├── customize.sh / post-fs-data.sh / service.sh / uninstall.sh
│   └── MiPayLiquidGlass.apk             （随包 APK，开机自动安装）
│
├── docs/hook-targets.md                 ← 反编译报告：真正的 Hook 点与类名清单
├── tools/adb-liquid-glass.ps1           ← ADB 诊断 / 修复脚本（体检、遮挡、重启、打开管理器）
└── preview.html                         ← 美化效果可视化预览（静态示意，自 1.0.2 起不再随版本更新）
```

---

## 快速开始

### 前置条件

- 已 Root 的设备（Magisk / KernelSU）
- 已安装 **LSPosed** 框架（本项目使用传统 Xposed API，LSPosed 1.8 及以上均可）
- Android 12 及以上（低于 12 时窗口模糊不可用，会自动退化为静态玻璃材质）

### 方式一：一体化安装（推荐）

1. 用 Magisk / KernelSU 刷入 `MiPayLiquidGlass-Root-v2.0.0.zip`；
2. 重启设备 —— 开机后 Root 模块会自动安装 `MiPayLiquidGlass.apk`；
3. 打开 **LSPosed 管理器 → 模块 → 小米智能卡 · 液态玻璃**，启用并确认作用域勾选了
   `com.miui.tsmclient`（以及可选的 `com.mipay.wallet`），
   **以及 `com.android.systemui`（系统界面）** —— v1.4.0 的刷卡动画需要它；
4. 强行停止「小米智能卡」，重新打开即可看到效果。
   更省事的做法：打开模块设置界面 → **Root 操作** → 点 **一键重启小米智能卡**，
   它会自动申请 Root 权限、强停并重新拉起目标应用。

### 方式二：手动安装

1. 直接安装 `MiPayLiquidGlass-v2.0.0.apk`；
2. 在 LSPosed 中启用模块并勾选作用域；
3. 重启目标应用（建议重启设备以确保模糊属性生效）。

### 从源码构建

```powershell
cd "MiPayLiquidGlass"

# Windows 下请显式指定带 jlink 的 JDK 17（AGP 8.2 需要完整 JDK 而非 JRE）
.\gradlew.bat assembleRelease "-Dorg.gradle.java.home=C:\path\to\jdk-17"
```

产物：`app/build/outputs/apk/release/app-release.apk`

> 若 `gradlew` 首次运行需要联网下载 Gradle 发行包失败，可直接使用本机已有的 Gradle：
> `gradle assembleRelease "-Dorg.gradle.java.home=<你的 JDK 17>"`

---

## 配置项

打开模块自带的设置界面（应用列表里的「小米智能卡 · 液态玻璃」）即可实时调整，**修改后需强制停止目标应用再重开**。

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| 启用液态玻璃 | 开 | 总开关，关闭后立即恢复系统原样 |
| 背景模糊半径 | 32 dp | 窗口背后内容的高斯模糊强度 |
| 玻璃染色强度 | 64 / 200 | 玻璃本体的白度，越大越「奶」 |
| 玻璃圆角半径 | 26 dp | 无法从原背景推断圆角时的兜底值 |
| 折射与色散高光 | 开 | AGSL 边缘菲涅尔高光 + 彩虹色散（需 Android 13+） |
| 流光动效 | 关 | 玻璃表面缓慢流动的镜面高光，会略微增加耗电 |
| 深度美化 | 关 | 把纯色背景的容器也转换为玻璃，视觉更统一但改动更大 |
| 沉浸模式 | 关 | 系统栏透明 + 根背景半透明，透出被模糊的桌面内容 |
| 保留设备卡面 | **开** | 识别为卡面 / 图片素材的视图完全不作修改（强烈建议保持开启） |
| 精准适配小米智能卡 | **开** | 按反编译得到的类名白名单直接命中刷卡页的自绘控件；**关闭后刷卡页几乎没有任何效果** |
| 卡面叠加玻璃镜面 | **开** | 在保留的卡面上叠折射高光与流光，只加质感、不遮图案 |
| 玻璃边缘亮线 | **关** | 给每块玻璃描一圈白色亮边。**默认关闭** —— 页面元素多时大量亮边叠加会形成满屏线条 |
| 卡面圆角 | **3.18 mm** | 卡面玻璃罩的圆角，按屏幕物理尺寸换算；3.18mm（1/8 英寸）与卡面严丝合缝 |
| iOS 超级岛样式 | **开** | 把刷卡「超级岛」改写为 iOS 灵动岛观感：标题转白、副标题 60% 白、隐藏右侧图标与按钮 |

**建议**：先只开「启用液态玻璃 + 折射」看基础效果，再按需开启「深度美化」和「沉浸模式」。
某些应用的布局未做 edge-to-edge 适配，开启沉浸模式后可能出现状态栏遮挡，此时关掉即可。
「保留设备卡面」默认开启且不建议关闭 —— 关掉后卡片封面会被一起玻璃化。

---

## 一键重启（Root）

改完配置后，目标应用必须**重新启动**才会加载新设置。为此模块自带 Root 操作面板，
不用再跑去系统设置里手动强停：

1. 打开模块设置界面，滑到 **Root 操作**；
2. 点 **一键重启小米智能卡**；
3. 首次会弹出 Root 授权请求，允许即可；
4. 模块会依次执行：`su -c id` 验证权限 → `am force-stop com.miui.tsmclient`
   → 等待 0.7s → 由本应用重新拉起小米智能卡。

执行细节：

- Root 命令通过 `ProcessBuilder("su", "-c", command)` 调用，避免了不同 `su` 实现
  对参数转义的差异；
- `am force-stop` 失败时（部分定制 ROM 会收紧该指令）自动回退到 `pkill -f`；
- 重新拉起用的是 `PackageManager#getLaunchIntentForPackage` + `startActivity`，
  因此清单里声明了 `<queries>`，否则 Android 11+ 会因包可见性限制解析不到目标应用；
- 除了「一键重启」按钮，还提供 **检测 Root 权限** 单独验证，不执行任何重启动作；
- 面板上会实时回显状态：已获取 / 未授权 / 已重新打开 / 未检测到小米智能卡。

> 本功能只执行「查询权限」与「停止进程」两类动作，不写文件、不改系统属性。

---

## 兼容性

| 项目 | 支持情况 |
| --- | --- |
| Android 版本 | 8.0+ 可运行；12+ 有真背景模糊；13+ 有折射色散 |
| LSPosed | 传统 Xposed API，1.8 ~ 2.x 均兼容 |
| 小米智能卡 | 不依赖内部类名，跨版本稳定 |
| 小米钱包 | 已内置为第二目标包，效果取决于其页面结构 |

### 自行扩展目标应用

默认目标包定义在 `LiquidGlassModule.java` 的 `TARGET_PACKAGES`，同时记得同步
`res/values/arrays.xml` 里的 `xposed_scope`（LSPosed 的推荐作用域）：

```java
private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
        PKG_SMART_CARD,     // com.miui.tsmclient   小米智能卡
        PKG_MIPAY_WALLET,   // com.mipay.wallet    小米钱包
        PKG_NEXTPAY));      // com.miui.nextpay    网页组件（1.0.1 新增）
```

### 关于 `com.miui.nextpay`（网页组件）

它是**插件式伴生包**，不是普通依赖库：dex 里存放着
`com.miui.tsmclient.ui.inapp.TagPayActivity`、`ui.scanpay.ScanPayActivity`、
`ui.records.*` 等**主包命名空间**的类，由主包动态加载；同时自己提供 H5 能力
（`miuipub.hybrid.HybridView` 与 `com.miui.nextpay.hybrid.HybridActivity`）。
它是**独立进程**，1.0.0 未纳入作用域，导致这些页面完全没有被处理。

---

## 常见问题

**Q：完全没有模糊效果？**
A：先确认 `ro.surface_flinger.supports_background_blur` 是否为 `1`
（运行 `getprop ro.surface_flinger.supports_background_blur`）。若为 `0` 或空，
说明你的 ROM 未开启该能力 —— Root 模块会尝试补齐，但需要重启。
低版本系统（< Android 12）只能得到静态玻璃材质。

**Q：改了设置但应用里没变化？**
A：模块在进程启动时读取配置。请到「设置 → 应用管理 → 小米智能卡 → 强行停止」，
再重新打开。

**Q：某些按钮变得不好看了？**
A：关掉「深度美化」，它只保留对「本身带圆角的卡片 / 按钮」的处理，改动更克制。

**Q：我的交通卡封面 / 银行卡片面会被改掉吗？**
A：不会。`CardFaceGuard` 会把 ImageView、Bitmap 背景、自定义 Drawable 以及资源名带
`card / cover / img` 等关键词的视图（连同子树）整体跳过，原样保留；
只在其上层叠一层只含折射高光与流光的玻璃镜面。若你希望连这层镜面也不要，
把「卡面叠加玻璃镜面」关掉即可。

**Q：会不会影响刷卡 / 支付安全？**
A：不会。模块只修改 `setBackground` / `setForeground`、窗口模糊半径与系统栏颜色，
不读取也不修改任何卡号、余额、指纹或支付接口，不 Hook 任何业务方法。
「一键重启」也只执行 `su -c id` 与 `am force-stop`，不写文件、不改系统属性。

**Q：怎么恢复原样？**
A：在设置界面关掉总开关，或在 LSPosed 中停用模块后重启目标应用。

**Q：一键重启提示「未授权」？**
A：说明 Root 授权被拒绝或设备未 Root。请在 Root 管理器（Magisk / KernelSU）里
把本模块的授权设为允许，再回到设置界面点「检测 Root 权限」验证。

**Q：排障日志在哪？**
A：Root 模块的日志在 `/data/local/tmp/mi_pay_liquid_glass.log`；
LSPosed 侧日志在 LSPosed 管理器的日志页，过滤 tag `MiPayLiquidGlass`。

---

## 刷卡动画方案（待定方向）

目标：刷卡时出现 **iOS 风格动画** —— 卡片翻转 → 卡下方打勾 / 打叉表示交易状态
→ 再下方显示**余额**与**交易金额**。

这套动画不是「改样式」，而是一套自定义动画，**载体选在哪里**决定了可行性与风险，
必须先定方向。三条路线：

| 方案 | 做法 | 优点 | 代价 |
| --- | --- | --- | --- |
| **A. 岛内动画** | 用本模块自己的布局替换通知里的 `miui.focus.rv`，让 HyperOS 大岛渲染我们的内容（模块 APK 已安装，`RemoteViews` 可以引用模块自己的资源） | 不碰 SystemUI，**零宕机风险**；与系统岛融为一体；**锁屏也能显示** | `RemoteViews` 只能用白名单控件与有限动画（ViewFlipper 等），「3D 翻转」只能做到近似，精细度受限 |
| **B. SystemUI 注入** | 模块作用域扩展到 `com.android.systemui`，在 SystemUI 进程里新建一个自有窗口（可盖锁屏），用自绘 View 做完整动画 | 动画可以**完全做到位**（真 3D 翻转、时序、缓动） | 改 SystemUI 风险高：一旦异常会导致状态栏消失、需要重启；且**无法在本机验证**，要靠你反复实测 |
| **C. App 悬浮窗** | `TYPE_APPLICATION_OVERLAY` 普通悬浮窗 | 最简单 | **锁屏下不显示**，而刷卡大多发生在锁屏 / 息屏，因此不满足需求 |

**推荐路径**：先做 **A**（安全、锁屏可用、与岛一体），把「翻转 + 勾/叉 + 余额 + 金额」
的**信息结构与观感**做出来；若你觉得翻转的精致度不够，再在 A 的基础上追加 **B**
（A 的数据链路可完全复用）。

数据来源已通：余额与交易金额可以从 App 投递的焦点通知（`miui.focus.rv` 与通知文本）
里取到 —— 这部分 `IslandStyler` 已经在钩。

---

## 更新日志

### v2.0.0

**本版改动**

- **正式发布 2.0.0**。经过 1.x 系列对页面材质、卡面保护、超级岛动画与交易详情的持续打磨，
  页面、弹窗、浮层与卡面叠加的液态玻璃材质已趋于稳定，本次作为 **2.0.0 正式版**发布。
- **交易详情页顶栏对齐 Miuix 原生 `TopAppBar`**（[Miuix](https://github.com/compose-miuix-ui/miuix)）：
  顶栏背景为不透明 `surface`（`color` 默认值），**不做毛玻璃**；返回键为扁平返回箭头
  （起始边距 16dp）；折叠高度 `TopAppBarDefaults.CollapsedHeight` 为 **52dp**；无 elevation。
  地图页顶栏同步为同一扁平样式。
- 详细的历史改动见下方 v1.7.x 各条。

### v1.7.11

**本版改动**

- **顶栏改回 Miuix 原生 TopAppBar 样式（不做毛玻璃）**。v1.7.10 给顶栏铺了半透明玻璃底，
  这一版按需求撤掉：顶栏背景恢复为不透明 `surface`（与页面同色），与官方
  [Miuix](https://github.com/compose-miuix-ui/miuix) 的 `TopAppBar`（`color` 默认 `surface`）一致。
- **顶栏返回键恢复为扁平返回箭头**。撤掉圆形玻璃底，回到 Miuix 的纯箭头导航图标
  （`plainBackButton`），起始边距 16dp；同时去掉顶栏投影 —— Miuix 顶栏是扁平 `surface`，无 elevation。
- **折叠高度对齐 Miuix**：`TopAppBarDefaults.CollapsedHeight` 由 56dp 改为 **52dp**。
- 地图页顶栏返回键同步改为同一扁平样式，两处顶栏观感统一。

### v1.7.10

**本版改动**

- **交易详情页顶栏改回液态玻璃**。上一版为了「严格对齐 Miuix TopAppBar 规范」，
  把顶栏背景写成了不透明 `surface`（`setBackgroundColor`），所以折叠后永远不可能半透明。
  本版把顶栏背景换成 `LiquidGlassDrawable` 的半透明磨砂底，内容上滑到顶栏下方时会透出来。
- **顶栏返回键改回玻璃圆底**。上一版用的是 Miuix 的扁平纯箭头（`plainBackButton`，无 background），
  本版换回「圆形液态玻璃底 + 箭头」（`navBackButton`）。

### v1.7.9

**本版改动**

- **交易通道改为真正的 TU / CU**。「交易通道」在交通卡领域指**卡上的应用通道**，
  不是交易记录里的字段 —— 之前我拿卡内 `ordinalTrafficType`（本机地铁是 `01`）当通道，
  方向就错了。按公开交通卡协议资料与开源项目
  [TU-Reader（行旅录，Apache-2.0）](https://github.com/HaoTian22/TU-Reader)：
  交通联合应用 AID `A000000632010105` → `TU`；住建部城市一卡通 AID `...86980701` → `CU`。
  现在从 `CardInfo#getCardAid()` 取 AID 判定，实机日志：
  `AID=A00000063201010511215449414E4A49 → TU`、`AID=A0000000032660869807010000000000 → CU`。
  界面显示形如 `TU · 地铁`。
- **顶栏按 Miuix TopAppBar 重做**：折叠高度 56dp、标题水平内边距 26dp、
  导航图标距左边 16dp、背景恒为 `surface`；折叠后的小标题从「居中」改成 Miuix 的
  「左对齐、跟在返回箭头之后」（17sp）。
- **修导航栏层级**：顶栏原先在 `ScrollView` 之前加入根布局，后加入的内容会盖住它。
  现在 `bringToFront()` + `elevation` 双保险，内容从顶栏下方穿过并被完全遮住。
- **修顶栏背景不透明**：去掉了早期那层 `alpha` 从 0 渐显的假底 —— 那会让折叠瞬间
  顶栏是半透明的。现在顶栏底色是恒定的不透明 `surface`，滚动时只切换标题。


- **所有 UI 统一到 Miuix 设计语言**（`compose-miuix-ui/miuix`）。色板逐项对齐官方令牌：
  浅色 `surface #F7F7F7` / `surfaceContainer #FFFFFF` / `onSurfaceContainer #000000` /
  `onSurfaceContainerVariant #959595` / `primary #3482FF` / `dividerLine #E0E0E0`，
  深色对应 `#000000` / `#242424` / `#E6FFFFFF` / `#737373` / `#277AF7` / `#393939`。
  实机逐像素核验（深色模式）：页面底色 `#000000`、卡片 `#242424`、药丸 `#2B3B54`
  （`tertiaryContainer`）、强调色 `#277AF7`、分隔线 `#393939` —— 全部命中官方值。
- **设置页整体重做**：页面底色换成 `surface`，分组面板换成 `surfaceContainer` + 20dp 圆角的
  Miuix Card，分隔线换成 `dividerLine` 并按 Miuix 惯例左缩进对齐文字；
  主按钮 `primary`、次按钮 `secondaryContainer`（都为 12dp 圆角并带按下态）、
  徽标改成 `tertiaryContainer` 全圆角药丸；开关与滑块的强调色统一到 `primary`。
- **排版遵循 Miuix Text Styles**：分组小标题 `subtitle 14sp Bold`、行标题 `main 17sp`、
  行摘要 `footnote1 13sp`、页面大标题 `title2 24sp`；层级只用字号与颜色区分，
  去掉了旧版靠字重和宽字距堆出来的「科技感」。
- **深色模式由资源自动切换**：新增 `values-night/`，同一套布局在两个模式下各自命中官方令牌，
  不再有硬编码的 `#1FFFFFFF`、`#08122B` 这类颜色。
- 说明：灵动岛（液态玻璃动画）与宿主页面上叠加的玻璃材质**保持原样**——
  那是本模块的核心效果，不属于「页面 UI 样式」；如果你希望岛本身也改成 Miuix 扁平卡片，
  告诉我，我单独改一版。

- **交易详情与地图都改成整屏页面**，并按 **Miuix**（`compose-miuix-ui/miuix`）官方色板重做：
  页面底色 `surface #F7F7F7 / #000000`、卡片 `surfaceContainer #FFFFFF / #242424`、
  强调色 `primary #3482FF / #277AF7`、分隔线 `dividerLine #E0E0E0 / #393939`，
  文案层级用 `onSurfaceContainer` 与 `onSurfaceContainerVariant`。
- **适配状态栏与导航栏**：窗口 edge-to-edge、两条系统栏全透明，底色由页面自己铺，
  再按 `systemBars` 的 insets 给根布局补上下留白，并按页面明暗切换系统栏图标明暗。
  状态栏与手势条叠在页面底色上不再出现割裂色块。
- **地图跟随页面滚动**，并支持**放大缩小**：地图位图按容器 1.4 倍渲染，
  右下角两枚液态玻璃圆底 `+` / `−` 按钮；地图上可直接**双指捏合**缩放、
  **双击**放大/复位、放大后**拖动**平移。未缩放时单指竖向滑动会把触摸交还给外层
  `ScrollView`，所以拖地图也能滚页面。
- **返回键改为液态玻璃**：导航栏箭头坐在一枚圆形 `LiquidGlassDrawable` 上。
- **交易通道显示真实字段**。反编译确认交易数据对象是
  `com.tsmclient.smartcard.model.TradeLog`，其中 `mTrafficType`（地铁/公交）、
  `mOrdinalTrafficType`（通道码，本机地铁记录为 `01`，北京一卡通公交记录为 `TU`/`CU` 一类）、
  `mConsumptionType`、`mTradeType`、`mTerminalNo`、`mTradeID` 这些字段**应用界面全都不显示**。
  现在「交易通道」一栏显示「通道码 · 交通类型」（实机为 `01 · 地铁`），
  并新增一整组**「凭证信息」**：交通类型 / 通道码 / 消费类型 / 交易类型码 / 终端编号 / 交易凭证号。
- **「更多交易信息」补全**：新增「站点官方名」（高德 POI 标题，如「草桥(地铁站)」）、
  「所属区域」（如「北京市丰台区」）、「途经线路」（如「10号线／19号线／大兴机场线／房山线」），
  以及「状态」（由金额正负推导：扣费成功 / 入账成功 / 待扣费）。
  页面按「行程 / 交易信息 / 凭证信息 / 站点信息」分组，并标注「地图数据 © 高德软件」。
- 顺带修掉一个隐患：交易行的点击监听原先会挂到**上层大容器**上
  （因为 `item_container` 是递归查找的），导致点列表任何位置都弹第一条记录。
  现在改成只认浅层（≤4 层）真正的行容器。

**为什么「交易通道」要这么绕才能拿到**

| 尝试 | 结果 |
| --- | --- |
| `XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$Adapter")` | LSPosed 会把类名里的点改写成 `$`（`androidx$recyclerview$...`）后找不到类 |
| `Class.forName(同类名, appClassLoader)` | 同样 `ClassNotFoundException` —— 宿主这个类不在模块可见的 classloader 里 |
| 从视图树按类名后缀找 `RecyclerView`，再反射 `getAdapter()` | 走通；再用 `getChildViewHolder` 拿真实位置，按时间数字匹配兜底 |
| 最终定位到 `TradeLog` 的 `mOrdinalTrafficType` | 即「通道码」，实机地铁为 `01` |

- **交易详情新增「站点 + 时间 + 交易地图」**（iOS 样式）。交易记录里每一行本来就有
  站点 / 时间 / 金额（`tv_first_sub_key` / `tv_sub_value` / `tv_value`），
  但这些行 `clickable=false`，点上去没有任何反馈。现在接管点击，弹出 iOS 风格卡片：
  顶部一张真实地图、下面是金额与状态、再下面是「站点 / 时间 / 类型 / 金额」四行
  （左灰标签、右黑值），底部一枚天蓝「完成」按钮。
- **交易地图**：用高德栅格瓦片（`webrd0x.is.autonavi.com`，中文标注、无需 key）
  按站点经纬度拼图，双站点时自动连线 + 两端图钉，单点居中打钉。
  坐标来自系统 `Geocoder`（MIUI 上由高德提供），站点名形如「草桥-首经贸」会拆成两段分别解析。
- **卡面清晰度**：重新启用「磁盘高清匹配」，并给落盘加了 96px 短边门槛。
  之前岛上卡面只有 `96x60`（那是设置页「选择卡面」的列表缩略图被一起落盘了），
  放大到约 324x192 的卡片上要放 5 倍，必然糊。现在会拿磁盘里同一张卡的高清图替换，
  实机日志印证：`高清匹配「京津冀互联互通卡」最佳距离=257140 次佳=888564`（最佳比次佳好 3.4 倍）。

**踩掉的坑（都写在这里，省得后续再踩）**

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| 「改了没生效」 | 动画跑在 SystemUI 进程，而它常年不重启；`am force-stop com.android.systemui` 会被系统忽略 | 装完 APK 必须重启 SystemUI（用 `am crash`）；日志里 `SystemUI 侧加载的模块版本 = x.y.z` 一眼可辨 |
| 误判「充值按钮分支从未触发」 | 只看日志时那段窗口里用户根本没打开卡详情页 | 用 `adb logcat` 实时复现：`充值按钮 → 天蓝纯色磨砂玻璃 id=tv_top_up` 确实在跑 |
| 交易条目拿不到 | 交易记录是异步拉取的，onResume 后 420ms 那次遍历跑的时候列表项还没创建 | 改挂 `RecyclerView.Adapter#bindViewHolder`，每行绑定时接管点击 |
| 地图取不到瓦片 | `tile.openstreetmap.org` / `nominatim.openstreetmap.org` 在国内容器内不可达（连接超时） | 改用高德栅格瓦片（实测 HTTP 200），解析/下载失败时降级为风格化地图卡 |

**iOS 风格与配色**

- 卡片：浅色 `#FFFFFF` / 深色 `#1C1C1E`，20pt 圆角；标题 17pt Semibold 居中；
- 明细行：左 15pt 灰标签 `#8E8E93`、右 15pt 中黑值，行间 1px 分隔线；
- 金额：32pt Heavy，单位小一号灰色，右侧绿色「交易成功」；
- 完成按钮：天蓝 `#34A0FF` 纯色，14pt 圆角。

**刷卡动画（SystemUI 自绘，锁屏可见）**

- **进出动画**：整块岛从屏幕顶部「探出」，消失时加速缩回顶部并淡出；
- **卡片翻转**：翻转在岛开始出现时就起步。**翻转前那一面是卡背（默认卡面），
  翻定后是卡正面（真实卡面）**；翻转拆成两半、两半都只转 0°~90°，
  几何上不产生左右镜像，从根上避免「卡面印反」；
- **打勾 / 打叉**：纯白线条逐段描出（`PathMeasure`），无彩色圆底；
- **卡背**：使用 `res/drawable-nodpi/card_default_back.png`（主色 `#177EE6` +
  居中白色徽标），centerCrop 铺满并裁圆角；拿不到真实卡面时也用它兜底；
- **触发去重**：广播与焦点通知两条路径 1.5s 内只播一次。

**卡面识别（拿你自己那张卡）**

- **按卡名识别**：从卡片列表条目里读 `card_list_item_card_name`，
  拿到「哪张卡 + 卡面图」这对完整信息，而不是靠图片比例猜；
- **「选择卡面」列表**：设置页可看图选卡，缩略图跨进程传过来
  （96px JPEG，39 张约 150KB，远低于广播上限）；第一项是「自动（跟随主卡）」；
- **结果落盘**：识别结果写到
  `/sdcard/Android/data/com.miui.tsmclient/files/faces/`，进程重启后自动读回，
  不必每次都先打开卡片列表；
- **删掉「盲扫磁盘缓存」兜底**：那条路只看宽高比，分不清是哪张卡，
  会把银行卡 / 门卡之类比例相近的图当成本卡画出来。现在拿不到就显示中性默认卡面，
  **绝不再画错卡**。


## 卸载

1. 卸载 `com.liquidglass.mipay`；
2. 在 Magisk / KernelSU 中删除 `Mi Pay Liquid Glass (Root)` 模块并重启
   （`uninstall.sh` 会清理仅由本模块安装的 APK 与临时文件）。

---

## 说明

本项目为个人设备的**界面美化**工具，仅修改视觉呈现，不涉及任何支付逻辑、账号数据或安全模块。
请在遵守当地法律与相关服务条款的前提下，于你**自己拥有**的设备上使用。
