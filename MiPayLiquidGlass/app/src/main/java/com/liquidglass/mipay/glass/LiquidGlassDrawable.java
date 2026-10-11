package com.liquidglass.mipay.glass;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Choreographer;

import com.liquidglass.mipay.Config;

/**
 * 液态玻璃材质的核心 Drawable。
 *
 * <p>绘制顺序（自下而上）：</p>
 * <ol>
 *     <li>基础染色：自上而下由亮到暗的半透明白 / 蓝白渐变；</li>
 *     <li>顶部膨光（bloom）：模拟玻璃上表面的体积感；</li>
 *     <li>镜面流光：一条沿对角线扫过的柔和高光；</li>
 *     <li>底部内阴影：带来厚度与「玻璃砖」的立体感；</li>
 *     <li>AGSL 折射层（API 33+）：边缘菲涅尔高光 + RGB 色散彩边；</li>
 *     <li>边缘亮线（rim stroke）：左上来光、右下收暗的描边。</li>
 * </ol>
 *
 * <p>本类只依赖 Framework API，可在被 Hook 的进程与模块设置界面中同时使用。</p>
 */
public class LiquidGlassDrawable extends Drawable {

    private final Config cfg;
    private final float radiusPx;
    private final float density;
    /**
     * 叠加模式。
     *
     * <p>{@code false}：作为背景使用，需要自己铺底（基础染色 + 膨光 + 内阴影）；
     * {@code true}：作为前景叠加在「保留的卡面」之上，大幅压低铺底层，
     * 只保留镜面流光、折射高光与边缘亮线，使卡面图案依然清晰可辨。</p>
     */
    private final boolean overlay;
    /**
     * 深色模式形态（1.0.2）。
     *
     * <p>浅色模式用「白玻璃」（亮铺底 + 冷白高光）；深色模式改用「烟玻璃」
     * （深蓝黑铺底 + 更亮的边缘高光），保证余额框、充值按钮、顶部按钮
     * 在两种模式下都读得出玻璃质感，而不是在深色下变成一块发灰的污渍。</p>
     */
    private final boolean dark;
    /**
     * 玻璃强度倍率（1.0.5）。
     *
     * <p>用于叠加模式下「既要透明、又要有可见的毛玻璃质感」的场景：
     * 默认 {@code 1f}，余额面板会传入更大的值，把铺底与高光整体提亮，
     * 避免在深色页面背景上被看成一块黑。</p>
     */
    private final float strength;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path clipPath = new Path();
    private final Path rimPath = new Path();
    private final RectF rect = new RectF();
    private final RectF rimRect = new RectF();

    /** 流光相位，0..1。 */
    private float phase = 0.18f;
    private long lastFrameNanos;
    private boolean frameScheduled;

    public LiquidGlassDrawable(Config config, float radiusPx, float density) {
        this(config, radiusPx, density, false, false, 1f);
    }

    public LiquidGlassDrawable(Config config, float radiusPx, float density, boolean overlay) {
        this(config, radiusPx, density, overlay, false, 1f);
    }

    public LiquidGlassDrawable(Config config, float radiusPx, float density,
                               boolean overlay, boolean dark) {
        this(config, radiusPx, density, overlay, dark, 1f);
    }

