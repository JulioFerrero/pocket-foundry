/*
 * The game's biggest blocks of memory in files while the phone runs short (LD_PRELOAD, Session).
 *
 * Android counts an app's anonymous memory and closes the app when the phone runs short, while pages of a file it
 * can write back and drop, then read again when they're touched. Loading sprites, Factorio passes ~11 GB through
 * blocks of 1 MB and more in 20 s (the base game, high resolution), 90% of it gone within a second; most of the peak
 * is a few blocks of 64 MB and more (the atlases it assembles, it seems), which live for seconds. So while the
 * phone's available memory is below POCKETFOUNDRY_FILEMEM_LOW_MB (an eighth of the RAM, 512 MB to 1.5 GB, unless
 * set), new blocks of POCKETFOUNDRY_FILEMEM_MIN_KB or more (64 MB unless set) are shared mappings of sparse, unlinked
 * files in POCKETFOUNDRY_FILEMEM_DIR, written back at once and marked first to go: the kernel drops them instead of
 * closing the app. Everything else, and everything on a phone with room, is glibc's. On a vivo X200 Ultra, forced:
 * 534 MB more available while sprites loaded, 3.7 GB written instead of 18 GB for all blocks, 20.6 s instead of 14.7.
 *
 * The blocks live in one reserved range of addresses, cut in shards of a file each: a block's offset in its shard's
 * file is its offset in the shard, so free() knows its own by the address alone. Threads take turns at the shards,
 * so pages fault in parallel (one file serialises them). A freed block is punched out of its file, unwritten
 * (POCKETFOUNDRY_FILEMEM_CACHE_MB keeps that much mapped for a second, for reuse). New blocks go to fresh addresses
 * first: refilling a stretch just punched out is slow.
 *
 * A child the game forks gets none of the blocks (they're shared mappings, which it would write through): the fork
 * handler puts the reservation back over them in the child.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/falloc.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#ifndef MADV_COLD
#define MADV_COLD 20
#endif

void *__libc_malloc(size_t size);
void __libc_free(void *ptr);
void *__libc_calloc(size_t count, size_t size);
void *__libc_realloc(void *ptr, size_t size);
void *__libc_memalign(size_t alignment, size_t size);

#define MAGIC 0x50464d454d424c4bULL
/** Addresses for the blocks: reserved, not memory. */
#define SPAN (64ULL << 30)
#define SHARDS 8
/** Free stretches of a shard, kept merged. */
#define EXTENTS 8192
/** Freed blocks kept mapped, per shard, up to POCKETFOUNDRY_FILEMEM_CACHE_MB (none unless set) and for CACHE_NS. */
#define CACHED 32
#define CACHE_NS 1000000000LL

/** Before the block's first byte, a page of its own: blocks are page-aligned and free() finds their length. */
struct block {
    uint64_t magic;
    size_t length;   /* mapped, this page included */
    size_t usable;
};

struct extent {
    size_t offset, length;
};

struct cached {
    size_t offset, length;
    long long freed_at;
};

struct shard {
    char *base;
    int fd;
    pthread_mutex_t lock;
    size_t top;   /* everything from here to the shard's end is untouched */
    struct extent extents[EXTENTS];
    size_t extent_count;
    struct cached cache[CACHED];
    size_t cache_count, cache_bytes;
};

static char *base;
static size_t span, shard_span, page, min_size, low_kb, cache_max;
static struct shard *shards;
static atomic_int ready;
/** The phone is short of memory: big blocks go to the files (watch). */
static atomic_int spilling;
static atomic_uint next_shard;
static __thread int my_shard = -1;
static int forked;
static size_t (*glibc_usable_size)(void *);
static atomic_size_t in_use, peak, blocks, taken_total;

static int ours(const void *ptr) {
    return base && (const char *) ptr >= base && (const char *) ptr < base + span;
}

static struct shard *shard_of(const void *ptr) {
    return &shards[((const char *) ptr - base) / shard_span];
}

static size_t round_up(size_t size, size_t to) {
    return (size + to - 1) / to * to;
}

static long long now_ns(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec * 1000000000LL + time.tv_nsec;
}

static void say(const char *format, ...) __attribute__((format(printf, 1, 2)));
static void say(const char *format, ...) {
    char line[256];
    va_list args;
    va_start(args, format);
    int length = vsnprintf(line, sizeof line, format, args);
    va_end(args);
    if (length > 0) write(STDERR_FILENO, line, (size_t) length < sizeof line ? (size_t) length : sizeof line - 1);
}

