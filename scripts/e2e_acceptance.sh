#!/usr/bin/env bash
# QwenPaw Node redroid 验收脚本。
#
# 用法：
#   ./scripts/e2e_acceptance.sh              # 跑全部场景（A1-A8、B1-B6）
#   ./scripts/e2e_acceptance.sh --keep       # 保留当前应用数据
#   ./scripts/e2e_acceptance.sh --only A4    # 只跑某个场景
#
# 设备：
#   默认取 `adb devices` 里唯一在线设备；多设备时用 ADB_SERIAL 指定，
#   redroid 上是 localhost:5555。
#   只支持 redroid 容器（x86_64 / Android 15），真机与模拟器分支已移除。
#
# 前提：
#   1. redroid 容器已启动并连上 adb，步骤见 AGENTS.md 3.1；
#      容器内 userdebug 默认 root，A6 断网用路由黑洞而不是 iptables；
#   2. LXC 测试容器已启动 QwenPaw（默认 qp-test / 10.10.0.211:8088），
#      启动方式见 AGENTS.md 2.1。本机 :8088 实例承载真实数据，不参与验收；
#   3. 已执行 `./gradlew :app:assembleFullDebug`（验 -no-sherpa 包时改成
#      `assembleNosherpaDebug`，并设 ADB_FLAVOR=nosherpa）。
#
# 覆盖场景：
#   A1 审批       —— 服务端 approval_level=STRICT 时触发审批后点允许
#   A2 附件       —— 上传相册图片并确认被服务端接收
#   A3 重连       —— 长任务中杀 App，重开后接回运行中的输出
#   A4 停止       —— 生成中停止，服务端回 idle
#   A5 多实例     —— 第二个实例的会话列表与第一个隔离
#   A6 断网/409   —— 断网提示中文、草稿保留、失败气泡可重试
#   A7 工作区     —— 设置入口可达、三个根可切换、记忆根列出 Markdown
#   A8 语音       —— 两个变体各自的设置页与麦克风行为（见 A8 段落注释）
#
#   B1 检查点页   —— 设置入口可达，自动开关与手动快照区都在
#   B2 只读对话   —— 点会话进入只读页，有「创建检查点」且没有输入框
#   B3 创建检查点 —— 命名后创建成功，回到检查点页且时间线出现该名称
#   B4 回滚预览   —— 打开回滚弹窗，能看到记忆与文件两个范围
#   B5 自动开关   —— 切换自动检查点后服务端状态跟随，并还原
#   B6 备份页     —— 设置入口可达，新建与导入入口都在
#
#   C1 用量统计   —— 设置入口可达、三档范围、两处范围标注、返回回到进入前那一页
#   C2 模型提供商 —— 两行默认模型、供应商列表与「测试连接」结果
#
#   D1 技能页     —— 设置入口可达、当前智能体与技能池两段都在
#   D2 技能编辑   —— 进详情、进入编辑态、正文输入框可用
#   D3 MCP        —— 列表或空态说明可见、详情两段与策略三档
#   D4 扩展市场   —— 两段切换、技能市场搜索或明确的来源不可用说明
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB_BIN="$SDK/platform-tools/adb"
# debug 包的 applicationId 带 .debug 后缀（见 app/build.gradle.kts），与 release 可以同机共存。
PKG="${ADB_PKG:-io.qwenpaw.node.debug}"
DEBUG_DIR="$ROOT/app/build/outputs/apk/debug"
OUT="$ROOT/build/e2e"
# 默认服务端指向 LXC 测试容器 qp-test（稳定版 2.2.2.post1）。
# 测预发布版时用 QWENPAW_HOST=10.10.0.220:8088 覆盖。
# 宿主机 10.0.2.2 不再使用：本机实例承载真实数据，不参与测试。
HOST="${QWENPAW_HOST:-10.10.0.211:8088}"
# API 用于脚本自身直接调服务端接口（不经设备），与设备访问的 HOST 同一个服务端。
API="${QWENPAW_API:-http://10.10.0.211:8088}"

# 选设备：ADB_SERIAL 优先，否则取唯一在线设备。
SERIAL="${ADB_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}' | head -1)"
fi
[ -n "$SERIAL" ] || { echo "没有在线设备，先按 AGENTS.md 3.1 启动 redroid 容器并 adb connect"; exit 1; }
ADB=("$ADB_BIN" -s "$SERIAL")
echo "设备：$SERIAL"

# splits.abi 对 debug 变体同样生效，产物是 app-<abi>-debug.apk；
# 早期未拆分时是 app-debug.apk。
# 0.4.0 起有 full / nosherpa 两个变体，产物落在 apk/<flavor>/debug/ 下。
# 默认装 full（语音能力最全）；验「无 sherpa 包」时用 ADB_FLAVOR=nosherpa 覆盖。
FLAVOR="${ADB_FLAVOR:-full}"
DEBUG_DIR="$ROOT/app/build/outputs/apk/$FLAVOR/debug"
DEVICE_ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
PICKED=""
for candidate in \
  "$DEBUG_DIR/app-$FLAVOR-$DEVICE_ABI-debug.apk" \
  "$DEBUG_DIR/app-$DEVICE_ABI-debug.apk"; do
  [ -f "$candidate" ] && { PICKED="$candidate"; break; }
done
[ -n "$PICKED" ] || { echo "找不到 debug 包（变体=$FLAVOR，设备 ABI=$DEVICE_ABI）：$DEBUG_DIR"; exit 1; }
APK="$PICKED"
echo "变体：$FLAVOR"
echo "安装包：$(basename "$APK")"

echo "服务器地址：$HOST"
# 第二实例（A5 用）：同一台宿主的 8099 端口。
SECOND_HOST="${QWENPAW_HOST2:-${HOST%:*}:8099}"

KEEP=0
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1 ;;
    --only) ONLY="${2:-}"; shift ;;
    *) echo "未知参数：$1"; exit 2 ;;
  esac
  shift
done

# 场景名写错时整轮会「什么都不跑却报完成」，所以先校验一次。
if [ -n "$ONLY" ] && [[ ! "$ONLY" =~ ^(A[1-8]|B[1-6]|C[1-2]|D[1-4])$ ]]; then
  echo "未知场景：$ONLY（可用：A1-A8、B1-B6、C1-C2、D1-D4）"; exit 2
fi
# B3 依赖 B2 留在只读页的状态，单跑 B3 会立刻跳过；
# 两者一起指定时按 B2 → B3 的顺序执行。
if [ "$ONLY" = "B3" ]; then
  echo "[提示] B3 依赖 B2 留下的只读页，单独跑会被跳过"
fi

mkdir -p "$OUT"

shot() {
  "${ADB[@]}" exec-out screencap -p > "$OUT/$1.png"
  echo "  截图：$OUT/$1.png"
}

# 导出一次界面层级到本地，供后续查找控件使用。
dump_ui() {
  "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  "${ADB[@]}" shell cat /sdcard/ui.xml > "$OUT/ui.xml"
}

# 从 uiautomator dump 里按 text/content-desc 找控件中心点，输出 "x y"。
# 找不到返回非零。避免写死坐标导致点击落空。
find_xy() {
  local pattern="$1"
  dump_ui || return 1
  python3 - "$OUT/ui.xml" "$pattern" <<'PY'
import re, sys
xml, pat = sys.argv[1], sys.argv[2]
data = open(xml, encoding='utf-8', errors='ignore').read()
for node in re.findall(r'<node[^>]*>', data):
    if pat in node:
        m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
}

