/*
 * s1kiosk_verify: checks one Ed25519 signature (RFC 8032, "pure" Ed25519 with SHA-512) and nothing
 * else. Ships in the S1 Kiosk boot image (overlay.d/sbin) so the root services can tell a radio
 * bundle signed by the project's release key from anything else (docs/radio.md "Radio bundles").
 *
 *   s1kiosk_verify <public-key-file> <message-file> <signature-file>
 *   s1kiosk_verify --copy|--move <base-dir> <rel-path> <uid> <N|max:N> <dest>
 *
 *   public-key-file  64 hex digits (32 bytes), optional trailing newline
 *   message-file     the signed bytes, at most 1 MiB (the bundle manifest)
 *   signature-file   128 hex digits (64 bytes), optional trailing newline
 *
 * Exit status: 0 = valid signature, 1 = invalid signature, 2 = usage or I/O error (bad file, too big,
 * not hex, symlink). Prints one line on stderr when it fails. All three files are opened with
 * O_NOFOLLOW and must be regular files.
 *
 * --copy / --move: the one safe way for a root service to take a file the kiosk app wrote. Walks
 * <base-dir>/<rel-path> with openat(O_NOFOLLOW) one component at a time (every directory and the file
 * owned by <uid>; no symlinks anywhere, so a component swapped for a symlink between a shell check and
 * the copy can't redirect it), opens the file O_NONBLOCK (a FIFO can't block us) and checks, on that
 * open fd: regular file, owner <uid>, one link, size exactly N (or at most N with "max:N"). Then copies
 * exactly that many bytes into <dest> (created O_EXCL|O_NOFOLLOW, mode 0600; it must not exist) and
 * prints "<size> <mtime>". --move then unlinks the source through the same verified parent directory
 * (only if the name still refers to the file copied). Exit: 0 copied, 3 the file (or a directory on
 * the way) doesn't exist, 4 refused (stderr says why), 2 usage or I/O error. <dest> is removed on any
 * failure.
 *
 * Crypto: Monocypher (crypto_ed25519_check, constant-time where it matters, CC0/BSD-2-Clause),
 * fetched and hash-checked by tools/root/build-verify.sh. Nothing else is linked except libc (static).
 */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "monocypher-ed25519.h"

#define MAX_MESSAGE (1024 * 1024)
#define MAX_HEX_FILE 256

static int fail(int code, const char *what, const char *path) {
    fprintf(stderr, "s1kiosk_verify: %s%s%s\n", what, path ? ": " : "", path ? path : "");
    return code;
}

/*
 * Reads a whole regular file (no symlinks) of at most max bytes into buf, which must hold max + 1
 * bytes (one more to notice a file that grew past max). Returns its length or -1.
 */
static long read_file(const char *path, uint8_t *buf, long max) {
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size > max) { close(fd); return -1; }
    long n = 0;
    while (n <= max) {
        ssize_t r = read(fd, buf + n, (size_t)(max + 1 - n));
        if (r < 0) { if (errno == EINTR) continue; close(fd); return -1; }
        if (r == 0) break;
        n += r;
    }
    close(fd);
    return n > max ? -1 : n;
}

