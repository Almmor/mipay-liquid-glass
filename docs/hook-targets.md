# 小米智能卡 · 反编译分析与 Hook 目标清单

> **状态：本报告的结论已在 v1.0.1 ~ v1.5.4 全部落地**，明细见文末「七~二十四」十八节。

分析对象（工作目录下两个 APK）：

| 文件 | 包名 | 体积 | dex | Activity |
| --- | --- | --- | --- | --- |
| `小米智能卡_26.09.09.4.f.apk` | `com.miui.tsmclient` | 32.7 MB | 3 个（18.7 MB） | 131 |
| `小米智能卡网页组件_26.08.17.4.apk` | `com.miui.nextpay` | 14.5 MB | 2 个（9.4 MB） | 9 |

## 一、两个 APK 的关系

`com.miui.nextpay` 不是普通依赖库，而是一个**插件式伴生包**：它的 dex 里存放着
`com.miui.tsmclient.ui.inapp.TagPayActivity`、`com.miui.tsmclient.ui.scanpay.ScanPayActivity`、
`com.miui.tsmclient.ui.records.*`、`com.miui.tsmclient.ui.doorcard.CardApplyActivity` 等
**以主包命名空间命名**的类，并由主包动态加载。同时它自己提供 H5 能力：

- `com.miui.nextpay.hybrid.HybridActivity`（+ 内部类 `$JsObject`，JS 桥）
- `com.miui.nextpay.webview.WebViewActivity` / `OutAppWebViewActivity`
- `com.miui.nextpay.hybrid.feature.*`（MiPay / Mifare / H5Pay / Navigator / Page / KeyEvent）

> **影响**：它是**独立进程**。当前模块的作用域只写了 `com.miui.tsmclient` 与 `com.mipay.wallet`，
> 因此该进程里的任何窗口都不会被美化。

## 二、刷卡页的真实结构（最关键）

资源名虽被混淆（`res/CE.xml`），但通过 `aapt2` 解开后，页面层级完全清晰：

**`layout/switch_card_page_fragment` → `res/CE.xml`（双击电源键刷卡页）**

| View 类 | ID | 父类 | 角色 |
| --- | --- | --- | --- |
| `FrameLayout` | `switch_card_page_fragment_layout` | — | 页面根 |
| `ui.widget.PaddingBarFrameLayout` | — | — | 状态栏留白 |
| **`quick.nonenfc.SwitchCardFingerLayer`** | `finger_layer` | `LinearLayout` | 刷卡手指感应层 |
| `com.airbnb.lottie.LottieAnimationView` | `notice_icon` | — | 刷卡动画 |
| `TextView` | `notice_text` | — | 提示文字 |
| **`ui.quick.CardStackLayout`** | `content` | `FrameLayout` | **卡堆容器（卡片本体区）** |
| `ViewStub` | `qr_trading_record_view_stub` | — | 交易记录 |
| `ImageView` | `swiping_settings` / `iv_open_scan` | — | 设置 / 扫码入口 |
| **`ui.widget.SwipeCardResourcesView`** | `swipe_card_res_view` | `LinearLayout` | 刷卡结果区 |

宿主 Activity：**`com.miui.tsmclient.ui.quick.DoubleClickActivity`**（继承 `ui.BaseActivity`）。
旁证：同包存在 `layout/double_press_power_guide_fragment`、`SwitchCardPageMode`、`CardStackAdapter`，
且 `quick/nonenfc`（non-enfc = 非 NFC 快刷）与双击电源键路径一致。

## 三、精确 Hook 目标（按可信度排序）

这些类名**都没有被混淆**，是稳定的 hook 锚点：

| 目标类 | 父类 | 归属 | 建议做法 |
| --- | --- | --- | --- |
| `com.miui.tsmclient.ui.quick.DoubleClickActivity` | `ui.BaseActivity` | 刷卡页宿主 | 页面级：窗口模糊 + 遍历 |
| `com.miui.tsmclient.ui.quick.CardStackLayout` | `FrameLayout` | 卡堆 | 实例 id=`content`，叠玻璃镜面 |
| `com.miui.tsmclient.quick.nonenfc.SwitchCardFingerLayer` | `LinearLayout` | 手指层 | 实例 id=`finger_layer`，叠玻璃镜面 |
| `com.miui.tsmclient.ui.widget.MiPayCardItemView` | `RelativeLayout` | 卡列表项 | 按类名匹配，叠玻璃镜面 |
| `com.miui.tsmclient.ui.widget.SwipeCardResourcesView` | `LinearLayout` | 刷卡结果 | 实例 id=`swipe_card_res_view` |
| `com.miui.tsmclient.ui.widget.SwipingAreaDialogView` | `FrameLayout` | 刷卡区域弹窗 | 按类名匹配 |
| `com.miui.tsmclient.ui.widget.SwitchCardQRView` | `RelativeLayout` | 切卡二维码 | 按类名匹配 |
| `com.miui.tsmclient.ui.widget.SlideView` | `RelativeLayout` | 列表滑动项 | 按类名匹配 |
| `com.miui.tsmclient.ui.widget.CardView` | **`AppCompatImageView`** | **卡面本体** | **只叠加，绝不替换内容** |
| `com.miui.tsmclient.ui.widget.URLImageView` | `ImageView` | 卡面网络图 | **必须保护** |
| `com.miui.tsmclient.ui.widget.FingerLayer` | `ConstraintLayout` | `FingerLayer` 基类 | 按类名匹配 |
| `miuipub.hybrid.HybridView` | — | H5 容器 | 需注入 CSS/JS |
| `com.miui.nextpay.hybrid.HybridActivity` | — | H5 页宿主（独立进程） | 需加入作用域 |

## 四、当前模块与真实情况的 3 处差距

### 差距 1：View 层判定方式脱靶（最严重）

`GlassViewStyler` 靠「background 是不是带圆角的 `GradientDrawable`」来识别卡片与按钮。
但 `switch_card_page_fragment` 与 `card_view_item` 中**没有任何 View 在 XML 里设置 background**
（只有 ImageView 的 `src`，以及 `visibility`）。这些控件的视觉全部在自定义 View 内部实现。

**结论：现有启发式在该页面的命中数为 0。** 必须改为**类名 / ID 白名单直击**。

