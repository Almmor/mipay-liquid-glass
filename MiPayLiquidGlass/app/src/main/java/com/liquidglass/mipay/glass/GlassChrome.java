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

    /** 充值按钮（1.0.3 起改 Apple Pay 式淡蓝色胶囊）。 */
    private static final Set<String> RECHARGE_BUTTON_IDS = new HashSet<>(Arrays.asList(
            "btn_balance_reminder_recharge"
    ));

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

    /** 刷卡页充值按钮（{@code btn_balance_reminder_recharge}）。 */
    public static boolean isRechargeButton(View view) {
        String name = entryNameOf(view);
        return name != null && RECHARGE_BUTTON_IDS.contains(name);
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
