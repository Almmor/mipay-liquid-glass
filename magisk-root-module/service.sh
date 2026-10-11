#!/system/bin/sh
# ============================================================================
# late_start service：
#   1) 校验系统级窗口模糊能力
#   2) 自动安装 / 更新随包的 LSPosed 模块 APK
# ============================================================================

MODDIR=${0%/*}
LOG=/data/local/tmp/mi_pay_liquid_glass.log
APK="$MODDIR/MiPayLiquidGlass.apk"
PKG=com.liquidglass.mipay
SENTINEL="$MODDIR/.apk_installed"

echo "[service] start" >> "$LOG"

# ---------- 等待系统启动完成 ----------
i=0
while [ $i -lt 90 ]; do
    if [ "$(getprop sys.boot_completed)" = "1" ]; then
        break
    fi
    sleep 2
    i=$((i + 1))
done
sleep 5

# ---------- 校验窗口模糊能力 ----------
BLUR=$(getprop ro.surface_flinger.supports_background_blur)
echo "[service] supports_background_blur=${BLUR:-<unset>}" >> "$LOG"
if [ "$BLUR" != "1" ]; then
    echo "[service] WARN: 系统未开启窗口模糊，LSPosed 模块将退化为静态玻璃材质" >> "$LOG"
fi

# ---------- 自动安装随包 APK ----------
if [ -f "$APK" ] && [ -f "$SENTINEL" ] && [ -n "$(pm path $PKG 2>/dev/null)" ]; then
    # 已安装且已打过标记，且 APK 未发生变化 -> 跳过
    echo "[service] module apk already installed, skip" >> "$LOG"
else
    if [ ! -f "$APK" ]; then
        echo "[service] no bundled apk, skip install" >> "$LOG"
    else
        TMP=/data/local/tmp/MiPayLiquidGlass.apk
        cp -f "$APK" "$TMP"
        chmod 644 "$TMP"
        pm install -r -d "$TMP" >> "$LOG" 2>&1
        if [ -n "$(pm path $PKG 2>/dev/null)" ]; then
            touch "$SENTINEL"
            echo "[service] module apk installed ok" >> "$LOG"
            # 首次安装后给出一次提醒（service.sh 本身以 root 身份运行）
            cmd notification post -S bigtext -t "液态玻璃模块" lg_tag \
                "已安装，请在 LSPosed 中启用模块并勾选「小米智能卡」，然后重启该应用" >> "$LOG" 2>&1
        else
            echo "[service] module apk install FAILED" >> "$LOG"
        fi
        rm -f "$TMP"
    fi
fi

echo "[service] done" >> "$LOG"