    /**
     * @param strength 玻璃强度倍率（{@code >= 1f}），仅影响叠加模式的铺底与高光
     */
    public LiquidGlassDrawable(Config config, float radiusPx, float density,
                               boolean overlay, boolean dark, float strength) {
        this.cfg = config == null ? Config.defaults() : config;
        this.radiusPx = Math.max(0f, radiusPx);
        this.density = density <= 0f ? 3f : density;
        this.overlay = overlay;
        this.dark = dark;
        this.strength = strength < 1f ? 1f : strength;

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1f, density * 1.1f));
    }

    // ======================================================================
    // 绘制
    // ======================================================================

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        rect.set(bounds);
        float radius = Math.min(radiusPx, Math.min(rect.width(), rect.height()) * 0.5f);

        clipPath.reset();
        if (radius > 0f) {
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        } else {
            clipPath.addRect(rect, Path.Direction.CW);
        }

        int save = canvas.save();
        canvas.clipPath(clipPath);

        drawTint(canvas);
        drawBloom(canvas);
        drawSweep(canvas);
        drawInnerShade(canvas);
        // 叠加模式（卡面上的玻璃罩）不做色散折射：卡面本身边缘复杂，
        // 再叠 RGB 彩边会形成一圈「彩色乱线」。折射只在背景玻璃上生效。
        if (!overlay) {
            drawRefraction(canvas, radius);
        }

        canvas.restoreToCount(save);

        // 边缘亮线默认关闭（cfg.rimStroke）。1.0.3 及以前每个玻璃元素都描一圈白边，
        // 一屏几十个圆角容器叠加后就是满屏线条；叠加模式更是不描边，避免卡面出现双层轮廓。
        if (cfg.rimStroke && !overlay) {
            drawRim(canvas, radius);
        }
    }

    /** 1. 基础染色（叠加模式下压到极低，避免糊住卡面）。 */
    private void drawTint(Canvas canvas) {
        // 1.0.4：叠加模式的铺底进一步压低（0.26 → 0.18），让卡面上的玻璃更「轻」。
        // 1.0.5：再乘 strength —— 余额面板需要可见的毛玻璃质感，卡面玻璃罩则保持轻盈。
        int alpha = overlay ? Math.round(cfg.tintAlpha * 0.18f * strength) : cfg.tintAlpha;
        int top;
        int bottom;
        if (dark && !overlay) {
            // 烟玻璃：深蓝黑铺底，深色界面里读得出「一块有厚度的暗玻璃」
            top = withAlpha(0xFF1A2334, Math.min(255, Math.round(alpha * 1.35f)));
            bottom = withAlpha(0xFF0A1120, Math.min(255, Math.round(alpha * 1.1f)));
        } else {
            top = withAlpha(0xFFFFFFFF, alpha);
            bottom = withAlpha(0xFFD7E6FF, Math.round(alpha * 0.55f));
        }
        fillPaint.setShader(new LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, fillPaint);
    }

    /** 2. 顶部膨光 + 左上角体积高光。 */
    private void drawBloom(Canvas canvas) {
        // 1.0.4：叠加模式的膨光整体调暗，卡面高光不再刺眼。
        int base = overlay ? Math.round(cfg.tintAlpha * 0.26f * strength)
                : (dark ? Math.round(cfg.tintAlpha * 0.62f) : cfg.tintAlpha);
        int boost = overlay ? Math.round(22 * strength) : (dark ? 52 : 74);
        int top = withAlpha(0xFFFFFFFF, Math.min(255, base + boost));
        int bottom = withAlpha(0xFFFFFFFF, 0);
        bloomPaint.setShader(new LinearGradient(
                rect.left, rect.top,
                rect.left, rect.top + rect.height() * 0.62f,
                top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, bloomPaint);

        float cx = rect.left + rect.width() * 0.20f;
        float cy = rect.top + rect.height() * 0.08f;
        float radius = Math.max(rect.width(), rect.height()) * 0.62f;
        bloomPaint.setShader(new RadialGradient(
                cx, cy, radius,
                withAlpha(0xFFFFFFFF, overlay ? Math.round(14 * strength) : 44),
                withAlpha(0xFFFFFFFF, 0),
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect, bloomPaint);
    }

    /** 3. 沿对角线扫过的镜面流光。 */
    private void drawSweep(Canvas canvas) {
        float p = Math.max(0.02f, Math.min(0.98f, phase));
        float half = 0.20f;
        float a = Math.max(0f, p - half);
        float b = Math.max(a + 0.001f, p);
        float c = Math.min(1f, Math.max(b + 0.001f, p + half));

        int transparent = withAlpha(0xFFFFFFFF, 0);
        // 流光不随 strength 放大：面板上出现明显的斜向亮带会像「线条」。
        int bright = withAlpha(0xFFFFFFFF, overlay ? 28 : 48);
        sweepPaint.setShader(new LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                new int[]{transparent, bright, transparent},
                new float[]{a, b, c},
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect, sweepPaint);
    }

    /** 4. 底部内阴影。 */
    private void drawInnerShade(Canvas canvas) {
        float scale = overlay ? 0.22f : (dark ? 0.95f : 0.75f);
        int baseColor = dark ? 0xFF000000 : 0xFF061024;
        int shade = withAlpha(baseColor, Math.min(96, Math.round(cfg.tintAlpha * scale)));
        shadePaint.setShader(new LinearGradient(
                rect.left, rect.bottom - rect.height() * 0.55f,
                rect.left, rect.bottom,
                withAlpha(baseColor, 0), shade, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, shadePaint);
    }

    /** 5. AGSL 折射 / 色散层（Android 13+）。 */
    private void drawRefraction(Canvas canvas, float radius) {
        if (!cfg.refraction || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        RuntimeShader shader = GlassShader.get();
        if (shader == null) {
            return;
        }
        try {
            shader.setFloatUniform("resolution", rect.width(), rect.height());
            shader.setFloatUniform("radius", radius);
            shader.setFloatUniform("phase", phase);
            shader.setFloatUniform("intensity", 0.9f);

            shaderPaint.setShader(shader);
            int save = canvas.save();
            canvas.translate(rect.left, rect.top);
            canvas.drawRect(0f, 0f, rect.width(), rect.height(), shaderPaint);
            canvas.restoreToCount(save);
        } catch (Throwable ignored) {
            // 着色器不可用时静默降级到 Canvas 渐变实现
        }
    }

    /** 6. 边缘亮线：左上来光、右下收暗。 */
    private void drawRim(Canvas canvas, float radius) {
        float inset = rimPaint.getStrokeWidth() * 0.5f;
        rimRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
        if (rimRect.width() <= 0f || rimRect.height() <= 0f) {
            return;
        }
        float r = Math.max(0f, radius - inset);

        rimPath.reset();
        if (r > 0f) {
            rimPath.addRoundRect(rimRect, r, r, Path.Direction.CW);
        } else {
            rimPath.addRect(rimRect, Path.Direction.CW);
        }

        int rimBright = dark && !overlay ? 214 : 168;
        int rimDim = dark && !overlay ? 78 : 44;
        rimPaint.setShader(new LinearGradient(
                rimRect.left, rimRect.top, rimRect.right, rimRect.bottom,
                withAlpha(0xFFFFFFFF, rimBright), withAlpha(0xFFFFFFFF, rimDim),
                Shader.TileMode.CLAMP));
        canvas.drawPath(rimPath, rimPaint);
        // 刻意不再绘制「左上角高亮弧线」：那段弧与容器圆角对不齐时，
        // 会在圆角处产生明显的交叉乱线，是 1.0.3 之前「满屏线条」的主要来源之一。
    }

    // ======================================================================
    // 轮廓（供 View#setClipToOutline 使用）
    // ======================================================================

    @Override
    public void getOutline(Outline outline) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        float radius = Math.min(radiusPx, Math.min(bounds.width(), bounds.height()) * 0.5f);
        if (radius > 0f) {
            outline.setRoundRect(bounds, radius);
        } else {
            outline.setRect(bounds);
        }
    }

    // ======================================================================
    // 动效
    // ======================================================================

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            if (!isVisible() || !cfg.flow) {
                return;
            }
            if (lastFrameNanos != 0L) {
                float deltaSeconds = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
                phase = (phase + deltaSeconds * 0.16f) % 1f;
            }
            lastFrameNanos = frameTimeNanos;
            invalidateSelf();
            scheduleFrame();
        }
    };

    private void scheduleFrame() {
        if (frameScheduled) {
            return;
        }
        try {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } catch (Throwable ignored) {
            frameScheduled = false;
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible && cfg.flow) {
            lastFrameNanos = 0L;
            scheduleFrame();
        } else {
            frameScheduled = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try {
                    Choreographer.getInstance().removeFrameCallback(frameCallback);
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return changed;
    }

    // ======================================================================
    // Drawable 抽象方法
    // ======================================================================

    @Override
    public void setAlpha(int alpha) {
        // 透明度由 Config#tintAlpha 统一控制
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // 不支持外部颜色滤镜
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // ======================================================================

    private static int withAlpha(int color, int alpha) {
        int a = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