static int hexval(uint8_t c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Exactly 2*len hex digits, then at most one "\n" (or "\r\n"). */
static int read_hex(const char *path, uint8_t *out, size_t len) {
    uint8_t buf[MAX_HEX_FILE + 1];
    long n = read_file(path, buf, MAX_HEX_FILE);
    if (n < 0) return -1;
    if (n > 0 && buf[n - 1] == '\n') n--;
    if (n > 0 && buf[n - 1] == '\r') n--;
    if ((size_t)n != 2 * len) return -1;
    for (size_t i = 0; i < len; i++) {
        int h = hexval(buf[2 * i]), l = hexval(buf[2 * i + 1]);
        if (h < 0 || l < 0) return -1;
        out[i] = (uint8_t)(h << 4 | l);
    }
    return 0;
}

/* --copy/--move ------------------------------------------------------------------------------ */
static int refuse(const char *why, const char *what) { return fail(4, why, what); }

static int take(int move, const char *base, const char *rel, const char *uid_s, const char *size_s, const char *dest) {
    char *end;
    unsigned long uid = strtoul(uid_s, &end, 10);
    if (!*uid_s || *end) return fail(2, "bad uid", uid_s);
    int at_most = strncmp(size_s, "max:", 4) == 0;
    const char *n_s = at_most ? size_s + 4 : size_s;
    unsigned long long want = strtoull(n_s, &end, 10);
    if (!*n_s || *end) return fail(2, "bad size", size_s);
    if (!*rel || rel[0] == '/' || strlen(rel) > 1024) return fail(2, "bad relative path", rel);

    int dir = open(base, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (dir < 0) return errno == ENOENT ? 3 : refuse("base is missing, a symlink or not a directory", base);
    struct stat st;
    if (fstat(dir, &st) != 0 || st.st_uid != uid) { close(dir); return refuse("base is not owned by the uid", base); }

    char path[1025];
    strcpy(path, rel);
    char *name = path, *slash;
    while ((slash = strchr(name, '/')) != NULL) {
        *slash = 0;
        if (!*name || !strcmp(name, ".") || !strcmp(name, "..")) { close(dir); return fail(2, "bad path component", rel); }
        int next = openat(dir, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        int e = errno;
        close(dir);
        if (next < 0) return e == ENOENT ? 3 : refuse("a directory on the way is a symlink or not a directory", rel);
        if (fstat(next, &st) != 0 || st.st_uid != uid) { close(next); return refuse("a directory on the way is not owned by the uid", rel); }
        dir = next;
        name = slash + 1;
    }
    if (!*name || !strcmp(name, ".") || !strcmp(name, "..")) { close(dir); return fail(2, "bad file name", rel); }
    int src = openat(dir, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_NOCTTY | O_CLOEXEC);
    if (src < 0) { int e = errno; close(dir); return e == ENOENT ? 3 : refuse("the file is a symlink or can't be opened", rel); }
    if (fstat(src, &st) != 0 || !S_ISREG(st.st_mode)) { close(src); close(dir); return refuse("not a regular file", rel); }
    if (st.st_uid != uid) { close(src); close(dir); return refuse("the file is not owned by the uid", rel); }
    if (st.st_nlink != 1) { close(src); close(dir); return refuse("the file has extra hard links", rel); }
    unsigned long long size = (unsigned long long)st.st_size;
    if (at_most ? size > want : size != want) { close(src); close(dir); return refuse("the file has the wrong size", rel); }

    int out = open(dest, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (out < 0) { close(src); close(dir); return fail(2, "cannot create the destination", dest); }
    static uint8_t buf[65536];
    unsigned long long left = size;
    int bad = 0;
    while (left > 0 && !bad) {
        ssize_t r = read(src, buf, left < sizeof buf ? (size_t)left : sizeof buf);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) { bad = 1; break; }   /* error, or shorter than fstat said: changed under us */
        for (ssize_t w = 0; w < r;) {
            ssize_t k = write(out, buf + w, (size_t)(r - w));
            if (k < 0 && errno == EINTR) continue;
            if (k <= 0) { bad = 1; break; }
            w += k;
        }
        left -= (unsigned long long)r;
    }
    if (!bad && fsync(out) != 0) bad = 1;
    if (close(out) != 0) bad = 1;
    struct stat src_st = st;
    close(src);
    if (bad) { unlink(dest); close(dir); return fail(2, "copy failed (the file changed while copying?)", rel); }
    if (move) {
        struct stat now;
        if (fstatat(dir, name, &now, AT_SYMLINK_NOFOLLOW) == 0 && now.st_ino == src_st.st_ino && now.st_dev == src_st.st_dev)
            unlinkat(dir, name, 0);
    }
    close(dir);
    printf("%llu %lld\n", size, (long long)src_st.st_mtime);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 7 && (!strcmp(argv[1], "--copy") || !strcmp(argv[1], "--move")))
        return take(!strcmp(argv[1], "--move"), argv[2], argv[3], argv[4], argv[5], argv[6]);
    if (argc != 4) {
        fprintf(stderr, "usage: s1kiosk_verify <public-key-file> <message-file> <signature-file>\n"
                        "       s1kiosk_verify --copy|--move <base-dir> <rel-path> <uid> <N|max:N> <dest>\n");
        return 2;
    }
    uint8_t pub[32], sig[64];
    if (read_hex(argv[1], pub, sizeof pub) != 0) return fail(2, "bad public key file", argv[1]);
    if (read_hex(argv[3], sig, sizeof sig) != 0) return fail(2, "bad signature file", argv[3]);
    uint8_t *msg = malloc(MAX_MESSAGE + 1);
    if (!msg) return fail(2, "out of memory", NULL);
    long n = read_file(argv[2], msg, MAX_MESSAGE);
    if (n < 0) { free(msg); return fail(2, "bad message file (missing, not a regular file, or over 1 MiB)", argv[2]); }
    int bad = crypto_ed25519_check(sig, pub, msg, (size_t)n);
    free(msg);
    if (bad) return fail(1, "signature does not match", argv[2]);
    return 0;
}
