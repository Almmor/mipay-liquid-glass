package com.liquidglass.mipay.glass;

import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 卡面截获器（1.5.7）。
 *
 * <h3>为什么要钩 {@code ImageView#setImageDrawable}</h3>
 *
 * <p>参考 {@code PayCardEditor} 的实现结论：小米智能卡的卡面来自
 * {@code CardInfo.getCardArt() / mCardArt} 这个<b>远程图片 URL</b>，
 * 由应用内嵌的 Glide 下载解码，最终以 {@code BitmapDrawable} 交给
 * {@code ImageView}。也就是说，<b>卡面位图一定会经过这个调用</b>，
 * 在这里截获比事后去 {@code view.draw()} 可靠得多 —— 不依赖布局完成、
 * 不依赖视图可见、也不会错过「图片晚于遍历才解码」的时序。</p>
 *
 * <h3>为什么只钩 {@code setImageDrawable} 一个方法</h3>
 *
 * <p>{@code ImageView} 内部做了归口：{@code setImageBitmap}、
 * {@code setImageResource}、{@code setImageURI}、{@code setImageIcon}
 * 最终都会转调 {@code setImageDrawable}。因此单点钩住它即可覆盖全部路径，
 * 也顺带覆盖 {@code AppCompatImageView} 的覆写（它同样会 {@code super} 下去）。</p>
 *
 * <h3>性能</h3>
 *
 * <p>该钩子在进程内每次设置图片都会触发，所以回调里只做「取位图引用 + 两次
 * 整数比较」；真正要不要采纳、要不要降采样，都交给
 * {@link IslandFaceCache#offerDrawable} 用最廉价的方式判定。</p>
 */
public final class CardFaceGrabber {

    private static final String TAG = "MiPayLiquidGlass/FaceGrab";

    private static boolean installed;

    private CardFaceGrabber() {
    }

    /** 在目标进程安装卡面截获。重复调用无副作用。 */
    public static void install(ClassLoader classLoader) {
        if (installed || classLoader == null) {
            return;
        }
        installed = true;
        installImageViewHook(classLoader);
        installCardInfoHook(classLoader);
        installBackgroundHook(classLoader);
    }

    /**
     * 挂 {@code CardInfo#updateBackground(Context)}（卡面加载入口）。
     *
     * <p>类名来自同机安装的 {@code PayCardEditor} 的 DexKit 解析结果
     * （{@code transitUpdateBackground: Lcom/miui/tsmclient/entity/CardInfo;->updateBackground(Landroid/content/Context;)Ljava/lang/Boolean;}），
     * <b>未被混淆</b>，因此是稳定锚点。</p>
     *
     * <p>这个方法的入参是 {@code CardInfo} 自己，调用后卡片的后台素材就已就位；
     * 此时把 CardInfo 里「卡名 + 位图字段」一起读出来，就能得到<b>带名字的全尺寸卡面</b>
     * —— 比从列表小图标（157x98）拿到的清晰得多。</p>
     */
    private static void installCardInfoHook(ClassLoader classLoader) {
        try {
            Class<?> cardInfo = XposedHelpers.findClassIfExists(
                    "com.miui.tsmclient.entity.CardInfo", classLoader);
            if (cardInfo == null) {
                XposedBridge.log(TAG + ": 找不到 CardInfo，跳过 updateBackground 钩子");
                return;
            }
            XposedHelpers.findAndHookMethod(cardInfo, "updateBackground", android.content.Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                IslandFaceCache.offerCardInfo(param.thisObject);
                                // 顺手记下这张卡的应用通道（AID → TU / CU），
                                // 交易详情页的「交易通道」一栏要用它。
                                TradeDetailSheet.noteCardInfo(param.thisObject);
                            } catch (Throwable ignored) {
                                // 截获失败绝不能影响宿主应用
                            }
                        }
                    });
            XposedBridge.log(TAG + ": 已安装 CardInfo#updateBackground 截获");
            // 再挂 getCardArt()：它返回这张卡的「卡面原始 URL」。
            // 有了 URL 就能自己下高清原图，彻底摆脱「只能拿到 157x98 列表小图」的模糊困局。
            // 方法签名已用 dexdump 从本机安装的 APK 里核实：
            //   Lcom/miui/tsmclient/entity/CardInfo;->getCardArt()Ljava/lang/String;
            XposedHelpers.findAndHookMethod(cardInfo, "getCardArt", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        IslandFaceCache.offerCardArt(param.thisObject,
                                result instanceof String ? (String) result : null);
                    } catch (Throwable ignored) {
                        // 截获失败绝不能影响宿主应用
                    }
                }
            });
            XposedBridge.log(TAG + ": 已安装 CardInfo#getCardArt 截获（卡面原始 URL）");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 安装 CardInfo 截获失败 " + throwable);
        }
    }

    /**
     * 挂 {@code View#setBackground(Drawable)}（1.7.0 新增）。
     *
     * <p>为什么必须补这一条：卡面大图在不少页面上是设成 <b>背景</b> 的，而不是设给
     * {@code ImageView}。此前只钩了 {@code setImageDrawable}，所以始终只能拿到列表里那张
     * 157x98 的小图标 —— 岛上的卡面因此发糊，而 App 页面上同一张卡却是清晰的大图。</p>
     *
     * <p>这个钩子触发极其频繁，因此先用 {@link IslandFaceCache#looksLikeBigBitmap} 做一次
     * 只含 instanceof 与尺寸比较的廉价筛选，绝大多数调用会在这里直接返回。</p>
     */
    private static void installBackgroundHook(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.view.View", classLoader,
                    "setBackground", Drawable.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object drawable = param.args == null || param.args.length == 0
                                        ? null : param.args[0];
                                if (!(drawable instanceof Drawable)
                                        || !IslandFaceCache.looksLikeBigBitmap((Drawable) drawable)) {
                                    return;
                                }
                                IslandFaceCache.offerDrawable((Drawable) drawable,
                                        param.thisObject instanceof android.view.View
                                                ? (android.view.View) param.thisObject : null);
                            } catch (Throwable ignored) {
                                // 截获失败绝不能影响宿主应用
                            }
                        }
                    });
            XposedBridge.log(TAG + ": 已安装 View#setBackground 截获（卡面大图）");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 安装 setBackground 截获失败 " + throwable);
        }
    }

    private static void installImageViewHook(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.widget.ImageView", classLoader,
                    "setImageDrawable", Drawable.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object view = param.thisObject;
                                Object drawable = param.args == null || param.args.length == 0
                                        ? null : param.args[0];
                                IslandFaceCache.offerDrawable(
                                        drawable instanceof Drawable ? (Drawable) drawable : null,
                                        view instanceof ImageView ? (ImageView) view : null);
                            } catch (Throwable ignored) {
                                // 截获失败绝不能影响宿主应用加载图片
                            }
                        }
                    });
            XposedBridge.log(TAG + ": 已安装卡面截获（ImageView#setImageDrawable）");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 安装卡面截获失败 " + throwable);
        }
    }
}
