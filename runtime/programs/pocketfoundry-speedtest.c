/*
 * The speed test: frames like the game's, through its own OpenGL, into a window the size of the game's screen, as
 * fast as the driver goes. A frame is a ground of opaque tiles, thousands of blended sprites from one big mipmapped
 * texture, sent anew every frame in batches whose blending changes between them, and a light map multiplied over it
 * all. The texture is compressed the way the game compresses its sprites, where the driver takes that format. The
 * scene is the same at every size, as the game's view is at every resolution: only the pixels change.
 *
 *   pocketfoundry-speedtest 1624x770 812x385
 *   speedtest: 1624x770 63 frames in 1508 ms
 *
 * Without sizes it tests the X screen's, and half of it each way: debug.env's test.program runs it that way.
 *
 * Exits 0 when it measured every size, 1 on a size it can't read, 2 when OpenGL didn't start (SpeedTest.kt reads
 * these).
 */
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>
#include <X11/Xlib.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define ATLAS 4096
/* Each sprite's square in the atlas. */
#define CELL 64
#define CELLS (ATLAS / CELL)
#define TILES_X 40
#define TILES_Y 20
#define TILES (TILES_X * TILES_Y)
#define SPRITES 3000
#define BATCH 100
/* The sprites cover the screen this many times over: with the ground and the light map, six layers. */
#define COVERAGE 4.0f
#define QUADS (TILES + SPRITES + 1)
#define WARM_UP_FRAMES 5
#define MEASURE_MS 1500
#define MIN_FRAMES 2

typedef GLXContext (*CreateContextAttribs)(Display *, GLXFBConfig, GLXContext, Bool, const int *);
typedef void (*SwapIntervalExt)(Display *, GLXDrawable, int);
typedef int (*SwapIntervalMesa)(unsigned);

static const char *VERTEX_SHADER =
    "#version 330 core\n"
    "layout(location = 0) in vec2 position;\n"
    "layout(location = 1) in vec2 coordinates;\n"
    "layout(location = 2) in vec4 tint;\n"
    "out vec2 uv;\n"
    "out vec4 colour;\n"
    "void main() { uv = coordinates; colour = tint; gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0); }\n";

static const char *FRAGMENT_SHADER =
    "#version 330 core\n"
    "uniform sampler2D atlas;\n"
    "in vec2 uv;\n"
    "in vec4 colour;\n"
    "out vec4 fragment;\n"
    "void main() { fragment = texture(atlas, uv) * colour; }\n";

typedef struct {
    float x, y, u, v;
    unsigned char tint[4];
} Vertex;

static Vertex vertices[QUADS * 4];
static GLuint indices[QUADS * 6];

static double now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

/* Repeatable noise. */
static unsigned hash(unsigned x)
{
    x ^= x >> 16;
    x *= 0x7feb352d;
    x ^= x >> 15;
    x *= 0x846ca68b;
    x ^= x >> 16;
    return x;
}

static float unit(unsigned x) { return (hash(x) & 0xffffff) / (float)0x1000000; }

static GLuint shader(GLenum type, const char *source)
{
    GLuint s = glCreateShader(type);
    GLint ok = 0;
    glShaderSource(s, 1, &source, NULL);
    glCompileShader(s);
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    return ok ? s : 0;
}

static int has_extension(const char *name)
{
    GLint count = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    for (GLint i = 0; i < count; i++)
        if (!strcmp((const char *)glGetStringi(GL_EXTENSIONS, i), name)) return 1;
    return 0;
}

/* Round sprites with soft edges, like the game's: their corners blend away. */
static const int INNER = (CELL / 2 - 4) * (CELL / 2 - 4), OUTER = (CELL / 2) * (CELL / 2);

static int distance(int x, int y)
{
    int dx = x % CELL - CELL / 2, dy = y % CELL - CELL / 2;
    return dx * dx + dy * dy;
}

static unsigned rgb565(int r, int g, int b) { return (r >> 3) << 11 | (g >> 2) << 5 | b >> 3; }

/*
 * As the game sends its sprites where the driver takes S3TC: DXT5 ("DXT: low-quality" in its log), made here level
 * by level. PanVK has no hardware for it and unpacks each texel it reads, which is much of what a frame costs there.
 * Each block runs between two colours of its sprite; its alpha is 255 (index 0), 0 (index 1) or about half (index 4).
 */
