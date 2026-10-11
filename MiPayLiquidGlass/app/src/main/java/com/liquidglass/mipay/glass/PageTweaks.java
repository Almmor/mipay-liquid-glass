package com.liquidglass.mipay.glass;

import android.view.View;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 页面内容微调（1.1.1）。
 *
 * <p>有些提示文案属于「对老用户已经冗余」的引导，用户希望去掉。这些文案
 * <b>不在资源表里</b>（反编译两个 APK 都搜不到 `手机背面中上部 / 靠近读卡器`），
 * 说明是运行时写入 TextView 的动态文本，因此这里用「id + 文案」双通道识别：</p>
 *
 * <ul>
 *     <li><b>按 id</b>：刷卡页的提示行是 {@code notice_text}
 *     （{@code notice_icon} 这个 Lottie 图标下方那一行）。
 *     它的 XML 默认文案是 {@code @string/verify_finger}「请验证指纹」，
 *     运行时会按状态改成 NFC 位置提示等 —— 两者共用同一个 TextView，
 *     因此隐藏它即可同时去掉用户点名的两句；</li>
 *     <li><b>按文案兜底</b>：万一某些版本把提示渲染到别的 TextView，
 *     再用文本匹配补一刀。</li>
 * </ul>
 *
 * <p>只把命中的视图设为 {@code GONE}，不删除也不改动其内容，随时可以撤回。</p>
 */
public final class PageTweaks {

    /** 需要隐藏的控件 id（资源项名）。 */
    private static final Set<String> HIDE_IDS = new HashSet<>(Arrays.asList(
            "notice_text"
    ));

    /** 精确匹配的目标文案。 */
    private static final String[] HIDE_TEXTS_EXACT = {
            "请验证指纹",
            "請驗證指紋"
    };

    /**
     * 组合匹配的目标文案：必须同时命中「必须包含」与「必须含其一」两组关键词，
     * 避免误伤其它页面里正常的 NFC 引导（例如「请将手机靠近读卡器进行刷卡」）。
     */
    private static final String[] AND_ALL = {
            "靠近读卡器",
            "靠近讀卡機"
    };
    private static final String[] AND_ANY = {
            "背面",
            "中上部",
            "中上部"
    };

    private PageTweaks() {
    }

    /** 该视图是否需要隐藏。 */
    public static boolean shouldHide(View view) {
        if (view == null) {
            return false;
        }
        if (HIDE_IDS.contains(GlassChrome.entryNameOf(view))) {
            return true;
        }
        if (view instanceof TextView) {
            try {
                CharSequence text = ((TextView) view).getText();
                if (text != null && matchesText(text.toString())) {
                    return true;
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return false;
    }

    /** 命中即隐藏（用 GONE 而不是清空文本，避免影响依赖文本的布局测量）。 */
    public static void hide(View view) {
        try {
            view.setVisibility(View.GONE);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static boolean matchesText(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (String target : HIDE_TEXTS_EXACT) {
            if (text.contains(target)) {
                return true;
            }
        }
        boolean hasReader = false;
        for (String key : AND_ALL) {
            if (text.contains(key)) {
                hasReader = true;
                break;
            }
        }
        if (!hasReader) {
            return false;
        }
        for (String key : AND_ANY) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
