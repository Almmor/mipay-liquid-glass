#!/system/bin/sh
# ============================================================================
# post-fs-data：在 zygote 启动前尽早写入模糊能力属性
# ============================================================================

MODDIR=${0%/*}
LOG=/data/local/tmp/mi_pay_liquid_glass.log

# 1) 尽力设置（若设备属性为只读锁定，此步骤会失败但不影响开机）
resetprop -n ro.surface_flinger.supports_background_blur 1 2>/dev/null

# 2) 校验当前生效值，便于排障
CURRENT=$(getprop ro.surface_flinger.supports_background_blur)
echo "[post-fs-data] supports_background_blur=${CURRENT:-<unset>}" >> "$LOG"

# 3) 记录一次性的安装时间戳
date >> "$LOG" 2>/dev/null