static void compressed_atlas(void)
{
    unsigned char *blocks = malloc((size_t)(ATLAS / 4) * (ATLAS / 4) * 16);
    int level = 0;
    for (int size = ATLAS; size >= 4; size /= 2, level++) {
        int across = size / 4;
        for (int by = 0; by < across; by++) {
            for (int bx = 0; bx < across; bx++) {
                unsigned char *block = blocks + ((size_t)by * across + bx) * 16;
                int x = bx * 4 << level, y = by * 4 << level;
                uint64_t alpha = 0;
                for (int t = 0; t < 16; t++) {
                    int d = distance((bx * 4 + t % 4) << level, (by * 4 + t / 4) << level);
                    alpha |= (uint64_t)(d < INNER ? 0 : d < OUTER ? 4 : 1) << (3 * t);
                }
                block[0] = 255;
                block[1] = 0;
                for (int i = 0; i < 6; i++) block[2 + i] = alpha >> (8 * i);
                unsigned colour = hash(y / CELL * CELLS + x / CELL);
                unsigned noise = hash((unsigned)level << 26 ^ (by * across + bx));
                int r = (colour & 0xff) / 2, g = (colour >> 8 & 0xff) / 2, b = (colour >> 16 & 0xff) / 2;
                unsigned light = rgb565(r + 64, g + 64, b + 64), dark = rgb565(r, g, b);
                block[8] = light;
                block[9] = light >> 8;
                block[10] = dark;
                block[11] = dark >> 8;
                memcpy(block + 12, &noise, 4);
            }
        }
        glCompressedTexImage2D(GL_TEXTURE_2D, level, GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, size, size, 0,
                               across * across * 16, blocks);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, level - 1);
    free(blocks);
}

/* Where the game's sprites stay uncompressed too. */
static void plain_atlas(void)
{
    unsigned char *pixels = malloc((size_t)ATLAS * ATLAS * 4);
    for (int y = 0; y < ATLAS; y++) {
        for (int x = 0; x < ATLAS; x++) {
            unsigned char *p = pixels + ((size_t)y * ATLAS + x) * 4;
            unsigned noise = hash(y * ATLAS + x), colour = hash(y / CELL * CELLS + x / CELL);
            int d = distance(x, y);
            p[0] = (colour & 0xff) / 2 + (noise & 63);
            p[1] = (colour >> 8 & 0xff) / 2 + (noise >> 6 & 63);
            p[2] = (colour >> 16 & 0xff) / 2 + (noise >> 12 & 63);
            p[3] = d < INNER ? 255 : d < OUTER ? 128 : 0;
        }
    }
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ATLAS, ATLAS, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glGenerateMipmap(GL_TEXTURE_2D);
    free(pixels);
}

static void make_atlas(void)
{
    GLuint texture;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    int s3tc = has_extension("GL_EXT_texture_compression_s3tc");
    printf("speedtest: sprites %s\n", s3tc ? "compressed as DXT5" : "uncompressed: no S3TC");
    if (s3tc) compressed_atlas();
    else plain_atlas();
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
}

static void quad(int index, float x, float y, float w, float h, unsigned cell, unsigned char shade)
{
    float u = (float)(cell % CELLS) / CELLS, v = (float)(cell / CELLS % CELLS) / CELLS, side = 1.0f / CELLS;
    static const float corners[4][2] = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 } };
    for (int c = 0; c < 4; c++) {
        Vertex *vertex = &vertices[index * 4 + c];
        vertex->x = x + w * corners[c][0];
        vertex->y = y + h * corners[c][1];
        vertex->u = u + side * corners[c][0];
        vertex->v = v + side * corners[c][1];
        memset(vertex->tint, shade, 4);
    }
}

/* Frame [frame]'s scene, in the window's 0 to 1 on both sides: the sprites move, so it's sent anew each frame. */
static void scene(int frame)
{
    int index = 0;
    for (int y = 0; y < TILES_Y; y++)
        for (int x = 0; x < TILES_X; x++)
            quad(index++, (float)x / TILES_X, (float)y / TILES_Y, 1.0f / TILES_X, 1.0f / TILES_Y,
                 hash(y * TILES_X + x) % 64, 255);
    const float side = sqrtf(COVERAGE / SPRITES);
    for (int i = 0; i < SPRITES; i++) {
        float speed = unit(i * 3 + 2) * 0.004f, x = fmodf(unit(i * 3) + frame * speed, 1.0f);
        quad(index++, x - side / 2, unit(i * 3 + 1) - side / 2, side, side, 64 + hash(i) % (CELLS * CELLS - 64),
             160 + i % 96);
    }
    quad(index, 0, 0, 1, 1, CELLS * CELLS - 1, 255);
}

static void draw(int frame)
{
    scene(frame);
    // Orphaned, then filled: a new buffer each frame, as the game streams its sprites.
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), NULL, GL_STREAM_DRAW);
    glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(vertices), vertices);
    glDisable(GL_BLEND);
    glDrawElements(GL_TRIANGLES, TILES * 6, GL_UNSIGNED_INT, NULL);
    glEnable(GL_BLEND);
    for (int first = 0; first < SPRITES; first += BATCH) {
        // Straight and premultiplied alpha by turns, as the game's sprites and its effects.
        if (first / BATCH % 2) glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        else glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDrawElements(GL_TRIANGLES, BATCH * 6, GL_UNSIGNED_INT, (void *)((TILES + first) * 6 * sizeof(GLuint)));
    }
    glBlendFunc(GL_DST_COLOR, GL_ZERO);
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, (void *)((TILES + SPRITES) * 6 * sizeof(GLuint)));
}

