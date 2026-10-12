package com.liquidglass.mipay.glass;

import android.view.View;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 界面控件（非卡面）处理通道（1.0.2 引入，1.0.3 拆分）。
 *
 * <p>反编译 {@code layout/switch_card_fragment} 确认了刷卡页三类控件的稳定 ID 锚点：</p>
 *
 * <table>
 *     <tr><th>控件</th><th>ID</th><th>处理（1.0.3）</th></tr>
 *     <tr><td>顶部扫码 / 帮助 / 设置按钮</td><td>{@code iv_open_scan} / {@code swiping_help} / {@code swiping_settings}</td><td>圆形玻璃底，图标保留</td></tr>
 *     <tr><td>卡片下余额区</td><td>{@code layout_balance_reminder}</td><td><b>去背景</b>，文字按 Apple Wallet 重排（{@link AppleStyle}）</td></tr>
 *     <tr><td>充值按钮</td><td>{@code btn_balance_reminder_recharge}</td><td><b>淡蓝色胶囊</b> + 白字（{@link AppleStyle}）</td></tr>
 * </table>
 *
 * <p>其余命中 balance / recharge / topup 关键词的「带背景面板」（卡详情页等）仍走玻璃背景。
 * 深色 / 浅色模式由调用方传入。</p>
 */
public final class GlassChrome {

    /**
     * 顶部按钮：圆形玻璃底 + 原图标保留。
     *
     * <p>它们是 ImageView，会被 {@link CardFaceGuard} 当作图片素材保护，
     * 因此必须在遍历时<b>先于</b>卡面判定命中本通道。</p>
     */
    private static final Set<String> ICON_BUTTON_IDS = new HashSet<>(Arrays.asList(
            "iv_open_scan",
            "swiping_help",
            "swiping_settings"
    ));

    /** 余额区（1.0.3 起去背景、重排文字，不再套玻璃）。 */
    private static final Set<String> BALANCE_PANEL_IDS = new HashSet<>(Arrays.asList(
            "layout_balance_reminder"
    ));

    /**
     * 刷卡页（双击电源键）的充值按钮：<b>天蓝纯色磨砂玻璃胶囊</b>。
     *
     * <p>来自反编译 {@code layout/switch_card_fragment} 的余额区
     * {@code layout_balance_reminder}：金额在左、CTA 在右。</p>
     */
    private static final Set<String> SWIPE_RECHARGE_BUTTON_IDS = new HashSet<>(Arrays.asList(
            "btn_balance_reminder_recharge"
    ));

    /**
     * 卡详情页的充值按钮：<b>天蓝纯色磨砂玻璃</b>，圆角与余额卡片一致（14pt）。
     *
     * <p>{@code tv_top_up} / {@code tv_refresh} 来自反编译
     * {@code layout}（{@code IssuedTransCardDetailOptionView}）：两者背景同为
     * {@code blue_border_selector}，文字分别是「充值(String/card_detail_recharge_now)」
     * 与「刷新(card_detail_refresh_now)」。实机 dump 到的资源名与文本一一对得上。</p>
     */
    private static final Set<String> DETAIL_RECHARGE_BUTTON_IDS = new HashSet<>(Arrays.asList(
            "tv_top_up",
            "tv_refresh",
            "btn_recharge"
    ));

    /** 兼容旧调用：全部充值按钮 id。 */
    private static final Set<String> RECHARGE_BUTTON_IDS = new HashSet<>();

    /**
     * 背景玻璃化的 ID 关键词：其余余额 / 充值类面板（卡详情页等）。
     *
     * <p>只对「本来就有背景」的视图生效（见 {@link #isGlassPanel}），
     * 避免把纯文字标签也套上一块玻璃。刷卡页的余额区与充值按钮
     * 由精确 ID 通道接管，不走关键词。</p>
     */
    private static final String[] GLASS_PANEL_KEYWORDS = {
            "balance", "recharge", "topup", "top_up"
    };

    static {
        RECHARGE_BUTTON_IDS.addAll(SWIPE_RECHARGE_BUTTON_IDS);
        RECHARGE_BUTTON_IDS.addAll(DETAIL_RECHARGE_BUTTON_IDS);
    }

    private GlassChrome() {
    }

    /** 该视图是否属于界面控件通道（顶部按钮 / 余额区 / 充值按钮 / 其余余额类面板）。 */
    public static boolean matches(View view) {
        return isIconButton(view) || isBalancePanel(view)
                || isRechargeButton(view) || isGlassPanel(view);
    }

    /** 顶部圆形按钮（扫码 / 帮助 / 设置）。 */
    public static boolean isIconButton(View view) {
        String name = entryNameOf(view);
        return name != null && ICON_BUTTON_IDS.contains(name);
    }

    /** 刷卡页卡片下的余额区（{@code layout_balance_reminder}）。 */
    public static boolean isBalancePanel(View view) {
        String name = entryNameOf(view);
        return name != null && BALANCE_PANEL_IDS.contains(name);
    }

    /** 是否任意充值按钮（刷卡页胶囊 + 卡详情页圆角按钮）。 */
    public static boolean isRechargeButton(View view) {
        return isSwipeRechargeButton(view) || isDetailRechargeButton(view);
    }

    /** 刷卡页（双击电源键）的充值按钮 → 天蓝纯色磨砂玻璃胶囊。 */
    public static boolean isSwipeRechargeButton(View view) {
        String name = entryNameOf(view);
        return name != null && SWIPE_RECHARGE_BUTTON_IDS.contains(name);
    }

    /** 卡详情页的充值 / 刷新按钮 → 天蓝纯色磨砂玻璃，圆角与余额卡片一致。 */
    public static boolean isDetailRechargeButton(View view) {
        String name = entryNameOf(view);
        return name != null && DETAIL_RECHARGE_BUTTON_IDS.contains(name);
    }

    /**
     * 其它页面的「充值按钮」：ID 命中充值关键词，且本身是可点击的文字控件。
     *
     * <p>为什么要跟面板区分：充值按钮与「余额面板」都可能命中
     * {@code recharge} / {@code balance} 关键词，但按钮要做成天蓝磨砂玻璃、
     * 面板要保持浅色毛玻璃，两者观感完全不同（1.6.8 新增）。</p>
     */
    public static boolean looksLikeRechargeButton(View view) {
        String name = entryNameOf(view);
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        boolean keyword = lower.contains("recharge")
                || lower.contains("topup") || lower.contains("top_up");
        if (!keyword) {
            return false;
        }
        return view instanceof android.widget.TextView || view.isClickable();
    }

    /** 余额 / 充值关键词命中的「带背景面板」（卡详情页等）。 */
    public static boolean isGlassPanel(View view) {
        if (view.getBackground() == null) {
            return false;
        }
        String name = entryNameOf(view);
        if (name == null) {
            return false;
        }
        if (BALANCE_PANEL_IDS.contains(name) || RECHARGE_BUTTON_IDS.contains(name)) {
            return false;
        }
        for (String keyword : GLASS_PANEL_KEYWORDS) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public static String entryNameOf(View view) {
        if (view == null) {
            return null;
        }
        try {
            int id = view.getId();
            if (id == View.NO_ID || id <= 0) {
                return null;
            }
            String name = view.getResources().getResourceEntryName(id);
            return name == null ? null : name.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
