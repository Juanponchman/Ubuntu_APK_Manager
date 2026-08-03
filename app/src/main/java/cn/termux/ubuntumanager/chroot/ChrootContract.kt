package cn.termux.ubuntumanager.chroot

object ChrootContract {
    const val VERSION = "19"
    const val BASE_DIRECTORY = "/data/local/cntermux"
    const val IMAGE_DIRECTORY = "$BASE_DIRECTORY/images"
    const val RUNTIME_DIRECTORY = "$BASE_DIRECTORY/runtime"
    const val LOG_DIRECTORY = "$BASE_DIRECTORY/logs"
    const val BACKUP_DIRECTORY = "$BASE_DIRECTORY/backups"
    const val PORTABLE_BACKUP_DIRECTORY = "/storage/emulated/0/Ubuntu管理器/备份"
    const val CACHE_DIRECTORY = "$BASE_DIRECTORY/cache"
    const val HELPER_PATH = "/data/adb/cntermux/chroot-supervisor.sh"
    const val SPARSE_COPY_PATH = "/data/adb/cntermux/cntermux-sparsecopy"
    const val AUTO_START_SCRIPT_PATH = "/data/adb/service.d/cntermux-autostart.sh"
    const val AUTO_START_CONFIG_PATH = "/data/adb/cntermux/autostart.conf"
    const val AUTO_START_LOG_PATH = "$LOG_DIRECTORY/boot.log"
    const val AUTO_START_VERSION = "CNTERMUX_AUTOSTART_1"
    const val AUTO_START_CONFIG_VERSION = "CNTERMUX_AUTOSTART_CONFIG_1"
    const val SPARSE_COPY_VERSION = "CNTERMUX_NATIVE_6"
    const val PACKAGED_SPARSE_COPY_NAME = "libcntermux_sparse.so"
    const val UBUNTU_VERSION = "24.04.4"
    const val UBUNTU_BASE_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
            "ubuntu-base-24.04.4-base-arm64.tar.gz"
    const val UBUNTU_BASE_SHA256 =
        "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
    const val DEFAULT_IMAGE_SIZE = "8G"
    const val DEFAULT_ROOT_PASSWORD = "root1234"
}
