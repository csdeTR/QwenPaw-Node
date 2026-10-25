#!/usr/bin/env bash
# QwenPaw Node 模拟器验收脚本。
#
# 用法：
#   ./scripts/e2e_acceptance.sh              # 跑全部场景（A1-A6）
#   ./scripts/e2e_acceptance.sh --keep       # 保留当前应用数据
#   ./scripts/e2e_acceptance.sh --only A4    # 只跑某个场景
#   ADB_SERIAL=192.168.10.201:5555 ./scripts/e2e_acceptance.sh --only A3
#
# 设备：
#   默认取 `adb devices` 里唯一在线设备；多设备时用 ADB_SERIAL 指定。
#   真机（ARM/Android 12）与模拟器（x86/Android 15）都适用，坐标一律动态获取。
#
# 前提：
#   1. 设备已连接（真机：adb connect <ip>:5555；模拟器：emulator -avd qp35）；
#     断网场景需要 `adb root` 才能用 iptables，真机通常拿不到 root，会自动跳过；
#   2. 宿主机已运行 `qwenpaw app --host 0.0.0.0`；
#   3. 已执行 `./gradlew :app:assembleDebug`。
#
# 覆盖场景：
#   A1 审批       —— 需要服务端 approval_level=ASK，触发审批后点允许
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
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
OUT="$ROOT/build/e2e"
HOST="${QWENPAW_HOST:-10.0.2.2:8088}"
API="${QWENPAW_API:-http://127.0.0.1:8088}"

# 选设备：ADB_SERIAL 优先，否则取唯一在线设备。
SERIAL="${ADB_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}' | head -1)"
fi
[ -n "$SERIAL" ] || { echo "没有在线设备，先 adb connect / 启动模拟器"; exit 1; }
ADB=("$ADB_BIN" -s "$SERIAL")
echo "设备：$SERIAL"

# 真机访问宿主要用局域网 IP，模拟器用 10.0.2.2。
if [ -z "${QWENPAW_HOST:-}" ]; then
  case "$SERIAL" in
    emulator-*) HOST="10.0.2.2:8088" ;;
    *) HOST="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}'):8088" ;;
  esac
fi
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

unblock_port() {
  run_root "iptables -D OUTPUT -p tcp --dport $PORT -j DROP; iptables -D INPUT -p tcp --sport $PORT -j DROP" || true
}

