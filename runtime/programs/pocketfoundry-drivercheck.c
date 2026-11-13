/*
 * The driver check: draws thirteen squares the way the game draws (shaders, textures, blending, state that changes
 * between draws, instances, and textures compressed in the formats the game uses, filled in pieces and mipmapped) into
 * an offscreen picture through the game's own OpenGL, then reads the picture back. Exits 0 when every square came out
 * right, 1 when the picture is wrong, 2 when OpenGL didn't start, and 128 + the signal when one kills it, after
 * saying where (DriverCheck.kt reads these).
 *
 * With "textures", the game's atlases at their real size instead: 8192 x 8192 in BC3 and BC1 and a mipmapped one,
 * filled in pieces and read in places through a shader; how much GPU memory one takes, which tells whether it stays
 * compressed; whether they come back right compressed, the way fast start saves them; and whether blocks copied in
 * from integer textures read right, the way Factorio's GPU compressor makes them. It ends with one line of results
 * (ATLASES) and exits 0, or 1 without S3TC, 2 without OpenGL. Either way it first says how much memory the driver has.
 * Last, it asks the Vulkan driver beneath which compressed formats it samples (FORMATS): through the compat layer, BC
 * among them means the layer is converting it.
 */
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>
#include <X11/Xlib.h>
#include <dlfcn.h>
#include <execinfo.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>

#define CELL 32
#define WIDTH (5 * CELL)
#define HEIGHT (3 * CELL)

typedef GLXContext (*CreateContextAttribs)(Display *, GLXFBConfig, GLXContext, Bool, const int *);

typedef unsigned char Rgb[3];
/* What a square should be at the middle of each of its 4 x 4 texels, row by row from the bottom. */
typedef Rgb Texels[16];

static const Rgb RED = { 255, 0, 0 }, GREEN = { 0, 255, 0 }, BLUE = { 0, 0, 255 }, YELLOW = { 255, 255, 0 },
                 MAGENTA = { 255, 0, 255 }, CYAN = { 0, 255, 255 }, WHITE = { 255, 255, 255 }, BLACK = { 0 };
static const int GREY = 64;

static const char *VERTEX_SHADER =
    "#version 330 core\n"
    "layout(location = 0) in vec2 position;\n"
    "out vec2 uv;\n"
    "void main() { uv = position * 0.5 + 0.5; gl_Position = vec4(position, 0.0, 1.0); }\n";

/* Two instances side by side, each with its own place and colour: attributes that step once per instance. */
static const char *INSTANCED_VERTEX_SHADER =
    "#version 330 core\n"
    "layout(location = 0) in vec2 position;\n"
    "layout(location = 1) in float offset;\n"
    "layout(location = 2) in vec3 tint;\n"
    "out vec3 colour;\n"
    "void main() { colour = tint; gl_Position = vec4(position.x * 0.5 + offset, position.y, 0.0, 1.0); }\n";

static const char *INSTANCED_FRAGMENT_SHADER =
    "#version 330 core\n"
    "in vec3 colour;\n"
    "out vec4 fragment;\n"
    "void main() { fragment = vec4(colour, 1.0); }\n";

static const char *FRAGMENT_SHADER =
    "#version 330 core\n"
    "uniform vec4 color;\n"
    "uniform sampler2D sprite;\n"
    "uniform float textured;\n"
    "in vec2 uv;\n"
    "out vec4 fragment;\n"
    "void main() { fragment = mix(color, texture(sprite, uv), textured); }\n";

/* Rows from the bottom, as glReadPixels returns them. */
static unsigned char picture[HEIGHT][WIDTH][4];

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
            printf("drivercheck: %s %s+0x%lx\n", what, path[0] ? path : "(anonymous)", address - start + offset);
            fclose(maps);
            return;
        }
    }
    printf("drivercheck: %s 0x%lx, mapped nowhere\n", what, (unsigned long)address);
    if (maps) fclose(maps);
}

/*
 * Xlib's own handler exits with 1, which reads as a wrong picture: a driver without OpenGL 3.3 refuses the context
 * that way (GLXBadFBConfig), and the check never asked for 3.3 anyway (DriverCheck.kt). Said, and left to the caller.
 */
static int x_error(Display *display, XErrorEvent *event)
{
    printf("drivercheck: X error %d on request %d.%d\n", event->error_code, event->request_code, event->minor_code);
    return 0;
}

/* Its output lands in drivercheck.log. */
static void crashed(int signal, siginfo_t *info, void *context)
{
    printf("drivercheck: crashed with signal %d, touching 0x%lx\n", signal, (unsigned long)info->si_addr);
#if defined(__aarch64__)
    const ucontext_t *state = context;
    where("in", state->uc_mcontext.pc);
    where("called from", state->uc_mcontext.regs[30]);
#endif
    fflush(stdout);
    void *frames[32];
    backtrace_symbols_fd(frames, backtrace(frames, 32), STDOUT_FILENO);
    _exit(128 + signal);
}

static GLuint shader(GLenum type, const char *source)
{
    GLuint s = glCreateShader(type);
    GLint ok = 0;
    glShaderSource(s, 1, &source, NULL);
    glCompileShader(s);
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    return ok ? s : 0;
}

