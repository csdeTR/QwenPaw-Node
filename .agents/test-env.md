# 测试环境

本文说明 LXC 服务端容器与测试设备的搭建、启动与收尾。
交汇处的硬约束（内存水位、用完即关、容器资源上限）写在 [AGENTS.md](../AGENTS.md) 第 1 节，
本文只记操作步骤与实测依据。

---

## 1、LXC 服务端容器

**测试不使用本机的 `:8088` 实例。** 本机实例承载用户的真实会话与记忆，
在上面做写操作会污染真实数据，历史上有过误归档真实会话的教训。

| 容器 | IP | 版本 | 用途 |
| --- | --- | --- | --- |
| `qp-test` | `10.10.0.211:8088` | `2.2.2.post1` | 稳定版基线，与用户当前使用的版本一致 |
| `qp-test-prerelease` | `10.10.0.220:8088` | `2.3.0b2` | 预发布版，用于提前发现破坏性变更 |

### 1.1 连接与启动

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
- **`qwenpaw` 命令只在交互式 shell 的 PATH 中。** 它由 `~/.qwenpaw/bin/qwenpaw`
  包装脚本提供，PATH 在 `~/.bashrc` 第 118 行注入；而该文件开头对非交互 shell 直接
  `return`，因此 Trae 的 shell 里 `which qwenpaw` 为空。非交互场景一律写全路径：
  `/home/csdetr/.qwenpaw/bin/qwenpaw`。

### 1.2 停止与探活

- 停止服务后要确认端口已释放：`ss -ltnp | grep 8088` 应无输出。
  直接 `pkill -f "qwenpaw app"` 在容器里会因权限失败，需加 `-u csdetr`，
  或从 `screen` 会话退出。
- 探测服务端是否在线用 `GET /api/version`；
  `/api/status` 与 `/api/health` 都不存在，返回 404，不要拿它们做探活。

### 1.3 `lxdbr0` 停在 `UNAVAILABLE`

先查 `nft_masq` 模块。LXD 5.21 建托管桥时写入
`ip saddr 10.10.0.0/24 ... oifname != "lxdbr0" masquerade`，该表达式由 `nft_masq.ko` 提供；
模块缺失时 nf_tables 返回 ENOENT，journal 表现为
`Failed adding outbound NAT rules … Could not process rule: No such file or directory`，
`lxc network list` 中桥停在 `UNAVAILABLE`，所有容器无法启动。本机已由
`/etc/modules-load.d/lxd-nft-masq.conf` 固化该模块；仍未生效时执行
`sudo modprobe nft_masq` 后 `sudo snap restart lxd`。
docker 走的是 iptables-nft 的 xt `MASQUERADE`，因此该模块不会被 docker 带起来。

### 1.4 审批档位

修改 `~/.qwenpaw/workspaces/default/agent.json` 前先备份到 `/tmp/agent.json.bak`。
该文件的 `approval_level` 取值为 `STRICT` / `SMART` / `AUTO` / `OFF`，服务端默认 `AUTO`。
**不存在 `ASK` 这个档位。** 依据是
`qwenpaw/security/tool_guard/execution_level.py` 的 `ToolExecutionLevel.from_config`：
无法解析的值一律回退成 `AUTO`，且不报错、不打日志，写错档位只能靠现场表现察觉。
需要每一条工具调用都弹审批时改 `STRICT`；`AUTO` 下只有 `guarded_tools` 里的工具才触发。测完还原。

**还原要核对，不要凭记忆。** 本机已出现过还原后残留 `OFF` 的情况。
收尾时执行 `diff /tmp/agent.json.bak ~/.qwenpaw/workspaces/default/agent.json`，
有差异就覆盖回去，再删备份。

### 1.5 会话残留

测试会留下大量 running / idle 状态的会话。结束时应确认没有残留，
必要时用 `POST /api/console/chat/stop?chat_id=` 收尾。

---

## 2、redroid 容器（首选设备）

**真机测试已停用，不要再尝试连接真机。** redroid 不可用时回退到 AVD `qp35`（第 3 节）。
两者的显示参数一致：720×1600、280dpi。设备一律按需启动、用完关闭，
收尾命令见 [AGENTS.md](../AGENTS.md) 第 1 节第 3 条。

### 2.1 镜像与数据分区

- 镜像 `docker.m.daocloud.io/redroid/redroid:15.0.0_64only-latest`：Android 15、x86_64、userdebug。
  与 `targetSdk 35` 对齐，边到边行为与验收基准一致。**不要换回 Android 12 的镜像**：
  Android 15 起系统强制边到边，旧版本会得出相反结论。
- 官方镜像**不含 GMS**，也不需要 GMS。GMS 要自行用 Open GApps 另建镜像，不要引入。
  实测 `pm list packages | grep -ci "google|gms"` 结果为 0。
- compose 在 `scripts/redroid/docker-compose.yml`，入版本管理；`/data` 分区在仓库外，
  默认 `~/redroid/qp15`，可用 `REDROID_DATA` 覆盖。实测初始约 55 MB，
  装完 debug 包后 75 MB，会继续随使用增长到数 GB，**不要**放进仓库。

### 2.2 启动

宿主每次重启后都要重做，`binder_linux` 不会自动加载：

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
- 宿主机重启后 `docker compose up` 之前必须先 `modprobe`，否则容器会因 binder 节点缺失
  反复崩溃。因此 compose 里 `restart` 固定为 `"no"`，不要改成 `unless-stopped`。
