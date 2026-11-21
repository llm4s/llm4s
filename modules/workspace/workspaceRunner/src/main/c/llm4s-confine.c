/*
 * llm4s-confine: runs one workspace command under the runner's sandbox (design 4.39, #1983).
 *
 *   llm4s-confine --status FILE [--max-abi N] [--rx P|--ro P|--rw P|--rwx P|--wfile P]... [--no-net] -- /abs/program args...
 *   llm4s-confine --report [--max-abi N]
 *
 * Installs a Landlock ruleset (ABI v3 or later) granting only the listed paths, sets no_new_privs, installs a seccomp
 * filter refusing non-Unix sockets when --no-net is given, then execs the program in its own place: same pid, session
 * and process group, so the runner's kill still finds it. Every process the program starts inherits all of it.
 *
 * From ABI v6 (kernel 6.12) the ruleset also scopes abstract Unix sockets and signals (#2009): the program can connect
 * only to abstract sockets made inside its own domain, and signal only processes inside it, so not the runner. Below
 * v6 it handles filesystem rights only, as before. --report prints the protections this kernel gets, one line, and
 * exits 0; --max-abi treats the kernel's ABI as at most N, for tests of an older kernel's behaviour (it can only
 * weaken this helper's own ruleset, never one already applied).
 *
 * Threat model: model-issued commands inside the workspace container; the container is the outer boundary. When it
 * cannot confine, it writes why to FILE and stderr and exits 125 without running the program; the runner reports
 * SANDBOX_UNAVAILABLE only when FILE exists, so a program's own exit 125 is not mistaken for it.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/landlock.h>
#include <linux/seccomp.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define UNAVAILABLE 125

static const char *status_path;

static void unavailable(const char *fmt, ...) {
  char msg[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(msg, sizeof msg, fmt, ap);
  va_end(ap);
  fprintf(stderr, "llm4s-confine: unavailable: %s\n", msg);
  if (status_path) {
    int fd = open(status_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd >= 0) {
      ssize_t ignored = write(fd, msg, strlen(msg));
      (void)ignored;
      close(fd);
    }
  }
  exit(UNAVAILABLE);
}

#define FS_READ (LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR)
#define FS_EXEC LANDLOCK_ACCESS_FS_EXECUTE
/* No grant makes a device node: a command could otherwise mknod one into the host-mounted workspace. Both rights
 * stay handled (FS_HANDLED), so they are refused everywhere. */
#define FS_WRITE                                                                                                     \
  (LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REMOVE_FILE |                 \
   LANDLOCK_ACCESS_FS_MAKE_DIR | LANDLOCK_ACCESS_FS_MAKE_REG | LANDLOCK_ACCESS_FS_MAKE_SOCK |                        \
   LANDLOCK_ACCESS_FS_MAKE_FIFO | LANDLOCK_ACCESS_FS_MAKE_SYM | LANDLOCK_ACCESS_FS_REFER |                          \
   LANDLOCK_ACCESS_FS_TRUNCATE)
#define FS_DEVICES (LANDLOCK_ACCESS_FS_MAKE_CHAR | LANDLOCK_ACCESS_FS_MAKE_BLOCK)
#define FS_FILE_ONLY                                                                                                 \
  (LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_EXECUTE |                      \
   LANDLOCK_ACCESS_FS_TRUNCATE)
#define FS_HANDLED (FS_READ | FS_EXEC | FS_WRITE | FS_DEVICES)

/* ABI v6 scopes (#2009). Noble's linux-libc-dev (6.8) has neither the constants nor the attribute's `scoped` field, so
 * both are defined here, as the kernel's uapi has them. */
#ifndef LANDLOCK_SCOPE_ABSTRACT_UNIX_SOCKET
#define LANDLOCK_SCOPE_ABSTRACT_UNIX_SOCKET (1ULL << 0)
#endif
#ifndef LANDLOCK_SCOPE_SIGNAL
#define LANDLOCK_SCOPE_SIGNAL (1ULL << 1)
#endif
#define SCOPES_ABI 6

struct ruleset_attr_v6 {
  __u64 handled_access_fs;
  __u64 handled_access_net;
  __u64 scoped;
};

/* The scopes a ruleset gets under Landlock ABI `abi`: both from v6, none below. */
static __u64 scopes_for(int abi) {
  return abi >= SCOPES_ABI ? (LANDLOCK_SCOPE_ABSTRACT_UNIX_SOCKET | LANDLOCK_SCOPE_SIGNAL) : 0;
}

/* The attribute's size for `abi`: below v6 the size before `scoped`, as the ruleset was made before #2009. */
static size_t ruleset_attr_size(int abi) {
  return abi >= SCOPES_ABI ? sizeof(struct ruleset_attr_v6) : offsetof(struct ruleset_attr_v6, scoped);
}

static void add_rule(int ruleset, const char *path, __u64 access) {
  int fd = open(path, O_PATH | O_CLOEXEC | O_NOFOLLOW);
  if (fd < 0) {
    if (errno == ENOENT) return; /* a path missing now has nothing to grant: skipped (design 4.39) */
    unavailable("cannot open %s: %s", path, strerror(errno));
  }
  struct stat st;
  if (fstat(fd, &st) != 0) unavailable("cannot stat %s: %s", path, strerror(errno));
  if (S_ISLNK(st.st_mode)) { /* never follow: the runner passes real paths, a link here is a race */
    close(fd);
    return;
  }
  if (!S_ISDIR(st.st_mode)) access &= FS_FILE_ONLY;
  struct landlock_path_beneath_attr attr = {.allowed_access = access, .parent_fd = fd};
  if (syscall(SYS_landlock_add_rule, ruleset, LANDLOCK_RULE_PATH_BENEATH, &attr, 0) != 0)
    unavailable("cannot grant %s: %s", path, strerror(errno));
  close(fd);
}

