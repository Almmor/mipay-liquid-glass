package com.liquidglass.mipay;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.liquidglass.mipay.glass.IslandOverlay;

/**
 * 刷卡动画预览（1.5.0）。
 *
 * <p>这是一条<b>不依赖 SystemUI</b> 的验证通道：动画在本模块自己的进程里播放，
 * 因此一定能看到。它的作用是先把变量拆开 ——</p>
 *
 * <ul>
 *     <li>预览能播 → 动画代码本身没问题，问题在「SystemUI 侧路径」（作用域 / 窗口 / 触发）；</li>
 *     <li>预览也播不出来 → 问题在动画视图自身，与 SystemUI 无关。</li>
 * </ul>
 *
 * <p>窗口使用半透明主题，动画结束后自行 {@code finish()}。</p>
 */
public class PreviewActivity extends Activity {

    public static final String EXTRA_SUCCESS = "preview_success";
    public static final String EXTRA_AMOUNT = "preview_amount";
    public static final String EXTRA_BALANCE = "preview_balance";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        } catch (Throwable ignored) {
            // ignore
        }

        boolean success = getIntent().getBooleanExtra(EXTRA_SUCCESS, true);
        String amount = getIntent().getStringExtra(EXTRA_AMOUNT);
        String balance = getIntent().getStringExtra(EXTRA_BALANCE);

        final IslandOverlay overlay = new IslandOverlay(this,
                amount == null ? "¥2.00" : amount,
                balance == null ? "余额 ¥128.50" : balance,
                success, null);
        overlay.setOnFinish(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = statusBarHeight() + dp(8);
        root.addView(overlay, params);
        setContentView(root);
    }

    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                return getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return dp(28);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
