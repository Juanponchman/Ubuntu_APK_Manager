#define _FILE_OFFSET_BITS 64
#define _GNU_SOURCE

#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define BUFFER_SIZE (1024U * 1024U)
#define TOOL_VERSION "CNTERMUX_NATIVE_4"

static void fail_errno(const char *operation, const char *path) {
    fprintf(stderr, "%s: %s: %s\n", operation, path, strerror(errno));
}

static bool all_zero(const unsigned char *buffer, size_t length) {
    const uint64_t *words = (const uint64_t *)buffer;
    while (length >= sizeof(*words)) {
        if (*words++ != 0) return false;
        length -= sizeof(*words);
    }
    const unsigned char *tail = (const unsigned char *)words;
    while (length-- > 0) if (*tail++ != 0) return false;
    return true;
}

static int write_all_at(int fd, const unsigned char *buffer, size_t length, off_t offset) {
    size_t written = 0;
    while (written < length) {
        ssize_t count = pwrite(fd, buffer + written, length - written, offset + (off_t)written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        written += (size_t)count;
    }
    return 0;
}

static int copy_extent(int source, int target, off_t begin, off_t end, unsigned char *buffer) {
    off_t offset = begin;
    while (offset < end) {
        size_t wanted = (size_t)((end - offset) > BUFFER_SIZE ? BUFFER_SIZE : (end - offset));
        ssize_t count;
        do {
            count = pread(source, buffer, wanted, offset);
        } while (count < 0 && errno == EINTR);
        if (count <= 0) return -1;
        if (write_all_at(target, buffer, (size_t)count, offset) != 0) return -1;
        offset += count;
    }
    return 0;
}

/* Portable fallback for filesystems that do not implement SEEK_DATA/SEEK_HOLE. */
static int copy_by_zero_scan(int source, int target, off_t size, unsigned char *buffer) {
    off_t offset = 0;
    while (offset < size) {
        size_t wanted = (size_t)((size - offset) > BUFFER_SIZE ? BUFFER_SIZE : (size - offset));
        ssize_t count;
        do {
            count = pread(source, buffer, wanted, offset);
        } while (count < 0 && errno == EINTR);
        if (count <= 0) return -1;
        if (!all_zero(buffer, (size_t)count) &&
            write_all_at(target, buffer, (size_t)count, offset) != 0) return -1;
        offset += count;
    }
    return 0;
}

static int sparse_copy(const char *source_path, const char *target_path) {
    int source = -1;
    int target = -1;
    unsigned char *buffer = NULL;
    bool target_owned = false;
    int result = 1;
    struct stat source_stat;

    source = open(source_path, O_RDONLY | O_CLOEXEC);
    if (source < 0) {
        fail_errno("open source", source_path);
        goto done;
    }
    if (fstat(source, &source_stat) != 0 || !S_ISREG(source_stat.st_mode)) {
        fail_errno("stat regular source", source_path);
        goto done;
    }
    target = open(
        target_path,
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
        source_stat.st_mode & 0777
    );
    if (target < 0) {
        fail_errno("open target", target_path);
        goto done;
    }
    target_owned = true;
    struct stat target_stat;
    if (fstat(target, &target_stat) != 0 ||
        (source_stat.st_dev == target_stat.st_dev && source_stat.st_ino == target_stat.st_ino)) {
        errno = EINVAL;
        fail_errno("source and target must differ", target_path);
        goto done;
    }
    if (ftruncate(target, source_stat.st_size) != 0) {
        fail_errno("size target", target_path);
        goto done;
    }
    buffer = (unsigned char *)malloc(BUFFER_SIZE);
    if (buffer == NULL) {
        errno = ENOMEM;
        fail_errno("allocate buffer", target_path);
        goto done;
    }

    off_t position = 0;
    bool fallback = false;
    while (position < source_stat.st_size) {
        errno = 0;
        off_t data = lseek(source, position, SEEK_DATA);
        if (data < 0) {
            if (errno == ENXIO) break;
            if (errno == EINVAL || errno == ENOTSUP) {
                fallback = true;
                break;
            }
            fail_errno("find data extent", source_path);
            goto done;
        }
        errno = 0;
        off_t hole = lseek(source, data, SEEK_HOLE);
        if (hole < 0) {
            if (errno == EINVAL || errno == ENOTSUP) {
                fallback = true;
                break;
            }
            if (errno == ENXIO) hole = source_stat.st_size;
            else {
                fail_errno("find hole extent", source_path);
                goto done;
            }
        }
        if (hole > source_stat.st_size) hole = source_stat.st_size;
        if (copy_extent(source, target, data, hole, buffer) != 0) {
            fail_errno("copy data extent", target_path);
            goto done;
        }
        position = hole;
    }
    if (fallback) {
        if (ftruncate(target, 0) != 0 || ftruncate(target, source_stat.st_size) != 0 ||
            copy_by_zero_scan(source, target, source_stat.st_size, buffer) != 0) {
            fail_errno("fallback sparse copy", target_path);
            goto done;
        }
    }
    if (fchmod(target, source_stat.st_mode & 07777) != 0 || fsync(target) != 0) {
        fail_errno("finalize target", target_path);
        goto done;
    }
    if (fstat(target, &target_stat) != 0 || target_stat.st_size != source_stat.st_size) {
        errno = EIO;
        fail_errno("verify target size", target_path);
        goto done;
    }
    printf("SPARSE_COPY_OK logical=%" PRId64 " allocated=%" PRId64 "\n",
           (int64_t)target_stat.st_size, (int64_t)target_stat.st_blocks * 512);
    result = 0;

done:
    if (buffer != NULL) free(buffer);
    if (target >= 0) close(target);
    if (source >= 0) close(source);
    if (result != 0 && target_owned) unlink(target_path);
    return result;
}

static int parse_signal(const char *value) {
    if (strcmp(value, "TERM") == 0 || strcmp(value, "15") == 0) return SIGTERM;
    if (strcmp(value, "KILL") == 0 || strcmp(value, "9") == 0) return SIGKILL;
    return -1;
}

static int kill_rooted(const char *expected_root, const char *signal_value) {
    int signal_number = parse_signal(signal_value);
    if (signal_number < 0 || expected_root[0] != '/') {
        fprintf(stderr, "invalid root path or signal\n");
        return 2;
    }
    DIR *proc = opendir("/proc");
    if (proc == NULL) {
        fail_errno("open", "/proc");
        return 1;
    }
    int killed = 0;
    struct dirent *entry;
    while ((entry = readdir(proc)) != NULL) {
        char *end = NULL;
        errno = 0;
        long parsed = strtol(entry->d_name, &end, 10);
        if (errno != 0 || end == entry->d_name || *end != '\0' || parsed <= 0 || parsed > INT_MAX) continue;
        pid_t pid = (pid_t)parsed;
        if (pid == getpid()) continue;
        char link_path[64];
        char root_path[PATH_MAX + 1];
        int path_length = snprintf(link_path, sizeof(link_path), "/proc/%d/root", pid);
        if (path_length <= 0 || (size_t)path_length >= sizeof(link_path)) continue;
        ssize_t root_length = readlink(link_path, root_path, PATH_MAX);
        if (root_length <= 0) continue;
        root_path[root_length] = '\0';
        if (strcmp(root_path, expected_root) != 0) continue;
        if (kill(pid, signal_number) == 0 || errno == ESRCH) killed++;
    }
    closedir(proc);
    printf("KILLED_ROOTED %d\n", killed);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        puts(TOOL_VERSION);
        return 0;
    }
    if (argc == 4 && strcmp(argv[1], "--kill-rooted") == 0) {
        return kill_rooted(argv[2], argv[3]);
    }
    if (argc != 3) {
        fprintf(stderr, "usage: %s SOURCE TARGET | --kill-rooted ROOT TERM|KILL\n", argv[0]);
        return 2;
    }
    return sparse_copy(argv[1], argv[2]);
}
