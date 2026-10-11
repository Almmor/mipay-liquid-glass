# ============================================================================
#  adb-liquid-glass.ps1
#  小米智能卡 · 液态玻璃 —— ADB 诊断 / 修复工具
#
#  用法（在本目录下打开 PowerShell）：
#     .\adb-liquid-glass.ps1 diag            # 全面体检：设备、Root、模糊属性、模块状态
#     .\adb-liquid-glass.ps1 overlay-list    # 列出所有持有「显示在其他应用上层」权限的应用
#     .\adb-liquid-glass.ps1 overlay-revoke  # 收回可疑应用的悬浮窗权限（解决「遮挡界面」提示）
#     .\adb-liquid-glass.ps1 restart-card    # 强制停止小米智能卡（让 LSPosed 重新注入）
#     .\adb-liquid-glass.ps1 open-manager    # 打开 LSPosed 管理器
#
#  注意：本脚本不使用 Root。凡标注「需要 Root」的能力（如直接修改 LSPosed 的
#        模块启用状态）ADB 侧无法完成，必须在 LSPosed 管理器里操作。
# ============================================================================

param(
    [Parameter(Position = 0)]
    [ValidateSet('diag', 'overlay-list', 'overlay-revoke', 'restart-card', 'open-manager')]
    [string]$Action = 'diag',

    # overlay-revoke 时指定要收回权限的包名；不传则使用内置的可疑清单
    [string[]]$Packages = @(),

    [string]$Serial = ''
)

$ErrorActionPreference = 'Continue'
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

# ---------- 目标常量 ----------
$PKG_MODULE  = 'com.liquidglass.mipay'
$PKG_CARD    = 'com.miui.tsmclient'
$PKG_WALLET  = 'com.mipay.wallet'
$PKG_MANAGER = 'org.lsposed.manager'

# 常见的「遮挡界面」元凶（第三方 / 常驻悬浮类）
$SUSPECTS = @(
    'com.newcall',                  # 第三方全屏来电秀
    'com.byyoung.setting',          # 第三方搞机工具
    'com.miui.contentextension',    # 传送门 / 内容中心
    'com.miui.personalassistant',   # 智能助理 / 负一屏
    'com.xiaomi.barrage',           # 弹幕通知
    'com.mfashiongallery.emag',     # 小米画报
    'com.xiaomi.touchservice',      # 触摸服务 / 游戏加速
    'com.miui.voicetrigger',        # 语音唤醒
    'com.miui.screenrecorder',      # 屏幕录制
    'com.xiaomi.minigame',          # 小游戏
    'com.xiaomi.macro'              # 自动化宏
)

# ---------- 定位 adb ----------
function Get-Adb {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw '未找到 adb.exe，请安装 Android SDK Platform-Tools 或把 adb 加入 PATH'
}

$Adb = Get-Adb
$AdbArgs = @()
if ($Serial) { $AdbArgs += @('-s', $Serial) }

function Invoke-Adb {
    param([string[]]$Cmd)
    return (& $Adb @($AdbArgs + $Cmd) 2>&1)
}

function Write-Section { param([string]$Text) Write-Host ''; Write-Host "== $Text ==" -ForegroundColor Cyan }
function Write-Key     { param([string]$K, [string]$V, [string]$Color = 'Gray')
    Write-Host ('  {0,-16} {1}' -f $K, $V) -ForegroundColor $Color }

# ---------- 前置检查 ----------
$devices = (Invoke-Adb @('devices')) | Where-Object { $_ -match '\tdevice$' }
if (-not $devices) {
    Write-Host '没有检测到已连接的设备。请开启 USB 调试并连接手机后重试。' -ForegroundColor Red
    exit 1
}

