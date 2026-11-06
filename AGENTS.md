# Agent 约定

本文记录在本仓库工作时必须遵守的约定。第 1 节关于系统资源的条目属于**硬约束**，
不遵守会直接影响同机的桌面环境；其余各节属于工程约定，用于避免重复踩坑。

## 0、协作方式

这一节约束工作方式而非工程细节，因此放在最前。

1. **用户给出的路线、方案、实现不等于最优解。** 发现更合适的做法时，
   应先说明它好在哪、代价是什么、与用户所给方案的差异，再由用户选择，
   而不是默认照办。用户的方案往往基于本文档看不到的背景，因此提出异议
   必须带依据（实测数据、代码位置、服务端字段名），不能只表达偏好。
2. **需要确认的地方先提问。** 需求含糊、涉及破坏性操作、要在多种合理实现之间取舍、
   或改动会越出本轮范围时，先问再做。判断标准是「做完才发现方向不对」的返工成本
   是否高于一次提问的成本；是则先问。
3. 提问要收敛：一次给出二到四个可选项，每项写明后果，推荐项放第一位。

## 1、系统资源：硬约束

本机内存 15 GB、swap 8 GB，同时运行着 GNOME 桌面、Trae、QwenPaw 服务端与模拟器壁纸渲染。
曾经发生一次 **GNOME 整个卡死**，原因是同时运行了「Gradle daemon + Kotlin daemon + 模拟器」，
swap 被完全占满，桌面因 swap thrash 而失去响应。

以下八条必须遵守。

1. **不要连续执行构建与设备测试。**
   全量构建、`connectedAndroidTest`、`assembleRelease`（含 R8）都属于重活。
   应逐个执行，并在两次之间留出间隔，避免内存占用叠加。

2. **动手前先看内存。** 执行 `free -h`，「可用内存」与「swap 已用」两项都要看。
   可用内存低于 3 GB，或 swap 已用超过总量的一半（4 GB）时，
   应先关闭测试设备、测试容器与多余进程，再开始构建。
   只看 available 不够：swap 被占到一半以上说明系统已经在换页，
   此时再叠加构建任务，就是那次卡死的直接前兆。

3. **用完即关，不要保留常驻进程。** 测试设备与测试服务端都按需启动，用完立即关闭，
   不因为「下次还要用」而挂在后台。
   - Gradle daemon：`./gradlew --stop`
   - Kotlin daemon：`pkill -f KotlinCompileDaemon`
   - redroid 容器：`docker compose -f scripts/redroid/docker-compose.yml down`
   - LXC 测试容器：`lxc stop qp-test qp-test-prerelease`
     （只停容器，LXD 服务与 `lxdbr0` 保留，下次直接 `lxc start`）
   - AVD：`~/Android/Sdk/platform-tools/adb -s emulator-5554 emu kill`

   忘记收尾会导致下一次构建时内存基线偏高，进而挤占桌面环境。

4. **redroid 容器的资源上限固定在 compose 里，不要调高。**
   `mem_limit` / `memswap_limit` 3 GB、`cpus` 2、`pids_limit` 4096，见 3.1。
   实测空闲占用 1.0–1.3 GiB；不设上限时容器曾把 swap 吃满，宿主机随即卡死。

5. **优先增量构建。**
   只编译并装包时用 `./gradlew installDebug`；只跑单测时用 `./gradlew testDebugUnitTest`。
   不要顺手敲 `./gradlew build`，该目标会连带执行 lint、release 构建与全部测试。

6. **单测优先于设备测试。**
   设备端测试启动成本高，失败信息也差。能覆盖的逻辑一律写成 JVM 单测。

7. **不要同时启动多个 Gradle 实例。**
   命令应排队执行，不要并行开多个终端同时运行 `./gradlew`。

8. **设备验收一次只跑一个场景。**
   `scripts/e2e_acceptance.sh` 使用 `--only A4` 形式的单场景参数，跑完检查 `free -h`。
   不要整轮跑完再统一收尾，因为测试容器与构建同时运行极易拖垮桌面环境。

## 2、QwenPaw 服务端

### 2.1 测试一律使用 LXC 容器

**测试不再使用本机的 `:8088` 实例。** 本机实例承载用户的真实会话与记忆，
在上面做写操作会污染真实数据，历史上有过误归档真实会话的教训。

