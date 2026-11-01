#!/usr/bin/env bash
#
# feiniu-diagnose.sh — NASMusicTV 飞牛音乐适配器 · 分步诊断脚本
# 对应 docs/feiniu-track-list-empty-improvement-plan.md §4（复现与诊断）
#
# 用途：复现 APP 对飞牛 music 服务的确切请求，把服务端「信封 code/msg」与
#       单条曲目字段结构抓出来，一次定性是「服务端拒绝参数」还是「APP 解析失败」。
#
# 用法（Windows Git Bash / WSL / Linux / macOS 通用）：
#   ./feiniu-diagnose.sh <服务器地址> <用户名> <密码> [访问码] [deviceId]
#
#   服务器地址：IP 或域名，可带端口（如 192.168.1.100 或 http://nas:5666）
#   用户名/密码：飞牛音乐登录账号
#   访问码：飞牛开启「外网访问码」时才需要填；没开启留空或不填
#   deviceId：设备标识，随便写个稳定字符串即可（默认 nasmusictv-diag）
#
# 例：
#   ./feiniu-diagnose.sh 192.168.1.100 admin MyP@ss123
#   ./feiniu-diagnose.sh 192.168.1.100:5666 admin MyP@ss123 "我的访问码" tv01
#
# 脚本只做诊断，不保存任何凭据；输出里出现的 token 会被自动脱敏。
#

set -u

SERVER="${1:-}"
USERNAME="${2:-}"
PASSWORD="${3:-}"
ACCESS_CODE="${4:-}"
DEVICE_ID="${5:-nasmusictv-diag}"

# ─── 强制 Python 子进程以 UTF-8 输出（Windows 默认 GBK 会把中文打成乱码）─────
export PYTHONIOENCODING=utf-8
export PYTHONUTF8=1

# ─── 参数检查 ───────────────────────────────────────────────────────────
if [[ -z "$SERVER" || -z "$USERNAME" || -z "$PASSWORD" ]]; then
    echo "用法: $0 <服务器地址> <用户名> <密码> [访问码] [deviceId]"
    echo ""
    echo "  服务器地址: IP 或域名，可带端口（例: 192.168.1.100 或 http://nas:5666）"
    echo "  访问码:     飞牛开启「外网访问码」时必填；未开启留空"
    echo ""
    echo "  例: $0 192.168.1.100 admin MyP@ss123"
    echo "  例: $0 192.168.1.100:5666 admin MyP@ss123 \"访问码\" tv01"
    exit 1
fi

