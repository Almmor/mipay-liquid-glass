#!/system/bin/sh
# ============================================================================
# 卸载清理：仅回收「由本模块自动安装」的 APK，避免误删用户手动安装的版本
# ============================================================================

MODDIR=${0%/*}
PKG=com.liquidglass.mipay
SENTINEL="$MODDIR/.apk_installed"

if [ -f "$SENTINEL" ]; then
    pm uninstall "$PKG" >/dev/null 2>&1
fi

rm -f /data/local/tmp/MiPayLiquidGlass.apk
rm -f /data/local/tmp/mi_pay_liquid_glass.log
