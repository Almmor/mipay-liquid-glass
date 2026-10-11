package com.liquidglass.mipay;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.PopupWindow;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.liquidglass.mipay.glass.GlassViewStyler;
import com.liquidglass.mipay.glass.GlassWindowStyler;

/**
 * 模块入口：为小米智能卡 / 小米钱包注入液态玻璃材质。
 *
 * <p>设计要点：</p>
 * <ol>
 *     <li><b>不依赖任何具体类名</b>。小米智能卡内部类名会随版本变化，因此这里只 Hook
 *     Framework 层的 {@code Activity} / {@code Dialog} / {@code PopupWindow}，
 *     再基于「背景 Drawable 的类型与圆角」这一通用特征识别卡片与按钮。
 *     这既保证了跨版本稳定性，也避免了触碰支付业务逻辑。</li>
 *     <li><b>尽可能覆盖所有页面形态</b>。Activity 页、Dialog 弹窗、PopupWindow 浮层
 *     三条路径全部接管，覆盖刷卡页、选卡页、卡详情、支付确认与 NFC 提示。</li>
 *     <li><b>保留设备卡面</b>。图片素材与厂商卡面由 {@code CardFaceGuard} 识别后整体跳过，
 *     只在最上层叠一层玻璃镜面。</li>
 *     <li><b>只做视觉改动</b>。全部操作局限于 {@code setBackground} / {@code setForeground}、
 *     窗口模糊半径、系统栏颜色，不读取、不修改任何支付数据，也不改变点击与验证流程。</li>
 *     <li><b>异常安全</b>。所有 Hook 回调都包裹在 try/catch 中，任何失败都只会导致
 *     效果缺失，而不会让应用崩溃。</li>
 * </ol>
 */
public class LiquidGlassModule implements IXposedHookLoadPackage {

    private static final String TAG = "MiPayLiquidGlass";

    /** 小米智能卡。 */
    public static final String PKG_SMART_CARD = "com.miui.tsmclient";
    /** 小米钱包（Mi Pay 刷卡 / 付款码入口）。 */
    public static final String PKG_MIPAY_WALLET = "com.mipay.wallet";
    /**
     * 小米智能卡网页组件。
     *
     * <p>反编译确认它是<b>插件式伴生包</b>：dex 里放着 {@code com.miui.tsmclient.ui.inapp.TagPayActivity}、
     * {@code ui.scanpay.ScanPayActivity} 等主包命名空间的类，同时自己提供 H5 能力
     * （{@code miuipub.hybrid.HybridView} 与 {@code com.miui.nextpay.hybrid.HybridActivity}）。
     * 它是<b>独立进程</b>，1.0.0 未纳入作用域，导致这些页面完全没有被处理。</p>
     */
    public static final String PKG_NEXTPAY = "com.miui.nextpay";

    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            PKG_SMART_CARD,
            PKG_MIPAY_WALLET,
            PKG_NEXTPAY));

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        final Config config = XposedPrefs.load();
        log("loaded in " + lpparam.packageName + " -> " + config);

        if (!config.enable) {
            log("已由用户在设置中关闭，跳过注入");
            return;
        }

        try {
            hookActivity(config);
            hookDialog(config);
            hookPopupWindow(config);
            log("hook 安装完成");
        } catch (Throwable throwable) {
            log("hook 安装失败: " + throwable);
        }
    }

    // ======================================================================
    // Activity：窗口模糊 + 视图树玻璃化
    // ======================================================================

    private void hookActivity(final Config config) {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        guard(new Runnable() {
                            @Override
                            public void run() {
                                GlassWindowStyler.apply((Activity) param.thisObject, config);
                            }
                        });
                    }
                });

        XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        guard(new Runnable() {
                            @Override
                            public void run() {
                                applyAll((Activity) param.thisObject, config);
                            }
                        });
                    }
                });

        XposedHelpers.findAndHookMethod(Activity.class, "onContentChanged",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        guard(new Runnable() {
                            @Override
                            public void run() {
                                GlassViewStyler.style((Activity) param.thisObject, config);
                            }
                        });
                    }
                });

        // onPostResume 在 Fragment 事务执行完毕后触发，
        // 对「ViewPager + Fragment」这类页面结构是最合适的补样式时机。
        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        guard(new Runnable() {
                            @Override
                            public void run() {
                                GlassViewStyler.style((Activity) param.thisObject, config);
                            }
                        });
                    }
                });
    }

    private static void applyAll(Activity activity, Config config) {
        GlassWindowStyler.apply(activity, config);
        GlassViewStyler.style(activity, config);
    }

    // ======================================================================
    // Dialog：支付确认弹窗、选卡弹窗、NFC 提示弹窗
    // ======================================================================

    private void hookDialog(final Config config) {
        XposedHelpers.findAndHookMethod(Dialog.class, "show",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        guard(new Runnable() {
                            @Override
                            public void run() {
                                GlassWindowStyler.applyToDialog((Dialog) param.thisObject, config);
                            }
                        });
                    }
                });
    }

    // ======================================================================
    // PopupWindow：卡种切换浮层、卡详情气泡
    // ======================================================================

    private void hookPopupWindow(final Config config) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    PopupWindow popup = (PopupWindow) param.thisObject;
                    View content = popup.getContentView();
                    if (content == null) {
                        return;
                    }
                    float density = content.getResources().getDisplayMetrics().density;
                    GlassViewStyler.styleView(content, density, config);
                } catch (Throwable throwable) {
                    log("PopupWindow 样式应用失败: " + throwable);
                }
            }
        };

        hookSafely(PopupWindow.class, "showAtLocation",
                new Class<?>[]{View.class, int.class, int.class, int.class}, hook);
        hookSafely(PopupWindow.class, "showAsDropDown",
                new Class<?>[]{View.class, int.class, int.class, int.class}, hook);
        hookSafely(PopupWindow.class, "showAsDropDown",
                new Class<?>[]{View.class}, hook);
    }

    // ======================================================================

    /** 兼容不同签名重载的安全 Hook：方法不存在时静默跳过，不影响其余 Hook。 */
    private static void hookSafely(Class<?> clazz, String methodName,
                                   Class<?>[] parameterTypes, XC_MethodHook callback) {
        try {
            Object[] args = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
            args[parameterTypes.length] = callback;
            XposedHelpers.findAndHookMethod(clazz, methodName, args);
        } catch (Throwable throwable) {
            log("跳过 Hook " + clazz.getSimpleName() + "#" + methodName + ": " + throwable);
        }
    }

    /** 所有 Hook 回调统一走这里，确保不会把异常抛回宿主应用。 */
    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            log("样式应用失败: " + throwable);
        }
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
            // 不在 Hook 进程中时（例如方法被静态分析）忽略
        }
    }
}
