# Ubuntu 管理器（Root Chroot）

面向已取得 Magisk Alpha Root 的 Android 设备的中文 Ubuntu Chroot 管理器。
运行时不依赖 Termux、`RUN_COMMAND` 或 `proot-distro`，由 APK 直接通过 Alpha Root
管理 Ubuntu 实例。

## 架构

```text
Ubuntu 管理器 APK
        ↓ Magisk Alpha su
/data/adb/cntermux/chroot-supervisor.sh
        ↓ 私有 mount namespace
独立 ext4 实例镜像
        ↓
Ubuntu 24.04 / OpenSSH / tmux / ttyd / Nmap
```

开机启动不经过 APK 或 `su`：Magisk Alpha 直接执行
`/data/adb/service.d/cntermux-autostart.sh`，读取经过校验的
`/data/adb/cntermux/autostart.conf` 后启动已勾选实例。

- 每个实例是一块独立的 8GB 稀疏 ext4 镜像，保存该实例的全部系统、软件和用户数据。
- 所有实例共享 Android 宿主网络，因此能直接读取真实网卡和路由，并支持 Root Nmap
  SYN、ARP 等扫描。不同实例必须使用不同的 SSH 端口。
- 每个运行实例使用独立挂载命名空间。`/dev`、`/dev/pts`、`/proc` 和 `/sys` 只在该
  命名空间内挂载，不污染 Android 全局挂载表。
- 本地会话由实例内的 `ttyd + tmux` 提供；离开页面不会结束 tmux 会话，再次进入可继续。
- Chroot 是运行环境，不是安全沙箱。实例内的 `root` 是设备上的真实 Root，只应运行可信程序。

## 实例与备份目录

```text
/data/local/cntermux/
├── images/      # <实例名>.img
├── runtime/     # PID、端口和临时挂载点
├── logs/        # supervisor、sshd 日志
├── backups/     # 稀疏 ext4 备份镜像与 SHA-256
└── cache/       # 已校验的 Ubuntu Base 下载缓存
```

监督脚本安装在 `/data/adb/cntermux/chroot-supervisor.sh`，ARM64 原生维护工具安装在
`/data/adb/cntermux/cntermux-sparsecopy`，负责稀疏复制和按 Chroot 根目录精确清理进程。
实例停止后才能备份、恢复、复制、重命名或
删除。备份直接复制 ext4 镜像的数据区，不逐个遍历或压缩实例内的小文件，也不会把镜像
空洞扩展成真实占用；每份备份均生成 SHA-256 校验文件。复制器源码位于
`app/src/main/cpp/sparsecopy.c`。

## 开机自动启动

- 设置页提供总开关，每个实例的高级设置提供独立开关。
- Root 配置只接受安全实例名和 1024～65535 的端口，不执行配置中的 Shell 内容。
- 脚本等待 `sys.boot_completed=1`，随后按顺序启动实例，每个实例最多尝试三次。
- 已运行实例返回 `ALREADY_RUNNING`，不会创建第二套挂载或监督进程。
- 开机阶段只启动 Chroot 和 SSH；`ttyd + tmux` 在进入本地会话时按需启动。
- 启动日志位于 `/data/local/cntermux/logs/boot.log`，超过 1MB 时保留一份旧日志。

## 新建实例

管理器下载 Canonical 官方 Ubuntu Base 24.04.4 ARM64：

`https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz`

内置 SHA-256：

`04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2`

创建时自动安装 `openssh-server`、`tmux`、`ttyd`、`nmap`、`iproute2`、`procps`、
`ca-certificates` 和 `zstd`。默认 SSH 账户为 `root`，默认密码为 `root1234`，可在实例
高级操作中按需修改。

## 与旧 Termux/PRoot 的关系

本版本不迁移、不读取、不停止，也不删除旧 Termux/PRoot 实例。旧数据仍保留在 Termux
私有目录中，但不会显示在本管理器内。用户确认不再需要后可自行处理 Termux；APK 的
Root Chroot 实例与旧数据没有交叉。

## 构建

```bash
./gradlew test assembleDebug
```

最低 Android 8（API 26），当前 APK 面向 ARM64，目标设备验证环境为 Redmi K20 Pro /
Android 11 / Magisk Alpha。
