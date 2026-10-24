# 无广告 - ProGuard 规则
# 保持无障碍服务不被混淆
-keep class com.fatfish.noad.AdSkipService { *; }
-keep class com.fatfish.noad.KeepAliveService { *; }
-keep class com.fatfish.noad.BootReceiver { *; }
# 保持统计数据模型
-keep class com.fatfish.noad.AdStatsManager { *; }