### 差距 2：卡面是位图，不是 Drawable

`com.miui.tsmclient.ui.widget.CardView` 继承 `androidx.appcompat.widget.AppCompatImageView` ——
卡面就是一张图片（占位符是 `@drawable/icon_card_default` 一类，运行时由 `URLImageView` 类逻辑换成真实卡面）。

- 好消息：`CardFaceGuard.isArtwork()` 因「`ImageView` 且有 drawable」会**天然命中并完整保护**它，符合「保留设备卡面」的要求。
- 若想让卡面也有玻璃质感，只能**在上层叠加**（`setForeground` + overlay 模式），不能替换背景。

### 差距 3：H5 与插件进程完全未覆盖

- `layout/hybrid_main` 的唯一子节点是 `miuipub.hybrid.HybridView`（小米自研 H5 容器），
  View 层美化对它无效，必须 hook WebView 注入 CSS/JS。
- `com.miui.nextpay` 是独立进程且不在作用域内 → 它的 `HybridActivity` / `WebViewActivity` 窗口一个都没被处理。

## 五、必须新增的保护

| 对象 | 原因 |
| --- | --- |
| `com.airbnb.lottie.LottieAnimationView` | 刷卡页 `notice_icon` 动画，一旦被替换背景/前景，动画即被破坏 |
| `com.miui.tsmclient.ui.widget.URLImageView` | 卡面网络图 |
| `com.miui.tsmclient.ui.widget.CardView` | 卡面本体（ImageView 子类） |
| `miuipub.hybrid.HybridView` / `WebContainerView` | H5 内容 |

## 六、建议的改造方案

1. **`GlassViewStyler` 增加「类名 / ID 白名单」通道**，与现有 background 启发式并存：
   命中白名单 → 直接在 `View#setForeground` 上叠 `LiquidGlassDrawable(overlay=true)`，不动原有背景与内容。
2. **`CardFaceGuard` 增加 Lottie / HybridView / URLImageView 保护规则**。
3. **`LiquidGlassModule.TARGET_PACKAGES` 与 `res/values/arrays.xml` 增加 `com.miui.nextpay`**。
4. **新增 H5 通道**：hook `miuipub.hybrid.HybridView` 或宿主 WebView 的
   `loadUrl` / `evaluateJavascript` / `WebViewClient#onPageFinished`，注入一份玻璃质感 CSS。
   这是唯一能让 H5 卡片页生效的方式。

## 七、复现命令

```powershell
# 资源名 -> 包内文件路径
aapt2 dump resources <apk> | Select-String 'layout/xxx' -Context 0,3

# 解出某个布局的真实 View 层级
aapt2 dump xmltree --file res/CE.xml <apk>

# 类的父类与接口
dexdump -f classes.dex > dd.txt   # 再从 dd.txt 里检索 "Class descriptor  : 'Lxxx;'"
```

> 注：`jadx` 源码级反编译在本机下载受限（69 MB zip 下载被截断），
> 本次结论来自 aapt2（资源/清单）与 dexdump（类结构），已足以定位 Hook 点。
> 如需 Java 源码层证据，可补齐 jadx 后对 `ui/quick/*`、`ui/widget/CardView` 三处精读。

## 七、落地情况（v1.0.1）

| 报告结论 | 1.0.1 的落地方式 |
| --- | --- |
| 背景启发式在刷卡页命中数为 0 | 新增 `glass/GlassTargets.java` 类名白名单通道，命中后只做 `setForeground` 叠加玻璃镜面 |
| 8 个自绘控件需要精准命中 | 全部写入 `GlassTargets.CLASS_NAMES`，设置界面提供「精准适配小米智能卡」开关（默认开） |
| `ui.widget.CardView` 是 `AppCompatImageView` 子类 | 由既有 `CardFaceGuard.isArtwork()` 保护；叠加走 overlay 模式，背景零改动 |
| `LottieAnimationView` 必须保护 | 新增 `CardFaceGuard.NEVER_TOUCH` 清单（含 Lottie 与 Hybrid 容器），遍历时直接剪枝 |
| `com.miui.nextpay` 是独立进程、未在作用域 | 已加入 `LiquidGlassModule.TARGET_PACKAGES` 与 `res/values/arrays.xml` 的 `xposed_scope` |
| 父子叠加会产生边框重影 | 遍历新增 `overlayConsumed` 标志沿递归传递，同一子树只叠一层 |
| H5 页面需注入 CSS | **尚未实现**，见下方「下一步」 |

### 下一步（v1.0.2 候选）

1. **H5 通道**：hook `miuipub.hybrid.HybridView` 或宿主 WebView 的
   `WebViewClient#onPageFinished`，注入玻璃质感 CSS。需要先拿到实际 H5 页面的 DOM 结构，
   否则通用 CSS 选择器容易误伤布局 —— 这也是暂缓的原因。
2. **`SwitchCardFingerLayer` 是否走独立窗口**：需 Java 源码层确认。若它通过
   `WindowManager.addView` 挂载，则不在 Activity 的 View 树内，需要单独 hook。
3. **补充 `FingerLayer` 基类**与更多 `ui/widget` 控件的白名单（需实机逐个验证视觉效果）。

## 八、v1.0.2 落地情况

1.0.1 上实机后出现「整块玻璃横带横穿页面」的 UI 错乱：启发式给大容器整块叠了玻璃镜面。
1.0.2 基于 `layout_switch_card_page_fragment` 等布局的 id 证据做了两处改造：

**界面控件通道（新增 `glass/GlassChrome.java`）**

| 目标 | id（资源表可查证） | 处理 |
| --- | --- | --- |
| 顶部按钮 | `iv_open_scan` / `swiping_settings` / `swiping_help`（`drawable/icon_swiping_help`） | `setBackground` 换玻璃背景，图标保留 |
| 余额显示框 | `layout_balance_reminder`（`dimen/balance_reminder_*` 一族可证） | 换玻璃面板背景，文字数值不动 |
| 充值按钮 | `btn_balance_reminder_recharge`（`dimen/btn_balance_reminder_recharge_text_size` 可证） | 换玻璃按钮背景，文案保留 |

**叠加规则收紧（修错乱）**

