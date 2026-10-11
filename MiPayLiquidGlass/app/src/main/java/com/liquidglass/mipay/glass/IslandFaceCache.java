package com.liquidglass.mipay.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 真实卡面的获取与缓存（1.5.6 起，1.5.9 改为多路策略 + 可信度分级）。
 *
 * <h3>问题</h3>
 *
 * <p>岛动画在 <b>SystemUI 进程</b>绘制，而真实卡面在 <b>小米智能卡进程</b>里，
 * 两边隔着进程边界，动画侧拿不到卡面素材。</p>
 *
 * <h3>前几版的失败与教训</h3>
 *
 * <ul>
 *     <li>1.5.6 靠 {@code view.draw()}：依赖布局完成 + 视图可见，拿不到；</li>
 *     <li>1.5.7 靠 {@code ImageView#setImageDrawable} 截获：界面没渲染过就没有；</li>
 *     <li>1.5.8 改为扫 Glide 磁盘缓存：终于能拿到 —— 但实机日志
 *     （{@code 2026-10-12T00_54}）显示选中了 {@code 1439x908}、比例 1.585 的图，
 *     <b>比例完美却不是卡面</b>。结论：<b>只靠宽高比无法区分卡面与卡片列表背景图</b>
 *     （后者同样是卡片比例的高清大图）。</li>
 * </ul>
 *
 * <h3>1.5.9：多路策略，按可信度取用</h3>
 *
 * <p>既然单看图片本身无法分辨，就必须引入<b>图片之外的证据</b>：
 * 这张图是从哪儿来的。因此把获取拆成三条策略，并按可信度排序，
 * 高可信度一旦拿到就<b>不会被低可信度覆盖</b>：</p>
 *
 * <ol>
 *     <li><b>【高】定向取屏幕上的卡面视图</b>（{@link #resolveFromViewTree()}）：
 *     从最后一次出现的页面视图树里，按<b>类名</b>（{@code ui.widget.CardView}
 *     ——反编译确认它就是卡面本体）或<b>资源名关键词</b>找到卡面视图，
 *     直接取它的 Drawable 位图。不看布局、不看是否可见，因此比
 *     {@code view.draw()} 稳得多；</li>
 *     <li><b>【高】图片加载截获</b>（{@link #offerDrawable}，来自
 *     {@link CardFaceGrabber}）：同样要求「来源视图被识别为卡面视图」，
 *     识别不出只当低可信度；</li>
 *     <li><b>【低】Glide 磁盘缓存扫描</b>（{@link #loadBestFromDiskCache}）：
 *     仅当上面两条都拿不到时才用。按 {@code PayCardEditor} 的结论，
 *     卡面落盘在 {@code <cacheDir>/image_manager_disk_cache/}；由于无法分辨
 *     卡面与列表背景图，这一路只作为兜底，并且<b>不会覆盖高可信度结果</b>。</li>
 * </ol>
 *
 * <p>每次选型都会把候选明细打进日志（文件名、尺寸、比例、修改时间），
 * 便于下一次据此调阈值 —— 而不是继续猜。</p>
 */
final class IslandFaceCache {

    private static final String TAG = "MiPayLiquidGlass/Face";

    /** 广播里允许携带的最大像素量：最长边超过它的卡面会被等比缩小。 */
    private static final int MAX_EDGE_PX = 384;
    /** ISO 卡片宽高比（85.60 / 53.98）—— 用来判断候选图「像不像卡面」。 */
    private static final float ISO_RATIO = 1.586f;
    /** 宽松宽高比范围：磁盘兜底用（横幅、二维码、图标都不在此区间）。 */
    private static final float LOOSE_MIN_RATIO = 1.25f;
    private static final float LOOSE_MAX_RATIO = 2.40f;
    /** 严格宽高比范围：磁盘兜底优先在这一档里挑，挑不到才放宽。 */
    private static final float TIGHT_MIN_RATIO = 1.42f;
    private static final float TIGHT_MAX_RATIO = 1.78f;
    /** 判定为「比例很标准」的容差，用于在多个候选中择优。 */
    private static final float IDEAL_RATIO_TOLERANCE = 0.12f;
    /** 卡面候选的最短边下限（px）：低于它的多半是 logo / 图标。 */
    private static final int MIN_SHORT_EDGE_PX = 180;
    /** 低于这个短边就认为「不够清晰」，需要用磁盘里的高清图做匹配升级。 */
    private static final int HIGH_RES_EDGE_PX = 300;
    /**
     * 是否启用「用磁盘高清图替换低清缩略图」。
     *
     * <p><b>实测为 false。</b>平均色签名对「列表缩略图 ↔ 磁盘大图」的区分度不够，
     * 会把卡面匹配错（详见 {@link #upgradeFromDisk} 里的记录）。
     * 宁可要正确但略糊的卡面，也不要清晰但错卡的卡面。</p>
     */
    private static final boolean ENABLE_DISK_MATCH = false;
    /** 视图树兜底路径的节流间隔。 */
    private static final long VIEW_CAPTURE_INTERVAL_MS = 600L;
    /** 单次磁盘扫描最多检查多少个文件（按修改时间从新到旧），兜住极端大的缓存目录。 */
    private static final int MAX_SCAN_FILES = 120;
    /** 磁盘扫描结果的有效期：这段时间内不重复扫。 */
    private static final long DISK_SCAN_TTL_MS = 60_000L;
    /** 视图树递归深度上限。 */
    private static final int MAX_TREE_DEPTH = 32;
    /** 日志里最多列举几个磁盘候选。 */
    private static final int MAX_LOGGED_CANDIDATES = 6;
    /** 送给设置页的卡面缩略图宽度（px）。 */
    private static final int THUMBNAIL_WIDTH_PX = 96;
    /** 诊断导出最多复制多少张候选图。 */
    private static final int MAX_DUMP_FILES = 60;

    /** 可信度：无。 */
    private static final int CONFIDENCE_NONE = 0;
    /** 可信度：低 —— 只凭图片本身（比例 / 尺寸）判定，可能误取列表背景图。 */
    private static final int CONFIDENCE_LOW = 1;
    /** 可信度：高 —— 来源视图被识别为卡面视图，基本可以确定就是这张卡。 */
    private static final int CONFIDENCE_HIGH = 2;

    /**
     * 反编译确认的卡面本体类名。
     *
     * <p>{@code PayCardEditor} 的 {@code CardClassAccessors} 与
     * {@code GlassTargets} 的注释一致：小米智能卡的卡面是
     * {@code ui.widget.CardView}（{@code AppCompatImageView} 子类），
     * 类名没有被混淆，是稳定锚点。</p>
     */
    private static final String CARD_FACE_CLASS = "com.miui.tsmclient.ui.widget.CardView";

    /**
     * Glide 默认磁盘缓存目录名。
     *
     * <p>来自 {@code PayCardEditor} 的实测结论（{@code ImageCacheHookRegistrar} 注释）：
     * {@code /cache/image_manager_disk_cache/<safeKey>.0}。</p>
     */
    private static final String[] CACHE_DIR_NAMES = {
            "image_manager_disk_cache",
            "image_cache"
    };

    /** 除缓存目录外，再顺手看看应用私有目录 —— 部分版本把卡面素材放在这里。 */
    private static final String[] EXTRA_DIR_NAMES = {
            "cardface", "card_face", "cardart", "card_art"
    };

    private static final Object LOCK = new Object();

    private static Bitmap cached;
    /** 当前缓存的可信度，见 {@link #CONFIDENCE_HIGH} / {@link #CONFIDENCE_LOW}。 */
    private static int cachedConfidence = CONFIDENCE_NONE;
    private static long lastViewCaptureAt;
    /** 上次磁盘扫描的时间与结果，避免短时间内反复扫盘。 */
    private static long lastDiskScanAt;
    private static boolean diskScanFoundNothing;

    /** 最后一次出现的页面根视图（弱引用，不会拖住 Activity）。 */
    private static WeakReference<View> lastDecor = new WeakReference<>(null);

    /** 按卡名保存的卡面（卡名 → 位图），卡名从 App 自己的卡片列表条目里读取。 */
    private static final java.util.LinkedHashMap<String, Bitmap> facesByName =
            new java.util.LinkedHashMap<>();
    /** 自动模式下使用的卡名：取<b>最先捕获</b>的那张（卡片列表里排第一 = 主卡）。 */
    private static String autoFaceName;
    private static final Object FACE_LOCK = new Object();

    /** 诊断用：已经打过上下文日志的来源，避免刷屏。 */
    private static final java.util.Set<String> CONTEXT_LOGGED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 诊断用：CardInfo 的位图字段清单只打一次。 */
    private static final java.util.Set<String> CARD_INFO_LOGGED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private IslandFaceCache() {
    }

    /** 当前已缓存的卡面（可能为 null）。返回缓存本体，调用方不得回收。 */
    static Bitmap get() {
        synchronized (LOCK) {
            return cached != null && !cached.isRecycled() ? cached : null;
        }
    }

    /**
     * 记住当前页面的根视图，供 {@link #resolveFromViewTree()} 定向找卡面视图。
     *
     * <p>由 {@code GlassViewStyler} 在每次玻璃化遍历时调用（它本来就拿到了 decor）。</p>
     */
    static void rememberDecor(View decor) {
        if (decor != null) {
            lastDecor = new WeakReference<>(decor);
        }
    }

    /**
     * 获取真实卡面（用时才取）。
     *
     * <p>优先级：用户指定卡名 → 最近一次由 App 自己显示的卡面 → 视图树定向 → 磁盘兜底。</p>
     *
     * <p>给「刷卡那一刻」用，允许几十毫秒的 IO，但<b>绝不能跑在 UI 线程</b> ——
     * 调用方（{@code IslandBridge}）已放到后台线程。</p>
     *
     * @param preferredName 用户在设置里指定的卡名，空表示「自动」
     */
    static Bitmap resolve(Context context, String preferredName) {
        // 进程刚起来时，把上次落盘的识别结果读回来（否则要等用户再开一次卡片列表）
        loadIfNeeded(context);
        // 1) 用户明确指定了卡名
        if (preferredName != null && !preferredName.isEmpty()) {
            Bitmap picked = faceOf(preferredName);
            if (picked != null) {
                XposedBridge.log(TAG + ": 使用用户指定卡面「" + preferredName + "」");
                return upgradeFromDisk(context, preferredName, picked);
            }
            XposedBridge.log(TAG + ": 指定卡面「" + preferredName + "」尚未捕获到，回退自动选择");
        }
        // 2) 自动：卡片列表里排第一的那张（= 主卡）
        Bitmap auto = lastFace();
        if (auto != null && autoFaceName != null) {
            return upgradeFromDisk(context, autoFaceName, auto);
        }
        // 3) 视图树定向
        Bitmap fromTree = resolveFromViewTree();
        if (fromTree != null) {
            return fromTree;
        }
        Bitmap cachedFace = get();
        if (cachedFace != null) {
            return cachedFace;
        }
        // 4) 不再做「盲扫磁盘缓存」兜底。
        //    那条路只看图片宽高比，无法区分是哪张卡 —— 实测会把银行卡/门卡之类
        //    比例相近的图当成本卡画出来（用户反馈「识别成门卡」正是由此而来）。
        //    宁可显示中性默认卡，也不要画错卡。
        XposedBridge.log(TAG + ": 没有可用的真实卡面，本次使用默认卡面"
                + "（已识别卡名 " + capturedNames().size() + " 个）");
        return null;
    }

    // ======================================================================
    // 持久化：让识别的卡面活过进程重启
    // ======================================================================

    /** 卡名与缩略图的持久化目录名（放在模块外部私有目录）。 */
    private static final String FACE_STORE_DIR = "faces";
    private static boolean loadedFromDisk;
    /** 有新捕获、尚未落盘。 */
    private static volatile boolean storeDirty;

    /** 是否有「已捕获但还没落盘」的卡面。 */
    static boolean hasUnsavedCaptures() {
        return storeDirty;
    }

    /**
     * 把已识别的卡面（卡名 + 缩略图）落盘。
     *
     * <p>小米智能卡进程随时可能被杀，而「用户在刷卡前打开过卡片列表」这件事
     * 并不会每次都发生。落盘之后，下次进程启动也能直接取用正确的卡面，
     * 不必退回盲猜。</p>
     */
    static void persist(Context context) {
        if (context == null || !storeDirty) {
            return;
        }
        try {
            File dir = new File(context.getExternalFilesDir(null), FACE_STORE_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            java.util.LinkedHashMap<String, Bitmap> snapshot;
            String auto;
            synchronized (FACE_LOCK) {
                snapshot = new java.util.LinkedHashMap<>(facesByName);
                auto = autoFaceName;
            }
            if (snapshot.isEmpty()) {
                // 空集合绝不落盘：模块同时注入主进程与 :daemon 两个进程，
                // 辅进程没有卡面缓存，若也来写一次就会把主进程刚存好的数据清空
                // （实测两次落盘只差几毫秒）。
                XposedBridge.log(TAG + ": 本进程无卡面可落盘，跳过（避免清空已有数据）");
                return;
            }
            StringBuilder index = new StringBuilder();
            int i = 0;
            for (java.util.Map.Entry<String, Bitmap> entry : snapshot.entrySet()) {
                // 落盘按原图尺寸（列表图标本身就是 157x98 左右），
                // 别用界面缩略图那档 96px，否则恢复后更糊
                byte[] jpeg = encodeJpeg(entry.getValue(), 240);
                if (jpeg.length == 0) {
                    continue;
                }
                writeFile(new File(dir, i + ".jpg"), jpeg);
                // 每行：序号\t卡名\t是否自动模式选中的那张
                index.append(i).append('\t').append(entry.getKey())
                        .append('\t').append(entry.getKey().equals(auto) ? 1 : 0).append('\n');
                i++;
            }
            writeFile(new File(dir, "index.txt"),
                    index.toString().getBytes("UTF-8"));
            storeDirty = false;
            XposedBridge.log(TAG + ": 已落盘 " + i + " 张卡面（自动选中 " + auto + "）");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 持久化卡面失败 " + throwable);
        }
    }

    /** 进程启动后按需把落盘的卡面读回内存。 */
    private static void loadIfNeeded(Context context) {
        if (loadedFromDisk || context == null) {
            return;
        }
        loadedFromDisk = true;
        synchronized (FACE_LOCK) {
            if (!facesByName.isEmpty()) {
                return;
            }
        }
        try {
            File dir = new File(context.getExternalFilesDir(null), FACE_STORE_DIR);
            File index = new File(dir, "index.txt");
            if (!index.isFile()) {
                return;
            }
            String text = new String(readFile(index), "UTF-8");
            int loaded = 0;
            for (String line : text.split("\n")) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 2) {
                    continue;
                }
                Bitmap bitmap = BitmapFactory.decodeFile(new File(dir, parts[0] + ".jpg").getAbsolutePath());
                if (bitmap == null) {
                    continue;
                }
                synchronized (FACE_LOCK) {
                    facesByName.put(parts[1], bitmap);
                    if (parts.length >= 3 && "1".equals(parts[2]) && autoFaceName == null) {
                        autoFaceName = parts[1];
                    }
                }
                loaded++;
            }
            if (loaded > 0) {
                XposedBridge.log(TAG + ": 已从落盘数据恢复 " + loaded + " 张卡面（自动选中 "
                        + autoFaceName + "）");
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 读取落盘卡面失败 " + throwable);
        }
    }

    private static void writeFile(File file, byte[] data) {
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(file);
            out.write(data);
            out.flush();
        } catch (Throwable ignored) {
            // ignore
        } finally {
            closeQuietly(out);
        }
    }

    private static byte[] readFile(File file) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            byte[] buffer = new byte[(int) Math.min(file.length(), 512 * 1024)];
            int read = in.read(buffer);
            if (read <= 0) {
                return new byte[0];
            }
            byte[] out = new byte[read];
            System.arraycopy(buffer, 0, out, 0, read);
            return out;
        } catch (Throwable ignored) {
            return new byte[0];
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 把「从列表小图标拿到的低清卡面」升级为磁盘缓存里的同款高清卡面。
     *
     * <p>卡名是从卡片列表条目里读到的，但列表图标只有 157x98，放大到动画尺寸会明显发糊。
     * 磁盘缓存里其实存着同一张卡的高清图（960x606 一类），问题只是「哪一张才是这张卡」。
     * 于是用<b>视觉相似度</b>把它们对应起来：两边都缩到 16x10 比平均色，
     * 距离最小的那个就是同一张卡。</p>
     *
     * <p>这一步只在低清时触发，且匹配结果会打进日志（含距离），便于核对。</p>
     */
    private static Bitmap upgradeFromDisk(Context context, String name, Bitmap lowRes) {
        try {
            if (context == null || lowRes == null) {
                return lowRes;
            }
            if (Math.min(lowRes.getWidth(), lowRes.getHeight()) >= HIGH_RES_EDGE_PX) {
                return lowRes; // 已经够清晰
            }
            float[] reference = signature(lowRes);
            if (reference == null) {
                return lowRes;
            }
            Bitmap bestBitmap = null;
            double bestDistance = Double.MAX_VALUE;
            File bestFile = null;
            double secondBest = Double.MAX_VALUE;
            for (File dir : candidateCacheDirs(context)) {
                if (dir == null || !dir.isDirectory()) {
                    continue;
                }
                File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    if (file == null || !file.isFile() || file.length() <= 0) {
                        continue;
                    }
                    String fileName = file.getName();
                    if (fileName.startsWith(".") || fileName.contains("journal")) {
                        continue;
                    }
                    int[] size = readImageSize(file);
                    if (size == null || Math.min(size[0], size[1]) < HIGH_RES_EDGE_PX) {
                        continue;
                    }
                    float ratio = size[0] / (float) size[1];
                    if (ratio < LOOSE_MIN_RATIO || ratio > LOOSE_MAX_RATIO) {
                        continue;
                    }
                    Bitmap small = decodeSample(file, 64);
                    if (small == null) {
                        continue;
                    }
                    float[] candidate = signature(small);
                    if (candidate == null) {
                        continue;
                    }
                    double distance = distance(reference, candidate);
                    if (distance < bestDistance) {
                        secondBest = bestDistance;
                        bestDistance = distance;
                        bestFile = file;
                        bestBitmap = decodeDownscaled(file);
                    } else if (distance < secondBest) {
                        secondBest = distance;
                    }
                }
            }
            XposedBridge.log(TAG + ": 高清匹配「" + name + "」最佳距离="
                    + Math.round(bestDistance) + " 次佳=" + Math.round(secondBest)
                    + (bestFile == null ? "" : " 命中=" + bestFile.getName()));
            // 实测结论：列表缩略图与磁盘大图在「平均色」维度上区分度不够 ——
            // 曾把「京津冀互联互通卡」匹配到校园卡那张（距离 305135 vs 次佳 1175603，
            // 看起来是「自信的错答」）。宁可要「正确但略糊」，也不要「清晰但错卡」，
            // 因此这里暂时不采用匹配结果，只保留日志，等有更可靠的对应关系再启用。
            if (!ENABLE_DISK_MATCH) {
                return lowRes;
            }
            // 只有「最佳明显优于次佳」时才替换，避免把不相干的图当成本卡
            if (bestBitmap == null || bestDistance > secondBest * 0.75) {
                return lowRes;
            }
            synchronized (FACE_LOCK) {
                facesByName.put(name, bestBitmap);
            }
            synchronized (LOCK) {
                adopt(bestBitmap, bestBitmap.getWidth(), bestBitmap.getHeight(),
                        CONFIDENCE_HIGH, "高清匹配(" + name + ")");
            }
            return bestBitmap;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 高清匹配失败 " + throwable);
            return lowRes;
        }
    }

    /** 把任意位图缩成 16x10 的平均色签名，用于快速比对两张图是否是同一设计。 */
    private static float[] signature(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return null;
        }
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 16, 10, true);
            float[] out = new float[16 * 10 * 3];
            int index = 0;
            for (int y = 0; y < 10; y++) {
                for (int x = 0; x < 16; x++) {
                    int color = scaled.getPixel(x, y);
                    out[index++] = (color >> 16) & 0xFF;
                    out[index++] = (color >> 8) & 0xFF;
                    out[index++] = color & 0xFF;
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double distance(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return Double.MAX_VALUE;
        }
        double sum = 0d;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }

    /** 按目标边长采样解码（用于只需要粗略特征时）。 */
    private static Bitmap decodeSample(File file, int targetEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int maxEdge = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxEdge / (sample * 2) >= targetEdge) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 已经捕获到的卡名列表（用于设置页展示）。 */
    static ArrayList<String> capturedNames() {
        synchronized (FACE_LOCK) {
            return new ArrayList<>(facesByName.keySet());
        }
    }

    /**
     * 与 {@link #capturedNames()} 一一对应的缩略图（JPEG 字节）。
     *
     * <p>设置页要「看图选卡面」，但模块自己的进程读不到小米智能卡的内存位图，
     * 只能跨进程送过去。这里统一压成 96px 宽的 JPEG（每张约 3~4KB，
     * 40 张也就 150KB 左右，远低于广播的 1MB 上限）。</p>
     */
    static ArrayList<byte[]> capturedThumbnails() {
        ArrayList<byte[]> out = new ArrayList<>();
        synchronized (FACE_LOCK) {
            for (java.util.Map.Entry<String, Bitmap> entry : facesByName.entrySet()) {
                out.add(encodeThumbnail(entry.getValue()));
            }
        }
        return out;
    }

    private static byte[] encodeThumbnail(Bitmap source) {
        return encodeJpeg(source, THUMBNAIL_WIDTH_PX);
    }

    /** 压成 JPEG；{@code targetWidth} 为期望宽度（原图更小则不放大）。 */
    private static byte[] encodeJpeg(Bitmap source, int targetWidth) {
        if (source == null || source.isRecycled()) {
            return new byte[0];
        }
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            Bitmap scaled = source;
            if (width > targetWidth) {
                int targetHeight = Math.max(1, Math.round(
                        height * (targetWidth / (float) width)));
                scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, buffer);
            return buffer.toByteArray();
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }

    private static Bitmap faceOf(String name) {
        synchronized (FACE_LOCK) {
            return facesByName.get(name);
        }
    }

    private static Bitmap lastFace() {
        synchronized (FACE_LOCK) {
            if (autoFaceName == null) {
                return null;
            }
            return facesByName.get(autoFaceName);
        }
    }

    // ======================================================================
    // 策略 0：直接从 CardInfo 取（带卡名的全尺寸卡面）
    // ======================================================================

    /**
     * 由 {@link CardFaceGrabber} 在 {@code CardInfo#updateBackground(Context)} 之后调用。
     *
     * <p>这是目前<b>最理想</b>的来源：{@code CardInfo} 既知道卡名，又持有卡面素材，
     * 因此能拿到「哪张卡 + 全尺寸卡面」这对完整信息，不必再靠比例猜。</p>
     *
     * <p>实现用反射遍历字段，而不是写死字段名 —— 厂商各版本字段名不完全一致，
     * 但「CardInfo 里存着卡名与卡面位图」这一点是稳定的。</p>
     */
    static void offerCardInfo(Object cardInfo) {
        if (cardInfo == null) {
            return;
        }
        try {
            String cardName = readCardName(cardInfo);
            Bitmap best = null;
            int bestArea = 0;
            StringBuilder inventory = new StringBuilder();
            Class<?> clazz = cardInfo.getClass();
            int depth = 0;
            while (clazz != null && clazz != Object.class && depth < 4) {
                for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(cardInfo);
                        if (value == null) {
                            continue;
                        }
                        if (value instanceof Bitmap) {
                            Bitmap bitmap = (Bitmap) value;
                            int area = bitmap.isRecycled() ? 0
                                    : bitmap.getWidth() * bitmap.getHeight();
                            if (inventory.length() < 400 && bitmap.getWidth() > 0) {
                                inventory.append(field.getName()).append('=')
                                        .append(bitmap.getWidth()).append('x')
                                        .append(bitmap.getHeight()).append(' ');
                            }
                            if (area > bestArea) {
                                bestArea = area;
                                best = bitmap;
                            }
                        } else if (value instanceof Drawable) {
                            Bitmap fromDrawable = peekBitmap((Drawable) value);
                            if (fromDrawable != null) {
                                int area = fromDrawable.getWidth() * fromDrawable.getHeight();
                                if (inventory.length() < 400) {
                                    inventory.append(field.getName()).append("(d)=")
                                            .append(fromDrawable.getWidth()).append('x')
                                            .append(fromDrawable.getHeight()).append(' ');
                                }
                                if (area > bestArea) {
                                    bestArea = area;
                                    best = fromDrawable;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 单个字段读失败不影响其它
                    }
                }
                clazz = clazz.getSuperclass();
                depth++;
            }
            if (CARD_INFO_LOGGED.add("log") && inventory.length() > 0) {
                XposedBridge.log(TAG + ": [CardInfo] 卡名=" + cardName
                        + " 位图字段：" + inventory);
            }
            if (best == null || best.isRecycled()) {
                return;
            }
            int width = best.getWidth();
            int height = best.getHeight();
            float ratio = width / (float) height;
            if (ratio < LOOSE_MIN_RATIO || ratio > LOOSE_MAX_RATIO
                    || Math.min(width, height) < MIN_SHORT_EDGE_PX) {
                return;
            }
            if (cardName == null || cardName.isEmpty()) {
                // 没读到卡名就退回低可信度通道，交给尺寸判定
                synchronized (LOCK) {
                    adopt(best, width, height, CONFIDENCE_LOW, "CardInfo(无名)");
                }
                return;
            }
            Bitmap stored = downscale(best);
            if (stored == null) {
                return;
            }
            boolean improved;
            synchronized (FACE_LOCK) {
                Bitmap old = facesByName.get(cardName);
                improved = old == null || Math.min(stored.getWidth(), stored.getHeight())
                        > Math.min(old.getWidth(), old.getHeight());
                if (improved) {
                    facesByName.put(cardName, stored);
                    if (autoFaceName == null) {
                        autoFaceName = cardName;
                    }
                }
            }
            if (improved) {
                XposedBridge.log(TAG + ": 已从 CardInfo 捕获卡面「" + cardName + "」 "
                        + width + "x" + height);
            }
            synchronized (LOCK) {
                adopt(best, width, height, CONFIDENCE_HIGH, "CardInfo(" + cardName + ")");
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": CardInfo 取卡面失败 " + throwable);
        }
    }

    /** 读 CardInfo 的卡名：优先 getCardName()，其次常见字段。 */
    private static String readCardName(Object cardInfo) {
        for (String method : new String[]{"getCardName", "getProductName", "getCardTypeName"}) {
            try {
                Object value = XposedHelpers.callMethod(cardInfo, method);
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    return ((String) value).trim();
                }
            } catch (Throwable ignored) {
                // 方法不存在就试下一个
            }
        }
        for (String field : new String[]{"mCardName", "mProductName", "mCardProductName"}) {
            try {
                Object value = XposedHelpers.getObjectField(cardInfo, field);
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    return ((String) value).trim();
                }
            } catch (Throwable ignored) {
                // 字段不存在就试下一个
            }
        }
        return null;
    }

    // ======================================================================
    // 策略 1：定向取屏幕上的卡面视图（高可信度）
    // ======================================================================

    /**
     * 从最后一次出现的页面视图树里找卡面视图，直接取其 Drawable 位图。
     *
     * <p>与 {@code view.draw()} 的区别：不要求布局完成、不要求可见，
     * 只要视图被创建并且图片已解码（{@code getDrawable()} 非空）就能取到。</p>
     */
    private static Bitmap resolveFromViewTree() {
        try {
            View decor = lastDecor.get();
            if (decor == null) {
                return null;
            }
            List<View> hits = new ArrayList<>();
            collectCardFaceViews(decor, 0, hits);
            if (hits.isEmpty()) {
                return null;
            }
            // 多个候选时取面积最大的那个（卡堆里可能还有后面的卡）
            Bitmap best = null;
            for (View view : hits) {
                Bitmap bitmap = bitmapOfView(view);
                if (bitmap == null || bitmap.isRecycled()) {
                    continue;
                }
                if (best == null || bitmap.getWidth() * bitmap.getHeight()
                        > best.getWidth() * best.getHeight()) {
                    best = bitmap;
                }
            }
            if (best == null) {
                return null;
            }
            int width = best.getWidth();
            int height = best.getHeight();
            float ratio = width / (float) height;
            if (ratio < LOOSE_MIN_RATIO || ratio > LOOSE_MAX_RATIO) {
                XposedBridge.log(TAG + ": 视图树候选尺寸不像卡面（" + width + "x" + height
                        + "），忽略");
                return null;
            }
            synchronized (LOCK) {
                adopt(best, width, height, CONFIDENCE_HIGH, "视图树定向");
            }
            return get();
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 视图树定向取卡面失败 " + throwable);
            return null;
        }
    }

    /** 递归收集「被识别为卡面」的视图。 */
    private static void collectCardFaceViews(View view, int depth, List<View> out) {
        if (view == null || depth > MAX_TREE_DEPTH) {
            return;
        }
        if (isCardFaceView(view)) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                collectCardFaceViews(group.getChildAt(i), depth + 1, out);
            }
        }
    }

    /**
     * 该视图是否是「卡面视图」。
     *
     * <p>只认两条稳定证据：<b>类名</b>等于反编译确认的卡面类，
     * 或<b>资源名</b>带卡面语义。刻意不用「图片比例」这类间接证据 ——
     * 那正是 1.5.8 选错的根源。</p>
     */
    private static boolean isCardFaceView(View view) {
        if (view == null) {
            return false;
        }
        try {
            if (CARD_FACE_CLASS.equals(view.getClass().getName())) {
                return true;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return matchesCardKeyword(idNameOf(view));
    }

    /** 取视图自身的图片位图：先 Drawable，再背景。 */
    private static Bitmap bitmapOfView(View view) {
        if (view instanceof ImageView) {
            Bitmap fromImage = peekBitmap(((ImageView) view).getDrawable());
            if (fromImage != null) {
                return fromImage;
            }
        }
        try {
            return peekBitmap(view.getBackground());
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ======================================================================
    // 策略 2：图片加载截获（可信度取决于来源视图）
    // ======================================================================

    /**
     * 由 {@link CardFaceGrabber} 在 {@code ImageView#setImageDrawable} 时调用。
     *
     * @param drawable 刚设置到 ImageView 上的 Drawable
     * @param view     目标 ImageView，可为 null
     */
    static void offerDrawable(Drawable drawable, View view) {
        try {
            String idName = idNameOf(view);
            boolean keywordHit = matchesCardKeyword(idName);
            boolean classHit = view != null && CARD_FACE_CLASS.equals(view.getClass().getName());
            // 最可靠的证据：这张图所在的卡片条目里带着「卡名」。
            // 卡名来自 App 自己的列表（如「北京互通卡」「京津冀互联互通卡」），
            // 拿到名字不仅说明它是卡面，还知道是哪一张卡。
            String cardName = findCardName(view);
            boolean trusted = keywordHit || classHit || cardName != null;

            // 快速路径：只从 BitmapDrawable 链里取位图，不做任何渲染。
            // 这个钩子会跟着进程里每一个 ImageView 触发，热路径必须廉价。
            Bitmap source = peekBitmap(drawable);
            if (source == null) {
                // 非位图形态极少是卡面；只有被判定为卡面视图时才值得渲染兜底
                if (!trusted) {
                    return;
                }
                source = renderDrawable(drawable);
                if (source == null) {
                    return;
                }
            }
            if (source.isRecycled()) {
                return;
            }

            int width = source.getWidth();
            int height = source.getHeight();
            float ratio = width / (float) height;
            if (ratio >= 1.2f && ratio <= 2.6f && Math.min(width, height) >= 300) {
                logContextOnce(view, width, height);
            }
            if (ratio < LOOSE_MIN_RATIO || ratio > LOOSE_MAX_RATIO) {
                if (trusted) {
                    // 卡面视图上出现了比例完全不对的图，多半是占位图 / 图标，记一笔便于排查
                    XposedBridge.log(TAG + ": 卡面视图(" + idName + ")上的图比例异常 "
                            + width + "x" + height + "，跳过");
                }
                return;
            }
            if (!trusted && !looksLikeCardFace(width, height)) {
                // 来源不可信时要求严格：只有「横着的卡片比例 + 短边够大」才收
                return;
            }

            if (cardName != null) {
                // 有卡名的：单独按名字存一份，供「选择卡面」精确取用
                Bitmap stored = downscale(source);
                if (stored != null) {
                    synchronized (FACE_LOCK) {
                        Bitmap old = facesByName.get(cardName);
                        if (old == null || Math.min(stored.getWidth(), stored.getHeight())
                                > Math.min(old.getWidth(), old.getHeight())) {
                            facesByName.put(cardName, stored);
                            XposedBridge.log(TAG + ": 已按卡名捕获卡面「" + cardName + "」 "
                                    + width + "x" + height);
                        }
                        // 自动模式固定用「最先捕获」的那张：卡片列表里排第一的是主卡，
                        // 而目录里的其它城市卡会排在后面，不该抢走自动模式。
                        if (autoFaceName == null) {
                            autoFaceName = cardName;
                        }
                        storeDirty = true;
                    }
                }
            }

            int confidence = trusted ? CONFIDENCE_HIGH : CONFIDENCE_LOW;
            synchronized (LOCK) {
                adopt(source, width, height, confidence,
                        cardName != null ? ("加载截获(" + cardName + ")")
                                : (trusted ? "加载截获(可信)" : "加载截获(弱)"));
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 图片加载截获失败 " + throwable);
        }
    }

    /**
     * 诊断：把「卡片比例大图」的来源视图及其祖先链、以及该条目里所有 TextView
     * 打一行日志，用于确认卡面到底挂在什么结构上、卡名该从哪里读。每种来源只记一次。
     */
    private static void logContextOnce(View view, int width, int height) {
        try {
            String selfKey = view == null ? "null" : (view.getClass().getName() + "#" + idNameOf(view));
            String key = selfKey + "@" + width + "x" + height;
            if (!CONTEXT_LOGGED.add(key)) {
                return;
            }
            StringBuilder chain = new StringBuilder();
            View node = view;
            for (int i = 0; i < 5 && node != null; i++) {
                chain.append('[').append(i).append(' ')
                        .append(node.getClass().getSimpleName())
                        .append('#').append(idNameOf(node)).append(']');
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
            StringBuilder texts = new StringBuilder();
            View root = view;
            for (int i = 0; i < 3 && root != null; i++) {
                collectTexts(root, 0, texts);
                if (texts.length() > 0) {
                    break;
                }
                android.view.ViewParent parent = root.getParent();
                root = parent instanceof View ? (View) parent : null;
            }
            XposedBridge.log(TAG + ": [上下文] " + width + "x" + height
                    + " 链=" + chain + " 文本=" + texts);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void collectTexts(View view, int depth, StringBuilder out) {
        if (view == null || depth > 6 || out.length() > 300) {
            return;
        }
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.length() > 0) {
                out.append('{').append(idNameOf(view)).append(':').append(text).append('}');
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTexts(group.getChildAt(i), depth + 1, out);
            }
        }
    }

    /**
     * 从卡片条目里读出卡名。
     *
     * <p>做法：从图片视图向上找若干层，在每一层的子树里找资源名为
     * {@code card_list_item_card_name}（实机 uiautomator dump 确认的 id）的 TextView，
     * 取其文本。只认这个 id，因此不会把标题、城市名等误当卡名。</p>
     */
    private static String findCardName(View view) {
        if (view == null) {
            return null;
        }
        try {
            View node = view;
            for (int level = 0; level < 5 && node != null; level++) {
                String name = firstCardNameInSubtree(node, 0);
                if (name != null) {
                    return name;
                }
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static String firstCardNameInSubtree(View view, int depth) {
        if (view == null || depth > 6) {
            return null;
        }
        if (view instanceof android.widget.TextView) {
            String idName = idNameOf(view);
            if (idName.contains("card_name") || idName.contains("cardname")) {
                CharSequence text = ((android.widget.TextView) view).getText();
                if (text != null && !text.toString().trim().isEmpty()) {
                    return text.toString().trim();
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                String name = firstCardNameInSubtree(group.getChildAt(i), depth + 1);
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    // ======================================================================
    // 策略 3：视图树兜底（1.5.6 老路径，仅按尺寸判定）
    // ======================================================================

    /**
     * 把一个「看起来是卡面」的视图绘制进缓存。
     *
     * <p>兜底用：既没识别出卡面视图、也没有 {@code setImageDrawable} 时才会走到。</p>
     */
    static void captureView(View view) {
        if (view == null) {
            return;
        }
        try {
            synchronized (LOCK) {
                if (cachedConfidence >= CONFIDENCE_HIGH) {
                    return;
                }
                long now = SystemClock.uptimeMillis();
                if (now - lastViewCaptureAt < VIEW_CAPTURE_INTERVAL_MS) {
                    return;
                }
                if (view.getWidth() <= 0 || view.getHeight() <= 0 || !view.isShown()) {
                    return;
                }
                if (!looksLikeCardFace(view.getWidth(), view.getHeight())) {
                    return;
                }
                lastViewCaptureAt = now;
            }
            int width = view.getWidth();
            int height = view.getHeight();
            float scale = Math.min(1f,
                    MAX_EDGE_PX / (float) Math.max(width, height));
            int w = Math.max(1, Math.round(width * scale));
            int h = Math.max(1, Math.round(height * scale));

            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            view.draw(canvas);

            synchronized (LOCK) {
                adopt(bitmap, width, height, CONFIDENCE_LOW, "视图树绘制");
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 视图树截获失败 " + throwable);
        }
    }

    // ======================================================================
    // 策略 4：Glide 磁盘缓存扫描（低可信度兜底）
    // ======================================================================

    /** 扫描磁盘缓存，挑出最像卡面的一张并缓存。 */
    private static Bitmap loadBestFromDiskCache(Context context) {
        if (context == null) {
            return null;
        }
        synchronized (LOCK) {
            long now = SystemClock.uptimeMillis();
            if (diskScanFoundNothing && now - lastDiskScanAt < DISK_SCAN_TTL_MS) {
                return null; // 刚扫过且没找到，短时间内不再扫
            }
            lastDiskScanAt = now;
        }
        try {
            List<File> dirs = candidateCacheDirs(context);
            // 两轮：先在「严格卡片比例」里挑，挑不到再放宽
            File bestFile = scanDirs(dirs, true);
            boolean tight = true;
            if (bestFile == null) {
                bestFile = scanDirs(dirs, false);
                tight = false;
            }
            if (bestFile == null) {
                synchronized (LOCK) {
                    diskScanFoundNothing = true;
                }
                XposedBridge.log(TAG + ": 磁盘缓存里没找到像卡面的图");
                return null;
            }
            int[] size = readImageSize(bestFile);
            Bitmap decoded = decodeDownscaled(bestFile);
            if (decoded == null || size == null) {
                return null;
            }
            synchronized (LOCK) {
                diskScanFoundNothing = false;
                adopt(decoded, size[0], size[1], CONFIDENCE_LOW,
                        tight ? "磁盘扫描(严格)" : "磁盘扫描(宽松)");
            }
            XposedBridge.log(TAG + ": 已从磁盘缓存取出卡面 " + bestFile.getName()
                    + " 原始=" + size[0] + "x" + size[1] + " 严格命中=" + tight);
            return get();
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 扫描磁盘缓存失败 " + throwable);
            return null;
        }
    }

    /** 扫一遍候选目录，返回得分最高的文件；同时把候选明细写进日志。 */
    private static File scanDirs(List<File> dirs, boolean tight) {
        File bestFile = null;
        long bestScore = Long.MIN_VALUE;
        int scanned = 0;
        StringBuilder candidates = new StringBuilder();
        int logged = 0;
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                continue;
            }
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (File file : files) {
                if (scanned >= MAX_SCAN_FILES) {
                    break;
                }
                if (file == null || !file.isFile() || file.length() <= 0) {
                    continue;
                }
                String name = file.getName();
                if (name.startsWith(".") || name.contains("journal")) {
                    continue;
                }
                scanned++;
                int[] size = readImageSize(file);
                if (size == null) {
                    continue;
                }
                if (logged < MAX_LOGGED_CANDIDATES) {
                    logged++;
                    candidates.append(name, 0, Math.min(12, name.length()))
                            .append('(').append(size[0]).append('x').append(size[1])
                            .append(')').append(' ');
                }
                long score = scoreOf(size[0], size[1], tight);
                if (score > bestScore) {
                    bestScore = score;
                    bestFile = file;
                }
            }
        }
        XposedBridge.log(TAG + ": 磁盘扫描" + (tight ? "(严格)" : "(宽松)")
                + " 检查 " + scanned + " 个文件，候选前几个：" + candidates);
        return bestFile;
    }

    /** 候选目录：Glide 缓存目录 + 应用私有目录里的卡面子目录。 */
    private static List<File> candidateCacheDirs(Context context) {
        List<File> dirs = new ArrayList<>();
        File cache = safeDir(context, false);
        File files = safeDir(context, true);
        File external = safeExternalCacheDir(context);
        if (cache != null) {
            for (String name : CACHE_DIR_NAMES) {
                dirs.add(new File(cache, name));
            }
            dirs.add(cache); // 防御：厂商改了 Glide 目录名时退回缓存根目录
        }
        if (external != null) {
            for (String name : CACHE_DIR_NAMES) {
                dirs.add(new File(external, name));
            }
        }
        if (files != null) {
            for (String name : EXTRA_DIR_NAMES) {
                dirs.add(new File(files, name));
            }
            dirs.add(files);
        }
        return dirs;
    }

    private static File safeDir(Context context, boolean filesDir) {
        try {
            return filesDir ? context.getFilesDir() : context.getCacheDir();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File safeExternalCacheDir(Context context) {
        try {
            return context.getExternalCacheDir();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 只读文件头拿尺寸，不做解码。 */
    private static int[] readImageSize(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null;
            }
            if (options.outMimeType != null && options.outMimeType.contains("svg")) {
                return null;
            }
            return new int[]{options.outWidth, options.outHeight};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 打分：越接近 ISO 卡片比例越高分，其次比像素量。
     *
     * @param tight 是否只接受「严格卡片比例」这一档
     */
    private static long scoreOf(int width, int height, boolean tight) {
        if (width <= 0 || height <= 0) {
            return Long.MIN_VALUE;
        }
        float ratio = width / (float) height;
        if (ratio < LOOSE_MIN_RATIO || ratio > LOOSE_MAX_RATIO) {
            return Long.MIN_VALUE;
        }
        if (Math.min(width, height) < MIN_SHORT_EDGE_PX) {
            return Long.MIN_VALUE;
        }
        boolean tightOk = ratio >= TIGHT_MIN_RATIO && ratio <= TIGHT_MAX_RATIO;
        if (tight && !tightOk) {
            return Long.MIN_VALUE;
        }
        long area = (long) width * height;
        boolean ideal = Math.abs(ratio - ISO_RATIO) <= IDEAL_RATIO_TOLERANCE;
        return (ideal ? 1_000_000_000_000L : 0L) + area;
    }

    /** 按需降采样解码，避免把 4K 大图整张读进内存。 */
    private static Bitmap decodeDownscaled(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int maxEdge = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxEdge / (sample * 2) >= MAX_EDGE_PX) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (decoded == null) {
                return null;
            }
            return downscale(decoded);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 解码卡面失败 " + throwable);
            return null;
        }
    }

    // ======================================================================
    // 诊断：导出候选图片
    // ======================================================================

    /**
     * 把所有「卡片比例」的候选图片原样复制到应用外部私有目录
     * （{@code /sdcard/Android/data/<pkg>/files/facecheck}），便于用 adb 拉出来核对。
     *
     * <p>用途：磁盘兜底只看图片本身，无法区分「卡面」与「列表背景图」。
     * 与其继续猜阈值，不如把候选摆出来看一眼，然后据此写死规则。
     * 文件名前缀带尺寸（{@code 960x606__原文件名}），方便对照。</p>
     *
     * @return 人类可读的摘要，供日志输出
     */
    static String dumpCandidates(Context context) {
        StringBuilder summary = new StringBuilder();
        try {
            File outDir = new File(context.getExternalFilesDir(null), "facecheck");
            if (!outDir.exists() && !outDir.mkdirs()) {
                return "无法创建导出目录 " + outDir;
            }
            // 清掉上一次的导出，避免混淆
            File[] stale = outDir.listFiles();
            if (stale != null) {
                for (File file : stale) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            int copied = 0;
            List<File> dirs = candidateCacheDirs(context);
            for (File dir : dirs) {
                summary.append("\n[").append(dir == null ? "null" : dir.getAbsolutePath())
                        .append("] ");
                if (dir == null || !dir.isDirectory()) {
                    summary.append("不存在");
                    continue;
                }
                File[] files = dir.listFiles();
                if (files == null || files.length == 0) {
                    summary.append("空");
                    continue;
                }
                summary.append(files.length).append(" 个文件");
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                for (File file : files) {
                    if (file == null || !file.isFile() || file.length() <= 0) {
                        continue;
                    }
                    int[] size = readImageSize(file);
                    if (size == null) {
                        continue;
                    }
                    File target = new File(outDir,
                            size[0] + "x" + size[1] + "__" + file.lastModified()
                                    + "__" + file.getName());
                    if (!copyFile(file, target)) {
                        continue;
                    }
                    copied++;
                    if (copied >= MAX_DUMP_FILES) {
                        break;
                    }
                }
                if (copied >= MAX_DUMP_FILES) {
                    break;
                }
            }
            summary.append("\n共导出 ").append(copied).append(" 张到 ").append(outDir);
            // 把导出清单也写进日志（截断），这样即使不 pull 文件也能看出缓存里都有什么
            File[] dumped = outDir.listFiles();
            if (dumped != null) {
                StringBuilder names = new StringBuilder();
                for (File file : dumped) {
                    names.append(file.getName()).append(' ');
                    if (names.length() > 1800) {
                        names.append("…");
                        break;
                    }
                }
                summary.append("\n清单：").append(names);
            }
            XposedBridge.log(TAG + ": 候引导出完成 —— " + summary);
            // 顺手把识别结果落盘（诊断时也走一遍，便于核对持久化）
            persist(context);
            // 顺手跑一遍「解析」流程，把选型打进日志，便于判断选的是哪张
            Bitmap resolved = resolve(context, "");
            XposedBridge.log(TAG + ": 自检解析结果=" + (resolved == null ? "无"
                    : resolved.getWidth() + "x" + resolved.getHeight())
                    + " 卡名列表=" + capturedNames());
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": 候引导出失败 " + throwable);
            return "导出失败 " + throwable;
        }
        return summary.toString();
    }

    private static boolean copyFile(File source, File target) {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(source);
            out = new java.io.FileOutputStream(target);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ======================================================================
    // 选型与缓存
    // ======================================================================

    /**
     * 采纳一个候选。
     *
     * <p>规则：<b>可信度高的永远压过低的</b>；可信度相同才比「比例是否标准」，
     * 最后比分辨率。这样磁盘兜底猜错的图不会把已经识别到的真卡面顶掉。</p>
     */
    private static void adopt(Bitmap candidate, int width, int height,
                              int confidence, String source) {
        if (candidate == null || candidate.isRecycled()) {
            return;
        }
        if (!isBetter(width, height, confidence)) {
            return;
        }
        Bitmap scaled = downscale(candidate);
        if (scaled == null) {
            return;
        }
        cached = scaled;
        cachedConfidence = confidence;
        XposedBridge.log(TAG + ": 采纳卡面（" + source + " 可信度=" + confidence
                + " 原始=" + width + "x" + height
                + " 缓存=" + scaled.getWidth() + "x" + scaled.getHeight() + "）");
    }

    /** 新候选是否比现有缓存更值得采用。 */
    private static boolean isBetter(int width, int height, int confidence) {
        Bitmap current = cached;
        if (current == null || current.isRecycled()) {
            return true;
        }
        if (confidence != cachedConfidence) {
            return confidence > cachedConfidence;
        }
        float newRatio = width / (float) height;
        float oldRatio = current.getWidth() / (float) current.getHeight();
        boolean newIdeal = Math.abs(newRatio - ISO_RATIO) <= IDEAL_RATIO_TOLERANCE;
        boolean oldIdeal = Math.abs(oldRatio - ISO_RATIO) <= IDEAL_RATIO_TOLERANCE;
        if (newIdeal != oldIdeal) {
            return newIdeal;
        }
        return Math.min(width, height) > Math.min(current.getWidth(), current.getHeight());
    }

    private static Bitmap downscale(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int maxEdge = Math.max(width, height);
        if (maxEdge <= MAX_EDGE_PX) {
            return source.copy(Bitmap.Config.ARGB_8888, false);
        }
        float scale = MAX_EDGE_PX / (float) maxEdge;
        int w = Math.max(1, Math.round(width * scale));
        int h = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(source, w, h, true);
    }

    /** 该尺寸是否像一张卡面：短边够大、比例落在卡片区间、横放。 */
    private static boolean looksLikeCardFace(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (Math.min(width, height) < MIN_SHORT_EDGE_PX) {
            return false;
        }
        float ratio = width / (float) height;
        return ratio >= TIGHT_MIN_RATIO && ratio <= TIGHT_MAX_RATIO;
    }

    /**
     * 只从「已经是位图」的 Drawable 形态里取位图，<b>不做任何渲染</b>。
     *
     * <p>这个方法会在每个 ImageView 的每次图片设置上被调用，因此必须廉价。</p>
     */
    private static Bitmap peekBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            Bitmap best = null;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                Bitmap candidate = peekBitmap(layers.getDrawable(i));
                if (candidate != null && (best == null
                        || candidate.getWidth() * candidate.getHeight()
                        > best.getWidth() * best.getHeight())) {
                    best = candidate;
                }
            }
            return best;
        }
        if (drawable instanceof DrawableContainer) {
            try {
                return peekBitmap(((DrawableContainer) drawable).getCurrent());
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 渲染兜底：把非位图形态的 Drawable 按固有尺寸画成位图。
     *
     * <p>成本较高，因此只在「来源视图被识别为卡面」时才会走到这里。</p>
     */
    private static Bitmap renderDrawable(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        if (Math.min(width, height) < MIN_SHORT_EDGE_PX) {
            return null;
        }
        int maxEdge = Math.max(width, height);
        float scale = maxEdge > MAX_EDGE_PX ? MAX_EDGE_PX / (float) maxEdge : 1f;
        int w = Math.max(1, Math.round(width * scale));
        int h = Math.max(1, Math.round(height * scale));
        try {
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String idNameOf(View view) {
        if (view == null) {
            return "";
        }
        try {
            int id = view.getId();
            if (id == View.NO_ID || id <= 0) {
                return "";
            }
            String name = view.getResources().getResourceEntryName(id);
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 资源名是否带卡面语义（与 {@code CardFaceGuard} 的判定口径一致）。 */
    private static boolean matchesCardKeyword(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String keyword : CARD_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] CARD_KEYWORDS = {
            "card_art", "cardart", "card_face", "cardface", "card_bg", "cardbg",
            "card_image", "cardimg", "card_view", "cardview", "kaimian",
            "cover", "artwork", "face"
    };
}
