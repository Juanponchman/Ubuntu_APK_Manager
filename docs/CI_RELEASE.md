# CI 打包与发布

GitLab 采用手动运行模式，GitHub 采用自动触发并保留手动重跑入口。`main` 表示滚动
latest，正式版本使用与 APK `versionName` 一致的不可变 `vX.Y.Z` Tag。

## Release 签名变量

两个平台必须配置完全相同的四个 Secret：

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

GitHub 配置位置：`Settings → Secrets and variables → Actions`。

GitLab 配置位置：`Settings → CI/CD → Variables`。四个变量应设为 Protected；密码和
Alias 设为 Masked。Base64 内容如果因平台的 Masked 格式限制无法保存为 Masked，至少
必须设为 Protected，并限制只有维护者能够运行受保护分支和 Tag 的 Pipeline。

Release keystore 不得放入仓库、Job Artifact 或 Release 附件。GitLab 与 GitHub 必须
长期使用同一把密钥，否则两个平台生成的 APK 不能互相覆盖升级。

## GitLab 手动构建

进入 `Build → Pipelines → Run pipeline`：

- 选择 `main`：运行 `package_latest`，产出签名的 latest APK，保留 30 天。
- 选择 `vX.Y.Z` Tag：运行 `package_release`，产出 latest 和版本号 APK，保留一年。

GitLab Runner 使用 Docker Executor 和固定 Android SDK 35 镜像，不需要 privileged
模式或 Docker-in-Docker。

## GitHub 自动发布

- 推送 `main`：自动运行 `Android Latest`，完成签名构建后将 `latest` Tag 移动到本次
  提交，并覆盖上传 latest APK 与 SHA-256。该 Release 标记为预发布，不会取代正式版本。
- 推送 `vX.Y.Z` Tag：自动运行 `Android Release`，校验 Tag 与 APK 版本一致后创建正式
  GitHub Release，并将其标记为最新正式版本。
- 两个工作流都保留 `Run workflow` 手动入口。`Android Latest` 始终重新构建 `main`；
  `Android Release` 需要输入已经推送的 `vX.Y.Z` Tag，适合失败后重跑。

`latest` 是唯一允许移动和覆盖的 Tag。`vX.Y.Z` 正式版本 Tag 不可修改，也不应删除后
重新创建。

正式 Release 同时包含：

```text
UbuntuManager-latest-arm64-v8a.apk
UbuntuManager-latest-arm64-v8a.apk.sha256
UbuntuManager-vX.Y.Z-arm64-v8a.apk
UbuntuManager-vX.Y.Z-arm64-v8a.apk.sha256
```

R8 `mapping.txt` 仅保存在私有 CI Artifact 中，不作为公开 Release 附件。

## 双仓库推送

本地 `origin` 保留 GitLab Fetch 地址，并配置 GitLab、GitHub 两个 Push URL。日常发布：

```bash
git push origin main
git tag -a vX.Y.Z -m "Ubuntu APK Manager X.Y.Z"
git push origin vX.Y.Z
```

一个 `git push` 会依次推送两个服务器，但跨服务器无法保证原子性。如果其中一个服务
不可用，修复连接后重新执行相同命令即可。GitHub 收到 `main` 或 `vX.Y.Z` 后会自动
发布；GitLab 不会自动运行 Pipeline，仍需在网页中手动启动。

## 本地等价验证

配置四个签名环境变量和 Android SDK 后，可以执行：

```bash
scripts/ci/package-android.sh latest
scripts/ci/package-android.sh release vX.Y.Z
```

脚本会执行单元测试、Release Lint、R8/资源压缩、Release 签名、`apksigner` 验证、
ARM64 原生库检查和 SHA-256 生成。输出位于本地 `dist/`，该目录不会提交到 Git。
