# 🐟 无广告（NoAd）

> 一只帮你自动跳过手机广告的肥鱼～

---

## ✨ 功能

| 功能 | 说明 |
|------|------|
| 🎯 **自动跳过广告** | 检测并自动点击"跳过/关闭/Skip"等按钮 |
| 🚀 **开机自启** | 手机开机后自动在后台运行 |
| 👻 **默默无闻** | 前台通知最低优先级，几乎不打扰 |
| 📊 **拦截统计** | 记录拦截次数、显示每个应用的广告拦截数 |
| 🪶 **极轻量** | APK < 3MB，内存占用极低 |

## 🛠 技术原理

通过 Android **无障碍服务（AccessibilityService）**监听窗口内容变化，当检测到包含「跳过」「关闭」「Skip」等关键词的可点击按钮时，自动执行点击操作。

- ✅ 不需要 Root 权限
- ✅ 不联网、不收集任何隐私数据
- ✅ 全部数据存储在本地 SharedPreferences

## 📱 获取 APK

### 🚀 方式一：GitHub Actions 自动构建（推荐！无需 Android Studio）

1. 把整个 `NoAd/` 文件夹推送到你的 GitHub 仓库
2. 推送后 GitHub Actions 自动开始编译（约 3 分钟）
3. 去仓库的 **Actions** 标签页 → 点最新的运行 → 底部 **Artifacts** 下载 `NoAd-APK`
4. 解压 zip → 得到 `app-debug.apk` → 传到手机安装 ✨

> 💡 也可以在 Actions 页面点 **「Run workflow」** 手动触发构建～

### 🛠 方式二：本地编译（需要 Android Studio）

用 Android Studio（Ladybug 或更新版）打开 `NoAd/` 目录：

```bash
# 在项目根目录执行
./gradlew assembleDebug
# APK 路径：app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 安装后配置

### 1. 开启无障碍服务

安装后打开应用，点击 **「前往开启无障碍服务」**：
1. 系统设置 → 无障碍 → 已安装的应用
2. 找到「无广告」→ 开启

### 4. 启动后台服务

点击 **「启动后台服务」**，应用会在后台持续运行。

> 💡 **提示**：部分手机需要在「电池优化」中将「无广告」设为「不优化」，防止被系统杀死。

## 📂 项目结构

```
NoAd/
├── app/
│   ├── build.gradle.kts          # 应用构建配置
│   ├── proguard-rules.pro         # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml    # 权限、服务声明
│       ├── java/com/fatfish/noad/
│       │   ├── NoAdApp.kt         # 应用入口（通知渠道）
│       │   ├── AdSkipService.kt   # 🔑 无障碍服务（核心）
│       │   ├── KeepAliveService.kt # 前台保活服务
│       │   ├── BootReceiver.kt    # 开机自启接收器
│       │   ├── AdStatsManager.kt  # 拦截统计管理
│       │   └── MainActivity.kt    # 主界面
│       └── res/
│           ├── layout/            # 界面布局
│           ├── xml/               # 无障碍服务配置
│           ├── values/            # 字符串、颜色、主题
│           └── drawable/          # 图标资源
├── build.gradle.kts               # 项目构建配置
├── settings.gradle.kts
└── gradle.properties
```

## ⚠️ 注意事项

1. **无障碍服务权限**：Android 系统要求用户手动在设置中开启，应用无法自动开启
2. **手机厂商限制**：部分国产 ROM（MIUI、ColorOS 等）可能限制无障碍服务自动启动，需要在「自启动管理」中允许
3. **兼容性**：理论上支持 Android 7.0+ (API 24+)
4. **不保证 100%**：部分应用使用自定义实现的广告跳过按钮，可能无法识别

## 🔧 开发环境

| 工具 | 版本 |
|------|------|
| Android Studio | Ladybug / Hedgehog+ |
| Kotlin | 1.9.22 |
| AGP | 8.2.2 |
| Gradle | 8.5 |
| compileSdk | 34 |
| minSdk | 24 |
| targetSdk | 34 |

## 🐟 关于肥肥

肥肥是主人从菜市场救回来的大肥鱼～现在赖在主人的手机里帮忙跳过广告！每跳过一个广告，肥肥就摆一下尾巴～咕噜咕噜～