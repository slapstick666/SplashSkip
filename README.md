# SplashSkip —— 安卓开屏广告跳过

[![Build](https://github.com/slapstick666/SplashSkip/actions/workflows/build.yml/badge.svg)](https://github.com/slapstick666/SplashSkip/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

参考 [GKD](https://github.com/gkd-kit/gkd) 的规则化思路，实现一个基于无障碍服务的开屏广告跳过工具。

> **完整设计文档见 [`docs/设计说明.md`](docs/设计说明.md)** —— 版本适配、权限、识别方式、触发逻辑、
> 规则机制、保活要点、与 GKD/李跳跳 的差异、模块划分与实现步骤，都在那里。

---

## 一句话说明

用 `AccessibilityService` 读取界面节点树，按 JSON 规则匹配「跳过」按钮并点击。
所有规则都来自你自己添加的订阅源，App 不内置任何规则。

## 模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| `:core` | 规则模型、GKD 选择器解析与匹配、调度引擎。**零 `android.*` 依赖**，可纯 JVM 单测 | — |
| `:rules` | 规则解析、GKD 订阅拉取与合并、分类开关 | `:core` |
| `:a11y` | 无障碍服务、节点树遍历、动作降级、事件节流 | `:core` `:rules` |
| `:app` | UI、权限引导、装配 | 全部 |

## 构建

```bash
# 需要 JDK 17 + Android SDK (compileSdk 35)

# 调试包（可共存，applicationId 带 .debug 后缀）
./gradlew :app:assembleDebug

# 正式包（R8 混淆 + 资源压缩 + release 签名）
./gradlew :app:assembleRelease

# 只跑核心逻辑单测（不需要模拟器）
./gradlew :core:test
```

> 首次在 Android Studio 打开时，IDE 会自动补 Gradle Wrapper 并同步依赖。

## 正式版

| 项 | 值 |
|---|---|
| 版本 | **1.3.2**（versionCode 7） |
| 安装包 | `SplashSkip-1.3.2.apk`，2.0 MB |
| 签名 | RSA 2048，SHA-256 `a1442206...` |
| SHA-256 | `a90f47caf9c0ac98b1622a330653f83732e4abd9235d48d658990b3804dc6829` |

release 签名配置在根目录 `keystore.properties`（含密码，**已在 .gitignore 排除，务必自行备份**，
丢了就无法给已安装用户推送更新）。文件不存在时构建不会报错，只是产物不签名。

`proguard-rules.pro` 里对所有走 Gson 反射的数据类都用了 `-keep class ... { *; }` ——
这一项漏了不会崩，而是让规则**整份解析失败**（R8 会把只有反射实例化点的 DTO 抽象化，
Gson 直接抛 "Abstract classes can't be instantiated"）。详见设计文档 7.5。

## 订阅：只支持 GKD 格式

规则来源就是 **GKD 社区订阅**。「＋ 添加订阅」填一个 GKD 订阅 URL 即可，例如：

```
https://gcore.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5
```

三个关键行为：

- **全量解析，一个不漏**：订阅里有什么就解析什么。以 Lin-arm 订阅 v620 为例，
  992 个应用 / 2477 个规则组 / 3880 条规则全部进来（成功 3864 条），
  数字和原版 GKD 里看到的一致。
- **按分类执行，默认只开「开屏广告」**：订阅里的规则按 GKD 的 categories 分组，
  订阅页有分类开关。默认值取订阅作者标的 `enable: true`（Lin-arm 订阅只有开屏），
  其余十个分类（局部广告、功能类、更新提示…）要你自己勾。
  —— 全开会在你正常刷信息流、点设置的时候自动动手，实测会误点。
- **没有签名校验**：GKD 社区本来就没有签名体系，强加验签等于拒收所有现成源。
  1.2.0 前自带的 ECDSA 验签随自研规则格式一起删了。

一个 App 的同一界面可能同时挂开屏、全屏广告、更新提示等多个分组，引擎会按优先级
逐条尝试，第一条命中即点，不是「一个 App 只认一条规则」。开屏分类的规则优先级更高
（`priority=100`），开屏页上先试「跳过」，不会先撞上其它分类的「关闭广告」。

1.2.0 起删掉了自研的扁平 JSON 规则格式和 `user.json` 手写规则 ——
GKD 生态现成有几千条社区维护的规则，自造格式的维护成本远大于收益。

GKD 选择器是**树形关系表达式**，几个常用写法：

```
@ImageView[text="跳过"] < [text="广告"]      左边是右边的子节点
@FrameLayout > @[text="关闭"]                 右边是左边的子节点
@[text="跳过"] + [vid="cover"]                兄弟节点
@[text*="跳过"][text.length<10][visibleToUser=true]
```

`@` 标记的是要点击的目标节点；没有 `@` 时目标是链尾。
方向记忆：`>` 右边是左边的子（同 CSS），`<` 反过来。完整语法见设计文档 6.4。


## 最低要求

- Android 8.0（API 26）及以上，推荐 Android 10+
- 无需 Root
- Shizuku **可选**：装了并授权后点击延迟可降到接近 0，不装也能完整使用无障碍通道

## 两条点击通道

| 通道 | 前提 | 延迟 | 说明 |
|---|---|---|---|
| 无障碍服务 | 系统设置里手动开启 | 100~200ms | 兼容性最好，默认可用 |
| Shizuku | 额外装 [Shizuku](https://github.com/RikkaApps/Shizuku) 并用 adb/root 启动 | 接近 0 | 走 shell 注入，不依赖无障碍常驻 |

设置页可在「自动 / 仅无障碍 / 仅 Shizuku」三档间切换，默认 AUTO（Shizuku 就绪时优先）。

## 上手

1. 安装后打开 App
2. 点「去开启无障碍服务」，在系统设置里找到「开屏跳过」并开启
   - Android 12+ 若提示「受限制的设置」，先到「应用信息 → 允许受限制的设置」放开
3. 打开任意带开屏广告的 App 验证
4. 没跳过？看「命中日志」—— 是被护栏拦了、超时了，还是压根没匹配上，日志里都有

## 想自己写规则？

直接按 GKD 的选择器语法写订阅（JSON5 的 `apps[].groups[].rules[]` 结构），
语法与写法见 [GKD 官方文档](https://gkd.li/guide/selector)。
本项目实测支持其中约 99.7% 的选择器写法：4115 条真实选择器里只有 13 条解析不了
（几乎都是 `->` 算符 —— 它要求记住查询路径，当前的从右往左回溯模型表达不了）。
这些规则会被跳过并在订阅页列告警，不影响其余规则。

写规则的调试手法不变：**先抓快照看节点树，再写选择器**。
主界面点「抓取界面快照」，能看到目标页面的完整节点树——文本、viewId、可点击、坐标都有。

## 合规声明

本项目仅供**个人学习与自用**。

2023 年 8 月，李跳跳、大圣净化、一指禅、叮小跳等多款同类工具收到律师函，援引
《反不正当竞争法》第二条、第十二条第二款第四项，主张构成不正当竞争，多数已下架停运。
法律界对此尚无统一结论。

因此本项目明确边界：**不修改任何第三方应用、不拦截网络请求、不注入代码、不破解付费内容**，
只做「代替用户点击屏幕上已有的按钮」这一件事，且完全离线运行。请勿用于商业用途或分发牟利。

## 已知限制

- **不内置任何规则**，首次使用必须自己添加 GKD 订阅源，否则规则数为 0
- **只支持 GKD 格式订阅**（1.2.0 起删掉了自研扁平格式），非 GKD 的 URL 会被拒收并提示
- **非开屏分类默认关闭**：订阅里的更新提示、评价提示、局部广告、功能类等十个分类
  需要在订阅页手动勾选才会生效 —— 它们会在非开屏页面自动点击，是误点的主要来源
- 已在 Android 15 模拟器验证：安装、启动、4 tab 切换、安全区域、无障碍连接、
  规则加载、快照抓取与查看、点击通道切换
- **GKD 订阅已在模拟器上跑通端到端**：加载真实 GKD 订阅（3864 条规则 / 986 个应用），
  并用一条 GKD 规则命中系统设置的「Battery」按钮、真实点击跳转到了电池页面
- **但真实 App 的开屏广告仍未实测**——模拟器里没有带开屏广告的第三方 App，
  GKD 规则在真实开屏页上的命中率未知
- GKD 选择器有约 0.3% 解析不了（主要是 `->` 算符），
  这些规则会被跳过并在订阅页列出告警，不影响其余规则
- GKD 规则**不套额外的启发式护栏**——GKD 规则本身精确到具体节点，
  再套「右上角 + 短文本」那套只会误杀真规则
- **Shizuku 通道未实测**——需要一台装了 Shizuku 并完成授权的设备
- 未做图像识别兜底（架构已留位置，见设计文档 2.5）

> 模拟器上 `adb screencap` 截图全黑是 gfxstream GPU 后端的缺陷，不是 App 的问题。
> 要截图需以 `-gpu swiftshader_indirect` 启动模拟器。

## 环境要求（国内网络）

`dl.google.com` 大概率直连超时，以下三处已配好，换机器时要记得：

| 位置 | 配置 |
|---|---|
| 环境变量 | `SDK_TEST_BASE_URL=https://googledownloads.cn/android/repository/` |
| `settings.gradle.kts` | 阿里云 Maven 镜像置于 `google()` 之前 |
| `gradle/wrapper/gradle-wrapper.properties` | distributionUrl 指向腾讯云镜像 |
