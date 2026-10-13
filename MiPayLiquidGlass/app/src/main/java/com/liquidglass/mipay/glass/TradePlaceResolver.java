package com.liquidglass.mipay.glass;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 站点定位：把「草桥」「首经贸」这类站名换成经纬度。
 *
 * <h3>为什么不用系统 Geocoder 打头阵</h3>
 * <p>实机日志证明它靠不住：{@code 草桥} 被解析到了 {@code 23.35,100.29}（缅甸一带），
 * 而 {@code 首经贸} 又只命中了同名大学而不是地铁站。所以主路径换成
 * <b>小米智能卡自己内置的高德服务 SDK</b>：反编译确认它带了
 * {@code com.amap.api.services.poisearch.PoiSearch} 等完整服务能力，
 * 且 Manifest 里就有它自己的 Android 平台 key。</p>
 *
 * <h3>两道准确性措施</h3>
 * <ol>
 *     <li><b>按当前位置收窄范围</b>：检索前用 {@link LocationManager} 的
 *     <i>最后已知位置</i>（不主动定位，只读缓存）算出城市中心，
 *     给高德 POI 加 {@code SearchBound}、给系统 Geocoder 加 bounding box。
 *     这一步正是修掉「草桥 → 缅甸」的关键；</li>
 *     <li><b>坐标系统一为 GCJ-02</b>：高德返回的就是 GCJ-02，与高德瓦片同一坐标系；
 *     系统 Geocoder 兜底时把 WGS-84 转成 GCJ-02，避免整体偏移几百米。</li>
 * </ol>
 *
 * <p>模块本身没有高德 SDK 的编译期依赖，因此这里全部走反射；
 * 任何一步失败都会退回系统 {@link Geocoder}，再不行就交给上层画「风格化地图」。</p>
 */
final class TradePlaceResolver {

    private static final String TAG = "MiPayLiquidGlass/Place";
    /** 单次 POI 检索的最长等待（毫秒）。 */
    private static final long POI_TIMEOUT_MS = 5000L;
    /** 以最后已知位置为中心的检索半径（米）。 */
    private static final int SEARCH_RADIUS_METER = 50000;
    /** 最后已知位置的最大可接受「年龄」（毫秒）：太旧说明用户早已换城市。 */
    private static final long LOCATION_MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    /** 是否已经尝试过设置高德隐私合规标记。 */
    private static boolean privacyTried;

    /** 解析结果。 */
    static final class Place {
        double lat;
        double lon;
        /** POI 标题（官方站名，如「草桥(地铁站)」）。 */
        String name;
        /** 完整地址（仅用于日志核对）。 */
        String address;
        /** 城市（如「北京市」）。 */
        String city;
        /** 行政区（如「丰台区」）。 */
        String district;
        /** 途经线路（如「10号线;19号线;大兴机场线」）。 */
        String lines;
        /** amap / geocoder。 */
        String source;
    }

    private TradePlaceResolver() {
    }

    /**
     * 同步解析一个站名（<b>必须在后台线程调用</b>）。
     *
     * @param type 交易类型（地铁 / 公交 …），用来决定补哪些后缀
     * @return 解析结果；失败返回 {@code null}
     */
    static Place locate(Context context, String keyword, String type) {
        if (context == null || keyword == null || keyword.trim().length() < 2) {
            return null;
        }
        String name = keyword.trim();
        double[] near = lastKnown(context);
        for (String candidate : candidates(name, type)) {
            Place place = byAmapPoi(context, candidate, near);
            if (place != null) {
                log("高德命中「" + name + "」→ 检索词=" + candidate
                        + " 名称=" + place.name + " 地址=" + place.address
                        + " 坐标=" + round(place.lat) + "," + round(place.lon));
                return place;
            }
        }
        Place place = byGeocoder(context, name, near);
        if (place != null) {
            log("系统 Geocoder 命中「" + name + "」→ 坐标="
                    + round(place.lat) + "," + round(place.lon) + " 名称=" + place.name);
        }
        return place;
    }

    // ======================================================================
    // 检索词
    // ======================================================================

    private static String[] candidates(String name, String type) {
        String lower = type == null ? "" : type;
        String base = normalize(name);
        // 已经带「站」的站名不再补后缀，顺手去掉「xx站公交站」这类重复，
        // 否则北京互通卡公交记录的站名很容易拼成检索不到的怪词。
        boolean station = base.endsWith("站");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (lower.contains("公交") || lower.contains("巴士") || lower.contains("轮渡")) {
            if (!station) {
                set.add(base + "公交站");
                set.add(base + "站");
            }
        } else if (lower.contains("地铁") || lower.contains("轨道") || lower.contains("轻轨")) {
            if (!station) {
                set.add(base + "地铁站");
                set.add(base + "站");
            }
        } else {
            if (!station) {
                set.add(base + "地铁站");
                set.add(base + "公交站");
                set.add(base + "站");
            }
        }
        set.add(base);
        return set.toArray(new String[0]);
    }

