// A stand-in for libwayland-client.so.0. Linux builds of Mesa's Vulkan drivers (PanVK's, for one) link Wayland for
// Wayland windows, which never happen here: the game draws through X11. Debian 13's Wayland (1.23) lacks symbols
// newer drivers import, so this defines the client API with every call failing.
//
// Signatures are left out on purpose: each function ignores its arguments, and on arm64 a caller passes them in
// registers either way, so one "fails" body fits any of them.
#include <stddef.h>

struct wl_message;
struct wl_interface {
    const char *name;
    int version;
    int method_count;
    const struct wl_message *methods;
    int event_count;
    const struct wl_message *events;
};

#define INTERFACE(name) const struct wl_interface name##_interface = { #name, 1, 0, NULL, 0, NULL };
INTERFACE(wl_display) INTERFACE(wl_registry) INTERFACE(wl_callback) INTERFACE(wl_compositor)
INTERFACE(wl_shm_pool) INTERFACE(wl_shm) INTERFACE(wl_buffer) INTERFACE(wl_data_offer) INTERFACE(wl_data_source)
INTERFACE(wl_data_device) INTERFACE(wl_data_device_manager) INTERFACE(wl_shell) INTERFACE(wl_shell_surface)
INTERFACE(wl_surface) INTERFACE(wl_seat) INTERFACE(wl_pointer) INTERFACE(wl_keyboard) INTERFACE(wl_touch)
INTERFACE(wl_output) INTERFACE(wl_region) INTERFACE(wl_subcompositor) INTERFACE(wl_subsurface) INTERFACE(wl_fixes)

#define NONE(name) void *name(void) { return NULL; }
#define FAIL(name) long name(void) { return -1; }
#define NOTHING(name) void name(void) {}

NONE(wl_display_connect) NONE(wl_display_connect_to_fd) NONE(wl_display_create_queue)
NONE(wl_display_create_queue_with_name) NONE(wl_proxy_create) NONE(wl_proxy_create_wrapper) NONE(wl_proxy_marshal_flags)
NONE(wl_proxy_marshal_array_flags) NONE(wl_proxy_marshal_constructor) NONE(wl_proxy_marshal_constructor_versioned)
NONE(wl_proxy_marshal_array_constructor) NONE(wl_proxy_marshal_array_constructor_versioned) NONE(wl_proxy_get_listener)
NONE(wl_proxy_get_user_data) NONE(wl_proxy_get_tag) NONE(wl_proxy_get_class) NONE(wl_proxy_get_display)
NONE(wl_proxy_get_queue) NONE(wl_event_queue_get_name)
FAIL(wl_display_get_fd) FAIL(wl_display_dispatch) FAIL(wl_display_dispatch_queue) FAIL(wl_display_dispatch_pending)
FAIL(wl_display_dispatch_queue_pending) FAIL(wl_display_dispatch_timeout) FAIL(wl_display_dispatch_queue_timeout)
FAIL(wl_display_get_error) FAIL(wl_display_get_protocol_error) FAIL(wl_display_flush) FAIL(wl_display_roundtrip)
FAIL(wl_display_roundtrip_queue) FAIL(wl_display_prepare_read) FAIL(wl_display_prepare_read_queue)
FAIL(wl_display_read_events) FAIL(wl_proxy_add_listener) FAIL(wl_proxy_add_dispatcher)
NOTHING(wl_display_disconnect) NOTHING(wl_display_cancel_read) NOTHING(wl_display_set_max_buffer_size)
NOTHING(wl_event_queue_destroy) NOTHING(wl_proxy_destroy) NOTHING(wl_proxy_wrapper_destroy) NOTHING(wl_proxy_marshal)
NOTHING(wl_proxy_marshal_array) NOTHING(wl_proxy_set_user_data) NOTHING(wl_proxy_set_tag) NOTHING(wl_proxy_set_queue)
NOTHING(wl_log_set_handler_client)
long wl_proxy_get_id(void) { return 0; }
long wl_proxy_get_version(void) { return 0; }

// The list helpers are real: they touch only the caller's memory.
struct wl_list { struct wl_list *prev, *next; };

void wl_list_init(struct wl_list *list) { list->prev = list->next = list; }

void wl_list_insert(struct wl_list *list, struct wl_list *elm) {
    elm->prev = list;
    elm->next = list->next;
    list->next = elm;
    elm->next->prev = elm;
}

void wl_list_remove(struct wl_list *elm) {
    elm->prev->next = elm->next;
    elm->next->prev = elm->prev;
    elm->next = elm->prev = NULL;
}

int wl_list_length(const struct wl_list *list) {
    int count = 0;
    for (const struct wl_list *e = list->next; e != list; e = e->next) count++;
    return count;
}

int wl_list_empty(const struct wl_list *list) { return list->next == list; }

void wl_list_insert_list(struct wl_list *list, struct wl_list *other) {
    if (wl_list_empty(other)) return;
    other->next->prev = list;
    other->prev->next = list->next;
    list->next->prev = other->prev;
    list->next = other->next;
}