| 容器 | IP | 版本 | 用途 |
| --- | --- | --- | --- |
| `qp-test` | `10.10.0.211:8088` | `2.2.2.post1` | 稳定版基线，与用户当前使用的版本一致 |
| `qp-test-prerelease` | `10.10.0.220:8088` | `2.3.0b2` | 预发布版，用于提前发现破坏性变更 |

- 连接方式：`lxc exec <name> -- <cmd>` 或 `ssh csdetr@<ip>`。两个账户（`csdetr` 与 `root`）
  使用同一个口令，按本机既有配置使用；`csdetr` 已配置 sudo 免密，日常操作不需要口令。
- 两个容器仅用于测试，可随意安装软件包与修改配置，不需要事前备份。
- **启动服务用 `screen -dmS`，不要用 `nohup` 或 `setsid`。** 写法：

  ```bash
  lxc exec qp-test -- sudo -u csdetr bash -lc \
    'screen -dmS qwenpaw bash -lc "~/.qwenpaw/venv/bin/qwenpaw app --host 0.0.0.0 --port 8088 > /tmp/qp.log 2>&1"'
  ```

  查看：`screen -ls`；进会话看输出：`screen -r qwenpaw`（脱离用 `Ctrl-A D`）；
  停止：`screen -S qwenpaw -X quit`。
- 容器内 `qwenpaw` 在 `~/.qwenpaw/venv/bin/qwenpaw`，同时提供了 `~/.qwenpaw/bin/qwenpaw`
  包装脚本；非交互 shell 下一律写全路径。
- 两个容器的 `auth.status` 都是关闭的（`{"enabled":false,...}`），可直接访问，无需 token。
- 停止服务后要确认端口已释放：`ss -ltnp | grep 8088` 应无输出。
  直接 `pkill -f "qwenpaw app"` 在容器里会因权限失败，需加 `-u csdetr`，
  或从 `screen` 会话退出。
- **`lxdbr0` 停在 `UNAVAILABLE` 时先查 `nft_masq` 模块。** LXD 5.21 建托管桥时写入
  `ip saddr 10.10.0.0/24 ... oifname != "lxdbr0" masquerade`，该表达式由 `nft_masq.ko` 提供；
  模块缺失时 nf_tables 返回 ENOENT，journal 表现为
  `Failed adding outbound NAT rules … Could not process rule: No such file or directory`，
  `lxc network list` 中桥停在 `UNAVAILABLE`，所有容器无法启动。本机已由
  `/etc/modules-load.d/lxd-nft-masq.conf` 固化该模块；仍未生效时执行
  `sudo modprobe nft_masq` 后 `sudo snap restart lxd`。
  docker 走的是 iptables-nft 的 xt `MASQUERADE`，因此该模块不会被 docker 带起来。

### 2.2 两个版本的端点差异（实测，2026-11-01）

用 `QWENPAW_OPENAPI_DOCS=1` 在临时 HOME 下导出 OpenAPI 对比得出：

- 2.2.2.post1：**517** 条路径；2.3.0b2：**545** 条路径。
- 2.3.0b2 新增 33 条，主要是插件管理（`/api/plugins/{id}/config|enabled|unload|repair-dependencies`、
  `dependency-upgrades`）、技能池下载任务、ChatGPT 提供商 OAuth、`/api/runtime/*`、
  `/api/advisor-mode`、`/api/chats/resolve`、`/api/chats/{id}/decision-settings`。
- **2.3.0b2 移除了 5 条，其中三条影响客户端功能规划**：
  - `GET|POST /api/workspace/download`、`/api/workspace/upload`（整包）**已删除**，
    只剩 `file-download` / `file-upload`（单文件）与 `project-directory/upload-zip`。
  - `POST /api/desktop/shutdown` 已删除。

**结论：客户端不得依赖整包下载/上传端点。** 需要整包能力时，
应改为遍历目录逐个下载，或使用 `project-directory/upload-zip`。

- 客户端实际使用的 33 条路径在两版**完全一致**，移除的 5 条里没有一条被调用。
  因此**不做运行时版本分支**，理由与判定规则见 `docs/protocol.md` 第 17 节。
- **405 不等于「端点不存在」。** `POST /api/workspace/upload` 在 2.3.0b2 上返回 405，
  因为同一路径下仍有其它方法的路由；`GET /api/workspace/download` 返回 404。
  判断端点是否可用只能以 404 与规格对照为准，不能拿 405 当结论。
