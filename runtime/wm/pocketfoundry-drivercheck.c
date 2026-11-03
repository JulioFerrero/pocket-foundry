/*
 * The driver check: draws eight squares the way the game draws (shaders, textures, blending, state that changes between
 * draws, and textures compressed in the formats the game uses) into an offscreen picture through the game's own OpenGL,
 * then reads the picture back. Exits 0 when every square came out right, 1 when the picture is wrong, 2 when OpenGL
 * didn't start.
 */
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>
#include <X11/Xlib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* A grid of 4 x 2 cells. */
#define CELL 32
#define WIDTH (4 * CELL)
#define HEIGHT (2 * CELL)

typedef GLXContext (*CreateContextAttribs)(Display *, GLXFBConfig, GLXContext, Bool, const int *);

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

/*
 * One 4x4 block of each compressed format, all of one colour: endpoint 0 is the colour and every index picks it.
 * BC1 yellow (RGB565 0xFFE0); BC3 magenta, opaque; BC4 red 200; BC5 red 200, green 100.
 */
static const unsigned char BC1_BLOCK[8] = { 0xE0, 0xFF, 0, 0, 0, 0, 0, 0 };
static const unsigned char BC3_BLOCK[16] = { 255, 0, 0, 0, 0, 0, 0, 0, 0x1F, 0xF8, 0, 0, 0, 0, 0, 0 };
static const unsigned char BC4_BLOCK[8] = { 200, 0, 0, 0, 0, 0, 0, 0 };
static const unsigned char BC5_BLOCK[16] = { 200, 0, 0, 0, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0 };

static GLuint shader(GLenum type, const char *source)
{
    GLuint s = glCreateShader(type);
    GLint ok = 0;
    glShaderSource(s, 1, &source, NULL);
    glCompileShader(s);
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    return ok ? s : 0;
}

/* A square over the whole viewport: [count] corners in a buffer of their own, [stride] floats apart. */
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

/* A 4x4 texture of one compressed [block], made the way the game makes its atlases: empty first, then filled in. */
static GLuint compressed(GLenum format, const unsigned char *block, GLsizei size)
{
    GLuint t = texture();
    glTexImage2D(GL_TEXTURE_2D, 0, format, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glCompressedTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, 4, format, size, block);
    return t;
}

static void viewport(int column, int row)
{
    glViewport(column * CELL, row * CELL, CELL, CELL);
}

/* Whether the pixel at the middle of the cell at [column], [row] is [want] (RGB), give or take rounding. */
static int expect(const char *name, int column, int row, const unsigned char *want)
{
    unsigned char got[4] = {0};
    glReadPixels(column * CELL + CELL / 2, row * CELL + CELL / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, got);
    for (int i = 0; i < 3; i++) {
        if (abs(got[i] - want[i]) > 8) {
            printf("drivercheck: %s square is %d,%d,%d instead of %d,%d,%d\n", name, got[0], got[1], got[2],
                   want[0], want[1], want[2]);
            return 0;
        }
    }
    return 1;
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

    GLuint picture = texture(), depth, framebuffer;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenRenderbuffers(1, &depth);
    glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, picture, 0);
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
    int s3tc = has_extension("GL_EXT_texture_compression_s3tc");
    GLuint bc1 = s3tc ? compressed(GL_COMPRESSED_RGB_S3TC_DXT1_EXT, BC1_BLOCK, sizeof(BC1_BLOCK)) : 0;
    GLuint bc3 = s3tc ? compressed(GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, BC3_BLOCK, sizeof(BC3_BLOCK)) : 0;
    GLuint bc4 = compressed(GL_COMPRESSED_RED_RGTC1, BC4_BLOCK, sizeof(BC4_BLOCK));
    GLuint bc5 = compressed(GL_COMPRESSED_RG_RGTC2, BC5_BLOCK, sizeof(BC5_BLOCK));
    if (!s3tc) printf("drivercheck: no S3TC, so no BC1 and BC3 squares\n");

    glViewport(0, 0, WIDTH, HEIGHT);
    glClearColor(0.25f, 0.25f, 0.25f, 1);
    glClearDepth(1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    // Bottom row, first: red, nothing else on.
    viewport(0, 0);
    glBindVertexArray(padded);
    glUniform1f(textured, 0);
    glUniform4f(color, 1, 0, 0, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Bottom row, second: green, culling back faces and testing depth.
    viewport(1, 0);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);
    glFrontFace(GL_CCW);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LESS);
    glBindVertexArray(packed);
    glUniform4f(color, 0, 1, 0, 1);
    glDrawArrays(GL_TRIANGLES, 0, 6);

    // Top row, first: the blue texture, blended, inside a scissor.
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
    // Top row, second: keeps the clear colour.

    // The compressed squares, blended like the game's sprites: BC1 and BC3 on the bottom row, BC4 and BC5 on top.
    const GLuint compressed_squares[] = { bc1, bc3, bc4, bc5 };
    for (int i = 0; i < 4; i++) {
        if (!compressed_squares[i]) continue;
        viewport(2 + i % 2, i / 2);
        glBindTexture(GL_TEXTURE_2D, compressed_squares[i]);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    glDisable(GL_BLEND);

    glFinish();
    static const unsigned char red[] = { 255, 0, 0 }, green[] = { 0, 255, 0 }, blue[] = { 0, 0, 255 },
                               grey[] = { 64, 64, 64 }, yellow[] = { 255, 255, 0 }, magenta[] = { 255, 0, 255 },
                               bc4_red[] = { 200, 0, 0 }, bc5_red_green[] = { 200, 100, 0 };
    // & rather than &&: every wrong square gets its line.
    int right = expect("the red", 0, 0, red) & expect("the green", 1, 0, green) &
                expect("the textured", 0, 1, blue) & expect("the cleared", 1, 1, grey) &
                (!s3tc || (expect("the BC1", 2, 0, yellow) & expect("the BC3", 3, 0, magenta))) &
                expect("the BC4", 2, 1, bc4_red) & expect("the BC5", 3, 1, bc5_red_green);
    printf("drivercheck: %s\n", right ? "the picture is right" : "the picture is wrong");

    glXMakeCurrent(display, None, NULL);
    glXDestroyContext(display, context);
    XDestroyWindow(display, window);
    XCloseDisplay(display);
    return right ? 0 : 1;
}
