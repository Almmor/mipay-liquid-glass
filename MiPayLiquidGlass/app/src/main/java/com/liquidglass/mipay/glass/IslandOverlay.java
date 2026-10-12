package com.liquidglass.mipay.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

/**
 * iOS 风格刷卡动画视图（1.5.7）。
 *
 * <p>整段动画由 Canvas 自绘，不依赖布局或图片资源。完整时序：</p>
 *
 * <ol>
 *     <li><b>进入</b>：绕「岛顶边中点」缩放 —— 岛从顶部一个点逐渐放大成完整的岛
 *     （1.6.8 起取代 1.5.6 的「整块岛从窗口上边界外滑入」）；</li>
 *     <li><b>卡片入场</b>：卡片淡入并轻微放大，与探出同时开始；</li>
 *     <li><b>翻转</b>：卡片沿 Y 轴翻转 180°（{@link Camera#rotateY}，真正的 3D 变换）。
 *     1.5.7 起<b>岛一开始出现就翻</b>；1.6.3 起<b>翻转前是卡背（默认卡面）、
 *     翻定后是卡正面（主卡面）</b>；</li>
 *     <li><b>状态</b>：卡片下方画入白色线条打勾 / 打叉，笔画用 {@link PathMeasure} 逐段描出；</li>
 *     <li><b>文案</b>：交易金额与余额从下方滑入；</li>
 *     <li><b>停留</b>；</li>
 *     <li><b>消失</b>：加速缩回顶部那个点并淡出，然后回调移除窗口。</li>
 * </ol>
 *
 * <h3>1.6.3 的关键改动</h3>
 *
 * <ul>
 *     <li><b>正反面调换</b>：翻转前先出现的一面改为<b>卡背（系统默认卡面）</b>，
 *     翻过去之后才是<b>卡正面（主卡面）</b>。此前是「先主卡面、后纯黑」，
 *     被判断为两面装反了；</li>
 *     <li><b>翻转拆成两半、永不镜像</b>：前半程卡背 0°→90°，后半程卡正面
 *     −90°→0°，全程 |角度| ≤ 90°。这样就不需要去「抵消 rotateY(180°) 的镜像」，
 *     从根上避免卡面左右颠倒；</li>
 *     <li><b>去掉纯黑背面</b>：卡背改用「蓝紫渐变 + 高光斜带 + 芯片」的默认卡面，
 *     同时兼任拿不到真实卡面时的兜底。</li>
 * </ul>
 *
 * <h3>1.5.9 的关键改动</h3>
 *
 * <ul>
 *     <li><b>卡背固定为纯黑</b>：翻转后的背面不再是蓝紫渐变示例卡，
 *     只有一道极淡的顶部反光；正面依旧是真实卡面（拿不到卡面时才回退示例卡）。
 *     （1.6.3 起已改为默认卡面，见上。）</li>
 * </ul>
 *
 * <h3>1.5.7 的关键改动</h3>
 *
 * <ul>
 *     <li><b>翻转提前</b>：{@code FLIP_START} 从 660ms 提到 140ms，与顶部探出重叠，
 *     岛探出来的过程中卡片就在翻转；</li>
 *     <li><b>真实卡面</b>：正面优先绘制 {@link IslandFaceCache} 从图片加载层面
 *     截获的真实卡面（见 {@link CardFaceGrabber}），仅在拿不到时才退回示例卡。</li>
 * </ul>
 *
 * <h3>1.5.6 的关键改动</h3>
 *
 * <ul>
 *     <li><b>进入 / 消失改为「顶部探出 / 缩回」</b>：位移量为
 *     {@code -(PILL_TOP + PILL_HEIGHT)}，即隐藏态整块岛都在窗口上边界之外，
 *     探出过程里岛的下边缘从屏幕顶端向下生长 —— 与灵动岛的出场方式一致。
 *     窗口本身贴屏幕顶端（y=0），滑出部分由窗口 Surface 裁剪。</li>
 *     <li><b>状态图标白色线条化</b>：删除绿色 / 红色实心圆底，
 *     勾与叉都改为纯白线条并保留 {@link PathMeasure} 逐段描画动画。</li>
 *     <li><b>真实卡面</b>：构造时可传入 {@code miui.tsmclient} 进程捕获的卡面位图
 *     （{@link IslandFaceCache}），正面直接绘制真实卡面；无卡面或翻转后的背面
 *     仍用渐变示例卡兜底。</li>
 * </ul>
 */