- 只有「短边 ≥ 96dp 的卡面级大图」才叠玻璃罩（`isCardFaceSized`）；
- 高度超过半屏的视图一律不叠（`isTooTall`），杜绝横带；
- 背景先解包 `StateListDrawable` / `InsetDrawable` 等「壳」再判定（`unwrap`）；
- `CardFaceGuard` 关键词移除过宽的 `art`，避免误伤非卡面控件。

**深浅色双形态**：`LiquidGlassDrawable` 新增 `dark` 形态（跟随 `Configuration.uiMode`），
浅色白玻璃 / 深色烟玻璃，窗口背景同步切换。

## 九、v1.0.3 落地情况

1.0.2 把余额框与充值按钮也套上玻璃，实机观感偏重。1.0.3 改为 Apple Wallet 排版，
并新增卡面圆角自动探测。

**余额区 / 充值按钮改走 Apple 通道（新增 `glass/AppleStyle.java`）**

| 目标 | id | 1.0.3 处理 |
| --- | --- | --- |
| 余额显示框 | `layout_balance_reminder`（背景 `card_balance_bg`） | **去背景**；`layout_balance_reminder_title` 小号次级色、`balance_text` 大号主色 |
| 充值按钮 | `btn_balance_reminder_recharge` | **淡蓝色渐变胶囊 + 白字**，按下态加深 |
| 顶部按钮 | `iv_open_scan` / `swiping_help` / `swiping_settings` | 维持圆形玻璃底 |

`GlassChrome` 相应拆出 `isBalancePanel` / `isRechargeButton` 两个精确 id 通道；
`balance` / `recharge` 关键词只用于其它页面的「带背景面板」。

**卡面圆角自动探测（新增 `glass/RadiusProbe.java`）**

反编译 `layout/card_view_item` 的关键证据：卡面图片 `com.miui.tsmclient.ui.widget.CardView`
（id `0x7f0a01ee`，`AppCompatImageView` 子类）被套在
`miuix.smooth.SmoothFrameLayout2`（id `0x7f0a01ef`）里，
圆角由该容器的 `android:radius=@dimen/common_radius`（`0x7f0704f3`）定义 ——
**圆角不在卡面自己身上**，所以必须在运行时向上层容器探测。

`RadiusProbe` 的取值顺序（命中即返回）：

1. 自身背景 `GradientDrawable`（`getCornerRadius` / `getCornerRadii`）；
2. 自身 `Outline`（`ViewOutlineProvider#getOutline`，`canClip` 时的圆角矩形半径）；
3. 反射：扫描 `getRadius` / `getCornerRadius` / 名字含 `radius`、`corner` 的 getter 与字段
   （MiuiX 圆角容器走这条）；
4. 向父容器递归（最多 4 层）重复 1~3，卡面图片即在第 4 步拿到 `common_radius`。

探测全失败才回退到配置默认圆角（26dp）。

## 十、v1.0.4 落地情况（修三个实机问题）

v1.0.3 实机反馈三个问题：满屏乱线、余额区发黑、卡面圆角错位。三者分别对应：

**1. 满屏乱线 —— 边缘亮线**

`LiquidGlassDrawable.drawRim()` 会给<b>每一个</b>被玻璃化的元素描一圈白色亮边，
外加一段「左上角高亮弧线」。刷卡页有大量圆角容器（`bg_*` 一类背景）会被启发式命中，
几十条亮边叠加即「满屏线条」；弧线与容器圆角对不齐时还会产生交叉乱线。

落地：新增 `Config#rimStroke`（**默认关闭**）门控 `drawRim`，并删除弧线绘制；
叠加模式（卡面玻璃罩）额外跳过 AGSL 色散折射，避免卡面边缘出现 RGB 彩边。

**2. 余额区发黑 —— 窗口背景被替换**

`GlassWindowStyler.applyToWindow()` 原本把 Activity 的窗口背景替换成 `LiquidGlassDrawable`。
但该页面的整页深蓝渐变是 App 自己用 `android:windowBackground` 绘制的；
替换后，页面中所有透明区域（尤其是 1.0.3 刚「去掉背景」的余额区
`layout_balance_reminder`）都会透出这层深色玻璃，看起来就是一块黑。

落地：`applyToWindow` 增加 `replaceBackground` 参数。Activity 只在**沉浸模式**下替换；
Dialog 保持替换（弹窗窗口本就透明，需要玻璃背景出效果）。

**3. 卡面圆角错位 —— 未按物理尺寸绘制**

落地：新增 `Config#cardRadiusMm`（默认 `3.18`，即 1/8 英寸），
`Utils.mm(Resources, float)` 按 `DisplayMetrics.xdpi` 精确换算为 px，
卡面玻璃罩改由 `GlassViewStyler#cardOverlayRadius` 取用该值，与卡面圆角一致。

设置界面同步新增「玻璃边缘亮线」开关与「卡面圆角」滑杆。

## 十一、v1.0.5 落地情况（Apple Pay 观感打磨）

**1. 卡面高光调暗**

`LiquidGlassDrawable` 叠加模式（卡面上的玻璃罩）的四层强度整体下调：
铺底 `0.26 → 0.18`、膨光基数 `0.42 → 0.26`、径向高光 `28 → 14`、流光 `48 → 28`、
内阴影 `0.34 → 0.22`。卡面玻璃更「轻」，不再刺眼。

**2. 余额区改为透明液态玻璃底**

`AppleStyle#applyGlassBackground` 用 **overlay 形态**的 `LiquidGlassDrawable`
作为 `layout_balance_reminder` 的背景：铺底极淡、不描边、不做色散，
于是既透（页面本身可见）又有玻璃柔光；不会像纯色深底那样压出一块黑。
圆角优先用 `RadiusProbe` 探测，失败兜底 16dp。

**3. 显示格式对齐 Apple Pay**

原布局 `balance_text_layout` 是**横向** `LinearLayout`，标签与金额并排
（「余额 19.75」）。`AppleStyle#applyAppleLayout` 运行时把它改为**竖向**，
得到 Apple Wallet 的层级：

| 元素 | id | 样式 |
| --- | --- | --- |
| 小标签 | `layout_balance_reminder_title` | 13sp · 次级标签色（浅 `#8A8A8E` / 深 `0x99EBEBF5`） |
| 大金额 | `balance_text` | 28sp · 主标签色（浅 `#1C1C1E` / 深 `#FFFFFF`）· medium |
| 充值按钮 | `btn_balance_reminder_recharge` | Apple 蓝胶囊（浅 `#007AFF` / 深 `#0A84FF`）+ 白字 |

