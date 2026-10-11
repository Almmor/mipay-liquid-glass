package com.liquidglass.mipay.glass;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import com.liquidglass.mipay.Config;

/**
 * 刷卡动画的 SystemUI 侧控制器（1.5.6）。
 *
 * <h3>v1.5.4 到底卡在哪</h3>
 *
 * <p>实机日志（{@code LSPosed_20261012_001454}）显示：</p>
 *
 * <pre>
 * 自建窗口成功 type=2010 size=0x0
 * 已用自建系统窗口显示
 * （3.496 秒后，正好等于 DURATION+600 的超时兜底）
 * 已移除动画窗口
 * </pre>
 *
 * <p>窗口「加进 WindowManager 成功」，但移除发生在<b>超时兜底</b>而不是
 * 动画自己的结束回调上 —— 反推可知 <b>{@code onDraw} 一帧都没跑</b>：
 * 窗口对象存在、却从未真正渲染。</p>
 *
 * <h3>1.5.5 的两条硬措施</h3>
 *
 * <ol>
 *     <li><b>真·可见性判定</b>：{@link IslandOverlay#getDrawCount()} 记录实际绘制帧数。
 *     加完窗口后 260ms 去看一眼，帧数还是 0 就说明这个 <b>窗口类型在锁屏上被压制</b>，
 *     立刻摘掉换下一个候选类型 —— 不再"加成功就当成功"。</li>
 *     <li><b>候选类型按层级从高到低轮询</b>：
 *     {@code 2015 TYPE_SECURE_SYSTEM_OVERLAY}（层 33）→ {@code 2010}（27）→
 *     {@code 2006}（23）→ {@code 2038}（11）。v1.5.4 直接从 2010 开始，
 *     2015 这条更高层级的路径<b>从来没有被试过</b>。</li>
 * </ol>
 *
 * <h3>1.5.6 的新增</h3>
 *
 * <ul>
 *     <li><b>真实卡面跨进程投递</b>：接收广播时读取 {@code IslandBridge#EXTRA_FACE}
 *     位图并交给 {@link IslandOverlay}，动画正面绘制真实卡面而非示例卡；</li>
 *     <li><b>触发去重</b>：同一次刷卡会经「显式广播」与「SystemUI 钩到的焦点通知」
 *     两条路各来一次，{@link #DEDUP_WINDOW_MS} 内只播一次，避免动画叠播。</li>
 * </ul>
 *
 * <p>所有自建窗口都不渲染时，再退回复用 SystemUI 已有的窗口视图树
 * （通知栏窗口 {@code TYPE_NOTIFICATION_SHADE=2040} → 状态栏窗口 {@code TYPE_STATUS_BAR=2000}）。</p>
 */
public final class IslandOverlayController {

    private static final String TAG = "MiPayLiquidGlass/IslandUI";

    /** 通知栏窗口类型（TYPE_NOTIFICATION_SHADE）。 */
    private static final int TYPE_NOTIFICATION_SHADE = 2040;
    /** 状态栏窗口类型（TYPE_STATUS_BAR）。 */
    private static final int TYPE_STATUS_BAR = 2000;

    /**
     * 自建窗口的候选类型，按「system uid 下的层级」<b>由高到低</b>。
     *
     * <p>层级来自 AOSP {@code WindowManagerPolicy#getWindowLayerFromTypeLw}
     * （internal=true 时）：2015→33、2010→27、2006→23、2038→11。
     * 锁屏宿主 2040 为 17，因此前三者都应在锁屏之上。</p>
     */
    private static final int[] WINDOW_TYPES = {
            2015, // TYPE_SECURE_SYSTEM_OVERLAY（层 33，最高）
            2010, // TYPE_SYSTEM_ERROR（层 27）
            2006, // TYPE_SYSTEM_OVERLAY（层 23）
            2038  // TYPE_APPLICATION_OVERLAY（层 11，仅解锁可见，最后兜底）
    };

