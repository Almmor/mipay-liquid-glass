package com.liquidglass.mipay.glass;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.liquidglass.mipay.Config;
import com.liquidglass.mipay.Utils;

/**
 * 窗口级液态玻璃处理。
 *
 * <p>核心是启用 Android 12（API 31）的「窗口背景模糊」：
 * {@code Window#setBackgroundBlurRadius} 会把窗口背后的内容做高斯模糊，再作为窗口背景绘制出来，
 * 而窗口自身的背景 Drawable 决定了模糊区域的轮廓。我们把窗口背景替换为
 * {@link LiquidGlassDrawable}，于是「模糊 + 玻璃渐变 + 边缘高光」三件事一次完成。</p>
 *
 * <p>该方案对弹窗、确认页、NFC 提示等天然透明背景的窗口效果最明显；对自带不透明背景的
 * 页面主体则完全无副作用（因为内容把模糊完全遮住了）。</p>
 */
public final class GlassWindowStyler {

    private GlassWindowStyler() {
    }

    /** 处理 Activity 窗口。 */
    public static void apply(Activity activity, Config cfg) {
        if (activity == null || cfg == null) {
            return;
        }
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            // Activity 窗口背景很可能正是 App 自己画页面渐变的地方（android:windowBackground）。
            // 只有在「沉浸模式」下才替换它，否则会把 App 的页面底色盖成一块深色玻璃，
            // 页面里所有透明区域（例如去掉背景的余额区）都会跟着变黑。
            applyToWindow(window, activity, cfg, cfg.immersive);
            if (cfg.immersive) {
                applyImmersive(activity, cfg);
            }
        } catch (Throwable ignored) {
            // 任何异常都不应该影响被 Hook 的应用
        }
    }

    /** 处理 Dialog 窗口（支付确认弹窗、NFC 选择弹窗等）。 */
    public static void applyToDialog(Dialog dialog, Config cfg) {
        if (dialog == null || cfg == null) {
            return;
        }
        try {
            Window window = dialog.getWindow();
            if (window == null) {
                return;
            }
            Context context = dialog.getContext();
            // 弹窗窗口天然透明，需要玻璃背景来出效果，因此这里保留替换。
            applyToWindow(window, context, cfg, true);
            if (cfg.immersive) {
                applyImmersiveWindow(window);
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 窗口级玻璃处理，适用于 Activity 与 Dialog（默认不替换窗口背景）。 */
    public static void applyToWindow(Window window, Context context, Config cfg) {
        applyToWindow(window, context, cfg, false);
    }

    /**
     * 窗口级玻璃处理。
     *
     * @param replaceBackground 是否把窗口背景替换为玻璃材质。
     *                          <p>Activity 默认传 {@code false}：多数页面用
     *                          {@code android:windowBackground} 画整页渐变，替换后会
     *                          把渐变盖成深色玻璃，透明区域（如去掉背景的余额区）随之变黑。
     *                          Dialog 传 {@code true}：弹窗原本透明，需要玻璃背景出效果。</p>
     */
    public static void applyToWindow(Window window, Context context, Config cfg,
                                     boolean replaceBackground) {
        enableBackgroundBlur(window, context, cfg);
        window.setDimAmount(0f);

        if (!replaceBackground) {
            return;
        }
        View decor = window.getDecorView();
        if (decor != null) {
            Drawable current = decor.getBackground();
            if (!(current instanceof LiquidGlassDrawable)) {
                float density = context.getResources().getDisplayMetrics().density;
                boolean dark = Utils.isNightMode(context.getResources());
                window.setBackgroundDrawable(new LiquidGlassDrawable(cfg, 0f, density, false, dark));
            }
        }
    }

    // ======================================================================
    // 背景模糊
    // ======================================================================

    private static void enableBackgroundBlur(Window window, Context context, Config cfg) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        int blurPx = Utils.dpi(context, cfg.blurRadiusDp);
        try {
            WindowManager.LayoutParams params = window.getAttributes();
            params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            params.setBlurBehindRadius(blurPx);
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            window.setBackgroundBlurRadius(blurPx);
        } catch (Throwable ignored) {
            // 某些 ROM 未开启 ro.surface_flinger.supports_background_blur，此时静默降级
        }
    }

    // ======================================================================
    // 沉浸模式：透明系统栏 + 根背景半透明，使被模糊的上层内容透出来
    // ======================================================================

    private static void applyImmersive(Activity activity, Config cfg) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        applyImmersiveWindow(window);

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        for (int i = 0; i < content.getChildCount(); i++) {
            makeTranslucent(content.getChildAt(i));
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyImmersiveWindow(Window window) {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(Color.TRANSPARENT);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setStatusBarContrastEnforced(false);
                window.setNavigationBarContrastEnforced(false);
            }

            View decor = window.getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_DEFAULT);
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 把整块不透明背景改为同色半透明，让窗口模糊透出来。 */
    private static void makeTranslucent(View view) {
        if (view == null) {
            return;
        }
        try {
            Drawable bg = view.getBackground();
            int color = opaqueColorOf(bg);
            if (color == 0) {
                return;
            }
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setColor(Utils.withAlpha(color, 172));
            view.setBackground(drawable);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 取出 Drawable 的不透明颜色；非不透明返回 0。 */
    private static int opaqueColorOf(Drawable drawable) {
        try {
            if (drawable instanceof ColorDrawable) {
                int color = ((ColorDrawable) drawable).getColor();
                return Utils.isOpaque(color) ? color : 0;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && drawable instanceof GradientDrawable) {
                android.content.res.ColorStateList csl =
                        ((GradientDrawable) drawable).getColor();
                if (csl != null) {
                    int color = csl.getDefaultColor();
                    return Utils.isOpaque(color) ? color : 0;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0;
    }
}
