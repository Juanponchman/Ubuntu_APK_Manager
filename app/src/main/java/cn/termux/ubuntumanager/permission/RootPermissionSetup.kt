package cn.termux.ubuntumanager.permission

import android.content.Context

/** Alpha-only Android background whitelist operation; Chroot itself is supervised independently. */
class RootPermissionSetup(context: Context) {
    private val appContext = context.applicationContext
    private val root = RootCommandExecutor(appContext)

    suspend fun protectBackgroundApps(): String {
        val alphaVersion = root.alphaVersion() ?: error("未安装受支持的 Magisk Alpha")
        val userId = android.os.Process.myUid() / 100_000
        val packageName = appContext.packageName
        require(PACKAGE_PATTERN.matches(packageName)) { "管理器包名不安全" }
        val result = root.execute(
            "set -e; /system/bin/cmd deviceidle whitelist +$packageName; " +
                "/system/bin/am set-inactive --user $userId $packageName false || true; " +
                "/system/bin/printf 'ROOT_BACKGROUND_PROTECTION_OK\\n'",
            90_000,
        )
        check(result.isSuccess) { result.bestError }
        check(result.stdout.lineSequence().any { it.trim() == "ROOT_BACKGROUND_PROTECTION_OK" }) {
            "Root 命令退出成功，但没有返回后台保护配置标记"
        }
        return "Magisk Alpha $alphaVersion 已将 Ubuntu 管理器加入 Android 后台白名单"
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+${'$'}")
    }
}