- **别只在有数据的实例上回归。** `GET /api/token-usage` 的 `cache_hit_rate`
  在无调用时返回 `null`（两版都如此，OpenAPI 声明为 `number | null`）。
  这个形状只在干净实例上出现，曾经因此让用量页整页解析失败。

### 2.3 通用约定

- 探测服务端是否在线用 `GET /api/version`；
  `/api/status` 与 `/api/health` 都不存在，返回 404，不要拿它们做探活。
- **`qwenpaw` 命令只在交互式 shell 的 PATH 中。** 它由 `~/.qwenpaw/bin/qwenpaw`
  包装脚本提供，PATH 在 `~/.bashrc` 第 118 行注入；而该文件开头对非交互 shell 直接
  `return`，因此 Trae 的 shell 里 `which qwenpaw` 为空。非交互场景一律写全路径：
  `/home/csdetr/.qwenpaw/bin/qwenpaw`。
- 修改 `~/.qwenpaw/workspaces/default/agent.json` 前先备份到 `/tmp/agent.json.bak`。
  该文件的 `approval_level` 取值为 `STRICT` / `SMART` / `AUTO` / `OFF`，服务端默认 `AUTO`。
  **不存在 `ASK` 这个档位。** 依据是
  `qwenpaw/security/tool_guard/execution_level.py` 的 `ToolExecutionLevel.from_config`：
  无法解析的值一律回退成 `AUTO`，且不报错、不打日志，写错档位只能靠现场表现察觉。
  需要每一条工具调用都弹审批时改 `STRICT`；`AUTO` 下只有 `guarded_tools` 里的工具才触发。测完还原。
- **还原要核对，不要凭记忆。** 本机已出现过还原后残留 `OFF` 的情况。
  收尾时执行 `diff /tmp/agent.json.bak ~/.qwenpaw/workspaces/default/agent.json`，
  有差异就覆盖回去，再删备份。
- 测试会留下大量 running / idle 状态的会话。结束时应确认没有残留，
  必要时用 `POST /api/console/chat/stop?chat_id=` 收尾。

## 3、测试设备

**真机测试已停用，不要再尝试连接真机。** 首选 redroid 容器（3.1）；
redroid 不可用时回退到 AVD `qp35`（3.2）。两者的显示参数一致：720×1600、280dpi。
设备一律按需启动、用完关闭，见第 1 节第 3 条。

### 3.1 redroid 容器（首选）

- 镜像 `docker.m.daocloud.io/redroid/redroid:15.0.0_64only-latest`：Android 15、x86_64、userdebug。
  与 `targetSdk 35` 对齐，边到边行为与验收基准一致。**不要换回 Android 12 的镜像**：
  Android 15 起系统强制边到边，旧版本会得出相反结论。
- 官方镜像**不含 GMS**，也不需要 GMS。GMS 要自行用 Open GApps 另建镜像，不要引入。
  实测 `pm list packages | grep -ci "google|gms"` 结果为 0。
- compose 在 `scripts/redroid/docker-compose.yml`，入版本管理；`/data` 分区在仓库外，
  默认 `~/redroid/qp15`，可用 `REDROID_DATA` 覆盖。实测初始约 55 MB，
  装完 debug 包后 75 MB，会继续随使用增长到数 GB，**不要**放进仓库。
- 启动步骤（宿主每次重启后都要重做，`binder_linux` 不会自动加载）：

  ```bash
  sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
  sudo chmod 666 /dev/binder /dev/hwbinder /dev/vndbinder
  docker compose -f scripts/redroid/docker-compose.yml up -d
  ~/Android/Sdk/platform-tools/adb connect localhost:5555
  ```

- **为什么必须 `chmod 666`。** 本机内核 `6.12.111+deb13-amd64` 没有开
  `CONFIG_ANDROID_BINDERFS`（`grep CONFIG_ANDROID_BINDERFS /boot/config-$(uname -r)`
  无输出），镜像内 `/dev/binder` 是指向 `/dev/binderfs/binder` 的符号链接，挂载失败后悬空，
  surfaceflinger 以 `Binder driver could not be opened` 退出（实测退出码 129）。
  compose 已把宿主设备节点映射到符号链接的目标位置，但模块生成的节点权限是 `0600`，
  容器内非 root 进程打不开，因此要放开权限。
