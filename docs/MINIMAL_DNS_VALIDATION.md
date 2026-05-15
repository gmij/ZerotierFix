# 最小验证：Android DNS 嗅探链路可用性（含日志锚点）

目标：先验证 Android 上 DNS 响应是否能进入既有链路  
`onVirtualNetworkFrame -> DnsPacketParser -> SmartRoutingManager.onDnsRecord`，  
暂不实现“国内/国外 DNS 双上游分流”完整方案。

## 1) 2×2 验证矩阵

- 路由模式
  - A: `CHINA_DIRECT`
  - B: `全局/组合`（非 CHINA_DIRECT）
- DNS 模式
  - 1: 系统普通 DNS（关闭 Private DNS）
  - 2: Private DNS (DoT)

得到四个场景：A1 / A2 / B1 / B2。

## 2) 日志锚点

重点观察以下锚点（logcat）：

- `[DNS_] ANCHOR DNS_PATH_CHINA_DIRECT_DOMESTIC`
  - 表示当前按 CHINA_DIRECT 国内 DNS 路径配置。
- `[DNS_] ANCHOR DNS_PATH_GLOBAL_INTERNATIONAL`
  - 表示当前按全局/组合国际 DNS 路径配置。
- `[DNS_] ANCHOR DNS_FRAME_CAPTURED ...`
  - 表示 DNS 响应帧已进入 `onVirtualNetworkFrame` 并被 `DnsPacketParser` 解析出记录。
- `[DNS_] 学习路由策略更新: ...`
  - 表示 `onDnsRecord` 后策略学习发生更新。
- `[DNS_] domain -> ip -> ZT (...)`
  - 表示命中 gfw/google 规则并学习为 VIA_ZT。
- `[CONN] ...`
  - 用于核对后续连接命中的域名/IP 与 DNS 学习结果是否一致。

## 3) 建议抓取命令

```bash
adb logcat -v time | grep -E "DNS_|CONN|CHINA_DIRECT 模式：已添加国内 DNS 服务器|全局代理模式：已添加国际 DNS 服务器"
```

## 4) 判定标准

- **支持该机制（可继续下一阶段）**
  - 某场景下出现 `DNS_FRAME_CAPTURED`，且后续出现 `学习路由策略更新` 或 `domain -> ip -> ZT`；
  - 并且 `[CONN]` 可观察到与学习结果一致的域名/IP 走向。

- **该场景存在 DNS 盲区（不适合投入完整双上游方案）**
  - 持续只有 `[CONN]`，无 `DNS_FRAME_CAPTURED` / 学习锚点。
  - 典型：`CHINA_DIRECT + 国内 DNS`，或启用 Private DNS (DoT) 后 UDP/53 不可见。

## 5) 下一步（仅在最小验证通过后）

- 实施“按 gfwlist 选择国内/国外 DNS 上游 + TTL/IP 学习补全”。
- 若目标 Android/ROM 下 DNS 响应无法进入当前链路，优先转向“连接级补偿策略”。