查找子视图用 `AppleStyle#findByEntryName`（按 id 资源项名递归）。

## 十二、v1.0.6 / v1.0.7 落地情况

### v1.0.6：余额面板发黑的真正根因

用 `aapt2 dump xmltree` 解出 `card_balance_bg`（`res/U21.xml`）：

```xml
<shape>
    <corners android:radius="@dimen/swipe_card_res_view_container_radius" />
    <solid   android:color="@android:color/background_light" />
</shape>
```

即余额面板背景 = **`@android:color/background_light`（白色）**；
其深色版本 `card_blur_balance_bg` 用 `swipe_card_res_view_lite_bg`，
值域 `#e5ffffff`（白 90%）/ **night `#e5141414`（黑 90%）**。
而 `layout_balance_reminder` 与 `card_balance_bg` 只出现在
`layout/switch_card_fragment` 一个布局里，可确认这就是屏幕上那一块。

**结论**：该面板在 App 自身设计里是<b>白色毛玻璃面板</b>。
模块此前套的是「跟随系统深色」的玻璃 + 极淡叠加，深色页面下自然被看成一块黑。

**落地**：

- `Config` 之外新增玻璃强度倍率：`LiquidGlassDrawable` 增加 `strength` 参数
  （6 参构造），叠加模式下 `tint` / `bloom` / 径向高光按它放大；**流光刻意不放大**，
  避免面板上出现斜向亮带。
- `AppleStyle#applyGlassBackground` 改为 **overlay + 固定浅色（`dark=false`）+ strength 2.6**
  → 得到可见的透明毛玻璃，既不跟随系统深色变黑，也不描边不色散。
- 其它余额 / 充值类面板走新增的 `AppleStyle#applyPanelGlass`，同一套浅色毛玻璃。

### v1.0.7：充值按钮叠加液态玻璃

`AppleStyle#glassPill` 用 `LayerDrawable` 两层：

1. 底层：Apple 蓝胶囊（`#007AFF` / 深色 `#0A84FF`，按下态 `#0062CC` / `#0A6FD8`）；
2. 上层：overlay 形态 `LiquidGlassDrawable`（strength 1.5）—— 不描边、不做色散。

外层仍用 `StateListDrawable` 承载 normal / pressed 两态。

### v1.0.8：玻璃配比微调

| 元素 | 常量 | v1.0.7 | v1.0.8 |
| --- | --- | --- | --- |
| 余额面板 | `AppleStyle#BALANCE_GLASS_STRENGTH` | 2.6 | **1.4**（更透） |
| 充值按钮 | `AppleStyle#RECHARGE_GLASS_STRENGTH` | 1.5 | **2.4**（更强） |

只改这两个强度常量，绘制路径完全不变（仍为 overlay 形态，不描边、不色散）。

### v1.0.9：充值按钮改为高光磨砂玻璃

原先按钮底层是**不透明 Apple 蓝**（`#007AFF` / 深色 `#0A84FF`），本质是实心蓝按钮。
v1.0.9 把底层换成 `AppleStyle#frostedPill` 生成的**半透明白磨砂层**：

| 状态 | 深色模式 | 浅色模式 |
| --- | --- | --- |
| 常态 | 顶 `#42FFFFFF` → 底 `#26BFD4F2` | 顶 `#80FFFFFF` → 底 `#54DCE9FF` |
| 按下 | 顶 `#5CFFFFFF` → 底 `#3DBFD4F2` | 顶 `#9EFFFFFF` → 底 `#70DCE9FF` |

上层玻璃高光仍为 overlay 形态的 `LiquidGlassDrawable`（`RECHARGE_GLASS_STRENGTH = 2.4`）。
文字颜色随之改为 iOS 主标签色（浅 `#1C1C1E` / 深 `#FFFFFF`），因为半透明玻璃上的纯白字会糊掉。

### v1.1.0：余额区切换到 iOS 卡片规格

充值按钮从「磨砂玻璃」改为规格指定的**纯黑药丸**，余额区改为 **iOS 极简卡片 + 大数字**。

**卡片容器** `layout_balance_reminder`：

| 项目 | 规格 | 落地方式 |
| --- | --- | --- |
| 底色 | 浅 `#FFFFFF` / 深 `#1C1C1E` | `GradientDrawable#setColor` |
| 圆角 | 14pt | `setCornerRadius(14 × density)` |
| 阴影 | alpha 4% / Y 4pt / blur 16pt | `setElevation(4dp)` + API 28 `setOutlineSpotShadowColor(0x0A000000)` |
| 内边距 | 左右 18pt / 上下 20pt | `setPadding` |

**排版**（`balance_text_layout` 转竖向）：

| id | 规格 | 落地 |
| --- | --- | --- |
| `layout_balance_reminder_title` | 12pt / Semibold / `#8E8E93` | `sans-serif-medium` |
| `balance_text` | 34pt / Heavy / 黑（深色白）/ 字距 -0.4pt | `sans-serif-black` + `setLetterSpacing(-0.012em)` |
| 两者间距 | 4pt | `balance_text` 的 `topMargin` |

**充值按钮** `btn_balance_reminder_recharge`：高 32pt（改写 `LayoutParams.height`）、
宽自适应（`WRAP_CONTENT`）、左右内边距 18pt、圆角 16pt、纯黑底白字（深色反转）。

**卡面阴影**：`AppleStyle#applyCardFaceShadow` 在给卡面叠玻璃罩时同时设置圆角
outline（半径取 `cardOverlayRadius`）+ `setElevation(2dp)`，对应 alpha 8% / Y 2pt / blur 8pt。

> 注：View 阴影由 elevation 驱动，阴影**颜色/透明度**仅 Android 9（API 28）起可指定，
> 更低版本回退到系统默认阴影。

### v1.0.9：充值按钮改为高光磨砂玻璃（已被 v1.1.0 取代）

## 十三、v1.1.1：移除页面引导提示

需要去掉两句提示：「手机背面中上部 靠近读卡器」与「请验证指纹」。

**证据与难点**