- 显示参数写死在 compose：**720×1600、280dpi**。
  像素数 1.15M，相比 AVD 的 1080×2400（2.59M）少 55%；dp 视口不变
  （720/280×160 = 411dp 宽，1080/420×160 = 411dp 宽），因此布局层面的既有结论仍然有效。
  `androidboot.use_memfd=1` 替代已在 6.x 内核移除的 ashmem；
  `androidboot.redroid_gpu_mode=guest` 是软件渲染，本机容器内没有可用 GPU。
- 容器内是 userdebug，`adb shell` 默认 `uid=0`，**不需要 `adb root`**。
- **A6 断网不要用 iptables。** 镜像内 `iptables -L` 报
  `can't initialize iptables table 'filter': Table does not exist`，netfilter 表不可用。
  改用路由黑洞，在容器自己的网络命名空间内执行，不修改宿主网卡，也不影响 5555 的 adb 连接：

  ```bash
  adb shell ip route add blackhole 10.10.0.211/32   # 换成实际服务端 IP
  adb shell ip route del blackhole 10.10.0.211/32
  ```

- 容器访问服务端仍走 LXC 容器地址：`10.10.0.211:8088`（稳定版）或 `10.10.0.220:8088`
  （预发布版）。**测试前确认 LXC 容器已启动**，否则 `ping 10.10.0.211` 会 100% 丢包。
  不要使用 `10.0.2.2:8088`，那指向宿主机，而宿主机实例不参与测试。
- **docker 到 LXC 的路径是通的，已实测。** redroid 容器 eth0 为 `172.20.0.2/16`，
  从该网络命名空间请求 `http://10.10.0.211:8088/api/version` 返回 HTTP 200
  （`2.2.2.post1`），容器内 `ping` 3/3 收到。这条路径依赖两件事：`ip_forward=1`，
  以及 `lxd-forward-fix.service` 在 `DOCKER-USER` 链放行 `iifname lxdbr0` 与
  `oifname lxdbr0` 的回程流量。镜像内没有 `curl`，toybox `netcat` 只能建连、读不到响应体，
  验证用宿主侧的 `nsenter -t <qp15 pid> -n curl ...`，路径与容器内完全一致。
- 宿主机重启后 `docker compose up` 之前必须先 `modprobe`，否则容器会因 binder 节点缺失
  反复崩溃。因此 compose 里 `restart` 固定为 `"no"`，不要改成 `unless-stopped`。

### 3.2 AVD 回退（redroid 不可用时）

只在 redroid 无法启动时使用。AVD 走 QEMU 用户态 NAT，与容器的桥接网络不是同一条路径，
服务端连通性要现场确认，不能照搬 3.1 的结论。

- AVD 名 `qp35`，Android 15（`system-images/android-35/google_apis/x86_64`）、x86_64、
  2 核、`hw.ramSize` 1536M、`PlayStore.enabled = no`。
- 显示参数已与 redroid 对齐：`~/.android/avd/qp35.avd/config.ini` 中
  `hw.lcd.width = 720`、`hw.lcd.height = 1600`、`hw.lcd.density = 280`。
  dp 视口仍是 411×914，与 redroid 相同，因此 `docs/ui-guidelines.md` 1.5 的布局期望通用；
  像素值按 720×1600 重取，不要沿用 1080×2400 的数字。
- **启动必须带 `-feature -BluetoothEmulation`。** 不加时 QEMU 卡在
  `detected a hanging thread 'QEMU2 CPU0 thread'` 并以段错误退出；
  `-accel off`、`-gpu off`、`-no-snapshot`、`-wipe-data` 都不能绕过，
  其中 `-accel off` 同样卡住可以排除 KVM。依据见 `docs/devlog.md` 44.5。

  ```bash
  ~/Android/Sdk/emulator/emulator -avd qp35 -feature -BluetoothEmulation &
  ~/Android/Sdk/platform-tools/adb wait-for-device
  ```

- 设备序列号是 `emulator-5554`，验收脚本用 `ADB_SERIAL=emulator-5554`。
- 服务端地址：历史上模拟器测试用 `adb reverse tcp:8088 tcp:8088` 指向宿主实例，
  而宿主机实例不参与测试（2.1）。回退时先确认 AVD 到 `10.10.0.211:8088` 是否可达，
  再用脚本，不要假设这条路径与 3.1 相同。
- A6 断网未在 AVD 上重测。脚本当前的 `ip route add blackhole` 实现是按 redroid 写的。
- 收尾：`~/Android/Sdk/platform-tools/adb -s emulator-5554 emu kill`。

