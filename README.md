# DSHgo

**把 DeepSeek Harness (dsh) 的工作台装进口袋。** 点开手机 App 就是电脑上正在跑的实时会话——可看、可操作、可语音。

手机端一个 APK（Tailscale 隧道内核内置），PC 端一个 dsh 插件 + Tailscale，开包即用。

- 作者：**Fa2517**（[bilibili](https://space.bilibili.com/330037645)）
- 版本：v1.0 · 本项目完全开源

## 功能

| | |
|---|---|
| 🔒 内置加密隧道 | Tailscale 内核编译进 APK，App 内登录建隧道，装一个 App 就够 |
| 🧭 万能抓取 | 隧道接通后自动识别远程工作台并直连，不挑主机名/端口 |
| 👀 实时监控 | 电脑上正在跑的会话、子智能体、后台任务，手机实时可见可操作 |
| 🎨 DSHgo / ZCode go 双模式 | 顶栏一键切换；切 ZCode 自动关隧道、切回自动开 |
| 🪟 站点小窗 | 悬浮窗查看本地监控页（默认关闭，可选开关）；页面里的 127.0.0.1 请求自动穿隧道回电脑 |
| 🎤 语音输入 | 麦克风权限自动申请，开箱即用 |
| 🌗 弱网优化 | 连接状态可视化、断线自动重连、历史缓存秒开 |
| 🎞 开机动画 | DSH 图标拉动式品牌动画，覆盖连接时段 |

> 已知限制：「窄屏自动收起侧边栏」为预留开关，功能未实现，开关不影响使用。

## 安装

**PC 端（一次性）**
1. 安装 [Tailscale](https://tailscale.com/) 并登录（与手机同账号）
2. 安装 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh 桌面版）
3. 进入 `pc-plugin/` 双击 `install.bat`，重启 dsh

**手机端**
1. 安装 `DSHRemote.apk`（[Releases](../../releases) 下载）
2. 打开 → 登录 Tailscale（仅此一次）→ 允许 VPN / 麦克风授权

之后每次打开 App 自动：建隧道 → 发现电脑 → 进入工作台。

详细文档见 [`docs/使用说明.md`](docs/使用说明.md)。

## 仓库结构

```
phone-app/    Android 端源码（无 Gradle，build.bat 一键打包）
pc-plugin/    PC 端 dsh 插件（install.bat 一键安装，dsh 启动即用）
docs/         使用说明
```

## 从源码构建手机端

```bat
cd phone-app
copy build-env.example.bat build-env.bat   :: 填入本机 JDK / Android SDK / libtailscale.aar 路径
build.bat
```

- 需要：JDK 17、Android SDK（build-tools 34、platform 34）
- libtailscale.aar：用 [tailscale-android](https://github.com/tailscale/tailscale-android) 源码经 gomobile bind 产出
- 签名密钥首次构建自动生成（`build/dsh.keystore`），勿提交到任何仓库

## 设计说明

- **PC 插件零 dsh 内部依赖**：只用 `apply(ctx, config)` 插件形态 + node 内置模块
  监督外部进程（`dsh web` 登录签发 + 桥接 + `tailscale serve`），dsh 版本升级不影响插件
- **桥接自愈**：端口被占用自动让位、进程挂掉 30 秒内重启；App 的 web 端口每回启动
  用 cookie 实测自动挑选（同进程可能托管多个 web 根）
- **本地端口穿透**：串流到手机的本地页面里的 `127.0.0.1:*` 请求自动代理回电脑，
  任何本地监控面板无需改动即可在手机使用

## 隐私

- 全程 Tailscale（WireGuard）加密隧道，仅自己设备可达
- 凭据经 Android Keystore 加密存储，不出设备
- App 不收集、不上传任何个人信息

## License

[MIT](LICENSE) · Made with ❤ by [Fa2517](https://space.bilibili.com/330037645)
