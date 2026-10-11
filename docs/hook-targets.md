# 小米智能卡 · 反编译分析与 Hook 目标清单

> **状态：本报告的结论已在 v1.0.1 ~ v1.0.7 全部落地**，明细见文末「七~十二」六节。

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