### 3.3 通用

- 操作界面时坐标一律从 `uiautomator dump` 动态获取，不要凭截图目测。
  截图是缩放的，按截图坐标点击会落空。
- `adb shell input text` 不支持中文，空格用 `%s`。
- 验收脚本：`ADB_SERIAL=localhost:5555 ./scripts/e2e_acceptance.sh --only <A1..A6>`。
  脚本默认操作 debug 包 `io.qwenpaw.node.debug`（见第 4 节），可用 `ADB_PKG` 覆盖。
  A6 断网场景走 3.1 的路由黑洞。
  **脚本已移除真机分支**，只按 redroid 维护；`su -c` 免密提权一类的手段不再保留。
- **截图与像素采样**：验证配色（如深色模式文字对比度）时，从 `adb exec-out screencap -p`
  取 PNG 后自行解码采样；环境里没有 PIL，需要手写 PNG 解码。

## 4、构建与签名

- Java 21 编译，字节码目标 17。AGP 8.7.3、Kotlin 2.1.0、KSP 2.1.0-1.0.29、
  Gradle wrapper 8.9、compileSdk 35、minSdk 26、targetSdk 35。
  `compileOptions` 与 `kotlinOptions` 都写 `17`，与 JDK 21 编译环境并存；
  改 `jvmTarget` 会连带影响 KSP 生成的代码，不要只改一处。
- release 签名密钥在仓库之外：`~/.qwenpaw-node-keys/`
  （`release.jks` 与 `keystore.properties`）。**不要复制进仓库**，
  `.gitignore` 已排除 `*.jks` / `*.keystore` / `keystore.properties`。
  密钥缺失时 release 退化为未签名，仍可构建，但不能安装与发布——
  构建成功不等于包可用，`assembleRelease` 之后要确认签名配置确实生效。
- release 开启了 R8 与资源压缩。改动依赖后必须重新验证一次 `assembleRelease`，
  不能只验 debug，因为部分问题只在 R8 处理后暴露。
- **新增或升级依赖时要同时确认两件事。** 一是 `app/proguard-rules.pro` 是否需要补 keep 规则
  （反射、序列化、Room 生成类都靠它）；二是四个 ABI 的包体积变化：
  `sherpa-onnx` 的 `.so` 每架构 20–30 MB，是体积的主要来源，
  新增 native 依赖必须复核 `assembleRelease` 输出的四个包，不能只看 arm64-v8a。
- **镜像仓库按环境切换。** `settings.gradle.kts` 在 `CI=true` 时跳过阿里云镜像，
  只使用 `google()` / `mavenCentral()` / `gradlePluginPortal()`，因为镜像在
  GitHub Actions 的 runner 上不可达。Trae 的 shell 也带 `CI=true`，
  在其中构建走的是官方仓库，与用户自己的终端不同。
  需要在本机复现 runner 的解析行为时，用 `CI=true ./gradlew <task>`。
- **涉及时间与时区的单测必须显式固定时区。** CI 的 runner 是 UTC，本机是 Asia/Shanghai，
  本地通过、CI 失败的典型原因就是断言里隐含了默认时区。
  写法参照 `TimeLabelsTest`：`TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))`
  与 `ZoneId.of("Asia/Shanghai")` 显式写出，不依赖运行环境。
