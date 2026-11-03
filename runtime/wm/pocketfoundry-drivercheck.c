/*
 * The driver check: draws four squares the way the game draws (shaders, a texture, blending, and state that changes
 * between draws) into an offscreen picture through the game's own OpenGL, then reads the picture back. Exits 0 when
 * every square came out right, 1 when the picture is wrong, 2 when OpenGL didn't start.
 */
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <GL/glx.h>
#include <X11/Xlib.h>
#include <stdio.h>
#include <stdlib.h>

#define SIZE 64
#define HALF (SIZE / 2)

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

/* Whether the pixel at the middle of the quarter at [x], [y] is [want] (RGB), give or take rounding. */
static int expect(const char *name, int x, int y, const unsigned char *want)
{
    unsigned char got[4] = {0};
    glReadPixels(x + HALF / 2, y + HALF / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, got);
    for (int i = 0; i < 3; i++) {
        if (abs(got[i] - want[i]) > 8) {
            printf("drivercheck: %s square is %d,%d,%d instead of %d,%d,%d\n", name, got[0], got[1], got[2],
                   want[0], want[1], want[2]);
            return 0;
        }
    }
    return 1;
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
    Window window = XCreateWindow(display, root, 0, 0, SIZE, SIZE, 0, visual->depth, InputOutput, visual->visual,
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

    GLuint picture, depth, framebuffer;
    glGenTextures(1, &picture);
    glBindTexture(GL_TEXTURE_2D, picture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenRenderbuffers(1, &depth);
    glBindRenderbuffer(GL_RENDERBUFFER, depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, SIZE, SIZE);
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
    GLuint sprite;
    glGenTextures(1, &sprite);
    glBindTexture(GL_TEXTURE_2D, sprite);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, blue_texels);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    glViewport(0, 0, SIZE, SIZE);
    glClearColor(0.25f, 0.25f, 0.25f, 1);
    glClearDepth(1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    // Bottom left: red, nothing else on.
    glViewport(0, 0, HALF, HALF);
    glBindVertexArray(padded);
    glUniform1f(textured, 0);
    glUniform4f(color, 1, 0, 0, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Bottom right: green, culling back faces and testing depth.
    glViewport(HALF, 0, HALF, HALF);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);
    glFrontFace(GL_CCW);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LESS);
    glBindVertexArray(packed);
    glUniform4f(color, 0, 1, 0, 1);
    glDrawArrays(GL_TRIANGLES, 0, 6);

    // Top left: the blue texture, blended, inside a scissor.
    glDisable(GL_CULL_FACE);
    glDisable(GL_DEPTH_TEST);
    glViewport(0, HALF, HALF, HALF);
    glEnable(GL_SCISSOR_TEST);
    glScissor(0, HALF, HALF, HALF);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glBindVertexArray(padded);
    glUniform1f(textured, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    // Top right keeps the clear colour.

    glFinish();
    static const unsigned char red[] = { 255, 0, 0 }, green[] = { 0, 255, 0 }, blue[] = { 0, 0, 255 },
                               grey[] = { 64, 64, 64 };
    // & rather than &&: every wrong square gets its line.
    int right = expect("the red", 0, 0, red) & expect("the green", HALF, 0, green) &
                expect("the textured", 0, HALF, blue) & expect("the cleared", HALF, HALF, grey);
    printf("drivercheck: %s\n", right ? "the picture is right" : "the picture is wrong");

    glXMakeCurrent(display, None, NULL);
    glXDestroyContext(display, context);
    XDestroyWindow(display, window);
    XCloseDisplay(display);
    return right ? 0 : 1;
}
