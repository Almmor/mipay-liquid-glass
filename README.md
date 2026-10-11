# 小米智能卡 · 液态玻璃（Mi Pay Liquid Glass）

让 **小米智能卡（`com.miui.tsmclient`）** 与 **小米钱包（`com.mipay.wallet`）** 的刷卡 / 支付页面
支持 iOS 26 风格的 **Liquid Glass（液态玻璃）** 材质，并对刷卡页面做整体美化。

整套方案由两个可独立工作的部分组成：

| 组成 | 形态 | 作用 |
| --- | --- | --- |
| **LSPosed 模块** | APK（`MiPayLiquidGlass-v1.0.7.apk`） | 运行时把刷卡页、选卡页、卡详情、支付确认弹窗与浮层改造成液态玻璃材质，**完整保留设备卡面**；内置设置界面与「一键重启小米智能卡」（Root） |
| **Root 模块** | Magisk / KernelSU zip（`MiPayLiquidGlass-Root-v1.0.7.zip`） | 开启系统级窗口模糊能力，并在开机后自动安装 / 更新上面的 APK |

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
├── MiPayLiquidGlass-v1.0.7.apk          ← 装好的 LSPosed 模块（可直接安装）
├── MiPayLiquidGlass-Root-v1.0.7.zip     ← Root 模块（Magisk / KernelSU 直接刷入）
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
│       │       ├── AppleStyle.java            （余额区 Apple Wallet 排版 + 充值按钮淡蓝胶囊，1.0.3）
│       │       ├── RadiusProbe.java           （卡面圆角自动探测，1.0.3）
│       │       ├── GlassWindowStyler.java     （窗口模糊 + 沉浸模式）
│       │       └── GlassViewStyler.java       （视图树玻璃化 + 卡面剪枝）
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

1. 用 Magisk / KernelSU 刷入 `MiPayLiquidGlass-Root-v1.0.7.zip`；
2. 重启设备 —— 开机后 Root 模块会自动安装 `MiPayLiquidGlass.apk`；
3. 打开 **LSPosed 管理器 → 模块 → 小米智能卡 · 液态玻璃**，启用并确认作用域勾选了
   `com.miui.tsmclient`（以及可选的 `com.mipay.wallet`）；
4. 强行停止「小米智能卡」，重新打开即可看到效果。
   更省事的做法：打开模块设置界面 → **Root 操作** → 点 **一键重启小米智能卡**，
   它会自动申请 Root 权限、强停并重新拉起目标应用。

### 方式二：手动安装

1. 直接安装 `MiPayLiquidGlass-v1.0.7.apk`；
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

## 更新日志

### v1.0.7

给充值按钮也加上液态玻璃。

- **充值按钮 = Apple 蓝胶囊 + 液态玻璃**：用 `LayerDrawable` 两层叠加 ——
  底层保留 Apple 蓝渐变胶囊（`#007AFF` / 深色 `#0A84FF`，含按下加深），
  上层叠一块 overlay 形态的 `LiquidGlassDrawable`（强度 1.5），
  得到「玻璃盖在蓝色按钮上」的通透高光。
- 上层仍是 overlay 形态：**不描边、不做色散**，不会给按钮添上线条；
  按下态同样带玻璃高光。
- 新增 `AppleStyle#RECHARGE_GLASS_STRENGTH` 控制按钮玻璃强度。

### v1.0.6

定位并修掉「余额背景发黑、无法透明」的**真正根因**。

反编译证据：余额面板背景 `card_balance_bg` 是
`<shape><corners radius="@dimen/swipe_card_res_view_container_radius"/>`
`<solid color="@android:color/background_light"/></shape>` ——
也就是 **`@android:color/background_light`（白色）**；而它的深色版本
`card_blur_balance_bg` 用的是 `swipe_card_res_view_lite_bg`，
值域是 `#e5ffffff`（白 90%）/ **night `#e5141414`（黑 90%）**。

结论：这个余额面板在 App 自身设计里是一块**白色毛玻璃面板**，
而模块之前给它套的是「跟随系统深色」的玻璃 + 极淡叠加 —— 深色页面下自然被看成一块黑。

落地：

- 余额面板改用**固定的浅色玻璃配方**（不跟随系统深色），并新增玻璃「强度倍率」
  （`LiquidGlassDrawable` 的 `strength` 参数）把铺底与高光提亮，
  得到**可见的透明毛玻璃**，而不是一片透明或一块黑。
- 仍是 overlay 形态：**不描边、不做色散**，不会引入线条；流光刻意不随强度放大，
  避免面板上出现斜向亮带。
- 其它「余额 / 充值类面板」（卡详情页等，走关键词通道）统一复用同一套浅色毛玻璃
  （新增 `AppleStyle#applyPanelGlass`），彻底杜绝深色下的黑块。

### v1.0.5

按 Apple Pay 观感做最后一轮打磨。

- **卡面高光调暗**：叠加模式的铺底、膨光、流光、内阴影整体下调
  （铺底 0.26→0.18、膨光基数 0.42→0.26、流光 48→28、内阴影 0.34→0.22），
  卡面上的玻璃更轻、不再刺眼。
- **余额区改为透明液态玻璃底**：`AppleStyle#applyGlassBackground` 用 overlay 形态的
  `LiquidGlassDrawable` 作为余额区背景 —— 既透（看得见页面本身），又有玻璃柔光，
  不会像纯色深底那样压出一块黑。
- **余额区显示格式对齐 Apple Pay**：把 `balance_text_layout` 由横向改为竖向，
  形成 Apple Wallet 的「**余额**（13sp 次级灰）在上、**¥19.75**（28sp 主色 medium）在下」层级；
  充值按钮保持 Apple 蓝胶囊。
