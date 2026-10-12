package com.liquidglass.mipay.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 交易地图渲染：用高德栅格瓦片拼出站点周边的小地图。
 *
 * <h3>为什么是高德瓦片</h3>
 * <p>实机联网验证：{@code tile.openstreetmap.org} 与 {@code nominatim.openstreetmap.org}
 * 在本机与设备上都是连接超时（不可达），而
 * {@code webrd0x.is.autonavi.com} 的栅格瓦片返回 HTTP 200，
 * 中文标注、无需 key，正好适合「交易地图」这种用途。</p>
 *
 * <h3>两种画法</h3>
 * <ul>
 *     <li>{@link #MODE_ROUTE}：地铁「哪到哪」—— 两点连线 + 两端图钉 + 站名标签；</li>
 *     <li>{@link #MODE_POINT}：公交的「交易地点」、地铁「不完整交易」的单一站点 —— 居中一个图钉。</li>
 * </ul>
 * <p>经纬度由 {@link TradePlaceResolver} 提供（高德 POI，GCJ-02，与瓦片同一坐标系），
 * 这里只负责拼图与绘制，不再自己做地理编码。</p>
 */
final class TradeMapRenderer {

    /** 哪到哪：两点连线。 */
    static final int MODE_ROUTE = 1;
    /** 单点：公交交易地点 / 不完整交易的站点。 */
    static final int MODE_POINT = 0;

    /** 起点图钉色（澎湃 OS 主题蓝）。 */
    static final int PIN_START = 0xFF3482FF;
    /** 终点图钉色（红），一眼区分「哪到哪」。 */
    static final int PIN_END = 0xFFFF3B30;
    /** 单点图钉色（公交交易地点 / 不完整交易站点）。 */
    static final int PIN_SINGLE = 0xFF3482FF;

    /** 高德栅格瓦片（路网图，中文标注，无需 key）。 */
    private static final String TILE_URL =
            "https://webrd0%d.is.autonavi.com/appmaptile"
                    + "?lang=zh_cn&size=1&scale=1&style=8&x=%d&y=%d&z=%d";
    private static final int TILE = 256;
    private static final int TILE_CACHE_MAX = 240;
    private static final int MAP_CACHE_MAX = 16;

    private static final Map<String, Bitmap> TILE_CACHE = new HashMap<>();
    private static final Map<String, Bitmap> MAP_CACHE = new HashMap<>();

    private TradeMapRenderer() {
    }

    static Bitmap cached(String key) {
        if (key == null) {
            return null;
        }
        synchronized (MAP_CACHE) {
            return MAP_CACHE.get(key);
        }
    }

    /**
     * 拼一张 {@code width x height} 的地图。
     *
     * <p><b>必须在后台线程调用</b>（涉及网络下载）。</p>
     *
     * @param points  经纬度（GCJ-02），1 个点即单点模式
     * @param labels  与 points 一一对应的站名，用于在地图上打标签
     * @param mode    {@link #MODE_ROUTE} / {@link #MODE_POINT}
     * @param cornerPx 圆角半径（px）
     * @return 地图位图；瓦片一张都没取到时返回 {@code null}
     */
    static Bitmap render(List<double[]> points, List<String> labels, int mode,
                         int width, int height, float cornerPx, boolean dark) {
        if (points == null || points.isEmpty() || width <= 0 || height <= 0) {
            return null;
        }
        String cacheKey = key(points, mode, width, height);
        synchronized (MAP_CACHE) {
            Bitmap hit = MAP_CACHE.get(cacheKey);
            if (hit != null) {
                return hit;
            }
        }
        try {
            int zoom = chooseZoom(points, width, height, mode);
            double[] center = centerOf(points);
            double[] centerPx = latLonToPixel(center[0], center[1], zoom);
            double left = centerPx[0] - width / 2.0;
            double top = centerPx[1] - height / 2.0;

            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(dark ? 0xFF16202B : 0xFFE8EAED);

            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            int x0 = (int) Math.floor(left / TILE);
            int y0 = (int) Math.floor(top / TILE);
            int x1 = (int) Math.floor((left + width - 1) / TILE);
            int y1 = (int) Math.floor((top + height - 1) / TILE);
            int maxTileIndex = (1 << zoom) - 1;
            int drawn = 0;
            for (int tx = x0; tx <= x1; tx++) {
                for (int ty = y0; ty <= y1; ty++) {
                    if (tx < 0 || ty < 0 || tx > maxTileIndex || ty > maxTileIndex) {
                        continue;
                    }
                    Bitmap tile = tile(zoom, tx, ty);
                    if (tile == null) {
                        continue;
                    }
                    canvas.drawBitmap(tile,
                            (float) (tx * (double) TILE - left),
                            (float) (ty * (double) TILE - top), paint);
                    drawn++;
                }
            }
            if (drawn == 0) {
                out.recycle();
                return null;
            }

            List<float[]> marks = new ArrayList<>();
            for (double[] point : points) {
                double[] pixel = latLonToPixel(point[0], point[1], zoom);
                marks.add(new float[]{(float) (pixel[0] - left), (float) (pixel[1] - top)});
            }

            boolean route = mode == MODE_ROUTE && marks.size() >= 2;
            if (route) {
                drawRoute(canvas, marks.get(0), marks.get(marks.size() - 1));
            }
            for (int i = 0; i < marks.size(); i++) {
                int color = !route ? PIN_SINGLE : (i == 0 ? PIN_START : PIN_END);
                drawPin(canvas, marks.get(i)[0], marks.get(i)[1], color);
            }
            for (int i = 0; i < marks.size() && labels != null && i < labels.size(); i++) {
                // 第一个点标签在上、第二个点标签在下，避免两点靠近时互相压住
                drawLabel(canvas, marks.get(i)[0], marks.get(i)[1], labels.get(i), i == 0);
            }
            out = roundCorners(out, cornerPx);

            synchronized (MAP_CACHE) {
                if (MAP_CACHE.size() > MAP_CACHE_MAX) {
                    MAP_CACHE.clear();
                }
                MAP_CACHE.put(cacheKey, out);
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 降级用的「风格化地图」：不联网，画一层路网肌理 + 图钉 + 站名。
     *
     * <p>瓦片取不到时用，保证「交易详情里总有一块地图区域」，而不是一个空洞。</p>
     */
    static Bitmap fallback(List<String> labels, int mode, int width, int height,
                           float cornerPx, boolean dark) {
        try {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            paint.setShader(new LinearGradient(0, 0, width, height,
                    dark ? 0xFF1B2A3A : 0xFFDFEAF4,
                    dark ? 0xFF12202E : 0xFFC9DCEE, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            paint.setColor(dark ? 0x22FFFFFF : 0x66FFFFFF);
            paint.setStrokeWidth(Math.max(2f, height * 0.010f));
            for (int i = 1; i <= 4; i++) {
                canvas.drawLine(0, height * i / 5f, width, height * i / 5f, paint);
            }
            for (int i = 1; i <= 5; i++) {
                canvas.drawLine(width * i / 6f, 0, width * i / 6f, height, paint);
            }
            paint.setStrokeWidth(Math.max(3f, height * 0.016f));
            paint.setColor(dark ? 0x33FFFFFF : 0x88FFFFFF);
            canvas.drawLine(0, height * 0.72f, width, height * 0.34f, paint);

            String first = labels != null && labels.size() > 0 ? labels.get(0) : null;
            String second = labels != null && labels.size() > 1 ? labels.get(1) : null;
            if (second != null) {
                float x1 = width * 0.28f;
                float y1 = height * 0.42f;
                float x2 = width * 0.72f;
                float y2 = height * 0.62f;
                drawRoute(canvas, new float[]{x1, y1}, new float[]{x2, y2});
                drawPin(canvas, x1, y1, PIN_START);
                drawPin(canvas, x2, y2, PIN_END);
                drawLabel(canvas, x1, y1, first, true);
                drawLabel(canvas, x2, y2, second, false);
            } else {
                float x = width * 0.5f;
                float y = height * 0.5f;
                drawPin(canvas, x, y, PIN_SINGLE);
                drawLabel(canvas, x, y, first, true);
            }
            return roundCorners(out, cornerPx);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ======================================================================
    // 绘制
    // ======================================================================

    /**
     * 图钉：水滴形 + 白色描边 + 中心白圆点 + 投影。
     *
     * <p>起点天蓝、终点 iOS 红，配合下面的弧线，一眼能看出「从哪到哪」。</p>
     */
    private static void drawPin(Canvas canvas, float x, float y, int color) {
        float r = pinRadius(canvas);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Path path = new Path();
        path.moveTo(x, y + r * 1.80f);
        path.cubicTo(x - r * 1.30f, y + r * 0.40f, x - r, y - r * 0.22f, x, y - r * 1.05f);
        path.cubicTo(x + r, y - r * 0.22f, x + r * 1.30f, y + r * 0.40f, x, y + r * 1.80f);
        path.close();

        // 投影：先画一层半透明黑
        paint.setColor(0x40000000);
        canvas.save();
        canvas.translate(0f, r * 0.16f);
        canvas.drawPath(path, paint);
        canvas.restore();

        // 白色描边（比本体粗一圈）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(r * 0.60f);
        paint.setColor(0xFFFFFFFF);
        canvas.drawPath(path, paint);

        // 本体
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(r * 0.10f);
        paint.setColor(0x1F000000);
        canvas.drawPath(path, paint);

        // 中心白点
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(x, y - r * 0.12f, r * 0.38f, paint);
    }

    private static float pinRadius(Canvas canvas) {
        float r = canvas.getHeight() * 0.062f;
        if (r < 8f) {
            r = 8f;
        }
        if (r > 22f) {
            r = 22f;
        }
        return r;
    }

    /**
     * 起点到终点：一条轻微上拱的弧线（白色描边 + 天蓝芯），比直线更像「行程」。
     */
    private static void drawRoute(Canvas canvas, float[] a, float[] b) {
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1f) {
            return;
        }
        // 控制点取中点沿垂线偏移 16% 弦长，做出柔和的弧形
        float offset = length * 0.16f;
        float cx = (a[0] + b[0]) * 0.5f - dy / length * offset;
        float cy = (a[1] + b[1]) * 0.5f + dx / length * offset;

        Path path = new Path();
        path.moveTo(a[0], a[1]);
        path.quadTo(cx, cy, b[0], b[1]);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(Math.max(7f, canvas.getHeight() * 0.024f));
        canvas.drawPath(path, paint);
        paint.setColor(PIN_START);
        paint.setStrokeWidth(Math.max(3.5f, canvas.getHeight() * 0.013f));
        canvas.drawPath(path, paint);
    }

    /** 站名标签：白底圆角小药丸，压在图钉的上方或下方。 */
    private static void drawLabel(Canvas canvas, float x, float y, String text, boolean above) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float textSize = Math.max(13f, canvas.getHeight() * 0.032f);
        if (textSize > 26f) {
            textSize = 26f;
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSize);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        float textWidth = paint.measureText(text);
        float padH = textSize * 0.6f;
        float padV = textSize * 0.36f;
        float chipW = textWidth + padH * 2;
        float chipH = textSize + padV * 2;
        float pinHeight = pinRadius(canvas) * 1.80f;
        float centerY = above ? y - pinHeight - chipH * 0.5f - 4f : y + pinHeight + chipH * 0.5f + 4f;
        float left = x - chipW * 0.5f;
        if (left < 4f) {
            left = 4f;
        }
        if (left + chipW > canvas.getWidth() - 4f) {
            left = canvas.getWidth() - 4f - chipW;
        }
        float top = centerY - chipH * 0.5f;

        paint.setColor(0x33000000);
        canvas.drawRoundRect(new RectF(left, top + 2f, left + chipW, top + chipH + 2f),
                chipH * 0.5f, chipH * 0.5f, paint);
        paint.setColor(0xF2FFFFFF);
        canvas.drawRoundRect(new RectF(left, top, left + chipW, top + chipH),
                chipH * 0.5f, chipH * 0.5f, paint);
        paint.setColor(0xFF1C1C1E);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = top + chipH * 0.5f - (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText(text, left + padH, baseline, paint);
    }

    private static Bitmap roundCorners(Bitmap bitmap, float radius) {
        if (bitmap == null || radius <= 0f) {
            return bitmap;
        }
        try {
            Bitmap rounded = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(rounded);
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                    radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawBitmap(bitmap, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            bitmap.recycle();
            return rounded;
        } catch (Throwable ignored) {
            return bitmap;
        }
    }

    // ======================================================================
    // 坐标
    // ======================================================================

    private static int chooseZoom(List<double[]> points, int width, int height, int mode) {
        if (mode == MODE_POINT || points.size() < 2) {
            return 16;
        }
        double limit = Math.min(width, height) * 0.55;
        for (int zoom = 16; zoom > 10; zoom--) {
            double[] base = latLonToPixel(points.get(0)[0], points.get(0)[1], zoom);
            double span = 0;
            for (double[] point : points) {
                double[] pixel = latLonToPixel(point[0], point[1], zoom);
                span = Math.max(span, Math.abs(pixel[0] - base[0]));
                span = Math.max(span, Math.abs(pixel[1] - base[1]));
            }
            if (span <= limit) {
                return zoom;
            }
        }
        return 11;
    }

    private static double[] centerOf(List<double[]> points) {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (double[] point : points) {
            minLat = Math.min(minLat, point[0]);
            maxLat = Math.max(maxLat, point[0]);
            minLon = Math.min(minLon, point[1]);
            maxLon = Math.max(maxLon, point[1]);
        }
        return new double[]{(minLat + maxLat) / 2, (minLon + maxLon) / 2};
    }

    /** 经纬度 → 世界像素坐标（Web 墨卡托）。 */
    private static double[] latLonToPixel(double lat, double lon, int zoom) {
        double world = TILE * (double) (1 << zoom);
        double x = (lon + 180.0) / 360.0 * world;
        double sin = Math.sin(Math.toRadians(lat));
        double y = (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * world;
        return new double[]{x, y};
    }

    // ======================================================================
    // 瓦片下载
    // ======================================================================

    private static Bitmap tile(int zoom, int x, int y) {
        String key = zoom + "/" + x + "/" + y;
        synchronized (TILE_CACHE) {
            Bitmap hit = TILE_CACHE.get(key);
            if (hit != null) {
                return hit;
            }
        }
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            int host = 1 + (Math.abs(x + y) % 4);
            URL url = new URL(String.format(Locale.US, TILE_URL, host, x, y, zoom));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) MiPayLiquidGlass/1.7.2");
            input = new BufferedInputStream(connection.getInputStream());
            byte[] data = readAll(input);
            if (data.length == 0) {
                return null;
            }
            Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap != null) {
                synchronized (TILE_CACHE) {
                    if (TILE_CACHE.size() > TILE_CACHE_MAX) {
                        TILE_CACHE.clear();
                    }
                    TILE_CACHE.put(key, bitmap);
                }
            }
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        } finally {
            close(input);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream input) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return buffer.toByteArray();
    }

    private static void close(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static String key(List<double[]> points, int mode, int width, int height) {
        StringBuilder builder = new StringBuilder();
        builder.append(mode).append('@').append(width).append('x').append(height);
        for (double[] point : points) {
            builder.append('|').append(Math.round(point[0] * 100000))
                    .append(',').append(Math.round(point[1] * 100000));
        }
        return builder.toString();
    }
}
