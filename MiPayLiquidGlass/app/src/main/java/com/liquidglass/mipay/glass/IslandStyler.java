package com.liquidglass.mipay.glass;

import android.app.AndroidAppHelper;
import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import com.liquidglass.mipay.Config;

/**
 * 超级岛（焦点通知）改造（1.2.0 起，1.5.5 收敛，1.5.6 修正摘除范围）。
 *
 * <h3>一、小米智能卡是怎么「上岛」的</h3>
 *
 * <p>上岛代码在 {@code classes2.dex}（不在 {@code classes.dex}），使用的是一套
 * <b>HyperOS 焦点通知契约</b>：{@code miui.focus.rv}（自定义 RemoteViews）、
 * {@code miui.focus.param.custom}、{@code param_island}、
 * {@code islandTimeout/islandPriority/...}、通知 tag {@code island_notification}。</p>
 *
 * <h3>二、为什么必须钩通知，而不是钩视图</h3>
 *
 * <p>岛是 {@code RemoteViews}，由 <b>SystemUI 在自身进程 inflate</b>，
 * 模块的视图树遍历碰不到它。但 {@code RemoteViews} 是 <b>App 进程</b>构建后
 * 塞进 {@code Notification.extras} 的，因此在 {@code NotificationManager#notify}
 * 之前改写它即可。</p>
 *
 * <h3>三、1.5.5 删掉了「把岛内容换成我们自己的布局」</h3>
 *
 * <p>1.3.0~1.5.4 会把 {@code miui.focus.rv} / {@code contentView} 整体替换成
 * 本模块的 {@code layout/island_swipe_row}。这是<b>一个真实存在的严重 bug</b>：
 * {@code RemoteViews} 的包名取自 {@code AndroidAppHelper.currentApplication()}
 * （也就是 {@code com.miui.tsmclient}），而布局 id 用的是<b>本模块</b>的
 * {@code R.layout.*}。SystemUI 按「小米智能卡的资源」去解析「本模块的资源 id」，
 * 于是 inflate 出来的根本不是我们的布局，后续的 {@code setTextViewText} /
 * {@code setViewVisibility} 全部打在不存在的控件上 —— 表现就是实机反馈的
 * <b>「岛出现了，但是没有内容、也无法关闭」</b>。</p>
 *
 * <p>现在岛的内容<b>完全交回给系统</b>（或按用户设置整条摘掉），我们的 iOS 动画
 * 一律由 SystemUI 侧的自绘窗口播放（{@link IslandOverlayController}），
 * 不再去动通知的 RemoteViews。</p>
 *
 * <h3>四、1.5.6 修好了「原有系统超级岛还显示」</h3>
 *
 * <p>1.5.5 的 {@code stripIslandExtras} 只删了 5 个写死的 key，而实机里真正承载岛内容的是
 * {@code miui.focus.param.custom}，且岛参数还可能挂在 {@code param_island} 等键下
 * —— 删不干净，系统自带的超级岛于是照旧跟我们的动画一起显示。现在改为
 * <b>按前缀整片清除</b>（{@code miui.focus.*} 与 {@code param_island}），并把
 * {@code hideSystemIsland} 默认打开。</p>
 *
 * <p>另外，刷卡发生时会经 {@link IslandBridge} 把 {@code IslandFaceCache} 缓存的
 * 真实卡面一并投给 SystemUI，动画正面因此能绘制真实卡面。</p>
 */
public final class IslandStyler {

    private static final String TAG = "MiPayLiquidGlass/Island";

    private static final String KEY_CUSTOM_RV_LOCAL = "miui.focus.rv";
    /** 焦点通知里承载自定义 RemoteViews 的 extras key（供同包的 IslandInfo 使用）。 */
    static final String KEY_CUSTOM_RV = KEY_CUSTOM_RV_LOCAL;
    private static final String KEY_ISLAND_PARAM = "param_island";
    private static final String KEY_PARAM_CUSTOM = "miui.focus.param.custom";
    private static final String TAG_ISLAND = "island_notification";

    /** 岛布局内几个视图 id 名（App 自带布局用，供「只改样式」模式使用）。 */
    private static final String ID_TITLE = "tv_title";
    private static final String ID_SUBTITLE = "tv_subtitle";
    private static final String ID_LEFT_ICON = "iv_left_icon";
    private static final String ID_RIGHT_ICON = "iv_right_icon";
    private static final String ID_BUTTON = "btn_right";

    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0x99FFFFFF;

    private static boolean installed;
    private static int logged;

    private IslandStyler() {
    }