- **CI 签名密钥来自四个 Repository secrets**：`ANDROID_KEYSTORE_BASE64`、
  `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。
  推 `v*` tag 触发 `.github/workflows/release.yml`，细节见 `docs/release.md` 5.3。
- **在这台机器上执行 `git push` 时加 `GIT_ASKPASS=/bin/false GIT_TERMINAL_PROMPT=0`。**
  Trae 注入的 askpass 脚本在没有交互终端时会永久等待，表现为命令超时而非报错。
- **debug 与 release 用不同包名，可同机共存。** debug 的 applicationId 为
  `io.qwenpaw.node.debug`，应用名为 `[debug]QP Node`（由
  `app/src/debug/res/values/strings.xml` 覆盖 `app_name`）；release 仍为
  `io.qwenpaw.node` / `QwenPaw Node`。装 debug 不再需要先卸载 release，
  两者的登录状态与缓存互不影响。
  设备侧的 `pm`、`am` 命令与 `scripts/e2e_acceptance.sh` 默认按 debug 包名执行，
  脚本可用 `ADB_PKG` 覆盖。
- 相关文档：协议细节与已知坑见 `docs/protocol.md`；UI、边到边与性能测量口径见
  `docs/ui-guidelines.md`；目录与数据流见 `docs/architecture.md`；
  发版流程见 `docs/release.md`。

## 5、文档

### 5.1 语体分档

文档按受众分两档，语体不同。修改文档前先确认它属于哪一档。

| 档位 | 文件 | 语体要求 |
| --- | --- | --- |
| **技术档** | `docs/architecture.md`、`docs/protocol.md`、`docs/ui-guidelines.md`、`docs/release.md`、`docs/devlog.md`、`AGENTS.md`、`待办清单.md` | 正式书面语 |
| **用户档** | `README.md`、`CHANGELOG.md`、`docs/guide.md` | 书面语，适度口语化 |

**用户档的定位：以书面语为主，只是把难度降下来，不是把语气变成聊天。**

判据是「书面语是否仍然成立」。说明文、手册、变更记录都应当保持书面语的句子结构与用词，
在此基础上做三件事：换成普通读者认识的词、把长句拆短、把抽象说法换成具体说法。
不应当把书面语改写成口语对话。

**技术档规则**

1. 使用完整句陈述，不用「支持 X」「进行 Y」这类名词化懒句。
2. 每个结论必须带依据：实测数据、代码位置或服务端字段名。
3. 保留术语原形（`statusBarsPadding`、`inSampleSize`、`msg_id`），不做中文意译。
4. **信息密度可以高，但不许为了顺口而删事实。** 重写后必须对照原文件逐条核对。
5. 不用第一人称叙事，不用「我们」。

**用户档规则**

1. 保持书面语。可以出现「请确认」「需先」「属于预期表现」这类表述，
   不要降格为「你试试」「就行了」「卡是正常的」。
2. 使用第二人称「你」或无人称叙述均可，但避免「我们」。
3. 句子以短句为主，一句说一件事；不为了简短而省掉必要成分（主语、条件、范围）。
4. 用普通读者认识的词，但不牺牲准确性。不确定的地方保留「约」「通常」「多数情况下」。
5. 不用「强大、流畅、极致、全方位、赋能」这类空泛形容词。
6. 不用客服腔，例如「感谢反馈」「如有疑问欢迎随时联系」。
7. 不用网络口语与语气词：「搞定」「卡是正常的」「看一眼」「点一个选上就行」。
8. 不硬凑三段式。有两项就写两项。
9. 界面上的按钮与开关一律用「」包住，与正文区分。
10. 技术细节不删，但放到段末，或加链接指向技术档。
11. 不编造功能。只写代码里真实存在的界面项，文案要与源码字符串核对。

### 5.2 开发日志必须随时更新

`docs/devlog.md` 是按时间顺序记录过程与决策的日志。

**每完成一轮有实质产出的工作，就立刻追加一节，不要攒着。**

1. 只记有信息量的内容：决策理由、被推翻的假设、踩过的坑、实测数据。
   功能清单那种流水账交给 `CHANGELOG.md`，不要重复。
2. **弯路要写。** 这一路最有价值的内容恰恰是「哪些方向已经被证明是错的」。
   不写下来，下一个人或下一轮会重走一遍。
3. 数字要当场取（`ls -la`、`wc -l`、`git log`），不要凭记忆或照抄上一版。
   本项目已经在体积、单测数、APK 大小上错过多次。
4. 追加时同步更新文首的目录表，并确认锚点仍然可跳。
5. 写过程与理由，不写「已完成 X 功能」这类结论式套话。
6. 每节末尾尽量指明结论现在落在哪份正式文档，便于从日志跳转到结论。
7. **节号一律用阿拉伯数字**：标题写 `## 13、xxx`，引用写「第 13 节」或「第 7、8 节」。
   小节写 `### 13.5 xxx`。同一规则适用于 `docs/` 下其余文档与 `AGENTS.md` 本身，
   这样跨文档引用不必换算。正文里的数量词（「三类信息」「九项体验」）保持汉字。

### 5.3 每轮改动的草稿

对项目的每一次改动，都先在项目目录的 `draft/` 下创建一个以时间戳命名的 Markdown 文件，
作为这一轮的草稿。所有过程性内容只写进草稿。

**命名格式**：`draft/YYYY.MM.DD-HH·MM·SS.md`，例如 `draft/2026.10.26-14·07·33.md`。