static void count(size_t length) {
    size_t now = atomic_fetch_add(&in_use, length) + length;
    size_t was = atomic_load(&peak);
    while (now > was && !atomic_compare_exchange_weak(&peak, &was, now)) {}
}

/** Back to a reservation, which nothing else can be mapped into. */
static void reserve(char *at, size_t length) {
    mmap(at, length, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED | MAP_NORESERVE, -1, 0);
}

/** [offset, length) of [shard] is free again: sorted in, merged with its neighbours, or back into the untouched end. */
static void give_back(struct shard *shard, size_t offset, size_t length) {
    pthread_mutex_lock(&shard->lock);
    struct extent *extents = shard->extents;
    size_t at = 0, high = shard->extent_count;
    while (at < high) {
        size_t middle = (at + high) / 2;
        if (extents[middle].offset < offset) at = middle + 1;
        else high = middle;
    }
    int after_previous = at > 0 && extents[at - 1].offset + extents[at - 1].length == offset;
    int before_next = at < shard->extent_count && offset + length == extents[at].offset;
    if (after_previous && before_next) {
        extents[at - 1].length += length + extents[at].length;
        memmove(&extents[at], &extents[at + 1], (shard->extent_count - at - 1) * sizeof *extents);
        shard->extent_count--;
        at--;
    } else if (after_previous) {
        extents[at - 1].length += length;
        at--;
    } else if (before_next) {
        extents[at].offset = offset;
        extents[at].length += length;
    } else if (shard->extent_count < EXTENTS) {
        memmove(&extents[at + 1], &extents[at], (shard->extent_count - at) * sizeof *extents);
        extents[at] = (struct extent) {offset, length};
        shard->extent_count++;
    } else {
        /* Too scattered to keep track of: the addresses are lost, the file's pages were punched out already. */
        pthread_mutex_unlock(&shard->lock);
        return;
    }
    if (at == shard->extent_count - 1 && extents[at].offset + extents[at].length == shard->top) {
        shard->top = extents[at].offset;
        shard->extent_count--;
    }
    pthread_mutex_unlock(&shard->lock);
}

/** A freed block's pages leave the file unwritten, and the stretch reads as zeros when it's given out again. */
static void punch(struct shard *shard, size_t offset, size_t length) {
    reserve(shard->base + offset, length);
    fallocate(shard->fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, (off_t) offset, (off_t) length);
    give_back(shard, offset, length);
}

static struct block *start_block(char *at, size_t length) {
    struct block *block = (struct block *) at;
    *block = (struct block) {MAGIC, length, length - page};
    count(length);
    atomic_fetch_add(&taken_total, length);
    atomic_fetch_add(&blocks, 1);
    return block;
}

/** Takes [length] bytes of [shard]'s addresses, fresh ones first; SIZE_MAX when there's no room. Locked. */
static size_t claim(struct shard *shard, size_t length) {
    if (length <= shard_span - shard->top) {
        size_t offset = shard->top;
        shard->top += length;
        return offset;
    }
    for (size_t i = 0; i < shard->extent_count; i++) {
        struct extent *stretch = &shard->extents[i];
        if (stretch->length < length) continue;
        size_t offset = stretch->offset;
        stretch->offset += length;
        stretch->length -= length;
        if (stretch->length == 0) {
            memmove(stretch, stretch + 1, (shard->extent_count - i - 1) * sizeof *stretch);
            shard->extent_count--;
        }
        return offset;
    }
    return SIZE_MAX;
}

/** A block of at least [size] bytes, page-aligned, [zero]ed if asked; NULL when [shard] has no room. */
static void *take_from(struct shard *shard, size_t size, int zero) {
    size_t length = round_up(size, page) + page;
    if (length < size) return NULL;
    pthread_mutex_lock(&shard->lock);
    /* A freed block of about this size, still mapped. */
    for (size_t i = shard->cache_count; i-- > 0;) {
        struct cached kept = shard->cache[i];
        if (kept.length < length || kept.length > length * 2) continue;
        memmove(&shard->cache[i], &shard->cache[i + 1], (shard->cache_count - i - 1) * sizeof *shard->cache);
        shard->cache_count--;
        shard->cache_bytes -= kept.length;
        pthread_mutex_unlock(&shard->lock);
        struct block *block = start_block(shard->base + kept.offset, kept.length);
        if (zero) memset((char *) block + page, 0, size);
        return (char *) block + page;
    }
    size_t offset = claim(shard, length);
    pthread_mutex_unlock(&shard->lock);
    if (offset == SIZE_MAX) return NULL;
    char *at = shard->base + offset;
    if (mmap(at, length, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, shard->fd, (off_t) offset) == MAP_FAILED) {
        reserve(at, length);
        give_back(shard, offset, length);
        return NULL;
    }
    return (char *) start_block(at, length) + page;
}

