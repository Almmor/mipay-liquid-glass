package com.liquidglass.mipay;

import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 在「被 Hook 的进程」中读取模块配置。
 *
 * <p>之所以单独抽一个类，是为了让 {@code XSharedPreferences} 的类引用被隔离在这里：
 * 模块自身的设置界面（普通应用进程）永远不会加载本类，因此不会出现
 * {@code NoClassDefFoundError}。LSPosed 会负责让被 Hook 的进程能够读取模块的
 * SharedPreferences 文件。</p>
 */
public final class XposedPrefs {

    private XposedPrefs() {
    }

    /** 读取模块配置；失败时返回默认配置，绝不抛异常。 */
    public static Config load() {
        try {
            XSharedPreferences prefs =
                    new XSharedPreferences(Config.MODULE_PACKAGE, Config.PREFS_NAME);
            prefs.reload();
            return Config.read(prefs);
        } catch (Throwable ignored) {
            return Config.defaults();
        }
    }

    /** 仅用于兼容性检查。 */
    public static SharedPreferences raw() {
        try {
            XSharedPreferences prefs =
                    new XSharedPreferences(Config.MODULE_PACKAGE, Config.PREFS_NAME);
            prefs.reload();
            return prefs;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
