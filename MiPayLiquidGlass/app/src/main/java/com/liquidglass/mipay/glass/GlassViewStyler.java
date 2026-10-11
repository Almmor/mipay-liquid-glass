package com.liquidglass.mipay.glass;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import com.liquidglass.mipay.Config;
import com.liquidglass.mipay.Utils;

/**
 * 视图树级的液态玻璃改造。
 *
 * <h3>1.0.2 的重要变化：修复「整块玻璃横在页面上」的 UI 错乱</h3>
 *
 * <p>1.0.1 会给所有「图片素材」和白名单容器叠整块玻璃镜面（{@code setForeground}）。
 * 实机截图显示这会制造两类错乱：</p>
 * <ol>
 *     <li>手指层 / 卡堆容器被误判成素材后，一整块玻璃面板横在工具栏与卡面上方，
 *     镜面弧线贯穿整屏；</li>
 *     <li>卡列表项（比卡面本体大一圈）被叠镜面后，玻璃横带盖住卡堆里第二、三张卡的卡面。</li>
 * </ol>
 *
 * <p>根因是「叠加层的 bounds 比卡面本体大」。1.0.2 因此收紧：</p>
 * <ul>
 *     <li><b>玻璃罩只叠在卡面级大图上</b>（ImageView、短边 ≥ 96dp），普通图标、
 *     容器一律不叠；</li>
 *     <li><b>承载卡面的容器整体不动</b>，不再剪枝到容器层就叠加，而是继续向下，
 *     让卡面本体自己叠罩；</li>
 *     <li>叠加前校验尺寸（过高 / 全屏的视图拒绝叠加）。</li>
 * </ul>
 *
 * <h3>处理优先级</h3>
 * <ol>
 *     <li>{@link CardFaceGuard#isNeverTouch} —— 动画与 H5 容器，任何情况下都不碰；</li>
 *     <li>{@link GlassChrome} —— 顶部按钮 / 余额框 / 充值按钮，换玻璃背景，图标与文字保留；</li>
 *     <li>卡面 / 图片素材 —— 仅卡面级大图叠玻璃罩，其余原样保留并剪枝；</li>
 *     <li>承载卡面的容器 —— 不动，继续向下；</li>
 *     <li>精准适配白名单 —— 尺寸达标才叠玻璃罩；</li>
 *     <li>普通卡片 / 按钮 / 面板 —— 按背景启发式做背景替换（1.0.2 起支持 selector 背景）。</li>
 * </ol>
 *
 * <p>为避免「父子各叠一层、边框重影」，同一子树内只允许叠一层镜面
 * （由 {@code overlayConsumed} 标志沿递归向下传递）。</p>
 */
public final class GlassViewStyler {

    /** 视图树递归深度上限，防止极端布局导致栈溢出。 */
    private static final int MAX_DEPTH = 26;
    /** 深度美化模式下，小于该尺寸（dp）的视图视为分割线 / 细节点，不再处理。 */
    private static final float MIN_DEEP_SIZE_DP = 32f;
    /** 需要叠加玻璃镜面的视图，短边至少要有该尺寸（dp），避免给图标描边。 */
    private static final float MIN_OVERLAY_SIZE_DP = 48f;
    /** 卡面级大图的短边下限（dp）：低于它的图片（logo / 图标）不叠玻璃罩。 */
    private static final float MIN_CARD_FACE_SIZE_DP = 96f;
    /** 叠加玻璃罩的视图高度上限（相对屏幕高度）：更高的视图会横穿页面造成错乱。 */
    private static final float MAX_OVERLAY_HEIGHT_RATIO = 0.55f;
    /** 判定「承载卡面的容器」时向下探测的层数。 */
    private static final int CARD_FACE_PROBE_DEPTH = 3;

    private static final String[] CARD_CLASS_NAMES = {
            "androidx.cardview.widget.CardView",
            "com.google.android.material.card.MaterialCardView"
    };

    /** 同一视图树的最小改造间隔，避免多个生命周期回调在短时间内重复遍历。 */
    private static final long MIN_INTERVAL_MS = 260L;
    private static final Map<View, Long> LAST_RUN = new WeakHashMap<>();

    private GlassViewStyler() {
    }

    // ======================================================================
    // 入口
    // ======================================================================