static void *take(size_t size, int zero) {
    if (my_shard < 0) my_shard = (int) (atomic_fetch_add(&next_shard, 1) % SHARDS);
    for (int i = 0; i < SHARDS; i++) {
        void *ptr = take_from(&shards[(my_shard + i) % SHARDS], size, zero);
        if (ptr) return ptr;
    }
    return NULL;
}

static struct block *block_of(void *ptr) {
    struct block *block = (struct block *) ((char *) ptr - page);
    if (block->magic != MAGIC) {
        say("filemem: %p was never given out, or freed twice\n", ptr);
        abort();
    }
    return block;
}

static void release(void *ptr) {
    if (forked) return;   /* the parent's, not mapped here */
    struct block *block = block_of(ptr);
    struct shard *shard = shard_of(block);
    size_t offset = (size_t) ((char *) block - shard->base), length = block->length;
    block->magic = 0;
    atomic_fetch_sub(&in_use, length);
    atomic_fetch_sub(&blocks, 1);
    pthread_mutex_lock(&shard->lock);
    int kept = shard->cache_count < CACHED && shard->cache_bytes + length <= cache_max;
    if (kept) {
        shard->cache[shard->cache_count++] = (struct cached) {offset, length, now_ns()};
        shard->cache_bytes += length;
    }
    pthread_mutex_unlock(&shard->lock);
    if (!kept) punch(shard, offset, length);
}

/** [block] takes in the free addresses right after it, up to [size] usable bytes: no copy. */
static int grow(struct block *block, size_t size) {
    size_t length = round_up(size, page) + page;
    if (length < size) return 0;
    struct shard *shard = shard_of(block);
    size_t more = length - block->length, end = (size_t) ((char *) block - shard->base) + block->length;
    int taken = 0;
    pthread_mutex_lock(&shard->lock);
    if (shard->top == end && more <= shard_span - shard->top) {
        shard->top += more;
        taken = 1;
    } else {
        for (size_t i = 0; i < shard->extent_count && shard->extents[i].offset <= end; i++) {
            struct extent *stretch = &shard->extents[i];
            if (stretch->offset != end || stretch->length < more) continue;
            stretch->offset += more;
            stretch->length -= more;
            if (stretch->length == 0) {
                memmove(stretch, stretch + 1, (shard->extent_count - i - 1) * sizeof *stretch);
                shard->extent_count--;
            }
            taken = 1;
            break;
        }
    }
    pthread_mutex_unlock(&shard->lock);
    if (!taken) return 0;
    if (mmap(shard->base + end, more, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, shard->fd, (off_t) end) ==
        MAP_FAILED) {
        reserve(shard->base + end, more);
        give_back(shard, end, more);
        return 0;
    }
    block->length = length;
    block->usable = length - page;
    count(more);
    atomic_fetch_add(&taken_total, more);
    return 1;
}

static int big(size_t size) {
    return size >= min_size && atomic_load_explicit(&spilling, memory_order_relaxed) &&
           atomic_load_explicit(&ready, memory_order_acquire) && !forked;
}

void *malloc(size_t size) {
    if (big(size)) {
        void *ptr = take(size, 0);
        if (ptr) return ptr;
    }
    return __libc_malloc(size);
}

void free(void *ptr) {
    if (ours(ptr)) release(ptr);
    else __libc_free(ptr);
}

void *calloc(size_t count, size_t size) {
    size_t total;
    if (__builtin_mul_overflow(count, size, &total)) {
        errno = ENOMEM;
        return NULL;
    }
    if (big(total)) {
        void *ptr = take(total, 1);
        if (ptr) return ptr;
    }
    return __libc_calloc(count, size);
}