static GLuint square(const float *corners, int count, int stride)
{
    GLuint vao, vbo;
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, count * stride * sizeof(float), corners, GL_STATIC_DRAW);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, stride * sizeof(float), NULL);
    glEnableVertexAttribArray(0);
    return vao;
}

static GLuint texture(void)
{
    GLuint t;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    return t;
}

/*
 * The index of texel (x, y) in every compressed block: index 0 is the block's first end, 1 its second, 2 and 3 lie
 * between. No two rows alike and nothing symmetric, so texels read swapped, mirrored or shifted show.
 */
static int pattern(int x, int y) { return (x + 3 * y) % 4; }
static int reversed(int x, int y) { return 3 - pattern(x, y); }
/* Only the two ends, for what the transcoder can't keep exactly between them (BC3's alpha). */
static int checkered(int x, int y) { return (x + y) % 2; }

/* Entry [index] of a block's palette, [parts] steps from end [a] (index 0) to end [b] (index 1). */
static int on_line(int a, int b, int index, int parts)
{
    int part = index == 0 ? 0 : index == 1 ? parts : index - 1;
    return (a * (parts - part) + b * part + parts / 2) / parts;
}

/* 16 indices of [bits] bits each, texel by texel from the lowest bit up, the way BC stores them. */
static void indices(unsigned char *out, int bits, int (*index)(int, int))
{
    uint64_t packed = 0;
    for (int t = 0; t < 16; t++) packed |= (uint64_t)index(t % 4, t / 4) << (bits * t);
    for (int i = 0; i < 2 * bits; i++) out[i] = packed >> (8 * i);
}

static unsigned rgb565(const Rgb c) { return (c[0] >> 3) << 11 | (c[1] >> 2) << 5 | c[2] >> 3; }

/* A BC1 block (and BC3's colour half) from [a] to [b]: pure colours, which 565 keeps, and [a] the greater, so the
 * block has four colours. */
static void bc1_block(unsigned char *block, const Rgb a, const Rgb b, int (*index)(int, int))
{
    unsigned first = rgb565(a), second = rgb565(b);
    block[0] = first;
    block[1] = first >> 8;
    block[2] = second;
    block[3] = second >> 8;
    indices(block + 4, 2, index);
}

/* A BC4 block (and BC3's alpha half, and each half of BC5) from [a] to [b]: eight values when [a] is the greater,
 * six and 0 and 255 otherwise. */
static void bc4_block(unsigned char *block, int a, int b, int (*index)(int, int))
{
    block[0] = a;
    block[1] = b;
    indices(block + 2, 3, index);
}

static void channel(Texels out, int c, int a, int b, int parts, int (*index)(int, int))
{
    for (int t = 0; t < 16; t++) out[t][c] = on_line(a, b, index(t % 4, t / 4), parts);
}

static Rgb *plain(Texels out, const Rgb colour)
{
    for (int t = 0; t < 16; t++) memcpy(out[t], colour, 3);
    return out;
}

static Rgb *bc1_texels(Texels out, const Rgb a, const Rgb b, int (*index)(int, int))
{
    for (int c = 0; c < 3; c++) channel(out, c, a[c], b[c], 3, index);
    return out;
}

/* A compressed texture made the way the game makes its atlases: empty first, then filled in. */
static GLuint compressed(GLenum format, const unsigned char *block, GLsizei size)
{
    GLuint t = texture();
    glTexImage2D(GL_TEXTURE_2D, 0, format, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glCompressedTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, 4, format, size, block);
    return t;
}

static GLuint mipmapped(const unsigned char *corner, const unsigned char *opposite, const unsigned char *small)
{
    GLuint t = texture();
    GLenum bc1 = GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, bc1, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexImage2D(GL_TEXTURE_2D, 1, bc1, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glCompressedTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, 4, bc1, 8, corner);
    glCompressedTexSubImage2D(GL_TEXTURE_2D, 0, 4, 4, 4, 4, bc1, 8, opposite);
    glCompressedTexSubImage2D(GL_TEXTURE_2D, 1, 0, 0, 4, 4, bc1, 8, small);
    return t;
}

static void viewport(int column, int row)
{
    glViewport(column * CELL, row * CELL, CELL, CELL);
}

static int expect(const char *name, int x, int y, int size, Rgb *want)
{
    for (int t = 0; t < 16; t++) {
        const unsigned char *got = picture[y + t / 4 * size + size / 2][x + t % 4 * size + size / 2];
        for (int c = 0; c < 3; c++) {
            if (abs(got[c] - want[t][c]) > 8) {
                printf("drivercheck: %s square is %d,%d,%d instead of %d,%d,%d at texel %d,%d\n", name,
                       got[0], got[1], got[2], want[t][0], want[t][1], want[t][2], t % 4, t / 4);
                return 0;
            }
        }
    }
    return 1;
}

static int expect_cell(const char *name, int column, int row, Rgb *want)
{
    return expect(name, column * CELL, row * CELL, CELL / 4, want);
}

static int has_extension(const char *name)
{
    GLint count = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    for (GLint i = 0; i < count; i++)
        if (!strcmp((const char *)glGetStringi(GL_EXTENSIONS, i), name)) return 1;
    return 0;
}

/*
 * NVX_gpu_memory_info, in KB: Zink's from the driver's VK_EXT_memory_budget, the same numbers our Mesa patch 0004 logs
 * while the game runs. llvmpipe's mean nothing.
 */
