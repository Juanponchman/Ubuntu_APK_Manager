package cn.termux.ubuntumanager.termux

import android.annotation.SuppressLint

@SuppressLint("SdCardPath")
object TermuxContract {
    const val PACKAGE_NAME = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERROR = "err"
    const val RESULT_ERROR_MESSAGE = "errmsg"
    const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"

    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val PROOT_DISTRO = "$PREFIX/bin/proot-distro"
    const val BASH = "$PREFIX/bin/bash"
    const val PYTHON = "$PREFIX/bin/python"
    const val TAR = "$PREFIX/bin/tar"
    const val SHA256SUM = "$PREFIX/bin/sha256sum"
    const val MKDIR = "$PREFIX/bin/mkdir"
    const val RM = "$PREFIX/bin/rm"
    const val WAKE_LOCK = "$PREFIX/bin/termux-wake-lock"
    const val WAKE_UNLOCK = "$PREFIX/bin/termux-wake-unlock"
}
