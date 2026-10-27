/*
 * pocketfoundry-wm: the smallest window manager the game needs. Without one, SDL's
 * fullscreen request is ignored and the game opens at 1920x1080 in a corner.
 * Every top-level window is kept at the size of the screen (also after a
 * resolution change), gets keyboard focus, and is told it is fullscreen.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <xcb/xcb.h>
#include <xcb/randr.h>

static xcb_connection_t *c;
static xcb_screen_t *screen;
static xcb_atom_t net_supported, net_check, net_wm_name, utf8_string;
static xcb_atom_t net_wm_state, net_wm_state_fullscreen;

static xcb_atom_t atom(const char *name)
{
    xcb_intern_atom_reply_t *r =
        xcb_intern_atom_reply(c, xcb_intern_atom(c, 0, strlen(name), name), NULL);
    xcb_atom_t a = r ? r->atom : XCB_ATOM_NONE;
    free(r);
    return a;
}

static void fill_screen(xcb_window_t w)
{
    uint32_t v[] = { 0, 0, screen->width_in_pixels, screen->height_in_pixels, 0 };
    xcb_configure_window(c, w,
        XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y | XCB_CONFIG_WINDOW_WIDTH |
        XCB_CONFIG_WINDOW_HEIGHT | XCB_CONFIG_WINDOW_BORDER_WIDTH, v);
}

static void manage(xcb_window_t w)
{
    fill_screen(w);
    xcb_change_property(c, XCB_PROP_MODE_REPLACE, w, net_wm_state, XCB_ATOM_ATOM, 32, 1,
                        &net_wm_state_fullscreen);
    xcb_map_window(c, w);
    xcb_set_input_focus(c, XCB_INPUT_FOCUS_POINTER_ROOT, w, XCB_CURRENT_TIME);
}

static void fill_all(void)
{
    xcb_query_tree_reply_t *t = xcb_query_tree_reply(c, xcb_query_tree(c, screen->root), NULL);
    if (!t)
        return;
    xcb_window_t *kids = xcb_query_tree_children(t);
    for (int i = 0; i < xcb_query_tree_children_length(t); i++) {
        xcb_get_window_attributes_reply_t *a =
            xcb_get_window_attributes_reply(c, xcb_get_window_attributes(c, kids[i]), NULL);
        if (a && !a->override_redirect && a->map_state == XCB_MAP_STATE_VIEWABLE)
            fill_screen(kids[i]);
        free(a);
    }
    free(t);
}

int main(void)
{
    c = xcb_connect(NULL, NULL);
    if (xcb_connection_has_error(c)) {
        fprintf(stderr, "pocketfoundry-wm: can't open display\n");
        return 1;
    }
    screen = xcb_setup_roots_iterator(xcb_get_setup(c)).data;

    uint32_t mask = XCB_EVENT_MASK_SUBSTRUCTURE_REDIRECT | XCB_EVENT_MASK_SUBSTRUCTURE_NOTIFY;
    xcb_generic_error_t *err = xcb_request_check(c,
        xcb_change_window_attributes_checked(c, screen->root, XCB_CW_EVENT_MASK, &mask));
    if (err) {
        fprintf(stderr, "pocketfoundry-wm: another window manager is running\n");
        return 1;
    }
    xcb_randr_select_input(c, screen->root, XCB_RANDR_NOTIFY_MASK_SCREEN_CHANGE);
    const xcb_query_extension_reply_t *randr = xcb_get_extension_data(c, &xcb_randr_id);

    net_supported = atom("_NET_SUPPORTED");
    net_check = atom("_NET_SUPPORTING_WM_CHECK");
    net_wm_name = atom("_NET_WM_NAME");
    utf8_string = atom("UTF8_STRING");
    net_wm_state = atom("_NET_WM_STATE");
    net_wm_state_fullscreen = atom("_NET_WM_STATE_FULLSCREEN");

    /* EWMH: announce ourselves so SDL uses _NET_WM_STATE for fullscreen. */
    xcb_window_t check = xcb_generate_id(c);
    xcb_create_window(c, 0, check, screen->root, -1, -1, 1, 1, 0,
                      XCB_WINDOW_CLASS_INPUT_ONLY, XCB_COPY_FROM_PARENT, 0, NULL);
    xcb_atom_t supported[] = { net_supported, net_check, net_wm_state, net_wm_state_fullscreen };
    xcb_change_property(c, XCB_PROP_MODE_REPLACE, screen->root, net_supported, XCB_ATOM_ATOM, 32,
                        4, supported);
    xcb_change_property(c, XCB_PROP_MODE_REPLACE, screen->root, net_check, XCB_ATOM_WINDOW, 32, 1,
                        &check);
    xcb_change_property(c, XCB_PROP_MODE_REPLACE, check, net_check, XCB_ATOM_WINDOW, 32, 1, &check);
    xcb_change_property(c, XCB_PROP_MODE_REPLACE, check, net_wm_name, utf8_string, 8, 10,
                        "pocketfoundry-wm");
    xcb_flush(c);

    xcb_generic_event_t *e;
    while ((e = xcb_wait_for_event(c))) {
        uint8_t type = e->response_type & ~0x80;
        if (type == XCB_MAP_REQUEST) {
            manage(((xcb_map_request_event_t *)e)->window);
        } else if (type == XCB_CONFIGURE_REQUEST) {
            /* Whatever size is asked for, the answer is the whole screen. */
            fill_screen(((xcb_configure_request_event_t *)e)->window);
        } else if (type == XCB_CLIENT_MESSAGE) {
            xcb_client_message_event_t *m = (xcb_client_message_event_t *)e;
            if (m->type == net_wm_state)
                manage(m->window);
        } else if (randr && type == randr->first_event + XCB_RANDR_SCREEN_CHANGE_NOTIFY) {
            xcb_randr_screen_change_notify_event_t *s = (xcb_randr_screen_change_notify_event_t *)e;
            screen->width_in_pixels = s->width;
            screen->height_in_pixels = s->height;
            fill_all();
        }
        free(e);
        xcb_flush(c);
    }
    return 0;
}