# ─── 归一化服务器地址为 <scheme>://<host>:<port>（无 /music/api/v1 前缀）───
# 与 FeiniuUrl.normalize 同约定：缺 scheme 补 http；缺端口补 5666/5667。
normalize_origin() {
    local raw="$1" scheme="http" host port
    if [[ "$raw" == http://* || "$raw" == https://* ]]; then
        scheme="${raw%%://*}"
        host="${raw#*://}"
    else
        host="$raw"
    fi
    host="${host%%/*}"   # 剥掉路径 /music/api/v1
    if [[ "$host" == *:* ]]; then
        port="${host##*:}"
        host="${host%:*}"
    else
        port=5666
        [[ "$scheme" == "https" ]] && port=5667
    fi
    echo "${scheme}://${host}:${port}"
}

ORIGIN="$(normalize_origin "$SERVER")"
# ⚠️ 末尾带斜杠：后面直接拼相对路径（user/password-login）时才能拼出 /music/api/v1/xxx
API="$ORIGIN/music/api/v1/"
echo "=========================================================="
echo " 飞牛音乐 · NASMusicTV 诊断脚本"
echo "=========================================================="
echo " 服务地址 : $ORIGIN"
echo " API 基址 : $API"
echo " 用户名   : ${USERNAME:0:2}***（已脱敏）"
echo " 访问码   : $([[ -n "$ACCESS_CODE" ]] && echo "已提供" || echo "未提供")"
echo " deviceId : $DEVICE_ID"
echo "=========================================================="
echo ""

# 需要 python 做 sha256（bash 无原生 sha256 且要 hex 小写）
PY="python"
command -v python >/dev/null 2>&1 || PY="python3"
if ! command -v "$PY" >/dev/null 2>&1; then
    echo "❌ 找不到 python/python3，无法计算 sha256 密码摘要，请安装后重试。"
    exit 2
fi

sha256_hex() {
    "$PY" -c "import hashlib,sys;print(hashlib.sha256(sys.argv[1].encode('utf-8')).hexdigest())" "$1"
}
b64_enc() {
    "$PY" -c "import base64,sys;print(base64.b64encode(sys.argv[1].encode('utf-8')).decode())" "$1"
}

# ─── 统一的请求函数：打印 HTTP 状态 + 完整信封（含 code/msg）───────────────
# 用法: request <标签> <method> <url> [--json <body>] [--auth]
# 输出存到 $LAST_RAW，供后续 parse_json 使用
LAST_RAW=""
request() {
    local label="$1" method="$2" url="$3"
    shift 3
    local body="" auth=0 json_flag=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --json) body="$2"; json_flag=1; shift 2 ;;
            --auth) auth=1; shift ;;
            *) shift ;;
        esac
    done

    local args=(-s -w $'\n__HTTP_STATUS__%{http_code}' --max-time 30)
    if [[ -n "$ACCESS_CODE" ]]; then
        local ac; ac="$(b64_enc "$ACCESS_CODE")"
        args+=(-H "x-access-code: ${ac}" -H "x-access-source: app")
    fi
    if [[ "$auth" == "1" && -n "$TOKEN" ]]; then
        args+=(-H "Authorization: ${TOKEN}")
    fi
    if [[ "$json_flag" == "1" ]]; then
        args+=(-H "Content-Type: application/json" -X POST -d "$body")
    fi

    echo "──────────────────────────────────────────────────"
    echo "▶ [$label] $method ${url/$ORIGIN/<origin>}"
    echo "  请求参数: $(echo "${url#*\?}" | tr '&' ' ')"
    echo "──────────────────────────────────────────────────"

    local raw
    raw="$(curl "${args[@]}" "$url" 2>&1)"
    local status
    status="$(echo "$raw" | grep -oE '__HTTP_STATUS__[0-9]+' | tail -1 | grep -oE '[0-9]+')"
    raw="${raw%$'\n'__HTTP_STATUS__*}"
    LAST_RAW="$raw"

    echo "  HTTP Status: ${status:-(无响应)}"
    if [[ -n "$status" && "$status" != "200" && "$status" != "2"* ]]; then
        echo "  ⚠️  非 2xx：APP 的 execute() 会走「HTTP ${status}」分支，直接返回 null"
    fi

    if [[ -n "$raw" ]]; then
        # 只回显信封骨架：code / msg / data.total / data.list 长度
        echo "$raw" | "$PY" -c "
import sys, json, re
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except Exception as e:
    # 不是 JSON（HTML 错误页/空响应）
    head = raw[:200].replace(chr(10),' ')
    print(f'  ⚠️ 响应不是 JSON（{e}），前 200 字: {head}')
    sys.exit(0)
code = d.get('code', '<缺失>')
msg  = str(d.get('msg','')).strip() or '<空>'
data = d.get('data')
total = data.get('total') if isinstance(data, dict) else None
lst   = data.get('list') if isinstance(data, dict) else None
n     = len(lst) if isinstance(lst, list) else 'N/A'
print(f'  code  = {code}')
print(f'  msg   = {msg[:120]}')
print(f'  total = {total}')
print(f'  list  = {n} 条')
"
    else
        echo "  ⚠️  空响应（body 为 null）"
    fi
    echo ""
}

