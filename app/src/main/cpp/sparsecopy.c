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
#include <zlib.h>

#define BUFFER_SIZE (1024U * 1024U)
#define TOOL_VERSION "CNTERMUX_NATIVE_6"
#define ARCHIVE_VERSION 1U
#define ARCHIVE_INSTANCE_SIZE 33U
#define ARCHIVE_DISPLAY_SIZE 513U
#define ARCHIVE_SHA256_SIZE 65U

static const unsigned char ARCHIVE_MAGIC[16] = {
    'C', 'N', 'U', 'B', 'U', 'N', 'T', 'U', '_', 'A', 'R', 'C', 'H', '_', '1', '\0'
};

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

static int write_all(int fd, const void *data, size_t length) {
    const unsigned char *buffer = (const unsigned char *)data;
    size_t written = 0;
    while (written < length) {
        ssize_t count = write(fd, buffer + written, length - written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        written += (size_t)count;
    }
    return 0;
}

static int read_all(int fd, void *data, size_t length) {
    unsigned char *buffer = (unsigned char *)data;
    size_t received = 0;
    while (received < length) {
        ssize_t count = read(fd, buffer + received, length - received);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        received += (size_t)count;
    }
    return 0;
}

static int write_u32(int fd, uint32_t value) {
    unsigned char bytes[4];
    for (size_t index = 0; index < sizeof(bytes); index++) {
        bytes[index] = (unsigned char)((value >> (index * 8U)) & 0xffU);
    }
    return write_all(fd, bytes, sizeof(bytes));
}

static int write_u64(int fd, uint64_t value) {
    unsigned char bytes[8];
    for (size_t index = 0; index < sizeof(bytes); index++) {
        bytes[index] = (unsigned char)((value >> (index * 8U)) & 0xffU);
    }
    return write_all(fd, bytes, sizeof(bytes));
}

static int read_u32(int fd, uint32_t *value) {
    unsigned char bytes[4];
    if (read_all(fd, bytes, sizeof(bytes)) != 0) return -1;
    uint32_t parsed = 0;
    for (size_t index = 0; index < sizeof(bytes); index++) {
        parsed |= ((uint32_t)bytes[index]) << (index * 8U);
    }
    *value = parsed;
    return 0;
}

static int read_u64(int fd, uint64_t *value) {
    unsigned char bytes[8];
    if (read_all(fd, bytes, sizeof(bytes)) != 0) return -1;
    uint64_t parsed = 0;
    for (size_t index = 0; index < sizeof(bytes); index++) {
        parsed |= ((uint64_t)bytes[index]) << (index * 8U);
    }
    *value = parsed;
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

/* Rebuilds a sparse regular file from an unseekable stream such as tar -xO. */
static int sparse_from_stdin(const char *target_path) {
    int target = -1;
    unsigned char *buffer = NULL;
    bool target_owned = false;
    int result = 1;
    off_t offset = 0;

    target = open(
        target_path,
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
        0600
    );
    if (target < 0) {
        fail_errno("open target", target_path);
        goto done;
    }
    target_owned = true;
    buffer = (unsigned char *)malloc(BUFFER_SIZE);
    if (buffer == NULL) {
        errno = ENOMEM;
        fail_errno("allocate buffer", target_path);
        goto done;
    }

    while (true) {
        ssize_t count;
        do {
            count = read(STDIN_FILENO, buffer, BUFFER_SIZE);
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            fail_errno("read sparse stream", "stdin");
            goto done;
        }
        if (count == 0) break;
        if (!all_zero(buffer, (size_t)count) &&
            write_all_at(target, buffer, (size_t)count, offset) != 0) {
            fail_errno("write sparse stream", target_path);
            goto done;
        }
        if (offset > INT64_MAX - count) {
            errno = EOVERFLOW;
            fail_errno("size sparse stream", target_path);
            goto done;
        }
        offset += count;
    }

    if (ftruncate(target, offset) != 0 || fsync(target) != 0) {
        fail_errno("finalize sparse stream", target_path);
        goto done;
    }
    struct stat target_stat;
    if (fstat(target, &target_stat) != 0 || target_stat.st_size != offset) {
        errno = EIO;
        fail_errno("verify sparse stream", target_path);
        goto done;
    }
    printf("SPARSE_STREAM_OK logical=%" PRId64 " allocated=%" PRId64 "\n",
           (int64_t)target_stat.st_size, (int64_t)target_stat.st_blocks * 512);
    result = 0;

done:
    if (buffer != NULL) free(buffer);
    if (target >= 0) close(target);
    if (result != 0 && target_owned) unlink(target_path);
    return result;
}

typedef struct {
    uint64_t logical_size;
    char instance[ARCHIVE_INSTANCE_SIZE];
    char display_base64[ARCHIVE_DISPLAY_SIZE];
    char sha256[ARCHIVE_SHA256_SIZE];
} archive_header;

static bool valid_instance_name(const char *value) {
    size_t length = strlen(value);
    if (length == 0 || length >= ARCHIVE_INSTANCE_SIZE) return false;
    if (!((value[0] >= 'A' && value[0] <= 'Z') ||
          (value[0] >= 'a' && value[0] <= 'z') ||
          (value[0] >= '0' && value[0] <= '9'))) return false;
    for (size_t index = 1; index < length; index++) {
        char character = value[index];
        if (!((character >= 'A' && character <= 'Z') ||
              (character >= 'a' && character <= 'z') ||
              (character >= '0' && character <= '9') ||
              character == '_' || character == '-')) return false;
    }
    return true;
}

static bool valid_base64url(const char *value) {
    size_t length = strlen(value);
    if (length == 0 || length >= ARCHIVE_DISPLAY_SIZE) return false;
    for (size_t index = 0; index < length; index++) {
        char character = value[index];
        if (!((character >= 'A' && character <= 'Z') ||
              (character >= 'a' && character <= 'z') ||
              (character >= '0' && character <= '9') ||
              character == '_' || character == '-')) return false;
    }
    return true;
}

static bool valid_sha256(const char *value) {
    if (strlen(value) != 64U) return false;
    for (size_t index = 0; index < 64U; index++) {
        char character = value[index];
        if (!((character >= '0' && character <= '9') ||
              (character >= 'a' && character <= 'f'))) return false;
    }
    return true;
}

static int write_archive_header(int fd, const archive_header *header) {
    uint32_t version = ARCHIVE_VERSION;
    if (write_all(fd, ARCHIVE_MAGIC, sizeof(ARCHIVE_MAGIC)) != 0 ||
        write_u32(fd, version) != 0 ||
        write_u64(fd, header->logical_size) != 0 ||
        write_all(fd, header->instance, sizeof(header->instance)) != 0 ||
        write_all(fd, header->display_base64, sizeof(header->display_base64)) != 0 ||
        write_all(fd, header->sha256, sizeof(header->sha256)) != 0) return -1;
    return 0;
}

static int read_archive_header(int fd, archive_header *header) {
    unsigned char magic[sizeof(ARCHIVE_MAGIC)];
    uint32_t version = 0;
    memset(header, 0, sizeof(*header));
    if (read_all(fd, magic, sizeof(magic)) != 0 ||
        memcmp(magic, ARCHIVE_MAGIC, sizeof(magic)) != 0 ||
        read_u32(fd, &version) != 0 || version != ARCHIVE_VERSION ||
        read_u64(fd, &header->logical_size) != 0 ||
        read_all(fd, header->instance, sizeof(header->instance)) != 0 ||
        read_all(fd, header->display_base64, sizeof(header->display_base64)) != 0 ||
        read_all(fd, header->sha256, sizeof(header->sha256)) != 0) return -1;
    if (header->instance[ARCHIVE_INSTANCE_SIZE - 1U] != '\0' ||
        header->display_base64[ARCHIVE_DISPLAY_SIZE - 1U] != '\0' ||
        header->sha256[ARCHIVE_SHA256_SIZE - 1U] != '\0' ||
        header->logical_size == 0 || header->logical_size > (uint64_t)INT64_MAX ||
        !valid_instance_name(header->instance) ||
        !valid_base64url(header->display_base64) ||
        !valid_sha256(header->sha256)) return -1;
    return 0;
}

static int archive_write_chunk(
    int target,
    uint64_t offset,
    const unsigned char *raw,
    uint32_t raw_size,
    unsigned char *compressed,
    uLongf compressed_capacity
) {
    if (all_zero(raw, raw_size)) return 0;
    uLongf compressed_size = compressed_capacity;
    int compressed_result = compress2(
        compressed,
        &compressed_size,
        raw,
        raw_size,
        Z_BEST_SPEED
    );
    if (compressed_result != Z_OK || compressed_size > UINT32_MAX) {
        errno = EIO;
        return -1;
    }
    if (write_u64(target, offset) != 0 ||
        write_u32(target, raw_size) != 0 ||
        write_u32(target, (uint32_t)compressed_size) != 0 ||
        write_all(target, compressed, (size_t)compressed_size) != 0) return -1;
    return 0;
}

static int archive_scan_range(
    int source,
    int target,
    off_t begin,
    off_t end,
    unsigned char *raw,
    unsigned char *compressed,
    uLongf compressed_capacity
) {
    off_t offset = begin;
    while (offset < end) {
        size_t wanted = (size_t)((end - offset) > BUFFER_SIZE ? BUFFER_SIZE : (end - offset));
        ssize_t count;
        do {
            count = pread(source, raw, wanted, offset);
        } while (count < 0 && errno == EINTR);
        if (count <= 0 || archive_write_chunk(
                target,
                (uint64_t)offset,
                raw,
                (uint32_t)count,
                compressed,
                compressed_capacity
            ) != 0) return -1;
        offset += count;
    }
    return 0;
}

static int export_archive(
    const char *source_path,
    const char *target_path,
    const char *instance,
    const char *display_base64,
    const char *sha256
) {
    int source = -1;
    int target = -1;
    bool target_owned = false;
    unsigned char *raw = NULL;
    unsigned char *compressed = NULL;
    int result = 1;
    struct stat source_stat;
    archive_header header;
    memset(&header, 0, sizeof(header));

    if (!valid_instance_name(instance) || !valid_base64url(display_base64) ||
        !valid_sha256(sha256)) {
        fprintf(stderr, "invalid archive metadata\n");
        return 2;
    }
    source = open(source_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (source < 0 || fstat(source, &source_stat) != 0 || !S_ISREG(source_stat.st_mode) ||
        source_stat.st_size <= 0) {
        fail_errno("open archive source", source_path);
        goto done;
    }
    target = open(target_path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (target < 0) {
        fail_errno("open archive target", target_path);
        goto done;
    }
    target_owned = true;
    header.logical_size = (uint64_t)source_stat.st_size;
    memcpy(header.instance, instance, strlen(instance));
    memcpy(header.display_base64, display_base64, strlen(display_base64));
    memcpy(header.sha256, sha256, strlen(sha256));
    if (write_archive_header(target, &header) != 0) {
        fail_errno("write archive header", target_path);
        goto done;
    }
    raw = (unsigned char *)malloc(BUFFER_SIZE);
    uLongf compressed_capacity = compressBound(BUFFER_SIZE);
    compressed = (unsigned char *)malloc((size_t)compressed_capacity);
    if (raw == NULL || compressed == NULL) {
        errno = ENOMEM;
        fail_errno("allocate archive buffers", target_path);
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
            fail_errno("find archive data", source_path);
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
                fail_errno("find archive hole", source_path);
                goto done;
            }
        }
        if (hole > source_stat.st_size) hole = source_stat.st_size;
        if (archive_scan_range(
                source,
                target,
                data,
                hole,
                raw,
                compressed,
                compressed_capacity
            ) != 0) {
            fail_errno("write archive data", target_path);
            goto done;
        }
        position = hole;
    }
    if (fallback) {
        if (ftruncate(target, 0) != 0 || lseek(target, 0, SEEK_SET) < 0 ||
            write_archive_header(target, &header) != 0 ||
            archive_scan_range(
                source,
                target,
                0,
                source_stat.st_size,
                raw,
                compressed,
                compressed_capacity
            ) != 0) {
            fail_errno("fallback archive scan", target_path);
            goto done;
        }
    }
    if (write_u64(target, UINT64_MAX) != 0 || write_u32(target, 0) != 0 ||
        write_u32(target, 0) != 0 || fsync(target) != 0) {
        fail_errno("finalize archive", target_path);
        goto done;
    }
    struct stat target_stat;
    if (fstat(target, &target_stat) != 0) {
        fail_errno("verify archive", target_path);
        goto done;
    }
    printf("ARCHIVE_EXPORT_OK logical=%" PRIu64 " archive=%" PRId64 "\n",
           header.logical_size, (int64_t)target_stat.st_size);
    result = 0;

done:
    if (compressed != NULL) free(compressed);
    if (raw != NULL) free(raw);
    if (target >= 0) close(target);
    if (source >= 0) close(source);
    if (result != 0 && target_owned) unlink(target_path);
    return result;
}

static int inspect_archive(const char *archive_path) {
    int archive = open(archive_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (archive < 0) {
        fail_errno("open archive", archive_path);
        return 1;
    }
    archive_header header;
    int result = read_archive_header(archive, &header);
    close(archive);
    if (result != 0) {
        fprintf(stderr, "invalid cnubuntu archive header\n");
        return 1;
    }
    printf("CNTERMUX_ARCHIVE|%" PRIu64 "|%s|%s|%s\n",
           header.logical_size,
           header.instance,
           header.display_base64,
           header.sha256);
    return 0;
}

static int rename_archive(const char *archive_path, const char *display_base64) {
    if (!valid_base64url(display_base64)) {
        fprintf(stderr, "invalid archive display name\n");
        return 2;
    }
    int archive = open(archive_path, O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    if (archive < 0) {
        fail_errno("open archive", archive_path);
        return 1;
    }
    archive_header header;
    if (read_archive_header(archive, &header) != 0) {
        close(archive);
        fprintf(stderr, "invalid cnubuntu archive header\n");
        return 1;
    }
    memset(header.display_base64, 0, sizeof(header.display_base64));
    memcpy(header.display_base64, display_base64, strlen(display_base64));
    if (lseek(archive, 0, SEEK_SET) < 0 || write_archive_header(archive, &header) != 0 ||
        fsync(archive) != 0) {
        fail_errno("update archive name", archive_path);
        close(archive);
        return 1;
    }
    close(archive);
    puts("ARCHIVE_RENAME_OK");
    return 0;
}

static int restore_archive(const char *archive_path, const char *target_path) {
    int archive = -1;
    int target = -1;
    bool target_owned = false;
    unsigned char *raw = NULL;
    unsigned char *compressed = NULL;
    int result = 1;
    archive_header header;

    archive = open(archive_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (archive < 0 || read_archive_header(archive, &header) != 0) {
        fail_errno("open valid archive", archive_path);
        goto done;
    }
    target = open(target_path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (target < 0) {
        fail_errno("open restore target", target_path);
        goto done;
    }
    target_owned = true;
    raw = (unsigned char *)malloc(BUFFER_SIZE);
    uLongf compressed_capacity = compressBound(BUFFER_SIZE);
    compressed = (unsigned char *)malloc((size_t)compressed_capacity);
    if (raw == NULL || compressed == NULL) {
        errno = ENOMEM;
        fail_errno("allocate restore buffers", target_path);
        goto done;
    }

    uint64_t previous_end = 0;
    while (true) {
        uint64_t offset = 0;
        uint32_t raw_size = 0;
        uint32_t compressed_size = 0;
        if (read_u64(archive, &offset) != 0 || read_u32(archive, &raw_size) != 0 ||
            read_u32(archive, &compressed_size) != 0) {
            errno = EIO;
            fail_errno("read archive record", archive_path);
            goto done;
        }
        if (offset == UINT64_MAX && raw_size == 0 && compressed_size == 0) break;
        if (raw_size == 0 || raw_size > BUFFER_SIZE ||
            compressed_size == 0 || compressed_size > compressed_capacity ||
            offset < previous_end || offset > header.logical_size ||
            raw_size > header.logical_size - offset) {
            errno = EINVAL;
            fail_errno("validate archive record", archive_path);
            goto done;
        }
        if (read_all(archive, compressed, compressed_size) != 0) {
            errno = EIO;
            fail_errno("read archive data", archive_path);
            goto done;
        }
        uLongf restored_size = raw_size;
        if (uncompress(raw, &restored_size, compressed, compressed_size) != Z_OK ||
            restored_size != raw_size ||
            write_all_at(target, raw, raw_size, (off_t)offset) != 0) {
            errno = EIO;
            fail_errno("restore archive data", target_path);
            goto done;
        }
        previous_end = offset + raw_size;
    }
    unsigned char trailing;
    ssize_t trailing_count;
    do {
        trailing_count = read(archive, &trailing, 1);
    } while (trailing_count < 0 && errno == EINTR);
    if (trailing_count != 0 || ftruncate(target, (off_t)header.logical_size) != 0 ||
        fsync(target) != 0) {
        errno = EIO;
        fail_errno("finalize restored archive", target_path);
        goto done;
    }
    struct stat target_stat;
    if (fstat(target, &target_stat) != 0 ||
        (uint64_t)target_stat.st_size != header.logical_size) {
        errno = EIO;
        fail_errno("verify restored archive", target_path);
        goto done;
    }
    printf("ARCHIVE_RESTORE_OK logical=%" PRIu64 " allocated=%" PRId64 " sha256=%s\n",
           header.logical_size,
           (int64_t)target_stat.st_blocks * 512,
           header.sha256);
    result = 0;

done:
    if (compressed != NULL) free(compressed);
    if (raw != NULL) free(raw);
    if (target >= 0) close(target);
    if (archive >= 0) close(archive);
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
    if (argc == 3 && strcmp(argv[1], "--sparse-stdin") == 0) {
        return sparse_from_stdin(argv[2]);
    }
    if (argc == 7 && strcmp(argv[1], "--export-archive") == 0) {
        return export_archive(argv[2], argv[3], argv[4], argv[5], argv[6]);
    }
    if (argc == 3 && strcmp(argv[1], "--inspect-archive") == 0) {
        return inspect_archive(argv[2]);
    }
    if (argc == 4 && strcmp(argv[1], "--rename-archive") == 0) {
        return rename_archive(argv[2], argv[3]);
    }
    if (argc == 4 && strcmp(argv[1], "--restore-archive") == 0) {
        return restore_archive(argv[2], argv[3]);
    }
    if (argc != 3) {
        fprintf(stderr,
                "usage: %s SOURCE TARGET | --sparse-stdin TARGET | "
                "--export-archive SOURCE TARGET INSTANCE DISPLAY_B64 SHA256 | "
                "--inspect-archive ARCHIVE | --rename-archive ARCHIVE DISPLAY_B64 | "
                "--restore-archive ARCHIVE TARGET | --kill-rooted ROOT TERM|KILL\n",
                argv[0]);
        return 2;
    }
    return sparse_copy(argv[1], argv[2]);
}