#define GPU_MEMORY_TOTAL 0x9048
#define GPU_MEMORY_AVAILABLE 0x9049

/* What the driver has free now, in KB, as it says it: -1 where it doesn't. */
static long available_kb(void)
{
    if (!has_extension("GL_NVX_gpu_memory_info")) return -1;
    GLint available = 0;
    glGetIntegerv(GPU_MEMORY_AVAILABLE, &available);
    return available > 0 ? available : -1;
}

static void memory(void)
{
    if (!has_extension("GL_NVX_gpu_memory_info")) {
        printf("drivercheck: memory unknown: no GL_NVX_gpu_memory_info\n");
        return;
    }
    GLint total = 0, available = 0;
    glGetIntegerv(GPU_MEMORY_TOTAL, &total);
    glGetIntegerv(GPU_MEMORY_AVAILABLE, &available);
    // An Adreno 830's driver gives a budget above its heap: 26 GB of 15.
    if (available > total) available = total;
    if (total > 0 && available > 0)
        printf("drivercheck: memory %d MB, %d MB available\n", total / 1024, available / 1024);
    else
        printf("drivercheck: memory unknown: %d KB, %d KB available\n", total, available);
}

/* ── textures ── */

#define ATLAS 8192
#define PIECE 1024
/* The mipmapped atlas, smaller: its second level holds blocks of its own. */
#define MIPMAPPED 2048

static const Rgb *const PALETTE[] = { &RED, &GREEN, &BLUE, &YELLOW, &MAGENTA, &CYAN, &WHITE, &BLACK };

/*
 * Block (bx, by) of [level]: two ends unlike its neighbours', so a block read from the wrong place shows, the greater
 * first (BC1's four colours), and one of two index patterns.
 */
typedef struct { const Rgb *a, *b; int (*index)(int, int); } Block;

static Block atlas_block(int bx, int by, int level)
{
    int p = (bx * 7 + by * 13 + level * 5) % 8;
    Block block = { PALETTE[p], PALETTE[(p + 3) % 8], (bx + by) % 2 ? reversed : pattern };
    if (rgb565(*block.a) < rgb565(*block.b)) {
        const Rgb *greater = block.b;
        block.b = block.a;
        block.a = greater;
    }
    return block;
}

/* BC3's alpha half, partial on every other texel, then the colour half; BC1 is the colour half alone. */
static void encode(unsigned char *out, int bytes, int bx, int by, int level)
{
    Block block = atlas_block(bx, by, level);
    if (bytes == 16) bc4_block(out, 255, 96, checkered);
    bc1_block(out + bytes - 8, *block.a, *block.b, block.index);
}

static Rgb *atlas_texels(Texels out, int bx, int by, int level)
{
    Block block = atlas_block(bx, by, level);
    return bc1_texels(out, *block.a, *block.b, block.index);
}

/* The GPU memory that went between two readings of available_kb(), in MB: -1 where it can't be told. */
static long taken_mb(long before, long after)
{
    return before > 0 && after > 0 && before > after ? (before - after) / 1024 : -1;
}

/*
 * An atlas the game's way: empty, then filled piece by piece. [made] and [filled]: the GPU memory it took once made and
 * once filled, in MB, or -1. Filling can leave the driver's upload buffers behind, so made says more where it's known.
 */
static GLuint atlas(GLenum format, int bytes, int size, int levels, long *made, long *filled)
{
    glFinish();
    long before = available_kb();
    GLuint t = texture();
    if (levels > 1) glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, levels - 1);
    for (int level = 0; level < levels; level++)
        glTexImage2D(GL_TEXTURE_2D, level, format, size >> level, size >> level, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glFinish();
    *made = taken_mb(before, available_kb());
    int piece = PIECE < size ? PIECE : size;
    unsigned char *data = malloc((size_t)(piece / 4) * (piece / 4) * bytes);
    for (int level = 0; level < levels; level++) {
        int edge = size >> level, part = piece < edge ? piece : edge;
        for (int py = 0; py < edge; py += part)
            for (int px = 0; px < edge; px += part) {
                for (int by = 0; by < part / 4; by++)
                    for (int bx = 0; bx < part / 4; bx++)
                        encode(data + ((size_t)by * (part / 4) + bx) * bytes, bytes, px / 4 + bx, py / 4 + by, level);
                glCompressedTexSubImage2D(GL_TEXTURE_2D, level, px, py, part, part, format,
                                          (part / 4) * (part / 4) * bytes, data);
            }
    }
    free(data);
    glFinish();
    *filled = taken_mb(before, available_kb());
    return t;
}

static const char *FETCH_SHADER =
    "#version 330 core\n"
    "uniform sampler2D sprite;\n"
    "uniform ivec2 at;\n"
    "uniform int level;\n"
    "out vec4 fragment;\n"
    "void main() { fragment = vec4(texelFetch(sprite, at + ivec2(gl_FragCoord.xy), level).rgb, 1.0); }\n";

/* The textures run's last line (DriverCheck.kt reads it): 1 or 0 for each, then what the BC3 atlas takes and would
 * take compressed, in MB, -1 where the driver doesn't say. */
#define ATLASES "drivercheck: atlases draws=%d comesBack=%d copies=%d takes=%ld of=%ld\n"