- 容器内是 userdebug，`adb shell` 默认 `uid=0`，**不需要 `adb root`**。

### 2.3 显示参数

写死在 compose：**720×1600、280dpi**。
像素数 1.15M，相比 AVD 原生的 1080×2400（2.59M）少 55%；dp 视口不变
（720/280×160 = 411dp 宽，1080/420×160 = 411dp 宽），因此布局层面的既有结论仍然有效。
`androidboot.use_memfd=1` 替代已在 6.x 内核移除的 ashmem；
`androidboot.redroid_gpu_mode=guest` 是软件渲染，本机容器内没有可用 GPU。

### 2.4 A6 断网

**不要用 iptables。** 镜像内 `iptables -L` 报
`can't initialize iptables table 'filter': Table does not exist`，netfilter 表不可用。
改用路由黑洞，在容器自己的网络命名空间内执行，不修改宿主网卡，也不影响 5555 的 adb 连接：

```bash
adb shell ip route add blackhole 10.10.0.211/32   # 换成实际服务端 IP
adb shell ip route del blackhole 10.10.0.211/32
```

### 2.5 到服务端的网络路径

容器访问服务端走 LXC 容器地址：`10.10.0.211:8088`（稳定版）或 `10.10.0.220:8088`
（预发布版）。**测试前确认 LXC 容器已启动**，否则 `ping 10.10.0.211` 会 100% 丢包。
不要使用 `10.0.2.2:8088`，那指向宿主机，而宿主机实例不参与测试。

**docker 到 LXC 的路径是通的，已实测。** redroid 容器 eth0 为 `172.20.0.2/16`，
从该网络命名空间请求 `http://10.10.0.211:8088/api/version` 返回 HTTP 200
（`2.2.2.post1`），容器内 `ping` 3/3 收到。这条路径依赖两件事：`ip_forward=1`，
以及 `lxd-forward-fix.service` 在 `DOCKER-USER` 链放行 `iifname lxdbr0` 与
`oifname lxdbr0` 的回程流量。镜像内没有 `curl`，toybox `netcat` 只能建连、读不到响应体，
验证用宿主侧的 `nsenter -t <qp15 pid> -n curl ...`，路径与容器内完全一致。

---

## 3、AVD 回退（redroid 不可用时）

AVD 走 QEMU 用户态 NAT，与容器的桥接网络不是同一条路径，
服务端连通性要现场确认，不能照搬第 2 节的结论。

- AVD 名 `qp35`，Android 15（`system-images/android-35/google_apis/x86_64`）、x86_64、
  2 核、`hw.ramSize` 1536M、`PlayStore.enabled = no`。
- 显示参数已与 redroid 对齐：`~/.android/avd/qp35.avd/config.ini` 中
  `hw.lcd.width = 720`、`hw.lcd.height = 1600`、`hw.lcd.density = 280`。
  dp 视口仍是 411×914，与 redroid 相同，因此 [ui-guidelines.md](../docs/ui-guidelines.md) 1.5 的布局期望通用；
  像素值按 720×1600 重取，不要沿用 1080×2400 的数字。
- **启动必须带 `-feature -BluetoothEmulation`。** 不加时 QEMU 卡在
  `detected a hanging thread 'QEMU2 CPU0 thread'` 并以段错误退出；
  `-accel off`、`-gpu off`、`-no-snapshot`、`-wipe-data` 都不能绕过，
  其中 `-accel off` 同样卡住可以排除 KVM。依据见 [devlog.md](../docs/devlog.md) 44.5。

  ```bash
  ~/Android/Sdk/emulator/emulator -avd qp35 -feature -BluetoothEmulation &
  ~/Android/Sdk/platform-tools/adb wait-for-device
  ```

- 设备序列号是 `emulator-5554`，验收脚本用 `ADB_SERIAL=emulator-5554`。
- 服务端地址：历史上模拟器测试用 `adb reverse tcp:8088 tcp:8088` 指向宿主实例，
  而宿主机实例不参与测试（第 1 节）。回退时先确认 AVD 到 `10.10.0.211:8088` 是否可达，
  再用脚本，不要假设这条路径与 redroid 相同。
- A6 断网未在 AVD 上重测。脚本当前的 `ip route add blackhole` 实现是按 redroid 写的。
- 收尾：`~/Android/Sdk/platform-tools/adb -s emulator-5554 emu kill`。

---

## 4、设备通用操作

- 操作界面时坐标一律从 `uiautomator dump` 动态获取，不要凭截图目测。
  截图是缩放的，按截图坐标点击会落空。
- `adb shell input text` 不支持中文，空格用 `%s`。
- 验收脚本：`ADB_SERIAL=localhost:5555 ./scripts/e2e_acceptance.sh --only <A1..A6>`。
  脚本默认操作 debug 包 `io.qwenpaw.node.debug`，可用 `ADB_PKG` 覆盖。
  A6 断网场景走第 2.4 节的路由黑洞。
  **脚本已移除真机分支**，只按 redroid 维护；`su -c` 免密提权一类的手段不再保留。
  场景划分与常见失败原因见 [release.md](../docs/release.md) 4.3。
- **截图与像素采样**：验证配色（如深色模式文字对比度）时，从 `adb exec-out screencap -p`
  取 PNG 后自行解码采样；环境里没有 PIL，需要手写 PNG 解码。
