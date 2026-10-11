package com.liquidglass.mipay;

import android.content.SharedPreferences;

/**
 * 模块配置模型。
 *
 * <p>该类是纯数据类，不依赖任何 Xposed API，因此既可以在被 Hook 的「小米智能卡」进程中使用，
 * 也可以在模块自身的设置界面中使用。</p>
 *
 * @author LiquidGlass
 */
public final class Config {

    /** 模块自身的包名，用于被 Hook 进程读取 SharedPreferences。 */
    public static final String MODULE_PACKAGE = "com.liquidglass.mipay";

    /** 配置文件名（与 {@code SettingsActivity} 中写入的文件保持一致）。 */
    public static final String PREFS_NAME = "liquid_glass_prefs";

    public static final String KEY_ENABLE = "enable";
    public static final String KEY_BLUR = "blur_radius_dp";
    public static final String KEY_TINT = "tint_alpha";
    public static final String KEY_CORNER = "corner_radius_dp";
    public static final String KEY_REFRACTION = "refraction";
    public static final String KEY_FLOW = "flow";
    public static final String KEY_DEEP = "deep_style";
    public static final String KEY_IMMERSIVE = "immersive";
    public static final String KEY_KEEP_CARD_FACE = "keep_card_face";
    public static final String KEY_GLASS_OVERLAY = "glass_overlay";
    public static final String KEY_SMART_TARGETS = "smart_targets";
    public static final String KEY_RIM_STROKE = "rim_stroke";
    public static final String KEY_CARD_RADIUS_MM = "card_radius_mm";

    /** 总开关。 */
    public boolean enable = true;
    /** 窗口背景模糊半径（dp），对应 {@code Window#setBackgroundBlurRadius}。 */
    public int blurRadiusDp = 32;
    /** 玻璃染色强度（0-255 的 alpha 分量）。 */
    public int tintAlpha = 64;
    /** 玻璃默认圆角半径（dp），用于无法从原背景推断圆角的容器。 */
    public int cornerRadiusDp = 26;
    /** 是否启用 AGSL 折射 / 色散高光（Android 13+）。 */
    public boolean refraction = true;
    /** 是否启用玻璃表面流光动效。 */
    public boolean flow = false;
    /** 是否把纯色背景的容器也转换为玻璃材质。 */
    public boolean deepStyle = false;
    /** 是否让应用根背景半透明，从而透出被模糊的上层内容。 */
    public boolean immersive = false;
    /**
     * 是否保留设备卡面。
     *
     * <p>开启后，凡是识别为「卡面 / 图片素材」的视图（ImageView、Bitmap 背景、
     * 自定义 Drawable、以及资源名带 card / 卡面 等关键词的容器）都不会被替换背景，
     * 原汁原味保留厂商卡面。</p>
     */
    public boolean keepCardFace = true;
    /**
     * 是否在保留的卡面之上叠加一层玻璃镜面。
     *
     * <p>叠加层只绘制折射高光、色散彩边与流光，不做基础染色铺底，
     * 因此卡面图案依然清晰可见，只是多了一层「玻璃罩」的质感。</p>
     */
    public boolean glassOverlay = true;
    /**
     * 是否启用「精准适配」：按反编译得到的类名白名单直接命中小米智能卡的自绘控件。
     *
     * <p>刷卡页与卡面布局中没有任何 View 在 XML 里设置 background，
     * 视觉全部由自定义 View 内部实现，因此必须靠类名白名单才能命中。
     * 关闭后只会退回基于背景 Drawable 的保守启发式。</p>
     */
    public boolean smartTargets = true;
    /**
     * 是否绘制玻璃「边缘亮线」（rim stroke）。
     *
     * <p><b>默认关闭。</b>1.0.3 及以前每个被玻璃化的元素都会描一圈白色亮边，
     * 页面上几十个圆角容器叠加后就是满屏「混乱线条」。关闭后玻璃只保留柔和的
     * 顶部高光与内阴影，观感干净；确实需要玻璃「封边」的用户可自行打开。</p>
     */
    public boolean rimStroke = false;
    /**
     * 卡面圆角（毫米）。
     *
     * <p>反编译确认卡面的圆角并不在卡面自身，而是由外层 MiuiX 圆角容器统一给出，
     * 实测规格为 <b>3.18mm</b>（1/8 英寸）。玻璃罩按该值绘制圆角，
     * 才能与卡面严丝合缝、不出现错位的边线。</p>
     */
    public float cardRadiusMm = 3.18f;

    public static Config defaults() {
        return new Config();
    }

    /** 从任意 SharedPreferences 读取配置，任何异常都会退回默认值。 */
    public static Config read(SharedPreferences prefs) {
        Config c = new Config();
        if (prefs == null) {
            return c;
        }
        try {
            c.enable = prefs.getBoolean(KEY_ENABLE, c.enable);
            c.blurRadiusDp = clamp(prefs.getInt(KEY_BLUR, c.blurRadiusDp), 0, 80);
            c.tintAlpha = clamp(prefs.getInt(KEY_TINT, c.tintAlpha), 0, 200);
            c.cornerRadiusDp = clamp(prefs.getInt(KEY_CORNER, c.cornerRadiusDp), 0, 60);
            c.refraction = prefs.getBoolean(KEY_REFRACTION, c.refraction);
            c.flow = prefs.getBoolean(KEY_FLOW, c.flow);
            c.deepStyle = prefs.getBoolean(KEY_DEEP, c.deepStyle);
            c.immersive = prefs.getBoolean(KEY_IMMERSIVE, c.immersive);
            c.keepCardFace = prefs.getBoolean(KEY_KEEP_CARD_FACE, c.keepCardFace);
            c.glassOverlay = prefs.getBoolean(KEY_GLASS_OVERLAY, c.glassOverlay);
            c.smartTargets = prefs.getBoolean(KEY_SMART_TARGETS, c.smartTargets);
            c.rimStroke = prefs.getBoolean(KEY_RIM_STROKE, c.rimStroke);
            c.cardRadiusMm = clamp(prefs.getFloat(KEY_CARD_RADIUS_MM, c.cardRadiusMm), 0f, 12f);
        } catch (Throwable ignored) {
            // 配置损坏时使用默认值，保证被 Hook 的应用不会因此崩溃
        }
        return c;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    @Override
    public String toString() {
        return "Config{enable=" + enable
                + ", blur=" + blurRadiusDp + "dp"
                + ", tint=" + tintAlpha
                + ", corner=" + cornerRadiusDp + "dp"
                + ", refraction=" + refraction
                + ", flow=" + flow
                + ", deep=" + deepStyle
                + ", immersive=" + immersive
                + ", keepCardFace=" + keepCardFace
                + ", glassOverlay=" + glassOverlay
                + ", smartTargets=" + smartTargets
                + ", rimStroke=" + rimStroke
                + ", cardRadius=" + cardRadiusMm + "mm" + '}';
    }
}