时间取**用户发起这一轮对话的时刻**，而不是开始动手的时刻。
日期用点号分隔、时间用间隔号分隔，目的是避开文件名中不允许出现的冒号。
若一轮对话跨越较长时间，仍沿用发起时刻，不因中途继续而改名。

**草稿内容**：这一轮要改什么、为什么改、涉及哪些文件、验证结果、尚待确认的问题。
按讨论推进逐步补全，不要求一次写完整。

**草稿不纳入版本管理。** `.gitignore` 已排除 `draft/`，不要提交其中的任何文件。

### 5.3.1 完成之前不许动正式文档

**这是硬性顺序，不是建议。**

在一轮任务完成之前，不得修改 `docs/`、`README.md`、`CHANGELOG.md`、`待办清单.md`
等任何正式文档。中间产物一律留在草稿里。

原因不是谨慎，而是防污染。上下文会被压缩、会丢失、会被后续对话覆盖，
如果一边做一边改文档，被压缩掉的中间状态就会以确定语气留在文档里，
读者无法分辨哪些是已核实的结论、哪些是中途被推翻的设想。
本项目已经出现过这种情况，文档因此出现与代码不符的表述。

顺序固定为：

1. 改动开始前创建草稿，边做边写。
2. 编码与验证全部结束、结论确定之后，才一次性把结论**提炼**进正式文档与 `docs/devlog.md`。
   提炼规则见 5.1 的语体分档与 5.2 的日志要求。
3. 正式文档写完并核对无误后，**删除**草稿。

**草稿不能替代正式文档。** 它是过程载体，结论必须落到 5.4 表中对应的文件里；
只写草稿而没更新正式文档，等于这一轮没有沉淀。

### 5.4 各文档分工

| 文件 | 定位 | 语体 | 更新时机 |
| --- | --- | --- | --- |
| `draft/YYYY.MM.DD-HH·MM·SS.md` | 本轮改动的草稿，不入版本管理 | 不限 | **每轮改动开始时创建，正式文档写完后删除** |
| `docs/devlog.md` | 过程与决策记录 | 技术档 | **每轮工作结束立刻追加** |
| `CHANGELOG.md` | 面向用户的变更 | 用户档 | 发版前 |
| `README.md` | 仓库首页，面向使用者 | 用户档 | 功能或安装方式变化时 |
| `docs/guide.md` | 使用手册，面向使用者 | 用户档 | 界面或操作流程变化时 |
| `docs/protocol.md` | 服务端协议实测与坑 | 技术档 | 发现新端点或新坑时 |
| `docs/ui-guidelines.md` | UI 约定与性能口径 | 技术档 | 新增 UI 约定或测量方法时 |
| `docs/architecture.md` | 分层与状态流转 | 技术档 | 结构调整时 |
| `docs/release.md` | 发版流程 | 技术档 | 发版流程变化时 |
| `待办清单.md` | 按版本排期的未接入能力；另收明确记录的客户端渲染层欠项（如 0.1.3 的上下标缩字号） | 技术档 | 一版做完后把该版小节移入「已发布」并写下 tag，**同时从「版本总览」的未发布表里删去该行** |

**「版本总览」只列未发布的版本。** 判断「这一版还有没有没做完的」看的是这张表：
一版发完若只在「已发布」里加一行、却把版本总览里的旧行留着，读者会以为它仍在计划中。
两处必须同时改。

还有两类目录不在上表内，但同样要注意定位：

| 路径 | 定位 | 约束 |
| --- | --- | --- |
| `prototype/` | UI 原型稿（`ui-prototype.html`）与素材，入库 | 配色与布局是 `docs/ui-guidelines.md` 的来源，但原型不是实现，**不得作为验收依据**；`assets/` 下的字体文件已被忽略，只有 `qwenpaw.png` 入库 |
| `.trae/documents/` | 历轮 AI 助手的过程记录 | **只读，且被 `.gitignore` 排除**。内容含本机绝对路径与未经验证的中间结论，不得当作项目文档修改或引用为事实 |

**`docs/test-plan.md` 已废弃。** 该文件从未入库，历史上 `devlog.md` 与 `README.md` 对它的引用
均指向不存在的目标。测试用例的现状统一由 `docs/architecture.md` 第 7 节描述；
过程记录保留在 `devlog.md` 原文中，不做追溯改写。