/* Frames as fast as the driver takes them: the display's refresh doesn't hold them back. */
static void no_vsync(Display *display, Window window)
{
    const char *extensions = glXQueryExtensionsString(display, DefaultScreen(display));
    if (extensions && strstr(extensions, "GLX_EXT_swap_control")) {
        SwapIntervalExt set = (SwapIntervalExt)glXGetProcAddressARB((const GLubyte *)"glXSwapIntervalEXT");
        if (set) set(display, window, 0);
    } else if (extensions && strstr(extensions, "GLX_MESA_swap_control")) {
        SwapIntervalMesa set = (SwapIntervalMesa)glXGetProcAddressARB((const GLubyte *)"glXSwapIntervalMESA");
        if (set) set(0);
    }
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    int widths[8], heights[8], sizes = 0;
    for (int i = 1; i < argc && sizes < 8; i++, sizes++) {
        if (sscanf(argv[i], "%dx%d", &widths[sizes], &heights[sizes]) != 2 || widths[sizes] < 1 || heights[sizes] < 1) {
            printf("speedtest: %s isn't a size\n", argv[i]);
            return 1;
        }
    }
    Display *display = XOpenDisplay(NULL);
    if (!display) {
        printf("speedtest: no X display\n");
        return 2;
    }
    if (!sizes) {
        widths[0] = DisplayWidth(display, DefaultScreen(display));
        heights[0] = DisplayHeight(display, DefaultScreen(display));
        widths[1] = widths[0] / 2;
        heights[1] = heights[0] / 2;
        sizes = 2;
    }
    static const int config_attributes[] = {
        GLX_X_RENDERABLE, True, GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT, GLX_RENDER_TYPE, GLX_RGBA_BIT,
        GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, GLX_DOUBLEBUFFER, True, None,
    };
    int configs = 0;
    GLXFBConfig *config = glXChooseFBConfig(display, DefaultScreen(display), config_attributes, &configs);
    CreateContextAttribs create =
        (CreateContextAttribs)glXGetProcAddressARB((const GLubyte *)"glXCreateContextAttribsARB");
    if (!config || !configs || !create) {
        printf("speedtest: no OpenGL configuration\n");
        return 2;
    }
    // Shown, so frames go all the way to the X server as the game's do; the window manager leaves it be.
    XVisualInfo *visual = glXGetVisualFromFBConfig(display, config[0]);
    Window root = RootWindow(display, visual->screen);
    XSetWindowAttributes window_attributes = {
        .colormap = XCreateColormap(display, root, visual->visual, AllocNone),
        .override_redirect = True,
    };
    Window window = XCreateWindow(display, root, 0, 0, widths[0], heights[0], 0, visual->depth, InputOutput,
                                  visual->visual, CWColormap | CWOverrideRedirect, &window_attributes);
    XMapWindow(display, window);
    XSync(display, False);
    static const int context_attributes[] = {
        GLX_CONTEXT_MAJOR_VERSION_ARB, 3, GLX_CONTEXT_MINOR_VERSION_ARB, 3,
        GLX_CONTEXT_PROFILE_MASK_ARB, GLX_CONTEXT_CORE_PROFILE_BIT_ARB, None,
    };
    GLXContext context = create(display, config[0], NULL, True, context_attributes);
    if (!context || !glXMakeCurrent(display, window, context)) {
        printf("speedtest: no OpenGL 3.3 context\n");
        return 2;
    }
    printf("speedtest: %s\n", (const char *)glGetString(GL_RENDERER));
    no_vsync(display, window);

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
        printf("speedtest: the shaders didn't compile\n");
        return 2;
    }
    glUseProgram(program);
    make_atlas();

    GLuint vao, vbo, ibo;
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), (void *)offsetof(Vertex, x));
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), (void *)offsetof(Vertex, u));
    glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, GL_TRUE, sizeof(Vertex), (void *)offsetof(Vertex, tint));
    for (int a = 0; a < 3; a++) glEnableVertexAttribArray(a);
    for (int q = 0; q < QUADS; q++) {
        static const GLuint corners[6] = { 0, 1, 2, 2, 1, 3 };
        for (int c = 0; c < 6; c++) indices[q * 6 + c] = q * 4 + corners[c];
    }
    glGenBuffers(1, &ibo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(indices), indices, GL_STATIC_DRAW);

    int frame = 0;
    for (int s = 0; s < sizes; s++) {
        XResizeWindow(display, window, widths[s], heights[s]);
        XSync(display, False);
        glViewport(0, 0, widths[s], heights[s]);
        // The first frames at a size make its swapchain and the driver's pipelines.
        for (int i = 0; i < WARM_UP_FRAMES; i++, frame++) {
            draw(frame);
            glXSwapBuffers(display, window);
        }
        glFinish();
        double start = now_ms(), elapsed = 0;
        int frames = 0;
        while (elapsed < MEASURE_MS || frames < MIN_FRAMES) {
            draw(frame++);
            glXSwapBuffers(display, window);
            frames++;
            elapsed = now_ms() - start;
        }
        glFinish();
        elapsed = now_ms() - start;
        printf("speedtest: %dx%d %d frames in %.0f ms\n", widths[s], heights[s], frames, elapsed);
    }

    glXMakeCurrent(display, None, NULL);
    glXDestroyContext(display, context);
    XDestroyWindow(display, window);
    XCloseDisplay(display);
    return 0;
}
