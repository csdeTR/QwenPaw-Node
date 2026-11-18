# 第三方组件许可

本文件登记直接内置或新增的第三方代码。其余通过 Gradle 引入的依赖（AndroidX、Compose、OkHttp、
kotlinx.serialization、Vico 等）按其自身许可证分发，不在此逐条重复。

## ConnectBot termlib

- 组件：`org.connectbot:termlib:0.3.15`
- 上游：<https://github.com/connectbot/termlib>
- 许可证：Apache License 2.0
- 用途：Compose 终端显示、文本选择、滚动与缩放；内置 libvterm（MIT）的 JNI 渲染。
- 传递依赖：appcompat、Material Components、slf4j-api；按各自许可证分发。
- 本项目未修改 termlib 源码；ABI 由 AAR 提供四个架构的 `libjni_cb_term.so`。

libvterm 许可证：MIT，见 <https://github.com/neovim/libvterm>。

Apache License 2.0 全文：<https://www.apache.org/licenses/LICENSE-2.0>

## JSch（mwiede fork）

- 组件：`com.github.mwiede:jsch:2.28.0`
- 上游：<https://github.com/mwiede/jsch>
- 许可证：BSD 风格（JSch 原许可证，见上游 `LICENSE.txt`）
- 用途：在客户端实现 SSHv2，经配套插件的 WebSocket 中继连接目标主机。

## 本项目

QwenPaw Node 自身的代码按 MIT 许可证发布，见仓库根目录 `LICENSE`。
