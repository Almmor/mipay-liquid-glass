package com.liquidglass.mipay.glass;

import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liquidglass.mipay.Config;

/**
 * 余额区 / 充值按钮的 iOS 规格实现。
 *
 * <p>反编译 {@code layout/switch_card_fragment} 确认余额区结构：</p>
 * <pre>
 * layout_balance_reminder               卡片容器（原背景 card_balance_bg）
 * ├── btn_balance_reminder_recharge     充值按钮（CTA）
 * └── RelativeLayout
 *     ├── balance_text_layout           标签 + 金额
 *     │   ├── layout_balance_reminder_title   「余额」标签
 *     │   └── balance_text                    金额
 *     └── LinearLayout（balance_tips_*，默认 gone）
 * </pre>
 *
 * <h3>设计规格（1.1.0）</h3>
 * <ul>
 *     <li><b>卡片容器</b>：纯白卡片底、14pt 大圆角、左右 18pt / 上下 20pt 内边距，
 *     极淡系统阴影（alpha 4%、Y 4pt、blur 16pt）；</li>
 *     <li><b>左侧排版</b>：小标签在上（12pt / #8E8E93），大金额在下
 *     （34pt / Heavy / 字距 -0.4pt），两者垂直间距 4pt，整体左对齐；</li>
 *     <li><b>右侧 CTA</b>：纯黑药丸按钮（高度 32pt、圆角 16pt、左右内边距 18pt、
 *     文字 16pt Semibold 白色），与金额垂直居中对齐。</li>
 * </ul>
 *
 * <p><b>深色模式颜色相反</b>：卡片转为深色卡（{@code #1C1C1E}）、金额转白、
 * 按钮转为白底黑字。</p>
 *
 * <p>全部改动幂等，重复遍历不会产生叠加副作用。</p>
 */
public final class AppleStyle {

    // ======================================================================
    // 色值：浅色 / 深色相反
    // ======================================================================

    /** 卡片底色：浅色纯白，深色用 iOS 深色分组背景。 */
    private static final int CARD_BG_LIGHT = 0xFFFFFFFF;
    private static final int CARD_BG_DARK = 0xFF1C1C1E;
    /** 「余额」标签色（Apple Secondary Label Gray，两种模式均可用）。 */
    private static final int LABEL_LIGHT = 0xFF8E8E93;
    private static final int LABEL_DARK = 0xFF8E8E93;
    /** 金额色：浅色纯黑，深色纯白。 */
    private static final int AMOUNT_LIGHT = 0xFF000000;
    private static final int AMOUNT_DARK = 0xFFFFFFFF;
    /** 充值按钮：浅色黑底白字，深色白底黑字。 */
    private static final int BUTTON_BG_LIGHT = 0xFF000000;
    private static final int BUTTON_BG_DARK = 0xFFFFFFFF;
    private static final int BUTTON_PRESSED_LIGHT = 0xFF3A3A3C;
    private static final int BUTTON_PRESSED_DARK = 0xFFD1D1D6;
    private static final int BUTTON_TEXT_LIGHT = 0xFFFFFFFF;
    private static final int BUTTON_TEXT_DARK = 0xFF000000;

    // ======================================================================
    // 规格参数
    // ======================================================================

    /** 卡片圆角（pt/dp）。 */
    private static final float CARD_RADIUS_DP = 14f;
    /** 卡片内边距：左右 18pt、上下 20pt。 */
    private static final float CARD_PAD_H_DP = 18f;
    private static final float CARD_PAD_V_DP = 20f;
    /** 「余额」标签字号（pt）。 */
    private static final float LABEL_SIZE_SP = 12f;
    /** 金额字号（pt）。 */
    private static final float AMOUNT_SIZE_SP = 34f;
    /** 金额字距：-0.4pt / 34pt ≈ -0.012em。 */
    private static final float AMOUNT_KERNING_EM = -0.012f;
    /** 标签与金额的垂直间距（pt）。 */
    private static final float LABEL_AMOUNT_GAP_DP = 4f;
    /** 卡片阴影：alpha 4%、Y 4pt、blur 16pt → 用 4dp elevation 近似。 */
    private static final float CARD_ELEVATION_DP = 4f;
    private static final int CARD_SHADOW_COLOR = 0x0A000000;
    /** 充值按钮规格。 */
    private static final float BUTTON_HEIGHT_DP = 32f;
    private static final float BUTTON_PAD_H_DP = 18f;
    private static final float BUTTON_TEXT_SIZE_SP = 16f;
    /** 字体族：Semibold / Heavy 在 Roboto 上的近似。 */
    private static final String FONT_SEMIBOLD = "sans-serif-medium";
    private static final String FONT_HEAVY = "sans-serif-black";
    /** 文字递归深度上限。 */
    private static final int MAX_TEXT_DEPTH = 8;

