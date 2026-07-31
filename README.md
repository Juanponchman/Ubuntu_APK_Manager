# Ubuntu 管理器

面向 Termux + PRoot Distro 5.x 的中文 Android 管理器。应用通过 Termux 官方
`RunCommandService` 管理多个命名 Ubuntu 实例，日常运行不需要 Root、Tasker，也不修改
Termux 源码。权限和存储初始化统一使用指定的 Magisk Alpha（包名
`io.github.vvb2060.magisk`）执行固定操作，不兼容普通 Magisk。

## 已实现

- 检查 Termux、`RUN_COMMAND` 权限、外部调用和 PRoot Distro 5.x 功能。
- 使用 Magisk Alpha 完成 `RUN_COMMAND`、外部调用和 Termux 存储权限配置。
- 自动发现实例；首次发现的已有实例全部设为受保护。
- 受保护实例可通过输入完整实例名解除保护，也可随时重新保护。
- 结构化读取 PRoot 会话，前台自动刷新并分别显示实例和 SSH 状态。
- 后台启动 SSH、普通停止和强制停止。
- 创建多个 Ubuntu 24.04 ARM64 实例并分配独立 SSH 端口。
- 在停止状态下重命名实例；修改 SSH 端口时支持安全重启和失败回滚。
- 将停止的实例流式复制为独立实例，并重新生成 SSH 主机密钥和机器身份。
- 安装/修复 `openssh-server`。
- 内置中文指令库，支持自定义标签；点击指令后再选择实例，可安装常用网络、编辑、
  编译、Python 和 Node.js 工具。
- 内置免密码、免密钥的本机交互会话。首次使用会在所选 Ubuntu 中安装 ttyd 和
  dtach，只监听 `127.0.0.1`；普通返回后会话继续在后台运行，重新进入会恢复记录，
  也可显式结束。
- 会话支持中文输入栏、方向键、Ctrl/Alt 一次与长按锁定、快捷指令、半透明滚动条、
  当前/总行数、距最新行数和一键回到最新位置。
- 停止状态下创建 `.tar.xz` 备份和 SHA-256 校验文件。
- 新建、复制、备份、恢复、删除和耗时指令由独立前台服务持有；关闭或划掉界面不会
  取消任务协调，任务状态会持久化，进程异常后只标记中断而不会自动重跑。
- 备份先写入 `.partial`，完成校验后再原子发布 `.tar.xz` 和校验文件，避免把中断的
  半成品显示为可恢复备份。
- 可在维护任务期间临时隐藏最近任务卡片，并通过低干扰常驻通知返回任务；系统要求的
  前台服务通知和 Termux 通知不会被 Root 隐藏。
- 可在实例、会话或后台维护任务运行时自动持有一个 Termux 全局 WakeLock，也可选择
  持续保持；状态会显示为待机、启用中、已持有、释放中或异常。并提供 Magisk Alpha
  Android Doze 白名单、系统电池优化、MIUI 自启动和应用详情入口。
- 存储页分项诊断 Termux 的读取、写入、所有文件访问、目录链接和实际写入状态。
- 单独提供 Magisk Alpha 存储权限修复、目录链接创建和安全重建操作。
- 校验并恢复普通实例。
- 已解除保护且停止的实例允许删除，删除仍需输入完整名称。
- 中文 Material 3 界面、深色模式和 Android 12 动态颜色。

## 手机端准备

Termux 中安装或升级：

```sh
pkg upgrade
pkg install proot-distro
```

编辑 `~/.termux/termux.properties`，确保包含：

```properties
allow-external-apps=true
```

在应用中使用“Magisk Alpha 首次配置”。该入口只支持包名为
`io.github.vvb2060.magisk` 且 `su -v` 返回 Alpha 标识的版本，
不兼容普通 Magisk。使用前需要在 Alpha 的“配置排除列表”中取消勾选 Ubuntu 管理器。
该操作会
先备份 `termux.properties`，再授予 `RUN_COMMAND` 并启用外部调用。Root 仅用于这次
固定配置，Ubuntu 和 PRoot 后续仍以普通应用权限运行。

如果备份目录仍不可写，请在“设置 → 备份与存储”查看五项诊断。系统权限与
`~/storage` 目录链接分别处理：Alpha 存储修复只授予固定权限；Alpha 目录链接操作会
把 Termux 拉到前台并调用 Termux 官方创建逻辑，不使用 Root 直接创建链接。安全重建
发现 `~/storage` 内有普通文件或目录时会拒绝执行。以上操作均不会停止或删除 PRoot
实例。

完整管理功能要求 PRoot Distro 5.1 或更高版本，并需要 `ps`、`kill` 和
`login --detach` 功能。

## 数据安全

- 首次发现的已有实例默认受保护；解除保护需要输入完整实例名。
- 受保护实例不能删除、重命名，也不能由普通恢复流程覆盖。
- 受保护实例可以备份、作为复制源、修改 SSH 端口和运行可信内置指令。
- 备份保存在 `Download/UbuntuManager`。
- 本地会话只连接当前手机上的所选 Ubuntu，不监听局域网地址，也不暴露 Android
  Root Shell。会话内允许交互式命令，因此实例保护不能阻止用户在终端中修改文件。
- 实例名和端口在调用 Termux 前会再次校验。
- 检测到实例正在备份或执行其他维护任务时，不允许启动本地会话。
- 停止实例会结束该实例的全部 PRoot 会话，包括用户手动打开的交互会话。

## 构建

需要 JDK 17 或更高版本和 Android SDK 35：

```sh
./gradlew assembleDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```