    /** 在目标进程安装通知钩子。重复调用无副作用。 */
    public static void install(ClassLoader classLoader, final Config cfg) {
        if (installed || classLoader == null) {
            return;
        }
        installed = true;

        // 1) NotificationManager#notify / notifyAsPackage
        try {
            Class<?> nm = XposedHelpers.findClassIfExists(
                    "android.app.NotificationManager", classLoader);
            if (nm != null) {
                XC_MethodHook hook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            handleNotify("notify", param.args, cfg);
                        } catch (Throwable ignored) {
                            // 任何异常都不应影响通知投递
                        }
                    }
                };
                XposedBridge.hookAllMethods(nm, "notify", hook);
                XposedBridge.hookAllMethods(nm, "notifyAsPackage", hook);
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // 2) Service#startForeground —— 上岛很可能走的是「前台服务通知」，
        //    这条路径不经过 NotificationManager#notify，只钩 notify 会完全漏掉。
        //    （反编译证据：投递方法是 NfcEventMonitorService:sendIslandNotification）
        try {
            Class<?> service = XposedHelpers.findClassIfExists(
                    "android.app.Service", classLoader);
            if (service != null) {
                XposedBridge.hookAllMethods(service, "startForeground", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            handleNotify("startForeground", param.args, cfg);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 通知拦截
    // ======================================================================

    private static void handleNotify(String source, Object[] args, Config cfg) {
        Notification notification = null;
        String tag = null;
        int id = 0;
        for (Object arg : args) {
            if (arg instanceof Notification) {
                notification = (Notification) arg;
            } else if (arg instanceof String && tag == null) {
                tag = (String) arg;
            } else if (arg instanceof Integer) {
                id = (Integer) arg;
            }
        }
        if (notification == null) {
            return;
        }
        Bundle extras = notification.extras;
        if (extras == null) {
            return;
        }

        RemoteViews mainRv = asRemoteViews(extras.get(KEY_CUSTOM_RV));
        RemoteViews contentView = fieldOf(notification, "contentView");
        RemoteViews bigContentView = fieldOf(notification, "bigContentView");
        RemoteViews headsupView = fieldOf(notification, "headsupContentView");

        boolean looksLikeIsland = mainRv != null
                || extras.containsKey(KEY_CUSTOM_RV)
                || extras.containsKey(KEY_ISLAND_PARAM)
                || TAG_ISLAND.equals(tag)
                || channelHas(notification, "island")
                || channelHas(notification, "focus");

        // 无论是否命中，都先把这条通知的形态记下来，便于定位问题。
        logNotification(source, tag, id, notification,
                mainRv, contentView, bigContentView, headsupView, looksLikeIsland);

        if (!looksLikeIsland) {
            return;
        }

        IslandInfo data = IslandInfo.from(notification);
        Context context = AndroidAppHelper.currentApplication();
        if (context == null) {
            return;
        }

        // 只做无害的样式微调（标题转白、隐藏右侧图标），不替换 RemoteViews
        if (cfg.islandStyle && mainRv != null) {
            styleOriginal(mainRv);
        }

        // 方案 B：把数据交给 SystemUI 进程，由它用系统窗口播放高保真动画
        //（SystemUI 的窗口能盖在锁屏之上，普通悬浮窗不行）。
        if (cfg.islandOverlay || cfg.islandAnimation) {
            XposedBridge.log(TAG + ": 交给 SystemUI 播放动画 success=" + data.success
                    + " amount=[" + data.amountText + "] balance=[" + data.balanceText + "]");
            IslandBridge.send(context, data.amountText, data.balanceText, data.success,
                    cfg.facePickName);
        }

        // 可选：摘掉焦点通知的岛参数，让 SystemUI 不再播它自己的岛动画，
        // 画面上只保留我们的 iOS 动画。
        if (cfg.hideSystemIsland) {
            stripIslandExtras(extras);
        }
    }

    /**
     * 移除焦点通知的岛相关 extras，使系统不再渲染自带岛。
     *
     * <p>1.5.6 的关键修复：旧实现只删了 5 个写死的 key，而实机日志里真正承载岛内容的是
     * {@code miui.focus.param.custom}，且岛参数还可能以 {@code param_island} 等键出现
     * —— 删不干净，系统自带的超级岛就会照旧显示。</p>
     *
     * <p>现在改为「按前缀整片清除」：凡是 {@code miui.focus.} 开头的键，
     * 以及 {@code param_island} 本身，一律移除。这样无论厂商换了多少个子键，
     * 只要还挂在焦点通知契约下就都会被摘掉。</p>
     */
    private static void stripIslandExtras(Bundle extras) {
        if (extras == null) {
            return;
        }
        try {
            java.util.ArrayList<String> doomed = new java.util.ArrayList<>();
            for (String key : extras.keySet()) {
                if (key == null) {
                    continue;
                }
                if (key.startsWith("miui.focus.") || KEY_ISLAND_PARAM.equals(key)) {
                    doomed.add(key);
                }
            }
            for (String key : doomed) {
                extras.remove(key);
            }
            XposedBridge.log(TAG + ": 已摘除系统岛参数（共 " + doomed.size()
                    + " 个键：[" + doomed + "]）");
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 退路：只改样式
    // ======================================================================

    private static void styleOriginal(RemoteViews rv) {
        int idTitle = idOf(ID_TITLE);
        int idSubtitle = idOf(ID_SUBTITLE);
        if (idTitle > 0) {
            rv.setTextColor(idTitle, TEXT_PRIMARY);
        }
        if (idSubtitle > 0) {
            rv.setTextColor(idSubtitle, TEXT_SECONDARY);
        }
        int idRightIcon = idOf(ID_RIGHT_ICON);
        int idButton = idOf(ID_BUTTON);
        if (idRightIcon > 0) {
            rv.setViewVisibility(idRightIcon, View.GONE);
        }
        if (idButton > 0) {
            rv.setViewVisibility(idButton, View.GONE);
        }
        int idLeftIcon = idOf(ID_LEFT_ICON);
        if (idLeftIcon > 0) {
            rv.setViewVisibility(idLeftIcon, View.VISIBLE);
        }
    }

    // ======================================================================
    // 诊断日志
    // ======================================================================

    /**
     * 记录每条通知的形态。
     *
     * <p>打印「来源方法 / tag / id / channel / 各槽位的 RemoteViews /
     * extras 键名 / 标题与正文」。</p>
     */
    private static void logNotification(String source, String tag, int id, Notification notification,
                                        RemoteViews mainRv, RemoteViews contentView,
                                        RemoteViews bigContentView, RemoteViews headsupView,
                                        boolean looksLikeIsland) {
        if (logged++ > 25) {
            return;
        }
        XposedBridge.log(TAG + ": [" + source + "] tag=" + tag + " id=" + id
                + " channel=" + channelOf(notification)
                + " island=" + looksLikeIsland
                + " rv(main)=" + describe(mainRv)
                + " contentView=" + describe(contentView)
                + " big=" + describe(bigContentView)
                + " headsup=" + describe(headsupView)
                + " keys=[" + keysOf(notification.extras) + "]"
                + " rvTexts=[" + IslandInfo.rvTextActions(mainRv) + "]"
                + " title=" + text(notification.extras, Notification.EXTRA_TITLE)
                + " text=" + text(notification.extras, Notification.EXTRA_TEXT));

        // 单独一行输出岛参数 JSON，避免被 Bundle 转储的截断吞掉关键字段
        try {
            Object json = notification.extras.get(KEY_PARAM_CUSTOM);
            if (json != null) {
                XposedBridge.log(TAG + ": paramJSON=" + cap(String.valueOf(json), 3000));
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // 命中岛时，再单独把 extras 里「可读文本」的值全部打出来。
        // 实机里金额 / 余额未必都在 paramJSON 的 textInfo 里，靠这一行才能
        // 抓到「刷卡成功」那条通知的真实字段（事故排查关键）。
        if (looksLikeIsland) {
            try {
                XposedBridge.log(TAG + ": extrasTexts=" + extrasTexts(notification.extras));
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 把 extras 里所有可读文本按 key=value 形式拼成一行（用于抓真实字段）。 */
    private static String extrasTexts(Bundle extras) {
        if (extras == null) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        try {
            for (String key : extras.keySet()) {
                if (key == null) {
                    continue;
                }
                Object value = extras.get(key);
                String text = readable(value);
                if (text == null) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(key).append('=').append(cap(text, 200));
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private static String readable(Object value) {
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            return text.isEmpty() ? null : text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private static String describe(RemoteViews rv) {
        return IslandInfo.describe(rv);
    }

    private static String cap(String text, int max) {
        if (text == null) {
            return "null";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String keysOf(Bundle extras) {
        if (extras == null) {
            return "-";
        }
        try {
            StringBuilder builder = new StringBuilder();
            int count = 0;
            for (String key : extras.keySet()) {
                if (count++ >= 14) {
                    builder.append(",...");
                    break;
                }
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(key);
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String channelOf(Notification notification) {
        try {
            return String.valueOf(notification.getChannelId());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static boolean channelHas(Notification notification, String keyword) {
        try {
            String channel = notification.getChannelId();
            return channel != null && channel.toLowerCase().contains(keyword);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static RemoteViews asRemoteViews(Object value) {
        return value instanceof RemoteViews ? (RemoteViews) value : null;
    }

    private static RemoteViews fieldOf(Notification notification, String name) {
        try {
            Object value = XposedHelpers.getObjectField(notification, name);
            return value instanceof RemoteViews ? (RemoteViews) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String text(Bundle extras, String key) {
        try {
            CharSequence value = extras.getCharSequence(key);
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 按资源名解析目标 App 内的 id，解析不到返回 0。 */
    private static int idOf(String entryName) {
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context == null) {
                return 0;
            }
            Resources resources = context.getResources();
            return resources.getIdentifier(entryName, "id", context.getPackageName());
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
