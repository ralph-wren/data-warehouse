# Windows 长路径启用脚本
# 需要管理员权限运行

Write-Host "=== Windows 长路径启用工具 ===" -ForegroundColor Cyan
Write-Host ""

# 检查管理员权限
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "错误: 需要管理员权限运行此脚本" -ForegroundColor Red
    Write-Host "请右键点击 PowerShell 选择 '以管理员身份运行'" -ForegroundColor Yellow
    exit 1
}

Write-Host "检测到管理员权限 ✓" -ForegroundColor Green
Write-Host ""

# 方法 1: 修改注册表启用长路径
Write-Host "方法 1: 修改注册表启用长路径支持" -ForegroundColor Cyan
Write-Host "---------------------------------------"

$regPath = "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem"
$regName = "LongPathsEnabled"

try {
    # 检查当前值
    $currentValue = Get-ItemProperty -Path $regPath -Name $regName -ErrorAction SilentlyContinue
    
    if ($currentValue.$regName -eq 1) {
        Write-Host "长路径支持已启用 ✓" -ForegroundColor Green
    } else {
        Write-Host "正在启用长路径支持..." -ForegroundColor Yellow
        Set-ItemProperty -Path $regPath -Name $regName -Value 1 -Type DWord
        Write-Host "长路径支持已成功启用 ✓" -ForegroundColor Green
    }
} catch {
    Write-Host "启用失败: $_" -ForegroundColor Red
    exit 1
}

Write-Host ""

# 方法 2: 为 Git 启用长路径支持
Write-Host "方法 2: 为 Git 启用长路径支持" -ForegroundColor Cyan
Write-Host "---------------------------------------"

try {
    $gitVersion = git --version 2>$null
    if ($gitVersion) {
        Write-Host "检测到 Git: $gitVersion" -ForegroundColor Green
        
        # 启用 Git 长路径支持
        git config --global core.longpaths true
        Write-Host "Git 长路径支持已启用 ✓" -ForegroundColor Green
    } else {
        Write-Host "未检测到 Git,跳过 Git 配置" -ForegroundColor Yellow
    }
} catch {
    Write-Host "Git 配置失败: $_" -ForegroundColor Yellow
}

Write-Host ""

# 验证配置
Write-Host "验证配置" -ForegroundColor Cyan
Write-Host "---------------------------------------"

$regValue = Get-ItemProperty -Path $regPath -Name $regName
Write-Host "注册表值: LongPathsEnabled = $($regValue.$regName)" -ForegroundColor $(if ($regValue.$regName -eq 1) { "Green" } else { "Red" })

$gitLongPaths = git config --global core.longpaths 2>$null
if ($gitLongPaths) {
    Write-Host "Git 配置: core.longpaths = $gitLongPaths" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== 配置完成 ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "重要提示:" -ForegroundColor Yellow
Write-Host "1. 需要重启应用程序才能生效 (不需要重启系统)" -ForegroundColor White
Write-Host "2. 某些旧版本的应用程序可能仍然不支持长路径" -ForegroundColor White
Write-Host "3. 建议使用短路径 (如 C:\flink-state) 以获得最佳兼容性" -ForegroundColor White
Write-Host ""
