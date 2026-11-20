#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

// Genie 1.13 C ABI used by the board bundle. Resolve it at runtime so the
// worker can report an incompatible bundle before starting model allocation.
typedef void (*QueryCallback)(const char *, int, const void *);
struct Genie {
    int (*config_create)(const char *, void **);
    int (*config_free)(void *);
    int (*create)(void *, void **);
    int (*reset)(void *);
    int (*query)(void *, const char *, int, QueryCallback, const void *);
    int (*signal)(void *, int);
    int (*free)(void *);
};

enum {
    QUERY_COMPLETE = 0,
    QUERY_REWIND = 5,
    QUERY_ABORT = 1,
    QUERY_ABORTED = 1,
    RESPONSE_LIMIT = 65536,
    MIN_IDLE_AVAILABLE_KIB = 1024 * 1024
};

struct Response {
    char bytes[RESPONSE_LIMIT];
    size_t length;
    long long started_ms;
    long long first_ms;
    bool overflow;
};

struct QueryCancellation {
    struct Genie api;
    void *dialog;
    const char *path;
    atomic_bool finished;
};

static long long now_ms(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec * 1000LL + time.tv_nsec / 1000000;
}

static char *read_file(const char *path, size_t limit) {
    FILE *file = fopen(path, "rb");
    if (!file) return NULL;
    char *data = NULL;
    if (fseek(file, 0, SEEK_END) == 0) {
        long size = ftell(file);
        if (size > 0 && (size_t)size <= limit && fseek(file, 0, SEEK_SET) == 0) {
            data = malloc((size_t)size + 1);
            if (data && fread(data, 1, (size_t)size, file) == (size_t)size) {
                data[size] = '\0';
            } else {
                free(data);
                data = NULL;
            }
        }
    }
    fclose(file);
    return data;
}

static void collect(const char *chunk, int sentence_code, const void *user_data) {
    (void)sentence_code;
    struct Response *response = (struct Response *)user_data;
    if (!chunk || !*chunk || response->overflow) return;
    if (response->first_ms < 0) response->first_ms = now_ms() - response->started_ms;
    size_t size = strlen(chunk);
    if (size > sizeof(response->bytes) - response->length) {
        response->overflow = true;
        return;
    }
    memcpy(response->bytes + response->length, chunk, size);
    response->length += size;
}

static void *watch_cancellation(void *data) {
    struct QueryCancellation *query = data;
    const struct timespec interval = {.tv_nsec = 25000000L};
    while (!atomic_load(&query->finished)) {
        nanosleep(&interval, NULL);
        if (!atomic_load(&query->finished) && access(query->path, F_OK) == 0) {
            // A signal sent before Genie enters the query can be ignored. Keep checking until it returns.
            query->api.signal(query->dialog, QUERY_ABORT);
        }
    }
    return NULL;
}

static int run_query(struct Genie api, void *dialog, const char *prompt, int sentence_code, const char *cancel_path,
                     struct Response *response) {
    if (access(cancel_path, F_OK) == 0) return QUERY_ABORTED;
    struct QueryCancellation query = {.api = api, .dialog = dialog, .path = cancel_path, .finished = false};
    pthread_t watcher;
    bool watching = api.signal && pthread_create(&watcher, NULL, watch_cancellation, &query) == 0;
    int status = api.query(dialog, prompt, sentence_code, collect, response);
    if (watching) {
        atomic_store(&query.finished, true);
        pthread_join(watcher, NULL);
    }
    return status;
}

static bool memory_available(void) {
    FILE *meminfo = fopen("/proc/meminfo", "r");
    if (!meminfo) {
        __android_log_print(ANDROID_LOG_WARN, "MalloGenieNative", "Cannot read MemAvailable: %s", strerror(errno));
        return false;
    }
    char line[256];
    long available = -1;
    while (fgets(line, sizeof(line), meminfo)) {
        if (sscanf(line, "MemAvailable: %ld", &available) == 1) break;
    }
    fclose(meminfo);
    if (available < MIN_IDLE_AVAILABLE_KIB) {
        __android_log_print(ANDROID_LOG_INFO, "MalloGenieNative", "Idle eviction availableKiB=%ld", available);
    }
    return available >= MIN_IDLE_AVAILABLE_KIB;
}

static bool next_request(struct Genie api, void **dialog) {
    for (;;) {
        struct pollfd input = {STDIN_FILENO, POLLIN, 0};
        int result = poll(&input, 1, *dialog ? 1000 : -1);
        if (result > 0) return (input.revents & POLLIN) != 0;
        if (result < 0 && errno != EINTR) return false;
        if (*dialog && !memory_available()) {
            int status = api.free(*dialog);
            *dialog = NULL;
            if (status != 0) return false;
        }
    }
}

static int load_dialog(struct Genie api, const char *config_path, void **dialog, const char **stage) {
    *stage = "config";
    char *json = read_file(config_path, 256 * 1024);
    if (!json) return -1;
    void *config = NULL;
    int status = api.config_create(json, &config);
    if (status == 0) {
        *stage = "create";
        status = api.create(config, dialog);
    }
    if (config) api.config_free(config);
    free(json);
    return status;
}