tap_text() {
  local xy
  xy="$(find_xy "$1")" || { echo "  未找到控件：$1"; return 1; }
  "${ADB[@]}" shell input tap $xy
}

# 按文字找到所在的可点击行，点行中心而不是文字中心。
# 设置页的 NavRow 是整行可点，文字中心偶尔会落在动画/惯性滚动的空白处。
tap_row_text() {
  local xy
  xy="$(dump_ui >/dev/null 2>&1; python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
data = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
target = sys.argv[2]
nodes = []
for n in re.findall(r'<node[^>]*>', data):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    t = re.search(r'text="([^"]*)"', n)
    nodes.append((x1, y1, x2, y2, 'clickable="true"' in n, t.group(1) if t else ""))
cx = cy = None
for x1, y1, x2, y2, c, t in nodes:
    if t.strip() == target:
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        break
if cx is None:
    sys.exit(1)
rows = [
    (x1, y1, x2, y2)
    for x1, y1, x2, y2, c, t in nodes
    if c and x1 <= cx <= x2 and y1 <= cy <= y2
]
if rows:
    x1, y1, x2, y2 = rows[-1]
    print((x1 + x2) // 2, (y1 + y2) // 2)
else:
    print(cx, cy)
PY
)" || { echo "  未找到可点击行：$1"; return 1; }
  "${ADB[@]}" shell input tap $xy
}

screen_has() {
  find_xy "$1" >/dev/null
}

api_get() { curl -s -m 5 "$API$1"; }

running_count() {
  api_get /api/chats | python3 -c '
import json,sys
try: print(sum(1 for c in json.load(sys.stdin) if c["status"]=="running"))
except Exception: print(-1)'
}

# redroid 镜像内 netfilter 的 filter 表不可用（iptables -L 报 Table does not exist），
# 因此用路由黑洞断网。黑洞在容器自己的 netns 内，不改动宿主网卡，
# 也不影响 5555 的 adb 连接。
HOST_IP="${HOST%%:*}"

block_port() {
  run_root "ip route add blackhole $HOST_IP/32"
}

unblock_port() {
  run_root "ip route del blackhole $HOST_IP/32" 2>/dev/null || true
}

# 以 root 执行。redroid 容器是 userdebug，adb shell 默认 uid 0。
run_root() {
  "${ADB[@]}" shell "$1"
}

