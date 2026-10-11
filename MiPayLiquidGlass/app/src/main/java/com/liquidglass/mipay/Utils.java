package com.liquidglass.mipay;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.TypedValue;

/** 通用工具方法。 */
public final class Utils {

    private Utils() {
    }

    /** 是否处于深色模式（跟随系统 uiMode）。异常时按浅色处理。 */
    public static boolean isNightMode(Resources resources) {
        try {
            int mode = resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** dp 转 px。 */
    public static float dp(Context context, float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    /** dp 转 px（取整）。 */
    public static int dpi(Context context, float dp) {
        return Math.round(dp(context, dp));
    }

    /**
     * 毫米转 px。
     *
     * <p>卡面圆角等规格常以毫米给出（如 3.18mm = 1/8 英寸），
     * 这里按屏幕物理宽度 {@code xdpi} 精确换算，保证不同机型上圆角一致。</p>
     */
    public static float mm(Resources resources, float mm) {
        try {
            float xdpi = resources.getDisplayMetrics().xdpi;
            if (xdpi <= 0f) {
                xdpi = 160f;
            }
            return mm * xdpi / 25.4f;
        } catch (Throwable ignored) {
            // 兜底：按 160dpi（1dp = 1/160 英寸）估算
            return mm * 6.3f;
        }
    }

    /** 保持 RGB 通道不变，替换 alpha。 */
    public static int withAlpha(int color, int alpha) {
        int a = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** 判断颜色是否完全不透明。 */
    public static boolean isOpaque(int color) {
        return ((color >>> 24) & 0xFF) == 0xFF;
    }

    /** 判断颜色是否完全透明。 */
    public static boolean isTransparent(int color) {
        return ((color >>> 24) & 0xFF) == 0x00;
    }
}
