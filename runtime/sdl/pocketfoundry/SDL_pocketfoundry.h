/*
  Pocket Foundry's additions to SDL; see SDL_pocketfoundry.c.
  Released under the zlib licence, like SDL.
*/
#ifndef SDL_pocketfoundry_h_
#define SDL_pocketfoundry_h_

// Plugs in the app's gamepad whenever the app is connected ($POCKETFOUNDRY_INPUT).
extern void SDL_POCKETFOUNDRY_StartInput(void);
extern void SDL_POCKETFOUNDRY_StopInput(void);

// Holds a buffer swap until its turn when the player chose fewer frames ($POCKETFOUNDRY_FRAME_RATE).
extern void SDL_POCKETFOUNDRY_PaceFrame(void);

#endif // SDL_pocketfoundry_h_
