# Deepseek Harness Mobile

基于 [DSHgo v1.2](https://github.com/just-a-FA-for-name/DSHgo/tree/v1.2) 修改的安卓客户端与电脑桥接插件，用手机通过 Tailscale 访问电脑上的 DeepSeek Harness 工作台。

本版本使用 DSH 工作台原有界面；手机窄屏使用抽屉侧栏，较宽的屏幕使用并排布局。应用外壳只提供深色和浅色主题，不显示原项目的装饰性开屏、大肥鱼主题及 ZCode 入口。原版 v1.2 的连接恢复、诊断和文件续传等功能仍保留。

## 快速安装

### 电脑端（被连接的电脑）

1. 安装并登录 [Tailscale for Windows](https://tailscale.com/download/windows)，再安装并打开 DeepSeek Harness。
2. 从本仓库下载源码并解压；进入 `pc-plugin`，双击 `install.bat`。
3. 在 DSH 的「插件 → 已安装插件」中启用 `dsh-phone-remote`，完全退出并重新打开 DSH。
4. 如果插件无法找到 `dsh` 命令，在 DSH 所用 profile 的 `cordis.patch.yml` 中为插件指定实际的命令行路径：

   ```yaml
   - id: phone-remote
     disabled: false
     config:
       dshBin: 'C:/你的路径/dsh.cmd'
   ```

   路径必须指向该电脑上可运行的 DSH 命令；可用 PowerShell 的 `Get-Command dsh` 查找。配置文件通常位于 `%USERPROFILE%\.dsh\profiles\desktop\cordis.patch.yml`。修改前建议备份。
5. 在 [Tailscale DNS 设置](https://login.tailscale.com/admin/dns)中启用 MagicDNS 和 HTTPS 证书，然后让插件启动 Tailscale Serve。按页面要求确认；证书透明度日志会公开设备名称及 tailnet 域名。Serve 提供的是 tailnet 内访问，不是公开互联网访问。

电脑插件负责登录转接和桥接；手机端的 v1.2 文件断点续传需要同时使用本仓库的 v1.2 电脑插件。更多配置见 [电脑插件说明](pc-plugin/README.md)。

### 安卓手机或平板

1. 从本仓库的 **Releases** 下载 `Deepseek-Harness-Mobile-v1.2-icon.apk` 并安装。需要 Android 8.0 或以上、ARM64 或 ARMv7 设备。
2. 打开应用，登录与电脑相同的 Tailscale 账号，并允许 VPN 权限。
3. 保持电脑开机、DSH 和 Tailscale 运行；应用会尝试自动发现并连接工作台。需要语音输入时再授权麦克风。

当前 APK 与上游 DSHgo 使用不同签名，不能直接覆盖安装上游原版；卸载旧应用可能清除其本地数据。与此前由本项目同一签名密钥构建的 APK 可以覆盖安装。Android 的权限限制、网络策略或设备兼容性仍可能影响连接，首次使用请实际测试。

## 排查连接

- 确认电脑和手机登录的是同一个 tailnet，且 Tailscale 在线；在手机的系统 VPN 授权提示中选择允许。
- 确认 DSH 已启用 `dsh-phone-remote` 并已重启；电脑桥接默认监听 `8124`，登录签发默认使用 `8090`。
- 如果 Tailscale Serve 未启用，在电脑运行 `tailscale serve --bg 8124`，按命令给出的页面提示开启 HTTPS。可以运行 `tailscale serve status` 查看状态。
- 如果桥接提示找不到 `dsh`，按上面的 `dshBin` 示例设置本机路径。若没有该命令，需要先安装可运行的 DSH CLI。
- 应用内「设置 → 故障排查」可查看或手动导出诊断信息；检查后再决定是否分享诊断包。

## 源码与构建

- `phone-app/`：安卓应用源码。
- `pc-plugin/`：电脑桥接插件和安装脚本。
- `docs/`：上游 v1.2 发布说明、使用说明和验收范围。

安卓源码构建需要 JDK 17、Android SDK Platform 34、Build Tools 34.0.0，以及兼容的 `libtailscale.aar`。参照 `phone-app/build-env.example.bat` 配置本地路径，再运行 `phone-app/build.bat`。签名密钥仅保存在构建者本地，不包含在仓库中；使用不同密钥构建的 APK 无法覆盖已安装的本项目版本。仓库中的 APK 是已签名的构建产物，不含私钥。

## 来源与许可

基于 [DSHgo](https://github.com/just-a-FA-for-name/DSHgo)，原作者 Fa2517。原项目代码依照 [MIT 许可证](LICENSE)使用。上游大肥鱼素材及其独立许可说明保留在 [phone-app/ASSET-LICENSES.md](phone-app/ASSET-LICENSES.md)，这些素材未作为本版本主题展示；它们不受代码 MIT 许可覆盖。应用图标取自本机 DeepSeek Harness 安装包，其权利归原权利人所有，MIT 代码许可不自动覆盖该图标。
