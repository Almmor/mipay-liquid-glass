package com.liquidglass.mipay.glass;

import android.graphics.RuntimeShader;
import android.os.Build;

/**
 * 液态玻璃的 AGSL（Android Graphics Shading Language）着色器。
 *
 * <p>该着色器不采样背景纹理，而是基于圆角矩形的有符号距离场（SDF）程序化生成：</p>
 * <ul>
 *     <li>边缘菲涅尔式高光（rim light）：朝向光源的边缘更亮；</li>
 *     <li>RGB 三通道分别偏移 SDF，形成真实的色散（chromatic aberration）彩边；</li>
 *     <li>沿对角线缓慢移动的镜面流光（specular sweep）。</li>
 * </ul>
 *
 * <p>这样既得到了 iOS「Liquid Glass」最具辨识度的光学特征，又避免了逐帧抓取背景位图
 * 带来的性能与兼容性问题。着色器仅在 Android 13（API 33）及以上可用，
 * 低版本会自动退回纯 Canvas 渐变实现。</p>
 */
public final class GlassShader {

    /** AGSL 源码。 */
    public static final String AGSL = String.join("\n",
            "uniform float2 resolution;",
            "uniform float radius;",
            "uniform float phase;",
            "uniform float intensity;",
            "",
            "float rrect(float2 p, float2 halfSize, float r) {",
            "    float2 q = abs(p - halfSize) - (halfSize - r);",
            "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;",
            "}",
            "",
            "half4 main(float2 fragCoord) {",
            "    float2 halfSize = resolution * 0.5;",
            "    float r = min(radius, min(halfSize.x, halfSize.y));",
            "    float d = rrect(fragCoord, halfSize, r);",
            "    float inside = smoothstep(1.5, -0.5, d);",
            "    if (inside <= 0.001) {",
            "        return half4(0.0, 0.0, 0.0, 0.0);",
            "    }",
            "",
            "    float e = 2.0;",
            "    float2 g = float2(",
            "        rrect(fragCoord + float2(e, 0.0), halfSize, r)",
            "            - rrect(fragCoord - float2(e, 0.0), halfSize, r),",
            "        rrect(fragCoord + float2(0.0, e), halfSize, r)",
            "            - rrect(fragCoord - float2(0.0, e), halfSize, r)",
            "    );",
            "    float2 n = normalize(g + float2(0.00001));",
            "",
            "    float edge = clamp(1.0 + d / 3.0, 0.0, 1.0);",
            "    float2 lightDir = normalize(float2(-0.55, -0.83));",
            "    float lambert = clamp(dot(n, -lightDir), 0.0, 1.0);",
            "    float rim = edge * pow(lambert, 1.4);",
            "",
            "    float dR = rrect(fragCoord + float2(1.35, 0.7), halfSize, r);",
            "    float dB = rrect(fragCoord - float2(1.35, 0.7), halfSize, r);",
            "    float edgeR = clamp(1.0 + dR / 3.0, 0.0, 1.0);",
            "    float edgeB = clamp(1.0 + dB / 3.0, 0.0, 1.0);",
            "",
            "    float2 dir = normalize(float2(0.72, 1.0));",
            "    float t = dot(fragCoord / max(resolution, float2(1.0, 1.0)), dir);",
            "    float delta = t - phase;",
            "    float gloss = exp(-(delta * delta) / 0.0072);",
            "",
            "    float3 fringe = float3(edgeR, edge, edgeB) * edge * 0.09;",
            "    float a = rim * 0.60 + gloss * 0.16;",
            "    float3 rgb = float3(1.0, 1.0, 1.0) * a + fringe;",
            "    float alpha = clamp(a + max(fringe.r, max(fringe.g, fringe.b)), 0.0, 1.0)",
            "            * inside * intensity;",
            "    return half4(half3(rgb), half(alpha));",
            "}");

    private static RuntimeShader sCached;
    private static boolean sTried;

    private GlassShader() {
    }

    /** 惰性创建并缓存着色器；API < 33 或编译失败时返回 null。 */
    public static RuntimeShader get() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null;
        }
        if (!sTried) {
            sTried = true;
            try {
                sCached = new RuntimeShader(AGSL);
            } catch (Throwable ignored) {
                sCached = null;
            }
        }
        return sCached;
    }
}