public final class IslandOverlay extends View {

    // ------------------------------------------------------------------
    // 时间轴（毫秒）
    // ------------------------------------------------------------------
    /** 整段动画总时长（含进入与消失）。 */
    public static final long DURATION_MS = 3400L;

    /** 进入：胶囊从顶部探出。 */
    private static final long ENTER_START = 0L;
    private static final long ENTER_END = 460L;
    /** 卡片入场：与探出同时开始（1.5.7 起不再等探出结束）。 */
    private static final long CARD_IN_START = 0L;
    private static final long CARD_IN_END = 180L;
    /**
     * 卡片翻转：1.6.8 起<b>与出现同时开始</b>且整体加快（560ms 内翻完）。
     *
     * <p>演进过程：最初要等探出（460ms）与卡片入场（660ms）都结束才开始翻；
     * 1.5.7 提到 140ms 起步；现在直接归零 —— 岛刚一出现卡片就在转，
     * 落定得更早，观感更「干脆」。</p>
     */
    private static final long FLIP_START = 0L;
    private static final long FLIP_END = 560L;
    /** 白色线条打勾 / 打叉描边。 */
    private static final long STATUS_START = 940L;
    private static final long STATUS_END = 1380L;
    /** 金额与余额滑入。 */
    private static final long TEXT_START = 1250L;
    private static final long TEXT_END = 1700L;
    /** 停留结束 / 开始缩回顶部。 */
    private static final long HOLD_END = 2700L;
    private static final long EXIT_END = DURATION_MS;

    // ------------------------------------------------------------------
    // 尺寸（dp）
    // ------------------------------------------------------------------
    private static final float PILL_WIDTH_DP = 264f;
    private static final float PILL_HEIGHT_DP = 168f;
    private static final float PILL_RADIUS_DP = 32f;
    /** 胶囊在视图内的上边距：探出动画的位移行程 = PILL_TOP + PILL_HEIGHT。 */
    private static final float PILL_TOP_DP = 14f;
    private static final float BOTTOM_PADDING_DP = 14f;
    /**
     * 进入 / 退出的起始缩放（1.6.8）。
     *
     * <p>取 0.04 而不是 0：真正缩到 0 时圆角矩形会退化成亚像素点，
     * 抗锯齿抖动明显；留 4% 既看着像「从一个点长出来」，又不会闪。</p>
     */
    private static final float POINT_SCALE = 0.04f;

    private static final float CARD_WIDTH_DP = 108f;
    private static final float CARD_HEIGHT_DP = 64f;
    private static final float CARD_RADIUS_DP = 10f;
    private static final float CARD_TOP_DP = 13f;
    /** 白色线条勾 / 叉的包围尺寸（无彩底后放大，维持视觉重量）。 */
    private static final float STATUS_SIZE_DP = 40f;
    private static final float STATUS_CENTER_Y_DP = 96f;
    private static final float AMOUNT_SIZE_DP = 21f;
    private static final float AMOUNT_BASELINE_DP = 138f;
    private static final float BALANCE_SIZE_DP = 11.5f;
    private static final float BALANCE_BASELINE_DP = 157f;

