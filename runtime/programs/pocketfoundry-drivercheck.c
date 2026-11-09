/*
 * The driver check: draws twelve squares the way the game draws (shaders, textures, blending, state that changes
 * between draws, and textures compressed in the formats the game uses, filled in pieces and mipmapped) into an
 * offscreen picture through the game's own OpenGL, then reads the picture back. Exits 0 when every square came out
 * right, 1 when the picture is wrong, 2 when OpenGL didn't start, and 128 + the signal when one kills it, after
 * saying where (DriverCheck.kt reads these).
 */
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>
#include <X11/Xlib.h>
#include <execinfo.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>

#define CELL 32
#define WIDTH (4 * CELL)
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

int main(void)
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
    GLXContext context = create(display, config[0], NULL, True, context_attributes);
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

    glXMakeCurrent(display, None, NULL);
    glXDestroyContext(display, context);
    XDestroyWindow(display, window);
    XCloseDisplay(display);
    return right ? 0 : 1;
}
