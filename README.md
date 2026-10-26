# QwenPaw Node

QwenPaw 的第三方 Android 客户端。Kotlin + Jetpack Compose，直连本机/局域网内的
QwenPaw 服务端（默认 `:8088`），支持会话管理、流式回复、审批、附件与离线语音输入。

## 功能

- **会话**：列表预览、创建/重命名/删除、置顶、归档（独立「已归档」页可恢复）、分组
- **对话**：SSE 流式输出、Markdown 渲染、代码块复制、思考过程折叠、工具调用卡片
- **附件**：图片收发与全屏查看、文件上传
- **审批**：工具调用审批（允许/拒绝），应用内 + 通知栏提醒
- **语音输入**：sherpa-onnx 离线流式识别，音频不出手机；模型按需下载
- **用量**：上下文占用明细、token 用量统计页
- **外观**：深浅色、字号缩放、紧凑列表、MiSans 字体（可选下载）
- **多实例**：同时保存多个服务端地址，随时切换

## 构建

```bash
./gradlew :app:assembleDebug        # 产出 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # JVM 单测
./gradlew :app:installDebug         # 装到已连接设备
```

- JDK 21 / AGP 8.7.3 / Kotlin 2.1.0 / compileSdk 35 / minSdk 26
- 仅打包 `arm64-v8a`（sherpa-onnx 的 `.so` 每个 ABI 20–30MB）。需要跑 x86 模拟器时，
  在 `app/build.gradle.kts` 的 `ndk.abiFilters` 里临时放开对应 ABI，**不要提交**。
- release 签名密钥放在仓库外（`~/.qwenpaw-node-keys/`），缺失时 release 退化为未签名。

## 体积

| 组成 | 体积 |
|---|---|
| APK（debug，仅 arm64-v8a） | 约 40MB |
| └ sherpa-onnx `.so` | 22MB |
| └ dex | 44MB（release 经 R8 后大幅缩小） |

字体与语音模型都不随 APK 分发，均由用户按需下载。

## 许可与第三方资源

- 本仓库代码：见 [LICENSE](LICENSE)（如无则默认保留所有权利）。
- **MiSans 字体**：版权归小米科技有限责任公司所有，遵循 MiSans 字体知识产权许可协议
  （免费商用，**禁止再分发字体本体**）。因此本仓库不包含字体文件；应用在用户主动点击下载时
  从 GitHub 项目 `adivenxnataly/MiSans` 获取，并在设置页与「关于」标注出处。
- **sherpa-onnx**：Apache-2.0，`app/libs/sherpa-onnx-1.13.3.aar` 为官方发布产物。
- **语音识别模型**：来自 sherpa-onnx 官方 release，用户按需下载。