# ─── 1. 访问码探测 ──────────────────────────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "【步骤 1/8】探测访问码（挂站点根 /access_code_verify）"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
request "访问码探测" "GET" "${ORIGIN}/access_code_verify"
ACCESS_CODE_OK=$(echo "$LAST_RAW" | "$PY" -c "
import sys,json
try:
    d=json.loads(sys.stdin.read()); print('1' if d.get('code')==0 else '0')
except Exception: print('0')
")
if [[ "$ACCESS_CODE_OK" != "1" ]]; then
    echo "❗ 访问码探测未通过（若未开启访问码请忽略，继续看后续登录是否成功）"
    echo ""
fi

# ─── 2. 登录取 token ───────────────────────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "【步骤 2/8】登录 user/password-login（密码 sha256 由脚本自动算）"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
PW_SHA="$(sha256_hex "$PASSWORD")"
LOGIN_BODY="{\"username\":\"${USERNAME}\",\"password\":\"${PW_SHA}\",\"deviceId\":\"${DEVICE_ID}\"}"
request "登录" "POST" "${API}user/password-login" --json "$LOGIN_BODY"

TOKEN=""
TOKEN_OK=""
if [[ -n "$LAST_RAW" ]]; then
    # 用 argv 传 raw（比 stdin/heredoc 稳健，避免 Windows Git Bash 的引号/换行转义问题）
    eval "$("$PY" -c "
import sys,json
try:
    d=json.loads(sys.argv[1])
except Exception:
    print(\"TOKEN=''\")
    print('TOKEN_OK=0')
    sys.exit(0)
data=d.get('data') or {}
tok=data.get('userToken','') if isinstance(data,dict) else ''
print(f'TOKEN={tok!r}')
print(f'TOKEN_OK={\"1\" if tok else \"0\"}')" "$LAST_RAW")"
fi
if [[ -z "$TOKEN" ]]; then
    echo "❌ 登录失败，未能取得 userToken。后续请求将无法带 Authorization，诊断中止。"
    echo "   请核对：用户名/密码/访问码；若 HTTP 401 说明账号被拒。"
    exit 3
fi
echo "  ✅ 已取得 token: ${TOKEN:0:8}…${TOKEN: -4}（已脱敏）"
echo ""

# ─── 3. 系统信息 ───────────────────────────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "【步骤 3/8】sys/config（服务端版本）"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
request "系统信息" "GET" "${API}sys/config"
echo ""

# ─── 4. 核心对照实验：不带 size（用户 curl 复现）──────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "【步骤 4/8】track/list?page=1&size=1（对照：不带 sort，用户 curl 已确认正常）"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
request "对照" "GET" "${API}track/list?page=1&size=1" --auth
echo ""

# ─── 5. 核心对照实验：复现 APP 的确切请求 ────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "【步骤 5/8】track/list?page=1&size=500&sort=createdAt%2Cdesc（APP getSongs）"
echo "        ⚠️ 这就是本次问题的关键请求。看 code 是否为 0、list 是否非空。"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
request "APP请求" "GET" "${API}track/list?page=1&size=500&sort=createdAt%2Cdesc" --auth
echo ""

# ─── 6. size 上限探测（二分）────────────────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "【步骤 6/8】size 上限探测：500 / 300 / 200 / 100 / 50"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
for n in 500 300 200 100 50; do
    request "size=$n" "GET" "${API}track/list?page=1&size=${n}&sort=createdAt%2Cdesc" --auth
done
echo ""

# ─── 7. 抓单条曲目字段结构 ──────────────────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "【步骤 7/8】抓单条曲目完整字段（核对 guid / title / duration / album / artists）"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
request "单条" "GET" "${API}track/list?page=1&size=1&sort=createdAt%2Cdesc" --auth
if [[ -n "$LAST_RAW" ]]; then
    echo "  ── 第一条曲目的字段名与类型 ──"
    echo "$LAST_RAW" | "$PY" -c "
import sys, json
d = json.load(sys.stdin)
lst = (d.get('data') or {}).get('list') or []
if not lst:
    print('  （list 为空，无法抓取单条）')
else:
    t = lst[0]
    print('  字段名 → 类型 / 示例值')
    print('  ' + '-' * 46)
    for k, v in t.items():
        tname = type(v).__name__
        shown = str(v)
        if len(shown) > 40: shown = shown[:40] + '…'
        print(f'  {k:<20} {tname:<8} {shown}')
"
    echo ""
fi

# ─── 8. 对照组：专辑 / 艺术家（证明其他路径正常）────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "【步骤 8/8】对照组：album/list + artist/list（用户报告这两个正常）"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
request "专辑" "GET" "${API}album/list?page=1&size=5&sort=createdAt%2Cdesc" --auth
request "艺术家" "GET" "${API}artist/list?page=1&size=5&sort=trackCount%2Cdesc" --auth

# ─── 汇总 ──────────────────────────────────────────────────────────────
echo "=========================================================="
echo " 诊断完成。请把上面全部输出（或保存到文件）反馈给开发者。"
echo "=========================================================="
echo ""
echo " 关键判读速览："
echo "  · 步骤5 的 code != 0          → 服务端拒绝 APP 参数组合（对应方案 H2b）"
echo "  · 步骤5 的 code == 0 且 list>0 → 服务端正常，问题在 APP 解析（H1）"
echo "  · 步骤5 的 code == 0 且 list=0 → 服务端返回空页，需结合步骤6/7 再判"
echo "  · 步骤7 单条无 title 字段       → 字段名不匹配（H1a）"
echo "  · 步骤7 duration 是字符串/浮点  → 解析异常（H1b），方案 T0/T1 已覆盖"
echo "  · 步骤6 某档 size 请求 200 却实回 200、请求 500 实回 200 → 服务端把 size 静默截断到 200"
echo ""
echo " 保存输出可用: ./feiniu-diagnose.sh 192.168.1.100 admin pass > report.txt 2>&1"
echo "=========================================================="