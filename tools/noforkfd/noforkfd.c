/*
 * libkdx-noforkfd: lets Qt 6 programs start child processes under proot.
 *
 * Qt 6's QProcess (forkfd) uses Linux "pidfd" process handles when the kernel has them – clone(CLONE_PIDFD) and
 * waitid(P_PIDFD). proot, which runs DroidSystem on Android, traces programs with ptrace and doesn't handle those,
 * so every child process Qt starts fails silently (KDE Plasma: "$DISPLAY is not set or cannot connect to the X
 * server", sessions that never finish starting). Qt probes for pidfd support with waitid(P_PIDFD, …); this
 * library, preloaded into DroidSystem's programs, answers that probe (and pidfd_open/clone3) with ENOSYS – "not
 * supported" – so Qt falls back to its classic fork() path, which proot handles. Everything else passes through.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdarg.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef P_PIDFD
#define P_PIDFD 3
#endif

int waitid(idtype_t idtype, id_t id, siginfo_t *info, int options)
{
    if ((int)idtype == P_PIDFD) { errno = ENOSYS; return -1; }
    static int (*real)(idtype_t, id_t, siginfo_t *, int);
    if (!real) real = (int (*)(idtype_t, id_t, siginfo_t *, int))dlsym(RTLD_NEXT, "waitid");
    return real(idtype, id, info, options);
}

#ifdef KDX_VFORK_TO_FORK
pid_t vfork(void) { return fork(); }
#endif

long syscall(long number, ...)
{
    va_list ap;
    long a[6];
    va_start(ap, number);
    for (int i = 0; i < 6; i++) a[i] = va_arg(ap, long);
    va_end(ap);

    if ((number == SYS_waitid && a[0] == P_PIDFD)
#ifdef SYS_pidfd_open
        || number == SYS_pidfd_open
#endif
#ifdef SYS_clone3
        || number == SYS_clone3
#endif
    ) {
        errno = ENOSYS;
        return -1;
    }

    static long (*real)(long, ...);
    if (!real) real = (long (*)(long, ...))dlsym(RTLD_NEXT, "syscall");
    return real(number, a[0], a[1], a[2], a[3], a[4], a[5]);
}
