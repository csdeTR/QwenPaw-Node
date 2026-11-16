#!/usr/bin/env bash
# 在 Qoder 沙箱里直接调用 LXD 的真实客户端。
#
# `/snap/bin/lxc` 是 snap wrapper，会先执行 snap-confine；Qoder 的沙箱缺少
# cap_dac_override，snap-confine 会直接失败。LXD 守护进程本身由 systemd 在沙箱外
# 运行，socket 的属组是 lxd，当前用户在 lxd 组里，因此绕过 wrapper 即可正常通信。
#
# 用法与 lxc 完全一致：
#   scripts/lxd.sh list
#   scripts/lxd.sh start qp-test
#   scripts/lxd.sh exec qp-test -- <cmd>
set -euo pipefail

export LXD_DIR="${LXD_DIR:-/var/snap/lxd/common/lxd}"
exec /snap/lxd/current/bin/lxc "$@"