    /** 对 Activity 的整个视图树应用玻璃材质。 */
    public static void style(final Activity activity, final Config cfg) {
        if (activity == null || cfg == null) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (decor == null) {
            return;
        }
        // 记住这棵视图树：刷卡时若内存里还没有卡面，就靠它定向找卡面视图
        // （按类名 / 资源名识别，不要求布局完成或可见）。
        IslandFaceCache.rememberDecor(decor);
        final float density = activity.getResources().getDisplayMetrics().density;
        schedule(decor, decor, density, cfg, 0);
    }

    /**
     * 对任意视图子树应用玻璃材质。
     *
     * <p>用于 Activity / Dialog 之外的窗口形态，例如 {@code PopupWindow} 的内容视图
     * （小米智能卡的选卡弹层、卡详情浮层常用它实现）。</p>
     */
    public static void styleView(final View root, final float density, final Config cfg) {
        if (root == null || cfg == null) {
            return;
        }
        schedule(root, root, density <= 0f ? 3f : density, cfg, 1);
    }

    /**
     * 立即一次 + 延迟一次。
     *
     * <p>部分内容（网络加载的卡面、列表、异步填充的布局）在首帧之后才成型，
     * 因此补一次延迟遍历；改造本身幂等，重复执行不会产生副作用。</p>
     */
    private static void schedule(final View root, final View measureRef,
                                 final float density, final Config cfg, final int startDepth) {
        // 去抖：onCreate / onContentChanged / onResume / onPostResume 会在极短时间内连续触发，
        // 而改造本身是幂等的，因此没有必要重复遍历整棵视图树。
        long now = SystemClock.uptimeMillis();
        synchronized (LAST_RUN) {
            Long last = LAST_RUN.get(root);
            if (last != null && now - last < MIN_INTERVAL_MS) {
                return;
            }
            LAST_RUN.put(root, now);
        }

        Runnable pass = new Runnable() {
            @Override
            public void run() {
                try {
                    styleTree(root, cfg, density,
                            measureRef.getWidth(), measureRef.getHeight(), startDepth, false);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        };
        root.post(pass);
        root.postDelayed(pass, 420L);
    }

    // ======================================================================
    // 遍历
    // ======================================================================

    private static void styleTree(View view, Config cfg, float density,
                                  int decorWidth, int decorHeight, int depth,
                                  boolean overlayConsumed) {
        if (view == null || depth > MAX_DEPTH) {
            return;
        }

        boolean consumed = overlayConsumed;

        if (depth > 0) {
            // ---- 优先级 -1：需要移除的页面提示 ----
            // 直接 GONE 并剪枝，避免再给这些提示做玻璃化。
            if (PageTweaks.shouldHide(view)) {
                PageTweaks.hide(view);
                return;
            }

            boolean dark = Utils.isNightMode(view.getResources());

            // ---- 优先级 0：动画与 H5 容器，任何情况下都不碰 ----
            if (CardFaceGuard.isNeverTouch(view)) {
                return;
            }

            // ---- 优先级 1：界面控件（顶部按钮 / 余额框 / 充值按钮）----
            // 必须先于卡面判定：顶部按钮是 ImageView，否则会被当图片素材跳过。
            if (GlassChrome.matches(view)) {
                applyChrome(view, cfg, density, dark);
                // 继续向下：余额框里的充值按钮各自命中本通道
            } else if (cfg.keepCardFace && CardFaceGuard.isArtwork(view)) {
                // ---- 优先级 2：卡面 / 图片素材 ----
                // 只有「卡面级大图」才叠玻璃罩；logo、图标、整块容器一律原样保留。
                // 这是 1.0.2 修复 UI 错乱的关键：不再给大容器叠整块镜面。
                if (!consumed && isCardFaceSized(view, density, decorHeight)) {
                    // 兜底路径：把这张大图缓存成「真实卡面」，供 SystemUI 侧的刷卡动画使用。
                    // 主路径是 ImageView#setImageDrawable 上的截获（见 CardFaceGrabber），
                    // 这里只在卡面由自定义 View 内部绘制、迟迟等不到 setImageDrawable 时才用得上。
                    IslandFaceCache.captureView(view);
                    consumed = applyOverlay(view, cfg, density, decorWidth, decorHeight, dark, true);
                }
                return; // 剪枝：素材内部一律不动
            } else if (cfg.keepCardFace
                    && CardFaceGuard.hasArtworkDescendant(view, CARD_FACE_PROBE_DEPTH)) {
                // ---- 优先级 3：容器里装着卡面 ----
                // 整体不动（不叠加、不换背景），继续向下让卡面本体自己叠罩，
                // 避免镜面横带盖住卡堆里后面的卡片。
            } else if (cfg.smartTargets && GlassTargets.matches(view)) {
                // ---- 优先级 4：精准适配白名单 ----
                if (!consumed) {
                    consumed = applyOverlay(view, cfg, density, decorWidth, decorHeight, dark);
                }
            } else {
                // ---- 优先级 5：普通卡片 / 按钮 / 面板 ----
                restyle(view, cfg, density, decorWidth, decorHeight, dark);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                styleTree(group.getChildAt(i), cfg, density,
                        decorWidth, decorHeight, depth + 1, consumed);
            }
        }
    }

    // ======================================================================
    // 界面控件（顶部按钮 / 余额区 / 充值按钮）
    // ======================================================================

    /**
     * 界面控件通道（1.0.3 起按控件分路）：
     *
     * <ul>
     *     <li>顶部按钮 —— 圆形玻璃底，图标原样保留；</li>
     *     <li>余额区 —— <b>去掉背景框</b>，文字按 Apple Wallet 排版重设
     *     （小标签 + 大金额），对应「去掉余额背景」的需求；</li>
     *     <li>充值按钮 —— <b>淡蓝色胶囊 + 白字</b>（Apple Pay 式），不再套玻璃；</li>
     *     <li>其余余额 / 充值类带背景面板（卡详情页等）—— 沿用玻璃背景。</li>
     * </ul>
     */
    private static void applyChrome(View view, Config cfg, float density, boolean dark) {
        try {
            if (GlassChrome.isIconButton(view)) {
                // 尚未测量时跳过，等延迟那一次再处理，保证圆角按真实尺寸算成圆形
                if (view.getWidth() <= 0 || view.getHeight() <= 0) {
                    return;
                }
                // 圆形玻璃底：圆角取短边一半
                float radius = Math.min(view.getWidth(), view.getHeight()) * 0.5f;
                view.setBackground(new LiquidGlassDrawable(cfg, radius, density, false, dark));
                clipToOutline(view);
            } else if (GlassChrome.isRechargeButton(view)) {
                // 充值按钮：Apple 蓝胶囊 + 液态玻璃高光，圆角依赖实测高度，未测量时等延迟遍历
                AppleStyle.applyRechargeButton(view, cfg, density, dark);
            } else if (GlassChrome.isBalancePanel(view)) {
                // 余额区：透明液态玻璃底 + Apple Pay 排版（幂等，重复遍历无副作用）
                AppleStyle.applyBalancePanel(view, cfg, density, dark);
            } else {
                // 其余余额 / 充值类面板（卡详情页等）：同样用浅色透明毛玻璃，
                // 避免在深色页面下被压成一块黑。
                AppleStyle.applyPanelGlass(view, cfg, density);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 卡面玻璃罩
    // ======================================================================

    /** 是否达到「卡面级大图」尺寸：只有这样的图才叠玻璃罩。 */
    private static boolean isCardFaceSized(View view, float density, int decorHeight) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        int minCard = Math.round(MIN_CARD_FACE_SIZE_DP * density);
        if (Math.min(width, height) < minCard) {
            return false;
        }
        return !isTooTall(view, decorHeight);
    }

    /**
     * 在保留的卡面之上叠一层玻璃镜面。
     *
     * <p>使用 {@code View#setForeground}，Framework 会在内容与子视图之上绘制它，
     * 因此卡面图案完全不受影响，只是多了一层「玻璃罩」的折射与流光。</p>
     *
     * @return 是否成功叠加（用于向子树传递「本子树已消耗镜面」）
     */
    private static boolean applyOverlay(View view, Config cfg, float density,
                                        int decorWidth, int decorHeight, boolean dark) {
        return applyOverlay(view, cfg, density, decorWidth, decorHeight, dark, false);
    }

    /**
     * @param cardFace 是否为「卡面上的玻璃罩」。卡面圆角以配置的毫米值为准（3.18mm），
     *                 与卡面本身严丝合缝，避免圆角错位产生双层轮廓线。
     */
    private static boolean applyOverlay(View view, Config cfg, float density,
                                        int decorWidth, int decorHeight, boolean dark,
                                        boolean cardFace) {
        if (!cfg.glassOverlay) {
            return true; // 用户明确关闭叠加，视为已消费，避免子树重复尝试
        }
        if (!isLargeEnoughToOverlay(view, density)) {
            return false;
        }
        if (isTooTall(view, decorHeight) || isFullBleed(view, decorWidth, decorHeight)) {
            return false;
        }
        Drawable foreground;
        try {
            foreground = view.getForeground();
        } catch (Throwable ignored) {
            return true;
        }
        if (foreground instanceof LiquidGlassDrawable) {
            return true;
        }
        // 已有自定义前景（如状态选择器）时不动，避免破坏原有效果
        if (foreground != null) {
            return true;
        }
        try {
            float radius = cardFace ? cardOverlayRadius(view, cfg) : resolveRadius(view, density, cfg);
            view.setForeground(new LiquidGlassDrawable(cfg, radius, density, true, dark));
            if (cardFace) {
                // 卡面按 iOS 规格加一层底部阴影（alpha 8%、Y 2pt、blur 8pt），
                // 让卡片在浅灰页面上「托起来」，与无阴影的余额卡片形成 Z 轴层次。
                AppleStyle.applyCardFaceShadow(view, radius, density);
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 卡面玻璃罩的圆角。
     *
     * <p>以配置的毫米值为第一优先（默认 3.18mm = 1/8 英寸，与卡面规格一致），
     * 配置为 0 时才退回自动探测，最后才用默认 dp。这样无论机型密度如何，
     * 玻璃罩圆角都与卡面一致。</p>
     */
    private static float cardOverlayRadius(View view, Config cfg) {
        try {
            float fromMm = Utils.mm(view.getResources(), cfg.cardRadiusMm);
            if (fromMm > 0f) {
                return fromMm;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return RadiusProbe.resolve(view);
    }

    // ======================================================================
    // 普通玻璃化（兜底启发式）
    // ======================================================================

    private static void restyle(View view, Config cfg, float density,
                                int decorWidth, int decorHeight, boolean dark) {
        // 双保险：即便遍历逻辑被改动，也绝不替换卡面素材
        if (cfg.keepCardFace && CardFaceGuard.isArtwork(view)) {
            return;
        }
        if (view instanceof ImageView) {
            return;
        }
        Drawable background = unwrap(view.getBackground());
        if (background instanceof LiquidGlassDrawable) {
            return;
        }

        // 1) CardView / MaterialCardView
        if (isCardView(view)) {
            float radius = readCardRadius(view, density, cfg);
            callSetter(view, "setCardBackgroundColor",
                    Utils.withAlpha(0xFFFFFFFF, cfg.tintAlpha));
            view.setBackground(new LiquidGlassDrawable(cfg, radius, density, false, dark));
            clipToOutline(view);
            return;
        }

        if (background == null) {
            return;
        }

        // 2) 圆角 Shape：设置页、开通页等普通布局的主力场景
        //    1.0.2 起先解包 selector / 容器 Drawable，充值按钮这类状态背景也能命中
        if (background instanceof GradientDrawable) {
            float radius = cornerRadiusOf((GradientDrawable) background);
            if (radius <= 0f && !cfg.deepStyle) {
                return;
            }
            if (isFullBleed(view, decorWidth, decorHeight)) {
                return;
            }
            float finalRadius = radius > 0f ? radius : cfg.cornerRadiusDp * density;
            view.setBackground(new LiquidGlassDrawable(cfg, finalRadius, density, false, dark));
            clipToOutline(view);
            return;
        }

        if (!cfg.deepStyle) {
            return;
        }

        // 3) 深度美化：纯色背景的大块容器
        if (background instanceof ColorDrawable) {
            int color = ((ColorDrawable) background).getColor();
            if (Utils.isOpaque(color) && isLargeEnough(view, density)
                    && !isFullBleed(view, decorWidth, decorHeight)) {
                view.setBackground(new LiquidGlassDrawable(
                        cfg, cfg.cornerRadiusDp * density, density, false, dark));
                clipToOutline(view);
            }
            return;
        }

        // 4) 深度美化：Material 的 MaterialShapeDrawable / RoundRectDrawable
        String name = background.getClass().getName();
        if ((name.contains("MaterialShapeDrawable") || name.contains("RoundRectDrawable"))
                && isLargeEnough(view, density)
                && !isFullBleed(view, decorWidth, decorHeight)) {
            view.setBackground(new LiquidGlassDrawable(
                    cfg, cfg.cornerRadiusDp * density, density, false, dark));
            clipToOutline(view);
        }
    }

    // ======================================================================
    // 辅助判断
    // ======================================================================

    /**
     * 解包 {@code DrawableContainer}（selector / level-list 等）到当前生效的子 Drawable。
     *
     * <p>充值按钮的 {@code swiping_card_recharge_btn_bg} 就是 selector，
     * 不解包就永远匹配不到里面的圆角 Shape。</p>
     */
    private static Drawable unwrap(Drawable drawable) {
        if (drawable instanceof DrawableContainer) {
            try {
                Drawable current = ((DrawableContainer) drawable).getCurrent();
                if (current != null && current != drawable) {
                    return current;
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return drawable;
    }

    private static boolean isCardView(View view) {
        String name = view.getClass().getName();
        for (String candidate : CARD_CLASS_NAMES) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static float readCardRadius(View view, float density, Config cfg) {
        try {
            Method method = view.getClass().getMethod("getRadius");
            Object result = method.invoke(view);
            if (result instanceof Float && (Float) result > 0f) {
                return (Float) result;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return cfg.cornerRadiusDp * density;
    }

    /**
     * 推断视图的圆角：优先自动探测（背景 Shape / Outline / MiuiX 圆角容器 / 父容器），
     * 其次取 CardView 圆角，最后用配置默认值。
     *
     * <p>1.0.3 起接入 {@link RadiusProbe}，因此卡面这类「圆角定义在父容器上」的
     * 视图也能拿到真实圆角，玻璃罩与卡面严丝合缝。</p>
     */
    private static float resolveRadius(View view, float density, Config cfg) {
        float probed = RadiusProbe.resolve(view);
        if (probed > 0f) {
            return probed;
        }
        if (isCardView(view)) {
            return readCardRadius(view, density, cfg);
        }
        return cfg.cornerRadiusDp * density;
    }

    private static float cornerRadiusOf(GradientDrawable drawable) {
        try {
            float radius = drawable.getCornerRadius();
            if (radius > 0f) {
                return radius;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            float[] radii = drawable.getCornerRadii();
            if (radii != null) {
                float max = 0f;
                for (float value : radii) {
                    if (value > max) {
                        max = value;
                    }
                }
                if (max > 0f) {
                    return max;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0f;
    }

    private static boolean isLargeEnough(View view, float density) {
        int minSize = Math.round(MIN_DEEP_SIZE_DP * density);
        return Math.max(view.getWidth(), view.getHeight()) >= minSize;
    }

    private static boolean isLargeEnoughToOverlay(View view, float density) {
        int minSize = Math.round(MIN_OVERLAY_SIZE_DP * density);
        return Math.min(view.getWidth(), view.getHeight()) >= minSize;
    }

    /**
     * 是否「过高」：超过半屏的视图叠镜面会横穿页面（1.0.1 的错乱根因之一）。
     */
    private static boolean isTooTall(View view, int decorHeight) {
        if (decorHeight <= 0) {
            return false;
        }
        return view.getHeight() > decorHeight * MAX_OVERLAY_HEIGHT_RATIO;
    }

    /** 是否占据整屏（页面根背景），这类视图交给沉浸模式处理。 */
    private static boolean isFullBleed(View view, int decorWidth, int decorHeight) {
        if (decorWidth <= 0 || decorHeight <= 0) {
            return false;
        }
        int width = view.getWidth();
        int height = view.getHeight();
        return width >= decorWidth * 0.96f && height >= decorHeight * 0.96f;
    }

    private static void clipToOutline(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                view.setClipToOutline(true);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static void callSetter(View view, String methodName, int value) {
        try {
            Method method = view.getClass().getMethod(methodName, int.class);
            method.invoke(view, value);
        } catch (Throwable ignored) {
            // 目标类没有该方法时忽略
        }
    }
}