- `请验证指纹` = `@string/verify_finger`（`0x7f1108aa`），
  它是 `notice_text`（`0x7f0a050e`）在 XML 里的默认文案。`notice_icon`
  （`0x7f0a050d`，Lottie）与 `notice_text` 同属
  `com.miui.tsmclient.quick.nonenfc.SwitchCardFingerLayer`（`finger_layer`）。
- `手机背面中上部 靠近读卡器` 在 **tsmclient 与 nextpay 两个包的字符串表里都搜不到**
  （`中上部`、`背面中上` 均无命中），说明它是**运行时写入**的动态文本。
  既然两句都落在同一个 `notice_text` 上，隐藏该视图即可一并去掉。

**落地：新增 `glass/PageTweaks.java`，双通道识别**

| 通道 | 规则 |
| --- | --- |
| 按 id | 资源项名为 `notice_text` → 隐藏 |
| 按文案（兜底） | 精确匹配 `请验证指纹` / `請驗證指紋` |
| 按文案（组合） | 必须含 `靠近读卡器`/`靠近讀卡機`，且含 `背面` 或 `中上部` |

组合规则用于避免误伤其它正常的 NFC 引导（例如「请将手机靠近读卡器进行刷卡」不含「背面」，不会命中）。

命中后仅 `setVisibility(GONE)`，不改文本、不影响布局测量。
调用点在 `GlassViewStyler#styleTree` 的最前面（优先级 -1），命中即剪枝，
不再对这些提示做玻璃化处理。

## 十四、v1.2.0：超级岛（焦点通知）

### 1. 上岛机制（反编译 `classes2.dex`）

上岛代码**不在 `classes.dex`**：`classes.dex` 只含 R 字段与少量引用，
`classes2.dex` 才是实现（含 `miui.focus*` 与 `island*` 字符串）。
命中的契约字符串：

| 字符串 | 用途 |
| --- | --- |
| `miui.focus.rv` | 自定义 RemoteViews |
| `miui.focus.param.custom` | 自定义参数 Bundle |
| `miui.focus.actions` / `miui.focus.action_1` | 动作 |
| `miui.focus.pics` / `miui.focus.pic_start` | 图片 |
| `param_island` | 岛参数 key |
| `islandTimeout` / `islandPriority` / `islandFirstFloat` / `islandProperty` | 岛行为参数 |
| `island_notification` | 通知 tag（另有 `tsmclient island notification`） |
| `IslandManager.java`、`NfcEventMonitorService:sendIslandNotification` | 关键类与投递方法 |
| `isCurrentSimpleCardSwipeSwitchOn`、`updateSimpleCardSwipeSwitchStatus` | 与岛上「刷卡状态切换」按钮相关 |
| `persist.sys.feature.island` | 能力开关 |

App 自带的岛布局 `layout/island_custom_notification_view`（`res/VC.xml`）：

```
LinearLayout(H)  paddingStart/End=island_layout_padding_*
├── FrameLayout  background=drawable/icon_island_background，尺寸=island_layout_card_icon_layout_*
│   └── ImageView  id=iv_left_icon
├── LinearLayout(V)  weight=1，marginEnd=island_layout_content_layout_margin_end
│   ├── TextView  id=tv_title     textSize=island_layout_title_text_size
│   └── TextView  id=tv_subtitle  textSize=island_layout_subtitle_text_size maxLines=1
├── ImageView  id=iv_right_icon
└── TextView   id=btn_right  background=drawable/island_custom_notification_button_bg
```

### 2. 为什么必须钩通知，而不是钩视图

岛是 `RemoteViews`，由 **SystemUI 在自身进程 inflate** ——
模块的视图树遍历（在 App 进程里跑）根本拿不到它。
但 `RemoteViews` 是在 **App 进程**构建后塞进 `Notification.extras` 的，
因此在 `NotificationManager#notify` 之前改写该对象即可，
`setTextColor` / `setFloat` / `setViewVisibility` 等动作会在 SystemUI 侧生效。

新增 `glass/IslandStyler.java`：

- `XposedBridge.hookAllMethods(NotificationManager, "notify")` —— 用 `hookAllMethods`
  覆盖 `(int,Notification)` / `(String,int,Notification)` 等重载，不依赖具体签名；
- 判定是否岛屿通知：`extras` 含 `miui.focus.rv`（RemoteViews）或 `param_island`，或 tag 为 `island_notification`；
- 改写：标题白 15sp（字距 -0.01em）、副标题 60% 白 12sp、
  `iv_right_icon` 与 `btn_right` 置 `GONE`；
- 所有 id 用 `Resources#getIdentifier(name,"id",pkg)` 按名解析（`tv_title`/`tv_subtitle`/
  `iv_left_icon`/`iv_right_icon`/`btn_right`），跨版本更稳。

### 3. 「不上岛」与「失败也上岛」

- **不上岛**：`persist.sys.feature.island` 是 App 上岛前读的能力开关。
  Root 模块在 `system.prop` 写入，并在 `service.sh` 用 `resetprop` 兜底；
  该属性为 `ro.*` 之外的可写属性，重启后生效。
- **失败也上岛**：失败路径是否调用 `sendIslandNotification` 静态分析无法确定，
  因此加了诊断日志（过滤 tag `MiPayLiquidGlass/Island`）：
  上岛通知会打印 tag/title/text；含「失败/错误/异常/重试」却未上岛的通知也会打印。
  拿到实机日志后再精准实现。

## 十五、v1.2.1：刷卡动画的载体选择

需求：刷卡时出现 iOS 风格动画（卡片翻转 → 勾/叉 → 余额 + 交易金额）。

关键判断：**动画载体决定可行性与风险**，三条路线见 README「刷卡动画方案」一节。
要点：

- **岛内动画（RemoteViews 替换）**：`RemoteViews(packageName, layoutId)` 的 `packageName`
  可以是**本模块自己的包**（模块 APK 已安装），因此模块可以自带布局与动画资源，
  在 hook 到 `miui.focus.rv` 时**整体替换**成自己的 RemoteViews。
  限制：RemoteViews 只能实例化白名单控件，动画仅支持 ViewFlipper / AdapterViewFlipper
  这类「子视图切换 + in/out 动画」机制，无法做真正的 3D 变换。
