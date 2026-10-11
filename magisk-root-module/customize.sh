#!/system/bin/sh
# ============================================================================
# Mi Pay Liquid Glass (Root) - 安装脚本
# 由 Magisk / KernelSU 在刷入 zip 时执行
# ============================================================================

SKIPUNZIP=0

LOG=/data/local/tmp/mi_pay_liquid_glass.log
echo "[customize] install start" > "$LOG"

ui_print " "
ui_print "**********************************************"
ui_print "  Mi Pay Liquid Glass  (Root)"
ui_print "  小米智能卡 · 液态玻璃"
ui_print "**********************************************"
ui_print " "

SDK=$(getprop ro.build.version.sdk)
DEVICE=$(getprop ro.product.model)
ui_print "- 设备型号 : $DEVICE"
ui_print "- Android  : API $SDK"

if [ -n "$SDK" ] && [ "$SDK" -lt 31 ]; then
    ui_print " "
    ui_print "! 警告：设备低于 Android 12 (API 31)"
    ui_print "! 系统级「窗口背景模糊」不可用，"
    ui_print "! 液态玻璃将退化为静态渐变玻璃材质（仍然可用）。"
fi

ui_print " "
ui_print "- 本模块需要配合 LSPosed 框架使用"
ui_print "- 若同目录下存在 MiPayLiquidGlass.apk，"
ui_print "- 将会在开机后自动安装/更新该模块"
ui_print "- 安装完成后请重启设备"
ui_print " "

echo "[customize] sdk=$SDK device=$DEVICE" >> "$LOG"
