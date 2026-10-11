package com.liquidglass.mipay.glass;

import android.app.Notification;
import android.os.Bundle;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 刷卡信息的统一解析（1.5.5）。
 *
 * <h3>数据究竟在哪里</h3>
 *
 * <p>实机日志（v1.5.4）确认：焦点通知的 {@code android.title} / {@code android.text}
 * <b>全为空</b>，{@code miui.focus.rv} 里也没有任何 {@code setText} 动作
 * （{@code rvTexts=[]}）。真正的内容只在这段 JSON 里 ——
 * {@code miui.focus.param.custom}：</p>
 *
 * <pre>
 * {"isShowNotification":false,"islandFirstFloat":true,"business":"tsmclient",
 *  "param_island":{"islandProperty":0,"islandTimeout":3,"expandedTime":3,
 *  "bigIslandArea":{"imageTextInfoLeft":{"type":1,
 *      "picInfo":{"type":4,"pic":"miui.focus.pic_start"}},
 *   "imageTextInfoRight":{"type":2,"textInfo":{"title":"刷卡失败"}}}}}
 * </pre>
 *
 * <h3>v1.5.4 为什么会解析出「金额=刷卡失败、余额=tsmclient」</h3>
 *
 * <p>旧实现用正则把所有 {@code "key":"value"} 对一股脑收下来，
 * 再按字段名表兜底排序，于是 {@code "business":"tsmclient"} 这种
 * <b>业务标识</b>被当成了展示文案。v1.5.5 改为两条硬约束：</p>
 *
 * <ol>
 *     <li><b>正规 JSON 解析</b>：优先用 {@link JSONObject} 递归遍历，
 *     只读 {@code textInfo} / {@code title} / {@code content} 这类承载文案的键；</li>
 *     <li><b>文案过滤</b>：任何候选值必须「含中日韩文字」或「含货币符号」才允许展示，
 *     纯标识符（如 {@code tsmclient}、{@code miui.focus.pic_start}）一律丢弃。</li>
 * </ol>
 *
 * <p>本类被两个进程共用：小米智能卡进程（{@link IslandStyler}）与
 * SystemUI 进程（{@link IslandOverlayController}）。</p>
 */
public final class IslandInfo {

    /** 失败态关键词：命中即判定为交易失败。 */
    private static final String[] FAILURE_KEYWORDS = {
            "失败", "錯誤", "错误", "异常", "異常", "超时", "超時", "拒绝", "拒絕",
            "余额不足", "餘額不足", "未完成", "中断", "中斷", "取消", "已退回",
            "fail", "error", "declined", "insufficient", "timeout"
    };

