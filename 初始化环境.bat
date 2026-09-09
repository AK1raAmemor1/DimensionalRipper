@echo off
chcp 65001 >nul
REM 一键初始化 NeoForge 1.21.1 开发环境
REM 会自动下载 gradle-wrapper.jar 并刷新依赖

cd /d "%~dp0"

echo ========================================
echo   正在初始化 NeoForge 1.21.1 开发环境
echo ========================================
echo.

powershell -ExecutionPolicy Bypass -File "setup.ps1"

if %errorlevel% neq 0 (
    echo.
    echo 初始化过程中出现错误，请检查网络连接或手动下载 gradle-wrapper.jar。
    pause
    exit /b %errorlevel%
)

echo.
echo 初始化完成！
echo 你现在可以运行：gradlew runClient
cmd /k
