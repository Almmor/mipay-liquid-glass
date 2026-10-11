package com.liquidglass.mipay.glass;

import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.NinePatchDrawable;
import android.graphics.drawable.VectorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 卡面守卫：判断一个视图是否承载「设备卡面 / 图片素材」。
 *
 * <p>小米智能卡的卡面（交通卡封面、银行卡片面、卡种图）形态多样：</p>
 * <ul>
 *     <li>最常见是 {@code ImageView} 上直接 setImageXXX；</li>
 *     <li>也可能是把 Bitmap / NinePatch 设为控件背景；</li>
 *     <li>或者是厂商自己写的自定义 Drawable；</li>
 *     <li>还可能是 ID 资源名带 {@code card / kaimian / cover} 等语义的容器。</li>
 * </ul>
 *
 * <p>判定策略刻意「宁可多保护、不可误改」：只要命中任意一条就认定为卡面素材，
 * 于是它和它的子树都会被跳过改造，只在最上层叠一层玻璃镜面。
 * 这样既能美化页面，又永远不会把用户的卡面弄花。</p>
 */
public final class CardFaceGuard {

    /**
     * 视为卡面的资源名关键词（小写匹配）。
     * 覆盖中英文常见的卡面 / 图片命名习惯。
     */
    private static final String[] PROTECTED_KEYWORDS = {
            "card", "cardface", "card_face", "cardbg", "card_bg",
            "kaimian", "face", "cover", "artwork",
            "banner", "poster", "photo", "picture",
            "img", "image", "avatar", "logo", "thumbnail",
            "tsm", "transit", "bus", "bank"
    };

    /**
     * 绝对不碰的类。
     *
     * <p>反编译确认：刷卡页 {@code notice_icon} 是 Lottie 动画，
     * 而 {@code layout/hybrid_main} 的唯一子节点是小米自研 H5 容器 {@code miuipub.hybrid.HybridView}。
     * 这两类对象既不能替换背景，也不应叠加玻璃镜面 —— 前者会破坏动画，
     * 后者会把整个 H5 页面罩上一层边框。</p>
     */
    private static final Set<String> NEVER_TOUCH = new HashSet<>(Arrays.asList(
            "com.airbnb.lottie.LottieAnimationView",
            "com.airbnb.lottie.LottieDrawable",
            "miuipub.hybrid.HybridView",
            "com.miuipub.internal.hybrid.WebContainerView",
            "com.miuipub.internal.hybrid.HybridProgressView"
    ));

    private CardFaceGuard() {
    }

    // ======================================================================
    // 单个视图
    // ======================================================================

    /** 该视图是否属于「任何情况下都不碰」的类别（动画 / H5 容器）。 */
    public static boolean isNeverTouch(View view) {
        if (view == null) {
            return false;
        }
        try {
            return NEVER_TOUCH.contains(view.getClass().getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 该视图本身是否承载卡面 / 图片素材。 */
    public static boolean isArtwork(View view) {
        if (view == null) {
            return false;
        }
        if (view instanceof ImageView) {
            return ((ImageView) view).getDrawable() != null;
        }
        if (containsBitmap(view.getBackground(), 0)) {
            return true;
        }
        if (isCustomDrawable(view.getBackground())) {
            return true;
        }
        return matchesKeyword(view);
    }

    /** 子树中（限定深度内）是否包含卡面 / 图片素材。 */
    public static boolean hasArtworkDescendant(View view, int maxDepth) {
        if (maxDepth <= 0 || !(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (isArtwork(child)) {
                return true;
            }
            if (hasArtworkDescendant(child, maxDepth - 1)) {
                return true;
            }
        }
        return false;
    }

    // ======================================================================
    // Drawable 形态判定
    // ======================================================================

    /**
     * 递归判断 Drawable 中是否包含位图 / 点九图内容。
     *
     * <p>LayerDrawable、StateListDrawable、InsetDrawable 这类「壳」在卡面场景中非常常见，
     * 因此需要向下钻取判断，而不是只看最外层类型。</p>
     */
    private static boolean containsBitmap(Drawable drawable, int depth) {
        if (drawable == null || depth > 4) {
            return false;
        }
        if (drawable instanceof BitmapDrawable || drawable instanceof NinePatchDrawable) {
            return true;
        }
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (containsBitmap(layers.getDrawable(i), depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (drawable instanceof DrawableContainer) {
            // StateListDrawable / LevelListDrawable / AnimatedStateListDrawable
            try {
                return containsBitmap(((DrawableContainer) drawable).getCurrent(), depth + 1);
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (drawable instanceof android.graphics.drawable.InsetDrawable) {
            return containsBitmap(((android.graphics.drawable.InsetDrawable) drawable).getDrawable(),
                    depth + 1);
        }
        if (drawable instanceof android.graphics.drawable.ClipDrawable) {
            return containsBitmap(((android.graphics.drawable.ClipDrawable) drawable).getDrawable(),
                    depth + 1);
        }
        if (drawable instanceof android.graphics.drawable.ScaleDrawable) {
            return containsBitmap(((android.graphics.drawable.ScaleDrawable) drawable).getDrawable(),
                    depth + 1);
        }
        if (drawable instanceof android.graphics.drawable.RotateDrawable) {
            return containsBitmap(((android.graphics.drawable.RotateDrawable) drawable).getDrawable(),
                    depth + 1);
        }
        return false;
    }

    /**
     * 是否为「非 Framework 内置」的自定义 Drawable。
     *
     * <p>厂商卡面经常是一个自定义 Drawable（渐变 + 纹理 + 卡号绘制一体）。
     * 这类对象我们无法安全推断其内部结构，因此一律视为素材、不做替换。</p>
     */
    private static boolean isCustomDrawable(Drawable drawable) {
        if (drawable == null) {
            return false;
        }
        if (drawable instanceof VectorDrawable || drawable instanceof AnimatedVectorDrawable) {
            return true;
        }
        String className = drawable.getClass().getName();
        return !className.startsWith("android.graphics.drawable.");
    }

    // ======================================================================
    // 资源名关键词判定
    // ======================================================================

    /** 视图的 ID 资源名是否带卡面语义。 */
    private static boolean matchesKeyword(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID || id <= 0) {
                return false;
            }
            String name = view.getResources().getResourceEntryName(id);
            if (name == null || name.isEmpty()) {
                return false;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (String keyword : PROTECTED_KEYWORDS) {
                if (lower.contains(keyword)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 资源名解析失败时按「不是卡面」处理，交给类型判定兜底
        }
        return false;
    }
}
