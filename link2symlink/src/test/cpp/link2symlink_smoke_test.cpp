#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

// Include the implementation so its file-local path helper can be tested.
// Rename the public interception symbols to avoid colliding with libc.
#define link zenlabs_test_link
#define linkat zenlabs_test_linkat
#include "../../main/cpp/link2symlink.cpp"
#undef link
#undef linkat

static int fail(const char *message) {
    std::fprintf(stderr, "link2symlink smoke test failed: %s (errno=%d: %s)\n",
                 message, errno, std::strerror(errno));
    return 1;
}

int main() {
    char directory[] = "/tmp/zenlabs-link2symlink-XXXXXX";
    if (mkdtemp(directory) == nullptr) {
        return fail("mkdtemp");
    }
    if (chdir(directory) != 0) {
        return fail("chdir");
    }

    const int source_fd = open("source.txt", O_CREAT | O_EXCL | O_WRONLY, 0600);
    if (source_fd < 0) {
        return fail("open source");
    }
    if (write(source_fd, "test", 4) != 4) {
        close(source_fd);
        return fail("write source");
    }
    close(source_fd);

    char directory_absolute[PATH_MAX];
    if (getcwd(directory_absolute, sizeof(directory_absolute)) == nullptr) {
        return fail("getcwd");
    }
    char expected[PATH_MAX];
    std::snprintf(expected, sizeof(expected), "%s/source.txt", directory_absolute);

    if (zenlabs_test_link("source.txt", "source-link") != 0) {
        return fail("link");
    }

    struct stat link_stat {};
    if (lstat("source-link", &link_stat) != 0) {
        return fail("lstat source-link");
    }
    if (!S_ISLNK(link_stat.st_mode)) {
        std::fprintf(stderr, "link2symlink smoke test failed: source-link is not a symlink\n");
        return 1;
    }

    char actual[PATH_MAX];
    const ssize_t actual_length = readlink("source-link", actual, sizeof(actual) - 1);
    if (actual_length < 0) {
        return fail("readlink source-link");
    }
    actual[actual_length] = '\0';
    if (std::strcmp(actual, expected) != 0) {
        std::fprintf(stderr, "link2symlink smoke test failed: target '%s' != '%s'\n",
                     actual, expected);
        return 1;
    }

    if (zenlabs_test_linkat(AT_FDCWD, "source.txt", AT_FDCWD, "source-linkat", 0) != 0) {
        return fail("linkat");
    }
    if (lstat("source-linkat", &link_stat) != 0 || !S_ISLNK(link_stat.st_mode)) {
        return fail("lstat source-linkat");
    }
    const ssize_t linkat_length = readlink("source-linkat", actual, sizeof(actual) - 1);
    if (linkat_length < 0) {
        return fail("readlink source-linkat");
    }
    actual[linkat_length] = '\0';
    if (std::strcmp(actual, expected) != 0) {
        std::fprintf(stderr, "link2symlink smoke test failed: linkat target '%s' != '%s'\n",
                     actual, expected);
        return 1;
    }

    char resolved[PATH_MAX];
    if (resolve_to_absolute(AT_FDCWD, "child.txt", 0, resolved) != 0) {
        return fail("resolve relative path");
    }
    char expected_resolved[PATH_MAX];
    std::snprintf(expected_resolved, sizeof(expected_resolved), "%s/child.txt", directory_absolute);
    if (std::strcmp(resolved, expected_resolved) != 0) {
        std::fprintf(stderr, "link2symlink smoke test failed: resolved '%s' != '%s'\n",
                     resolved, expected_resolved);
        return 1;
    }

    std::remove("source-link");
    std::remove("source-linkat");
    std::remove("source.txt");
    if (chdir("/") != 0) {
        return fail("restore cwd");
    }
    rmdir(directory);
    return 0;
}
