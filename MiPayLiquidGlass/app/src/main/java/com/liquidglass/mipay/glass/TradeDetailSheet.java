package com.liquidglass.mipay.glass;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liquidglass.mipay.Config;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * iOS 风格的「交易详情」<b>全屏页面</b>：站点 + 时间 + 金额，顶部是交易地图。
 *
 * <h3>为什么由模块来画</h3>
 * <p>反编译 + 实机确认：交易记录列表（{@code record_container} → 分页
 * 「消费记录 / 充值记录」→ RecyclerView）的每一行是
 * {@code item_container}，行内已经有四个字段：</p>
 * <pre>
 * tv_key           交易类型（地铁 / 公交 / 消费 …）
 * tv_first_sub_key 站点（如「草桥-首经贸」）
 * tv_value         金额（如「-3.00元」）
 * tv_sub_value     时间（如「2026-09-06 19:28:13」）
 * </pre>
 * <p>但这些行<b>本身不可点击</b>（{@code clickable=false}，实机 dump 确认）。
 * 这里为每一行挂上点击监听，打开整屏页面（1.7.2 起由底部弹层改为全屏，
 * 地图再点一次还能进整屏地图页）。</p>
 *
 * <h3>地图画什么，按交易类型分三种</h3>
 * <ul>
 *     <li><b>地铁</b>且站名是「A-B」→ <b>哪到哪</b>：两端图钉 + 连线 + 站名标签；</li>
 *     <li><b>不完整交易</b>（地铁只有一段站名）→ <b>站点坐标</b>：单个图钉；</li>
 *     <li><b>公交</b> → <b>交易地点</b>：单个图钉（按公交站检索）。</li>
 * </ul>
 *
 * <p>全部逻辑包在 try/catch 里，任何异常都不会影响宿主 App。</p>
 */
public final class TradeDetailSheet {

    /** 已经挂过监听的条目，避免重复遍历时反复注册。 */
    private static final Map<View, Boolean> ATTACHED = new WeakHashMap<>();
    /** 行视图 → 交易通道码（trafficType，如 TU / CU）。 */
    private static final Map<View, String> CHANNEL = new WeakHashMap<>();
    /** 行视图 → 交易凭证原始字段（字段名 → 值），用于「凭证信息」一栏。 */
    private static final Map<View, java.util.LinkedHashMap<String, String>> EXTRAS =
            new WeakHashMap<>();
    /** 当前卡片的应用通道码：TU（交通联合）/ CU（住建部城市一卡通）。 */
    private static volatile String cardChannel;