# ============================================================================
function Invoke-Diag {
    Write-Section '设备'
    Write-Key '型号'   ((Invoke-Adb @('shell','getprop','ro.product.model')) -join '').Trim()
    Write-Key '系统'   (((Invoke-Adb @('shell','getprop','ro.mi.os.version.name')) -join '').Trim() + ' / Android ' + ((Invoke-Adb @('shell','getprop','ro.build.version.release')) -join '').Trim() + ' (API ' + ((Invoke-Adb @('shell','getprop','ro.build.version.sdk')) -join '').Trim() + ')')

    Write-Section 'Root 状态'
    $suHits = @()
    foreach ($p in @('su', '/system/bin/su', '/data/adb/ksu/bin/su', 'ksud', '/data/adb/ksud')) {
        $r = (Invoke-Adb @('shell', "$p -c id 2>&1")) -join ' '
        if ($r -match 'uid=0') { $suHits += "$p 可用" }
    }
    $ksuSys   = ((Invoke-Adb @('shell','ls /sys/module 2>/dev/null')) | Select-String -Pattern 'ksu|sukisu|apatch|magisk') -join ','
    $ksudSvc  = ((Invoke-Adb @('shell','getprop init.svc.ksud')) -join '').Trim()
    $vbState  = ((Invoke-Adb @('shell','getprop ro.boot.verifiedbootstate')) -join '').Trim()

    if ($suHits.Count -gt 0) {
        Write-Key 'su' ($suHits -join ' / ') 'Green'
    } else {
        Write-Key 'su' '任何路径都拿不到 root（无 su/ksud 可执行文件）' 'Red'
    }
    Write-Key '内核模块' $(if ($ksuSys) { $ksuSys } else { '未发现 kernelsu / sukisu / apatch / magisk 模块' }) $(if ($ksuSys) { 'Green' } else { 'Red' })
    Write-Key 'ksud 服务' $(if ($ksudSvc) { $ksudSvc } else { '未注册（root 框架未运行）' }) $(if ($ksudSvc) { 'Green' } else { 'Red' })
    Write-Key 'boot 校验态' $vbState
    Write-Key '结论' $(if ($suHits.Count -gt 0) { 'Root 可用' } else { '当前无可用 Root —— LSPosed 无法工作，必须先恢复 Root' }) $(if ($suHits.Count -gt 0) { 'Green' } else { 'Red' })

    Write-Section '系统能力'
    $blur = ((Invoke-Adb @('shell','getprop','ro.surface_flinger.supports_background_blur')) -join '').Trim()
    Write-Key '窗口模糊' $(if ($blur -eq '1') { 'ro.surface_flinger.supports_background_blur=1（已开启）' } else { "未开启（值='$blur'）" }) $(if ($blur -eq '1') { 'Green' } else { 'Yellow' })

    Write-Section '相关应用'
    foreach ($p in @(@($PKG_MODULE,'本模块 APK'), @($PKG_CARD,'小米智能卡'), @($PKG_WALLET,'小米钱包'), @($PKG_MANAGER,'LSPosed 管理器'))) {
        $path = (Invoke-Adb @('shell', "pm path $($p[0])")) -join ' '
        Write-Key $p[1] $(if ($path -match 'package:') { '已安装' } else { '未安装' }) $(if ($path -match 'package:') { 'Green' } else { 'Yellow' })
    }

    Write-Section '遮挡检测'
    $holders = (Invoke-Adb @('shell','cmd appops query-op SYSTEM_ALERT_WINDOW allow')) | Where-Object { $_ -match '^com\.|^org\.|^me\.|^tv\.' }
    $attached = & $Adb @($AdbArgs + @('shell','dumpsys window windows')) 2>&1 | Select-String -Pattern 'ty=2038|ty=2003|ty=2006'
    Write-Key '持权应用数' "$($holders.Count) 个拥有「显示在其他应用上层」权限"
    Write-Key '当前遮挡' $(if ($attached) { "$($attached.Count) 个悬浮窗正挂在屏幕上" } else { '无（此刻没有任何应用在遮挡屏幕）' }) $(if ($attached) { 'Red' } else { 'Green' })

    Write-Section '启动入口'
    $launcher = ((Invoke-Adb @('shell','cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.miui.tsmclient')) -join ' ').Trim()
    Write-Key '小米智能卡' $(if ($launcher -match '/') { $launcher } else { '无 Launcher 入口（解耦应用，需从小米钱包 / NFC 唤起）' }) 'Yellow'
}