    // ------------------------------------------------------------------
    // 配色
    // ------------------------------------------------------------------
    private static final int PILL_COLOR = 0xF2000000;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0x99FFFFFF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    /**
     * 系统默认卡面（卡背）用的图片资源。
     *
     * <p>由用户提供的卡背设计：主色 {@code #177EE6} + 居中白色徽标，
     * 原始比例 1.587（正好是卡片比例）。这里只解码一次并静态缓存，
     * 避免每次动画都读一遍资源。</p>
     */
    private static Bitmap defaultBackBitmap;
    private static boolean defaultBackLoaded;

    private final RectF rect = new RectF();
    private final RectF dstRect = new RectF();
    private final Path path = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();

    private final float density;
    private final boolean success;
    private final String amountText;
    private final String balanceText;
    /** 真实卡面（可空，空则正面用示例卡兜底）。 */
    private final Bitmap cardFace;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long startTime;
    private boolean started;
    private Runnable onFinish;
    private boolean finishPosted;
    private int drawCount;

    /** 时间轴驱动：与 {@code onDraw} 解耦，保证结束回调一定触发。 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (finished()) {
                invalidate();
                postFinish();
                return;
            }
            invalidate();
            handler.postDelayed(this, 16L);
        }
    };

    public IslandOverlay(Context context, String amount, String balance,
                         boolean success, Bitmap cardFace) {
        super(context);
        this.density = getResources().getDisplayMetrics().density;
        this.amountText = amount == null ? "" : amount;
        this.balanceText = balance == null ? "" : balance;
        this.success = success;
        this.cardFace = cardFace;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** 动画结束时回调（用于把窗口移除）。 */
    public void setOnFinish(Runnable runnable) {
        this.onFinish = runnable;
    }

    /** 外部强制结束（例如宿主判定的超时兜底）。 */
    public void finishNow() {
        postFinish();
    }

    /** 已经绘制过的帧数。SystemUI 侧据此判断窗口是否真的在渲染。 */
    public int getDrawCount() {
        return drawCount;
    }

