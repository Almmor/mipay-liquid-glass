package com.liquidglass.mipay.glass;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liquidglass.mipay.Config;

/**
 * Apple Pay / Apple Wallet 风格设计（1.0.3 引入，1.0.4 对齐 Apple Pay）。
 *
 * <p>反编译 {@code layout/switch_card_fragment} 确认余额区结构：
 * {@code layout_balance_reminder}（RelativeLayout，原背景 {@code card_balance_bg}）内含</p>
 * <pre>
 * layout_balance_reminder
 * ├── btn_balance_reminder_recharge       充值按钮（selector 背景）
 * └── RelativeLayout
 *     ├── balance_text_layout             横向 LinearLayout
 *     │   ├── layout_balance_reminder_title   「余额」
 *     │   └── balance_text                    金额
 *     └── LinearLayout（balance_tips_*，默认 gone）
 * </pre>
 *
 * <p>1.0.4 起按 Apple Pay 的排版与样式落地：</p>
 * <ul>
 *     <li><b>余额区底</b>：用 overlay 形态的 {@link LiquidGlassDrawable}，只做极淡的
 *     铺底与高光 —— 既是「透明」的，又有液态玻璃质感，不会像纯色深底那样压出一块黑；</li>
 *     <li><b>显示格式</b>：把 {@code balance_text_layout} 改成竖向，
 *     形成 Apple Wallet 的「小标签在上、大金额在下」层级；</li>
 *     <li><b>字号字重</b>：标签 13sp / 次级标签色，金额 28sp / 主标签色 / medium；</li>
 *     <li><b>充值按钮</b>：淡蓝色胶囊 + 白色 medium 文字，按下加深
 *     （浅色 iOS 系统蓝 {@code #007AFF}，深色 {@code #0A84FF}）。</li>
 * </ul>
 *
 * <p>全部改动幂等，重复遍历不会产生叠加副作用。</p>
 */
public final class AppleStyle {

    /** iOS 浅色主标签色（labelColor）。 */
    private static final int LIGHT_PRIMARY = 0xFF1C1C1E;
    /** iOS 浅色次级标签色（secondaryLabelColor）。 */
    private static final int LIGHT_SECONDARY = 0xFF8A8A8E;
    /** iOS 深色主标签色。 */
    private static final int DARK_PRIMARY = 0xFFFFFFFF;
    /** iOS 深色次级标签色（约 60% 白）。 */
    private static final int DARK_SECONDARY = 0x99EBEBF5;

    /** 「余额」标签字号（sp）。 */
    private static final float LABEL_TEXT_SIZE_SP = 13f;
    /** 金额字号（sp）。 */
    private static final float AMOUNT_TEXT_SIZE_SP = 28f;
    /** 探测不到圆角时的兜底圆角（dp）。 */
    private static final float FALLBACK_RADIUS_DP = 16f;
    /**
     * 余额面板的玻璃强度倍率。
     *
     * <p>余额面板在 App 自身设计里是一块<b>白色毛玻璃面板</b>
     * （背景 {@code card_balance_bg} = {@code @android:color/background_light}，
     * 深色下则为 {@code card_blur_balance_bg} 的 {@code #e5141414}）。因此这里固定用
     * <b>浅色</b>玻璃配方，并给足强度，让它在任何页面底色上都呈现「透明毛玻璃」，
     * 而不会被看成一块黑。</p>
     */
    private static final float BALANCE_GLASS_STRENGTH = 2.6f;
    /** 充值按钮玻璃强度：在 Apple 蓝胶囊之上叠一层可见的玻璃高光。 */
    private static final float RECHARGE_GLASS_STRENGTH = 1.5f;

    /** 文字递归深度上限。 */
    private static final int MAX_TEXT_DEPTH = 8;

    private AppleStyle() {
    }

    // ======================================================================
    // 余额面板
    // ======================================================================

    /**
     * 余额面板：换成「透明液态玻璃」底，文字按 Apple Pay 排版重设。
     *
     * @param panel   余额区容器（{@code layout_balance_reminder}）
     * @param cfg     模块配置
     * @param density 屏幕密度
     * @param dark    是否深色模式
     */
    public static void applyBalancePanel(View panel, Config cfg, float density, boolean dark) {
        applyGlassBackground(panel, cfg, density, dark);
        applyAppleLayout(panel);
        restyleTexts(panel, dark, 0);
    }

    /**
     * 其它「余额 / 充值类面板」（卡详情页等）复用同一套透明毛玻璃底。
     *
     * <p>这些面板与刷卡页的余额区同源（都是 App 的浅色毛玻璃表面），
     * 因此也用固定浅色配方，避免跟随系统深色后变成黑块。</p>
     */
    public static void applyPanelGlass(View panel, Config cfg, float density) {
        applyGlassBackground(panel, cfg, density, false);
    }