    /** 加完窗口后等多久去检查「有没有真的画出帧」。 */
    private static final long VISIBILITY_PROBE_MS = 260L;
    /** 少于这么多帧就判定该窗口类型没有被渲染。 */
    private static final int MIN_DRAW_FRAMES = 3;
    /** 去重窗口：这段时间内重复的触发请求直接忽略（广播 + 通知双触发）。 */
    private static final long DEDUP_WINDOW_MS = 1500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean installed;
    private static Context appContext;
    /** 捕获到的「通知栏窗口」与「状态栏窗口」根视图，自建窗口全部失败时挂进这里。 */
    private static volatile ViewGroup shadeRoot;
    private static volatile ViewGroup statusBarRoot;
    /** 当前正在播放的动画视图与其宿主（用于移除）。 */
    private static View currentView;
    private static ViewGroup currentHost;
    /** 待挂载的动画视图（{@link #attachTo} 需要它）。 */
    private static IslandOverlay pendingView;
    /** 上次真正播放动画的时间，用于两条触发路径的去重。 */
    private static long lastShowAt;

    private IslandOverlayController() {
    }

    // ======================================================================
    // 安装
    // ======================================================================

    public static void install(ClassLoader classLoader, final Config cfg) {
        if (installed || classLoader == null) {
            return;
        }
        installed = true;

        hookWindowSpy(classLoader);
        hookNotificationListener(classLoader);
        hookApplication(classLoader, cfg);

        XposedBridge.log(TAG + ": SystemUI 侧安装完成");
    }

