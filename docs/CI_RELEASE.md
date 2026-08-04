# CI 打包与发布

GitLab 和 GitHub 都采用手动运行模式。`main` 表示 latest，正式版本使用与 APK
`versionName` 一致的不可变 `vX.Y.Z` Tag。

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

## GitHub 手动构建

进入 `Actions`：

- `Android Latest`：点击 `Run workflow`，始终从 `main` 构建 latest Artifact。
- `Android Release`：点击 `Run workflow`，输入已经推送的 `vX.Y.Z` Tag。工作流会
  校验 Tag 与 APK 版本，上传 Actions Artifact，并创建或更新 GitHub Release。

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
不可用，修复连接后重新执行相同命令即可。

## 本地等价验证

配置四个签名环境变量和 Android SDK 后，可以执行：

```bash
scripts/ci/package-android.sh latest
scripts/ci/package-android.sh release vX.Y.Z
```

脚本会执行单元测试、Release Lint、R8/资源压缩、Release 签名、`apksigner` 验证、
ARM64 原生库检查和 SHA-256 生成。输出位于本地 `dist/`，该目录不会提交到 Git。