- **SystemUI 注入**：需要把 `com.android.systemui` 加入模块作用域并在其进程内建窗口。
  动画自由度最高（可盖锁屏），但崩了会导致状态栏消失，属高危改动。
- **App 悬浮窗**：`TYPE_APPLICATION_OVERLAY` 无法覆盖锁屏，不满足刷卡场景。

数据链路：余额 / 金额可从 `IslandStyler` 已钩到的焦点通知
（`miui.focus.rv` 与 `Notification.extras`）中提取，两种载体都可复用。

另：v1.2.1 已在设置界面提供「重启 SystemUI」（`RootHelper#restartSystemUi`，
依次 `pkill -f com.android.systemui` / `killall`），供后续改 SystemUI 相关配置时重新加载。

## 十六、v1.3.0：方案 A 落地（岛内 iOS 刷卡动画）

把 {@code miui.focus.rv} 整体替换为模块自己的 RemoteViews。

**模块侧新增资源**

| 资源 | 作用 |
| --- | --- |
| `layout/island_swipe_card` | 岛内容布局：卡片 flipper + 状态图标 + 金额 + 余额 |
| `drawable/bg_island_card` | 纯黑 26dp 圆角底（iOS 灵动岛） |
| `anim/island_flip_out` / `island_flip_in` | 卡片翻转的横向压扁 / 展开（scaleX + alpha） |

布局 id：`island_card_flipper`、`island_card_front`、`island_card_back`、
`island_status`、`island_amount`、`island_balance`。

**新增类**

- `IslandCardRenderer`：Canvas 现画卡片与勾/叉位图（不依赖图片资源）。
  成功 `#34C759`、失败 `#FF3B30`，白色圆头笔画。
- `IslandStyler`：改写为「替换布局 + 两段式翻转 + 数据解析」三段逻辑。

**两段式翻转**

`RemoteViews` 无法做 3D 变换，因此用 `ViewFlipper` 承载卡面前后两帧
（in/out 动画写在布局 XML 里），首发通知显示正面；
`FLIP_DELAY_MS = 520` 后由模块用 `NotificationManager#notify(tag, id, notification)`
补发同 tag / 同 id 的通知并 `setDisplayedChild(1)`，触发翻转。
补发全程用 `ThreadLocal` 的 `SELF_POST` 标记，防止钩子递归。

**失败态判定**

关键词表 `FAILURE_KEYWORDS`（失败/错误/异常/超时/余额不足/拒绝/未完成/取消/
fail/error/declined/insufficient/timeout…）命中即打叉，否则打勾。
金额 `AMOUNT_PATTERN`（`[¥￥]数字` 或 `数字元`）、余额 `BALANCE_PATTERN`
（`余额…数字`）从 `EXTRA_TITLE/EXTRA_TEXT/EXTRA_SUB_TEXT` 拼接文本中提取。

> 待实机验证项：SystemUI 能否 inflate 本模块包名的 RemoteViews、
> 补发通知是否被岛接受为「原地更新」（而非新建一条）、
> 以及 `ViewFlipper` 的 in/out 动画在岛内是否生效。

## 十八、v1.3.2：实机日志确认的上岛结构

实机 LSPosed 日志（`MiPayLiquidGlass/Island`）关键行：

```
[notify] tag=null id=10 channel=TMS_CHANNEL island=true
  rv(main)=RemoteViews?  contentView=-  big=-  headsup=-
  keys=[android.reduced.images, miui.focus.pics, miui.focus.param.custom,
        miui.focus.rv, android.appInfo, miui.focus.actions, android.showWhen]
  title=   text=
```

另有 `[startForeground] channel=TMS_CHANNEL_BACKGROUND_SERVICE island=false`（已被正确忽略）。

**确认的事实**

| 结论 | 依据 |
| --- | --- |
| 上岛走 `NotificationManager#notify` | 日志来源标记为 `[notify]` |
| 岛通知标识 | channel `TMS_CHANNEL`、`id=10`、`tag=null` |
| 岛相关 extras | `miui.focus.rv` / `miui.focus.pics` / `miui.focus.param.custom` / `miui.focus.actions` |
| 通知 `title`/`text` 为空 | 金额与余额是 `setTextViewText` 写进 RemoteViews 的 |
| `persist.sys.feature.island = 1` | `log/props.txt` |

**修正的两个自身缺陷**

1. `describe()` 在实机输出过 `RemoteViews?` —— 该 Android 版本上 `mPackage` /
   `mLayoutId` 字段名不同。改为「先试隐藏方法 `getPackage()` / `getLayoutId()`，
   再试字段」的多路兜底。
2. 数据解析只读通知文本（全空）→ 岛上无数据。新增 `describeTextActions()`：
   反射读取 `RemoteViews#mActions` 中 `setText*` 动作的 `viewId` + `value`，
   并入 `IslandData` 解析。

**新增诊断**：`rvTexts` / `param.custom`（Bundle 键值）/ `pics` / `actions`，
用于判定岛究竟由哪个 key 驱动。

> **待判定**：`miui.focus.rv` 是否就是岛的展示内容。若日志显示
> `rvTexts` 为空而 `param.custom` / `pics` 才是岛的真实数据源，
> 则说明 `miui.focus.rv` 只是通知中心的视图 —— 方案 A（换 RemoteViews）
> 结构上无法改写岛，必须转方案 B（SystemUI 注入）。

## 十九、v1.4.0：方案 B 落地（SystemUI 自绘动画）

**为什么放弃 A**：实机验证表明换掉 `miui.focus.rv` 后岛没有任何变化
（`title`/`text` 为空、数据写在 RemoteViews 动作里、岛由 SystemUI 侧渲染），
方案 A 结构上无法改写岛。

**新结构**

```
小米智能卡进程                           SystemUI 进程
IslandStyler（识别焦点通知）
   │ 解析 金额 / 余额 / 成功失败
   ▼
IslandBridge.send()  ──显式广播──▶  IslandOverlayController（注册接收器）
   action=…ISLAND_SWIPE                 └─ IslandOverlay（Canvas 自绘 → 播放）
   setPackage("com.android.systemui")
```

**新增作用域**：`com.android.systemui`
（`arrays.xml` 的 `xposed_scope` 与 `LiquidGlassModule.PKG_SYSTEM_UI`）。
SystemUI 侧只安装动画控制器，**不做任何界面玻璃化**。

