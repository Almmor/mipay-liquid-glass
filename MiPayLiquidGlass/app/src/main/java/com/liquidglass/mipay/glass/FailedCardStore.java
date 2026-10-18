package com.liquidglass.mipay.glass;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「未收取费用」记录仓库。
 *
 * <p>刷卡失败时宿主只会在超级岛里闪一下「刷卡失败」，交易记录列表里没有对应条目。
 * 这里把失败事件按时间倒序落盘，随后由 {@link TradeDetailSheet} 反射插入宿主的
 * {@code PurchaseRecordAdapter} 数据源，交给宿主的 {@code RecordListItemView}
 * 原生渲染 —— 这样列表行与详情页的样式都与普通交易记录完全一致。</p>
 */
final class FailedCardStore {

    private static final String PREFS = "liquid_glass_unread_cards";
    private static final String KEY = "records";
    /** 最多保留的失败记录条数。 */
    private static final int MAX = 20;
    /** 同一条失败通知通常会被 notify / startForeground 重复投递，短时间内按文案去重。 */
    private static final long DEDUPE_MS = 5000L;
    /** 这些失败与读卡无关（充值、退款等），不生成「未收取费用」记录。 */
    private static final String[] IGNORE_KEYWORDS = {
            "充值", "退款", "转账", "开卡", "绑卡", "解绑", "删除", "网络", "登录"
    };

    /** 记录版本号：变化后列表适配器才会重新注入，避免每次绑定都改数据源。 */
    private static final java.util.concurrent.atomic.AtomicLong VERSION =
            new java.util.concurrent.atomic.AtomicLong();

    private static long lastTime;
    private static String lastText;

    private FailedCardStore() {
    }

    /** 一条失败记录。 */
    static final class Entry {
        long id;
        long timestamp;
        /** yyyyMMdd，宿主列表按这个格式解析。 */
        String date;
        /** HHmmss，宿主列表按这个格式解析。 */
        String time;
        /** 通知里的原始文案，用于详情页排查。 */
        String text;
    }

    static long version() {
        return VERSION.get();
    }

    /**
     * 记录一次刷卡失败（由 {@link IslandStyler} 在焦点通知判定失败时调用）。
     */
    static void record(Context context, IslandInfo info) {
        if (context == null || info == null || info.success) {
            return;
        }
        synchronized (FailedCardStore.class) {
            long now = System.currentTimeMillis();
            String text = info.rawText == null ? "" : info.rawText.trim();
            if (text.isEmpty()) {
                text = "刷卡失败";
            }
            if (isUnrelated(text)) {
                return;
            }
            // 同一条通知会被 notify / startForeground 各投递一次，甚至连续重发；
            // 短时间内的同文案只算一次。
            if (now - lastTime < DEDUPE_MS && text.equals(lastText)) {
                return;
            }
            lastTime = now;
            lastText = text;

            List<Entry> entries = load(context);
            Entry entry = new Entry();
            entry.id = now;
            entry.timestamp = now;
            entry.date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(now));
            entry.time = new SimpleDateFormat("HHmmss", Locale.US).format(new Date(now));
            entry.text = text;
            entries.add(0, entry);
            while (entries.size() > MAX) {
                entries.remove(entries.size() - 1);
            }
            save(context, entries);
            VERSION.incrementAndGet();
            android.util.Log.i("MiPayLiquidGlass/UnreadCard",
                    "记录未收取费用：" + entry.date + " " + entry.time + " 文案=" + text);
        }
    }

    /** 充值 / 退款等与刷卡无关的失败不算「未收取费用」。 */
    private static boolean isUnrelated(String text) {
        for (String keyword : IGNORE_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 按时间倒序返回所有失败记录。 */
    static List<Entry> all(Context context) {
        synchronized (FailedCardStore.class) {
            return load(context);
        }
    }

    private static List<Entry> load(Context context) {
        List<Entry> entries = new ArrayList<>();
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY, null);
            if (raw == null || raw.isEmpty()) {
                return entries;
            }
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && i < MAX; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                Entry entry = new Entry();
                entry.id = object.optLong("id");
                entry.timestamp = object.optLong("ts");
                entry.date = object.optString("date");
                entry.time = object.optString("time");
                entry.text = object.optString("text");
                if (entry.date == null || entry.date.isEmpty()
                        || entry.time == null || entry.time.isEmpty()) {
                    continue;
                }
                entries.add(entry);
            }
        } catch (Throwable ignored) {
            // 损坏的数据直接当作空仓库
        }
        return entries;
    }

    private static void save(Context context, List<Entry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) {
                JSONObject object = new JSONObject();
                object.put("id", entry.id);
                object.put("ts", entry.timestamp);
                object.put("date", entry.date);
                object.put("time", entry.time);
                object.put("text", entry.text);
                array.put(object);
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, array.toString()).apply();
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