# ============================================================================
function Invoke-OverlayList {
    Write-Section '持有「显示在其他应用上层」权限的应用'
    $holders = (Invoke-Adb @('shell','cmd appops query-op SYSTEM_ALERT_WINDOW allow')) | Where-Object { $_ -match '^com\.|^org\.|^me\.|^tv\.' } | Sort-Object -Unique
    $running = (Invoke-Adb @('shell','ps -A')) | ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique

    foreach ($h in $holders) {
        $isRunning = $running | Select-String -SimpleMatch $h
        $isSuspect = $SUSPECTS -contains $h
        $tag = @()
        if ($isRunning) { $tag += '运行中' }
        if ($isSuspect) { $tag += '可疑' }
        $color = if ($isSuspect -and $isRunning) { 'Red' } elseif ($isSuspect) { 'Yellow' } else { 'Gray' }
        Write-Host ('  {0,-34} {1}' -f $h, ($tag -join ' / ')) -ForegroundColor $color
    }
    Write-Host ''
    Write-Host '  红色 = 可疑且正在运行，最可能造成「遮挡界面」提示' -ForegroundColor DarkGray
}

# ============================================================================
function Invoke-OverlayRevoke {
    $targets = if ($Packages.Count -gt 0) { $Packages } else { $SUSPECTS }
    Write-Section '收回悬浮窗权限'
    foreach ($t in $targets) {
        $installed = (Invoke-Adb @('shell', "pm path $t")) -join ' '
        if ($installed -notmatch 'package:') { Write-Host "  跳过 $t（未安装）" -ForegroundColor DarkGray; continue }
        $before = (Invoke-Adb @('shell', "cmd appops get $t SYSTEM_ALERT_WINDOW")) -join ' '
        Invoke-Adb @('shell', "cmd appops set $t SYSTEM_ALERT_WINDOW ignore") | Out-Null
        $after = (Invoke-Adb @('shell', "cmd appops get $t SYSTEM_ALERT_WINDOW")) -join ' '
        Write-Host ("  {0,-34} {1}  ->  {2}" -f $t, ($before.Trim()), ($after.Trim())) -ForegroundColor Green
    }
    Write-Host ''
    Write-Host '  恢复方法（把某个包改回默认）：' -ForegroundColor DarkGray
    Write-Host '    adb shell cmd appops set <包名> SYSTEM_ALERT_WINDOW default' -ForegroundColor DarkGray
}

# ============================================================================
function Invoke-RestartCard {
    Write-Section '重启小米智能卡'
    Invoke-Adb @('shell', "am force-stop $PKG_CARD") | Out-Null
    Write-Host '  已强制停止 com.miui.tsmclient' -ForegroundColor Green

    $launcher = ((Invoke-Adb @('shell', "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $PKG_CARD")) -join ' ').Trim()
    if ($launcher -match '/') {
        Invoke-Adb @('shell', "am start -n $launcher") | Out-Null
        Write-Host "  已重新拉起：$launcher" -ForegroundColor Green
    } else {
        Write-Host '  该应用没有 Launcher 入口（解耦应用），请手动打开：' -ForegroundColor Yellow
        Write-Host '    小米钱包 → 交通卡 / 或直接把手机靠近闸机由 NFC 唤起' -ForegroundColor Yellow
    }
}

# ============================================================================
function Invoke-OpenManager {
    Write-Section '打开 LSPosed 管理器'
    $r = (Invoke-Adb @('shell', "monkey -p $PKG_MANAGER -c android.intent.category.LAUNCHER 1")) -join ' '
    Write-Host "  $r" -ForegroundColor Green
}

# ============================================================================
switch ($Action) {
    'diag'           { Invoke-Diag }
    'overlay-list'   { Invoke-OverlayList }
    'overlay-revoke' { Invoke-OverlayRevoke }
    'restart-card'   { Invoke-RestartCard }
    'open-manager'   { Invoke-OpenManager }
}
Write-Host ''
