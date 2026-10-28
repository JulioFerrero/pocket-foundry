// A stand-in for libSPIRV-Tools.so. Linux builds of Mesa's Vulkan drivers (PanVK's, for one) link it only to print a
// shader as text while debugging. Debian ships it as a static library alone, so this defines that C API with every
// call failing; a debug print then says it couldn't disassemble. Arguments are ignored (see wayland-client.c).
#include <stddef.h>

#define SPV_ERROR_INTERNAL (-1)

void *spvContextCreate(void) { return NULL; }
void spvContextDestroy(void) {}
long spvBinaryToText(void) { return SPV_ERROR_INTERNAL; }
long spvTextToBinary(void) { return SPV_ERROR_INTERNAL; }
void spvTextDestroy(void) {}
void spvBinaryDestroy(void) {}
long spvDiagnosticPrint(void) { return SPV_ERROR_INTERNAL; }
void spvDiagnosticDestroy(void) {}
const char *spvSoftwareVersionString(void) { return "stand-in"; }
