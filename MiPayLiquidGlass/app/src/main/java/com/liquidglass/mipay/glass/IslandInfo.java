package com.liquidglass.mipay.glass;

import android.app.Notification;
import android.os.Bundle;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 刷卡信息的统一解析。
 *
 * <h3>数据究竟在哪里</h3>
 *
 * <p>实机日志确认：焦点通知的 {@code android.title} / {@code android.text}
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
 * <h3>为什么「刷卡成功」看不到金额与余额</h3>
 *
 * <p>官方超级岛模板把一段文案拆成 {@code frontTitle}（币种 / 前缀）、
 * {@code title}（数值）、{@code content}（后缀 / 余额）三个字段。例如一次
 * 成功刷卡在岛里很可能是：</p>
 *
 * <pre>
 * "imageTextInfoLeft":{"textInfo":{"frontTitle":"¥","title":"2.00"}}
 * "imageTextInfoRight":{"textInfo":{"frontTitle":"余额","title":"128.50"}}
 * </pre>
 *
 * <p>旧实现逐个字段独立判断：{@code "¥"} 长度不足 2 被丢，
 * {@code "2.00"} 因为既不含中日韩文字、也不含货币符号而被当成标识符丢弃，
 * 于是金额与余额双双落空。本版本做三件事：</p>
 *
 * <ol>
 *     <li><b>对象内拼接</b>：把同一个 {@code textInfo} 对象里的
 *     {@code frontTitle + title + content} 先拼成完整文案再判断，
 *     「¥」+「2.00」→「¥2.00」；</li>
 *     <li><b>金额字形放行</b>：{@link #isDisplayText} 允许「¥12.00」「12.00」
 *     「12元」这类纯金额字形，但依旧拒绝 {@code tsmclient}、
 *     {@code miui.focus.pic_start} 这类纯标识符；</li>
 *     <li><b>语义字段兜底</b>：按字段名抓 {@code amount} / {@code balance}
 *     （含中文「金额」「余额」）这类显式键，覆盖把数字单独放字段的实现；</li>
 *     <li><b>竖线分段</b>：官方成功文案是一整串
 *     「支付 %1$s元 | 余额 %2$s元」（{@code simple_card_swiping_status_content}），
 *     先按 {@code |} / {@code ｜} 拆成语义段落，再逐段识别金额与余额；</li>
 *     <li><b>状态文案隔离</b>：「刷卡失败」「刷卡成功」「已刷卡」「切卡」这类不含
 *     数字的结果文案永远不会被当成金额，只会作为岛的主文案。</li>
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

    /**
     * 纯状态文案：只描述「刷卡结果」，本身不含任何数字，永远不能被当成金额。
     *
     * <p>官方超级岛对成功 / 失败 / 切卡分别使用
     * {@code simple_card_swiping_status_success}（刷卡成功）、
     * {@code simple_card_swiping_status_failure}（刷卡失败）、
     * {@code simple_card_swiping_status_switch}（切卡）等字符串。旧实现会把
     * 「刷卡失败」当成金额（日志里 {@code amount=[刷卡失败]}），这里显式识别。</p>
     */
    private static final String[] STATUS_WORDS = {
            "刷卡失败", "刷卡成功", "已刷卡", "切卡", "交易成功", "交易失败",
            "扣费成功", "扣费失败", "支付成功", "支付失败", "请重试", "请再次刷卡"
    };

    /**
     * 官方成功文案是一整串「支付 %1$s元 | 余额 %2$s元」
     * （{@code simple_card_swiping_status_content}），需要按竖线拆成语义段落，
     * 才能把「支付 X元」和「余额 Y元」分别认出来。
     */
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("[|｜丨]");

    /** 段落里含「余额」语义，用于把该段判为余额而不是金额。 */
    private static final Pattern BALANCE_WORD = Pattern.compile(
            "余\\s*额|餘\\s*額|结余|结存|剩余|可用");

    /** 金额：¥128.50 / 128.50元（允许千分位）。 */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "[¥￥]\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)"
                    + "|([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*元");
    /** 余额：余额 / 餘額 / 余额： / 余额 ¥ 后面的数字。 */
    private static final Pattern BALANCE_PATTERN = Pattern.compile(
            "余\\s*额[^0-9¥￥]{0,6}[¥￥]?\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)");
    /** 取出字符串里的第一个数字。 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?");

    /**
     * 整串就是一个金额字形。
     *
     * <p>要求「带货币符号」或「带小数点」或「带元」，因此纯整数（例如分页号
     * {@code "16"}）不会被误判成金额。</p>
     */
    private static final Pattern MONEY_ONLY = Pattern.compile(
            "(?:[¥￥]\\s*[0-9]{1,6}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"
                    + "|(?:[0-9]{1,6}(?:,[0-9]{3})*\\.[0-9]{1,2})"
                    + "|(?:[0-9]{1,6}(?:,[0-9]{3})*\\s*元)");

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

    /** 同一个对象里被拆开的文案字段，按语义顺序拼接。 */
    private static final String[] JOIN_KEYS = {"frontTitle", "title", "content"};

    /** 金额语义键（小写包含匹配）。 */
    private static final String[] AMOUNT_KEY_HINTS = {
            "amount", "money", "price", "fee", "payamount", "pay_amount",
            "tradeamount", "trade_amount", "deduct", "deduction",
            "金额", "交易额", "交易金额", "消费", "扣款", "扣费"
    };

    /** 余额语义键（小写包含匹配）。 */
    private static final String[] BALANCE_KEY_HINTS = {
            "balance", "cardbalance", "card_balance", "remain", "remaining",
            "余额", "餘額", "结余", "结存"
    };

    /** 从 JSON 里抽取 "key":"value" 形式的字符串对（正规解析失败时的兜底）。 */
    private static final Pattern JSON_PAIR = Pattern.compile(
            "\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*\"([^\"]*)\"");

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

            // 1) 解析 JSON：文案候选 + 语义金额 / 余额
            Facts facts = parseJson(jsonRaw);
            List<String> islandTexts = facts.texts;

            // 1.5) 通知 extras 里除 param JSON 之外的字段也可能直接带着金额 / 余额
            //（不同 MIUI 版本的字段名不一样），一并纳入解析，避免漏掉。
            List<String> extraTexts = collectExtraTexts(extras);

            // 2) 汇总所有「人类可读」的来源（拼接过的 textInfo 也在这里面）
            StringBuilder human = new StringBuilder();
            human.append(title).append(' ').append(body).append(' ').append(sub)
                    .append(' ').append(rvTexts);
            for (String item : islandTexts) {
                human.append(' ').append(item);
            }
            for (String item : extraTexts) {
                human.append(' ').append(item);
            }
            String all = human.toString();

            // 3) 成功 / 失败
            boolean success = true;
            String lower = all.toLowerCase(Locale.ROOT);
            for (String keyword : FAILURE_KEYWORDS) {
                if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    success = false;
                    break;
                }
            }

            // 4) 把岛文案切成「语义段落」：官方成功文案是一整串
            //    「支付 %1$s元 | 余额 %2$s元」，必须按竖线拆开才能分开识别金额与余额。
            List<String> chunks = new ArrayList<>();
            for (String item : islandTexts) {
                addChunk(chunks, item);
            }
            for (String item : extraTexts) {
                addChunk(chunks, item);
            }
            addChunk(chunks, title);
            addChunk(chunks, body);
            addChunk(chunks, sub);
            addChunk(chunks, rvTexts);
            List<String> segments = new ArrayList<>();
            for (String chunk : chunks) {
                for (String piece : SEGMENT_SPLIT.split(chunk)) {
                    addChunk(segments, piece);
                }
            }

            // 5) 余额优先算（这样从金额里就能把余额片段剔除掉，不会把余额当金额）
            String balance = null;
            String balanceDigits = null;
            Matcher balanceMatcher = BALANCE_PATTERN.matcher(all);
            if (balanceMatcher.find()) {
                balanceDigits = digitsOnly(balanceMatcher.group(1));
            }
            if (balanceDigits == null && facts.balance != null) {
                balanceDigits = firstNumber(facts.balance);
            }
            if (balanceDigits == null) {
                // 逐段兜底：哪一段含「余额」语义就取它的数字
                for (String segment : segments) {
                    if (BALANCE_WORD.matcher(segment).find()) {
                        String number = firstNumber(segment);
                        if (number != null) {
                            balanceDigits = number;
                            break;
                        }
                    }
                }
            }
            if (balanceDigits != null) {
                balance = "余额 ¥" + balanceDigits;
            }

            // 6) 金额：先把余额片段从待匹配文本里剔除，避免「余额 ¥128.50」被当成金额
            String amountScope = BALANCE_PATTERN.matcher(all).replaceAll(" ");
            String amount = null;
            Matcher amountMatcher = AMOUNT_PATTERN.matcher(amountScope);
            if (amountMatcher.find()) {
                String number = amountMatcher.group(1) != null
                        ? amountMatcher.group(1) : amountMatcher.group(2);
                if (number != null) {
                    amount = "¥" + digitsOnly(number);
                }
            }
            if (amount == null && facts.amount != null) {
                String number = firstNumber(facts.amount);
                if (number != null) {
                    amount = "¥" + number;
                }
            }
            if (amount == null) {
                // 逐段兜底：跳过状态文案（刷卡失败…）与余额段，取第一个金额字形
                for (String segment : segments) {
                    String number = amountOf(segment);
                    if (number != null && !number.equals(balanceDigits)) {
                        amount = "¥" + number;
                        break;
                    }
                }
            }
            if (amount == null) {
                // 最后兜底：岛 JSON 只给了「¥2.00」或裸的「2.00」（frontTitle/title 被拆开）
                for (String candidate : islandTexts) {
                    String number = moneyTokenOf(candidate);
                    if (number != null && !number.equals(balanceDigits)) {
                        amount = "¥" + number;
                        break;
                    }
                }
            }

            // 7) 主文案：金额 > 状态文案（刷卡失败 / 刷卡成功…）> 岛 JSON 文案 > 通知正文 > 标题
            String primary = null;
            if (amount != null) {
                primary = amount;
            } else {
                primary = findStatus(segments);
                if (primary == null && !islandTexts.isEmpty()) {
                    primary = stripBalancePart(islandTexts.get(0));
                }
                if (primary == null || primary.isEmpty()) {
                    primary = stripBalancePart(firstNonEmpty(body, firstNonEmpty(title, rvTexts)));
                }
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

    /** 去掉千分位，只保留数字与小数点。 */
    private static String digitsOnly(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace(",", "").trim();
        return value.isEmpty() ? null : value;
    }

    /** 取字符串里第一个数字（去千分位）。 */
    private static String firstNumber(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        return digitsOnly(matcher.group());
    }

    /** 整串就是金额字形时，返回其中的数字。 */
    private static String moneyTokenOf(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (!MONEY_ONLY.matcher(value).matches()) {
            return null;
        }
        return firstNumber(value);
    }

    /** 去重地收集一段文本（空串忽略）。 */
    private static void addChunk(List<String> out, String value) {
        if (value == null) {
            return;
        }
        String text = value.trim();
        if (text.isEmpty() || out.contains(text)) {
            return;
        }
        out.add(text);
    }

    /**
     * 一个语义段落里的金额数字。
     *
     * <p>纯状态文案（「刷卡失败」）与余额段（「余额 128.50元」）一律返回
     * {@code null}，避免「刷卡失败」被当成金额。先按「¥12.00 / 12.00元」这类
     * 带单位 / 符号的字形抓，其次才接受整段就是一个裸金额（「12.00」）。</p>
     */
    private static String amountOf(String segment) {
        if (segment == null || segment.isEmpty()) {
            return null;
        }
        if (isStatusText(segment) || BALANCE_WORD.matcher(segment).find()) {
            return null;
        }
        Matcher matcher = AMOUNT_PATTERN.matcher(segment);
        if (matcher.find()) {
            String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String digits = digitsOnly(number);
            if (digits != null) {
                return digits;
            }
        }
        return moneyTokenOf(segment);
    }

    /**
     * 这段文本是不是纯状态文案（刷卡成功 / 刷卡失败 / 已刷卡 / 切卡…）。
     *
     * <p>判定非常保守：必须命中 {@link #STATUS_WORDS}，且整段不含任何数字
     * （含数字的「支付 2.00元」不会被误判），长度不超过 12 个字符。</p>
     */
    static boolean isStatusText(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        if (text.isEmpty() || text.length() > 12 || NUMBER_PATTERN.matcher(text).find()) {
            return false;
        }
        for (String word : STATUS_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 在若干段落里找出第一段状态文案。 */
    private static String findStatus(List<String> segments) {
        for (String segment : segments) {
            if (isStatusText(segment)) {
                return segment;
            }
        }
        return null;
    }

    /** 去掉一段文案里的余额片段与竖线分隔符（避免主文案把余额再显示一遍）。 */
    private static String stripBalancePart(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = BALANCE_PATTERN.matcher(value).replaceAll(" ");
        cleaned = SEGMENT_SPLIT.matcher(cleaned).replaceAll(" ");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    // ======================================================================
    // 岛 JSON 文案提取
    // ======================================================================

    /** 解析结果：按「先出现的优先」排序的文案 + 语义金额 / 余额。 */
    private static final class Facts {
        final List<String> texts = new ArrayList<>();
        String amount;
        String balance;
    }

    /**
     * 从岛 JSON 里取出人类可读的文案与语义金额 / 余额。
     *
     * <p>优先走 {@link JSONObject} 正规解析；厂商 JSON 不合法时退回正则，
     * 但两条路径都经过 {@link #isDisplayText} 过滤，因此不会再把
     * {@code business} / {@code pic} 这类内部标识当成金额与余额。</p>
     */
    private static Facts parseJson(String json) {
        Facts facts = new Facts();
        if (json == null || json.isEmpty()) {
            return facts;
        }
        try {
            Object root = new JSONObject(json);
            collectJson(root, facts);
        } catch (Throwable ignored) {
            // 不是合法 JSON：走正则兜底
        }
        if (facts.texts.isEmpty()) {
            Matcher matcher = JSON_PAIR.matcher(json);
            while (matcher.find()) {
                addText(facts.texts, matcher.group(2));
            }
        }
        return facts;
    }

    /** 兼容旧调用：只要文案候选。 */
    static List<String> jsonTexts(String json) {
        return parseJson(json).texts;
    }

    /** 递归遍历 JSON，收集文案候选，并按字段名抓语义金额 / 余额。 */
    private static void collectJson(Object node, Facts facts) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            // 1) 字段表里的文案键
            for (String key : TEXT_KEYS) {
                Object value = object.opt(key);
                if (value instanceof String) {
                    addText(facts.texts, (String) value);
                }
            }
            // 2) 语义键：amount / balance（含中文），数字可能是 String 也可能是 Number
            try {
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String lowerKey = key.toLowerCase(Locale.ROOT);
                    Object value = object.opt(key);
                    if (containsAny(lowerKey, BALANCE_KEY_HINTS)) {
                        String text = scalarText(value);
                        if (facts.balance == null && text != null) {
                            facts.balance = text;
                        }
                    } else if (containsAny(lowerKey, AMOUNT_KEY_HINTS)) {
                        String text = scalarText(value);
                        if (facts.amount == null && text != null) {
                            facts.amount = text;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
            // 3) 同一个 textInfo 对象里被拆开的 frontTitle + title + content 重新拼起来
            String joined = joinSplitText(object);
            if (joined != null) {
                addText(facts.texts, joined);
            }
            // 4) 递归子节点（textInfo / bigIslandArea 等都在里面）
            try {
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    collectJson(object.opt(keys.next()), facts);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectJson(array.opt(i), facts);
            }
        }
    }

    /** 把本层的 frontTitle + title + content 按顺序拼成一段完整文案。 */
    private static String joinSplitText(JSONObject object) {
        StringBuilder builder = new StringBuilder();
        for (String key : JOIN_KEYS) {
            Object value = object.opt(key);
            if (value instanceof String) {
                String part = ((String) value).trim();
                if (!part.isEmpty()) {
                    builder.append(part);
                }
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    /** 把 JSON 标量转成字符串（数字也接受），其它类型返回 null。 */
    private static String scalarText(Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (number == Math.rint(number) && Math.abs(number) < 1e15d) {
                return String.valueOf((long) number);
            }
            return String.valueOf(number);
        }
        return null;
    }

    private static boolean containsAny(String lowerKey, String[] hints) {
        for (String hint : hints) {
            if (lowerKey.contains(hint)) {
                return true;
            }
        }
        return false;
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
     * <p>含中日韩文字或货币符号的值一律放行；此外，整串就是一个金额字形
     * （{@code ¥2.00} / {@code 2.00} / {@code 2元}）也放行 —— 这是本版本
     * 新增的规则，用于接住 {@code textInfo} 里被拆开的金额。纯标识符
     * （{@code tsmclient}、{@code miui.focus.pic_start}、{@code imageTextInfoRight}）
     * 与纯整数（{@code "16"}）依旧被丢弃。</p>
     */
    static boolean isDisplayText(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        if (text.length() < 2 || text.length() > 64) {
            return false;
        }
        if (CJK.matcher(text).find()) {
            return true;
        }
        if (CURRENCY.matcher(text).find()) {
            return true;
        }
        return MONEY_ONLY.matcher(text).matches();
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

    /**
     * 把通知 extras 里除岛参数 JSON / 岛 RemoteViews 之外的所有「可读文本」收集起来。
     *
     * <p>不同 MIUI 版本把交易金额 / 余额塞的位置并不一样：有的放进
     * {@code miui.focus.param.custom} 的 {@code textInfo}，有的直接挂在自定义
     * extras 键上（例如 {@code android.infoText}、厂商自定义的 {@code miui.focus.*}
     * 子键）。只读 {@code android.title} / {@code android.text} 这两个固定键会漏，
     * 因此这里统一扫一遍整个 extras，再由 {@link #isDisplayText} 把
     * {@code tsmclient}、{@code miui.focus.pic_start} 这类标识符过滤掉。</p>
     */
    private static List<String> collectExtraTexts(Bundle extras) {
        List<String> out = new ArrayList<>();
        if (extras == null) {
            return out;
        }
        try {
            for (String key : extras.keySet()) {
                if (key == null || isParamJsonKey(key)
                        || IslandStyler.KEY_CUSTOM_RV.equals(key)) {
                    continue;
                }
                Object value;
                try {
                    value = extras.get(key);
                } catch (Throwable ignored) {
                    continue;
                }
                addText(out, readScalar(value));
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return out;
    }

    /** 这个 extras 键是不是已经单独解析过的岛参数 JSON。 */
    private static boolean isParamJsonKey(String key) {
        for (String param : PARAM_JSON_KEYS) {
            if (param.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 把 extras 里的一个值转成可读文本：支持 CharSequence / Number，其它返回 null。 */
    private static String readScalar(Object value) {
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            return text.isEmpty() ? null : text;
        }
        if (value instanceof Number) {
            return scalarText(value);
        }
        return null;
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