/* Where the atlases are read, in blocks: corners, middle and a few between. */
static const int SPOTS[][2] = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1023, 1023 }, { 2047, 0 }, { 0, 2047 },
                                { 2047, 2047 }, { 512, 1536 }, { 1789, 333 } };
#define SPOT_COUNT (int)(sizeof(SPOTS) / sizeof(SPOTS[0]))

static int same(const char *name, const char *how, const unsigned char *got, int stride, Texels want, int bx, int by,
                int level)
{
    for (int t = 0; t < 16; t++) {
        const unsigned char *texel = got + t * stride;
        for (int c = 0; c < 3; c++)
            if (abs(texel[c] - want[t][c]) > 8) {
                printf("drivercheck: the %s atlas %s %d,%d,%d instead of %d,%d,%d at block %d,%d of level %d\n", name,
                       how, texel[0], texel[1], texel[2], want[t][0], want[t][1], want[t][2], bx, by, level);
                return 0;
            }
    }
    return 1;
}

/* Block (bx, by) of [level] through the shader, onto 4 x 4 pixels. */
static int read_block(GLint at, GLint level_uniform, const char *name, int bx, int by, int level)
{
    unsigned char got[16][4];
    glUniform2i(at, bx * 4, by * 4);
    glUniform1i(level_uniform, level);
    glViewport(0, 0, 4, 4);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glReadPixels(0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, got);
    Texels want;
    return same(name, "reads", got[0], 4, atlas_texels(want, bx, by, level), bx, by, level);
}

/* BC1's four colours, from a block's colour half. */
static void decode_bc1(const unsigned char *block, Texels out)
{
    unsigned ends[2] = { block[0] | block[1] << 8, block[2] | block[3] << 8 };
    int colour[4][3];
    for (int e = 0; e < 2; e++) {
        colour[e][0] = (ends[e] >> 11 & 31) * 255 / 31;
        colour[e][1] = (ends[e] >> 5 & 63) * 255 / 63;
        colour[e][2] = (ends[e] & 31) * 255 / 31;
    }
    for (int c = 0; c < 3; c++) {
        colour[2][c] = (2 * colour[0][c] + colour[1][c] + 1) / 3;
        colour[3][c] = (colour[0][c] + 2 * colour[1][c] + 1) / 3;
    }
    uint32_t indices = block[4] | block[5] << 8 | block[6] << 16 | (uint32_t)block[7] << 24;
    for (int t = 0; t < 16; t++)
        for (int c = 0; c < 3; c++) out[t][c] = colour[indices >> (2 * t) & 3][c];
}

/* [level] read back compressed, as fast start saves it: the blocks at SPOTS have to decode to what was put in. */
static int read_back(const char *name, int bytes, int size, int level)
{
    int edge = size >> level, blocks = edge / 4;
    GLint length = 0;
    glGetTexLevelParameteriv(GL_TEXTURE_2D, level, GL_TEXTURE_COMPRESSED_IMAGE_SIZE, &length);
    if (length != blocks * blocks * bytes) {
        printf("drivercheck: the %s atlas comes back as %d bytes, not %d\n", name, length, blocks * blocks * bytes);
        return 0;
    }
    unsigned char *data = malloc(length);
    glGetCompressedTexImage(GL_TEXTURE_2D, level, data);
    int right = glGetError() == GL_NO_ERROR;
    if (!right) printf("drivercheck: the %s atlas couldn't be read back\n", name);
    for (int s = 0; right && s < SPOT_COUNT; s++) {
        int bx = SPOTS[s][0] % blocks, by = SPOTS[s][1] % blocks;
        Texels got, want;
        decode_bc1(data + ((size_t)by * blocks + bx) * bytes + bytes - 8, got);
        right = same(name, "comes back", got[0], 3, atlas_texels(want, bx, by, level), bx, by, level);
    }
    free(data);
    return right;
}

/* ── Vulkan's own word on formats: the few declarations it takes, from vulkan_core.h, which our build lacks ── */

typedef struct { int type; const void *next; const char *name; uint32_t version; const char *engine;
                 uint32_t engine_version, api_version; } VulkanApplication;
typedef struct { int type; const void *next; uint32_t flags; const VulkanApplication *application;
                 uint32_t layers; const char *const *layer_names; uint32_t extensions;
                 const char *const *extension_names; } VulkanInstanceInfo;
typedef struct { uint32_t linear, optimal, buffer; } VulkanFormatProperties;
typedef void (*VulkanFunction)(void);
typedef VulkanFunction (*GetInstanceProcAddr)(void *, const char *);
typedef int (*CreateInstance)(const VulkanInstanceInfo *, const void *, void **);
typedef int (*EnumeratePhysicalDevices)(void *, uint32_t *, void **);
typedef void (*GetProperties)(void *, void *);
typedef void (*GetFormatProperties)(void *, int, VulkanFormatProperties *);
typedef void (*DestroyInstance)(void *, const void *);

#define VULKAN_APPLICATION_INFO 0
#define VULKAN_INSTANCE_CREATE_INFO 1
#define VULKAN_SAMPLED_IMAGE 0x1
/* VkPhysicalDeviceProperties: apiVersion first, deviceName after four more 32-bit fields. */
#define DEVICE_NAME_OFFSET 20

