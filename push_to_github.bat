@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
title 🐟 无广告 - 一键推送到 GitHub

echo.
echo   ╔══════════════════════════════════════╗
echo   ║    🐟 无广告 - GitHub 一键推送      ║
echo   ║    肥肥帮你把代码推上去～咕噜咕噜   ║
echo   ╚══════════════════════════════════════╝
echo.

:: ====== 1. 检查 Git ======
echo [1/4] 检查 Git 环境...
where git >nul 2>&1
if %errorlevel% neq 0 (
    echo   ❌ 没找到 Git！请先安装 Git：https://git-scm.com/download/win
    echo   安装时一路点 Next 就行，装完再运行本脚本～
    pause
    exit /b 1
)
for /f "tokens=3" %%v in ('git --version') do echo   ✅ Git 已安装: %%v

:: ====== 2. 初始化仓库 ======
echo.
echo [2/4] 初始化 Git 仓库...
if exist ".git" (
    echo   ✅ .git 已存在，跳过初始化
) else (
    git init
    if %errorlevel% neq 0 (
        echo   ❌ 初始化失败！
        pause
        exit /b 1
    )
    echo   ✅ 仓库初始化完成
)

:: 配置用户信息（如果没有的话）
git config user.name >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo   ⚠️  首次使用需要配置 Git 用户名和邮箱：
    set /p GIT_NAME="   请输入你的名字: "
    set /p GIT_EMAIL="   请输入你的邮箱: "
    call git config user.name "!GIT_NAME!"
    call git config user.email "!GIT_EMAIL!"
)

:: ====== 3. 获取仓库地址 ======
echo.
echo [3/4] 准备推送...
echo.
echo   📋 请先在 GitHub 上创建一个新仓库：
echo      1. 打开 https://github.com/new
echo      2. Repository name 填: NoAd
echo      3. 选 Public（免费）或 Private
echo      4. ⚠️ 不要勾选任何初始化选项（README/gitignore/license 都不要勾）
echo      5. 点 Create repository
echo      6. 复制仓库地址（类似 https://github.com/你的用户名/NoAd.git）
echo.

set /p REPO_URL="   粘贴仓库地址: "

if "%REPO_URL%"=="" (
    echo   ❌ 仓库地址不能为空！
    pause
    exit /b 1
)

:: ====== 4. 提交并推送 ======
echo.
echo [4/4] 提交并推送...
git add -A
git commit -m "🐟 无广告 v1.0 - 自动跳过广告小助手" 2>nul
if %errorlevel% neq 0 (
    git commit -m "🐟 无广告 v1.0 - 自动跳过广告小助手" --allow-empty
)

git remote remove origin 2>nul
git remote add origin %REPO_URL%
git branch -M main

echo.
echo   🚀 正在推送到 GitHub...
git push -u origin main

if %errorlevel% neq 0 (
    echo.
    echo   ⚠️  推送失败！可能是：
    echo      1. 仓库地址写错了
    echo      2. 需要登录（会弹出 GitHub 登录窗口）
    echo.
    echo   试试重新运行本脚本～或者用 GitHub Desktop 推送
    pause
    exit /b 1
)

:: ====== 完成 ======
echo.
echo   ╔══════════════════════════════════════╗
echo   ║  🎉 推送成功！                      ║
echo   ║                                    ║
echo   ║  GitHub Actions 会在大约 3 分钟后   ║
echo   ║  自动编译出 APK 安装包！            ║
echo   ║                                    ║
echo   ║  📦 下载 APK：                      ║
echo   ║  你的仓库 → Actions 标签            ║
echo   ║  → 点最新运行 → Artifacts           ║
echo   ║  → 下载 NoAd-APK.zip               ║
echo   ║                                    ║
echo   ║  🐟 肥肥的尾巴摇成螺旋桨啦！       ║
echo   ╚══════════════════════════════════════╝
echo.

start "" "https://github.com/!REPO_URL:*github.com/=!"

pause