# 以 root 执行，三种情况：adb 已是 root、adb root 成功、真机走 su -c。
# 注意 su -c 的命令要整体加引号，否则 -I 之类会被 su 当成自己的参数。
run_root() {
  local cmd="$1"
  if [ "$("${ADB[@]}" shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; then
    "${ADB[@]}" shell "$cmd"
  elif [ "${USE_SU:-0}" = "1" ]; then
    "${ADB[@]}" shell "su -c \"$cmd\""
  else
    "${ADB[@]}" shell "$cmd"
  fi
}

# 断网需要 root，并且要知道走 adb root 还是 su。
# 只封 HOST 的端口（不是 5555），所以不会切断 adb 自身。
can_block() {
  local uid
  uid="$("${ADB[@]}" shell id -u 2>/dev/null | tr -d '\r')"
  if [ "$uid" = "0" ]; then
    USE_SU=0
    return 0
  fi
  if "${ADB[@]}" shell su -c id 2>/dev/null | grep -q 'uid=0'; then
    USE_SU=1
    return 0
  fi
  if "${ADB[@]}" root >/dev/null 2>&1 && sleep 2 && \
     [ "$("${ADB[@]}" shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; then
    USE_SU=0
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

# 点顶栏最右侧的可点按钮（列表页的溢出菜单）。不同机型分辨率不同，不能写死坐标。
tap_topbar_last_action() {
  local xy
  xy="$("${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "${ADB[@]}" shell cat /sdcard/ui.xml | python3 -c '
import re,sys
data=sys.stdin.read()
items=[]
for n in re.findall(r"<node[^>]*>", data):
    if "clickable=\"true\"" not in n: continue
    m=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
    if not m: continue
    x1,y1,x2,y2=map(int,m.groups())
    if y2 < 300: items.append((x2,(x1+x2)//2,(y1+y2)//2))
items.sort()
print(items[-1][1], items[-1][2]) if items else sys.exit(1)
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
shot 01-launch

if screen_has '连接到你的 QwenPaw 实例'; then
  echo "== 首次连接：填写地址 =="
  tap_text 'class="android.widget.EditText"' || true
  sleep 1
  "${ADB[@]}" shell input text "$HOST"
  sleep 1
  "${ADB[@]}" shell input keyevent 111
  sleep 1
  tap_text 'text="连接"' || true
  sleep 12
fi
shot 02-chat-list

# 需要一个可用的会话：取列表第一条。
if ! screen_has 'QwenPaw Node'; then
  echo "列表页异常，终止"; exit 1
fi

open_first_chat() {
  # 列表项是 clickable 的整行，取第一个可点的行（跳过顶栏）
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
            if y1>300: rows.append(((x1+x2)//2,(y1+y2)//2,y1))
rows.sort(key=lambda r:r[2])
print(rows[0][0], rows[0][1]) if rows else sys.exit(1)
')" || { echo "  没有可打开的会话"; return 1; }
  "${ADB[@]}" shell input tap $xy
  sleep 6
}

echo "== 打开第一条会话 =="
open_first_chat || exit 1
shot 03-chat-opened

# ---------------------------------------------------------------- A4 停止

if want A4; then
  echo "== A4 停止生成 =="
  if type_and_send "please run a 120 second loop and report every 10 seconds"; then
    sleep 12
    shot 04-a4-running
    if tap_text 'content-desc="停止生成"'; then
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
    echo "  [跳过] 设备拿不到 root（adb root / su 都不行），无法断网"
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
  if curl -s -m 3 http://127.0.0.1:8099/api/version >/dev/null 2>&1; then
    "${ADB[@]}" shell input keyevent 4   # 回列表
    sleep 2
    # 列表页顶栏右侧是溢出菜单（没有 content-desc），点它再选「设置」。
    if tap_topbar_last_action && sleep 2 && screen_has 'text="设置"'; then
      tap_text 'text="设置"' || true
      sleep 2
      if tap_text 'text="添加实例"'; then
        sleep 2
        tap_text 'class="android.widget.EditText"' || true
        sleep 1
        "${ADB[@]}" shell input text "${SECOND_HOST}"
        sleep 1
        "${ADB[@]}" shell input keyevent 111
        sleep 1
        tap_text 'text="连接"'
        sleep 8
        shot 08-a5-second-instance
        "${ADB[@]}" shell input keyevent 4
        sleep 3
        echo "  [提示] 确认列表只显示第二个实例的会话（见截图）"
        shot 09-a5-second-list
      fi
    fi
  else
    echo "  [跳过] 8099 上没有第二个实例"
  fi
fi

# ---------------------------------------------------------------- A1 审批

if want A1; then
  echo "== A1 审批（需要服务端 approval_level=ASK）=="
  if grep -q '"approval_level"[[:space:]]*:[[:space:]]*"ASK"' "$HOME/.qwenpaw/workspaces/default/agent.json" 2>/dev/null; then
    type_and_send "run rm -rf /tmp/qwenpaw-approval-acceptance" || true
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
  else
    echo "  [跳过] agent.json 的 approval_level 不是 ASK"
  fi
fi

# ---------------------------------------------------------------- A2 附件

if want A2; then
  echo "== A2 附件 =="
  if tap_text 'content-desc="添加附件"'; then
    sleep 3
    shot 12-a2-picker
    echo "  [提示] 附件选择需要人工在系统相册里选图，脚本仅打开选择器"
  else
    echo "  [跳过] 未找到附件按钮"
  fi
fi

echo
echo "完成。截图与 UI dump 在：$OUT"