**动画本体 `IslandOverlay`**（自绘，总时长 2900ms）

| 阶段 | 时间 | 做法 |
| --- | --- | --- |
| 卡片入场 | 0–240ms | 缩放 0.86→1 + 淡入 |
| 卡片翻转 | 240–760ms | `Camera#rotateY` 0→180°，真正的 3D 翻转 |
| 状态图标 | 760–1160ms | 实心圆 + `PathMeasure` 逐段描出的对勾 / 叉 |
| 金额与余额 | 1000–1400ms | 上移 + 淡入 |
| 停留 / 淡出 | 1400–2900ms | 保持 1 秒后整体淡出，回调移除窗口 |

**窗口策略 `IslandOverlayController`**

- 安装点：hook `Application#onCreate` 取到 SystemUI 的 Context（不依赖 SystemUI 内部类名）；
- 接收：动态注册 `RECEIVER_EXPORTED`（API 33+）接收 `ISLAND_SWIPE`；
- 窗口类型按优先级尝试：`2014 TYPE_STATUS_BAR_PANEL`（@hide，字面量）→ `2006` → `2015` → `2038`；
- flags：`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
  —— **不拦截触摸**，动画期间用户操作不受影响；
- 位置：顶部居中，`y = 状态栏高度 + 8dp`；总时长结束后自动 `removeViewImmediate`。

**验证入口**：设置界面「预览成功动画 / 预览失败动画」按钮，
直接发广播播放，无需真实刷卡。日志过滤 `MiPayLiquidGlass/IslandUI`。

## 二十、v1.5.0：查证官方文档后重做（多路备份）

### 1. 方案 A 被官方规范否决

小米澎湃OS 开发者平台《小米超级岛 → 开发指南》《接入流程》等文档确证：

- 岛的数据接口是 **`miui.focus.param`（String，JSON）**；
  大岛/小岛为 JSON 内 `param_island.bigIslandArea` / `smallIslandArea`，
  由**模板组件**构建，**不是 RemoteViews**；
- `miui.focus.rvBar` / `miui.focus.rvBarNight` 仅供 **OS2 状态栏**；
- 官方未提供「自定义岛 RemoteViews」能力 →
  **v1.3.x 替换 `miui.focus.rv` 结构上不可能生效**（与实机一致）；
- 第三方接入需**申请白名单**（OAID 调试设备，30 天/10 台）。

（旁证：`miui.focus.pics`（Bundle）、`miui.focus.actions`（Bundle）、
JSON 内 `param_v2`/`business`/`param_island`/`baseInfo`/`hintInfo` 等为文档确证字段。）

### 2. 窗口层级纠正（AOSP `getWindowLayerFromTypeLw`，system uid）

| type | 值 | 层级 |
| --- | --- | --- |
| TYPE_NOTIFICATION_SHADE | 2040 | 17（**锁屏宿主**） |
| TYPE_STATUS_BAR | 2000 | 15 |
| TYPE_SYSTEM_OVERLAY | 2006 | 23 |
| TYPE_SYSTEM_ERROR | **2010** | 27 |
| TYPE_SECURE_SYSTEM_OVERLAY | 2015 | 33 |
| TYPE_APPLICATION_OVERLAY | 2038 | 11（**盖不住锁屏**） |
| TYPE_STATUS_BAR_PANEL | 2014 | 已废弃 |

v1.4.0 首选 2014 且把 `TYPE_SYSTEM_ERROR` 写成 2015 → 窗口加不上。

### 3. 本版实现

**显示三路**：① 挂进状态栏窗口（2000）视图树 → ② 挂进通知栏窗口（2040）视图树
（锁屏就在这棵树里，挂进去等于继承该窗口）→ ③ 自建窗口 `2010 → 2006 → 2015 → 2038`。
窗口根视图通过 hook `ViewRootImpl#setView` 捕获。

**触发两路**：① SystemUI 进程内 hook
`com.android.systemui.statusbar.NotificationListener#onNotificationPosted`（免跨进程）
→ ② 跨进程广播。

**另修**：`IslandOverlay` 缺 `onMeasure()`，`WRAP_CONTENT` 下高度为 0 →
即使窗口成功也看不见。

**新增**：`IslandInfo`（两进程共用的解析）、`PreviewActivity`（App 内预览，保证可见）、
设置界面第三个按钮「测试 SystemUI 动画」。

### 4. 参考实现

**HyperIsland**（`io.github.hyperisland`，1812z，MIT）：面向 HyperOS 3/4 的超级岛模块，
hook `NotificationManagerService` + HyperOS SystemUI，Compose 渲染岛 UI，支持锁屏常驻。

## 二十一、v1.5.1：作用域缺失（实机根因）

**实机 LSPosed 日志给出确凿结论**：

- `db_scope.txt` 中本模块作用域只有 `com.miui.tsmclient` / `com.mipay.wallet` / `com.miui.nextpay`
  —— **`com.android.systemui` 不在其中**；
- 全部日志中 `MiPayLiquidGlass/IslandUI` 与 `loaded in SystemUI` **命中数为 0**。

即 SystemUI 侧代码从未执行。原因是 `xposed_scope` meta-data 仅影响**首次安装**时的推荐作用域，
已启用模块新增的作用域不会自动应用。

**同时确认的岛结构（实机日志）**

```
[notify] id=10 channel=TMS_CHANNEL island=true
  rv(main)=com.miui.tsmclient/0x7f0d00b2   ← 即 layout/island_custom_notification_view
  rvTexts=[]                                ← RV 内无 setText 动作
  param.custom={"isShowNotification":false,"islandFirstFloat":true,
                "business":"tsmclient","param_island":{"islandProperty":0,"islandTim…}
  pics=Bundle[{miui.focus.pic_start=Icon(typ=BITMAP size=856x539)}]
  actions=Bundle[{miui.focus.action_1=Notification$Action@…}]
  title= text=
```

推论：

1. `miui.focus.rv` 确为 App 自带岛布局，但文本不在 RV 动作里；
2. 岛内容/图片由 `miui.focus.param.custom`（JSON）+ `miui.focus.pics` 驱动，
   与官方文档「`miui.focus.param` JSON + 模板组件」一致；