/* The textures run's last line (DriverCheck.kt reads it): Vulkan's version, the device, then what it samples. */
#define FORMATS "drivercheck: formats Vulkan %u.%u (%s): BC %s, ETC2 %s, EAC %s, ASTC %s\n"

static const char *samples(GetFormatProperties get, void *physical, const int *formats, int count)
{
    int sampled = 0;
    for (int i = 0; i < count; i++) {
        VulkanFormatProperties properties = { 0 };
        get(physical, formats[i], &properties);
        sampled += (properties.optimal & VULKAN_SAMPLED_IMAGE) != 0;
    }
    return sampled == count ? "yes" : sampled ? "some" : "no";
}

static void formats(void)
{
    void *loader = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    GetInstanceProcAddr proc = loader ? (GetInstanceProcAddr)dlsym(loader, "vkGetInstanceProcAddr") : NULL;
    CreateInstance create = proc ? (CreateInstance)proc(NULL, "vkCreateInstance") : NULL;
    VulkanApplication application = { VULKAN_APPLICATION_INFO, NULL, "pocketfoundry-drivercheck", 1, NULL, 0,
                                       (1u << 22) | (1u << 12) };
    VulkanInstanceInfo info = { VULKAN_INSTANCE_CREATE_INFO, NULL, 0, &application, 0, NULL, 0, NULL };
    void *instance = NULL, *physical = NULL;
    uint32_t count = 1;
    if (!create || create(&info, NULL, &instance) != 0 || !instance) {
        printf("drivercheck: formats unknown: no Vulkan instance\n");
        return;
    }
    EnumeratePhysicalDevices enumerate = (EnumeratePhysicalDevices)proc(instance, "vkEnumeratePhysicalDevices");
    GetProperties properties = (GetProperties)proc(instance, "vkGetPhysicalDeviceProperties");
    GetFormatProperties format = (GetFormatProperties)proc(instance, "vkGetPhysicalDeviceFormatProperties");
    DestroyInstance destroy = (DestroyInstance)proc(instance, "vkDestroyInstance");
    int result = enumerate ? enumerate(instance, &count, &physical) : -1;
    if ((result != 0 && result != 5 /* VK_INCOMPLETE */) || !physical || !properties || !format) {
        printf("drivercheck: formats unknown: no Vulkan device\n");
    } else {
        // The whole VkPhysicalDeviceProperties is about 800 bytes.
        static unsigned char device[4096];
        properties(physical, device);
        uint32_t version;
        memcpy(&version, device, sizeof(version));
        // VkFormat: BC1 RGBA, BC3, BC4, BC5; ETC2 RGBA8; EAC R11, R11G11; ASTC 4x4, all UNORM.
        static const int bc[] = { 133, 137, 139, 141 }, etc2[] = { 151 }, eac[] = { 153, 155 }, astc[] = { 157 };
        printf(FORMATS, version >> 22 & 0x7f, version >> 12 & 0x3ff, (const char *)device + DEVICE_NAME_OFFSET,
               samples(format, physical, bc, 4), samples(format, physical, etc2, 1), samples(format, physical, eac, 2),
               samples(format, physical, astc, 1));
    }
    if (destroy) destroy(instance, NULL);
}

static void finish(Display *display, GLXContext context, Window window)
{
    glXMakeCurrent(display, None, NULL);
    glXDestroyContext(display, context);
    XDestroyWindow(display, window);
    XCloseDisplay(display);
}

/*
 * Factorio's GPU compressor writes its blocks as integers, then copies them into the atlas: BC3's 16 bytes are one
 * RGBA32UI texel, BC1's 8 one RG32UI texel. Whether 4 x 4 blocks copied in that way read right.
 */