    /**
     * 记录卡片的应用通道。
     *
     * <p>「交易通道」在交通卡领域指<b>卡上的应用通道</b>，不是交易记录里的字段
     * （本机地铁记录的 {@code mOrdinalTrafficType} 是 {@code 01}，那是卡内的通道序号，
     * 不是 TU/CU）。按公开交通卡协议资料与开源项目 TU-Reader（行旅录，Apache-2.0）：
     * 交通联合应用 AID {@code A000000632010105}/{@code A000000632010106} → {@code TU}；
     * 住建部城市一卡通 AID {@code A00000000386980701} → {@code CU}。</p>
     */
    static void noteCardInfo(Object cardInfo) {
        try {
            if (cardInfo == null) {
                return;
            }
            String aid = firstNonEmpty(stringOfMethod(cardInfo, "getCardAid"),
                    stringOfMethod(cardInfo, "getAid"),
                    stringOfField(cardInfo, "mAid"),
                    stringOfField(cardInfo, "mCardAid"));
            if (aid == null) {
                return;
            }
            String upper = aid.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
            // 宿主在 AID 后面还拼了卡号/机构码等尾串，实机两种形态：
            //   TU → A0000006320101050113581058000000（前缀即交通联合 AID）
            //   CU → A0000000032660869807010000000000（住建部 AID 86980701 在中间）
            // 所以用「包含」判定，而不是前缀。
            String code = null;
            if (upper.contains("A0000006320101")) {
                code = "TU";
            } else if (upper.contains("86980701")) {
                code = "CU";
            }
            if (code != null) {
                cardChannel = code;
            }
            android.util.Log.i("MiPayLiquidGlass/TradeSheet",
                    "卡片应用通道 AID=" + upper + " → " + code);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static String stringOfMethod(Object target, String method) {
        Object value = callIfExists(target, method);
        return value == null ? null : String.valueOf(value);
    }

    private static String stringOfField(Object target, String name) {
        Object value = readField(target, name);
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonEmpty(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isEmpty() && !"null".equals(value)) {
                    return value;
                }
            }
        }
        return null;
    }
    /** 行诊断只打一次。 */
    private static volatile boolean channelProbed;
    /** 列表数据诊断只打一次。 */
    private static volatile boolean listLogged;
    /** 模块配置（install 时注入）。 */
    private static volatile Config config;
    /** 站名 → 坐标，避免反复解析（打开整屏地图时会二次渲染）。 */
    /** 站名 → 解析结果（含官方站名 / 区域 / 线路），避免反复解析（打开整屏地图时会二次渲染）。 */
    private static final Map<String, TradePlaceResolver.Place> POINT_CACHE = new HashMap<>();
    /** 异步解析中的占位文案。 */
    private static final String LOADING = "定位中…";
    /** 弹层里的地图要联网，放到后台单线程。 */
    private static final ExecutorService MAP_IO =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "lg-trade-map");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- 配色全部取自 Miuix 官方色板（compose-miuix-ui/miuix）----
    // 来源：https://compose-miuix-ui.github.io/miuix/guide/colors
    /** primary —— 强调色（跳转/按钮/线路）。 */
    private static final int HYPER_ACCENT = 0xFF3482FF;
    /** primary(dark)。 */
    private static final int HYPER_ACCENT_DARK = 0xFF277AF7;
    /** 状态文字用强调色（Miuix 色板里没有单独的成功绿，用 primary 最贴主题）。 */
    private static final int HYPER_GREEN = 0xFF3482FF;
    /** surface —— 页面底色。 */
    private static final int LIGHT_PAGE = 0xFFF7F7F7;
    private static final int DARK_PAGE = 0xFF000000;
    /** surfaceContainer —— 分组卡片底色。 */
    private static final int LIGHT_CARD = 0xFFFFFFFF;
    private static final int DARK_CARD = 0xFF242424;
    /** onSurfaceContainer —— 主文字。 */
    private static final int LIGHT_TEXT = 0xFF000000;
    private static final int DARK_TEXT = 0xE6FFFFFF;
    private static final int LIGHT_VALUE = 0xFF000000;
    private static final int DARK_VALUE = 0xE6FFFFFF;
    /** onSurfaceContainerVariant（浅）/ onSurfaceContainerVariant（深）。 */
    private static final int LABEL = 0xFF949494;
    /** dividerLine。 */
    private static final int LIGHT_SEPARATOR = 0xFFE0E0E0;
    private static final int DARK_SEPARATOR = 0xFF393939;
    private static final int IOS_BLUE = HYPER_ACCENT;
    private static final int IOS_GREEN = HYPER_GREEN;
    /** 导航条底色与页面同色（Miuix 详情页就是如此）。 */
    private static final int LIGHT_NAV = 0xFFF7F7F7;
    private static final int DARK_NAV = 0xFF000000;
    /** tertiaryContainer —— 地图上的浅蓝小药丸。 */
    private static final int LIGHT_CHIP_ACCENT = 0xFFEAF2FF;
    private static final int DARK_CHIP_ACCENT = 0xFF2B3B54;
    /** primaryContainer —— 主按钮底色。 */
    private static final int LIGHT_BUTTON_FILL = 0xFF3482FF;
    private static final int DARK_BUTTON_FILL = 0xFF277AF7;

    private static int accent(boolean dark) {
        return dark ? HYPER_ACCENT_DARK : HYPER_ACCENT;
    }

    private static int pageColor(boolean dark) {
        return dark ? DARK_PAGE : LIGHT_PAGE;
    }

    private static int cardColor(boolean dark) {
        return dark ? DARK_CARD : LIGHT_CARD;
    }

    private static int onCard(boolean dark) {
        return dark ? DARK_VALUE : LIGHT_VALUE;
    }

    private static int dividerColor(boolean dark) {
        return dark ? DARK_SEPARATOR : LIGHT_SEPARATOR;
    }

    private static int chipAccentColor(boolean dark) {
        return dark ? DARK_CHIP_ACCENT : LIGHT_CHIP_ACCENT;
    }

    /**
     * 卡片圆角（pt）。
     *
     * <p>金额卡、明细卡、详情页顶部的地图、地图页的地图与信息卡<b>全部取这一个值</b>，
     * 所以「地图的圆角和下面框架一样」是同一个常量保证的，不是各写各的；
     * 圆角同时烘进地图位图（见 {@link TradeMapRenderer}）并对容器做裁剪，
     * 因此换任何圆角、任何尺寸都能自动适配。</p>
     */
    private static final float CARD_RADIUS_DP = 20f;
    /** 地图页四周留白（pt）。 */
    private static final float MAP_PAGE_PAD_DP = 12f;
    /** 公交上下车配对允许的最大时间差（3 小时）。 */
    private static final long MAX_TRIP_GAP_MS = 3L * 3600 * 1000;
    /** 交易时间可能出现的两种写法。 */
    private static final java.text.SimpleDateFormat[] TIME_FORMATS = {
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA),
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
    };
    private static final String[] SEPARATORS = {"-", "—", "–", "－", "至", "→", "~", "到"};
    /**
     * 交易记录里的「行程未结束」占位词。出站（或下车扣费）之前，应用会把终点写成
     * 这类词，例如截图里的「丰台南路-行程中」。
     *
     * <p>它们不是真实地点：如果不过滤，{@link #splitPlace} 会把 {@code 丰台南路-行程中}
     * 拆成两段并判定为「路线」，行程区块就会显示成「起点 丰台南路 / 终点 行程中」这种
     * 假行程，而正确的结果应当是只显示单点「交易地点 丰台南路」。</p>
     */
    private static final String[] PENDING_TERMS = {
            "行程中", "行程进行中", "进行中", "待扣费", "待扣款", "扣费中", "结算中",
            "交易中", "处理中", "未完成", "待出站", "出站中", "待补登"
    };
    private static final String[] PLACEHOLDERS = {
            "未知", "不详", "未知站", "未知站点", "无", "--", "-", "null", "N/A"
    };

    private TradeDetailSheet() {
    }

    /** 一条交易记录的数据。 */
    private static final class Trade {
        String type;
        String station;
        String amount;
        String time;
        /** 交易通道码（取自应用交易数据的 trafficType，如 TU / CU）。 */
        String channel;
        /** 交易凭证原始字段（字段名 → 值）。 */
        java.util.LinkedHashMap<String, String> extras;
    }

    /** 地图该怎么画。 */
    private static final class Plan {
        int mode = TradeMapRenderer.MODE_POINT;
        final List<String> labels = new ArrayList<>();
        final List<String> keywords = new ArrayList<>();
        String reason = "";
    }

    /** 公交配对出来的行程（上车站 → 下车站）。 */
    private static final class Trip {
        String boardingStation;
        String alightingStation;
    }

    /**
     * 由 {@link GlassViewStyler} 在遍历视图树时调用。
     *
     * @return 是否识别为交易记录条目（识别成功后已挂上点击监听）
     */
    static boolean attachIfRecordItem(View view, Config cfg) {
        try {
            if (cfg != null) {
                config = cfg;
            }
            if (view == null) {
                return false;
            }
            // 只认「就在眼前」的那一行：item_container 自己是行容器；
            // 若是上层大容器则往下探有限层数再找 —— 不设深度限制的话，
            // 页面根布局会因为子树里含有列表行而被当成行，点哪儿都弹第一条记录。
            View container = "item_container".equals(GlassChrome.entryNameOf(view))
                    ? view : findByEntryNameAtDepth(view, "item_container", 4);
            if (container == null || !(container instanceof ViewGroup)) {
                return false;
            }
            synchronized (ATTACHED) {
                if (ATTACHED.containsKey(container)) {
                    return true;
                }
            }
            final Trade trade = readTrade(container);
            if (!channelProbed) {
                channelProbed = true;
                probeRow(container);
            }
            scanRecycler(findRecyclerAncestor(container));
            trade.channel = channelOfRow(container);
            trade.extras = extrasOfRow(container);
            if (trade.amount == null && trade.time == null) {
                return false;
            }
            synchronized (ATTACHED) {
                ATTACHED.put(container, Boolean.TRUE);
            }
            container.setClickable(true);
            container.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View target) {
                    try {
                        // 公交（北京一卡通）上车记 0.00 元、下车才扣费，是两条独立记录。
                        // 点开时顺手在列表里找一下另一半，能配上就画「上车站 → 下车站」。
                        Trip trip = null;
                        if (isBusLike(trade)) {
                            trip = pairBusTrip(trade, collectSiblings(target));
                            if (trip != null) {
                                android.util.Log.i("MiPayLiquidGlass/TradeSheet",
                                        "公交配对成功：上车站=" + trip.boardingStation
                                                + " 下车站=" + trip.alightingStation);
                            } else if (isZeroAmount(trade.amount)) {
                                android.util.Log.i("MiPayLiquidGlass/TradeSheet",
                                        "公交上车记录未找到配对的下车扣费，按单点显示："
                                                + trade.station);
                            }
                        }
                        showDetailPage(target.getContext(), trade, trip, config);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            });
            android.util.Log.i("MiPayLiquidGlass/TradeSheet",
                    "已接管交易条目点击：类型=" + trade.type + " 站点=" + trade.station
                            + " 时间=" + trade.time + " 金额=" + trade.amount
                            + " 通道=" + trade.channel);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 限定层数地按资源项名找视图（避免把高层容器误认成列表行）。 */
    private static View findByEntryNameAtDepth(View root, String entryName, int maxDepth) {
        if (root == null || maxDepth < 0) {
            return null;
        }
        if (entryName.equals(GlassChrome.entryNameOf(root))) {
            return root;
        }
        if (maxDepth == 0 || !(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findByEntryNameAtDepth(group.getChildAt(i), entryName, maxDepth - 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 从一行里读出四个字段。 */
    private static Trade readTrade(View container) {
        Trade trade = new Trade();
        trade.type = textOf(container, "tv_key");
        trade.station = textOf(container, "tv_first_sub_key");
        trade.amount = textOf(container, "tv_value");
        trade.time = textOf(container, "tv_sub_value");
        return trade;
    }

    /**
     * 读这一行的「交易通道」码。
     *
     * <p>反编译确认：应用的交易数据对象上有 {@code getTrafficType()} /
     * {@code getOrdinalTrafficType()}（交通卡通道，取值形如 {@code TU} / {@code CU}），
     * 但界面资源里<b>没有</b>对应文案，所以它不在列表上显示。
     * 这里从列表适配器的数据对象里把它取出来，交给「交易通道」一行显示。</p>
     */
    private static void readChannel(Object adapter, Integer position, View itemView) {
        try {
            if (adapter == null || position == null || itemView == null) {
                return;
            }
            Object item = null;
            boolean byGetItem = true;
            try {
                item = XposedHelpers.callMethod(adapter, "getItem", position);
            } catch (Throwable ignored) {
                byGetItem = false;
                item = itemFromAdapterFields(adapter, position);
            }
            StringBuilder info = new StringBuilder();
            info.append("绑定行 position=").append(position)
                    .append(" 适配器=").append(adapter.getClass().getName())
                    .append(" getItem=").append(byGetItem);
            if (item == null) {
                info.append(" → 未取到数据对象；字段=").append(fieldNamesOf(adapter));
                android.util.Log.i("MiPayLiquidGlass/TradeSheet", info.toString());
                return;
            }
            Object trafficType = callIfExists(item, "getTrafficType");
            Object ordinal = callIfExists(item, "getOrdinalTrafficType");
            String code = codeOf(trafficType);
            info.append(" 数据=").append(item.getClass().getName())
                    .append(" trafficType=").append(trafficType)
                    .append(" ordinal=").append(ordinal)
                    .append(" → 通道=").append(code);
            android.util.Log.i("MiPayLiquidGlass/TradeSheet", info.toString());
            if (code != null) {
                synchronized (CHANNEL) {
                    CHANNEL.put(itemView, code);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * 载入宿主里的 androidx 类。
     *
     * <p>不能用 {@code XposedHelpers.findClass}：实机日志显示它会把
     * {@code androidx.recyclerview.widget.RecyclerView$Adapter} 里的点改写成 {@code $}
     * （变成 {@code androidx$recyclerview$...}）然后找不到类。这里依次尝试
     * 原名、改写名、以及 Xposed 的实现。</p>
     */
    private static Class<?> loadClass(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            // 继续尝试
        }
        try {
            int dot = name.indexOf('.');
            String mangled = dot < 0 ? name
                    : name.substring(0, dot) + name.substring(dot).replace('.', '$');
            return Class.forName(mangled, false, loader);
        } catch (Throwable ignored) {
            // 继续尝试
        }
        try {
            return XposedHelpers.findClass(name, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 诊断：打印行视图的祖先链与 tag，用于定位列表数据对象。 */
    private static void probeRow(View row) {
        try {
            StringBuilder chain = new StringBuilder();
            View node = row;
            int guard = 0;
            while (node != null && guard++ < 12) {
                if (chain.length() > 0) {
                    chain.append(" < ");
                }
                chain.append(node.getClass().getSimpleName());
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
            android.util.Log.i("MiPayLiquidGlass/TradeSheet",
                    "行诊断 链=" + chain + " tag=" + row.getTag());
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 顺视图树往上找 RecyclerView 祖先。
     *
     * <p>为什么不直接 {@code Class.forName("androidx...RecyclerView")}：实机日志显示
     * 宿主的 classloader 里加载不到这个名字（uiautomator 能看到类名，
     * 但模块的 classloader 拿不到），所以改成按<b>类名后缀</b>在视图树里认，
     * 再反射调用它自己的 {@code getAdapter()}。</p>
     */
    private static View findRecyclerAncestor(View row) {
        try {
            View node = row;
            int guard = 0;
            while (node != null && guard++ < 20) {
                String name = node.getClass().getName();
                if (name != null && name.endsWith("RecyclerView")) {
                    return node;
                }
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    /** 把这一屏的「交易通道」码按时间匹配挂到各行上。 */
    private static void scanRecycler(View recycler) {
        try {
            if (!(recycler instanceof ViewGroup)) {
                return;
            }
            Object adapter = callIfExists(recycler, "getAdapter");
            if (adapter == null) {
                android.util.Log.i("MiPayLiquidGlass/TradeSheet", "列表未拿到 adapter");
                return;
            }
            List<?> items = listFromAdapter(adapter);
            if (items == null) {
                android.util.Log.i("MiPayLiquidGlass/TradeSheet", "适配器无可读 List："
                        + adapter.getClass().getName() + " 字段=" + fieldNamesOf(adapter));
                return;
            }
            ViewGroup group = (ViewGroup) recycler;
            boolean logged = false;
            if (!listLogged) {
                listLogged = true;
                android.util.Log.i("MiPayLiquidGlass/TradeSheet", "列表 size=" + items.size()
                        + " 字段=" + describeItem(items.get(0))
                        + " 适配器=" + adapter.getClass().getName());
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                View container = findByEntryName(child, "item_container");
                if (container == null) {
                    continue;
                }
                // 用 ViewHolder 的真实位置取数据对象：屏幕上的子视图序号
                // 与 adapter 位置并不相等（可能已经有滚动偏移）。
                int position = positionOf(recycler, child);
                Object match = null;
                if (position >= 0) {
                    match = callWithArgs(adapter, "getItem", position);
                }
                if (match == null && position >= 0 && position < items.size()) {
                    match = items.get(position);
                }
                if (match == null) {
                    String time = textOf(container, "tv_sub_value");
                    String wanted = digitsOf(time);
                    if (!wanted.isEmpty()) {
                        for (Object item : items) {
                            if (item != null
                                    && digitsOf(String.valueOf(item)).contains(wanted)) {
                                match = item;
                                break;
                            }
                        }
                    }
                }
                if (match == null) {
                    continue;
                }
                if (!logged) {
                    logged = true;
                    android.util.Log.i("MiPayLiquidGlass/TradeSheet",
                            "列表数据 position=" + position + " 字段=" + describeItem(match));
                }
                collectExtras(container, match, items);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 取某一行的真实 adapter 位置（子视图序号会因滚动偏移而不等于位置）。 */
    private static int positionOf(View recycler, View child) {
        try {
            Object holder = XposedHelpers.callMethod(recycler, "getChildViewHolder", child);
            if (holder == null) {
                return -1;
            }
            Object position = callIfExists(holder, "getAbsoluteAdapterPosition");
            if (position == null) {
                position = callIfExists(holder, "getBindingAdapterPosition");
            }
            if (position == null) {
                position = callIfExists(holder, "getAdapterPosition");
            }
            return position instanceof Integer ? (Integer) position : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static List<?> listFromAdapter(Object adapter) {
        try {
            Class<?> type = adapter.getClass();
            while (type != null && type != Object.class) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(adapter);
                    if (value instanceof java.util.List && !((java.util.List<?>) value).isEmpty()) {
                        return (java.util.List<?>) value;
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    /**
     * 把交易凭证（{@code com.tsmclient.smartcard.model.TradeLog}）的原始字段搬到界面上。
     *
     * <p>这些字段应用<b>都没有显示</b>（界面资源里没有对应文案），但对账和排查很有用：
     * 尤其是「通道码」（{@code mOrdinalTrafficType}，本机地铁记录为 {@code 01}，
     * 北京一卡通公交记录为 {@code TU}/{@code CU} 一类）。</p>
     */
    private static void collectExtras(View container, Object item, List<?> items) {
        try {
            java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
            putExtra(map, item, "mTrafficType", "交通类型");
            putExtra(map, item, "mOrdinalTrafficType", "通道码");
            putExtra(map, item, "mConsumptionType", "消费类型");
            putExtra(map, item, "mTradeType", "交易类型码");
            putExtra(map, item, "mTerminalNo", "终端编号");
            putExtra(map, item, "mTradeID", "交易凭证号");
            // 下面三个字段应用界面同样没有显示，但北京互通卡（交通联合）公交记录里
            // 列表首行常常只是线路名（如「1路」），真正的站点写在凭证里，
            // 所以必须一起抓出来，否则「交易地点」和站点解析都拿不到站名。
            putExtra(map, item, "mStartToEndStation", "起终点站");
            putExtra(map, item, "mAppFunctionStationDescription", "站点描述");
            putExtra(map, item, "mBusinessName", "业务名称");
            String channel = map.get("通道码");
            if (channel == null || channel.isEmpty()) {
                channel = map.get("交通类型");
            }
            synchronized (CHANNEL) {
                if (channel != null && !channel.isEmpty()) {
                    CHANNEL.put(container, channel);
                }
                if (!map.isEmpty()) {
                    EXTRAS.put(container, map);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void putExtra(java.util.LinkedHashMap<String, String> map, Object item,
                                 String field, String label) {
        try {
            Object value = readField(item, field);
            if (value == null) {
                return;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty() || "null".equals(text)) {
                return;
            }
            map.put(label, text);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static Object readField(Object target, String name) {
        try {
            Class<?> type = target.getClass();
            while (type != null && type != Object.class) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    // 往父类找
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static java.util.LinkedHashMap<String, String> extrasOfRow(View row) {
        if (row == null) {
            return null;
        }
        synchronized (CHANNEL) {
            java.util.LinkedHashMap<String, String> map = EXTRAS.get(row);
            return map == null ? null : new java.util.LinkedHashMap<>(map);
        }
    }

    /** 读凭证里某一项（按界面标签名），没有返回 null。 */
    private static String extraOf(Trade trade, String label) {
        if (trade == null || trade.extras == null) {
            return null;
        }
        String value = trade.extras.get(label);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    /**
     * 取最能代表「站点」的一串文字。
     *
     * <p>北京互通卡（交通联合）公交记录的列表首行经常是线路名（如「1路」「快速直达专线」），
     * 真正的站点写在应用界面上看不到的 {@code mStartToEndStation} /
     * {@code mAppFunctionStationDescription} 里。所以这里在列表首行看着像线路名时，
     * 优先用凭证里的起终点站，其次才是列表行文字；列表行看着像站名时则以它为准。
     * 这与行旅录（TU-Reader）的站点定位顺序「站点 → 线路 → 交通类型」一致。</p>
     */
    private static String stationOf(Trade trade) {
        if (trade == null) {
            return null;
        }
        String base = trade.station == null ? "" : trade.station.trim();
        String startEnd = extraOf(trade, "起终点站");
        String described = extraOf(trade, "站点描述");
        if (looksLikeLine(base)) {
            return firstNonEmpty(startEnd, described, base);
        }
        return firstNonEmpty(base, startEnd, described);
    }

    /**
     * 一串文字是否更像「线路名」而不是「站点名」。
     *
     * <p>只认最典型的公交线路写法，避免把真正的站名（如「八里庄路」）误判成线路。</p>
     */
    private static boolean looksLikeLine(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String value = text.trim();
        // 1路 / 夜1路 / 专10路 / 特8内环 / 10号线 / 快速直达专线 / 机场大巴
        if (value.matches("^(夜|专|特|快|慢|区间|快专|节假日|观光|机场)?[0-9]{1,3}路.*$")) {
            return true;
        }
        if (value.matches("^[0-9]{1,3}号线.*$")) {
            return true;
        }
        return value.contains("专线") || value.contains("快线") || value.endsWith("环线");
    }

    /** 只保留数字（用于跨格式比对时间，如 19:28:13 ↔ 192813）。 */
    private static String digitsOf(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /** 反射列出对象的全部字段名=值，用来找「交易通道」这类没有界面的字段。 */
    private static String describeItem(Object item) {
        StringBuilder builder = new StringBuilder();
        if (item == null) {
            return "null";
        }
        try {
            Class<?> type = item.getClass();
            int guard = 0;
            while (type != null && type != Object.class && guard++ < 5) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(item);
                        if (builder.length() > 0) {
                            builder.append(", ");
                        }
                        builder.append(field.getName()).append('=')
                                .append(abbreviate(String.valueOf(value)));
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    if (builder.length() > 900) {
                        return builder.append("…").toString();
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return builder.toString();
    }

    /** 列出适配器及其父类的字段名，便于定位存放数据的字段。 */
    private static String fieldNamesOf(Object adapter) {
        StringBuilder builder = new StringBuilder();
        try {
            Class<?> type = adapter.getClass();
            int guard = 0;
            while (type != null && type != Object.class && guard++ < 6) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (builder.length() > 0) {
                        builder.append(',');
                    }
                    builder.append(field.getName());
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return builder.toString();
    }

    /** 适配器没有 getItem 时，扫它的 List 字段按位置取。 */
    private static Object itemFromAdapterFields(Object adapter, int position) {
        try {
            Class<?> type = adapter.getClass();
            while (type != null && type != Object.class) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(adapter);
                    if (value instanceof java.util.List) {
                        java.util.List<?> list = (java.util.List<?>) value;
                        if (position >= 0 && position < list.size()) {
                            return list.get(position);
                        }
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static Object callIfExists(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object callWithArgs(Object target, String method, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] instanceof Integer ? int.class : Object.class;
            }
            return target.getClass().getMethod(method, types).invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把 trafficType 归一成 1~6 位大写码（String 直接用，枚举取 name）。 */
    private static String codeOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof Enum
                ? ((Enum<?>) value).name() : String.valueOf(value);
        if (text == null) {
            return null;
        }
        String trimmed = text.trim().toUpperCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > 6 || "NULL".equals(trimmed)) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isLetterOrDigit(trimmed.charAt(i))) {
                return null;
            }
        }
        return trimmed;
    }

    private static String channelOfRow(View row) {
        if (row == null) {
            return null;
        }
        synchronized (CHANNEL) {
            return CHANNEL.get(row);
        }
    }

    /** 把当前可见的列表行都读出来（RecyclerView 只保留可见项，配对够用了）。 */
    private static List<Trade> collectSiblings(View row) {
        List<Trade> out = new ArrayList<>();
        try {
            View node = row;
            ViewGroup list = null;
            while (node != null) {
                if (node instanceof ViewGroup
                        && node.getClass().getName().contains("RecyclerView")) {
                    list = (ViewGroup) node;
                    break;
                }
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
            if (list == null) {
                return out;
            }
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                View container = "item_container".equals(GlassChrome.entryNameOf(child))
                        ? child : findByEntryName(child, "item_container");
                if (container != null) {
                    out.add(readTrade(container));
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return out;
    }

    /**
     * 北京一卡通公交：上车生成 0.00 元记录、下车才扣费，两条记录彼此独立，
     * App 自己不会把它们合成一段行程。
     *
     * <p>这里的配对规则（保守优先，配不上就按单点）：</p>
     * <ol>
     *     <li>类型相同（都含「公交」）、一条是 0.00 元（上车）、另一条不是（下车）；</li>
     *     <li>两条时间差不超过 3 小时；时间解析不出来时不采用该候选；</li>
     *     <li>多个候选取时间最近的。</li>
     * </ol>
     */
    private static Trip pairBusTrip(Trade clicked, List<Trade> siblings) {
        try {
            if (clicked == null || siblings == null || siblings.isEmpty()) {
                return null;
            }
            boolean boarding = isZeroAmount(clicked.amount);
            long clickedTime = parseTime(clicked.time);
            Trade best = null;
            long bestGap = Long.MAX_VALUE;
            for (Trade other : siblings) {
                if (other == null || sameRow(clicked, other)) {
                    continue;
                }
                if (!sameKind(clicked, other)) {
                    continue;
                }
                if (isZeroAmount(other.amount) == boarding) {
                    continue; // 需要「一条 0.00 + 一条非 0.00」
                }
                long otherTime = parseTime(other.time);
                if (clickedTime <= 0 || otherTime <= 0) {
                    continue; // 时间不可靠就不硬配
                }
                long gap = Math.abs(otherTime - clickedTime);
                if (gap > MAX_TRIP_GAP_MS) {
                    continue;
                }
                if (gap < bestGap) {
                    bestGap = gap;
                    best = other;
                }
            }
            if (best == null) {
                return null;
            }
            Trip trip = new Trip();
            // 取「最像站点」的文字（线路名会被凭证里的起终点站替换掉），
            // 这样北京互通卡公交也能画到正确的上车站 → 下车站。
            trip.boardingStation = boarding ? stationOf(clicked) : stationOf(best);
            trip.alightingStation = boarding ? stationOf(best) : stationOf(clicked);
            return trip;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean sameRow(Trade one, Trade other) {
        return equalsText(one.time, other.time)
                && equalsText(one.amount, other.amount)
                && equalsText(one.station, other.station)
                && equalsText(one.type, other.type);
    }

    private static boolean equalsText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean sameKind(Trade one, Trade other) {
        String a = one.type == null ? "" : one.type;
        String b = other.type == null ? "" : other.type;
        return a.contains("公交") && b.contains("公交");
    }

    private static boolean isBusLike(Trade trade) {
        if (trade == null) {
            return false;
        }
        String type = trade.type == null ? "" : trade.type;
        if (type.contains("公交") || type.contains("巴士") || type.contains("轮渡")) {
            return true;
        }
        // 地铁/轨道类记录即使金额为 0.00 元（进站待扣费），也不是公交上车记录，
        // 不能拿去做「上车站 → 下车站」配对。
        if (type.contains("地铁") || type.contains("轨道") || type.contains("轻轨")
                || type.contains("有轨")) {
            return false;
        }
        return isZeroAmount(trade.amount);
    }

    /** 金额是否为 0.00（上车记录的标志）。 */
    private static boolean isZeroAmount(String amount) {
        if (amount == null || amount.isEmpty()) {
            return false;
        }
        String digits = amount.replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return false;
        }
        try {
            return Double.parseDouble(digits) == 0d;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 解析「2026-09-06 19:28:13」；解析不出来返回 -1。 */
    private static long parseTime(String text) {
        if (text == null || text.trim().isEmpty()) {
            return -1L;
        }
        String value = text.trim();
        for (java.text.SimpleDateFormat format : TIME_FORMATS) {
            try {
                java.util.Date date = format.parse(value);
                if (date != null) {
                    return date.getTime();
                }
            } catch (Throwable ignored) {
                // 换下一种格式
            }
        }
        return -1L;
    }

    /**
     * 安装列表绑定钩子。
     *
     * <p>交易记录是异步拉取的，页面 onResume 之后那次延迟遍历跑的时候，
     * 列表项往往还没创建。{@code RecyclerView.Adapter#bindViewHolder}
     * 在每个 item 绑定时都会回调，挂在这里就一定拿得到每一行。</p>
     */
    public static void install(ClassLoader classLoader, Config cfg) {
        config = cfg;
        try {
            Class<?> adapter;
            Class<?> holder;
            try {
                adapter = loadClass(classLoader,
                        "androidx.recyclerview.widget.RecyclerView$Adapter");
                holder = loadClass(classLoader,
                        "androidx.recyclerview.widget.RecyclerView$ViewHolder");
            } catch (Throwable notFound) {
                XposedBridge.log("MiPayLiquidGlass/TradeSheet: 找不到 androidx RecyclerView 类："
                        + notFound);
                return;
            }
            if (adapter == null || holder == null) {
                XposedBridge.log("MiPayLiquidGlass/TradeSheet: androidx RecyclerView 类为空，跳过");
                return;
            }
            XposedHelpers.findAndHookMethod(adapter, "bindViewHolder", holder, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object itemView = XposedHelpers.getObjectField(param.args[0],
                                        "itemView");
                                if (itemView instanceof View) {
                                    readChannel(param.thisObject, (Integer) param.args[1],
                                            (View) itemView);
                                    attachIfRecordItem((View) itemView, config);
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
            XposedBridge.log("MiPayLiquidGlass/TradeSheet: 已挂 RecyclerView.Adapter#bindViewHolder");
        } catch (Throwable throwable) {
            XposedBridge.log("MiPayLiquidGlass/TradeSheet: 挂 bindViewHolder 失败 " + throwable);
        }
    }

    // ======================================================================
    // 全屏「交易详情」页
    // ======================================================================

    private static void showDetailPage(Context context, Trade trade, Trip trip, Config cfg) {
        final Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing()) {
            return;
        }
        float d = activity.getResources().getDisplayMetrics().density;
        if (d <= 0f) {
            d = 3f;
        }
        final float density = d;
        final boolean dark = com.liquidglass.mipay.Utils.isNightMode(activity.getResources());
        final Plan plan = planFor(trade, trip);

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 根布局用 FrameLayout：内容铺满整屏，顶栏浮在内容之上，
        // 上滑时内容从顶栏下面滑过 —— 这是 Miuix「大标题折叠」的前提。
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(pageColor(dark));

        // Miuix TopAppBarDefaults.CollapsedHeight：折叠高度 52dp
        final int barHeight = dp(52, d);
        final View.OnClickListener dismiss = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        };

        // ---- 大标题（Miuix TopAppBar 的 largeTitle：title2 24sp / 水平内边距 26dp）----
        final TextView largeTitle = new TextView(activity);
        largeTitle.setText("交易详情");
        largeTitle.setTextSize(24f);
        largeTitle.setTextColor(dark ? DARK_VALUE : LIGHT_VALUE);
        largeTitle.setGravity(Gravity.START);
        // 内容区自身有 16dp 内边距，这里补足到 Miuix 的 26dp
        largeTitle.setPadding(dp(10, d), 0, dp(10, d), 0);

        // ---- 顶栏（Miuix TopAppBar：背景恒为不透明 surface，不做毛玻璃）----
        FrameLayout bar = new FrameLayout(activity);
        bar.setBackgroundColor(pageColor(dark));
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.TOP));

        // 导航图标：Miuix 是扁平返回箭头（无底色），起始边距 16dp
        final View backButton = plainBackButton(activity, d, dark, dismiss);
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(
                dp(40, d), dp(40, d));
        backParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        backParams.leftMargin = dp(16, d);
        bar.addView(backButton, backParams);

        // 折叠后的小标题：Miuix 是「左对齐、跟在导航图标之后」（不是居中），
        // 字号 main 17sp、颜色 onSurface
        final TextView barTitle = new TextView(activity);
        barTitle.setText("交易详情");
        barTitle.setTextSize(17f);
        barTitle.setTextColor(dark ? DARK_VALUE : LIGHT_VALUE);
        barTitle.setGravity(Gravity.CENTER_VERTICAL);
        barTitle.setAlpha(0f);
        FrameLayout.LayoutParams barTitleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        barTitleParams.gravity = Gravity.START;
        barTitleParams.leftMargin = dp(16, d) + dp(40, d) + dp(4, d);
        bar.addView(barTitle, barTitleParams);

        // ---- 地图（放进下面的滚动内容里，跟随页面一起滚）----
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        final int heroHeight = Math.round(screenHeight * 0.34f);
        final int mapRadius = dp(CARD_RADIUS_DP, d);
        final int heroWidth = screenWidth - dp(16, d) * 2;
        FrameLayout mapBox = new FrameLayout(activity);
        mapBox.setBackgroundColor(dark ? 0xFF16202B : 0xFFDFEAF4);
        clipRounded(mapBox, mapRadius);
        mapBox.setClipChildren(true);
        mapBox.setClipToPadding(true);

        final ZoomableImageView hero = new ZoomableImageView(activity);
        hero.setScaleType(ImageView.ScaleType.MATRIX);
        mapBox.addView(hero, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String heroTag = plan != null && !plan.labels.isEmpty()
                ? join(plan.labels) : trade.station;
        if (heroTag != null && !heroTag.isEmpty()) {
            mapBox.addView(chip(activity, d, dark, heroTag, false),
                    chipParams(d, Gravity.BOTTOM | Gravity.START, 12, 12));
        }
        mapBox.addView(chip(activity, d, dark, "查看大地图", true),
                chipParams(d, Gravity.TOP | Gravity.END, 12, 12));

        // 缩放按钮（叠在地图右下角，液态玻璃圆底）
        LinearLayout zoomGroup = new LinearLayout(activity);
        zoomGroup.setOrientation(LinearLayout.VERTICAL);
        zoomGroup.addView(zoomButton(activity, d, config, dark, "+", hero, 1.5f));
        LinearLayout.LayoutParams minusParams = wrapWrap();
        minusParams.topMargin = dp(8, d);
        zoomGroup.addView(zoomButton(activity, d, config, dark, "−", hero, 1f / 1.5f), minusParams);
        mapBox.addView(zoomGroup, chipParams(d, Gravity.BOTTOM | Gravity.END, 12, 12));

        // ---- 内容区（顶部留出顶栏高度，上滑时从顶栏下面穿过）----
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, barHeight, 0, 0);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16, d);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 大标题是滚动内容的第一块
        LinearLayout.LayoutParams largeTitleParams = wrapWrap();
        largeTitleParams.bottomMargin = dp(14, d);
        content.addView(largeTitle, largeTitleParams);

        // 地图作为滚动内容的第二块 —— 页面上下滑动时地图跟着一起走
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroHeight);
        content.addView(mapBox, mapParams);

        // ---- 上滑折叠（Miuix TopAppBar 行为）----
        // 顶栏高度恒定、底色恒为不透明 surface，滚动时只切换标题：
        // 大标题淡出、折叠小标题淡入，进度线性映射，避免两层标题同时可见。
        // 监听器里要用密度，先固化一份（外层 d 不是有效最终变量）
        final float collapseDensity = d;
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldX, int oldY) {
                float span = Math.max(1f, dp(52, collapseDensity));
                float progress = Math.min(1f, Math.max(0f, scrollY / span));
                largeTitle.setAlpha(1f - progress);
                largeTitle.setTranslationY(-dp(10, collapseDensity) * progress);
                barTitle.setAlpha(progress);
            }
        });
        // 修「导航栏层级」：顶栏必须压在滚动内容之上。
        // 顶栏先于 ScrollView 加入根布局，后加入的内容会盖住它；
        // bringToFront 把顶栏提到最上层（绘制与触摸都优先落在顶栏）。
        // Miuix 顶栏是扁平 surface，不加 elevation，避免多出一道投影。
        bar.bringToFront();

        // 金额卡
        LinearLayout amountCard = card(activity, dark, d);
        content.addView(amountCard, matchWrap());

        String[] split = splitAmount(trade.amount);
        LinearLayout amountRow = new LinearLayout(activity);
        amountRow.setOrientation(LinearLayout.HORIZONTAL);
        amountRow.setGravity(Gravity.CENTER_VERTICAL);
        amountCard.addView(amountRow, matchWrap());

        TextView amountText = new TextView(activity);
        amountText.setText(split[0]);
        amountText.setTextSize(34f);
        amountText.setTextColor(dark ? DARK_TEXT : LIGHT_TEXT);
        amountText.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        amountText.setIncludeFontPadding(false);
        amountRow.addView(amountText, wrapWrap());
        if (split[1] != null) {
            TextView unit = new TextView(activity);
            unit.setText(split[1]);
            unit.setTextSize(16f);
            unit.setTextColor(LABEL);
            unit.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams unitParams = wrapWrap();
            unitParams.leftMargin = dp(3, d);
            amountRow.addView(unit, unitParams);
        }
        TextView status = new TextView(activity);
        status.setText(statusText(trade.amount));
        status.setTextSize(13f);
        status.setTextColor(IOS_GREEN);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams statusParams = wrapWrap();
        statusParams.leftMargin = dp(10, d);
        amountRow.addView(status, statusParams);

        TextView subtitle = new TextView(activity);
        subtitle.setText(plan != null ? plan.reason : "");
        subtitle.setTextSize(12f);
        subtitle.setTextColor(LABEL);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(6, d);
        amountCard.addView(subtitle, subtitleParams);

        boolean route = plan != null && plan.mode == TradeMapRenderer.MODE_ROUTE
                && plan.labels.size() >= 2;

        // ---- 行程 ----
        content.addView(sectionTitle(activity, d, "行程"));
        LinearLayout tripCard = card(activity, dark, d);
        content.addView(tripCard, matchWrap());
        if (route) {
            tripCard.addView(addRow(activity, d, dark, "起点", plan.labels.get(0), true).root);
            tripCard.addView(addRow(activity, d, dark, "终点", plan.labels.get(1), true).root);
        } else {
            // 单点交易（含「行程中」这种未结束的记录）：不编造路线，只给出真实交易地点。
            tripCard.addView(addRow(activity, d, dark, "交易地点",
                    plan != null && !plan.labels.isEmpty() ? plan.labels.get(0) : trade.station,
                    true).root);
        }
        tripCard.addView(addRow(activity, d, dark, "类型", trade.type, true).root);
        tripCard.addView(addRow(activity, d, dark, "站点记录", trade.station, true).root);
        final InfoRow linesRow = addRow(activity, d, dark, "途经线路", LOADING, true);
        tripCard.addView(linesRow.root);
        final InfoRow areaRow = addRow(activity, d, dark, "所属区域", LOADING, false);
        tripCard.addView(areaRow.root);

        // ---- 交易信息 ----
        content.addView(sectionTitle(activity, d, "交易信息"));
        LinearLayout infoCard = card(activity, dark, d);
        content.addView(infoCard, matchWrap());
        infoCard.addView(addRow(activity, d, dark, "状态", statusText(trade.amount), true).root);
        infoCard.addView(addRow(activity, d, dark, "交易时间", trade.time, true).root);
        infoCard.addView(addRow(activity, d, dark, "交易金额", trade.amount, true).root);
        infoCard.addView(addRow(activity, d, dark, "交易通道", channelText(trade), false).root);

        // ---- 凭证信息（应用界面上看不到的原始字段）----
        if (trade.extras != null && !trade.extras.isEmpty()) {
            content.addView(sectionTitle(activity, d, "凭证信息"));
            LinearLayout extraCard = card(activity, dark, d);
            content.addView(extraCard, matchWrap());
            int index = 0;
            int total = trade.extras.size();
            for (java.util.Map.Entry<String, String> entry : trade.extras.entrySet()) {
                index++;
                extraCard.addView(addRow(activity, d, dark, entry.getKey(),
                        entry.getValue(), index < total).root);
            }
        }

        // ---- 站点信息（高德解析出的官方站名 / 区域 / 线路）----
        final List<String> infoLabels = plan != null && !plan.labels.isEmpty()
                ? new ArrayList<>(plan.labels) : singleList(trade.station);
        content.addView(sectionTitle(activity, d, "站点信息"));
        LinearLayout stationCard = card(activity, dark, d);
        content.addView(stationCard, matchWrap());
        final List<TextView[]> stationViews = new ArrayList<>();
        for (int i = 0; i < infoLabels.size(); i++) {
            TextView[] holder = new TextView[2];
            View block = stationBlock(activity, d, dark, infoLabels.get(i), holder);
            stationCard.addView(block,
                    i == infoLabels.size() - 1 ? wrapWrap() : withBottomMargin(d, 14));
            stationViews.add(holder);
        }
        content.addView(attribution(activity, d));

        // 先用「风格化地图」占位，联网解析成功后再换成真地图
        List<String> fallbackLabels = plan != null && !plan.labels.isEmpty()
                ? plan.labels : singleList(trade.station);
        int mode = plan == null ? TradeMapRenderer.MODE_POINT : plan.mode;
        hero.setImageBitmap(TradeMapRenderer.fallback(fallbackLabels, mode,
                heroWidth, heroHeight, mapRadius, dark));

        final int finalMode = mode;
        final List<String> finalLabels = fallbackLabels;
        final List<String> keywords = plan == null ? fallbackLabels : plan.keywords;
        final String tradeType = trade.type;
        MAP_IO.execute(new Runnable() {
            @Override
            public void run() {
                loadMapInto(activity.getApplicationContext(), tradeType, keywords, finalLabels,
                        finalMode, heroWidth, heroHeight, mapRadius, dark, hero, "详情页",
                        new OnPlacesResolved() {
                            @Override
                            public void onResolved(List<TradePlaceResolver.Place> places) {
                                fillPlaceInfo(places, infoLabels, stationViews, linesRow, areaRow);
                            }
                        });
            }
        });

        final Plan finalPlan = plan;
        mapBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    showMapPage(activity, trade, finalPlan, dark, density,
                            screenWidth, screenHeight);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        });

        dialog.setContentView(root);
        applyFullScreen(dialog, root, !dark);
        dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface dlg, int code, KeyEvent event) {
                if (code == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    dlg.dismiss();
                    return true;
                }
                return false;
            }
        });
        try {
            dialog.show();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 全屏「地图」页
    // ======================================================================

    private static void showMapPage(Activity activity, Trade trade, Plan plan, boolean dark,
                                    float d, int screenWidth, int screenHeight) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(dark ? DARK_PAGE : LIGHT_PAGE);

        // 导航条（Miuix TopAppBar：不透明 surface 底 + 扁平返回箭头）
        FrameLayout nav = new FrameLayout(activity);
        nav.setBackgroundColor(dark ? DARK_NAV : LIGHT_NAV);
        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52, d)));

        View back = plainBackButton(activity, d, dark,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                    }
                });
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(
                dp(40, d), dp(40, d));
        backParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        backParams.leftMargin = dp(8, d);
        nav.addView(back, backParams);

        TextView title = new TextView(activity);
        title.setText(mapTitle(plan, trade));
        title.setTextSize(17f);
        title.setTextColor(dark ? DARK_TEXT : LIGHT_TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nav.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        // 地图卡片：与下方信息卡同圆角（CARD_RADIUS_DP）、同左右边距
        final int pad = dp(MAP_PAGE_PAD_DP, d);
        final int mapRadius = dp(CARD_RADIUS_DP, d);
        FrameLayout mapBox = new FrameLayout(activity);
        mapBox.setBackgroundColor(dark ? 0xFF16202B : 0xFFDFEAF4);
        clipRounded(mapBox, mapRadius);
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mapParams.leftMargin = pad;
        mapParams.rightMargin = pad;
        root.addView(mapBox, mapParams);

        final ZoomableImageView map = new ZoomableImageView(activity);
        map.setScaleType(ImageView.ScaleType.MATRIX);
        mapBox.addView(map, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 大地图同样支持缩放（右下角两个液态玻璃按钮）
        LinearLayout bigZoom = new LinearLayout(activity);
        bigZoom.setOrientation(LinearLayout.VERTICAL);
        bigZoom.addView(zoomButton(activity, d, config, dark, "+", map, 1.5f));
        LinearLayout.LayoutParams bigMinus = wrapWrap();
        bigMinus.topMargin = dp(8, d);
        bigZoom.addView(zoomButton(activity, d, config, dark, "−", map, 1f / 1.5f), bigMinus);
        mapBox.addView(bigZoom, chipParams(d, Gravity.BOTTOM | Gravity.END, 12, 12));

        // 底部信息卡
        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(rounded(dark ? DARK_CARD : LIGHT_CARD, mapRadius));
        bottom.setPadding(dp(18, d), dp(14, d), dp(18, d), dp(14, d));
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.leftMargin = pad;
        bottomParams.rightMargin = pad;
        bottomParams.topMargin = pad;
        bottomParams.bottomMargin = pad;
        root.addView(bottom, bottomParams);

        bottom.addView(mapRow(activity, d, dark, "站点", trade.station));
        bottom.addView(mapRow(activity, d, dark, "时间", trade.time));
        bottom.addView(mapRow(activity, d, dark, "金额", trade.amount));

        final int mode = plan == null ? TradeMapRenderer.MODE_POINT : plan.mode;
        final List<String> labels = plan == null || plan.labels.isEmpty()
                ? singleList(trade.station) : plan.labels;
        // 等布局量出真实尺寸再渲染：地图与卡片严丝合缝，也不会把图钉裁掉
        mapBox.post(new Runnable() {
            @Override
            public void run() {
                final int width = mapBox.getWidth();
                final int height = mapBox.getHeight();
                if (width <= 0 || height <= 0) {
                    return;
                }
                map.setImageBitmap(TradeMapRenderer.fallback(labels, mode,
                        width, height, mapRadius, dark));
                MAP_IO.execute(new Runnable() {
                    @Override
                    public void run() {
                        loadMapInto(activity.getApplicationContext(), trade.type, labels, labels,
                                mode, width, height, mapRadius, dark, map, "地图页", null);
                    }
                });
            }
        });

        dialog.setContentView(root);
        applyFullScreen(dialog, root, !dark);
        dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface dlg, int code, KeyEvent event) {
                if (code == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    dlg.dismiss();
                    return true;
                }
                return false;
            }
        });
        try {
            dialog.show();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 地图加载
    // ======================================================================

    private static void loadMapInto(Context appContext, String tradeType, List<String> keywords,
                                    List<String> labels, int mode, int width, int height,
                                    float corner, boolean dark, ImageView target, String where,
                                    OnPlacesResolved callback) {
        if (appContext == null || target == null) {
            return;
        }
        final WeakReference<ImageView> ref = new WeakReference<>(target);
        List<double[]> points = new ArrayList<>();
        final List<TradePlaceResolver.Place> places = new ArrayList<>();
        if (keywords != null) {
            for (String keyword : keywords) {
                TradePlaceResolver.Place place = cachedPlace(keyword);
                if (place == null) {
                    place = TradePlaceResolver.locate(appContext, keyword, tradeType);
                    if (place != null) {
                        putCachedPlace(keyword, place);
                    }
                }
                places.add(place);
                if (place != null) {
                    points.add(new double[]{place.lat, place.lon});
                }
            }
        }
        if (callback != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    callback.onResolved(places);
                }
            });
        }
        if (points.isEmpty()) {
            android.util.Log.i("MiPayLiquidGlass/TradeSheet",
                    where + " 地图走降级（未解析到坐标）：" + labels);
            return;
        }
        // 只解析到一半时退化为单点，避免画出「一端在图外」的怪图
        int mode2 = (mode == TradeMapRenderer.MODE_ROUTE && points.size() < 2)
                ? TradeMapRenderer.MODE_POINT : mode;
        List<String> labels2 = mode2 == mode ? labels : labels.subList(0, 1);
        final Bitmap bitmap = TradeMapRenderer.render(points, labels2, mode2,
                width, height, corner, dark);
        if (bitmap == null) {
            android.util.Log.i("MiPayLiquidGlass/TradeSheet", where + " 地图瓦片不可用，保留降级图");
            return;
        }
        android.util.Log.i("MiPayLiquidGlass/TradeSheet", where + " 地图已生成：" + labels
                + " " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " 模式=" + (mode2 == TradeMapRenderer.MODE_ROUTE ? "哪到哪" : "单点"));
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                ImageView view = ref.get();
                if (view != null) {
                    view.setImageBitmap(bitmap);
                }
            }
        });
    }

    private static TradePlaceResolver.Place cachedPlace(String keyword) {
        if (keyword == null) {
            return null;
        }
        synchronized (POINT_CACHE) {
            return POINT_CACHE.get(keyword);
        }
    }

    private static void putCachedPlace(String keyword, TradePlaceResolver.Place place) {
        if (keyword == null || place == null) {
            return;
        }
        synchronized (POINT_CACHE) {
            POINT_CACHE.put(keyword, place);
        }
    }

    // ======================================================================
    // 地图规划（地铁哪到哪 / 不完整单点 / 公交交易地点）
    // ======================================================================

    private static Plan planFor(Trade trade, Trip trip) {
        try {
            String type = trade.type == null ? "" : trade.type.trim();
            // 北京互通卡公交记录的首行可能只是线路名，真正的站点在凭证字段里，
            // 所以统一走 stationOf 取「最像站点」的那串文字。
            String raw = stationOf(trade);
            if (raw == null) {
                raw = "";
            }
            boolean bus = type.contains("公交") || type.contains("巴士")
                    || type.contains("轮渡");
            boolean zero = isZeroAmount(trade.amount);

            if (bus) {
                Plan plan = new Plan();
                String up = trip == null ? null : clean(trip.boardingStation);
                String down = trip == null ? null : clean(trip.alightingStation);
                if (up == null && down == null) {
                    // 单条记录里可能直接写着「上车站-下车站」，先用分隔符拆一次；
                    // 拆不开再按 0.00 元判断它是上车还是下车记录。
                    String[] parts = splitPlace(raw);
                    if (parts.length >= 2) {
                        up = parts[0];
                        down = parts[1];
                    } else if (parts.length == 1) {
                        if (zero) {
                            up = parts[0];
                        } else {
                            down = parts[0];
                        }
                    }
                }
                if (up != null && down != null) {
                    plan.mode = TradeMapRenderer.MODE_ROUTE;
                    plan.labels.add(up);
                    plan.labels.add(down);
                    plan.keywords.add(up);
                    plan.keywords.add(down);
                    plan.reason = "公交行程：" + up + " → " + down;
                    return plan;
                }
                String only = up != null ? up : down;
                if (only == null) {
                    // 站点文字确实拿不到（凭证字段也没有）时，不编造地点。
                    return null;
                }
                plan.mode = TradeMapRenderer.MODE_POINT;
                plan.labels.add(only);
                plan.keywords.add(only);
                plan.reason = zero ? "公交上车记录（0.00 元，下车扣费）" : "公交下车扣费记录";
                return plan;
            }

            if (raw.isEmpty()) {
                return null;
            }
            String[] parts = splitPlace(raw);
            Plan plan = new Plan();
            // 「行程中」这类占位终点已在 splitPlace/clean 里被剔除；这里再记一笔，
            // 便于把副标题写成「行程进行中」而不是泛泛的「不完整交易」。
            boolean pending = containsPendingTerm(raw);
            if (parts.length >= 2) {
                plan.mode = TradeMapRenderer.MODE_ROUTE;
                plan.labels.add(parts[0]);
                plan.labels.add(parts[1]);
                plan.keywords.add(parts[0]);
                plan.keywords.add(parts[1]);
                plan.reason = "地铁行程：" + parts[0] + " → " + parts[1];
            } else {
                // 只剩一段：此时它是唯一的真实交易地点（例：丰台南路-行程中 → 丰台南路）。
                String one = parts.length >= 1 ? parts[0] : null;
                if (one == null) {
                    return null;
                }
                plan.mode = TradeMapRenderer.MODE_POINT;
                plan.labels.add(one);
                plan.keywords.add(one);
                if (pending) {
                    plan.reason = "行程进行中：仅记录到上车站点";
                } else if (zero) {
                    plan.reason = "进站记录（0.00 元，出站扣费）";
                } else {
                    plan.reason = "单点交易记录";
                }
            }
            return plan;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstPart(String raw) {
        String[] parts = splitPlace(raw);
        return parts.length >= 1 ? parts[0] : null;
    }

    /** 把「草桥-首经贸」拆成起终点；单段（不完整交易）返回 1 个元素。 */
    private static String[] splitPlace(String raw) {
        String text = raw == null ? "" : raw.trim();
        for (String separator : SEPARATORS) {
            int index = text.indexOf(separator);
            if (index <= 0 || index >= text.length() - separator.length()) {
                continue;
            }
            String first = clean(text.substring(0, index));
            String second = clean(text.substring(index + separator.length()));
            List<String> valid = new ArrayList<>();
            if (first != null) {
                valid.add(first);
            }
            if (second != null) {
                valid.add(second);
            }
            if (valid.size() == 2) {
                return new String[]{valid.get(0), valid.get(1)};
            }
            if (valid.size() == 1) {
                return new String[]{valid.get(0)};
            }
        }
        String one = clean(text);
        return one == null ? new String[0] : new String[]{one};
    }

    /** 去掉空白与「未知」这类占位词；不合格返回 null。 */
    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        while (value.startsWith("-") || value.startsWith("—") || value.startsWith("－")) {
            value = value.substring(1).trim();
        }
        while (value.endsWith("-") || value.endsWith("—") || value.endsWith("－")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.length() < 2) {
            return null;
        }
        for (String placeholder : PLACEHOLDERS) {
            if (value.equalsIgnoreCase(placeholder)) {
                return null;
            }
        }
        // 「行程中」「待扣费」这类占位终点不是真实地点，必须当作缺失处理。
        for (String pending : PENDING_TERMS) {
            if (value.equalsIgnoreCase(pending)) {
                return null;
            }
        }
        return value;
    }

    /** 交易地点里是否夹带「行程未结束」占位词（如「行程中」「待扣费」）。 */
    private static boolean containsPendingTerm(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        for (String term : PENDING_TERMS) {
            if (raw.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String mapTitle(Plan plan, Trade trade) {
        if (plan != null && !plan.labels.isEmpty()) {
            return join(plan.labels);
        }
        return trade.station == null ? "交易地图" : trade.station;
    }

    // ======================================================================
    // 小组件
    // ======================================================================

    /**
     * 全屏页面化，并适配状态栏与导航栏。
     *
     * <p>澎湃 OS 的详情页是「内容铺到系统栏下面、再按 insets 留白」：这里把窗口设成
     * edge-to-edge、状态栏与导航栏都透明，底色由页面自己铺；再按 {@code systemBars}
     * 的 insets 给根布局补上下留白，并按页面明暗切换系统栏图标的明暗
     * （浅色页面用深色图标）。这样状态栏与手势条叠在页面底色上，不会出现割裂的色块。</p>
     */
    private static void applyFullScreen(Dialog dialog, final View root, final boolean light) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(0x00000000));
        window.setDimAmount(0f);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(0x00000000);
        window.setNavigationBarColor(0x00000000);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.TOP;
        window.setAttributes(params);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);

        final View decor = window.getDecorView();
        decor.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(
                    View view, android.view.WindowInsets insets) {
                int top;
                int bottom;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(
                            android.view.WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                if (root.getPaddingTop() != top || root.getPaddingBottom() != bottom) {
                    root.setPadding(root.getPaddingLeft(), top,
                            root.getPaddingRight(), bottom);
                }
                return insets;
            }
        });
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller =
                    decor.getWindowInsetsController();
            if (controller != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            int flags = decor.getSystemUiVisibility();
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
        decor.requestApplyInsets();
    }

    /**
     * 让容器按圆角裁剪内容。
     *
     * <p>圆角已经烘进地图位图（见 {@link TradeMapRenderer}），这里再裁一次是为了
     * 「自动适配」：详情页 40% 屏高的地图、地图页整块的地图，换成任何圆角、任何尺寸，
     * 都不会在四角漏出直角。</p>
     */
    private static void clipRounded(View view, final float radiusPx) {
        try {
            view.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
                }
            });
            view.setClipToOutline(true);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static LinearLayout card(Activity activity, boolean dark, float d) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(dark ? DARK_CARD : LIGHT_CARD, dp(20, d)));
        int padH = dp(18, d);
        int padV = dp(16, d);
        box.setPadding(padH, padV, padH, padV);
        return box;
    }

    /** 一行「左标签 + 右值」，并把右值 TextView 交出去，便于异步填充（地点解析完再回填）。 */
    private static final class InfoRow {
        final View root;
        final TextView value;

        InfoRow(View root, TextView value) {
            this.root = root;
            this.value = value;
        }
    }

    private static InfoRow addRow(Activity activity, float d, boolean dark,
                                  String label, String value, boolean separator) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11, d), 0, dp(11, d));

        TextView key = new TextView(activity);
        key.setText(label);
        key.setTextSize(15f);
        key.setTextColor(LABEL);
        row.addView(key, wrapWrap());

        TextView val = new TextView(activity);
        val.setText(value == null || value.isEmpty() ? "—" : value);
        val.setTextSize(15f);
        val.setTextColor(dark ? DARK_VALUE : LIGHT_VALUE);
        val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        val.setGravity(Gravity.END);
        LinearLayout.LayoutParams valueParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        valueParams.leftMargin = dp(12, d);
        row.addView(val, valueParams);

        if (!separator) {
            return new InfoRow(row, val);
        }
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row, matchWrap());
        View line = new View(activity);
        line.setBackgroundColor(dark ? DARK_SEPARATOR : LIGHT_SEPARATOR);
        wrapper.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f, d))));
        return new InfoRow(wrapper, val);
    }

    /** 分组小标题（澎湃 OS 的分组列表就是这种小号灰字）。 */
    private static TextView sectionTitle(Activity activity, float d, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(13f);
        view.setTextColor(LABEL);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(dp(4, d), dp(20, d), 0, dp(8, d));
        return view;
    }

    /** 站点信息块：官方站名 + 「区域 · 途经线路」。 */
    private static View stationBlock(Activity activity, float d, boolean dark,
                                     String keyword, TextView[] holder) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(keyword);
        title.setTextSize(15f);
        title.setTextColor(dark ? DARK_VALUE : LIGHT_VALUE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = new TextView(activity);
        sub.setText(LOADING);
        sub.setTextSize(12f);
        sub.setTextColor(LABEL);
        box.addView(title, wrapWrap());
        LinearLayout.LayoutParams subParams = wrapWrap();
        subParams.topMargin = dp(3, d);
        box.addView(sub, subParams);
        holder[0] = title;
        holder[1] = sub;
        return box;
    }

    /** 地图数据来源署名（用高德瓦片，理当标注）。 */
    private static TextView attribution(Activity activity, float d) {
        TextView view = new TextView(activity);
        view.setText("地图数据 © 高德软件");
        view.setTextSize(11f);
        view.setTextColor(LABEL);
        view.setPadding(dp(4, d), dp(14, d), 0, dp(4, d));
        return view;
    }

    private static LinearLayout.LayoutParams withBottomMargin(float d, int value) {
        LinearLayout.LayoutParams params = wrapWrap();
        params.bottomMargin = dp(value, d);
        return params;
    }

    /** 交易状态：由金额正负推导（应用不提供独立状态字段）。 */
    private static String statusText(String amount) {
        if (isZeroAmount(amount)) {
            return "待扣费";
        }
        String digits = amount == null ? "" : amount.replaceAll("[^0-9.\\-]", "");
        try {
            return Double.parseDouble(digits) < 0 ? "扣费成功" : "入账成功";
        } catch (Throwable ignored) {
            return "交易成功";
        }
    }

    /**
     * 交易通道。
     *
     * <p>优先用应用交易数据里的 {@code trafficType}（反编译确认存在，取值形如
     * {@code TU} / {@code CU}；界面资源里没有对应文案，所以应用自己不显示它）。
     * 取不到时按可确证的事实回退：记录来自本机虚拟交通卡 → 「手机 NFC 刷卡」。</p>
     */
    private static String channelText(Trade trade) {
        // 交易通道 = 卡的应用通道（TU / CU），这是唯一正确的来源；
        // 卡内交易记录里的 ordinalTrafficType（本机为 01）只是通道序号，不是 TU/CU。
        if (cardChannel != null && !cardChannel.isEmpty()) {
            String type = trade.extras == null ? null : trade.extras.get("交通类型");
            return type == null || type.isEmpty() ? cardChannel : cardChannel + " · " + type;
        }
        if (trade.channel != null && !trade.channel.isEmpty()) {
            String type = trade.extras == null ? null : trade.extras.get("交通类型");
            if (type != null && !type.equals(trade.channel)) {
                return trade.channel + " · " + type;
            }
            return trade.channel;
        }
        String type = trade.type == null ? "" : trade.type;
        if (type.contains("码") || type.contains("扫码")) {
            return "乘车二维码";
        }
        boolean consumption = trade.amount != null
                && (trade.amount.startsWith("-") || isZeroAmount(trade.amount));
        return consumption ? "手机 NFC 刷卡" : "—";
    }

    /** 地点解析完成后的回调（主线程）。 */
    interface OnPlacesResolved {
        void onResolved(List<TradePlaceResolver.Place> places);
    }

    /** 把高德解析出的官方站名 / 区域 / 线路回填到页面上。 */
    private static void fillPlaceInfo(List<TradePlaceResolver.Place> places, List<String> keywords,
                                      List<TextView[]> stationViews, InfoRow linesRow,
                                      InfoRow areaRow) {
        try {
            StringBuilder lines = new StringBuilder();
            StringBuilder areas = new StringBuilder();
            for (int i = 0; i < keywords.size() && i < stationViews.size(); i++) {
                TextView[] holder = stationViews.get(i);
                TradePlaceResolver.Place place =
                        places != null && i < places.size() ? places.get(i) : null;
                if (place == null) {
                    holder[1].setText("未能定位");
                    continue;
                }
                if (place.name != null && !place.name.isEmpty()) {
                    holder[0].setText(place.name);
                }
                String area = joinNonEmpty(place.city, place.district);
                String line = normalizeLines(place.lines);
                StringBuilder sub = new StringBuilder();
                if (!area.isEmpty()) {
                    sub.append(area);
                }
                if (!line.isEmpty()) {
                    if (sub.length() > 0) {
                        sub.append(" · ");
                    }
                    sub.append(line);
                }
                holder[1].setText(sub.length() == 0 ? "已定位" : sub.toString());
                appendUniqueTokens(lines, line);
                appendUniqueTokens(areas, area);
            }
            linesRow.value.setText(lines.length() == 0 ? "—" : lines.toString());
            areaRow.value.setText(areas.length() == 0 ? "—" : areas.toString());
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static String normalizeLines(String snippet) {
        if (snippet == null || snippet.trim().isEmpty()) {
            return "";
        }
        return snippet.replace(';', ' ').replace('；', ' ').replaceAll("\\s+", " ").trim()
                .replace(' ', '／');
    }

    private static String joinNonEmpty(String a, String b) {
        StringBuilder builder = new StringBuilder();
        if (a != null && !a.isEmpty()) {
            builder.append(a);
        }
        if (b != null && !b.isEmpty()) {
            builder.append(b);
        }
        return builder.toString();
    }

    /** 以「 ／ 」为分隔做去重合并。 */
    private static void appendUniqueTokens(StringBuilder target, String delimited) {
        if (delimited == null || delimited.isEmpty()) {
            return;
        }
        for (String token : delimited.split("／")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            boolean exists = false;
            for (String existing : target.toString().split("／")) {
                if (existing.trim().equals(value)) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                continue;
            }
            if (target.length() > 0) {
                target.append("／");
            }
            target.append(value);
        }
    }

    private static View mapRow(Activity activity, float d, boolean dark,
                               String label, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4, d), 0, dp(4, d));
        TextView key = new TextView(activity);
        key.setText(label);
        key.setTextSize(13f);
        key.setTextColor(LABEL);
        row.addView(key, wrapWrap());
        TextView val = new TextView(activity);
        val.setText(value == null || value.isEmpty() ? "—" : value);
        val.setTextSize(13f);
        val.setTextColor(dark ? DARK_VALUE : LIGHT_VALUE);
        val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        val.setGravity(Gravity.END);
        LinearLayout.LayoutParams valueParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        valueParams.leftMargin = dp(10, d);
        row.addView(val, valueParams);
        return row;
    }

    /** 地图上的小药丸（站名 / 提示 / 跳转）。 */
    private static TextView chip(Activity activity, float d, boolean dark, String text,
                                 boolean accent) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(12f);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setTextColor(accent ? 0xFFFFFFFF : onCard(dark));
        view.setBackground(rounded(accent ? accent(dark) : chipAccentColor(dark), dp(13, d)));
        view.setPadding(dp(10, d), dp(5, d), dp(10, d), dp(5, d));
        return view;
    }

    /** Miuix 扁平返回箭头：无底，只有箭头（与大标题折叠后的顶栏配套）。 */
    private static View plainBackButton(Activity activity, float d, boolean dark,
                                        View.OnClickListener listener) {
        FrameLayout box = new FrameLayout(activity);
        ChevronView chevron = new ChevronView(activity,
                dark ? DARK_VALUE : LIGHT_VALUE, Math.max(2f, 2.2f * d));
        box.addView(chevron, new FrameLayout.LayoutParams(dp(22, d), dp(22, d), Gravity.CENTER));
        box.setOnClickListener(listener);
        return box;
    }

    /** 地图缩放按钮：液态玻璃圆底 + 符号。 */
    private static TextView zoomButton(Activity activity, float d, Config cfg, boolean dark,
                                       String symbol, final ZoomableImageView target,
                                       final float factor) {
        final TextView view = new TextView(activity);
        view.setText(symbol);
        view.setTextSize(18f);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(onCard(dark));
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int size = dp(36, d);
        view.setBackground(liquidGlassCircle(cfg, size * 0.5f, d, dark));
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                target.zoomBy(factor);
            }
        });
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return view;
    }

    /** 圆形液态玻璃底（返回键 / 缩放键共用）。 */
    private static android.graphics.drawable.Drawable liquidGlassCircle(
            Config cfg, float radiusPx, float density, boolean dark) {
        try {
            if (cfg != null) {
                return new LiquidGlassDrawable(cfg, radiusPx, density, false, dark);
            }
        } catch (Throwable ignored) {
            // 退化成半透明圆
        }
        return rounded(dark ? 0x66000000 : 0x66FFFFFF, radiusPx);
    }

    private static FrameLayout.LayoutParams chipParams(float d, int gravity,
                                                       int marginH, int marginV) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = gravity;
        params.leftMargin = dp(marginH, d);
        params.rightMargin = dp(marginH, d);
        params.topMargin = dp(marginV, d);
        params.bottomMargin = dp(marginV, d);
        return params;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(float value, float density) {
        return Math.round(value * density);
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" → ");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static List<String> singleList(String value) {
        List<String> list = new ArrayList<>();
        if (value != null && !value.isEmpty()) {
            list.add(value);
        }
        return list;
    }

    /** 「-3.00元」→ ["-3.00", "元"]。 */
    private static String[] splitAmount(String amount) {
        if (amount == null || amount.isEmpty()) {
            return new String[]{"—", null};
        }
        int index = amount.length();
        while (index > 0) {
            char c = amount.charAt(index - 1);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == ',') {
                break;
            }
            index--;
        }
        String number = amount.substring(0, index).trim();
        String unit = amount.substring(index).trim();
        if (number.isEmpty()) {
            return new String[]{amount, null};
        }
        return new String[]{number, unit.isEmpty() ? null : unit};
    }

    private static String textOf(View root, String entryName) {
        View view = findByEntryName(root, entryName);
        if (!(view instanceof TextView)) {
            return null;
        }
        CharSequence text = ((TextView) view).getText();
        return text == null ? null : text.toString().trim();
    }

    private static View findByEntryName(View root, String entryName) {
        if (root == null) {
            return null;
        }
        if (entryName.equals(GlassChrome.entryNameOf(root))) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByEntryName(group.getChildAt(i), entryName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Activity activityOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    /** iOS 风格返回箭头。 */
    private static final class ChevronView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChevronView(Context context, int color, float stroke) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
            paint.setStrokeWidth(stroke);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            Path path = new Path();
            path.moveTo(w * 0.60f, h * 0.26f);
            path.lineTo(w * 0.36f, h * 0.50f);
            path.lineTo(w * 0.60f, h * 0.74f);
            canvas.drawPath(path, paint);
        }
    }

    /**
     * 可缩放的地图视图：双指捏合缩放、放大后单指拖动平移、双击放大/复位。
     *
     * <p>地图本体是位图（高德瓦片拼出来的），所以这里用 {@link android.graphics.Matrix}
     * 做缩放与平移 —— 即时响应、离线也能用；位图按容器 1.4 倍渲染，
     * 放到 2 倍左右仍然清晰。</p>
     *
     * <p>手势与页面滚动的分工：地图区域先拿住触摸（保证捏合能起来），
     * 但在「未缩放 + 单指竖向滑动」时把触摸交还给外层 ScrollView，
     * 于是拖地图以外的地方照常滚页面、拖地图本身竖向也能滚页面，
     * 只有两指捏合/已放大后的拖动才落到地图上。</p>
     */
    private static final class ZoomableImageView extends ImageView {
        private static final float MIN_SCALE = 1f;
        private static final float MAX_SCALE = 4f;

        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final android.view.ScaleGestureDetector scaleDetector;
        private final android.view.GestureDetector gestureDetector;
        private float baseScale = 1f;
        private float scale = 1f;
        private float downX;
        private float downY;

        ZoomableImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new android.view.ScaleGestureDetector(context,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(android.view.ScaleGestureDetector detector) {
                            zoomBy(detector.getScaleFactor(), detector.getFocusX(),
                                    detector.getFocusY());
                            return true;
                        }

                        @Override
                        public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                            disallowParent(true);
                            return true;
                        }

                        @Override
                        public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                            disallowParent(scale > MIN_SCALE + 0.01f);
                        }
                    });
            gestureDetector = new android.view.GestureDetector(context,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(android.view.MotionEvent event) {
                            if (scale > MIN_SCALE + 0.01f) {
                                reset();
                            } else {
                                zoomBy(2f, event.getX(), event.getY());
                            }
                            return true;
                        }

                        @Override
                        public boolean onScroll(android.view.MotionEvent first,
                                                android.view.MotionEvent second,
                                                float distanceX, float distanceY) {
                            if (scale <= MIN_SCALE + 0.01f) {
                                return false;
                            }
                            pan(-distanceX, -distanceY);
                            return true;
                        }
                    });
        }

        @Override
        public void setImageBitmap(Bitmap bitmap) {
            super.setImageBitmap(bitmap);
            post(new Runnable() {
                @Override
                public void run() {
                    reset();
                }
            });
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            reset();
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            boolean scaled = scale > MIN_SCALE + 0.01f;
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    disallowParent(true);
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!scaled && !scaleDetector.isInProgress()) {
                        float dx = Math.abs(event.getX() - downX);
                        float dy = Math.abs(event.getY() - downY);
                        if (dy > dp(getContext(), 6) && dy > dx) {
                            // 未缩放的单指竖向滑动 → 交还给页面滚动
                            disallowParent(false);
                            return false;
                        }
                    }
                    break;
                default:
                    break;
            }
            return scaled || scaleDetector.isInProgress();
        }

        /** 按倍数缩放（按钮用，焦点取视图中心）。 */
        void zoomBy(float factor) {
            zoomBy(factor, getWidth() * 0.5f, getHeight() * 0.5f);
        }

        void zoomBy(float factor, float focusX, float focusY) {
            if (getDrawable() == null || getWidth() == 0) {
                return;
            }
            float target = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
            float applied = target / scale;
            if (Math.abs(applied - 1f) < 0.001f) {
                return;
            }
            scale = target;
            matrix.postScale(applied, applied, focusX, focusY);
            clamp();
            setImageMatrix(matrix);
            disallowParent(scale > MIN_SCALE + 0.01f);
        }

        /** 复位到铺满容器的初始状态。 */
        void reset() {
            android.graphics.drawable.Drawable drawable = getDrawable();
            if (drawable == null || getWidth() == 0 || getHeight() == 0) {
                return;
            }
            int drawableWidth = Math.max(1, drawable.getIntrinsicWidth());
            int drawableHeight = Math.max(1, drawable.getIntrinsicHeight());
            baseScale = Math.max((float) getWidth() / drawableWidth,
                    (float) getHeight() / drawableHeight);
            scale = MIN_SCALE;
            matrix.reset();
            matrix.postScale(baseScale, baseScale);
            matrix.postTranslate(
                    (getWidth() - drawableWidth * baseScale) * 0.5f,
                    (getHeight() - drawableHeight * baseScale) * 0.5f);
            setImageMatrix(matrix);
            disallowParent(false);
        }

        private void pan(float dx, float dy) {
            matrix.postTranslate(dx, dy);
            clamp();
            setImageMatrix(matrix);
        }

        /** 把画面夹在容器内：不留空白边。 */
        private void clamp() {
            android.graphics.drawable.Drawable drawable = getDrawable();
            if (drawable == null) {
                return;
            }
            android.graphics.RectF rect = new android.graphics.RectF(0, 0,
                    drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            matrix.mapRect(rect);
            float dx = 0;
            float dy = 0;
            if (rect.width() <= getWidth()) {
                dx = (getWidth() - rect.width()) * 0.5f - rect.left;
            } else if (rect.left > 0) {
                dx = -rect.left;
            } else if (rect.right < getWidth()) {
                dx = getWidth() - rect.right;
            }
            if (rect.height() <= getHeight()) {
                dy = (getHeight() - rect.height()) * 0.5f - rect.top;
            } else if (rect.top > 0) {
                dy = -rect.top;
            } else if (rect.bottom < getHeight()) {
                dy = getHeight() - rect.bottom;
            }
            if (dx != 0 || dy != 0) {
                matrix.postTranslate(dx, dy);
            }
        }

        private void disallowParent(boolean disallow) {
            android.view.ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(disallow);
            }
        }

        private static int dp(Context context, int value) {
            float density = context.getResources().getDisplayMetrics().density;
            return Math.round(value * (density <= 0f ? 3f : density));
        }
    }
}