3. 岛受模板限制，无法改成自由 iOS 动画 → 只能走 SystemUI 覆盖层。

**修复**：手动在 LSPosed 勾选「系统界面」并重启 SystemUI。本版在设置界面加了常驻提示与
Toast 指引，使该失败可自解释。

## 二十二、v1.5.2：挂载成功但被裁剪 + 数据为空

勾选作用域后的实机日志（`com.android.systemui`，uid 1000）：

```
IslandUI: SystemUI 侧安装完成
IslandUI: 已安装窗口捕获（ViewRootImpl#setView）
IslandUI: 找不到 NotificationListener，改用广播触发
IslandUI: 已捕获状态栏窗口根视图 type=2000 title=StatusBar cls=StatusBarWindowView
IslandUI: 已捕获通知栏窗口根视图 type=2040 title=NotificationShade cls=NotificationShadeWindowView
IslandUI: [broadcast] 预览/触发 success=true amount=[] balance=[]
IslandUI: 已挂入 SystemUI 窗口视图树
```

**问题一：挂进状态栏窗口 → 被裁剪**

`StatusBarWindowView`（type 2000）的窗口高度只有状态栏那么高（约 30dp），
206dp 的动画被窗口边界裁掉。修正为**优先挂进全屏的通知栏窗口**
（`NotificationShadeWindowView`，type 2040），并加「容器高度 < 150dp 直接跳过」的保护，
挂载成功时打印容器类名与尺寸。

**问题二：金额/余额为空**

`EXTRA_TITLE`/`EXTRA_TEXT` 为空、`rvTexts=[]`，数据只在
`miui.focus.param.custom` 的 JSON 中。`IslandInfo` 已将该 JSON 原文纳入解析范围；
日志 JSON 转储上限由 300 → 2600 字符。

**问题三：HyperOS 无 `NotificationListener`**

`com.android.systemui.statusbar.NotificationListener` 在 HyperOS 3 上不存在，
进程内触发不可用，退回跨进程广播（实测刷卡时确实触发成功）。

## 二十三、v1.5.3：改走独立窗口 + 可关闭系统岛

v1.5.2 日志证明：注入成功、数据解析正确（`amount=[¥2.00]`）、
且**确实挂进了全屏容器** `NotificationShadeWindowView`（1200x2670）——
但画面不可见。判定为**宿主窗口本身不可见**（被锁屏压住或被整体平移出屏）。

**改动一：自建系统窗口提为首选**

顺序由「先挂视图树」改为：

1. 自建窗口 `2010 → 2006 → 2015 → 2038`（独立图层，层级 27/23/33 > 锁屏 17）；
2. 失败再回退挂进 SystemUI 窗口视图树。

日志新增 `自建窗口成功 type=… size=…` / `自建窗口 type=… 失败 <异常>`，
可区分"权限被拒"与"根本没走到"。

**改动二：`Config#hideSystemIsland`（默认开）**

开启后在 `IslandStyler` 中摘除 `miui.focus.param.custom` / `miui.focus.pics` /
`miui.focus.actions` / `miui.focus.rv`，使系统不再渲染自带岛，只保留我们的动画。

## 二十四、v1.5.4：动画可见后的四项收尾

v1.5.3 实机确认**自建系统窗口可以显示动画**（"岛出现了"）。随后修四件事：

**1) 播完不消失**

`onFinish` 在 `onDraw` 的绘制流程中直接调 `WindowManager#removeViewImmediate`，
系统拒绝窗口操作 → 视图长期驻留。改为 `View#post(...)` 投递到下一帧执行，
并新增 `DURATION + 600ms` 超时兜底；移除失败时 `removeViewImmediate → removeView`、
宿主侧 `removeView → removeViewInLayout` 逐级降级。

**2) 金额/余额为空**

`EXTRA_TITLE`/`EXTRA_TEXT`/RV 动作全空，文案只在 `miui.focus.param.custom`。
新增 `IslandInfo#jsonTexts`：正则取全部 `"key":"value"`，
按 `title → content → frontTitle → subtitle → …` 优先级取值，不足则按出现顺序兜底；
金额取第 1 条、余额取第 2 条。另加 `paramJSON=` 完整 JSON 日志行。

**3) 尺寸缩小**

胶囊 320×190 → 260×168dp，卡片 132×88 → 112×68，状态图标 44 → 34，
金额 24 → 21sp，余额 13 → 11.5sp；并按「卡片 → 状态 → 金额 → 余额」重排，消除重叠。

**4) 系统岛打架**

`hideSystemIsland` 默认改为开启，摘除 `miui.focus.*` 后系统不再渲染自带岛。

## 十七、v1.3.1：修正槽位与钩子覆盖面

v1.3.0 实机结果：**岛无法展开**、**仍为系统自带岛**。两条各自对应一个真实错误。

**1. 槽位尺寸不匹配（→ 无法展开）**

App 自带 `island_custom_notification_view` 是单行紧凑布局，说明 `miui.focus.rv`
承载的是**收起态**。v1.3.0 把约 200dp 高的竖版布局塞进去 → 岛装不下 → 回退系统岛。

修正后的槽位分配：

| 槽位 | 布局 |
| --- | --- |
| `miui.focus.rv` / `contentView` | `island_swipe_row`（紧凑行：状态图标 + 金额 + 余额） |
| `bigContentView` / `headsupContentView` | `island_swipe_card`（竖版：卡片翻转 + 勾/叉 + 金额 + 余额） |

**2. 钩子覆盖面不足（→ 无任何变化）**

v1.2.0/v1.3.0 只钩 `NotificationManager#notify`，而 `sendIslandNotification` 位于
**Service**（`NfcEventMonitorService`）—— 前台服务通知不经过 `notify`。修正后钩三条：

- `NotificationManager#notify`（全部重载）
- `NotificationManager#notifyAsPackage`
- `Service#startForeground`（全部重载）

**3. 诊断增强**

`logNotification` 打印：来源方法 / tag / id / channel / 四个槽位的 RemoteViews
（`mPackage` + `mLayoutId`）/ `extras` 键名 / 标题与正文。
`describe()` 反射读取 `RemoteViews#mPackage` / `mLayoutId`，
可据此判断槽位里是不是 `com.miui.tsmclient/0x7f0d00b2`（即 App 自带的岛布局）。