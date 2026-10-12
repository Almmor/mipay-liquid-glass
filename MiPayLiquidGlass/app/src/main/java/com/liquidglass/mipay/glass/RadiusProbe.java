package com.liquidglass.mipay.glass;

import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 圆角自动探测（1.0.3）。
 *
 * <p>玻璃叠加层要与被覆盖的卡面/控件严丝合缝，圆角必须一致。但小米智能卡的卡面
 * <b>不是</b>一个带圆角背景的 View —— 反编译 {@code layout/card_view_item} 显示，
 * 卡面图片 {@code ui.widget.CardView}（{@code AppCompatImageView} 子类）被套在
 * MiuiX 的 {@code miuix.smooth.SmoothFrameLayout2} 里，圆角定义在该容器的
 * {@code android:radius=@dimen/common_radius} 属性上。</p>
 *
 * <p>因此按下列顺序自动取圆角，命中即返回：</p>
 * <ol>
 *     <li><b>自身背景</b>的 {@code GradientDrawable} 圆角；</li>
 *     <li><b>自身 Outline</b>（API 21+）—— 系统实际用于裁剪的圆角矩形；</li>
 *     <li><b>自身反射</b>：MiuiX 圆角容器把 radius 存在私有字段 / getter 里；</li>
 *     <li>向<b>上层容器</b>递归（最多 {@value #MAX_ANCESTOR_DEPTH} 层）重复 1~3，
 *     这正是卡面图片拿到圆角的路径。</li>
 * </ol>
 *
 * <p>全部失败时返回 0，由调用方回退到配置里的默认圆角。所有探测都包在 try/catch 中，
 * 异常一律按「探测失败」处理，不影响被 Hook 的应用。</p>
 */
public final class RadiusProbe {

    /** 向上追溯容器的最大层数。 */
    private static final int MAX_ANCESTOR_DEPTH = 4;

    private RadiusProbe() {
    }

    /**
     * 自动探测视图的圆角半径（px）。
     *
     * @return 探测到的圆角；探测不到时返回 0
     */
    public static float resolve(View view) {
        return resolve(view, 0);
    }

    /**
     * 只取「本视图自身」的圆角（背景 / outline / 反射），<b>不向父级回溯</b>。
     *
     * <p>用途：叠加在元素上的玻璃高光必须与该元素自身背景的圆角一致。
     * 若沿用 {@link #resolve(View)} 的回溯行为，元素自己没有可读圆角时
     * 会拿到父容器的大圆角，于是「高光圆角」比「背景圆角」大一圈，
     * 在四角出现明显的错位（1.6.8 修复的正是这个）。</p>
     */
    public static float resolveOwn(View view) {
        if (view == null) {
            return 0f;
        }
        float radius = ownBackgroundRadius(view);
        if (radius > 0f) {
            return radius;
        }
        radius = outlineRadius(view);
        if (radius > 0f) {
            return radius;
        }
        return reflectiveRadius(view);
    }

    private static float resolve(View view, int depth) {
        if (view == null || depth > MAX_ANCESTOR_DEPTH) {
            return 0f;
        }

        float radius = ownBackgroundRadius(view);
        if (radius > 0f) {
            return radius;
        }
        radius = outlineRadius(view);
        if (radius > 0f) {
            return radius;
        }
        radius = reflectiveRadius(view);
        if (radius > 0f) {
            return radius;
        }

        // 卡面图片本身没有圆角，圆角定义在它的父容器（MiuiX 圆角 FrameLayout）上
        View parent = parentOf(view);
        return parent == null ? 0f : resolve(parent, depth + 1);
    }

    // ======================================================================
    // 来源 1：背景 Drawable 的圆角
    // ======================================================================

    private static float ownBackgroundRadius(View view) {
        Drawable background = view.getBackground();
        if (background instanceof DrawableContainer) {
            try {
                Drawable current = ((DrawableContainer) background).getCurrent();
                if (current != null && current != background) {
                    background = current;
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        if (!(background instanceof GradientDrawable)) {
            return 0f;
        }
        GradientDrawable gradient = (GradientDrawable) background;
        try {
            float radius = gradient.getCornerRadius();
            if (radius > 0f) {
                return radius;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            float[] radii = gradient.getCornerRadii();
            if (radii != null) {
                float max = 0f;
                for (float value : radii) {
                    if (value > max) {
                        max = value;
                    }
                }
                if (max > 0f) {
                    return max;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0f;
    }

    // ======================================================================
    // 来源 2：Outline（系统真实裁剪用的圆角）
    // ======================================================================

    private static float outlineRadius(View view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return 0f;
        }
        try {
            ViewOutlineProvider provider = view.getOutlineProvider();
            if (provider == null || provider == ViewOutlineProvider.BACKGROUND) {
                // 背景轮廓与来源 1 等价，无需重复
                return 0f;
            }
            Outline outline = new Outline();
            provider.getOutline(view, outline);
            if (outline.canClip() && outline.getRadius() > 0f) {
                return outline.getRadius();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0f;
    }

    // ======================================================================
    // 来源 3：反射读取 MiuiX 圆角容器的私有半径字段 / getter
    // ======================================================================

    /**
     * MiuiX 的 {@code miuix.smooth.SmoothFrameLayout2} 等圆角容器把 radius 存在私有成员里，
     * 没有公开 API。这里扫描 {@code getRadius} / {@code getCornerRadius} 之类的 getter，
     * 再退而求其次扫描名字含 {@code radius} / {@code corner} 的数值字段。
     */
    private static float reflectiveRadius(View view) {
        Class<?> type = view.getClass();
        float fromGetter = readRadiusGetter(view, type);
        if (fromGetter > 0f) {
            return fromGetter;
        }
        return readRadiusField(view, type);
    }

    private static float readRadiusGetter(View view, Class<?> type) {
        Class<?> current = type;
        while (current != null && current != View.class && current != Object.class) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable ignored) {
                methods = new Method[0];
            }
            for (Method method : methods) {
                String name = method.getName();
                if (method.getParameterTypes().length != 0) {
                    continue;
                }
                boolean looksLikeRadius = name.equals("getRadius")
                        || name.equals("getCornerRadius")
                        || name.equals("getCornerRadii")
                        || (name.startsWith("get") && name.toLowerCase(java.util.Locale.ROOT).contains("radius"));
                if (!looksLikeRadius) {
                    continue;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType != float.class && returnType != int.class
                        && returnType != Float.class && returnType != Integer.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(view);
                    float radius = toPositiveFloat(value);
                    if (radius > 0f) {
                        return radius;
                    }
                } catch (Throwable ignored) {
                    // 继续找下一个
                }
            }
            current = current.getSuperclass();
        }
        return 0f;
    }

    private static float readRadiusField(View view, Class<?> type) {
        Class<?> current = type;
        while (current != null && current != View.class && current != Object.class) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                fields = new Field[0];
            }
            for (Field field : fields) {
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (!name.contains("radius") && !name.contains("corner")) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                if (fieldType != float.class && fieldType != int.class
                        && fieldType != Float.class && fieldType != Integer.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    float radius = toPositiveFloat(field.get(view));
                    if (radius > 0f) {
                        return radius;
                    }
                } catch (Throwable ignored) {
                    // 继续找下一个
                }
            }
            current = current.getSuperclass();
        }
        return 0f;
    }

    private static float toPositiveFloat(Object value) {
        if (value instanceof Number) {
            float result = ((Number) value).floatValue();
            return result > 0f ? result : 0f;
        }
        return 0f;
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private static View parentOf(View view) {
        try {
            if (view.getParent() instanceof View) {
                return (View) view.getParent();
            }
            // 某些「逻辑父级」不是直接 parent，退回用父 ViewGroup 推断
            if (view instanceof ViewGroup) {
                return null;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }
}