    /** 金额：[¥￥]128.50 或 128.50元 */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "[¥￥]\\s*([0-9]+(?:\\.[0-9]+)?)|([0-9]+(?:\\.[0-9]+)?)\\s*元");
    /** 余额：余额 / 余额： / 余额 ¥ 后面的数字 */
    private static final Pattern BALANCE_PATTERN = Pattern.compile(
            "余\\s*额[^0-9¥￥]{0,6}[¥￥]?\\s*([0-9]+(?:\\.[0-9]+)?)");

    /**
     * 岛参数 JSON 所在的 extras key。
     *
     * <p>实机日志确认内容只在 {@code miui.focus.param.custom}；
     * {@code miui.focus.param} 作为兼容备选。</p>
     */
    private static final String[] PARAM_JSON_KEYS = {
            "miui.focus.param.custom",
            "miui.focus.param"
    };

    /**
     * 岛 JSON 里承载文案的字段名。
     *
     * <p>官方模板里文字统一放在 {@code textInfo} 对象里，键名以
     * {@code title} / {@code content} 为主；其余为不同样式模板的变体。</p>
     */
    private static final String[] TEXT_KEYS = {
            "title", "content", "subtitle", "subTitle",
            "frontTitle", "shareContent", "shareTitle", "aodTitle", "ticker", "text"
    };

    /** 从 JSON 里抽取 "key":"value" 形式的字符串对（正规解析失败时的兜底）。 */
    private static final Pattern JSON_PAIR = Pattern.compile(
            "\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*\"([^\"]*)\"");

    /** 纯标识符：只由字母数字与 . _ : / - 组成 —— 一定是内部字段值，不是文案。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:/\\-]+");
    /** 含中日韩文字。 */
    private static final Pattern CJK = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");
    /** 含货币符号。 */
    private static final Pattern CURRENCY = Pattern.compile("[¥￥$€£]|元|圆|円|원");

    public final boolean success;
    /** 岛上的主文案：优先金额，其次「刷卡失败」「交易成功」一类状态文案。 */
    public final String amountText;
    public final String balanceText;
    public final String rawText;

    private IslandInfo(boolean success, String amountText, String balanceText, String rawText) {
        this.success = success;
        this.amountText = amountText;
        this.balanceText = balanceText;
        this.rawText = rawText;
    }

    /** 从一条通知里解析刷卡信息。任何异常都返回一个「成功 + 空文案」的安全结果。 */
    public static IslandInfo from(Notification notification) {
        try {
            Bundle extras = notification == null ? null : notification.extras;
            String title = text(extras, Notification.EXTRA_TITLE);
            String body = text(extras, Notification.EXTRA_TEXT);
            String sub = text(extras, Notification.EXTRA_SUB_TEXT);
            String rvTexts = rvTextActions(extras == null ? null
                    : asRemoteViews(extras.get(IslandStyler.KEY_CUSTOM_RV)));

            // 岛内容在 param JSON 里，把原文一并纳入解析
            StringBuilder json = new StringBuilder();
            if (extras != null) {
                for (String key : PARAM_JSON_KEYS) {
                    Object value = extras.get(key);
                    if (value != null) {
                        json.append(' ').append(value);
                    }
                }
            }
            String jsonRaw = json.toString().trim();

            // 1) 从 JSON 里按语义取文案（含过滤，杜绝 business / pic 这类内部值）
            List<String> islandTexts = jsonTexts(jsonRaw);

            // 2) 汇总所有「人类可读」的来源
            StringBuilder human = new StringBuilder();
            human.append(title).append(' ').append(body).append(' ').append(sub)
                    .append(' ').append(rvTexts);
            for (String item : islandTexts) {
                human.append(' ').append(item);
            }
            String all = human.toString();

            // 3) 成功 / 失败
            boolean success = true;
            String lower = all.toLowerCase();
            for (String keyword : FAILURE_KEYWORDS) {
                if (lower.contains(keyword.toLowerCase())) {
                    success = false;
                    break;
                }
            }

            // 4) 金额与余额
            String amount = null;
            Matcher amountMatcher = AMOUNT_PATTERN.matcher(all);
            if (amountMatcher.find()) {
                String number = amountMatcher.group(1) != null
                        ? amountMatcher.group(1) : amountMatcher.group(2);
                if (number != null) {
                    amount = "¥" + number;
                }
            }
            String balance = null;
            Matcher balanceMatcher = BALANCE_PATTERN.matcher(all);
            if (balanceMatcher.find()) {
                balance = "余额 ¥" + balanceMatcher.group(1);
            }

            // 5) 主文案：金额 > 岛 JSON 文案 > 通知正文 > 标题
            String primary = null;
            if (amount != null) {
                primary = amount;
            } else if (!islandTexts.isEmpty()) {
                primary = islandTexts.get(0);
            } else {
                primary = firstNonEmpty(body, firstNonEmpty(title, rvTexts));
            }

            return new IslandInfo(success, nullToEmpty(primary),
                    balance == null ? "" : balance, all.trim());
        } catch (Throwable ignored) {
            return new IslandInfo(true, "", "", "");
        }
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ======================================================================
    // 岛 JSON 文案提取
    // ======================================================================

    /**
     * 从岛 JSON 里取出人类可读的文案，按「先出现的优先」排序。
     *
     * <p>优先走 {@link JSONObject} 正规解析；厂商 JSON 不合法时退回正则，
     * 但两条路径都经过 {@link #isDisplayText} 过滤，因此不会再把
     * {@code business} / {@code pic} 这类内部标识当成金额与余额。</p>
     */
    static List<String> jsonTexts(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return result;
        }
        try {
            Object root = new JSONObject(json);
            collectJson(root, result);
        } catch (Throwable ignored) {
            // 不是合法 JSON：走正则兜底
        }
        if (result.isEmpty()) {
            Matcher matcher = JSON_PAIR.matcher(json);
            while (matcher.find()) {
                addText(result, matcher.group(2));
            }
        }
        return result;
    }

    /** 递归遍历 JSON，收集所有承载文案的字符串值。 */
    private static void collectJson(Object node, List<String> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            // 先按字段表取本层的文案键
            for (String key : TEXT_KEYS) {
                Object value = object.opt(key);
                if (value instanceof String) {
                    addText(out, (String) value);
                }
            }
            // 再递归子节点（textInfo / bigIslandArea 等都在里面）
            try {
                java.util.Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    collectJson(object.opt(keys.next()), out);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectJson(array.opt(i), out);
            }
        }
    }

    private static void addText(List<String> out, String value) {
        if (!isDisplayText(value) || out.contains(value)) {
            return;
        }
        out.add(value);
    }

    /**
     * 判断一个 JSON 字符串值是不是「可以展示给用户的文案」。
     *
     * <p>这是 v1.5.5 新增的关键过滤：只有含中日韩文字或货币符号的值才可能是
     * 金额 / 状态 / 余额；纯标识符（{@code tsmclient}、{@code miui.focus.pic_start}、
     * {@code imageTextInfoRight} …）全部丢弃。</p>
     */
    static boolean isDisplayText(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        if (text.length() < 2 || text.length() > 64) {
            return false;
        }
        if (IDENTIFIER.matcher(text).matches()) {
            return false;
        }
        return CJK.matcher(text).find() || CURRENCY.matcher(text).find();
    }

    // ======================================================================
    // extras / RemoteViews 读取
    // ======================================================================

    private static String text(Bundle extras, String key) {
        try {
            CharSequence value = extras == null ? null : extras.getCharSequence(key);
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static RemoteViews asRemoteViews(Object value) {
        return value instanceof RemoteViews ? (RemoteViews) value : null;
    }

    /**
     * 读出 RemoteViews 里所有 {@code setText*} 动作的文本。
     *
     * <p>实测焦点通知里这里恒为空，保留它是为了兼容其它形态的通知。</p>
     */
    public static String rvTextActions(RemoteViews rv) {
        if (rv == null) {
            return "";
        }
        try {
            Object raw = XposedHelpers.getObjectField(rv, "mActions");
            if (!(raw instanceof List)) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (Object action : (List<?>) raw) {
                try {
                    Object method = XposedHelpers.getObjectField(action, "methodName");
                    if (!(method instanceof String) || !((String) method).startsWith("setText")) {
                        continue;
                    }
                    Object value = XposedHelpers.getObjectField(action, "value");
                    if (builder.length() > 0) {
                        builder.append(' ');
                    }
                    builder.append(value);
                } catch (Throwable ignored) {
                    // 单个动作读失败不影响其它
                }
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 把 RemoteViews 描述成「包名/布局id」，用于日志。
     *
     * <p>不同 Android 版本上字段名不同（实机曾出现 {@code RemoteViews?}），
     * 因此先试隐藏方法再试字段。</p>
     */
    public static String describe(RemoteViews rv) {
        if (rv == null) {
            return "-";
        }
        String pkg = null;
        Integer layoutId = null;
        try {
            Object value = XposedHelpers.callMethod(rv, "getPackage");
            if (value instanceof String) {
                pkg = (String) value;
            }
        } catch (Throwable throwable) {
            XposedBridge.log("MiPayLiquidGlass: describe getPackage 失败 " + throwable);
        }
        try {
            Object value = XposedHelpers.callMethod(rv, "getLayoutId");
            if (value instanceof Integer) {
                layoutId = (Integer) value;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (pkg == null) {
            try {
                Object value = XposedHelpers.getObjectField(rv, "mPackage");
                if (value instanceof String) {
                    pkg = (String) value;
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        if (layoutId == null) {
            try {
                layoutId = XposedHelpers.getIntField(rv, "mLayoutId");
            } catch (Throwable ignored) {
                // ignore
            }
        }
        if (pkg == null && layoutId == null) {
            return "RemoteViews?";
        }
        return pkg + "/" + (layoutId == null ? "?" : "0x" + Integer.toHexString(layoutId));
    }
}