static int serve(FILE *wire, struct Genie api, const char *config_path) {
    void *dialog = NULL;
    char command[4096];
    bool rewind_supported = true;
    bool previous_complete = false;
    int result = 0;
    fputs("MALLO_GENIE_3\n", wire);
    fflush(wire);
    while (next_request(api, &dialog) && fgets(command, sizeof(command), stdin)) {
        size_t length = strlen(command);
        if (length < 2 || command[length - 1] != '\n') break;
        command[length - 1] = '\0';
        // Benchmarks can replay the same prompts in the same worker with a clean context.
        bool force_full = strncmp(command, "FULL ", 5) == 0;
        const char *path = command + (force_full ? 5 : 0);
        bool preload = strcmp(path, "PRELOAD") == 0;
        char cancel_path[sizeof(command) + sizeof(".cancel")];
        snprintf(cancel_path, sizeof(cancel_path), "%s.cancel", path);
        if (!preload && access(cancel_path, F_OK) == 0) {
            if (fputs("CANCELLED\n", wire) == EOF || fflush(wire) == EOF) break;
            continue;
        }
        char *prompt = preload ? NULL : read_file(path, 1024 * 1024);
        bool reused = dialog != NULL;
        const char *stage = "prompt";
        int status = preload || prompt ? 0 : -1;
        long long init_ms = 0;
        struct Response response = {.first_ms = -1};
        if (status == 0 && !reused) {
            previous_complete = false;
            long long start = now_ms();
            status = load_dialog(api, config_path, &dialog, &stage);
            init_ms = now_ms() - start;
        }
        if (preload && status == 0) {
            fprintf(wire, "READY %lld\n", init_ms);
            if (fflush(wire) == EOF) break;
            continue;
        }
        bool rewind = reused && previous_complete && rewind_supported && !force_full;
        previous_complete = false;
        if (status == 0) {
            response.started_ms = now_ms();
            if (reused && !rewind) {
                stage = "reset";
                status = api.reset(dialog);
            }
            if (status == 0) {
                stage = "query";
                status = run_query(api, dialog, prompt, rewind ? QUERY_REWIND : QUERY_COMPLETE, cancel_path, &response);
            }
            if (rewind && (status != 0 || response.overflow || response.length == 0) &&
                access(cancel_path, F_OK) != 0) {
                __android_log_print(ANDROID_LOG_WARN, "MalloGenieNative",
                                    "GENIE_PREFIX_FALLBACK status=%d", status);
                rewind_supported = false;
                rewind = false;
                response = (struct Response){.started_ms = response.started_ms, .first_ms = -1};
                stage = "reset";
                status = api.reset(dialog);
                if (status == 0) {
                    stage = "query";
                    status = run_query(api, dialog, prompt, QUERY_COMPLETE, cancel_path, &response);
                }
            }
        }
        free(prompt);
        if (!preload && (status == 0 || status == QUERY_ABORTED) && access(cancel_path, F_OK) == 0) {
            __android_log_print(ANDROID_LOG_INFO, "MalloGenieNative", "GENIE_QUERY_CANCELLED status=%d", status);
            if (fputs("CANCELLED\n", wire) == EOF || fflush(wire) == EOF) break;
            continue;
        }
        if (status != 0 || response.overflow || response.length == 0) {
            fprintf(wire, "ERROR %s %d\n", stage, status);
            fflush(wire);
            result = 1;
            break;
        }
        previous_complete = true;
        if (rewind) {
            __android_log_print(ANDROID_LOG_INFO, "MalloGenieNative",
                                "GENIE_PREFIX_REUSE firstMs=%lld", response.first_ms);
        }
        fprintf(wire, "OK %zu %lld %lld %lld %d\n", response.length, init_ms,
                now_ms() - response.started_ms, response.first_ms, reused ? 1 : 0);
        if (fwrite(response.bytes, 1, response.length, wire) != response.length ||
            fflush(wire) == EOF) break;
    }
    if (dialog) {
        api.free(dialog);
        __android_log_print(ANDROID_LOG_INFO, "MalloGenieNative", "Released dialog result=%d", result);
    }
    return result;
}

static void *watch_parent(void *data) {
    pid_t parent = (pid_t)(intptr_t)data;
    while (getppid() == parent) sleep(1);
    __android_log_write(ANDROID_LOG_INFO, "MalloGenieNative", "Parent process exited");
    _exit(2);
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    // Watch the app process, since the Java thread that launched us can retire.
    pid_t parent = getppid();
    pthread_t watchdog;
    if (parent == 1 || pthread_create(&watchdog, NULL, watch_parent, (void *)(intptr_t)parent) != 0) return 2;
    int wire_fd = dup(STDOUT_FILENO);
    if (wire_fd < 0 || dup2(STDERR_FILENO, STDOUT_FILENO) < 0) return 2;
    FILE *wire = fdopen(wire_fd, "w");
    if (!wire) return 2;
    setvbuf(stdin, NULL, _IONBF, 0);
    void *library = dlopen("libGenie.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fprintf(stderr, "%s\n", dlerror());
        return 2;
    }
    struct Genie api = {
        .config_create = dlsym(library, "GenieDialogConfig_createFromJson"),
        .config_free = dlsym(library, "GenieDialogConfig_free"),
        .create = dlsym(library, "GenieDialog_create"),
        .reset = dlsym(library, "GenieDialog_reset"),
        .query = dlsym(library, "GenieDialog_query"),
        .signal = dlsym(library, "GenieDialog_signal"),
        .free = dlsym(library, "GenieDialog_free")
    };
    if (!api.config_create || !api.config_free || !api.create || !api.reset || !api.query || !api.free) {
        return 2;
    }
    int result = serve(wire, api, argv[1]);
    fclose(wire);
    return result;
}