    /**
     * 透明毛玻璃底。
     *
     * <p>用 <b>overlay + 固定浅色 + 加strength</b> 的组合：</p>
     * <ul>
     *     <li>overlay：不描边、不做色散，只有铺底与柔光，不会引入线条；</li>
     *     <li>固定浅色（{@code dark=false}）：不跟随系统深色，避免压成黑块；</li>
     *     <li>strength：把铺底与高光提亮，得到可见的毛玻璃质感，而非一片透明。</li>
     * </ul>
     */
    private static void applyGlassBackground(View panel, Config cfg, float density, boolean dark) {
        if (panel.getBackground() instanceof LiquidGlassDrawable) {
            return;
        }
        float safeDensity = density <= 0f ? 3f : density;
        float radius = RadiusProbe.resolve(panel);
        if (radius <= 0f) {
            radius = FALLBACK_RADIUS_DP * safeDensity;
        }
        try {
            panel.setBackground(new LiquidGlassDrawable(
                    cfg, radius, safeDensity, true, false, BALANCE_GLASS_STRENGTH));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * Apple Wallet 显示格式：把「余额 19.75」拆成上下两行。
     *
     * <p>原布局是一个横向 LinearLayout（{@code balance_text_layout}），
     * 标签与金额并排。改成竖向即得到 Apple 的
     * 「<b>余额</b>（小、灰）→ <b>¥19.75</b>（大、白）」层级。</p>
     */
    private static void applyAppleLayout(View panel) {
        View textLayout = findByEntryName(panel, "balance_text_layout");
        if (textLayout instanceof LinearLayout) {
            try {
                ((LinearLayout) textLayout).setOrientation(LinearLayout.VERTICAL);
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

    /** 按 id 命中余额区的各个文字，换成 Apple Pay 的字号 / 颜色层级。 */
    private static void restyleText(TextView tv, boolean dark) {
        String name = GlassChrome.entryNameOf(tv);
        if (name == null) {
            return;
        }
        int primary = dark ? DARK_PRIMARY : LIGHT_PRIMARY;
        int secondary = dark ? DARK_SECONDARY : LIGHT_SECONDARY;
        try {
            if ("balance_text".equals(name) || "post_paid_card_status".equals(name)) {
                if ("balance_text".equals(name)) {
                    // 金额：大号 + 主标签色 + medium，对应 Apple Wallet 的余额数字
                    tv.setTextSize(AMOUNT_TEXT_SIZE_SP);
                    tv.setTextColor(primary);
                    tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                } else {
                    tv.setTextColor(primary);
                    tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                }
            } else if ("layout_balance_reminder_title".equals(name)
                    || "balance_tips_text".equals(name)
                    || "balance_tips_left_bracket".equals(name)
                    || "balance_tips_right_bracket".equals(name)
                    || "post_paid_card_description".equals(name)) {
                // 「余额」标签 / 括号提示 / 副说明：小号 + 次级标签色
                tv.setTextColor(secondary);
                if ("layout_balance_reminder_title".equals(name)) {
                    tv.setTextSize(LABEL_TEXT_SIZE_SP);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 充值按钮
    // ======================================================================

    /**
     * 充值按钮：Apple Pay 式淡蓝色胶囊，<b>并在其上叠加一层液态玻璃</b>。
     *
     * <p>胶囊圆角按按钮实测高度取一半，因此<b>必须等测量完成后调用</b>
     * （与顶部圆形玻璃按钮同一约定，由延迟遍历兜底）。</p>
     *
     * @param view 充值按钮（Button 是 TextView 子类）
     */
    public static void applyRechargeButton(View view, Config cfg, float density, boolean dark) {
        if (!(view instanceof TextView)) {
            return;
        }
        TextView tv = (TextView) view;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float safeDensity = density <= 0f ? 3f : density;
        float pillRadius = Math.min(width, height) * 0.5f;
        try {
            Drawable normal = glassPill(cfg, safeDensity, dark, false, pillRadius);
            Drawable pressed = glassPill(cfg, safeDensity, dark, true, pillRadius);
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_pressed}, pressed);
            background.addState(new int[0], normal);
            tv.setBackground(background);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * 蓝色胶囊 + 液态玻璃高光（两层）。
     *
     * <p>底层是 Apple 蓝的胶囊（保留品牌色与按下反馈），上层叠一块 overlay 形态的
     * {@link LiquidGlassDrawable} —— 它只画铺底与柔光、不描边、不做色散，
     * 于是得到「玻璃盖在蓝色按钮上」的通透质感，同时不会引出线条。</p>
     */
    private static Drawable glassPill(Config cfg, float density, boolean dark,
                                     boolean pressed, float radius) {
        Drawable base;
        if (pressed) {
            base = solid(dark ? 0xFF0A6FD8 : 0xFF0062CC, radius);
        } else {
            base = pillGradient(
                    dark ? 0xFF3F9DFF : 0xFF45A4FF,
                    dark ? 0xFF0A84FF : 0xFF007AFF,
                    radius);
        }
        LiquidGlassDrawable glass = new LiquidGlassDrawable(
                cfg, radius, density, true, false, RECHARGE_GLASS_STRENGTH);
        return new LayerDrawable(new Drawable[]{base, glass});
    }

    /** 淡蓝色上下渐变的胶囊（亮在上、Apple 蓝在下，保留玻璃页面的通透感）。 */
    private static GradientDrawable pillGradient(int topColor, int bottomColor, float radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{topColor, bottomColor});
        drawable.setCornerRadius(radius);
        return drawable;
    }

    /** 按下态的纯色胶囊。 */
    private static GradientDrawable solid(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
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
}
