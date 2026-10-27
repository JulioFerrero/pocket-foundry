// Opens a window on the X server and prints the mouse, wheel, keys and gamepad SDL sees, as
// Factorio would get them: checks the controls → X / virtual pad → SDL paths without the game
// (or a GPU).
// Debug builds run it in the game's place with `test.program=pocketfoundry-inputtest` in
// files/debug.env; it prints to logs/game.log.
#include <SDL3/SDL.h>
#include <stdio.h>

int main(void)
{
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMEPAD)) {
        printf("SDL_Init: %s\n", SDL_GetError());
        return 1;
    }
    SDL_Window *window = SDL_CreateWindow("pocketfoundry-inputtest", 0, 0, SDL_WINDOW_FULLSCREEN);
    if (!window) {
        printf("SDL_CreateWindow: %s\n", SDL_GetError());
        return 1;
    }
    SDL_SyncWindow(window);
    int width, height;
    SDL_GetWindowSize(window, &width, &height);
    printf("window %dx%d\n", width, height);
    fflush(stdout);

    Uint64 lastMotion = 0;
    bool running = true;
    SDL_Event e;
    while (running) {
        // A plain grey window: something on screen, no GPU needed.
        SDL_Surface *surface = SDL_GetWindowSurface(window);
        if (surface) {
            SDL_FillSurfaceRect(surface, NULL, SDL_MapSurfaceRGB(surface, 60, 60, 64));
            SDL_UpdateWindowSurface(window);
        }
        if (!SDL_WaitEventTimeout(&e, 500)) {
            continue;
        }
        switch (e.type) {
        case SDL_EVENT_QUIT:
            running = false;
            break;
        case SDL_EVENT_MOUSE_MOTION:
            // Every motion while a button is held; otherwise at most every 100 ms.
            if (e.motion.state || e.motion.timestamp - lastMotion > 100000000) {
                printf("motion %.0f,%.0f%s\n", e.motion.x, e.motion.y, e.motion.state ? " held" : "");
                lastMotion = e.motion.timestamp;
            }
            break;
        case SDL_EVENT_MOUSE_BUTTON_DOWN:
        case SDL_EVENT_MOUSE_BUTTON_UP:
            printf("button %s %s at %.0f,%.0f\n", e.button.button == SDL_BUTTON_LEFT ? "left" : e.button.button == SDL_BUTTON_RIGHT ? "right" : "other",
                   e.button.down ? "down" : "up", e.button.x, e.button.y);
            break;
        case SDL_EVENT_MOUSE_WHEEL:
            printf("wheel %+.2f\n", e.wheel.y);
            break;
        case SDL_EVENT_GAMEPAD_ADDED:
            SDL_OpenGamepad(e.gdevice.which);
            printf("pad added\n");
            break;
        case SDL_EVENT_GAMEPAD_BUTTON_DOWN:
        case SDL_EVENT_GAMEPAD_BUTTON_UP:
            printf("pad %s %s\n", SDL_GetGamepadStringForButton((SDL_GamepadButton) e.gbutton.button), e.gbutton.down ? "down" : "up");
            break;
        case SDL_EVENT_GAMEPAD_AXIS_MOTION:
            // Triggers only, and only across the middle: pressed or let go.
            if (e.gaxis.axis == SDL_GAMEPAD_AXIS_LEFT_TRIGGER || e.gaxis.axis == SDL_GAMEPAD_AXIS_RIGHT_TRIGGER) {
                static bool pulled[SDL_GAMEPAD_AXIS_COUNT];
                bool now = e.gaxis.value > 16384;
                if (now != pulled[e.gaxis.axis]) {
                    pulled[e.gaxis.axis] = now;
                    printf("pad %s %s\n", SDL_GetGamepadStringForAxis((SDL_GamepadAxis) e.gaxis.axis), now ? "down" : "up");
                }
            }
            break;
        case SDL_EVENT_KEY_DOWN:
        case SDL_EVENT_KEY_UP:
            if (!e.key.repeat) {
                printf("key %s %s\n", SDL_GetScancodeName(e.key.scancode), e.key.down ? "down" : "up");
            }
            break;
        default:
            break;
        }
        fflush(stdout);
    }
    SDL_Quit();
    return 0;
}
