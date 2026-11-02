# TinyWatch

**网页监控面板 —— 实时看玩家在哪、服务器累不累、刚才谁做了什么。**

单文件插件，零依赖，内置 HTTP 服务，打开浏览器就能看。

![Version](https://img.shields.io/badge/version-1.1.0-blue) ![License](https://img.shields.io/badge/license-MIT-green) ![API](https://img.shields.io/badge/API-1.13%2B-orange)

---

## 它跟别的监控插件有什么不同

市面上有两类工具：

| | 问题 |
|---|---|
| **Plan** | 功能极全，但 jar 装完 20+ MB、依赖 H2/SQLite、启动慢、全英文，而且数据是**定时聚合**的 —— 你看到的是「过去 30 分钟」 |
| **CoreProtect** | 方块日志做到了极致，但它是**事后回滚**工具，没有实时面板，也没有服务器负载 |

TinyWatch 只做一件事：**现在正在发生什么**。

- 玩家现在在哪个坐标、血多少、饿不饿、卡不卡
- 服务器现在是 20 TPS 还是 12 TPS，内存还剩多少
- 刚才那五分钟里，谁挖了什么、开了谁的箱子、敲了什么命令

**它不替代 Plan，也不替代 CoreProtect。** 需要长期统计看 Plan，需要回滚存档看 CoreProtect，需要「马上是怎么回事」看这个。

---

## 功能

### 实时状态

- 在线玩家表：坐标、所在区块、血量、饥饿、等级、模式、延迟、本次在线时长
- 点某个玩家 → 右侧抽屉看详情 + 他最近的操作记录
- TPS / MSPT / 内存 / 实体数 / 已加载区块，三条曲线实时滚动（最近 5 分钟）

### 操作日志

默认记录 6 类：

| 类型 | 内容 |
|---|---|
| 破坏 | 方块名 + 精确坐标 |
| 放置 | 方块名 + 精确坐标 |
| 容器 | 打开了什么容器（箱子/木桶/熔炉…） |
| 命令 | 完整命令内容 |
| 死亡 | 死亡或被谁击杀 |
| 进出服 | 上下线 |

- 按玩家、按类型、按关键词筛选
- 实时推送到页面，不用刷新
- 保留最近 5000 条（可配），超了挤掉最旧的

### 技术特点

- **零依赖**：不用 Vault、不用 PlaceholderAPI、不引任何第三方库
- **单 jar**：网页就打包在插件里，不用另外部署静态站点
- **纯 Bukkit API**：不用 NMS、不用反射改内部（只有取延迟用了一次反射，带两级降级）
- **只读**：不杀实体、不卸区块、不改方块、不踢人

---

## 安装

1. 把 `tinywatch-1.1.0.jar` 放进 `plugins/`
2. 重启服务器
3. **控制台会打印一个自动生成的登录密码**，抄下来：

```
=====================================================
 web.password 为空，已自动生成一个登录密码：

     atHsC33HpysgpCAS

 请自己保存好。随时可以在游戏里执行 /watch 密码 查看。
=====================================================
```

4. 访问 `http://127.0.0.1:8123/`，输入密码

> 默认只监听 `127.0.0.1`，所以**外网直接访问不到**，这是有意的。见下面「怎么从外网访问」。

---

## 怎么从外网访问

面板上能看到所有玩家的坐标和操作记录。**直接把它暴露在公网上等于给服务器装了个没锁的摄像头。**

### 方式一：SSH 隧道（最安全，推荐）

在自己电脑上执行：

```bash
ssh -L 8123:127.0.0.1:8123 root@你的服务器IP
```

保持这个窗口开着，浏览器打开 `http://localhost:8123` 即可。

**服务器上一个端口都不用开，也不用配 nginx 和证书。**

### 方式二：nginx 反代 + 域名

```nginx
server {
    listen 443 ssl http2;
    server_name watch.example.com;

    ssl_certificate     /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8123;
        proxy_http_version 1.1;

        # SSE 必须关掉缓冲，否则事件会卡在 nginx 里不下来
        proxy_buffering off;
        proxy_set_header X-Accel-Buffering no;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # SSE 是长连接，读超时不能太短
        proxy_read_timeout 3600s;
    }
}
```

**两个关键点**，漏了会出问题：

1. `proxy_buffering off` + `X-Accel-Buffering: no`
   nginx 默认会把响应缓冲起来，SSE 的实时推送会一直卡着不出来，页面看起来「连上了但不动」。
   （插件自己也会发 `X-Accel-Buffering: no` 头，双保险。）

2. `proxy_read_timeout` 要放大
   默认 60 秒，SSE 连接会被掐断重连。插件每 5 秒发一次心跳，一般够用，但放大更稳。

### 方式三：直接监听 0.0.0.0（不推荐）

```yaml
web:
  host: "0.0.0.0"
```

能用，但必须在防火墙层面限制来源 IP。插件会在启动时打印警告。

---

## 指令

主命令 `/watch`，别名 `/tw`、`/监控`。**只有 op 能用。**

| 英文 | 中文 | 作用 |
|---|---|---|
| `/watch status` | `/watch 状态` | 看面板地址、连接数、日志条数 |
| `/watch password` | `/watch 密码` | 查看当前登录密码 |
| `/watch newpassword` | `/watch 新密码` | 重置密码（所有已登录页面会掉线） |
| `/watch clear` | `/watch 清空` | 清空内存里的操作日志 |
| `/watch reload` | `/watch 重载` | 重载配置 |

Tab 补全中英文都支持。

---

## 配置

```yaml
web:
  enabled: true
  host: "127.0.0.1"        # 只监听本机。改 0.0.0.0 前先读上面的「安全」一节
  port: 8123
  password: ""             # 留空 = 首次启动自动生成
  session-hours: 12        # 登录有效期
  show-ip: false           # 是否在面板上显示玩家 IP（默认关，属于隐私）

monitor:
  sample-interval-ticks: 20   # 采样间隔，20 tick = 1 秒
  history-size: 300           # 曲线保留多少个点（300 × 1 秒 = 5 分钟）
  slow-stat-every-samples: 5  # 实体数/区块数每 5 次采样才统计一次（遍历全世界比较贵）

events:
  enabled: true
  buffer-size: 5000           # 内存里留多少条日志（约 600 KB）
  record:
    block-break: true
    block-place: true
    container: true
    container-click: false    # 容器内点击，默认关（事件量太大）
    command: true
    death: true
    join-quit: true
  ignore-worlds: []           # 排除刷怪塔、自动农场等高频繁区域
  ignore-commands:            # ⚠️ 登录类命令必须留在这里，见下
    - login
    - register
    - l
    - reg
    - changepassword
    - cp
    - passwd
    - password
```

### ⚠️ 关于 `ignore-commands`（很重要）

**登录插件的命令参数里是明文密码**，比如 `/login MyPassword123`。

如果记进日志，等于**把全服玩家的密码明文写在网页上**，任何能打开面板的人都能看到。

插件默认已经屏蔽了常见登录插件的命令。**如果你用的是别的登录插件（AuthMe、LibsDisguises 认证之类），请把它注册的命令加进这个列表。**

### 修改后生效

改完配置执行 `/watch 重载` 即可，不用重启服务器。

---

## 兼容性

| 项 | 值 |
|---|---|
| 服务端 | Spigot / Paper / Purpur |
| 版本 | **1.13 ~ 26.x** |
| Java | **8+** |
| 前置 | **无** |
| NMS | 零（纯 Bukkit API） |

### 不同版本上的差异（都做了降级，不会崩）

| 功能 | 拿不到时的表现 |
|---|---|
| MSPT | Spigot / 1.13 没有 `getAverageTickTime`，面板显示「服务端未提供」，**不会编一个假数字** |
| 玩家延迟 | 1.16.4 以下靠反射读实体字段；读不到显示「—」 |
| 新版本容器（木桶/高炉/烟熏炉…） | 用字符串匹配识别，1.13 上只是不会出现这些类型 |

---

## 为什么日志不落盘

落盘要处理磁盘 IO、文件轮转、并发写、断点恢复。这是日志审计插件（CoreProtect 那类）的活，它们已经做得很好。

TinyWatch 的定位是「现在正在发生什么」。重启清空是可以接受的取舍 —— 换来的是**零依赖、零配置、零额外磁盘占用**。

需要长期存档请配合 CoreProtect。

---

## 已验证 / 未验证

**实测通过的**：

| 项 | 环境 | 结果 |
|---|---|---|
| 插件加载 | Paper 26.3 + Java 25 | 零异常 |
| 插件加载 | Paper 1.13.2 + Java 8 | 零异常，JDK 8 的 HttpServer 正常 |
| 登录鉴权 | 两种版本 | 错误密码拒绝、未登录 401、Cookie 与 X-Token 都能用 |
| 快照接口 | 两种版本 | TPS 20 / MSPT 0.65ms（26.3）/ null（1.13 正确降级） |
| 历史曲线 | 两种版本 | 每秒累积一个点 |
| SSE 实时连接 | 两种版本 | 建立正常、断线 5~8 秒内清理、不阻塞其他请求 |
| 控制台指令 | 两种版本 | 中英文子命令都识别，Tab 补全正常 |
| 并发 | 26.3 | SSE 挂着时 20 个并发请求全 200 |

**尚未验证的**（需要真人进服才能测，作者的环境没有玩家）：

- 操作日志实际记录效果（挖方块/开箱子/敲命令是否都正确落到页面）
- 玩家详情抽屉的数据刷新
- 界面在真实数据下的观感

如果你遇到这三个方面的问题，欢迎开 issue。

---

## 已知限制

- **只记录插件装上之后的日志**，重启清空
- **不能跨服**（多子服要每个服各装一个，各自看各自的）
- **不做告警**（要告警去看 TinyRadar，它专门做卡顿诊断和自动报警）

---

## 协议

MIT