    private AppleStyle() {
    }

    // ======================================================================
    // 余额卡片
    // ======================================================================

    /**
     * 余额区：套上 iOS 卡片（底色 / 圆角 / 内边距 / 阴影），并重排文字与按钮。
     *
     * @param panel   余额区容器（{@code layout_balance_reminder}）
     * @param cfg     模块配置（保留入参以便后续扩展）
     * @param density 屏幕密度
     * @param dark    是否深色模式
     */
    public static void applyBalancePanel(View panel, Config cfg, float density, boolean dark) {
        applyCardBackground(panel, density, dark);
        applyCardPadding(panel, density);
        applyCardShadow(panel, density);
        applyAppleLayout(panel, density);
        restyleTexts(panel, dark, 0);
    }

    /** 卡片底：纯色 + 14pt 圆角。 */
    private static void applyCardBackground(View panel, float density, boolean dark) {
        try {
            GradientDrawable card = new GradientDrawable();
            card.setShape(GradientDrawable.RECTANGLE);
            card.setColor(dark ? CARD_BG_DARK : CARD_BG_LIGHT);
            card.setCornerRadius(CARD_RADIUS_DP * density);
            panel.setBackground(card);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 卡片内边距：左右 18pt、上下 20pt。 */
    private static void applyCardPadding(View panel, float density) {
        try {
            int h = Math.round(CARD_PAD_H_DP * density);
            int v = Math.round(CARD_PAD_V_DP * density);
            panel.setPadding(h, v, h, v);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * 卡片阴影：alpha 4%、Y 偏移 4pt、blur 16pt。
     *
     * <p>View 的阴影由 elevation 驱动，透明度只能在 API 28+ 通过
     * {@code setSpotShadowColor} / {@code setOutlineAmbientShadowColor} 指定，
     * 因此这里做版本分支：28+ 用规格里的 4% 黑；更低版本用系统默认阴影。</p>
     */
    private static void applyCardShadow(View panel, float density) {
        try {
            panel.setElevation(CARD_ELEVATION_DP * density);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                panel.setOutlineAmbientShadowColor(CARD_SHADOW_COLOR);
                panel.setOutlineSpotShadowColor(CARD_SHADOW_COLOR);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * 信息排版：标签在上、金额在下，垂直间距 4pt，整体左对齐。
     *
     * <p>原布局是横向 {@code LinearLayout}（{@code balance_text_layout}），
     * 改为竖向并把金额的上外边距设为 4pt。</p>
     */
    private static void applyAppleLayout(View panel, float density) {
        View textLayout = findByEntryName(panel, "balance_text_layout");
        if (textLayout instanceof LinearLayout) {
            try {
                ((LinearLayout) textLayout).setOrientation(LinearLayout.VERTICAL);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        View amount = findByEntryName(panel, "balance_text");
        if (amount != null) {
            try {
                ViewGroup.LayoutParams lp = amount.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).topMargin =
                            Math.round(LABEL_AMOUNT_GAP_DP * density);
                    amount.setLayoutParams(lp);
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static void restyleTexts(View view, boolean dark, int depth) {
        if (view instanceof TextView) {
            restyleText((TextView) view, dark);
        }
        if (depth < MAX_TEXT_DEPTH && view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                restyleTexts(group.getChildAt(i), dark, depth + 1);
            }
        }
    }

    /** 按 id 命中余额区的各个文字，套用规格里的字号 / 字重 / 颜色。 */
    private static void restyleText(TextView tv, boolean dark) {
        String name = GlassChrome.entryNameOf(tv);
        if (name == null) {
            return;
        }
        int amountColor = dark ? AMOUNT_DARK : AMOUNT_LIGHT;
        int labelColor = dark ? LABEL_DARK : LABEL_LIGHT;
        try {
            if ("balance_text".equals(name)) {
                // 核心数据：34pt / Heavy / 紧字距
                tv.setTextSize(AMOUNT_SIZE_SP);
                tv.setTextColor(amountColor);
                tv.setTypeface(Typeface.create(FONT_HEAVY, Typeface.NORMAL));
                tv.setLetterSpacing(AMOUNT_KERNING_EM);
                tv.setIncludeFontPadding(false);
            } else if ("post_paid_card_status".equals(name)) {
                tv.setTextColor(amountColor);
                tv.setTypeface(Typeface.create(FONT_SEMIBOLD, Typeface.NORMAL));
            } else if ("layout_balance_reminder_title".equals(name)) {
                // 标签：12pt / Semibold / 次级灰
                tv.setTextSize(LABEL_SIZE_SP);
                tv.setTextColor(labelColor);
                tv.setTypeface(Typeface.create(FONT_SEMIBOLD, Typeface.NORMAL));
            } else if ("balance_tips_text".equals(name)
                    || "balance_tips_left_bracket".equals(name)
                    || "balance_tips_right_bracket".equals(name)
                    || "post_paid_card_description".equals(name)) {
                tv.setTextColor(labelColor);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 充值按钮（CTA）
    // ======================================================================

    /**
     * 充值按钮：纯黑药丸（深色模式转为白底黑字）。
     *
     * <p>尺寸按规格硬性给定：高度 32pt、宽自适应、左右内边距 18pt，
     * 因此<b>必须在测量后调用</b>（由延迟遍历兜底）。</p>
     *
     * @param view 充值按钮（Button 是 TextView 子类）
     */
    public static void applyRechargeButton(View view, Config cfg, float density, boolean dark) {
        if (!(view instanceof TextView)) {
            return;
        }
        TextView tv = (TextView) view;
        float d = density <= 0f ? 3f : density;
        try {
            applyButtonMetrics(tv, d);

            // 药丸圆角 = 高度的一半（规格：32pt 高 → 16pt 圆角）
            float pillRadius = BUTTON_HEIGHT_DP * 0.5f * d;
            Drawable normal = pill(dark ? BUTTON_BG_DARK : BUTTON_BG_LIGHT, pillRadius);
            Drawable pressed = pill(dark ? BUTTON_PRESSED_DARK : BUTTON_PRESSED_LIGHT, pillRadius);
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_pressed}, pressed);
            background.addState(new int[0], normal);
            tv.setBackground(background);

            tv.setTextSize(BUTTON_TEXT_SIZE_SP);
            tv.setTextColor(dark ? BUTTON_TEXT_DARK : BUTTON_TEXT_LIGHT);
            tv.setTypeface(Typeface.create(FONT_SEMIBOLD, Typeface.NORMAL));
            tv.setIncludeFontPadding(false);
            tv.setGravity(android.view.Gravity.CENTER);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 按钮尺寸：高度 32pt、宽自适应、左右内边距 18pt。 */
    private static void applyButtonMetrics(TextView tv, float density) {
        int height = Math.round(BUTTON_HEIGHT_DP * density);
        int padH = Math.round(BUTTON_PAD_H_DP * density);
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp != null) {
            lp.height = height;
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            tv.setLayoutParams(lp);
        }
        tv.setPadding(padH, 0, padH, 0);
        tv.setMinWidth(0);
        tv.setMinimumWidth(0);
        tv.setMinHeight(0);
        tv.setMinimumHeight(0);
    }

    /** 纯色药丸（圆角取高度一半）。 */
    private static GradientDrawable pill(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    // ======================================================================
    // 其它页面：余额 / 充值类面板
    // ======================================================================

    /**
     * 其它「余额 / 充值类面板」（卡详情页等）保持浅色透明毛玻璃底。
     *
     * <p>这些面板与刷卡页的余额区同源（都是 App 的浅色毛玻璃表面），
     * 固定浅色可避免跟随系统深色后变成黑块。</p>
     */
    public static void applyPanelGlass(View panel, Config cfg, float density) {
        try {
            if (panel.getBackground() instanceof LiquidGlassDrawable) {
                return;
            }
            float d = density <= 0f ? 3f : density;
            float radius = RadiusProbe.resolve(panel);
            if (radius <= 0f) {
                radius = CARD_RADIUS_DP * d;
            }
            panel.setBackground(new LiquidGlassDrawable(cfg, radius, d, true, false, 2.0f));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    /** 在子树中按资源项名（id 名）查找视图。 */
    private static View findByEntryName(View root, String entryName) {
        if (root == null) {
            return null;
        }
        String name = GlassChrome.entryNameOf(root);
        if (entryName.equals(name)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                View found = findByEntryName(group.getChildAt(i), entryName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 给卡面视图加 iOS 规格的底部阴影（alpha 8%、Y 2pt、blur 8pt）。
     *
     * <p>由 {@link GlassViewStyler} 在给卡面叠玻璃罩时调用。
     * 阴影形状跟随卡面圆角，因此这里为视图设置一个圆角矩形 outline。</p>
     *
     * @param view    卡面视图
     * @param radiusPx 卡面圆角（px）
     * @param density 屏幕密度
     */
    public static void applyCardFaceShadow(View view, float radiusPx, float density) {
        try {
            final float radius = radiusPx;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline outline) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                    }
                });
            }
            view.setElevation(2f * (density <= 0f ? 3f : density));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                view.setOutlineAmbientShadowColor(0x14000000);
                view.setOutlineSpotShadowColor(0x14000000);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
