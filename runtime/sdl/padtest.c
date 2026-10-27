// Prints what SDL sees from the gamepad for 30 s: checks the app → SDL path
// without the game.  Run from a debug build:
//   adb shell am broadcast -a app.pocketfoundry.debug.PAD --es run pocketfoundry-padtest
#include <SDL3/SDL.h>
#include <stdio.h>

int main(void)
{
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    if (!SDL_Init(SDL_INIT_GAMEPAD)) {
        printf("SDL_Init: %s\n", SDL_GetError());
        return 1;
    }
    printf("SDL %d, waiting for the gamepad\n", SDL_GetVersion());
    fflush(stdout);

    const Uint64 end = SDL_GetTicks() + 30000;
    SDL_Event e;
    while (SDL_GetTicks() < end) {
        if (!SDL_WaitEventTimeout(&e, 100)) {
            continue;
        }
        switch (e.type) {
        case SDL_EVENT_GAMEPAD_ADDED:
            SDL_OpenGamepad(e.gdevice.which);
            printf("added %u: %s\n", e.gdevice.which, SDL_GetGamepadNameForID(e.gdevice.which));
            break;
        case SDL_EVENT_GAMEPAD_REMOVED:
            printf("removed %u\n", e.gdevice.which);
            break;
        case SDL_EVENT_GAMEPAD_BUTTON_DOWN:
        case SDL_EVENT_GAMEPAD_BUTTON_UP:
            printf("button %s %s\n", SDL_GetGamepadStringForButton(e.gbutton.button), e.gbutton.down ? "down" : "up");
            break;
        case SDL_EVENT_GAMEPAD_AXIS_MOTION:
            printf("axis %s %d\n", SDL_GetGamepadStringForAxis(e.gaxis.axis), e.gaxis.value);
            break;
        case SDL_EVENT_JOYSTICK_BUTTON_DOWN:
            printf("(joystick button %d down)\n", e.jbutton.button);
            break;
        }
        fflush(stdout);
    }
    SDL_Quit();
    return 0;
}
