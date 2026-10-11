package com.liquidglass.mipay.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 卡面候选导出（诊断用，1.5.9）。
 *
 * <h3>它解决什么问题</h3>
 *
 * <p>磁盘兜底只能看「图片本身」，因此无法区分卡面与卡片列表背景图 ——
 * 这正是 1.5.8 选错的根因。继续猜阈值没有意义，正确做法是把缓存里的候选
 * <b>摆出来看一眼</b>，确认哪张才是真卡面，再把规则写死。</p>
 *
 * <h3>怎么用</h3>
 *
 * <p>在小米智能卡进程里注册一个可被外部触发的广播，收到后把候选图片
 * 原样复制到 {@code /sdcard/Android/data/com.miui.tsmclient/files/facecheck/}，
 * 文件名前缀带尺寸，拉出来即可核对：</p>
 *
 * <pre>
 * adb shell am broadcast -a com.liquidglass.mipay.action.EXPORT_FACES \
 *     -n com.miui.tsmclient/  # 仅指定包，动态注册的接收器即可收到
 * adb pull /sdcard/Android/data/com.miui.tsmclient/files/facecheck ./facecheck
 * </pre>
 *
 * <p>导出是只读的（只复制缓存文件），不影响刷卡，也不会改动任何业务数据。</p>
 */
public final class FaceExportDiagnostic {

    private static final String TAG = "MiPayLiquidGlass/FaceDump";

    /** 触发导出的广播 action。 */
    public static final String ACTION = "com.liquidglass.mipay.action.EXPORT_FACES";

    /** 设置页查询「已识别到的卡名」的 action。 */
    public static final String ACTION_QUERY_NAMES = "com.liquidglass.mipay.action.QUERY_FACE_NAMES";
    /** 小米智能卡回复「已识别到的卡名」的 action。 */
    public static final String ACTION_NAMES_RESULT = "com.liquidglass.mipay.action.FACE_NAMES_RESULT";
    /** 设置页通知「配置变了，请重新读一次」的 action。 */
    public static final String ACTION_RELOAD_CONFIG = "com.liquidglass.mipay.action.RELOAD_CONFIG";
    /** 结果里的卡名列表（{@code ArrayList<String>}）。 */
    public static final String EXTRA_NAMES = "names";
    /** 与卡名一一对应的缩略图（{@code ArrayList<byte[]>}，JPEG 字节）。 */
    public static final String EXTRA_THUMBS = "thumbs";

    private static final String PKG_MODULE = "com.liquidglass.mipay";

    private static boolean installed;
    private static boolean registered;

    private FaceExportDiagnostic() {
    }

    /** 在目标进程安装：拿到 Context 后注册导出接收器。 */
    public static void install(ClassLoader classLoader) {
        if (installed || classLoader == null) {
            return;
        }
        installed = true;
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", classLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object self = param.thisObject;
                                if (self instanceof Context) {
                                    register(((Context) self).getApplicationContext());
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 安装导出入口失败 " + throwable);
        }
    }

    private static void register(final Context context) {
        if (context == null || registered) {
            return;
        }
        registered = true;
        try {
            IntentFilter filter = new IntentFilter(ACTION);
            filter.addAction(ACTION_QUERY_NAMES);
            filter.addAction(ACTION_RELOAD_CONFIG);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent == null ? null : intent.getAction();
                        if (ACTION_QUERY_NAMES.equals(action)) {
                            replyNames(context);
                            return;
                        }
                        if (ACTION_RELOAD_CONFIG.equals(action)) {
                            reloadPickedName();
                            return;
                        }
                        XposedBridge.log(TAG + ": 收到候引导出请求");
                        IslandFaceCache.dumpCandidates(context);
                    } catch (Throwable throwable) {
                        XposedBridge.log(TAG + ": 处理广播异常 " + throwable);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            XposedBridge.log(TAG + ": 已注册候引导出接收器（" + ACTION + "）");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 注册导出接收器失败 " + throwable);
        }
    }

    /**
     * 重新读一次模块配置里的「指定卡面」。
     *
     * <p>被 Hook 的进程只在启动时读一次配置，所以用户在设置页切换卡面后，
     * 需要这条广播把新值推进来，否则「切换了却没生效」。</p>
     */
    private static void reloadPickedName() {
        try {
            com.liquidglass.mipay.Config fresh = com.liquidglass.mipay.XposedPrefs.load();
            IslandBridge.setPickedName(fresh.facePickName);
            XposedBridge.log(TAG + ": 已重载指定卡面 -> 「" + fresh.facePickName + "」");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 重载配置失败 " + throwable);
        }
    }

    /**
     * 把已捕获的卡名回给设置页。
     *
     * <p>卡名只能在这里（小米智能卡进程）拿到，模块自己的设置页读不到别的应用
     * 私有数据，所以用一条定向广播把纯文本的卡名列表送过去。</p>
     *
     * <p><b>空列表不回复。</b>模块会同时注入 {@code com.miui.tsmclient} 与
     * {@code com.miui.tsmclient:daemon} 两个进程，两边都会注册本接收器，
     * 而卡面只在主进程被捕获。若辅进程也回一个空列表，设置页会在好数据之后
     * 被清空（实测两次回复只差 2ms）—— 于是「选择卡面」里什么都没有。</p>
     */
    private static void replyNames(Context context) {
        try {
            java.util.ArrayList<String> names = IslandFaceCache.capturedNames();
            if (names.isEmpty()) {
                XposedBridge.log(TAG + ": 本进程暂无已捕获卡名，不回复（避免清空设置页列表）");
                return;
            }
            Intent reply = new Intent(ACTION_NAMES_RESULT);
            reply.setPackage(PKG_MODULE);
            reply.putStringArrayListExtra(EXTRA_NAMES, names);
            // 同时带上缩略图，设置页才能「看图选卡面」（每张约 3~4KB）
            reply.putExtra(EXTRA_THUMBS, IslandFaceCache.capturedThumbnails());
            context.sendBroadcast(reply);
            XposedBridge.log(TAG + ": 已回复卡名列表（" + names.size() + " 项，含缩略图）");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 回复卡名列表失败 " + throwable);
        }
    }
}
