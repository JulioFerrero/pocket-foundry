// Loops, one on each of the given cores, at Linux's idle priority (SCHED_IDLE): they run only while their core has
// nothing else to, so they take no time from the game, but the core looks busy, so the phone keeps its clock up and
// never puts it to sleep between the game's short turns of work (app/.../core/game/CpuBoost.kt). Each waits in WFE,
// which stops the core's clock until an event (the kernel's event stream sends one every 100 us, and an interrupt
// does too): the same frame rate as spinning, 5 to 7 °C cooler on a Xiaomi 12T.
#define _GNU_SOURCE
#include <jni.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

struct busy;

struct core {
    struct busy *busy;
    int cpu;
    bool started;
    pthread_t thread;
};

struct busy {
    atomic_bool stop;
    int count;
    struct core cores[];
};

static void *spin(void *arg) {
    struct core *core = arg;
    cpu_set_t set;
    struct sched_param param = { 0 };
    CPU_ZERO(&set);
    CPU_SET(core->cpu, &set);
    // Either one refused, the loop would take the game's time or the wrong core's: none then.
    if (sched_setaffinity(0, sizeof set, &set) != 0 || sched_setscheduler(0, SCHED_IDLE, &param) != 0) return NULL;
    while (!atomic_load_explicit(&core->busy->stop, memory_order_relaxed)) {
        __asm__ volatile("wfe");
    }
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_app_pocketfoundry_core_system_BusyCores_start(JNIEnv *env, jclass cls, jintArray cpus) {
    jsize count = (*env)->GetArrayLength(env, cpus);
    struct busy *busy = calloc(1, sizeof *busy + (size_t)count * sizeof(struct core));
    if (!busy) return 0;
    jint *list = (*env)->GetIntArrayElements(env, cpus, NULL);
    if (!list) {
        free(busy);
        return 0;
    }
    busy->count = count;
    for (jsize i = 0; i < count; i++) {
        struct core *core = &busy->cores[i];
        core->busy = busy;
        core->cpu = list[i];
        core->started = pthread_create(&core->thread, NULL, spin, core) == 0;
        if (core->started) pthread_setname_np(core->thread, "pf-busy-core");
    }
    (*env)->ReleaseIntArrayElements(env, cpus, list, JNI_ABORT);
    return (jlong)(intptr_t)busy;
}

JNIEXPORT void JNICALL
Java_app_pocketfoundry_core_system_BusyCores_stop(JNIEnv *env, jclass cls, jlong handle) {
    struct busy *busy = (struct busy *)(intptr_t)handle;
    if (!busy) return;
    atomic_store(&busy->stop, true);
    for (int i = 0; i < busy->count; i++) {
        if (busy->cores[i].started) pthread_join(busy->cores[i].thread, NULL);
    }
    free(busy);
}