#if defined(__x86_64__)
#define NATIVE_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define NATIVE_ARCH AUDIT_ARCH_AARCH64
#endif

static void deny_network(void) {
#ifndef NATIVE_ARCH
  unavailable("--no-net is not supported on this architecture");
#else
  struct sock_filter filter[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NATIVE_ARCH, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS), /* a foreign ABI (i386 socketcall) */
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
#if defined(__x86_64__)
    BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000 /* __X32_SYSCALL_BIT */, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS), /* the x32 ABI */
#endif
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_io_uring_setup, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),                     /* io_uring opens sockets itself */
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 2, 0),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socketpair, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),                             /* any other call */
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])), /* socket(pair): the domain */
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
  };
  struct sock_fprog prog = {.len = sizeof filter / sizeof filter[0], .filter = filter};
  if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &prog) != 0)
    unavailable("cannot install the network filter: %s", strerror(errno));
#endif
}

/* `--max-abi N` at argv[i], if there: N, and i moved past it; else INT_MAX. */
static int max_abi(int argc, char **argv, int *i) {
  if (*i >= argc || strcmp(argv[*i], "--max-abi") != 0) return INT_MAX;
  if (*i + 1 >= argc) unavailable("--max-abi needs a number");
  char *end;
  long n = strtol(argv[*i + 1], &end, 10);
  if (*argv[*i + 1] == '\0' || *end != '\0' || n < 1 || n > INT_MAX) unavailable("bad --max-abi %s", argv[*i + 1]);
  *i += 2;
  return (int)n;
}

/* The ABI this run uses: the kernel's, capped at `cap`; refused below v3. */
static int landlock_abi(int cap) {
  int abi = (int)syscall(SYS_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
  if (abi < 0) unavailable("Landlock is not available in this kernel: %s", strerror(errno));
  if (abi > cap) abi = cap;
  if (abi < 3) unavailable("Landlock ABI %d is below the v3 this sandbox needs (kernel 6.2)", abi);
  return abi;
}

/* One line naming what a command gets here; the runner logs it at startup. */
static int report(int abi) {
  printf("landlock-abi=%d scopes=%s\n", abi, scopes_for(abi) == 0 ? "none" : "abstract-unix-socket,signal");
  return 0;
}

int main(int argc, char **argv) {
  int i = 1, no_net = 0;
  if (argc > 1 && strcmp(argv[1], "--report") == 0) {
    i = 2;
    int cap = max_abi(argc, argv, &i);
    if (i != argc) unavailable("usage: llm4s-confine --report [--max-abi N]");
    return report(landlock_abi(cap));
  }
  if (argc > 2 && strcmp(argv[1], "--status") == 0) {
    status_path = argv[2];
    i = 3;
  } else {
    unavailable("usage: llm4s-confine --status FILE [--max-abi N] [grants] [--no-net] -- program args");
  }

  int abi = landlock_abi(max_abi(argc, argv, &i));

  struct ruleset_attr_v6 ra = {.handled_access_fs = FS_HANDLED, .scoped = scopes_for(abi)};
  int ruleset = (int)syscall(SYS_landlock_create_ruleset, &ra, ruleset_attr_size(abi), 0);
  if (ruleset < 0) unavailable("cannot create the Landlock ruleset: %s", strerror(errno));

  for (; i < argc && strcmp(argv[i], "--") != 0; i++) {
    const char *opt = argv[i];
    if (strcmp(opt, "--no-net") == 0) {
      no_net = 1;
      continue;
    }
    if (i + 1 >= argc) unavailable("%s needs a path", opt);
    const char *path = argv[++i];
    if (strcmp(opt, "--rx") == 0) add_rule(ruleset, path, FS_READ | FS_EXEC);
    else if (strcmp(opt, "--ro") == 0) add_rule(ruleset, path, FS_READ);
    else if (strcmp(opt, "--rw") == 0) add_rule(ruleset, path, FS_READ | FS_WRITE);
    else if (strcmp(opt, "--rwx") == 0) add_rule(ruleset, path, FS_READ | FS_WRITE | FS_EXEC);
    else if (strcmp(opt, "--wfile") == 0)
      add_rule(ruleset, path, LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_TRUNCATE);
    else unavailable("unknown option %s", opt);
  }
  if (i >= argc || i + 1 >= argc) unavailable("no program after --");
  char **program = &argv[i + 1];

  /* Every unavailable() comes before landlock_restrict_self: once the ruleset applies, the status file's directory
   * (never granted) cannot be written, and a 125 without it would read as the program's own. The network filter
   * does not block the Landlock calls, so it goes first. */
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) unavailable("cannot set no_new_privs: %s", strerror(errno));
  if (no_net) deny_network();
  if (syscall(SYS_landlock_restrict_self, ruleset, 0) != 0)
    unavailable("cannot enforce the Landlock ruleset: %s", strerror(errno));
  close(ruleset);

  execv(program[0], program);
  int err = errno; /* fprintf may change errno */
  fprintf(stderr, "llm4s-confine: cannot run %s: %s\n", program[0], strerror(err));
  return err == ENOENT ? 127 : 126; /* the program's own failure to start, as a shell reports it: not 125 */
}