    /**
     * 把站名收拾成可检索的形态：去掉括号补充（「北京西站(北广场)」→「北京西站」）
     * 与所有空白，避免线路名/换乘提示把 POI 检索带偏。
     */
    private static String normalize(String raw) {
        String text = raw == null ? "" : raw.trim();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '（') {
                if (i > 0) {
                    text = text.substring(0, i);
                }
                break;
            }
        }
        return text.replaceAll("\\s+", "").trim();
    }

    // ======================================================================
    // 高德 POI（反射）
    // ======================================================================

    private static Place byAmapPoi(Context context, String keyword, double[] near) {
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> queryClass = Class.forName(
                    "com.amap.api.services.poisearch.PoiSearch$Query", false, loader);
            Class<?> searchClass = Class.forName(
                    "com.amap.api.services.poisearch.PoiSearch", false, loader);
            Class<?> listenerClass = Class.forName(
                    "com.amap.api.services.poisearch.PoiSearch$OnPoiSearchListener", false, loader);

            ensurePrivacy(context, loader);

            Object query = queryClass
                    .getConstructor(String.class, String.class, String.class)
                    .newInstance(keyword, "", "");
            queryClass.getMethod("setPageSize", int.class).invoke(query, 1);
            try {
                queryClass.getMethod("setCityLimit", boolean.class).invoke(query, false);
            } catch (Throwable ignored) {
                // 低版本没有这个方法
            }
            applySearchBound(loader, queryClass, query, near);

            Object search = searchClass.getConstructor(Context.class, queryClass)
                    .newInstance(context, query);
            // 等待回调期间必须保持 search / query 强引用，否则可能被 GC 掉导致回调不来
            final Object keepAlive = search;

            final CountDownLatch latch = new CountDownLatch(1);
            final Object[] holder = new Object[1];
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                if (args != null && args.length > 0 && args[0] != null
                                        && args[0].getClass().getName().endsWith("PoiResult")) {
                                    holder[0] = args[0];
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            latch.countDown();
                            return null;
                        }
                    });
            searchClass.getMethod("setOnPoiSearchListener", listenerClass).invoke(search, listener);
            searchClass.getMethod("searchPOIAsyn").invoke(search);

            if (!latch.await(POI_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log("高德 POI 超时：「" + keyword + "」");
                return null;
            }
            if (keepAlive == null) {
                return null;
            }
            Object result = holder[0];
            if (result == null) {
                return null;
            }
            Object pois = result.getClass().getMethod("getPois").invoke(result);
            if (!(pois instanceof List)) {
                return null;
            }
            for (Object poi : (List<?>) pois) {
                Place place = fromPoi(poi);
                if (place != null) {
                    return place;
                }
            }
            return null;
        } catch (Throwable throwable) {
            log("高德 POI 失败「" + keyword + "」：" + describe(throwable));
            return null;
        }
    }

    /** 给检索加一个以当前位置为中心的圆形范围，避免同名地名被解析到别的省市。 */
    private static void applySearchBound(ClassLoader loader, Class<?> queryClass,
                                         Object query, double[] near) {
        if (near == null) {
            return;
        }
        try {
            Class<?> boundClass = Class.forName(
                    "com.amap.api.services.poisearch.PoiSearch$SearchBound", false, loader);
            Class<?> latLonClass = Class.forName(
                    "com.amap.api.services.core.LatLonPoint", false, loader);
            Object center = latLonClass.getConstructor(double.class, double.class)
                    .newInstance(near[0], near[1]);
            Object bound = boundClass.getConstructor(latLonClass, int.class)
                    .newInstance(center, SEARCH_RADIUS_METER);
            queryClass.getMethod("setSearchBound", boundClass).invoke(query, bound);
        } catch (Throwable ignored) {
            // 加不上范围就退化为全国检索
        }
    }

    /**
     * 高德 SDK 9.2 起要求宿主先声明隐私合规。
     *
     * <p>小米智能卡自己就是高德服务的正式使用者（它内置了地图选点、公交站检索），
     * 用户也已经同意了它的隐私政策；这里只是在同一个进程内把这两个 SDK 标记补齐，
     * 让我们的检索能正常发出（否则 SDK 会直接抛「隐私合规未设置」）。</p>
     */
    private static void ensurePrivacy(Context context, ClassLoader loader) {
        if (privacyTried) {
            return;
        }
        privacyTried = true;
        try {
            Class<?> settings = Class.forName(
                    "com.amap.api.services.core.ServiceSettings", false, loader);
            try {
                settings.getMethod("updatePrivacyShow", Context.class, boolean.class, boolean.class)
                        .invoke(null, context, true, true);
            } catch (Throwable ignored) {
                // 老版本没有该方法
            }
            try {
                settings.getMethod("updatePrivacyAgree", Context.class, boolean.class)
                        .invoke(null, context, true);
            } catch (Throwable ignored) {
                // 老版本没有该方法
            }
        } catch (Throwable ignored) {
            // 没有 ServiceSettings 说明版本更老，无需设置
        }
    }

    private static Place fromPoi(Object poi) {
        if (poi == null) {
            return null;
        }
        try {
            Object point = poi.getClass().getMethod("getLatLonPoint").invoke(poi);
            if (point == null) {
                return null;
            }
            double lat = ((Number) point.getClass().getMethod("getLatitude").invoke(point))
                    .doubleValue();
            double lon = ((Number) point.getClass().getMethod("getLongitude").invoke(point))
                    .doubleValue();
            if (lat == 0d || lon == 0d) {
                return null;
            }
            Place place = new Place();
            place.lat = lat;
            place.lon = lon;
            place.source = "amap";
            place.name = stringOf(poi, "getTitle");
            String city = stringOf(poi, "getCityName");
            String district = stringOf(poi, "getAdName");
            String snippet = stringOf(poi, "getSnippet");
            place.city = city;
            place.district = district;
            place.lines = snippet;
            StringBuilder address = new StringBuilder();
            if (city != null) {
                address.append(city);
            }
            if (district != null) {
                address.append(district);
            }
            if (snippet != null) {
                address.append(snippet);
            }
            place.address = address.toString();
            return place;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringOf(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ======================================================================
    // 系统 Geocoder（兜底）
    // ======================================================================

    private static Place byGeocoder(Context context, String name, double[] near) {
        try {
            Geocoder geocoder = new Geocoder(context, Locale.CHINA);
            String[] queries = {name, name + "站"};
            for (String query : queries) {
                List<Address> list = near != null
                        ? geocoder.getFromLocationName(query, 1,
                                near[0] - 0.6, near[1] - 0.8, near[0] + 0.6, near[1] + 0.8)
                        : geocoder.getFromLocationName(query, 1);
                if (list == null || list.isEmpty()) {
                    continue;
                }
                Address address = list.get(0);
                if (!address.hasLatitude() || !address.hasLongitude()) {
                    continue;
                }
                double[] gcj = wgs84ToGcj02(address.getLatitude(), address.getLongitude());
                Place place = new Place();
                place.lat = gcj[0];
                place.lon = gcj[1];
                place.source = "geocoder";
                place.name = address.getFeatureName();
                place.address = address.getAddressLine(0);
                return place;
            }
        } catch (Throwable ignored) {
            // 走风格化降级
        }
        return null;
    }

    // ======================================================================
    // 位置与坐标转换
    // ======================================================================

    /** 读「最后已知位置」（不主动定位），转成 GCJ-02；太旧或没有就返回 null。 */
    private static double[] lastKnown(Context context) {
        try {
            LocationManager manager =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) {
                return null;
            }
            Location best = null;
            for (String provider : manager.getProviders(true)) {
                Location location;
                try {
                    location = manager.getLastKnownLocation(provider);
                } catch (Throwable ignored) {
                    continue;
                }
                if (location == null) {
                    continue;
                }
                if (best == null || location.getTime() > best.getTime()) {
                    best = location;
                }
            }
            if (best == null) {
                return null;
            }
            if (System.currentTimeMillis() - best.getTime() > LOCATION_MAX_AGE_MS) {
                return null;
            }
            return wgs84ToGcj02(best.getLatitude(), best.getLongitude());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final double PI = 3.1415926535897932384626;
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    /** WGS-84 → GCJ-02（高德/腾讯用的火星坐标系）。 */
    static double[] wgs84ToGcj02(double lat, double lon) {
        try {
            if (outOfChina(lat, lon)) {
                return new double[]{lat, lon};
            }
            double dLat = transformLat(lon - 105.0, lat - 35.0);
            double dLon = transformLon(lon - 105.0, lat - 35.0);
            double radLat = lat / 180.0 * PI;
            double magic = Math.sin(radLat);
            magic = 1 - EE * magic * magic;
            double sqrtMagic = Math.sqrt(magic);
            dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
            dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
            return new double[]{lat + dLat, lon + dLon};
        } catch (Throwable ignored) {
            return new double[]{lat, lon};
        }
    }

    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    // ======================================================================

    private static String describe(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 6) {
            if (builder.length() > 0) {
                builder.append(" <- ");
            }
            builder.append(current.getClass().getSimpleName())
                    .append('(').append(current.getMessage()).append(')');
            current = current.getCause();
        }
        return builder.toString();
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 100000) / 100000.0);
    }

    private static void log(String message) {
        try {
            android.util.Log.i(TAG, message);
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            de.robv.android.xposed.XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