static int copies(GLint at, GLint level)
{
    if (!has_extension("GL_ARB_copy_image")) {
        printf("drivercheck: no ARB_copy_image: blocks can't be copied in\n");
        return 0;
    }
    struct { const char *name; GLenum format, integers, layout; int bytes; } kinds[] = {
        { "copied BC3", GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, GL_RGBA32UI, GL_RGBA_INTEGER, 16 },
        { "copied BC1", GL_COMPRESSED_RGB_S3TC_DXT1_EXT, GL_RG32UI, GL_RG_INTEGER, 8 },
    };
    int right = 1;
    for (size_t k = 0; k < sizeof(kinds) / sizeof(kinds[0]); k++) {
        unsigned char blocks[16 * 16];
        for (int b = 0; b < 16; b++) encode(blocks + b * kinds[k].bytes, kinds[k].bytes, b % 4, b / 4, 0);
        while (glGetError() != GL_NO_ERROR) {}
        GLuint source = texture();
        glTexImage2D(GL_TEXTURE_2D, 0, kinds[k].integers, 4, 4, 0, kinds[k].layout, GL_UNSIGNED_INT, blocks);
        GLuint target = texture();
        glTexImage2D(GL_TEXTURE_2D, 0, kinds[k].format, 16, 16, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        glCopyImageSubData(source, GL_TEXTURE_2D, 0, 0, 0, 0, target, GL_TEXTURE_2D, 0, 0, 0, 0, 4, 4, 1);
        GLenum error = glGetError();
        if (error != GL_NO_ERROR) {
            printf("drivercheck: copying %s blocks in failed with 0x%x\n", kinds[k].name, error);
            right = 0;
        }
        for (int b = 0; right && b < 16; b++) right = read_block(at, level, kinds[k].name, b % 4, b / 4, 0);
        glDeleteTextures(1, &source);
        glDeleteTextures(1, &target);
    }
    return right;
}

/* Exits as the header says. */
static int textures(void)
{
    if (!has_extension("GL_EXT_texture_compression_s3tc")) {
        printf("drivercheck: no S3TC: the game's atlases can't be made\n");
        return 1;
    }
    GLuint program = glCreateProgram();
    GLuint vertex = shader(GL_VERTEX_SHADER, VERTEX_SHADER), fragment = shader(GL_FRAGMENT_SHADER, FETCH_SHADER);
    GLint linked = 0;
    if (vertex && fragment) {
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
    }
    if (!linked) {
        printf("drivercheck: the shaders didn't compile\n");
        return 2;
    }
    glUseProgram(program);
    GLint at = glGetUniformLocation(program, "at"), level = glGetUniformLocation(program, "level");
    static const float strip[] = { -1, -1, 1, -1, -1, 1, 1, 1 };
    glBindVertexArray(square(strip, 4, 2));

    struct { const char *name; GLenum format; int bytes, size, levels; } atlases[] = {
        { "BC3", GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, 16, ATLAS, 1 },
        { "BC1", GL_COMPRESSED_RGB_S3TC_DXT1_EXT, 8, ATLAS, 1 },
        { "mipmapped BC3", GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, 16, MIPMAPPED, 2 },
    };
    int draws = 1, comes_back = 1;
    long takes = -1;
    for (size_t i = 0; i < sizeof(atlases) / sizeof(atlases[0]); i++) {
        long made, filled;
        GLuint t = atlas(atlases[i].format, atlases[i].bytes, atlases[i].size, atlases[i].levels, &made, &filled);
        if (glGetError() == GL_OUT_OF_MEMORY) {
            printf("drivercheck: no memory for the %s atlas\n", atlases[i].name);
            glDeleteTextures(1, &t);
            draws = 0;
            break;
        }
        long compressed = (long)(atlases[i].size / 4) * (atlases[i].size / 4) * atlases[i].bytes >> 20;
        printf("drivercheck: the %s atlas takes %ld MB made, %ld MB filled; %ld MB compressed\n", atlases[i].name,
               made, filled, compressed);
        if (i == 0) takes = made > 0 ? made : filled;
        for (int l = 0; l < atlases[i].levels; l++) {
            int blocks = (atlases[i].size >> l) / 4;
            for (int s = 0; s < SPOT_COUNT; s++)
                draws &= read_block(at, level, atlases[i].name, SPOTS[s][0] % blocks, SPOTS[s][1] % blocks, l);
            comes_back &= read_back(atlases[i].name, atlases[i].bytes, atlases[i].size, l);
        }
        glDeleteTextures(1, &t);
        glFinish();
    }
    int copied = copies(at, level);
    printf("drivercheck: the atlases %s, %s; blocks copied in read %s\n", draws ? "read right" : "read wrong",
           comes_back ? "come back right" : "come back wrong", copied ? "right" : "wrong");
    printf(ATLASES, draws, comes_back, copied, takes, (long)ATLAS / 4 * (ATLAS / 4) * 16 >> 20);
    return 0;
}

int main(int argc, char **argv)
{
    // Line by line, so a driver that crashes still leaves what it got to.
    setvbuf(stdout, NULL, _IOLBF, 0);
    // backtrace() loads its unwinder on first use: here, rather than inside the handler.
    void *first[1];
    backtrace(first, 1);
    static const int fatal[] = { SIGSEGV, SIGBUS, SIGILL, SIGABRT, SIGFPE };
    struct sigaction on_crash = { .sa_sigaction = crashed, .sa_flags = SA_SIGINFO };
    for (size_t i = 0; i < sizeof(fatal) / sizeof(fatal[0]); i++) sigaction(fatal[i], &on_crash, NULL);
    Display *display = XOpenDisplay(NULL);
    if (!display) {
        printf("drivercheck: no X display\n");
        return 2;
    }
    static const int config_attributes[] = {
        GLX_X_RENDERABLE, True, GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT, GLX_RENDER_TYPE, GLX_RGBA_BIT,
        GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, GLX_DOUBLEBUFFER, True, None,
    };
    int configs = 0;
    GLXFBConfig *config = glXChooseFBConfig(display, DefaultScreen(display), config_attributes, &configs);
    CreateContextAttribs create = (CreateContextAttribs)glXGetProcAddressARB((const GLubyte *)"glXCreateContextAttribsARB");
    if (!config || !configs || !create) {
        printf("drivercheck: no OpenGL configuration\n");
        return 2;
    }
    // A window the game's way, never mapped: the squares are drawn into a texture of their own.
    XVisualInfo *visual = glXGetVisualFromFBConfig(display, config[0]);
    Window root = RootWindow(display, visual->screen);
    XSetWindowAttributes window_attributes = { .colormap = XCreateColormap(display, root, visual->visual, AllocNone) };
    Window window = XCreateWindow(display, root, 0, 0, WIDTH, HEIGHT, 0, visual->depth, InputOutput, visual->visual,
                                  CWColormap, &window_attributes);
    static const int context_attributes[] = {
        GLX_CONTEXT_MAJOR_VERSION_ARB, 3, GLX_CONTEXT_MINOR_VERSION_ARB, 3,
        GLX_CONTEXT_PROFILE_MASK_ARB, GLX_CONTEXT_CORE_PROFILE_BIT_ARB, None,
    };
    XSetErrorHandler(x_error);
    GLXContext context = create(display, config[0], NULL, True, context_attributes);
    XSync(display, False);
    if (!context || !glXMakeCurrent(display, window, context)) {
        printf("drivercheck: no OpenGL 3.3 context\n");
        return 2;
    }
    printf("drivercheck: %s\n", (const char *)glGetString(GL_RENDERER));

    GLuint target = texture(), depth, framebuffer;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenRenderbuffers(1, &depth);
    glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        printf("drivercheck: the offscreen picture couldn't be made\n");
        return 2;
    }
    memory();
    if (argc > 1 && !strcmp(argv[1], "textures")) {
        int result = textures();
        finish(display, context, window);
        formats();
        return result;
    }

    GLuint program = glCreateProgram();
    GLuint vertex = shader(GL_VERTEX_SHADER, VERTEX_SHADER), fragment = shader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
    GLint linked = 0;
    if (vertex && fragment) {
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        glGetProgramiv(program, GL_LINK_STATUS, &linked);
    }
    if (!linked) {
        printf("drivercheck: the shaders didn't compile\n");
        return 2;
    }
    glUseProgram(program);
    GLint color = glGetUniformLocation(program, "color"), textured = glGetUniformLocation(program, "textured");

    // The same square as a strip with padding between corners, and as two triangles packed tight: the vertex
    // layout and the primitive both change between draws.
    static const float strip[] = { -1, -1, 0, 0, 1, -1, 0, 0, -1, 1, 0, 0, 1, 1, 0, 0 };
    static const float triangles[] = { -1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1 };
    GLuint padded = square(strip, 4, 4), packed = square(triangles, 6, 2);

    static const unsigned char blue_texels[] = { 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255 };
    GLuint sprite = texture();
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, blue_texels);

    // The game's compressed formats: BC1 and BC3 when OpenGL offers them (S3TC), BC4 and BC5 always (RGTC, core).
    // Each block runs between two ends, its texels mixing them and what lies between, and BC3's alpha is partial on
    // every other texel. The values are ones the compat layer's transcoder keeps within rounding: it turns BC4 and
    // BC5 into EAC, which can't keep every value between two ends exactly.
    unsigned char bc1[8], bc3[16], bc4[8], bc5[16], corner[8], opposite[8], small[8];
    bc1_block(bc1, YELLOW, BLUE, pattern);
    bc4_block(bc3, 255, 96, checkered);
    bc1_block(bc3 + 8, MAGENTA, GREEN, pattern);
    bc4_block(bc4, 192, 64, pattern);
    bc4_block(bc5, 192, 64, pattern);
    bc4_block(bc5 + 8, 64, 192, reversed);
    bc1_block(corner, WHITE, BLACK, pattern);
    bc1_block(opposite, RED, CYAN, reversed);
    bc1_block(small, RED, GREEN, pattern);
    int s3tc = has_extension("GL_EXT_texture_compression_s3tc");
    // The mipmapped texture first: after the transcoder fills part of a texture, Adreno's own driver garbles the next
    // copy. Made last, that would be reading the picture back, and the check would judge the copy, not the drawing.
    GLuint mipmapped_texture = s3tc ? mipmapped(corner, opposite, small) : 0;
    GLuint bc1_texture = s3tc ? compressed(GL_COMPRESSED_RGB_S3TC_DXT1_EXT, bc1, sizeof(bc1)) : 0;
    GLuint bc3_texture = s3tc ? compressed(GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, bc3, sizeof(bc3)) : 0;
    GLuint bc4_texture = compressed(GL_COMPRESSED_RED_RGTC1, bc4, sizeof(bc4));
    GLuint bc5_texture = compressed(GL_COMPRESSED_RG_RGTC2, bc5, sizeof(bc5));
    if (!s3tc) printf("drivercheck: no S3TC, so no BC1, BC3 and mipmapped squares\n");

    glViewport(0, 0, WIDTH, HEIGHT);
    glClearColor(GREY / 255.0f, GREY / 255.0f, GREY / 255.0f, 1);
    glClearDepth(1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    viewport(0, 0);
    glBindVertexArray(padded);
    glUniform1f(textured, 0);
    glUniform4f(color, 1, 0, 0, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    viewport(1, 0);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);
    glFrontFace(GL_CCW);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LESS);
    glBindVertexArray(packed);
    glUniform4f(color, 0, 1, 0, 1);
    glDrawArrays(GL_TRIANGLES, 0, 6);

    glDisable(GL_CULL_FACE);
    glDisable(GL_DEPTH_TEST);
    viewport(0, 1);
    glEnable(GL_SCISSOR_TEST);
    glScissor(0, CELL, CELL, CELL);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glBindVertexArray(padded);
    glBindTexture(GL_TEXTURE_2D, sprite);
    glUniform1f(textured, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisable(GL_SCISSOR_TEST);
    // Middle row, second: keeps the clear colour.

    viewport(0, 2);
    glDisable(GL_BLEND);
    glUniform1f(textured, 0);
    glUniform4f(color, 1, 0, 0, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
    glUniform4f(color, 0, 1, 0, 0.25f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // The game's light map darkens the picture this way.
    viewport(1, 2);
    glDisable(GL_BLEND);
    glUniform4f(color, 1, 0.5f, 1, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glEnable(GL_BLEND);
    glBlendFunc(GL_DST_COLOR, GL_ZERO);
    glUniform4f(color, 0.5f, 0.5f, 1, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Blended like the game's sprites. Shrunk onto 4 x 4 pixels, the mipmapped texture shows its second level.
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glUniform1f(textured, 1);
    const GLuint compressed_squares[] = { bc1_texture, bc3_texture, bc4_texture, bc5_texture };
    for (int i = 0; i < 4; i++) {
        if (!compressed_squares[i]) continue;
        viewport(2 + i % 2, i / 2);
        glBindTexture(GL_TEXTURE_2D, compressed_squares[i]);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    if (mipmapped_texture) {
        glBindTexture(GL_TEXTURE_2D, mipmapped_texture);
        viewport(2, 2);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glViewport(3 * CELL, 2 * CELL, 4, 4);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    glDisable(GL_BLEND);

    // Last, with its own program: the game draws many sprites in one call this way.
    viewport(4, 0);
    GLuint instanced = glCreateProgram();
    GLuint instanced_vertex = shader(GL_VERTEX_SHADER, INSTANCED_VERTEX_SHADER),
           instanced_fragment = shader(GL_FRAGMENT_SHADER, INSTANCED_FRAGMENT_SHADER);
    GLint instanced_linked = 0;
    if (instanced_vertex && instanced_fragment) {
        glAttachShader(instanced, instanced_vertex);
        glAttachShader(instanced, instanced_fragment);
        glLinkProgram(instanced);
        glGetProgramiv(instanced, GL_LINK_STATUS, &instanced_linked);
    }
    if (instanced_linked) {
        // Offset, then colour, for each instance: yellow on the left half, cyan on the right.
        static const float per_instance[] = { -0.5f, 1, 1, 0, 0.5f, 0, 1, 1 };
        glUseProgram(instanced);
        glBindVertexArray(packed);
        GLuint instances;
        glGenBuffers(1, &instances);
        glBindBuffer(GL_ARRAY_BUFFER, instances);
        glBufferData(GL_ARRAY_BUFFER, sizeof(per_instance), per_instance, GL_STATIC_DRAW);
        glVertexAttribPointer(1, 1, GL_FLOAT, GL_FALSE, 4 * sizeof(float), NULL);
        glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void *)sizeof(float));
        glVertexAttribDivisor(1, 1);
        glVertexAttribDivisor(2, 1);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glDrawArraysInstanced(GL_TRIANGLES, 0, 6, 2);
    }

    glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, picture);
    static const Rgb grey = { GREY, GREY, GREY }, kept = { 191, 0, 0 }, multiplied = { 128, 64, 255 };
    Texels want;
    // One square at a time, every wrong one getting its line.
    int right = expect_cell("the red", 0, 0, plain(want, RED));
    right &= expect_cell("the green", 1, 0, plain(want, GREEN));
    right &= expect_cell("the textured", 0, 1, plain(want, BLUE));
    right &= expect_cell("the cleared", 1, 1, plain(want, grey));
    right &= expect_cell("the darkened", 0, 2, plain(want, kept));
    right &= expect_cell("the multiplied", 1, 2, plain(want, multiplied));
    if (instanced_linked) {
        for (int t = 0; t < 16; t++) memcpy(want[t], t % 4 < 2 ? YELLOW : CYAN, 3);
        right &= expect_cell("the instanced", 4, 0, want);
    } else {
        printf("drivercheck: the instanced square's shaders didn't compile\n");
        right = 0;
    }
    if (s3tc) {
        right &= expect_cell("the BC1", 2, 0, bc1_texels(want, YELLOW, BLUE, pattern));
        bc1_texels(want, MAGENTA, GREEN, pattern);
        for (int t = 0; t < 16; t++) {
            int alpha = on_line(255, 96, checkered(t % 4, t / 4), 7);
            for (int c = 0; c < 3; c++) want[t][c] = (want[t][c] * alpha + GREY * (255 - alpha) + 127) / 255;
        }
        right &= expect_cell("the BC3", 3, 0, want);
        right &= expect("the mipmapped", 2 * CELL, 2 * CELL, CELL / 8, bc1_texels(want, WHITE, BLACK, pattern));
        right &= expect("the mipmapped", 2 * CELL + CELL / 2, 2 * CELL + CELL / 2, CELL / 8,
                        bc1_texels(want, RED, CYAN, reversed));
        right &= expect("the second mipmap level's", 3 * CELL, 2 * CELL, 1, bc1_texels(want, RED, GREEN, pattern));
    }
    plain(want, BLACK);
    channel(want, 0, 192, 64, 7, pattern);
    right &= expect_cell("the BC4", 2, 1, want);
    channel(want, 1, 64, 192, 5, reversed);
    right &= expect_cell("the BC5", 3, 1, want);
    printf("drivercheck: %s\n", right ? "the picture is right" : "the picture is wrong");

    finish(display, context, window);
    return right ? 0 : 1;
}
