# --- Gson 反射依赖 ---
#
# 这一组一旦漏掉，release 包不会崩溃，而是**静默失效** ——
# Gson 按字段名反射，混淆后字段变成 a/b/c，反序列化得到一堆 null，
# 表现为「规则加载 0 条」「订阅加不进去」「快照全是空字段」，比崩溃更难查。
#
# 这里必须用 `-keep class ... { *; }` 而不是 `-keepclassmembers ... { <fields>; }`。
# 原因：`-keepclassmembers` 只保住字段名，R8 仍会认为「这个类没有直接实例化点，
# 只有 Gson 在反射里 new」，于是把它**抽象化**掉（移除构造函数）。
# Gson 拿到一个抽象类就直接抛
# "Abstract classes can't be instantiated! Adjust the R8 configuration"。
#
# 这个坑真实发生过：release 包加载原生格式订阅时整份解析失败，
# 而 debug 包完全正常 —— 只测 debug 会完全错过。
#
# 覆盖范围：
#   rules.RuleCodec$*     规则 JSON 的传输层 DTO（订阅、手写规则）
#   rules.SubscriptionEntry 订阅配置（存 SharedPreferences 的 JSON）
#   core.model.**         规则模型本身
#   core.engine.HitRecord 命中日志（JSONL）
#   a11y.SnapshotDoc/Node 界面快照（JSON 文件）
-keep class com.splashskip.rules.RuleCodec$* {
    *;
}
-keep class com.splashskip.rules.SubscriptionEntry {
    *;
}
-keep class com.splashskip.core.model.** {
    *;
}
-keep class com.splashskip.core.engine.HitRecord {
    *;
}
-keep class com.splashskip.a11y.SnapshotDoc {
    *;
}
-keep class com.splashskip.a11y.SnapshotNode {
    *;
}

# GKD 的 AST 是 sealed interface 的多态结构，Gson 碰不到，
# 但字段名仍需保留以便日志与排查时能看出结构。
-keep class com.splashskip.core.gkd.** {
    *;
}

# --- 系统通过 manifest 反射实例化，必须保留 ---
-keep class com.splashskip.a11y.SplashSkipService { *; }
-keep class com.splashskip.a11y.ConfirmReceiver { *; }

# --- 规则引擎的接口实现走运行时多态，保留接口名 ---
-keep interface com.splashskip.core.model.NodeHandle { *; }
-keep interface com.splashskip.core.engine.EngineHost { *; }

# WorkManager 的 Worker 由框架按类名实例化
-keep class com.splashskip.rules.RuleUpdateWorker { *; }

# --- Shizuku ---
# ShizukuBridge 用反射调 Shizuku.newProcess（该方法在 13.x 是 private），
# 方法名一旦混淆，release 包里反射必挂，Shizuku 通道会静默失效。
-keep class rikka.shizuku.Shizuku {
    *;
}
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep class rikka.shizuku.ShizukuBinderWrapper { *; }
