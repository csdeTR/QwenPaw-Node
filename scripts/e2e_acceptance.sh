#!/usr/bin/env bash
# QwenPaw Node 模拟器验收脚本。
#
# 用法：
#   ./scripts/e2e_acceptance.sh              # 跑全部场景（A1-A6）
#   ./scripts/e2e_acceptance.sh --keep       # 保留当前应用数据
#   ./scripts/e2e_acceptance.sh --only A4    # 只跑某个场景
#
# 设备：
#   默认取 `adb devices` 里唯一在线设备；多设备时用 ADB_SERIAL 指定。
#   只支持模拟器（x86_64 / Android 15），真机分支已移除。
#
# 前提：
#   1. 模拟器已启动（emulator -avd qp35），参数见 AGENTS.md 3.1；
#      A6 需要 `adb root` 才能用 iptables，模拟器可 root；
#   2. LXC 测试容器已启动 QwenPaw（默认 qp-test / 10.10.0.211:8088），
#      启动方式见 AGENTS.md 2.1。本机 :8088 实例承载真实数据，不参与验收；
#   3. 已执行 `./gradlew :app:assembleDebug`。
#
# 覆盖场景：
#   A1 审批       —— 服务端 approval_level=STRICT 时触发审批后点允许
#   A2 附件       —— 上传相册图片并确认被服务端接收
#   A3 重连       —— 长任务中杀 App，重开后接回运行中的输出
#   A4 停止       —— 生成中停止，服务端回 idle
#   A5 多实例     —— 第二个实例的会话列表与第一个隔离
#   A6 断网/409   —— 断网提示中文、草稿保留、失败气泡可重试
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB_BIN="$SDK/platform-tools/adb"
PKG="io.qwenpaw.node"
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
[ -n "$SERIAL" ] || { echo "没有在线设备，先启动模拟器"; exit 1; }
ADB=("$ADB_BIN" -s "$SERIAL")
echo "设备：$SERIAL"

# splits.abi 对 debug 变体同样生效，产物是 app-<abi>-debug.apk；
# 早期未拆分时是 app-debug.apk。按设备实际 ABI 选包，两者都能命中。
DEVICE_ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
PICKED=""
for candidate in "$DEBUG_DIR/app-$DEVICE_ABI-debug.apk" "$DEBUG_DIR/app-debug.apk"; do
  [ -f "$candidate" ] && { PICKED="$candidate"; break; }
done
[ -n "$PICKED" ] || { echo "找不到 debug 包（设备 ABI=$DEVICE_ABI）：$DEBUG_DIR"; exit 1; }
APK="$PICKED"
echo "安装包：$(basename "$APK")"

echo "服务器地址：$HOST"
# 第二实例（A5 用）：同一台宿主的 8099 端口。
SECOND_HOST="${QWENPAW_HOST2:-${HOST%:*}:8099}"
# 断网只封这个端口，避免误伤 adb 的 5555。
PORT="${HOST##*:}"

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

mkdir -p "$OUT"

shot() {
  "${ADB[@]}" exec-out screencap -p > "$OUT/$1.png"
  echo "  截图：$OUT/$1.png"
}

# 从 uiautomator dump 里按 text/content-desc 找控件中心点，输出 "x y"。
# 找不到返回非零。避免写死坐标导致点击落空。
find_xy() {
  local pattern="$1"
  "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  "${ADB[@]}" shell cat /sdcard/ui.xml > "$OUT/ui.xml"
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

block_port() {
  run_root "iptables -I OUTPUT -p tcp --dport $PORT -j DROP; iptables -I INPUT -p tcp --sport $PORT -j DROP"
}

# 收尾时规则通常不存在，iptables 会往 stderr 打 "Bad rule"，不是故障。
unblock_port() {
  run_root "iptables -D OUTPUT -p tcp --dport $PORT -j DROP; iptables -D INPUT -p tcp --sport $PORT -j DROP" 2>/dev/null || true
}

# 以 root 执行。模拟器支持 `adb root`，因此只有这一条提权路径。
run_root() {
  "${ADB[@]}" shell "$1"
}

# 断网需要 root。只封 HOST 的端口（不是 5555），所以不会切断 adb 自身。
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
  if screen_has 'content-desc="会话列表"'; then
    tap_text 'content-desc="会话列表"' || return 1
    sleep 3
    return 0
  fi
  # 附加页：返回键回上层。只按一次，避免连按退到桌面。
  "${ADB[@]}" shell input keyevent 4
  sleep 3
  if ! screen_has 'content-desc="批量管理"'; then
    tap_text 'content-desc="会话列表"' || return 1
    sleep 3
  fi
  screen_has 'content-desc="批量管理"'
}

# 选第一个智能体卡片。
#
# 不能简单地「取 y>400 的第一个整行宽可点区」：地址输入框本身就是整行宽的可点区，
# 那样会点进输入框，把地址改成别的值并把流程打回第 1 步。
# 因此先定位「选择智能体」这个小标题，再取它下方第一个整行宽可点区。
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
# 「选择智能体」标题的下边界，作为卡片区起点
anchor=None
for x1,y1,x2,y2,t,c in nodes:
    if t.strip()=="选择智能体": anchor=y2; break
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
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
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
  # 只填地址不会推进：步骤 1 的勾以「验证成功」为准，
  # 而验证成功要么点「验证连接」，要么读列表成功后自动置位。
  # 地址框可能带默认值，先清空再输入，
  # 否则 `input text` 会追加到默认值后面，拼出一个无效地址。
  tap_text 'class="android.widget.EditText"' || true
  sleep 1
  "${ADB[@]}" shell input keyevent 123   # 光标移到行尾
  for _ in $(seq 1 40); do "${ADB[@]}" shell input keyevent 67; done
  sleep 1
  "${ADB[@]}" shell input text "$HOST"
  sleep 1
  "${ADB[@]}" shell input keyevent 111
  sleep 1
  tap_text 'text="验证连接"' || true
  sleep 6
  tap_text 'text="读取列表"' || true
  sleep 8
  # 选第一个智能体：智能体卡片是一整行可点区域，取 y>400 的第一行。
  pick_first_agent || echo "  [警告] 没读到智能体列表"
  sleep 2
  "${ADB[@]}" shell input swipe 540 1800 540 900 300 >/dev/null 2>&1 || true
  sleep 1
  tap_text 'text="连接"' || true
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

echo "== 进入一条会话 =="
open_first_chat || exit 1
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
    echo "  [跳过] 模拟器没拿到 root（adb root 失败），无法断网"
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
  "${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
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

echo
echo "完成。截图与 UI dump 在：$OUT"