    /** 拿到 SystemUI 的 Context，并注册广播接收器（备用触发路径）。 */
    private static void hookApplication(ClassLoader classLoader, final Config cfg) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", classLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object self = param.thisObject;
                                if (self instanceof Context) {
                                    register((Context) self, cfg);
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": hook Application#onCreate 失败 " + throwable);
        }
    }

    /**
     * 捕获 SystemUI 的窗口根视图。
     *
     * <p>{@code ViewRootImpl#setView(View, LayoutParams, View)} 是每个窗口建立
     * 视图树的必经点，能同时拿到根 View 与 LayoutParams。</p>
     */
    private static void hookWindowSpy(ClassLoader classLoader) {
        try {
            Class<?> viewRootImpl = XposedHelpers.findClassIfExists(
                    "android.view.ViewRootImpl", classLoader);
            if (viewRootImpl == null) {
                XposedBridge.log(TAG + ": 找不到 ViewRootImpl");
                return;
            }
            XposedBridge.hookAllMethods(viewRootImpl, "setView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View view = null;
                        WindowManager.LayoutParams attrs = null;
                        for (Object arg : param.args) {
                            if (arg instanceof View && view == null) {
                                view = (View) arg;
                            } else if (arg instanceof WindowManager.LayoutParams && attrs == null) {
                                attrs = (WindowManager.LayoutParams) arg;
                            }
                        }
                        if (!(view instanceof ViewGroup) || attrs == null) {
                            return;
                        }
                        String title = String.valueOf(attrs.getTitle());
                        if (attrs.type == TYPE_NOTIFICATION_SHADE
                                || title.contains("NotificationShade")) {
                            if (shadeRoot == null) {
                                shadeRoot = (ViewGroup) view;
                                XposedBridge.log(TAG + ": 已捕获通知栏窗口根视图 type="
                                        + attrs.type + " title=" + title
                                        + " cls=" + view.getClass().getName());
                            }
                        } else if (attrs.type == TYPE_STATUS_BAR
                                || title.contains("StatusBar")) {
                            if (statusBarRoot == null) {
                                statusBarRoot = (ViewGroup) view;
                                XposedBridge.log(TAG + ": 已捕获状态栏窗口根视图 type="
                                        + attrs.type + " title=" + title
                                        + " cls=" + view.getClass().getName());
                            }
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            });
            XposedBridge.log(TAG + ": 已安装窗口捕获（ViewRootImpl#setView）");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 安装窗口捕获失败 " + throwable);
        }
    }

    /** 主触发路径：SystemUI 进程内直接感知通知，避免跨进程失败。 */
    private static void hookNotificationListener(ClassLoader classLoader) {
        try {
            Class<?> listener = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.NotificationListener", classLoader);
            if (listener == null) {
                XposedBridge.log(TAG + ": 找不到 NotificationListener，改用广播触发");
                return;
            }
            XposedBridge.hookAllMethods(listener, "onNotificationPosted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Notification notification = null;
                        for (Object arg : param.args) {
                            if (arg instanceof android.service.notification.StatusBarNotification) {
                                notification = ((android.service.notification.StatusBarNotification) arg)
                                        .getNotification();
                                break;
                            }
                        }
                        if (notification != null) {
                            handleNotification(notification, "onNotificationPosted");
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            });
            XposedBridge.log(TAG + ": 已安装通知监听（NotificationListener#onNotificationPosted）");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 安装通知监听失败 " + throwable);
        }
    }

    private static void register(Context context, final Config cfg) {
        if (appContext != null) {
            return;
        }
        try {
            appContext = context.getApplicationContext();
            // 关键诊断：把「SystemUI 里实际加载的模块版本」打进日志。
            // 动画代码跑在 SystemUI 进程里，而 SystemUI 常年不重启 ——
            // 装了新版 APK 却没重启 SystemUI 时，跑的还是旧代码，
            // 只看这一行就能立刻分辨「改了没生效」和「改了但不对」。
            XposedBridge.log(TAG + ": 已注册广播接收器"
                    + "（SystemUI 侧加载的模块版本 = " + moduleVersion(appContext) + "）");
            IntentFilter filter = new IntentFilter(IslandBridge.ACTION);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String amount = stringExtra(intent, IslandBridge.EXTRA_AMOUNT);
                        String balance = stringExtra(intent, IslandBridge.EXTRA_BALANCE);
                        boolean success = intent == null
                                || intent.getBooleanExtra(IslandBridge.EXTRA_SUCCESS, true);
                        Bitmap face = bitmapExtra(intent, IslandBridge.EXTRA_FACE);
                        XposedBridge.log(TAG + ": [broadcast] 触发 success=" + success
                                + " amount=[" + amount + "] balance=[" + balance
                                + "] face=" + (face == null ? "无" :
                                face.getWidth() + "x" + face.getHeight()));
                        if (cfg == null || cfg.islandOverlay || cfg.islandAnimation) {
                            show(amount, balance, success, face);
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(receiver, filter);
            }
            XposedBridge.log(TAG + ": 已注册广播接收器");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 注册广播接收器失败 " + throwable);
        }
    }

    // ======================================================================
    // 触发
    // ======================================================================

    /** 判断是否焦点通知：extras 里出现任意 {@code miui.focus.*} 键。 */
    private static void handleNotification(final Notification notification, String source) {
        try {
            Bundle extras = notification.extras;
            if (extras == null) {
                return;
            }
            boolean focus = false;
            for (String key : extras.keySet()) {
                if (key != null && key.startsWith("miui.focus.")) {
                    focus = true;
                    break;
                }
            }
            if (!focus) {
                return;
            }
            final IslandInfo info = IslandInfo.from(notification);
            final Bitmap face = readFaceFromExtras(extras);
            XposedBridge.log(TAG + ": [" + source + "] 焦点通知 success=" + info.success
                    + " amount=[" + info.amountText + "] balance=[" + info.balanceText + "]");
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    show(info.amountText, info.balanceText, info.success, face);
                }
            });
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 显示
    // ======================================================================

    private static void show(String amount, String balance, boolean success, Bitmap face) {
        Context context = appContext;
        if (context == null) {
            XposedBridge.log(TAG + ": 还没有拿到 Context，动画无法显示");
            return;
        }
        // 去重：同一次刷卡会被两条路径各触发一次 —— 小米智能卡进程的显式广播，
        // 以及 SystemUI 侧钩到的焦点通知。若不去重，用户会看到两段动画叠着播。
        long now = SystemClock.uptimeMillis();
        if (now - lastShowAt < DEDUP_WINDOW_MS) {
            XposedBridge.log(TAG + ": " + (now - lastShowAt) + "ms 内已有动画，忽略本次重复触发");
            return;
        }
        lastShowAt = now;
        try {
            removeCurrent();

            final IslandOverlay view = new IslandOverlay(context, amount, balance, success, face);
            // 日志自证：岛是安全层窗口，截屏/录屏都拍不到，只能让动画自己报告画了什么
            XposedBridge.log(TAG + ": 动画内容 " + view.describeContent());
            view.setOnFinish(new Runnable() {
                @Override
                public void run() {
                    try {
                        removeCurrent();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            });
            currentView = view;

            // 超时兜底：动画自身或绘制都异常时，也不至于赖在屏幕上。
            // 时长要把「逐类型轮询」的时间也算进去，否则最后一个类型
            // 刚挂上就被兜底掐断，动画会看不完整。
            long budget = IslandOverlay.DURATION_MS
                    + WINDOW_TYPES.length * VISIBILITY_PROBE_MS + 900L;
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (currentView == view) {
                        XposedBridge.log(TAG + ": 到达超时兜底，强制移除动画");
                        view.finishNow();
                    }
                }
            }, budget);

            tryWindowType(context, view, 0);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 显示动画异常 " + throwable);
        }
    }

    /**
     * 依次尝试 {@link #WINDOW_TYPES}：加完窗口 260ms 后检查实际绘制帧数，
     * 有帧才算成功；一帧都没画就摘掉换下一个类型。
     */
    private static void tryWindowType(final Context context, final IslandOverlay view,
                                      final int index) {
        if (currentView != view) {
            return; // 这一轮已经被取消 / 替换
        }
        if (index >= WINDOW_TYPES.length) {
            XposedBridge.log(TAG + ": 自建窗口所有类型都没有渲染帧，改挂 SystemUI 窗口视图树");
            pendingView = view;
            if (attachTo(shadeRoot) || attachTo(statusBarRoot)) {
                probeTreeAttach(view);
                return;
            }
            XposedBridge.log(TAG + ": 动画显示失败 —— 自建窗口与视图树都没能让它渲染"
                    + "（屏幕未点亮时也可能出现，请点亮屏幕后重试）");
            return;
        }

        final int type = WINDOW_TYPES[index];
        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            XposedBridge.log(TAG + ": 拿不到 WindowManager");
            return;
        }
        WindowManager.LayoutParams params = buildParams(context);
        params.type = type;
        try {
            windowManager.addView(view, params);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 自建窗口 type=" + type + " 添加失败 " + throwable);
            tryWindowType(context, view, index + 1);
            return;
        }

        XposedBridge.log(TAG + ": 自建窗口已添加 type=" + type + "，等待绘制帧探测");
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentView != view) {
                    return;
                }
                int frames = view.getDrawCount();
                if (frames >= MIN_DRAW_FRAMES) {
                    XposedBridge.log(TAG + ": 自建窗口 type=" + type + " 确认可见"
                            + "（" + frames + " 帧，尺寸 " + view.getWidth()
                            + "x" + view.getHeight() + "）");
                    return;
                }
                XposedBridge.log(TAG + ": 自建窗口 type=" + type + " 已添加但只有 "
                        + frames + " 帧渲染，判定被压制，换下一个类型");
                detachWindow(context, view);
                tryWindowType(context, view, index + 1);
            }
        }, VISIBILITY_PROBE_MS);
    }

    /** 视图树兜底路径也做一次帧探测，便于定位问题。 */
    private static void probeTreeAttach(final IslandOverlay view) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentView != view) {
                    return;
                }
                XposedBridge.log(TAG + ": 视图树挂载结果 —— 渲染帧数=" + view.getDrawCount()
                        + " 尺寸=" + view.getWidth() + "x" + view.getHeight()
                        + " isShown=" + view.isShown());
            }
        }, VISIBILITY_PROBE_MS);
    }

    /** 把动画挂进指定窗口根视图；成功后记录宿主以便移除。 */
    private static boolean attachTo(ViewGroup container) {
        if (container == null) {
            return false;
        }
        try {
            // 容器太矮说明是状态栏一类的小窗口，挂进去会被裁剪，直接放弃
            int height = container.getHeight();
            if (height > 0 && height < Math.round(150f
                    * container.getResources().getDisplayMetrics().density)) {
                XposedBridge.log(TAG + ": 跳过过矮容器 cls="
                        + container.getClass().getName() + " h=" + height);
                return false;
            }
            IslandOverlay view = pendingView;
            if (view == null) {
                return false;
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = statusBarHeight(container.getContext());
            container.addView(view, params);
            view.bringToFront();
            currentHost = container;
            pendingView = null;
            XposedBridge.log(TAG + ": 已挂入 " + container.getClass().getName()
                    + " 容器尺寸=" + container.getWidth() + "x" + container.getHeight());
            return true;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 挂入窗口失败 " + throwable);
            return false;
        }
    }

    private static WindowManager.LayoutParams buildParams(Context context) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        float density = context.getResources().getDisplayMetrics().density;
        params.y = statusBarHeight(context) + Math.round(6f * density);
        params.format = PixelFormat.TRANSLUCENT;
        params.alpha = 1f;
        // 不抢焦点、不拦截触摸：动画期间用户照常操作。
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // 允许绘制到刘海区域，避免顶部位置被裁
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            } catch (Throwable ignored) {
                // ignore
            }
        }
        params.setTitle("MiPayIslandOverlay");
        return params;
    }

    /** 只把视图从窗口里摘掉，不影响 {@link #currentView}（用于轮询换类型）。 */
    private static void detachWindow(Context context, View view) {
        try {
            WindowManager windowManager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return;
            }
            windowManager.removeViewImmediate(view);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void removeCurrent() {
        View view = currentView;
        ViewGroup host = currentHost;
        currentView = null;
        currentHost = null;
        pendingView = null;
        if (view == null) {
            return;
        }
        // 宿主视图树分支
        if (host != null) {
            try {
                host.removeView(view);
                XposedBridge.log(TAG + ": 已从宿主视图树移除");
                return;
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                host.removeViewInLayout(view);
                XposedBridge.log(TAG + ": 已从宿主视图树移除(inLayout)");
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": 从宿主移除失败 " + throwable);
            }
            return;
        }
        // 自建窗口分支
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            WindowManager windowManager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return;
            }
            try {
                windowManager.removeViewImmediate(view);
                XposedBridge.log(TAG + ": 已移除动画窗口");
            } catch (Throwable throwable) {
                windowManager.removeView(view);
                XposedBridge.log(TAG + ": 已移除动画窗口(removeView)");
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 移除动画窗口失败 " + throwable);
        }
    }

    private static int statusBarHeight(Context context) {
        try {
            int id = context.getResources()
                    .getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                return context.getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return Math.round(28f * context.getResources().getDisplayMetrics().density);
    }

    /** 读取本模块的已安装版本号，仅用于日志诊断。 */
    private static String moduleVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(com.liquidglass.mipay.Config.MODULE_PACKAGE, 0)
                    .versionName;
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    private static String stringExtra(Intent intent, String key) {
        try {
            String value = intent == null ? null : intent.getStringExtra(key);
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 从广播里取出卡面位图。
     *
     * <p>{@code Bitmap} 是 {@code Parcelable}，跨进程会走 ashmem 传递，
     * 因此不必担心 Binder 事务大小（位图已在发送侧缩到 384px 内）。</p>
     */
    private static Bitmap bitmapExtra(Intent intent, String key) {
        try {
            Object value = intent == null ? null : intent.getParcelableExtra(key);
            return value instanceof Bitmap ? (Bitmap) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从焦点通知的 extras 里取卡面（多数情况下为 null，卡面走广播通道）。 */
    private static Bitmap readFaceFromExtras(Bundle extras) {
        try {
            Object value = extras == null ? null : extras.get(IslandBridge.EXTRA_FACE);
            return value instanceof Bitmap ? (Bitmap) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