size_t malloc_usable_size(void *ptr) {
    if (ours(ptr)) return forked ? 0 : block_of(ptr)->usable;
    return ptr && glibc_usable_size ? glibc_usable_size(ptr) : 0;
}

void *realloc(void *ptr, size_t size) {
    if (!ptr) return malloc(size);
    if (!ours(ptr)) {
        if (!big(size)) return __libc_realloc(ptr, size);
        void *moved = take(size, 0);
        if (!moved) return __libc_realloc(ptr, size);
        size_t had = glibc_usable_size ? glibc_usable_size(ptr) : 0;
        memcpy(moved, ptr, had < size ? had : size);
        __libc_free(ptr);
        return moved;
    }
    if (size == 0) {
        free(ptr);
        return NULL;
    }
    struct block *block = block_of(ptr);
    size_t usable = block->usable;
    if (size <= usable && size >= min_size) return ptr;
    if (size > usable && !forked && grow(block, size)) return ptr;
    void *moved = malloc(size);
    if (!moved) return NULL;
    memcpy(moved, ptr, usable < size ? usable : size);
    free(ptr);
    return moved;
}

void *reallocarray(void *ptr, size_t count, size_t size) {
    size_t total;
    if (__builtin_mul_overflow(count, size, &total)) {
        errno = ENOMEM;
        return NULL;
    }
    return realloc(ptr, total);
}

void *memalign(size_t alignment, size_t size) {
    if (alignment <= page && big(size)) {
        void *ptr = take(size, 0);
        if (ptr) return ptr;
    }
    return __libc_memalign(alignment, size);
}

void *aligned_alloc(size_t alignment, size_t size) {
    return memalign(alignment, size);
}

int posix_memalign(void **result, size_t alignment, size_t size) {
    if (alignment < sizeof(void *) || (alignment & (alignment - 1))) return EINVAL;
    void *ptr = memalign(alignment, size);
    if (!ptr) return ENOMEM;
    *result = ptr;
    return 0;
}

void *valloc(size_t size) {
    return memalign(page ? page : 4096, size);
}

void *pvalloc(size_t size) {
    size_t to = page ? page : 4096;
    return memalign(to, round_up(size, to));
}

/** A field of /proc/meminfo in kB, or -1. */
static long meminfo(const char *name) {
    char text[2048];
    int file = open("/proc/meminfo", O_RDONLY | O_CLOEXEC);
    if (file < 0) return -1;
    ssize_t length = read(file, text, sizeof text - 1);
    close(file);
    if (length <= 0) return -1;
    text[length] = 0;
    char *at = strstr(text, name);
    return at ? strtol(at + strlen(name), NULL, 10) : -1;
}

/** Punches out the freed blocks kept long enough, or all of them when [all]. */
static void forget(int all) {
    long long now = now_ns();
    for (int s = 0; s < SHARDS; s++) {
        struct shard *shard = &shards[s];
        struct cached old[CACHED];
        size_t count = 0, kept = 0;
        pthread_mutex_lock(&shard->lock);
        for (size_t i = 0; i < shard->cache_count; i++) {
            struct cached block = shard->cache[i];
            if (all || now - block.freed_at >= CACHE_NS) {
                old[count++] = block;
                shard->cache_bytes -= block.length;
            } else {
                shard->cache[kept++] = block;
            }
        }
        shard->cache_count = kept;
        pthread_mutex_unlock(&shard->lock);
        for (size_t i = 0; i < count; i++) punch(shard, old[i].offset, old[i].length);
    }
}

/**
 * Turns the files on while the phone is short of memory (off again a quarter above the mark, so it doesn't flicker),
 * writes their blocks back meanwhile, lets go of freed blocks kept a second, and says what it does in game.log.
 */