**对话记录不是项目文档。** 不要把过程记录当成结论文档去改。
要沉淀的内容应提炼进上面这些文件；`README.md` 与 `docs/guide.md` 里不要放构建命令与发版细节。

### 5.5 界面文案

界面文案与文档语体是两件事，但同样按受众分档。判据是「给谁看」。

**App 内文案属于用户档，但比 `docs/guide.md` 更严格。** 界面空间有限，
且用户不能回看上下文，因此：

1. 一句说一件事，优先用动词开头的短句（「重试」「停止生成」），不用名词化结构。
2. 不使用「请」以外的敬辞，不用网络口语与语气词。
3. 错误提示必须说明**发生了什么**与**可以做什么**，不能只写「操作失败」。
4. 状态词保持单一写法：同一状态在列表、顶栏、气泡里用同一个词，不做同义替换。
5. **不用比喻与形象化的说法。** 「是两回事」「暴露在广域网」这类句子看似生动，
   实际要读者自己补出含义；应直接写清两者分别在什么条件下发生、后果是什么。
   例：「它与「Web登录认证」无关」「关闭后，服务器地址可从公网访问，且服务端未开启
   「Web登录认证」或未使用 HTTPS 时，不再提醒。」
6. **「·」不用来连接并列成分。** 「我·头像」「跟随系统·字号 100%」一律改成
   「我的头像」「跟随系统与字号」「跟随系统，字号 100%」这类正常写法。
   「·」只保留在时间、版本号等本身需要分隔符的地方。
7. 中文引号统一用「」。不要在「」里再套英文引号，也不要出现落单或错位的引号。
8. 产品名与端点名保留原写法（QwenPaw、QwenPaw Node、QwenPaw Node bridge），
   直接放进句子，不做中文意译，也不改写大小写。

**文案当前硬编码在 Compose 源码里，这是已知状态。** `strings.xml` 只有 5 条通知文案，
`app/src/main/java` 下约 45 个文件、662 处中文字面量，`stringResource` 调用数为 0。
本轮不要求迁移到资源文件；新增文案与既有文案放在一起，保持写法一致即可。
若要开始迁移，应作为独立一轮处理，避免与功能改动混在一起。

**改动文案必须回查源码字符串。** 文档与截图里的文案可能滞后于代码，
以 `app/src/main/java` 下的实际字符串为准。

## 6、提交与门禁

### 6.1 提交信息

仓库只有 `master` 一个长期分支，提交信息统一使用「前缀: 中文描述」的形式。
现有 15 条提交使用过的前缀及分布为：`ci` 7 条、`feat` 3 条、`test` 2 条、
`docs` 1 条、`build` 1 条、`chore` 1 条。

| 前缀 | 适用 |
| --- | --- |
| `feat` | 新增功能或用户可感知的行为 |
| `fix` | 修复缺陷 |
| `test` | 只动测试代码 |
| `docs` | 只动文档，含 `docs/`、`README.md`、`AGENTS.md` |
| `build` | 构建配置、依赖、版本号、Gradle 与 ProGuard 规则 |
| `ci` | `.github/workflows/` 与 CI 相关脚本 |
| `chore` | 许可、忽略规则、素材整理等杂项 |

描述写**做了什么**，不写「更新代码」这类无信息量的说法；同一轮的多个文件放在一个提交里，
不要把一次逻辑改动拆成若干零碎提交。

### 6.2 提交前门禁

改完不等同于可提交。按改动类型逐项核对，任何一项未过都不提交。

| 改了 | 必须执行 | 通过判据 |
| --- | --- | --- |
| 任何 `app/src/main` 代码 | `./gradlew :app:testDebugUnitTest` | 全部用例通过；新增逻辑补了对应用例 |
| 依赖、`proguard-rules.pro`、`build.gradle.kts`、版本号 | `./gradlew :app:assembleRelease` | 构建成功，四个 ABI 的包都产出，签名配置生效 |
| 涉及时间、时区、格式化的断言 | 同上单测 + 固定 `ZoneId` | 显式写出时区，不依赖运行环境的默认值 |
| 任何文档 | 对照 5.1 / 5.5 的语体规则逐条核对 | 文案与源码字符串一致，链接目标存在 |
| 端口、地址、路径类常量 | 实际执行一次相关命令 | 命令能跑通，不是照抄旧记录 |

构建一次只跑一个，两次构建之间留出间隔并检查 `free -h`（见第 1 节）。