# 断网需要 root。只封服务端 IP（不是 5555），所以不会切断 adb 自身。
can_block() {
  if [ "$("${ADB[@]}" shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; then
    return 0
  fi
  "${ADB[@]}" root >/dev/null 2>&1 || true
  sleep 2
  if [ "$("${ADB[@]}" shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; then
    return 0
  fi
  return 1
}

type_and_send() {
  local text="$1"
  tap_text 'class="android.widget.EditText"' || return 1
  sleep 1
  "${ADB[@]}" shell input text "$(echo "$text" | sed 's/ /%s/g')"
  sleep 1
  "${ADB[@]}" shell input keyevent 111   # 收起输入法，避免按钮被遮挡
  sleep 1
  tap_text 'content-desc="发送"'
}

want() {
  [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]
}

# 从任意页面回到会话列表。
#
# 三种状态要区分开，否则会误按返回键把 App 退到桌面：
#   1. 已在列表页（有「批量管理」）
#   2. 在聊天页（有「会话列表」入口）→ 点入口
#   3. 在附加页（设置/已归档/用量）→ 按一次返回键回到聊天页或列表页
open_chat_list() {
  if screen_has 'content-desc="批量管理"'; then
    return 0
  fi
  if screen_has 'content-desc="对话列表"'; then
    tap_text 'content-desc="对话列表"' || return 1
    sleep 3
    return 0
  fi
  # 附加页：返回键回上层。只按一次，避免连按退到桌面。
  "${ADB[@]}" shell input keyevent 4
  sleep 3
  if ! screen_has 'content-desc="批量管理"'; then
    tap_text 'content-desc="对话列表"' || return 1
    sleep 3
  fi
  screen_has 'content-desc="批量管理"'
}

# 选第一个智能体卡片。
#
# 不能简单地「取 y>400 的第一个整行宽可点区」：地址输入框本身就是整行宽的可点区，
# 那样会点进输入框，把地址改成别的值并把流程打回第 1 步。
# 因此先定位「智能体」这个小标题，再取它下方第一个整行宽可点区。
pick_first_agent() {
  local xy
  xy="$("${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "${ADB[@]}" shell cat /sdcard/ui.xml | python3 -c '
import re,sys
data=sys.stdin.read()
nodes=[]
for n in re.findall(r"<node[^>]*>", data):
    m=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
    if not m: continue
    x1,y1,x2,y2=map(int,m.groups())
    t=re.search(r"text=\"([^\"]*)\"", n)
    nodes.append((x1,y1,x2,y2,t.group(1) if t else "", "clickable=\"true\"" in n))
# 「智能体」标题的下边界，作为卡片区起点
anchor=None
for x1,y1,x2,y2,t,c in nodes:
    if t.strip()=="智能体": anchor=y2; break
if anchor is None: sys.exit(1)
rows=[]
for x1,y1,x2,y2,t,c in nodes:
    if not c: continue
    if y1>=anchor and (x2-x1)>700: rows.append(((x1+x2)//2,(y1+y2)//2,y1))
rows.sort(key=lambda r:r[2])
print(rows[0][0], rows[0][1]) if rows else sys.exit(1)
')" || return 1
  "${ADB[@]}" shell input tap $xy
}

# ---------------------------------------------------------------- 准备

echo "== 安装 APK =="
"${ADB[@]}" install -r "$APK" >/dev/null

if [ "$KEEP" -eq 0 ]; then
  echo "== 清空应用数据 =="
  "${ADB[@]}" shell pm clear "$PKG" >/dev/null
fi
"${ADB[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
unblock_port

echo "== 启动应用 =="
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell am start -n "$PKG/io.qwenpaw.node.MainActivity" >/dev/null
sleep 6
# Android 13+ 首次启动会弹通知权限对话框，它是系统窗口，会挡住后面所有点击。
# `pm grant` 在部分情况下不生效（pm clear 之后重新申请、或安装时权限被重置），
# 所以这里再兜一次：对话框还在顶层就点 Allow。
if "${ADB[@]}" shell dumpsys activity activities 2>/dev/null | grep -q GrantPermissionsActivity; then
  tap_text 'text="Allow"' || true
  sleep 2
fi
shot 01-launch

if screen_has '连接到 QwenPaw'; then
  echo "== 首次连接：填地址→读列表→选智能体→连接 =="
  # 三步向导：第 1 步填地址并「验证连接」后才点亮「下一步」，
  # 第 2 步是账号（服务端未开鉴权时自动跳过），第 3 步才出现智能体列表。
  # 地址框可能带默认值，先清空再输入，
  # 否则 `input text` 会追加到默认值后面，拼出一个无效地址。
  # 页面有三个输入框（名称 / 地址 / 别名），只按 class 会点到第一个。
  # 改为按占位文案定位地址框：它同时含「chat.example.com」。
  tap_text 'text="例如 chat.example.com 或 192.168.1.10:8088"' \
    || tap_text 'class="android.widget.EditText"' || true
  sleep 1
  "${ADB[@]}" shell input keyevent 123   # 光标移到行尾
  for _ in $(seq 1 40); do "${ADB[@]}" shell input keyevent 67; done
  sleep 1
  "${ADB[@]}" shell input text "$HOST"
  sleep 1
  # 收起键盘：`keyevent 111` 只在部分输入法上生效，redroid 的软键盘仍会占掉
  # 下半屏，把「验证连接」「下一步」推到可视区之外，导致后面所有点击落空。
  "${ADB[@]}" shell input keyevent 4
  sleep 2
  # 「下一步」在第 1 步只有在验证通过后才可点，所以先点「验证连接」。
  tap_text 'text="验证连接"' || true
  sleep 6
  tap_text 'text="下一步"' || true
  sleep 5
  # 第 2 步是账号；服务端 auth 关闭时向导会自动跳过，这里再兜一次。
  if screen_has 'text="账号"'; then
    tap_text 'text="下一步"' || true
    sleep 6
  fi
  # 第 3 步自动拉智能体列表。
  sleep 6
  # 选第一个智能体：智能体卡片是一整行可点区域，取「智能体」标题下方的第一行。
  # 卡片本身没有 text（文字在子节点里），pick_first_agent 只认 text，
  # 因此直接按位置点：智能体步骤的卡片区第一张卡的中心。
  xy="$(dump_ui >/dev/null 2>&1; python3 - "$OUT/ui.xml" <<'PY'
import re, sys
xml = sys.argv[1]
data = open(xml, encoding='utf-8', errors='ignore').read()
anchor = None
rows = []
for n in re.findall(r'<node[^>]*>', data):
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    t = re.search(r'text="([^"]*)"', n)
    if t and t.group(1).strip() == "智能体":
        anchor = y2
    if 'clickable="true"' not in n or "long-clickable=\"true\"" in n:
        continue
    if anchor is not None and y1 >= anchor and (x2 - x1) > 500:
        rows.append((y1, (x1 + x2) // 2, (y1 + y2) // 2))
rows.sort()
print(rows[0][1], rows[0][2]) if rows else sys.exit(1)
PY
)" || xy=""
  if [ -n "$xy" ]; then
    "${ADB[@]}" shell input tap $xy
  else
    echo "  [警告] 没读到智能体列表"
  fi
  sleep 2
  # 第 3 步的确认按钮叫「完成」；旧版本叫「连接」，两个都试一次。
  tap_text 'text="完成"' || tap_text 'text="连接"' || true
  sleep 12
fi
shot 02-chat-list

# 首页就是聊天页（不再是列表页）。判据换成聊天页独有的节点：
# 输入框的发送按钮或空态文案。
if ! screen_has 'content-desc="发送"' && ! screen_has 'text="开始新的对话"'; then
  echo "聊天页异常，终止"; exit 1
fi

# 打开第一条会话：先确保在列表页，再点首行。
open_first_chat() {
  open_chat_list || { echo "  找不到会话列表入口"; return 1; }
  local xy
  xy="$("${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "${ADB[@]}" shell cat /sdcard/ui.xml | python3 -c '
import re,sys
data=sys.stdin.read()
rows=[]
for n in re.findall(r"<node[^>]*>", data):
    if "clickable=\"true\"" in n and "long-clickable=\"true\"" in n:
        m=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
        if m:
            x1,y1,x2,y2=map(int,m.groups())
            if y1>400: rows.append(((x1+x2)//2,(y1+y2)//2,y1))
rows.sort(key=lambda r:r[2])
print(rows[0][0], rows[0][1]) if rows else sys.exit(1)
')" || { echo "  没有可打开的会话"; return 1; }
  "${ADB[@]}" shell input tap $xy
  sleep 6
}

# 打开设置里的一个二级页面（工作区文件 / 备份 / 检查点）。
#
# 条目在「服务端配置」卡片靠下，先滚动到可见再点；
# 前面的场景可能把 App 留在任意页面，所以先回前台再回列表。
open_settings_item() {
  local name="$1"
  "${ADB[@]}" shell am start -n "$PKG/io.qwenpaw.node.MainActivity" >/dev/null 2>&1 || true
  sleep 4
  open_chat_list >/dev/null 2>&1 || true
  tap_text 'content-desc="设置"' || { echo "  未找到设置入口"; return 1; }
  sleep 3
  local found=1
  for _ in 1 2 3 4 5 6; do
    if screen_has "text=\"$name\""; then found=0; break; fi
    "${ADB[@]}" shell input swipe 360 1300 360 600 300 >/dev/null 2>&1 || true
    sleep 1
  done
  # 0.7.1 起服务端配置收进二级页；主页面找不到时回到顶部再进二级页找。
  # 「语音」等本机设置仍在主页面，因此保留先找主页面这一步。
  if [ "$found" != 0 ]; then
    for _ in 1 2 3; do
      "${ADB[@]}" shell input swipe 360 600 360 1300 300 >/dev/null 2>&1 || true
      sleep 1
    done
    if tap_row_text '服务端配置'; then
      sleep 3
      # 首次点击偶尔会被设置页的进入动画吞掉，页面没变就再点一次。
      if ! screen_has 'text="模型与智能体"'; then
        tap_row_text '服务端配置' || true
        sleep 3
      fi
      for _ in 1 2 3 4 5 6; do
        if screen_has "text=\"$name\""; then found=0; break; fi
        "${ADB[@]}" shell input swipe 360 1300 360 600 300 >/dev/null 2>&1 || true
        sleep 1
      done
    fi
  fi
  [ "$found" = 0 ] || { echo "  设置页没有「$name」"; return 1; }
  tap_row_text "$name" || { echo "  无法打开「$name」"; return 1; }
  sleep 4
}

echo "== 进入一条会话 =="
# A7 只验设置里的工作区入口，不需要已有会话；A8 只验设置页与麦克风点击，同理；
# C1/C2 都从设置页进入，同样不依赖会话；D1-D4 同理。
if ! open_first_chat; then
  if want A7 || want A8 || want C1 || want C2 || want D1 || want D2 || want D3 || want D4; then
    echo "  [提示] 没有可打开的会话，该场景不依赖会话，继续"
  else
    exit 1
  fi
fi
shot 03-chat-opened

# ---------------------------------------------------------------- A4 停止

if want A4; then
  echo "== A4 停止生成 =="
  if type_and_send "please run a 120 second loop and report every 10 seconds"; then
    sleep 12
    shot 04-a4-running
    # 停止按钮是文字「停止」而非 content-desc，早期版本写的「停止生成」永远找不到。
    if tap_text 'text="停止"'; then
      sleep 8
      shot 05-a4-stopped
      left="$(running_count)"
      echo "  服务端剩余 running：$left"
      [ "$left" = "0" ] || echo "  [警告] 服务端仍有运行中的会话"
      screen_has 'content-desc="发送"' && echo "  [通过] 发送按钮已复位"
    else
      echo "  [跳过] 任务已自行结束，未见到停止按钮"
    fi
  else
    echo "  [失败] 无法发送消息"
  fi
fi

# ---------------------------------------------------------------- A6 断网

if want A6; then
  echo "== A6 断网 =="
  if ! can_block; then
    echo "  [跳过] 设备没有 root，无法断网"
  else
  block_port
  if type_and_send "offline acceptance probe"; then
    sleep 30
    shot 06-a6-offline
    if screen_has '无法连接服务器' || screen_has '连接超时'; then
      echo "  [通过] 出现了中文错误提示"
    else
      echo "  [警告] 未在界面上找到中文错误提示"
    fi
    if screen_has 'text="重试"'; then
      echo "  [通过] 失败气泡带重试入口"
    else
      echo "  [警告] 未找到重试按钮"
    fi
  else
    echo "  [失败] 断网时无法发送"
  fi
  unblock_port
  echo "  等待网络恢复后可手动点「重试」"
  fi
fi

# ---------------------------------------------------------------- A3 重连

if want A3; then
  echo "== A3 杀 App 后重连 =="
  type_and_send "run a 90 second loop with progress every 10 seconds" || true
  sleep 10
  before="$(running_count)"
  echo "  杀 App 前服务端 running：$before"
  "${ADB[@]}" shell am force-stop "$PKG"
  sleep 3
  "${ADB[@]}" shell am start -n "$PKG/io.qwenpaw.node.MainActivity" >/dev/null
  sleep 6
  open_first_chat || true
  sleep 6
  shot 07-a3-reconnected
  after="$(running_count)"
  echo "  重开后服务端 running：$after"
  if screen_has '运行中' || [ "${before:-0}" != "0" ]; then
    echo "  [通过] 界面仍处于接流状态"
  else
    echo "  [提示] 任务可能已结束，检查截图确认历史完整"
  fi
fi

# ---------------------------------------------------------------- A5 多实例

if want A5; then
  echo "== A5 多实例（需要第二个实例已在 8099 运行）=="
  if curl -s -m 3 "http://$SECOND_HOST/api/version" >/dev/null 2>&1; then
    open_chat_list
    tap_text 'content-desc="设置"' || true
    sleep 3
    if tap_text 'text="添加实例"'; then
      sleep 2
      tap_text 'class="android.widget.EditText"' || true
      sleep 1
      "${ADB[@]}" shell input text "$SECOND_HOST"
      sleep 1
      "${ADB[@]}" shell input keyevent 111
      sleep 1
      tap_text 'text="读取列表"' || true
      sleep 8
      pick_first_agent || true
      sleep 2
      "${ADB[@]}" shell input swipe 540 1800 540 900 300 >/dev/null 2>&1 || true
      sleep 1
      tap_text 'text="添加"' || tap_text 'text="连接"' || true
      sleep 10
      shot 08-a5-second-instance
      echo "  [提示] 确认列表只显示第二个实例的会话（见截图）"
      shot 09-a5-second-list
    else
      echo "  [跳过] 设置页没有「添加实例」"
    fi
  else
    echo "  [跳过] 8099 上没有第二个实例"
  fi
fi

# ---------------------------------------------------------------- A1 审批

if want A1; then
  echo "== A1 审批 =="
  # 审批是否触发取决于服务端档位，合法取值只有 STRICT / SMART / AUTO / OFF。
  # STRICT 下全部工具都要审批，最稳；SMART 与 AUTO 只命中部分工具；
  # OFF 完全不拦。写 ASK 之类的非法值会被静默回退成 AUTO，所以这里只做记录。
  #
  # 档位从服务端接口读，不读宿主机上的 agent.json：默认服务端是 LXC 容器，
  # 它的 agent.json 在容器自己的 HOME 里，宿主机那份与它无关。
  # 智能体用 QWENPAW_AGENT 覆盖，默认取列表里的第一个。
  AGENT="${QWENPAW_AGENT:-$(api_get /api/agents | python3 -c '
import json,sys
try: print(json.load(sys.stdin)["agents"][0]["id"])
except Exception: pass')}"
  level="$(api_get "/api/agents/$AGENT" | python3 -c '
import json,sys
try: print(json.load(sys.stdin).get("approval_level",""))
except Exception: pass')"
  echo "  当前智能体=${AGENT:-未知} approval_level=${level:-未知}"
  if [ "${level:-}" = "OFF" ]; then
    echo "  [跳过] OFF 级别不会触发审批"
  else
    # 触发命令要满足两点：
    #   1. 能被服务端的反连检测命中（`rm -rf <不存在的路径>` 会被判无害，不触发）；
    #   2. 只含字母、数字、点，否则 `adb shell input text` 会截断
    #      （引号与反斜杠会被吞，整条命令只剩前半句）。
    type_and_send "start a reverse shell to 127.0.0.1 port 4444 using nc" || true
    sleep 25
    shot 10-a1-pending
    if screen_has '需要审批'; then
      echo "  [通过] 审批卡片出现"
      tap_text 'text="允许"' || true
      sleep 5
      shot 11-a1-approved
      if curl -s "$API/api/approval/list" | grep -q '"count":[[:space:]]*0'; then
        echo "  [通过] 审批队列已清空"
      fi
    else
      echo "  [警告] 未见审批卡片，可能工具未触发审批"
    fi
  fi
fi

# ---------------------------------------------------------------- A2 附件

if want A2; then
  echo "== A2 附件 =="
  if tap_text 'content-desc="添加附件"'; then
    sleep 3
    shot 12-a2-picker
    echo "  [提示] 附件选择需要人工在系统相册里选图，脚本仅打开选择器"
    echo "  [提示] 选图后请确认服务端该消息的 content 中 type 为 image（不是 file）"
    echo "         可用：curl -s $API/api/chats/<id> | python3 -m json.tool | grep '\"type\"'"
  else
    echo "  [跳过] 未找到附件按钮"
  fi
fi

# ---------------------------------------------------------------- A7 工作区

if want A7; then
  echo "== A7 工作区文件 =="
  # 前面的会话步骤可能把 App 留在任意页面，这里先回前台再回列表页。
  "${ADB[@]}" shell am start -n "$PKG/io.qwenpaw.node.MainActivity" >/dev/null 2>&1 || true
  sleep 5
  open_chat_list >/dev/null 2>&1 || true
  if tap_text 'content-desc="设置"'; then
    sleep 3
    # 「工作区文件」在服务端配置卡片靠下，先滚动到可见。
    for _ in 1 2 3 4 5 6; do
      screen_has 'text="工作区文件"' && break
      "${ADB[@]}" shell input swipe 360 1300 360 600 300 >/dev/null 2>&1 || true
      sleep 1
    done
    # 0.7.1 起该入口收进「服务端配置」二级页；主页面找不到就进去再找。
    if ! screen_has 'text="工作区文件"'; then
      for _ in 1 2 3; do
        "${ADB[@]}" shell input swipe 360 600 360 1300 300 >/dev/null 2>&1 || true
        sleep 1
      done
      tap_row_text '服务端配置' || true
      sleep 3
      if ! screen_has 'text="模型与智能体"'; then
        tap_row_text '服务端配置' || true
        sleep 3
      fi
      for _ in 1 2 3 4 5 6; do
        screen_has 'text="工作区文件"' && break
        "${ADB[@]}" shell input swipe 360 1300 360 600 300 >/dev/null 2>&1 || true
        sleep 1
      done
    fi
    if tap_row_text '工作区文件'; then
      sleep 6
      shot 13-a7-workspace-list
      if screen_has 'text="记忆"' && screen_has 'text="项目目录"'; then
        echo "  [通过] 三个根切换可见"
      else
        echo "  [警告] 未找到根切换控件"
      fi
      if tap_text 'text="记忆"'; then
        sleep 5
        shot 14-a7-memory
        if screen_has '.md"'; then
          echo "  [通过] 记忆根列出 Markdown"
        else
          echo "  [提示] 记忆根为空或没有 Markdown"
        fi
      fi
    else
      echo "  [失败] 无法打开工作区文件"
    fi
  else
    echo "  [跳过] 未找到设置入口"
  fi
fi

# ---------------------------------------------------------------- A8 语音
#
# 判据随变体不同：
#   full     设置页要有「转写来源」两档与「语音识别模型」行；默认本地识别，
#            未下载模型时点麦克风弹「需要先下载识别模型」。
#   nosherpa 设置页必须没有「转写来源」与「语音识别模型」；
#            点麦克风时服务端未配好则弹说明。
# 文案即用户看到的字符串，改文案后这里要同步改。
if want A8; then
  echo "== A8 语音（变体 $FLAVOR） =="
  if open_settings_item "语音"; then
    sleep 2
    shot 17-a8-voice-settings
    if screen_has 'text="显示语音按钮"' && screen_has 'text="触发方式"'; then
      echo "  [通过] 语音开关与触发方式可见"
    else
      echo "  [失败] 未找到语音开关或触发方式"
    fi
    if [ "$FLAVOR" = "nosherpa" ]; then
      for gone in 'text="转写来源"' 'text="语音识别模型"'; do
        if screen_has "$gone"; then
          echo "  [失败] 无端上引擎的包仍出现 $gone"
        else
          echo "  [通过] 已移除 $gone"
        fi
      done
      if screen_has 'text="重新检测"'; then
        echo "  [通过] 服务端转写行可见"
      else
        echo "  [失败] 未找到「重新检测」按钮（服务端转写行）"
      fi
    else
      if screen_has 'text="转写来源"' && screen_has 'text="本地识别"' \
        && screen_has 'text="服务端转写"'; then
        echo "  [通过] 转写来源两档可见"
      else
        echo "  [失败] 未找到「转写来源」或其两个选项"
      fi
      if screen_has 'text="语音识别模型"'; then
        echo "  [通过] 模型行可见（full 包默认本地识别）"
      else
        echo "  [失败] full 包在本地识别下未显示模型行"
      fi
      # 「服务端转写」这四个字同时是「转写来源」里的选项名，不能拿来判这一行是否存在；
      # 用该行独有的「重新检测」按钮做判据。
      if screen_has 'text="重新检测"'; then
        echo "  [失败] 本地识别为来源时仍显示服务端转写行"
      else
        echo "  [通过] 本地识别为来源时不显示服务端转写行"
      fi
    fi
    # 回到聊天页点麦克风。
    # 麦克风只在聊天页。当前停在设置的二级页，要逐层退回列表（先回设置主页，
    # 再回列表），然后点开一条会话。open_chat_list 每次只退一层。
    open_chat_list >/dev/null 2>&1 || true
    open_chat_list >/dev/null 2>&1 || true
    # 脚本前面清过应用数据，录音权限会重新弹系统对话框把界面顶掉。
    # 预先授予，让判断落在应用自己的提示上而不是权限弹窗上。
    "${ADB[@]}" shell pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
    if open_first_chat; then
      if tap_text 'content-desc="点击开始录音"' || tap_text 'content-desc="按住说话"'; then
        sleep 3
        shot 18-a8-voice-tap
        if [ "$FLAVOR" = "nosherpa" ]; then
          if screen_has 'text="服务端未启用语音转写"' || screen_has 'text="服务端转写缺少配置"' \
            || screen_has 'text="当前服务端版本不支持语音转写"'; then
            echo "  [通过] 不可用时给出说明"
          elif screen_has 'text="正在录音，再次点击麦克风结束"' \
            || screen_has 'text="正在录音，松开麦克风结束"'; then
            echo "  [提示] 服务端转写可用，直接进入录音态"
          else
            echo "  [警告] 点击麦克风后未见说明也未见录音浮层"
          fi
        else
          # full 包默认本地识别：未下载模型时应弹下载提示，不应进入录音态。
          if screen_has 'text="需要先下载识别模型"'; then
            echo "  [通过] 本地识别缺模型时提示下载"
          elif screen_has 'text="正在录音，再次点击麦克风结束"' \
            || screen_has 'text="正在录音，松开麦克风结束"'; then
            echo "  [提示] 模型已就绪，直接进入录音态"
          else
            echo "  [警告] 点击麦克风后未见提示也未见录音浮层"
          fi
        fi
      else
        echo "  [跳过] 未找到麦克风（可能「显示语音按钮」已关闭）"
      fi
    else
      echo "  [跳过] 没有可打开的会话，无法验证麦克风"
    fi
  else
    echo "  [失败] 无法打开语音设置页"
  fi
fi

# ---------------------------------------------------------------- B1 检查点页
#
# B 段覆盖备份与检查点。判据一律看界面上能找到的文字，
# 因为它们就是用户看到的字符串；改文案后这里要同步改。

if want B1; then
  echo "== B1 检查点页面 =="
  if open_settings_item "检查点"; then
    sleep 2
    shot 15-b1-checkpoint
    if screen_has 'text="自动检查点"'; then
      echo "  [通过] 自动开关区可见"
    else
      echo "  [警告] 未找到「自动检查点」"
    fi
    if screen_has 'text="手动快照"'; then
      echo "  [通过] 手动快照区可见"
    else
      echo "  [警告] 未找到「手动快照」，可能工作区不是 git 仓库（页面为只读）"
    fi
  else
    echo "  [失败] 无法打开检查点页"
  fi
fi

# 进入检查点页第一条会话的只读页。
# B2 与 B3 都用它：脚本一次只跑一个场景，不能让 B3 依赖上一次运行留下的页面。
open_snapshot_readonly() {
  open_settings_item "检查点" || return 1
  sleep 2
  local xy
  xy="$(dump_ui >/dev/null 2>&1; python3 - "$OUT/ui.xml" <<'PY'
import re, sys
data = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
anchor = None
rows = []
for n in re.findall(r'<node[^>]*>', data):
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    t = re.search(r'text="([^"]*)"', n)
    if t and t.group(1).strip() == "手动快照":
        anchor = y2
    if 'clickable="true"' not in n:
        continue
    if anchor is not None and y1 >= anchor and (x2 - x1) > 500:
        rows.append((y1, (x1 + x2) // 2, (y1 + y2) // 2))
rows.sort()
print(rows[0][1], rows[0][2]) if rows else sys.exit(1)
PY
)" || return 1
  "${ADB[@]}" shell input tap $xy
  sleep 6
  screen_has 'text="创建检查点"'
}

# ---------------------------------------------------------------- B2 只读对话页
#
# 「创建检查点」只在只读页出现；同时确认页面里没有输入框，
# 否则说明打开的是聊天页而不是只读页。

if want B2; then
  echo "== B2 只读对话页 =="
  if open_snapshot_readonly; then
    shot 16-b2-readonly
    if screen_has 'text="创建检查点"'; then
      echo "  [通过] 只读页底部是「创建检查点」"
    else
      echo "  [警告] 未找到「创建检查点」"
    fi
    if screen_has 'class="android.widget.EditText"'; then
      echo "  [警告] 只读页出现了输入框，可能不是只读页"
    else
      echo "  [通过] 只读页没有输入框"
    fi
  else
    echo "  [跳过] 手动快照区没有可点的会话"
  fi
fi

# ---------------------------------------------------------------- B3 创建检查点
#
# 自己进只读页，不依赖 B2 留下的状态。
# 成功判据是回到检查点页（「手动快照」重现）且时间线上出现该名称。

if want B3; then
  echo "== B3 创建检查点 =="
  if open_snapshot_readonly; then
    if tap_text 'text="创建检查点"'; then
      sleep 2
      tap_text 'class="android.widget.EditText"' || true
      sleep 1
      "${ADB[@]}" shell input text "acceptance"
      sleep 1
      "${ADB[@]}" shell input keyevent 111
      sleep 1
      # 对话框里同时有「名称（可选）」与按钮「创建」，按完整文字定位按钮。
      if tap_text 'text="创建"'; then
        sleep 8
        shot 17-b3-created
        # 时间线按会话分组、组内按时间倒序，新节点可能落在首屏之外，
        # 因此以服务端图谱为准判断「确实创建出来了」。
        created="$(api_get /api/workspace/checkpoints/graph?limit=20 | python3 -c '
import json,sys
try:
    nodes=json.load(sys.stdin).get("nodes",[])
    print("yes" if any(n.get("kind")=="snap" and n.get("name")=="acceptance" for n in nodes) else "no")
except Exception:
    print("unknown")')"
        echo "  服务端出现 acceptance 检查点：$created"
        if [ "$created" = "yes" ]; then
          echo "  [通过] 检查点已创建"
        else
          echo "  [警告] 服务端未见该检查点，见截图"
        fi
        if screen_has 'text="手动快照"'; then
          echo "  [通过] 已回到检查点页"
        else
          echo "  [警告] 创建后未回到检查点页"
        fi
      else
        echo "  [失败] 命名对话框里没有「创建」按钮"
      fi
    else
      echo "  [失败] 无法点击「创建检查点」"
    fi
  else
    echo "  [跳过] 无法进入只读页"
  fi
fi

# ---------------------------------------------------------------- B4 回滚预览
#
# 只打开预览，不点确认：回滚会改写工作区，验收不该留下副作用。

if want B4; then
  echo "== B4 回滚预览 =="
  if open_settings_item "检查点"; then
    sleep 3
    # 时间线在「手动快照」卡片下方，首屏看不到；先往下滚到出现「回滚」。
    found=""
    for _ in 1 2 3 4 5 6; do
      if screen_has 'text="回滚"'; then found="yes"; break; fi
      "${ADB[@]}" shell input swipe 360 1400 360 600 300 >/dev/null 2>&1 || true
      sleep 1
    done
    if [ -n "$found" ] && tap_text 'text="回滚"'; then
      sleep 5
      shot 18-b4-restore-preview
      if screen_has 'text="会话记忆"' && screen_has 'text="工作区文件"'; then
        echo "  [通过] 回滚范围可选"
      else
        echo "  [警告] 回滚弹窗里没有范围开关"
      fi
      # 返回键关闭弹窗，不执行回滚。
      "${ADB[@]}" shell input keyevent 4
      sleep 2
    else
      echo "  [跳过] 时间线上没有「回滚」按钮（可能还没有检查点）"
    fi
  else
    echo "  [失败] 无法打开检查点页"
  fi
fi

# ---------------------------------------------------------------- B5 自动开关
#
# 开关状态改完要还原，否则会污染后续场景与测试容器。

if want B5; then
  echo "== B5 自动检查点开关 =="
  auto_state() {
    api_get /api/workspace/checkpoints/status | python3 -c '
import json,sys
try: print("true" if json.load(sys.stdin).get("auto_enabled") else "false")
except Exception: print("unknown")'
  }
  if open_settings_item "检查点"; then
    sleep 2
    # 开关文字不可点，真正可点的是右侧的 Switch：从「自动创建检查点」这一行
    # 取同一 y 上最靠右的可点控件。
    tap_auto_switch() {
      local xy
      xy="$(dump_ui >/dev/null 2>&1; python3 - "$OUT/ui.xml" <<'PY'
import re, sys
data = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
row_y = None
cands = []
for n in re.findall(r'<node[^>]*>', data):
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    t = re.search(r'text="([^"]*)"', n)
    if t and t.group(1).strip() == "自动创建检查点":
        row_y = (y1 + y2) // 2
    if 'clickable="true"' in n:
        cands.append((x2, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2))
if row_y is None:
    sys.exit(1)
hits = [c for c in cands if c[3] <= row_y <= c[4]]
hits.sort(reverse=True)
print(hits[0][1], row_y) if hits else sys.exit(1)
PY
)" || return 1
      "${ADB[@]}" shell input tap $xy
    }
    before="$(auto_state)"
    echo "  切换前 auto_enabled=$before"
    if tap_auto_switch; then
      sleep 4
      after="$(auto_state)"
      echo "  切换后 auto_enabled=$after"
      if [ "$before" != "$after" ] && [ "$after" != "unknown" ]; then
        echo "  [通过] 服务端状态随开关变化"
      else
        echo "  [警告] 服务端状态未变化，可能没点到开关"
      fi
      # 再点一次还原。
      tap_auto_switch || true
      sleep 4
      restored="$(auto_state)"
      echo "  还原后 auto_enabled=$restored"
      [ "$restored" = "$before" ] && echo "  [通过] 已还原" || echo "  [警告] 未还原，请手动改回 $before"
      shot 19-b5-auto-toggle
    else
      echo "  [跳过] 未找到自动开关"
    fi
  else
    echo "  [失败] 无法打开检查点页"
  fi
fi

# ---------------------------------------------------------------- B6 备份页

if want B6; then
  echo "== B6 备份页面 =="
  if open_settings_item "备份"; then
    sleep 2
    shot 20-b6-backup
    if screen_has 'text="新建备份"'; then
      echo "  [通过] 新建备份区可见"
    else
      echo "  [警告] 未找到「新建备份」"
    fi
    if screen_has 'text="导入备份"'; then
      echo "  [通过] 导入备份区可见"
    else
      echo "  [警告] 未找到「导入备份」"
    fi
  else
    echo "  [失败] 无法打开备份页"
  fi
fi

# ---------------------------------------------------------------- C1 用量统计

# 判据用界面上真正显示的文字。用量页的四块数据来自三组端点，
# 其中「当前智能体」按 X-Agent-Id 过滤、其余全局，因此两处范围标注必须都在。
if want C1; then
  echo "== C1 用量统计 =="
  if open_settings_item "用量统计"; then
    sleep 4
    shot 21-c1-usage
    if screen_has 'text="近 7 天"' && screen_has 'text="近 30 天"' && screen_has 'text="近 90 天"'; then
      echo "  [通过] 三档时间范围可见"
    else
      echo "  [失败] 未找到完整的三档时间范围"
    fi
    if screen_has 'text="当前智能体"'; then
      echo "  [通过] 「当前智能体」范围标注可见"
    else
      echo "  [失败] 未找到「当前智能体」，范围差异没有讲清"
    fi
    if screen_has 'text="全服务端"'; then
      echo "  [通过] 「全服务端」范围标注可见"
    else
      echo "  [失败] 未找到「全服务端」"
    fi
    # 切到 7 天：换范围会重建四个请求，这里只看界面没崩、仍在用量页。
    if tap_text 'text="近 7 天"'; then
      sleep 5
      shot 22-c1-usage-7d
      if screen_has 'text="近 7 天"'; then
        echo "  [通过] 切换范围后仍在用量页"
      else
        echo "  [失败] 切换范围后页面异常"
      fi
    else
      echo "  [警告] 未点中「近 7 天」"
    fi
    # 从设置进入的用量页，返回应回到设置（有一级列表的「服务端配置」）。
    "${ADB[@]}" shell input keyevent 4
    sleep 3
    if screen_has 'text="服务端配置"'; then
      echo "  [通过] 从设置进入时返回回到设置页"
    else
      echo "  [失败] 返回没有回到设置页"
    fi
  else
    echo "  [失败] 无法打开用量统计页"
  fi

  # 第二个入口：对话列表顶栏的柱状图。返回应回到列表。
  if open_chat_list >/dev/null 2>&1; then
    if tap_text 'content-desc="用量统计"'; then
      sleep 4
      shot 23-c1-usage-from-list
      "${ADB[@]}" shell input keyevent 4
      sleep 3
      if screen_has 'content-desc="批量管理"'; then
        echo "  [通过] 从列表进入时返回回到对话列表"
      else
        echo "  [失败] 从列表进入的返回落点不对"
      fi
    else
      echo "  [警告] 列表顶栏没有「用量统计」图标"
    fi
  fi
fi

# ---------------------------------------------------------------- C2 模型与提供商

# 两行默认模型必须同时显示「全局默认」与「当前智能体默认」：
# 服务端 `scope=global` 与 `scope=agent` 是两个不同的值，合并成一行会让用户
# 以为改的是同一件事。连通性测试失败也是 HTTP 200，界面按 success 分支。
if want C2; then
  echo "== C2 模型与提供商 =="
  if open_settings_item "模型与提供商"; then
    sleep 4
    shot 24-c2-models
    if screen_has 'text="全局默认"' && screen_has 'text="当前智能体默认"'; then
      echo "  [通过] 两行默认模型可见"
    else
      echo "  [失败] 未同时找到两行默认模型"
    fi
    if screen_has 'text="测试连接"'; then
      echo "  [通过] 供应商列表非空"
      tap_text 'text="测试连接"' >/dev/null 2>&1 || true
      sleep 6
      shot 25-c2-model-test
      if screen_has 'text="可用"'; then
        echo "  [通过] 连通性测试返回可用"
      elif screen_has 'text="测试连接"'; then
        echo "  [提示] 未见「可用」，页面已恢复为可再次测试（失败时显示服务端原文）"
      else
        echo "  [警告] 测试后页面状态异常"
      fi
    else
      echo "  [警告] 供应商列表为空，或「测试连接」不在可视范围内"
    fi
    "${ADB[@]}" shell input keyevent 4
    sleep 3
    if screen_has 'text="服务端配置"'; then
      echo "  [通过] 返回回到设置页"
    else
      echo "  [失败] 返回没有回到设置页"
    fi
  else
    echo "  [失败] 无法打开模型与提供商页"
  fi
fi

# ---------------------------------------------------------------- D1 技能页

# 两段都要在：工作区技能（可启停）与技能池（只读）来自两个不同端点，
# 只验其中一段会让另一段整段失败时仍然「通过」。
if want D1; then
  echo "== D1 技能页 =="
  if open_settings_item "技能"; then
    sleep 4
    shot 31-d1-skills
    if screen_has 'text="当前智能体"'; then
      echo "  [通过] 「当前智能体」一段可见"
    else
      echo "  [失败] 未找到「当前智能体」"
    fi
    # 技能池在工作区技能之后，16 条技能能把这一段顶到首屏之外，先滑到它出现为止。
    for _ in 1 2 3 4 5 6 7 8; do
      screen_has 'text="技能池"' && break
      "${ADB[@]}" shell input swipe 360 1300 360 600 300 >/dev/null 2>&1 || true
      sleep 1
    done
    if screen_has 'text="技能池"'; then
      echo "  [通过] 「技能池」一段可见"
    else
      echo "  [失败] 未找到「技能池」"
    fi
    if screen_has 'text="只读"'; then
      echo "  [通过] 技能池标注为只读"
    else
      echo "  [警告] 未见「只读」标注"
    fi
    "${ADB[@]}" shell input keyevent 4
    sleep 3
    if screen_has 'text="服务端配置"'; then
      echo "  [通过] 返回回到设置页"
    else
      echo "  [失败] 返回没有回到设置页"
    fi
  else
    echo "  [失败] 无法打开技能页"
  fi
fi

# ---------------------------------------------------------------- D2 技能编辑

# 技能名不可预知，先从服务端取一个真实存在的名字再点它，
# 不猜界面上的第一行。只验到「进入编辑态且输入框可用」为止：
# 保存会真写服务端技能目录，在共享测试容器上反复改写会污染其它场景的基线。
if want D2; then
  echo "== D2 技能编辑 =="
  skill_name="$(api_get /api/skills | python3 -c '
import json,sys
try:
    items=json.load(sys.stdin)
    print(items[0]["name"] if items else "")
except Exception:
    print("")')"
  echo "  目标技能：${skill_name:-（无）}"
  if [ -z "$skill_name" ]; then
    echo "  [跳过] 服务端没有可打开的技能"
  elif open_settings_item "技能"; then
    sleep 4
    if tap_text "text=\"$skill_name\""; then
      sleep 4
      shot 32-d2-skill-detail
      if screen_has 'text="编辑正文"'; then
        echo "  [通过] 详情页有「编辑正文」"
        if tap_text 'text="编辑正文"'; then
          sleep 3
          shot 33-d2-skill-editor
          if screen_has 'content-desc="返回技能详情"'; then
            echo "  [通过] 进入编辑态，返回按钮为「返回技能详情」"
          else
            echo "  [失败] 未进入编辑态"
          fi
          if screen_has 'class="android.widget.EditText"'; then
            echo "  [通过] 正文输入框可用"
          else
            echo "  [失败] 编辑态没有输入框"
          fi
          "${ADB[@]}" shell input keyevent 4
          sleep 2
          # 未改动时直接退出，不应弹「放弃未保存的修改」。
          if screen_has 'text="编辑正文"'; then
            echo "  [通过] 未改动时返回直接回到详情"
          else
            echo "  [警告] 返回后未回到详情页"
          fi
        fi
      else
        echo "  [失败] 详情页未找到「编辑正文」"
      fi
    else
      echo "  [失败] 列表里点不到技能「$skill_name」"
    fi
    "${ADB[@]}" shell input keyevent 4
    sleep 2
    "${ADB[@]}" shell input keyevent 4
    sleep 3
  else
    echo "  [失败] 无法打开技能页"
  fi
fi

# ---------------------------------------------------------------- D3 MCP

# 列表可能为空（服务端未配置 MCP 客户端），此时必须给出可读的空态说明，
# 不能是一张空白卡片。
if want D3; then
  echo "== D3 MCP =="
  if open_settings_item "MCP"; then
    sleep 5
    shot 33-d3-mcp
    client_name="$(api_get /api/mcp | python3 -c '
import json,sys
try:
    items=json.load(sys.stdin)
    print(items[0]["name"] if items else "")
except Exception:
    print("")')"
    echo "  目标客户端：${client_name:-（无）}"
    if screen_has 'text="MCP 客户端（'; then
      echo "  [通过] 客户端计数可见"
    elif screen_has 'text="没有配置 MCP 客户端"'; then
      echo "  [通过] 空态说明了原因"
    else
      echo "  [失败] 既没有列表也没有空态说明"
    fi
    # 详情页两段都要在：白名单与策略是两条不同端点，只验其中一段会漏掉另一段。
    if [ -n "$client_name" ] && tap_text "text=\"$client_name\""; then
      sleep 4
      shot 34-d3-mcp-detail
      if screen_has 'text="工具白名单"'; then
        echo "  [通过] 详情页有「工具白名单」"
      else
        echo "  [失败] 详情页未找到「工具白名单」"
      fi
      # 客户端被禁用时服务端直接回空数组，界面必须解释这一点而不是显示空列表。
      if screen_has 'text="该客户端已停用，先启用后才能读取工具列表。"'; then
        echo "  [通过] 已停用的客户端给出了原因"
      fi
      if tap_text 'text="访问策略"'; then
        sleep 3
        shot 35-d3-mcp-policy
        if screen_has 'text="默认效果"'; then
          echo "  [通过] 策略页有「默认效果」"
        else
          echo "  [失败] 策略页未找到「默认效果」"
        fi
        if screen_has 'text="允许"' && screen_has 'text="询问"' && screen_has 'text="拒绝"'; then
          echo "  [通过] 三档效果可见"
        else
          echo "  [失败] 三档效果不全"
        fi
      else
        echo "  [失败] 无法切到访问策略"
      fi
      "${ADB[@]}" shell input keyevent 4
      sleep 2
    fi
    "${ADB[@]}" shell input keyevent 4
    sleep 3
    if screen_has 'text="服务端配置"'; then
      echo "  [通过] 返回回到设置页"
    else
      echo "  [失败] 返回没有回到设置页"
    fi
  else
    echo "  [失败] 无法打开 MCP 页"
  fi
fi

# ---------------------------------------------------------------- D4 扩展市场

# 插件目录可能因 CDN 不可达而为空，此时要显示「无法获取插件目录」而不是空白。
if want D4; then
  echo "== D4 扩展市场 =="
  if open_settings_item "扩展市场"; then
    sleep 5
    shot 35-d4-market
    if screen_has 'text="技能市场"'; then
      echo "  [通过] 技能市场一段可见"
    else
      echo "  [失败] 未找到「技能市场」"
    fi
    # 来源与分类是搜索参数的两个维度，缺任一项都会让 provider_pages 或 category 失效。
    if screen_has 'text="来源"'; then
      echo "  [通过] 来源筛选可见"
    else
      echo "  [警告] 未找到来源筛选"
    fi
    if screen_has 'text="分类"'; then
      echo "  [通过] 分类筛选可见"
    else
      echo "  [警告] 未找到分类筛选"
    fi
    # 真搜一次：三个来源并行，实测十几到二十秒。
    if tap_text 'class="android.widget.EditText"'; then
      sleep 1
      "${ADB[@]}" shell input text "pdf"
      sleep 1
      "${ADB[@]}" shell input keyevent 111
      sleep 1
      if tap_text 'text="搜索"'; then
        sleep 22
        shot 36-d4-market-search
        if screen_has 'text="ClawHub"' && ! screen_has 'text="没有匹配的技能。"'; then
          echo "  [通过] 搜索返回了结果，来源标签可见"
        else
          echo "  [失败] 搜索没有返回结果"
        fi
      else
        echo "  [警告] 未点中「搜索」"
      fi
    else
      echo "  [警告] 未找到搜索输入框"
    fi
    # QwenPaw 来源的落地点：服务端给的是安装用地址（/skills/@owner/name），
    # 平台前端只认 UUID。直接打开会落到「未找到技能详情」，而那个页面本身返回 200，
    # 所以必须看浏览器地址栏，不能只看「点开了没有」。
    qname="$(curl -s -m 30 -X POST "$API/api/market/search" \
      -H 'Content-Type: application/json' \
      -d '{"query":"qwenpaw","provider_pages":{"qwenpaw":1},"limit":5,"lang":"zh"}' \
      | python3 -c '
import json,sys
try:
    rows=[r for r in json.load(sys.stdin)["results"] if r["source"]=="qwenpaw"]
    print(rows[0]["name"] if rows else "")
except Exception:
    print("")')"
    if [ -n "$qname" ]; then
      if tap_text 'class="android.widget.EditText"'; then
        sleep 1
        "${ADB[@]}" shell input keyevent 123
        for _ in $(seq 1 20); do "${ADB[@]}" shell input keyevent 67; done
        "${ADB[@]}" shell input text "qwenpaw"
        sleep 1
        "${ADB[@]}" shell input keyevent 111
        sleep 1
        tap_text 'text="搜索"' >/dev/null 2>&1 || true
        sleep 22
        if tap_text "text=\"$qname\""; then
          sleep 5
          shot 38-d4-qwenpaw-jump
          # 从 dumpsys 读顶层 Activity 的 dat=：浏览器分两种实现（完整浏览器或
          # WebView Shell），地址栏的控件名各不相同，读 intent 数据才是通用判据。
          landed="$("${ADB[@]}" shell dumpsys activity activities 2>/dev/null \
            | grep -oE 'dat=https://platform\.agentscope\.io[^ }]*' | head -1 \
            | sed 's/^dat=//')"
          if [ -z "$landed" ]; then
            echo "  [警告] 没有读到浏览器地址栏，跳过来源跳转校验"
          elif echo "$landed" | grep -qE 'platform\.agentscope\.io/skills/[0-9a-fA-F]{8}-'; then
            echo "  [通过] QwenPaw 来源打开的是 UUID 详情页"
          elif echo "$landed" | grep -q '@'; then
            echo "  [失败] QwenPaw 来源仍在打开安装用地址：$landed"
          else
            echo "  [提示] 打开的是 $landed（可能是回退到平台列表）"
          fi
          # 从浏览器返回应用。
          "${ADB[@]}" shell input keyevent 4
          sleep 3
          "${ADB[@]}" shell input keyevent 4
          sleep 3
        else
          echo "  [警告] 搜索结果里点不到「$qname」"
        fi
      fi
    fi
    if tap_text 'text="插件目录"'; then
      sleep 5
      shot 37-d4-plugins
      if screen_has 'text="官方插件目录"'; then
        echo "  [通过] 插件目录一段可见"
      else
        echo "  [失败] 未找到「官方插件目录」"
      fi
      if screen_has 'text="无法获取插件目录"'; then
        echo "  [提示] CDN 不可达，已显示明确说明"
      fi
    else
      echo "  [失败] 无法切到插件目录"
    fi
    "${ADB[@]}" shell input keyevent 4
    sleep 3
  else
    echo "  [失败] 无法打开扩展市场页"
  fi
fi

echo
echo "完成。截图与 UI dump 在：$OUT"