- `preview.html` 依旧不随版本更新。

### v1.0.4

针对实机反馈的「满屏乱线 / 余额发黑 / 卡面圆角错位」三个问题，从根因上一次性修掉。

- **不再覆盖窗口背景（修余额发黑）**：`GlassWindowStyler` 之前把 Activity 的窗口背景
  替换成了深色玻璃，而这个页面的整页渐变恰恰是 App 用 `android:windowBackground` 画的。
  替换后，所有透明区域（包括已经去掉背景的余额区）都透出这块深色玻璃，看着就是一块黑。
  现在**只有沉浸模式才替换窗口背景**；弹窗仍保留（弹窗本身透明，需要玻璃出效果）。
- **边缘亮线默认关闭（修满屏线条）**：每个被玻璃化的元素原本都会描一圈白边 + 左上角弧线，
  一屏几十个圆角容器叠加就是满屏线条。现在 `rimStroke` 默认关闭，并移除了那条
  「左上角高亮弧线」（它与圆角对不齐时会产生交叉乱线）。
- **卡面玻璃罩不再做色散折射**：叠加模式跳过 AGSL 色散，避免卡面边缘出现彩色乱线。
- **卡面圆角按物理尺寸 3.18mm**：新增「卡面圆角」设置（单位 mm，默认 3.18 = 1/8 英寸），
  按屏幕 `xdpi` 精确换算，玻璃罩与卡面严丝合缝，不再出现圆角错位的双层轮廓。
- 设置界面新增「玻璃边缘亮线」开关与「卡面圆角」滑杆。

### v1.0.3

按 Apple Wallet 排版重做余额区，并自动探测卡面圆角。

- **余额区去背景**：`layout_balance_reminder` 不再套任何底色框，
  「余额」小标签 + 大金额直接浮在页面上；文字改用 iOS `labelColor` /
  `secondaryLabelColor`，并跟随深浅色。
- **充值按钮 Apple Pay 化**：`btn_balance_reminder_recharge` 改为淡蓝色渐变胶囊 +
  白色 medium 文字，按下加深；浅色 `#007AFF`、深色 `#0A84FF`。
- **新增 `RadiusProbe` 卡面圆角自动探测**：背景 Shape → Outline → 反射圆角容器
  （MiuiX `SmoothFrameLayout2`）→ 向上层容器递归，四路兜底，失败才用默认圆角。
  玻璃罩因此与卡面严丝合缝。
- **新增 `AppleStyle`**：集中管理余额区排版与充值按钮样式；`GlassChrome` 拆出
  `isBalancePanel` / `isRechargeButton` 独立通道。
- `preview.html` 自本版起不再随版本更新，仅保留为 1.0.x 的静态示意。

### v1.0.2

修复 UI 错乱，并把顶部按钮 / 余额框 / 充值按钮纳入液态玻璃，深浅色双形态。

- **修复 UI 错乱**：收紧叠加规则 —— 只有「短边 ≥ 96dp 的卡面级大图」才叠玻璃罩，
  普通图标与高度超过半屏的大容器一律不叠，消除「玻璃横带横穿页面」的问题；
  背景支持解包 `selector`（`StateListDrawable`）后再判定。
- **新增 `GlassChrome` 界面控件通道**：按反编译得到的真实 id 命中顶部按钮
  （`iv_open_scan` / `swiping_help` / `swiping_settings`）、余额显示框
  （`layout_balance_reminder`）与充值按钮（`btn_balance_reminder_recharge`），
  换玻璃背景但**图标、文字、数值全部保留**。
- **深浅色双形态玻璃**：跟随系统 `uiMode` 自动切换 —— 浅色「白玻璃」（亮铺底 + 冷白高光）、
  深色「烟玻璃」（深蓝黑铺底 + 更亮边缘高光）；窗口背景一并跟随。
- `CardFaceGuard` 关键词收紧：移除过宽的 `art`，避免误伤非卡面控件。

### v1.0.1

反编译小米智能卡（`com.miui.tsmclient`）与网页组件（`com.miui.nextpay`）后的适配版本。

- **新增「精准适配」通道**：`GlassTargets` 类名白名单直接命中刷卡页的自绘控件。
  修复 1.0.0 在刷卡页命中数为 0 的问题（该页所有 View 在 XML 中都没有 background）。
- **作用域补齐 `com.miui.nextpay`**：反编译确认网页组件是独立进程的插件伴生包，
  承载 `TagPayActivity` / `ScanPayActivity` 与 H5 容器，1.0.0 完全遗漏。
- **新增保护**：`LottieAnimationView`（刷卡页 `notice_icon` 动画）与
  `miuipub.hybrid.HybridView`（H5 容器）列入「绝不触碰」清单。
- **防重影**：同一子树内只允许叠一层玻璃镜面（`overlayConsumed` 标志沿递归传递）。
- 设置界面新增「精准适配小米智能卡」开关；新增 `docs/hook-targets.md` 反编译报告；
  新增 `tools/adb-liquid-glass.ps1` 诊断脚本。

### v1.0.0

首个版本：窗口背景模糊 + 六层玻璃材质 + AGSL 折射色散、设备卡面保护与叠加、
一键重启（Root）、Magisk / KernelSU Root 模块。

---

## 卸载

1. 卸载 `com.liquidglass.mipay`；
2. 在 Magisk / KernelSU 中删除 `Mi Pay Liquid Glass (Root)` 模块并重启
   （`uninstall.sh` 会清理仅由本模块安装的 APK 与临时文件）。

---

## 说明

本项目为个人设备的**界面美化**工具，仅修改视觉呈现，不涉及任何支付逻辑、账号数据或安全模块。
请在遵守当地法律与相关服务条款的前提下，于你**自己拥有**的设备上使用。
