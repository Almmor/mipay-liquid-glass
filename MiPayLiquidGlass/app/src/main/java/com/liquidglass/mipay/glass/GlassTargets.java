package com.liquidglass.mipay.glass;

import android.view.View;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 精准适配目标：小米智能卡自绘控件的类名白名单。
 *
 * <p>这是 1.0.1 的核心改动。对 APK 反编译（aapt2 解资源 + dexdump 解类结构）后发现：
 * 刷卡页 {@code layout/switch_card_page_fragment} 与卡面 {@code layout/card_view_item}
 * 中<b>没有任何 View 在 XML 里设置 background</b>，所有视觉都由自定义 View 内部实现。
 * 因此 1.0.0 那种「靠背景 Drawable 类型识别卡片」的启发式在这些页面上命中数为 0。</p>
 *
 * <p>解决办法是改用<b>类名白名单直击</b>。这些类名都没有被混淆，是稳定的锚点
 * （反编译确认的父类见注释）。命中后只做一件事：在 {@code View#setForeground} 上叠一层
 * 玻璃镜面，不改动原有背景与绘制内容。</p>
 */
public final class GlassTargets {

    /**
     * 白名单类名（精确匹配，反编译确认未混淆）。
     *
     * <p>刻意不包含卡面本体 {@code ui.widget.CardView}（它是 {@code AppCompatImageView}
     * 的子类，属于图片素材，由 {@link CardFaceGuard} 负责保护与叠加），
     * 也不包含 {@code FingerLayer} 基类（避免与子类重复叠加）。</p>
     */
    private static final String[] CLASS_NAMES = {
            // 刷卡页：卡堆容器（id = content，父类 FrameLayout）
            "com.miui.tsmclient.ui.quick.CardStackLayout",
            // 刷卡页：手指感应层（id = finger_layer，父类 LinearLayout）
            "com.miui.tsmclient.quick.nonenfc.SwitchCardFingerLayer",
            // 卡列表项（父类 RelativeLayout）
            "com.miui.tsmclient.ui.widget.MiPayCardItemView",
            // 刷卡结果区（id = swipe_card_res_view，父类 LinearLayout）
            "com.miui.tsmclient.ui.widget.SwipeCardResourcesView",
            // 刷卡区域弹窗（父类 FrameLayout）
            "com.miui.tsmclient.ui.widget.SwipingAreaDialogView",
            // 切卡二维码（父类 RelativeLayout）
            "com.miui.tsmclient.ui.widget.SwitchCardQRView",
            // 列表滑动项（父类 RelativeLayout）
            "com.miui.tsmclient.ui.widget.SlideView",
            // 卡片 + 更多项组合布局
            "com.miui.tsmclient.ui.widget.CardWithMoreItemsLayout"
    };

    private static final Set<String> LOOKUP = new HashSet<>(Arrays.asList(CLASS_NAMES));

    private GlassTargets() {
    }

    /** 该视图是否属于需要精准叠加玻璃镜面的自绘控件。 */
    public static boolean matches(View view) {
        if (view == null) {
            return false;
        }
        try {
            return LOOKUP.contains(view.getClass().getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 白名单条数，用于日志与设置界面展示。 */
    public static int size() {
        return CLASS_NAMES.length;
    }
}
