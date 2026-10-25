# Agent 约定

本文件记录在本仓库里工作时必须遵守的约定，尤其是**系统资源**方面的。

## 一、系统资源：这是硬约束

本机内存 15 GB、swap 8 GB，同时跑着 GNOME 桌面、Trae、QwenPaw 服务端和模拟器壁纸渲染。
之前发生过一次 **GNOME 整个卡死**，原因是同时跑了「Gradle daemon + Kotlin daemon + 模拟器」，swap 被打满。

必须遵守：

1. **不要连跑构建与设备测试。** 全量构建、`connectedAndroidTest`、`assembleRelease`（含 R8）都是重活，
   逐个执行，跑完等一会儿再做下一件。
2. **动手前先看内存**：`free -h`。可用内存低于 3 GB 时，先关进程再构建。
3. **用完就关**，别留着常驻：
   - Gradle daemon：`./gradlew --stop`
   - Kotlin daemon：`pkill -f KotlinCompileDaemon`
   - 模拟器：`adb emu kill`
4. **模拟器内存固定 2048 MB，不要加。** 启动参数保持 `-memory 2048 -no-window -gpu swiftshader_indirect`。
5. **优先增量构建**：只编译 + 装包用 `./gradlew installDebug`；只跑单测用
   `./gradlew testDebugUnitTest`。不要顺手敲 `./gradlew build`（会连带 lint、release、所有测试）。
6. **单测优先于设备测试**。设备端测试启动成本高、失败信息还差，能覆盖的逻辑一律写成 JVM 单测。
7. **不要起多个 Gradle 实例**。命令排队跑，不要并行开好几个终端同时 `./gradlew`。
8. 需要长时间跑的设备验收（`scripts/e2e_acceptance.sh`）一次只跑一个场景（`--only A4`），
   跑完检查 `free -h`，别整轮跑完再一起收。

## 二、QwenPaw 服务端

- 本机实例跑在 `0.0.0.0:8088`，`auth.status.enabled=false`，可直接用。
- 同机再起第二实例时用独立 HOME（如 `HOME=/tmp/qp2 qwenpaw app --port 8099`），
  **用完必须 kill 并删掉临时 HOME**，别留后台进程。
- 改 `~/.qwenpaw/workspaces/default/agent.json` 前先备份（如 `/tmp/agent.json.bak`），
  **测完立刻还原**（`approval_level` 默认是 `AUTO`，测审批才临时改 `ASK`）。
- 测试会留下大量 running/idle 会话，结束时确认没有残留：
  `curl -s localhost:8088/api/chats | python3 -c "..."`，必要时用
  `POST /api/console/chat/stop?chat_id=` 收尾。

## 三、测试设备

### 真机（首选）

- `192.168.10.201:5555`（LM_G820N，Android 12，arm64，逻辑分辨率 1080x1920）。
  用 `adb connect 192.168.10.201:5555`，宿主地址 `192.168.10.204:8088`。
- **没有 root**，`iptables` 断网场景跑不了，脚本会自动跳过 A6。
- 这台机器装了 **Termux 保活 Magisk 模块，会定期把 Termux 拉到前台**。
  看到界面跳到 Termux 不是应用崩溃，重新 `am start` 即可，别当成 bug 去查。
- 中文输入法会把 `.` `:` 打成全角（`。` `：`），`adb shell input text` 输入地址会变形。
  应用侧已在 `normalizeBaseUrl` 做全角→半角折叠，脚本不必绕过。
- 输入框获得焦点后键盘会挡住按钮，点击前先 `input keyevent 4` 收键盘。
- 屏幕会自动息屏：`input keyevent KEYCODE_WAKEUP` 再上滑解锁（当前已关闭锁屏密码）。

### 模拟器（备用）

- AVD 名 `qp35`（Android 15，1080x2400），启动参数 `-memory 2048 -no-window -gpu swiftshader_indirect`。
- 可以 `adb root`，断网场景用 `iptables`（`svc wifi disable` 和飞行模式在模拟器里不管用）。
  **用完必须删掉 iptables 规则**，否则后续调试全部连不上。
- 内存紧张时模拟器和 Gradle 一起跑极易拖垮桌面，非必要不用。

### 通用

- 操作界面时坐标从 `uiautomator dump` 动态拿，不要凭截图目测（截图是缩放的，会点空）。
- `adb shell input text` 不支持中文，空格用 `%s`。
- 验收脚本：`ADB_SERIAL=<serial> ./scripts/e2e_acceptance.sh --only <A1..A6>`。

## 四、构建与签名

- Java 21 / AGP 8.7.3 / Kotlin 2.1.0 / compileSdk 35。
- release 签名密钥在仓库外：`~/.qwenpaw-node-keys/`（`release.jks` + `keystore.properties`），
  **不要复制进仓库**，`.gitignore` 已排除 `*.jks` / `*.keystore` / `keystore.properties`。
- release 开了 R8 + 资源压缩，改依赖后要重新验一次 `assembleRelease`。
- 协议细节与已知坑见 `开发 QwenPaw Android 客户端.md` 第五节。