    /**
     * 描述这次动画「两面各画了什么」，用于日志自证。
     *
     * <p>岛窗口是 {@code TYPE_SECURE_SYSTEM_OVERLAY}（安全层），系统禁止截屏与录屏捕获，
     * 因此没有办法靠截图验证画面对不对；只能让动画自己把内容报出来。
     * 例如 {@code 卡背=默认卡面资源(700x441) 卡正面=真实卡面(384x242)}。</p>
     */
    public String describeContent() {
        String back = defaultBackBitmap() != null
                ? ("默认卡面资源(" + defaultBackBitmap.getWidth() + "x"
                        + defaultBackBitmap.getHeight() + ")")
                : "渐变兜底(资源缺失)";
        String front = cardFace != null && !cardFace.isRecycled()
                ? ("真实卡面(" + cardFace.getWidth() + "x" + cardFace.getHeight() + ")")
                : "默认卡面(未取到真实卡面)";
        return "卡背=" + back + " 卡正面=" + front;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 每次挂上窗口都从头播：SystemUI 侧会「换窗口类型重试」，
        // 重新 addView 也会走这里，动画不该接着上一次的进度继续。
        started = true;
        startTime = SystemClock.uptimeMillis();
        finishPosted = false;
        drawCount = 0;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 只停时间轴。这里<b>不能</b>把 finishPosted 置位 ——
        // 「换窗口类型」时宿主会先摘掉再挂上，置位会让后续真正播完的动画
        // 再也回调不到移除逻辑。
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = Math.round(360f * density);
        }
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = Math.round(360f * density);
        }
        // 视图高度 = 岛自身（含顶部边距）+ 底部 padding。
        // 探出动画的行程正好是 PILL_TOP + PILL_HEIGHT：隐藏态整块岛
        // 都在窗口上边界之外（被 Surface 裁剪），展开态落在原位。
        setMeasuredDimension(width, Math.round(
                (PILL_TOP_DP + PILL_HEIGHT_DP + BOTTOM_PADDING_DP) * density));
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawCount++;

        long t = elapsed();
        float enter = fraction(t, ENTER_START, ENTER_END);
        float exit = fraction(t, HOLD_END, EXIT_END);

        float alpha = Math.min(fraction(t, 0L, 200L), 1f) * (1f - exit);
        // 1.6.8：进入 / 退出改为「从顶部一个点逐渐放大成一个岛」。
        // 绕「岛顶边中点」缩放：该点固定不动，岛自顶部向下长出来（消失时缩回该点）。
        // 相比此前「整块岛从窗口上边界外滑入」，这个效果更像灵动岛从顶部「挤」出来。
        float scaleIn = POINT_SCALE + (1f - POINT_SCALE) * easeOutPeek(enter);
        float scaleOut = 1f - (1f - POINT_SCALE) * easeIn(exit);
        float scale = scaleIn * scaleOut;

        if (alpha <= 0f || scale <= 0.01f) {
            return;
        }

        int save = canvas.save();
        canvas.scale(scale, scale, getWidth() * 0.5f, PILL_TOP_DP * density);

        drawPill(canvas, alpha);
        drawCard(canvas, alpha, t);
        drawStatus(canvas, alpha, t);
        drawTexts(canvas, alpha, t);

        canvas.restoreToCount(save);
    }

    private void postFinish() {
        if (finishPosted) {
            return;
        }
        finishPosted = true;
        handler.removeCallbacks(ticker);
        final Runnable callback = onFinish;
        if (callback == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.run();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 各图层
    // ------------------------------------------------------------------

    private void drawPill(Canvas canvas, float alpha) {
        float w = PILL_WIDTH_DP * density;
        float h = PILL_HEIGHT_DP * density;
        float radius = PILL_RADIUS_DP * density;
        float left = (getWidth() - w) * 0.5f;
        float top = PILL_TOP_DP * density;
        rect.set(left, top, left + w, top + h);
        paint.setShader(null);
        paint.setColor(applyAlpha(PILL_COLOR, alpha));
        canvas.drawRoundRect(rect, radius, radius, paint);

        // 玻璃质感：顶部一条极淡的高光
        int save = canvas.save();
        float highlight = 0.5f * h;
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + highlight);
        paint.setShader(new LinearGradient(0f, rect.top, 0f, rect.top + highlight,
                new int[]{applyAlpha(0x24FFFFFF, alpha), applyAlpha(0x00FFFFFF, alpha)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
        canvas.restoreToCount(save);
    }

    private void drawCard(Canvas canvas, float alpha, long t) {
        float in = fraction(t, CARD_IN_START, CARD_IN_END);
        float flip = fraction(t, FLIP_START, FLIP_END);
        if (in <= 0f) {
            return;
        }

        float w = CARD_WIDTH_DP * density;
        float h = CARD_HEIGHT_DP * density;
        float radius = CARD_RADIUS_DP * density;
        float cx = getWidth() * 0.5f;
        float cy = (PILL_TOP_DP + CARD_TOP_DP + CARD_HEIGHT_DP * 0.5f) * density;

        float scale = 0.86f + 0.14f * easeOut(in);
        float cardAlpha = alpha * in;

        // 整段翻转拆成两半，两半都只转 0°~90°，因此<b>永远不产生左右镜像</b>：
        //   前半程：卡背从 0° 转到 90°（侧对着观众，等于「转走了」）
        //   后半程：卡正面从 -90° 转回 0°（侧着转进来，最后正对观众）
        // 这样就不必去「抵消 rotateY(180°) 的镜像」—— 那种做法一旦算错，
        // 卡面就会左右颠倒（看着像印反了）。
        float progress = easeInOut(flip);
        // 已翻到「卡正面」那一面：1.6.3 起两面调换 —— 翻转前先出现的一面是卡背
        // （系统默认卡面），翻过去之后才落在卡正面（主卡面）。
        // 之前是「先主卡面、后纯黑」，被判断为两面装反了。
        boolean front = progress > 0.5f;
        float half = front ? (1f - progress) * 2f : progress * 2f;
        float angle = front ? -90f * half : 90f * half;
        float flipFade = 1f;
        float edge = Math.abs(progress - 0.5f) * 2f; // 0=正好侧对观众，1=两面
        if (edge < 0.25f) {
            flipFade = 1f - (0.25f - edge) / 0.25f * 0.45f;
        }

        int save = canvas.save();
        camera.save();
        camera.rotateY(angle);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-cx, -cy);
        matrix.postTranslate(cx, cy);
        matrix.postScale(scale, scale, cx, cy);
        canvas.concat(matrix);

        rect.set(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);

        if (front) {
            // 卡正面（翻转后落在观众眼前的这一面）：绘制真实卡面
            if (cardFace != null && !cardFace.isRecycled()) {
                drawFaceCard(canvas, cardAlpha * flipFade, radius, w, h);
            } else {
                // 拿不到卡面时用默认卡面兜底，避免出现空白
                drawDefaultCardFace(canvas, cardAlpha * flipFade, radius, w, h);
            }
        } else {
            // 卡背（翻转前先出现的一面）：1.6.3 起显示系统默认卡面（此前是纯黑）
            drawDefaultCardFace(canvas, cardAlpha * flipFade, radius, w, h);
        }

        canvas.restoreToCount(save);
    }

    /**
     * 系统默认卡面（作卡背，同时兼任拿不到真实卡面时的兜底）。
     *
     * <p>优先绘制用户提供的卡背设计图 {@code R.drawable.card_default_back}
     * （主色 #177EE6 + 居中白色徽标），按 centerCrop 填满卡片并裁成圆角；
     * 万一资源缺失，退回代码画的蓝紫渐变卡，保证任何情况下都有卡面。</p>
     */
    private void drawDefaultCardFace(Canvas canvas, float cardAlpha, float radius,
                                     float w, float h) {
        Bitmap back = defaultBackBitmap();
        if (back != null && !back.isRecycled()) {
            int save = canvas.save();
            path.reset();
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.clipPath(path);
            // centerCrop：按较大的缩放比铺满，多出来的部分裁掉（源比例与卡片几乎一致）
            float scale = Math.max(rect.width() / back.getWidth(),
                    rect.height() / back.getHeight());
            float dw = back.getWidth() * scale;
            float dh = back.getHeight() * scale;
            float left = rect.centerX() - dw * 0.5f;
            float top = rect.centerY() - dh * 0.5f;
            dstRect.set(left, top, left + dw, top + dh);
            paint.setShader(null);
            paint.setAlpha(Math.round(255 * cardAlpha));
            paint.setFilterBitmap(true);
            canvas.drawBitmap(back, null, dstRect, paint);
            // 顶部受光，让卡背也有体积感；渐变已含 alpha，故先复位画笔 alpha
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(0f, rect.top, 0f, rect.top + h * 0.4f,
                    new int[]{applyAlpha(0x1FFFFFFF, cardAlpha), applyAlpha(0x00FFFFFF, cardAlpha)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            canvas.restoreToCount(save);
            return;
        }
        drawFallbackGradientCard(canvas, cardAlpha, radius, w, h);
    }

    /**
     * 读取（并缓存）卡背资源；读不到返回 null。
     *
     * <p><b>不能直接用 {@code R.drawable.*}。</b>这段代码会被注入到 SystemUI 进程里跑，
     * 而 SystemUI 的 {@code Resources} 是它自己的资源表，里面没有本模块的资源 id，
     * 直接 decode 会失败（实测日志就是 {@code 卡背=渐变兜底(资源缺失)}）。
     * 因此必须「借」模块自己的 Resources：先用自身 Resources 试（模块进程内预览时可用），
     * 再用 {@code createPackageContext} 拿模块的 Context 去取。</p>
     */
    private Bitmap defaultBackBitmap() {
        if (!defaultBackLoaded) {
            synchronized (IslandOverlay.class) {
                if (!defaultBackLoaded) {
                    String pkg = "com.liquidglass.mipay";
                    defaultBackBitmap = decodeDrawable(getResources(), pkg);
                    if (defaultBackBitmap == null) {
                        try {
                            Context module = getContext().createPackageContext(pkg, 0);
                            if (module != null) {
                                defaultBackBitmap = decodeDrawable(module.getResources(), pkg);
                            }
                        } catch (Throwable ignored) {
                            // 模块没装 / 拿不到就退回渐变兜底
                        }
                    }
                    defaultBackLoaded = true;
                }
            }
        }
        return defaultBackBitmap;
    }

    /** 按资源名从指定 Resources 里解码图片；取不到返回 null。 */
    private Bitmap decodeDrawable(android.content.res.Resources resources, String pkg) {
        if (resources == null) {
            return null;
        }
        try {
            int id = resources.getIdentifier("card_default_back", "drawable", pkg);
            if (id == 0) {
                return null;
            }
            return BitmapFactory.decodeResource(resources, id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 兜底：代码绘制的蓝紫渐变卡（资源缺失时才用）。 */
    private void drawFallbackGradientCard(Canvas canvas, float cardAlpha, float radius,
                                          float w, float h) {
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{0xFF2B4BD8, 0xFF8A3BD6}, null, Shader.TileMode.CLAMP));
        paint.setAlpha((int) (255 * cardAlpha));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        // 高光斜带
        paint.setColor(applyAlpha(0x40FFFFFF, cardAlpha));
        Path band = new Path();
        band.moveTo(rect.left + w * 0.06f, rect.bottom - h * 0.30f);
        band.lineTo(rect.left + w * 0.56f, rect.top + h * 0.22f);
        band.lineTo(rect.left + w * 0.78f, rect.top + h * 0.22f);
        band.lineTo(rect.left + w * 0.28f, rect.bottom - h * 0.30f);
        band.close();
        canvas.drawPath(band, paint);

        // 芯片
        paint.setColor(applyAlpha(0x55FFD98A, cardAlpha));
        RectF chip = new RectF(rect.left + w * 0.10f, rect.top + h * 0.30f,
                rect.left + w * 0.10f + h * 0.17f, rect.top + h * 0.30f + h * 0.13f);
        canvas.drawRoundRect(chip, h * 0.02f, h * 0.02f, paint);

        // 顶部柔光
        int save = canvas.save();
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(path);
        paint.setShader(new LinearGradient(0f, rect.top, 0f, rect.top + h * 0.45f,
                new int[]{applyAlpha(0x26FFFFFF, cardAlpha), applyAlpha(0x00FFFFFF, cardAlpha)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, paint);
        paint.setShader(null);
        canvas.restoreToCount(save);
    }

    /** 卡正面：绘制真实卡面（圆角裁剪 + 顶部柔光）。 */
    private void drawFaceCard(Canvas canvas, float cardAlpha, float radius, float w, float h) {
        paint.setShader(null);
        paint.setAlpha(Math.round(255 * cardAlpha));
        paint.setFilterBitmap(true);

        int save = canvas.save();
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(path);
        // fitXY 拉伸：捕获的卡面比例与显示区域几乎一致，形变可忽略
        canvas.drawBitmap(cardFace, null, rect, paint);
        // 顶部受光，让真实卡面也带一点体积感。
        // 这里的渐变颜色已含 cardAlpha，故把画笔 alpha 复位，避免衰减两次。
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0f, rect.top, 0f, rect.top + h * 0.5f,
                new int[]{applyAlpha(0x26FFFFFF, cardAlpha), applyAlpha(0x00FFFFFF, cardAlpha)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, paint);
        paint.setShader(null);
        canvas.restoreToCount(save);
        paint.setAlpha(255);
    }

    private void drawStatus(Canvas canvas, float alpha, long t) {
        float reveal = fraction(t, STATUS_START, STATUS_END);
        if (reveal <= 0f) {
            return;
        }
        float in = fraction(t, STATUS_START, STATUS_START + 180L);

        // 1.5.6：不再画彩色实心圆底，勾 / 叉都是纯白线条。
        float s = STATUS_SIZE_DP * density;
        float cx = getWidth() * 0.5f;
        float cy = (PILL_TOP_DP + STATUS_CENTER_Y_DP) * density;

        strokePaint.setColor(applyAlpha(0xFFFFFFFF, alpha * in));
        strokePaint.setStrokeWidth(4f * density);

        path.reset();
        if (success) {
            path.moveTo(cx - s * 0.24f, cy + s * 0.03f);
            path.lineTo(cx - s * 0.05f, cy + s * 0.20f);
            path.lineTo(cx + s * 0.26f, cy - s * 0.19f);
        } else {
            path.moveTo(cx - s * 0.18f, cy - s * 0.18f);
            path.lineTo(cx + s * 0.18f, cy + s * 0.18f);
        }
        pathMeasure.setPath(path, false);
        float length = pathMeasure.getLength();
        Path revealed = new Path();
        pathMeasure.getSegment(0f, length * reveal, revealed, true);
        if (!success && reveal > 0.5f) {
            Path second = new Path();
            second.moveTo(cx + s * 0.18f, cy - s * 0.18f);
            second.lineTo(cx - s * 0.18f, cy + s * 0.18f);
            PathMeasure measure = new PathMeasure(second, false);
            measure.getSegment(0f, measure.getLength() * (reveal - 0.5f) * 2f, second, true);
            revealed.addPath(second);
        }
        canvas.drawPath(revealed, strokePaint);
    }

    private void drawTexts(Canvas canvas, float alpha, long t) {
        float in = fraction(t, TEXT_START, TEXT_END);
        if (in <= 0f) {
            return;
        }
        float eased = easeOut(in);
        float dy = (1f - eased) * 9f * density;

        paint.setShader(null);
        paint.setTextAlign(Paint.Align.CENTER);
        if (!amountText.isEmpty()) {
            paint.setColor(applyAlpha(TEXT_PRIMARY, alpha * eased));
            paint.setTextSize(AMOUNT_SIZE_DP * density);
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            canvas.drawText(amountText, getWidth() * 0.5f,
                    (PILL_TOP_DP + AMOUNT_BASELINE_DP) * density + dy, paint);
        }
        if (!balanceText.isEmpty()) {
            paint.setColor(applyAlpha(TEXT_SECONDARY, alpha * eased));
            paint.setTextSize(BALANCE_SIZE_DP * density);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            canvas.drawText(balanceText, getWidth() * 0.5f,
                    (PILL_TOP_DP + BALANCE_BASELINE_DP) * density + dy, paint);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private long elapsed() {
        if (!started) {
            started = true;
            startTime = SystemClock.uptimeMillis();
        }
        return SystemClock.uptimeMillis() - startTime;
    }

    private boolean finished() {
        return elapsed() >= DURATION_MS;
    }

    private static float fraction(long t, long start, long end) {
        if (end <= start) {
            return t >= start ? 1f : 0f;
        }
        float value = (t - start) / (float) (end - start);
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    private static float easeOut(float value) {
        float inverse = 1f - value;
        return 1f - inverse * inverse;
    }

    private static float easeIn(float value) {
        return value * value;
    }

    private static float easeInOut(float value) {
        return value < 0.5f
                ? 2f * value * value
                : 1f - 2f * (1f - value) * (1f - value);
    }

    /**
     * 顶部探出的回弹缓动：c1 取小值（0.7），末端过冲约 4%，
     * 让岛「探得略过头再稳住」，而不是缩放动画那种大回弹。
     */
    private static float easeOutPeek(float value) {
        float c1 = 0.7f;
        float c3 = c1 + 1f;
        float x = value - 1f;
        return 1f + c3 * x * x * x + c1 * x * x;
    }

    private static int applyAlpha(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * (alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
