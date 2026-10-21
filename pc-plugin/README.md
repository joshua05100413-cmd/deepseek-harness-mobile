# dsh-phone-remote

DSHgo 手机远程的 PC 端：一个 dsh 插件，装完即用。

## 组成

- **手机端**：`DSHgo.apk`（Android，内置 Tailscale，开包即用）
- **PC 端**：本插件 + Tailscale（隧道本体）

## PC 安装（两步）

1. 安装 [Tailscale](https://tailscale.com/) 并登录（与手机同一账号）
2. 双击 `install.bat`（会把插件装进 `~/.dsh/bundles` 并挂进所有 dsh profile）

重启 dsh 桌面版，插件自动拉起整条链路：

| 组件 | 作用 |
| --- | --- |
| minter | `dsh web` 子进程，负责登录 cookie 自动签发 |
| bridge | 8124 端口，手机流量入口（页面/图片/本地端口穿透） |
| serve | `tailscale serve --bg 8124` 自动发布到你的 tailnet |

之后手机打开 `https://<你的主机名>.ts.net/` 即可，无需任何手动步骤。

## 兼容性设计

- 不依赖任何 dsh 内部接口，只监督外部进程（dsh 升级不影响）
- 端口自愈：进程挂掉 30 秒内自动重启；端口被占用则自动让位
- 登录 cookie 自动续期，手机永远不需要输 token

## 常用配置（`cordis.patch.yml` 的 `phone-remote` 条目）

```yaml
- id: phone-remote
  name: "dsh-phone-remote"
  config:
    bridgePort: 8124     # 手机流量入口
    cliPort: 8123        # cookie 签发实例
    minter: true         # 已自行运行 dsh web 时可关
    trustedHost: ""      # dsh web --trusted-host 参数
    serve: true          # 自动 tailscale serve；自行管理时可关
```

## 卸载

删除各 profile `cordis.patch.yml` 中 `phone-remote` 那一段，重启 dsh。

## 手机端

安装 `DSHgo.apk` → 首次打开登录一次 Tailscale → 自动发现工作台，开包即用。
设置里可切换 DSHgo / ZCode go 双模式、站点小窗（本地监控页穿透）等。