static void *watch(void *unused) {
    (void) unused;
    unsigned ticks = 0;
    for (;;) {
        usleep(100 * 1000);
        ticks++;
        long available = meminfo("MemAvailable:");
        int was = atomic_load(&spilling);
        int low = available >= 0 && (size_t) available < (was ? low_kb + low_kb / 4 : low_kb);
        size_t now = atomic_load(&in_use);
        if (low != was) {
            atomic_store(&spilling, low);
            say(low ? "filemem: %ld MB available on the phone: blocks of %zu MB and more go to files\n"
                    : "filemem: %ld MB available again: blocks of %zu MB and more stay in memory\n",
                available >> 10, min_size >> 20);
        }
        forget(low);
        if (now == 0) continue;
        /* Once a second: more often, an atlas still being filled is written again and again. */
        if (low && ticks % 10 == 0) {
            for (int s = 0; s < SHARDS; s++) {
                sync_file_range(shards[s].fd, 0, 0, SYNC_FILE_RANGE_WRITE);
                pthread_mutex_lock(&shards[s].lock);
                size_t used = shards[s].top;
                pthread_mutex_unlock(&shards[s].lock);
                madvise(shards[s].base, used, MADV_COLD);
            }
        }
        if (ticks % 50 == 0) {
            say("filemem: %zu MB in %zu blocks in files (peak %zu MB, %zu MB given out so far)\n", now >> 20,
                atomic_load(&blocks), atomic_load(&peak) >> 20, atomic_load(&taken_total) >> 20);
        }
    }
    return NULL;
}

static void before_fork(void) {
    for (int s = 0; s < SHARDS; s++) pthread_mutex_lock(&shards[s].lock);
}

static void after_fork(void) {
    for (int s = 0; s < SHARDS; s++) pthread_mutex_unlock(&shards[s].lock);
}

static void in_child(void) {
    forked = 1;
    after_fork();
    reserve(base, span);
}

static size_t env_size(const char *name, size_t fallback) {
    const char *value = getenv(name);
    return value && *value ? strtoull(value, NULL, 10) : fallback;
}

/** An unlinked file in [dir], or -1. */
static int open_file(const char *dir) {
    int fd = open(dir, O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
    if (fd >= 0) return fd;
    char path[4096];
    snprintf(path, sizeof path, "%s/pocketfoundry-filemem-XXXXXX", dir);
    fd = mkostemp(path, O_CLOEXEC);
    if (fd >= 0) unlink(path);
    return fd;
}

__attribute__((constructor)) static void start(void) {
    glibc_usable_size = (size_t (*)(void *)) dlsym(RTLD_NEXT, "malloc_usable_size");
    page = (size_t) sysconf(_SC_PAGESIZE);
    const char *dir = getenv("POCKETFOUNDRY_FILEMEM_DIR");
    if (!dir || !*dir) return;
    min_size = env_size("POCKETFOUNDRY_FILEMEM_MIN_KB", 65536) << 10;
    long total_kb = meminfo("MemTotal:");
    size_t eighth_mb = total_kb > 0 ? (size_t) total_kb / 8 >> 10 : 1024;
    if (eighth_mb < 512) eighth_mb = 512;
    if (eighth_mb > 1536) eighth_mb = 1536;
    low_kb = env_size("POCKETFOUNDRY_FILEMEM_LOW_MB", eighth_mb) << 10;
    cache_max = env_size("POCKETFOUNDRY_FILEMEM_CACHE_MB", 0) << 20;
    span = SPAN;
    shard_span = span / SHARDS;

    struct shard *all = mmap(NULL, SHARDS * sizeof *all, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    char *range = mmap(NULL, span, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (all == MAP_FAILED || range == MAP_FAILED) {
        say("filemem: no room for the blocks' addresses: blocks stay in memory\n");
        return;
    }
    for (int s = 0; s < SHARDS; s++) {
        struct shard *shard = &all[s];
        shard->base = range + s * shard_span;
        pthread_mutex_init(&shard->lock, NULL);
        shard->fd = open_file(dir);
        /* The file can't grow past its shard, and punching a stretch out is how a freed block costs nothing. */
        if (shard->fd < 0 || ftruncate(shard->fd, (off_t) shard_span) != 0 ||
            fallocate(shard->fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, 0, (off_t) page) != 0) {
            say("filemem: %s can't hold a sparse file (%s): blocks stay in memory\n", dir, strerror(errno));
            for (int i = 0; i <= s; i++) if (all[i].fd > 0) close(all[i].fd);
            munmap(range, span);
            return;
        }
    }
    shards = all;
    base = range;
    pthread_atfork(before_fork, after_fork, in_child);
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    pthread_create(&thread, &attributes, watch, NULL);
    pthread_attr_destroy(&attributes);
    atomic_store_explicit(&ready, 1, memory_order_release);
    say("filemem: below %zu MB available, blocks of %zu MB and more go to files in %s\n", low_kb >> 10,
        min_size >> 20, dir);
}
