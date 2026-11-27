/*
 * What a crash in a driver leaves for the logs, for the driver check and the speed test: where it happened, by
 * library and offset (glibc can't name the Android libraries libhybris loads), what the code called last, its
 * registers and its instructions. Then the program exits with 128 + the signal (DriverCheck.kt and SpeedTest.kt read
 * these). Included once, by a program that defines _GNU_SOURCE first.
 */
#ifndef _GNU_SOURCE
#error "crash.h needs _GNU_SOURCE, defined before any include"
#endif
#include <dlfcn.h>
#include <execinfo.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <ucontext.h>
#include <unistd.h>

/* The program's name, before each line it writes. */
static const char *crash_program = "crash";

/* From /proc/self/maps: glibc can't name Android's libraries, which libhybris loads. */
static void where(const char *what, uintptr_t address)
{
    FILE *maps = fopen("/proc/self/maps", "r");
    char line[512], path[400];
    unsigned long start, end, offset;
    while (maps && fgets(line, sizeof(line), maps)) {
        path[0] = 0;
        if (sscanf(line, "%lx-%lx %*s %lx %*s %*s %399s", &start, &end, &offset, path) >= 3 &&
            address >= start && address < end) {
            printf("%s: %s %s+0x%lx\n", crash_program, what, path[0] ? path : "(anonymous)", address - start + offset);
            fclose(maps);
            return;
        }
    }
    printf("%s: %s 0x%lx, mapped nowhere\n", crash_program, what, (unsigned long)address);
    if (maps) fclose(maps);
}

#if defined(__aarch64__)
/* Whether [address] can be read: a crashed driver's registers may hold anything. */
static int readable(uintptr_t address)
{
    FILE *maps = fopen("/proc/self/maps", "r");
    char line[512], perms[8];
    unsigned long start, end;
    int found = 0;
    while (!found && maps && fgets(line, sizeof(line), maps))
        found = sscanf(line, "%lx-%lx %7s", &start, &end, perms) == 3 && address >= start && address + 8 <= end &&
                perms[0] == 'r';
    if (maps) fclose(maps);
    return found;
}

/*
 * The function [address] is in, by name, if its library names it: libhybris knows the Android libraries it loaded,
 * glibc the rest. A driver's own functions are mostly unnamed, the ones it calls in other libraries aren't.
 */
static void name(const char *what, uintptr_t address)
{
    Dl_info info = { 0 };
    void *hybris = dlopen("libhybris-common.so.1", RTLD_LAZY | RTLD_NOLOAD);
    int (*android_dladdr)(const void *, void *) =
        hybris ? (int (*)(const void *, void *))dlsym(hybris, "android_dladdr") : NULL;
    if ((android_dladdr && android_dladdr((const void *)address, &info) && info.dli_sname) ||
        (dladdr((const void *)address, &info) && info.dli_sname))
        printf("%s: %s %s+0x%lx\n", crash_program, what, info.dli_sname, (unsigned long)(address - (uintptr_t)info.dli_saddr));
}

/*
 * What the code that crashed called last, from the instruction before x30: a bl to one of its own functions or to a
 * PLT entry, whose slot holds the library function it went to, or a blr through a register. A driver that reads 0x0
 * just after a call usually got NULL from it.
 */
static void last_call(const mcontext_t *state)
{
    uintptr_t back = state->regs[30];
    if (!readable(back - 4)) return;
    uint32_t call = *(const uint32_t *)(back - 4);
    uintptr_t target;
    if ((call & 0xFC000000) == 0x94000000) target = back - 4 + (uintptr_t)((int64_t)((int32_t)(call << 6) >> 6) * 4);
    else if ((call & 0xFFFFFC1F) == 0xD63F0000) target = state->regs[(call >> 5) & 31];
    else return;
    where("which called", target);
    name("which is", target);
    // adrp x16, page; ldr x17, [x16, #offset]; ... br x17
    if (!readable(target)) return;
    uint32_t adrp = ((const uint32_t *)target)[0], ldr = ((const uint32_t *)target)[1];
    if ((adrp & 0x9F00001F) != 0x90000010 || (ldr & 0xFFC003FF) != 0xF9400211) return;
    uint64_t page = ((adrp >> 5) & 0x7FFFF) << 2 | ((adrp >> 29) & 3);
    uintptr_t slot = (target & ~(uintptr_t)0xFFF) + (uintptr_t)((int64_t)(page << 43) >> 31) + ((ldr >> 10) & 0xFFF) * 8;
    if (!readable(slot)) return;
    uintptr_t real = *(const uintptr_t *)slot;
    where("through its PLT, to", real);
    name("which is", real);
}

/* The registers, and the instructions up to the one that crashed and the next, for a disassembler. */
static void registers_and_code(const mcontext_t *state)
{
    for (int first = 0; first < 31; first += 4) {
        printf("%s:", crash_program);
        for (int r = first; r < first + 4 && r < 31; r++) printf(" x%d=%lx", r, (unsigned long)state->regs[r]);
        printf("\n");
    }
    printf("%s: sp=%lx\n", crash_program, (unsigned long)state->sp);
    uintptr_t from = state->pc - 0x40;
    if (!readable(from) || !readable(state->pc)) return;
    where("code from", from);
    printf("%s: code", crash_program);
    for (uintptr_t at = from; at <= state->pc + 4; at += 4) printf(" %08x", *(const uint32_t *)at);
    printf("\n");
}
#endif

static void crashed(int signal, siginfo_t *info, void *context)
{
    printf("%s: crashed with signal %d, touching 0x%lx\n", crash_program, signal, (unsigned long)info->si_addr);
#if defined(__aarch64__)
    const ucontext_t *state = context;
    where("in", state->uc_mcontext.pc);
    name("which is", state->uc_mcontext.pc);
    where("called from", state->uc_mcontext.regs[30]);
    last_call(&state->uc_mcontext);
    registers_and_code(&state->uc_mcontext);
#endif
    fflush(stdout);
    void *frames[32];
    backtrace_symbols_fd(frames, backtrace(frames, 32), STDOUT_FILENO);
    _exit(128 + signal);
}

/* Every fatal signal to crashed(), its lines starting with [program]. */
static void on_crash(const char *program)
{
    crash_program = program;
    // backtrace() loads its unwinder on first use: here, rather than inside the handler.
    void *first[1];
    backtrace(first, 1);
    static const int fatal[] = { SIGSEGV, SIGBUS, SIGILL, SIGABRT, SIGFPE };
    struct sigaction handler = { .sa_sigaction = crashed, .sa_flags = SA_SIGINFO };
    for (size_t i = 0; i < sizeof(fatal) / sizeof(fatal[0]); i++) sigaction(fatal[i], &handler, NULL);
}
