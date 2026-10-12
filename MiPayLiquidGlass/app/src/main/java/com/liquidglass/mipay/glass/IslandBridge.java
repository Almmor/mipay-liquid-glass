package com.liquidglass.mipay.glass;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

/**
 * 刷卡动画的跨进程通道（1.4.0，方案 B；1.5.6 起携带真实卡面）。
 *
 * <p>动画由 <b>SystemUI 进程</b>绘制（只有它能盖在锁屏之上），
 * 而「刷卡发生了」这件事是在 <b>小米智能卡进程</b>里被我们钩到的
 * （{@code IslandStyler} 已经能看到焦点通知）。
 * 两个进程之间用一条显式广播传递数据。</p>
 *
 * <p>1.5.6 起广播额外携带 {@link #EXTRA_FACE}：由 {@link IslandFaceCache}
 * 在小米智能卡进程里抓取的真实卡面位图（最长边 ≤ 384px，约 300KB，
 * 小于 Binder 事务 1MB 上限）。SystemUI 侧拿不到 App 的视图树，
 * 只能靠这种方式把卡面「运过去」。</p>
 *
 * <p>广播名带模块包名前缀、并且只发给 {@code com.android.systemui}，
 * 目的是尽量缩小可被外部触发面（即便被触发，也只是播一段动画，无副作用）。</p>
 */
public final class IslandBridge {

    /** 触发/接收刷卡动画的广播 action。 */
    public static final String ACTION = "com.liquidglass.mipay.action.ISLAND_SWIPE";

    public static final String EXTRA_AMOUNT = "amount";
    public static final String EXTRA_BALANCE = "balance";
    public static final String EXTRA_SUCCESS = "success";
    /** 真实卡面位图（可空）。 */
    public static final String EXTRA_FACE = "face";

    /** SystemUI 包名。 */
    public static final String PKG_SYSTEM_UI = "com.android.systemui";

    /**
     * 用户在设置页切换后的「指定卡面」覆盖值。
     *
     * <p>被 Hook 的进程只在启动时读一次配置，因此设置页切完卡面后会发一条广播
     * 把新值推到这里；{@code null} 表示「还没被推送过，用传入的配置值」。</p>
     */
    private static volatile String pickedNameOverride;

    /** 取卡面可能要走磁盘 IO，因此放到单线程池里跑，绝不阻塞调用方（刷卡的 UI 线程）。 */
    private static final java.util.concurrent.ExecutorService FACE_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "lg-island-face");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private IslandBridge() {
    }

    /** 设置页切换卡面后调用；传空字符串表示「自动」。 */
    public static void setPickedName(String name) {
        pickedNameOverride = name == null ? "" : name;
    }

    /**
     * 由小米智能卡进程调用：通知 SystemUI 播放一次刷卡动画。
     *
     * <p>卡面<b>用时才取</b>：优先用用户在设置里指定的卡名对应的卡面，
     * 否则跟随 App 最近显示过的卡面，再退回磁盘缓存。</p>
     *
     * @param preferredFaceName 用户指定的卡名，空表示自动
     */
    public static void send(Context context, String amount, String balance, boolean success,
                            String preferredFaceName) {
        if (context == null) {
            return;
        }
        final Context appContext;
        try {
            appContext = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
        } catch (Throwable ignored) {
            return;
        }
        final String finalAmount = amount;
        final String finalBalance = balance;
        final boolean finalSuccess = success;
        // 设置页推送过就以推送值为准（进程启动时读到的配置可能已经过期）
        String override = pickedNameOverride;
        final String finalName = override != null ? override
                : (preferredFaceName == null ? "" : preferredFaceName);
        FACE_IO.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap face = null;
                try {
                    face = IslandFaceCache.resolve(appContext, finalName);
                } catch (Throwable ignored) {
                    // 拿不到就用示例卡，不影响动画
                }
                // 顺手把识别结果落盘：小米智能卡进程随时可能被杀，
                // 落盘后下次进程启动也能直接取用正确的卡面，不必退回盲猜。
                try {
                    if (IslandFaceCache.hasUnsavedCaptures()) {
                        IslandFaceCache.persist(appContext);
                    }
                    IslandFaceCache.persistUrls(appContext);
                } catch (Throwable ignored) {
                    // 落盘失败不影响动画
                }
                dispatch(appContext, finalAmount, finalBalance, finalSuccess, face);
            }
        });
    }

    /**
     * 由小米智能卡进程调用（显式指定卡面，跳过取卡逻辑）。
     *
     * @param face 真实卡面位图，可为 null（此时 SystemUI 侧用示例卡兜底）
     */
    public static void send(Context context, String amount, String balance, boolean success,
                            Bitmap face) {
        dispatch(context, amount, balance, success, face);
    }

    private static void dispatch(Context context, String amount, String balance, boolean success,
                                 Bitmap face) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(ACTION);
            intent.setPackage(PKG_SYSTEM_UI);
            intent.putExtra(EXTRA_AMOUNT, amount);
            intent.putExtra(EXTRA_BALANCE, balance);
            intent.putExtra(EXTRA_SUCCESS, success);
            if (face != null && !face.isRecycled()) {
                intent.putExtra(EXTRA_FACE, face);
            }
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
            // 发送失败只意味着动画不播，不影响刷卡
        }
    }
}
