// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreR-Proprietary
//
// EmuCoreR libretro frontend for the SwanStation PlayStation 1 core.
//
// The core is linked directly (libswanstation_libretro_android.so) and driven
// through the libretro API. This file owns:
//   * environment negotiation (options, directories, logging, AV info),
//   * software framebuffer presentation into an ANativeWindow,
//   * PCM capture from retro_audio_sample_batch + AAudio output,
//   * controller state forwarding into retro_input_state,
//   * save-state (retro_serialize) file IO.
//
// Hardware renderers (OpenGL ES / Vulkan) are negotiated through
// RETRO_ENVIRONMENT_SET_HW_RENDER. When the frontend cannot provide a context
// the core transparently falls back to its software renderer.

#include <jni.h>
#include <deque>
#include <chrono>
#include <signal.h>
#include <pthread.h>
#include <unistd.h>
#include <dlfcn.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <aaudio/AAudio.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include "libretro.h"
#include "shader_chain.h"
#include "vulkan_frontend.h"

#if defined(EMUCORER_HAVE_LIBRASHADER)
#include <librashader.h>

extern "C" void EmuCoreR_ResetGLProgramCache();
#endif

namespace vulkan = emucorer::vulkan;

#define LOG_TAG "EmuCoreR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Core option keys understood by the SwanStation libretro core.
// ---------------------------------------------------------------------------

// Read-only snapshots: never replace handlers or change memory protection.
static void GooseDiagnosticSnapshot(const char* stage) {
    sigset_t mask;
    const int mask_result = pthread_sigmask(SIG_SETMASK, nullptr, &mask);
    for (int number : {SIGSEGV, SIGBUS}) {
        struct sigaction action{};
        if (sigaction(number, nullptr, &action) != 0) {
            LOGW("GOOSE-DIAG %s signal=%d query failed", stage, number);
            continue;
        }
        void* handler = (action.sa_flags & SA_SIGINFO)
            ? reinterpret_cast<void*>(action.sa_sigaction)
            : reinterpret_cast<void*>(action.sa_handler);
        Dl_info info{};
        dladdr(handler, &info);
        LOGI("GOOSE-DIAG %s tid=%d signal=%d handler=%p library=%s symbol=%s flags=0x%x blocked=%d page=%ld",
             stage, static_cast<int>(gettid()), number, handler,
             info.dli_fname ? info.dli_fname : "unknown",
             info.dli_sname ? info.dli_sname : "unknown",
             static_cast<unsigned>(action.sa_flags),
             mask_result == 0 ? sigismember(&mask, number) : -1,
             sysconf(_SC_PAGESIZE));
    }
}

// Messages are copied on receipt; the core owns the original pointers.
struct GooseNotice { std::string text; unsigned duration; std::chrono::steady_clock::time_point received; };
static std::mutex goose_notice_mutex;
static std::deque<GooseNotice> goose_notices;
static void QueueGooseNotice(const char* text, unsigned duration) {
    if (!text || !*text) return;
    std::lock_guard<std::mutex> lock(goose_notice_mutex);
    if (goose_notices.size() >= 32) goose_notices.pop_front();
    goose_notices.push_back({std::string(text).substr(0, 4096),
        std::clamp(duration, 500u, 15000u), std::chrono::steady_clock::now()});
    LOGI("GOOSE-NOTICE %s", text);
}

namespace option_key {
constexpr const char* kRenderer = "swanstation_GPU_Renderer";
constexpr const char* kAspectRatio = "swanstation_Display_AspectRatio";
constexpr const char* kBiosNtscJ = "swanstation_BIOS_PathNTSCJ";
constexpr const char* kBiosNtscU = "swanstation_BIOS_PathNTSCU";
constexpr const char* kBiosPal = "swanstation_BIOS_PathPAL";
}  // namespace option_key

// The core only recognizes the DualShock subclass of RETRO_DEVICE_ANALOG; the
// bare RETRO_DEVICE_ANALOG value is unknown to it and disconnects the port.
#define RETRO_DEVICE_PS_DUALSHOCK RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_ANALOG, 0)

// GTK/desktop style renderer integer used by the Kotlin layer.
namespace core_renderer {
constexpr int kSoftware = 0;
constexpr int kVulkan = 1;
constexpr int kOpenGl = 2;
}  // namespace core_renderer

namespace {

constexpr size_t kAudioRingCapacityFrames = 48000;  // ~1s at 48 kHz stereo.
// Number of frames used to ramp audio in/out around a discontinuity. Long
// enough to remove the click, short enough to stay imperceptible.
constexpr int32_t kAudioDeclickFrames = 48;

// PlayStation memory card image geometry (128 KiB = 1024 frames of 128 bytes).
constexpr size_t kMemoryCardSize = 128 * 1024;
constexpr size_t kMemoryCardFrameSize = 128;

uint8_t MemoryCardFrameChecksum(const uint8_t* frame) {
    uint8_t checksum = frame[0];
    for (size_t i = 1; i < kMemoryCardFrameSize - 1; i++) checksum ^= frame[i];
    return checksum;
}

/** Builds a formatted card identical to the core's MemoryCardImage::Format. */
std::vector<uint8_t> BuildFormattedMemoryCard() {
    std::vector<uint8_t> data(kMemoryCardSize, 0xFF);
    // First block: header, directory and broken-sector list (16 frames).
    for (size_t frame = 0; frame < 16; frame++) {
        uint8_t* fptr = data.data() + frame * kMemoryCardFrameSize;
        std::fill_n(fptr, kMemoryCardFrameSize, uint8_t(0x00));
        if (frame == 0) {
            fptr[0] = 'M';
            fptr[1] = 'C';
        } else {
            fptr[0] = 0xA0;  // free directory entry
            fptr[8] = 0xFF;
            fptr[9] = 0xFF;
        }
        fptr[0x7F] = MemoryCardFrameChecksum(fptr);
    }
    for (size_t frame = 16; frame < 36; frame++) {
        uint8_t* fptr = data.data() + frame * kMemoryCardFrameSize;
        std::fill_n(fptr, kMemoryCardFrameSize, uint8_t(0x00));
        fptr[0] = 0xFF;
        fptr[1] = 0xFF;
        fptr[2] = 0xFF;
        fptr[3] = 0xFF;
        fptr[8] = 0xFF;
        fptr[9] = 0xFF;
        fptr[0x7F] = MemoryCardFrameChecksum(fptr);
    }
    for (size_t frame = 36; frame < 63; frame++) {
        uint8_t* fptr = data.data() + frame * kMemoryCardFrameSize;
        std::fill_n(fptr, kMemoryCardFrameSize, uint8_t(0x00));
    }
    // Write test frame mirrors the header frame.
    std::memcpy(data.data() + 63 * kMemoryCardFrameSize, data.data(), kMemoryCardFrameSize);
    return data;
}

// ---------------------------------------------------------------------------
// Frontend state. The libretro core is a process singleton, so this state is
// global and only accessed from the emulation thread plus short JNI calls.
// ---------------------------------------------------------------------------
struct FrontendState {
    std::mutex mutex;
    // Serialises retro_init/retro_deinit/retro_load_game. Never taken by the
    // environment callback, so core-initiated environment queries cannot
    // deadlock against a caller that owns [mutex].
    std::mutex core_mutex;

    // Directories handed to the core through the environment callback.
    std::string system_dir;
    std::string save_dir;
    std::string core_assets_dir;

    // Presentation surface.
    ANativeWindow* window = nullptr;
    int requested_renderer = core_renderer::kSoftware;
    int current_window_width = 0;
    int current_window_height = 0;
    // Bumped on every attach/detach so the frame thread can rebuild its EGL
    // surface on the correct thread.
    uint32_t window_generation = 0;

    // Core options (overrides only). GET_VARIABLE falls back to the core default
    // when a key is absent, which is what we want for untouched settings.
    std::unordered_map<std::string, std::string> options;
    // Set whenever an option changes after boot so the core re-reads them
    // through GET_VARIABLE_UPDATE on its next frame.
    std::atomic<bool> options_dirty{false};

    // Last frame geometry reported by the core.
    unsigned frame_width = 0;
    unsigned frame_height = 0;

    // Presentation aspect ratio taken from the core's AV info. The core is
    // only queried when it announces new geometry or when an option that can
    // alter the frame aspect changes, instead of on every presented frame.
    double aspect_ratio = 4.0 / 3.0;
    std::atomic<bool> av_info_refresh_pending{true};

    // Software frame format requested through SET_PIXEL_FORMAT.
    int pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;

    // Audio ring buffer (interleaved stereo int16 frames).
    std::mutex audio_mutex;
    std::vector<int16_t> audio_ring;
    size_t audio_read_frame = 0;
    size_t audio_write_frame = 0;

    // Declick counter shared between the producer (ring drops) and the output
    // callback (underruns). While it is non-zero the callback ramps real
    // samples back in so a discontinuity never turns into an audible click.
    std::atomic<int32_t> audio_declick_frames{0};

    // AAudio output configuration, applied when the next stream is opened.
    std::atomic<int> audio_output_latency_ms{30};
    std::atomic<bool> audio_low_latency{false};
    std::atomic<float> audio_gain{1.0f};

    // Frontend frame skip: 0 = present every rendered frame.
    std::atomic<int> frame_skip{0};

    // Display crop in source pixels, trimmed before aspect-ratio scaling.
    std::atomic<int> crop_left{0};
    std::atomic<int> crop_top{0};
    std::atomic<int> crop_right{0};
    std::atomic<int> crop_bottom{0};

    // Input: active-high bitmask per port plus analog axes.
    std::atomic<uint16_t> pad_buttons[2]{{0xFFFF}, {0xFFFF}};
    std::atomic<int16_t> pad_analog[2][4]{};  // lx, ly, rx, ry in -32768..32767
    std::atomic<bool> pad_analog_mode[2]{{false}, {false}};

    std::atomic<bool> core_initialized{false};
    std::atomic<bool> game_loaded{false};

    // Rumble latched from the core, read back by the UI.
    std::atomic<uint8_t> rumble_strong[2]{{0}, {0}};
    std::atomic<uint8_t> rumble_weak[2]{{0}, {0}};
};

FrontendState g_frontend;

// ---------------------------------------------------------------------------
// OpenGL ES hardware renderer state. Created and used exclusively on the frame
// worker thread, because EGL contexts are thread-affine.
// ---------------------------------------------------------------------------
struct GlRenderState {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    retro_hw_render_callback* callback = nullptr;
    bool pending = false;
    bool ready = false;
    bool failed = false;
    uint32_t window_generation = 0;

    // Offscreen framebuffer the core renders into. The frontend then blits the
    // active region to the window, scaled and letterboxed.
    GLuint fbo = 0;
    GLuint fbo_texture = 0;
    GLuint fbo_depth = 0;
    GLsizei fbo_width = 0;
    GLsizei fbo_height = 0;
};

GlRenderState g_gl;

// Frontend post-processing effect selected from the RetroArch shader preset.
// 0 = off, 1 = CRT, 2 = LCD, 3 = sharp bilinear, 4 = nearest, 5 = bilinear.
std::atomic<int> g_shader_effect{0};

struct GlEffectState {
    GLuint program = 0;
    GLuint vao = 0;
    GLint u_dst_rect = -1;
    GLint u_src_rect = -1;
    GLint u_out_size = -1;
    GLint u_effect = -1;
    GLint u_texture = -1;
    bool failed = false;
};

GlEffectState g_gl_effect;

#if defined(EMUCORER_HAVE_LIBRASHADER)
struct GlShaderChainState {
    void* chain = nullptr;
    std::string preset;
    uint64_t generation = 0;
    bool failed = false;
    uint64_t frame_count = 0;
    GLuint input_texture = 0;
    GLuint input_fbo = 0;
    GLsizei input_width = 0;
    GLsizei input_height = 0;
    GLuint target_texture = 0;
    GLuint target_fbo = 0;
    GLsizei target_width = 0;
    GLsizei target_height = 0;
};

GlShaderChainState g_gl_chain;

const void* GlShaderChainLoader(const char* name) {
    return reinterpret_cast<const void*>(eglGetProcAddress(name));
}

void ReportGlShaderChainError(const char* what, libra_error_t err) {
    char* message = nullptr;
    if (libra_error_write(err, &message) == 0 && message != nullptr) {
        LOGE("librashader GL: %s failed: %s", what, message);
        libra_error_free_string(&message);
    } else {
        LOGE("librashader GL: %s failed (errno %d)", what, static_cast<int>(libra_error_errno(err)));
    }
    libra_error_free(&err);
}

void DestroyGlShaderChain() {
    if (g_gl_chain.chain != nullptr) {
        libra_gl_filter_chain_t chain = static_cast<libra_gl_filter_chain_t>(g_gl_chain.chain);
        libra_gl_filter_chain_free(&chain);
        g_gl_chain.chain = nullptr;
    }
    if (g_gl_chain.input_fbo != 0) glDeleteFramebuffers(1, &g_gl_chain.input_fbo);
    if (g_gl_chain.input_texture != 0) glDeleteTextures(1, &g_gl_chain.input_texture);
    if (g_gl_chain.target_fbo != 0) glDeleteFramebuffers(1, &g_gl_chain.target_fbo);
    if (g_gl_chain.target_texture != 0) glDeleteTextures(1, &g_gl_chain.target_texture);
    g_gl_chain = GlShaderChainState{};
}

bool EnsureGlChainTexture(GLuint* texture, GLuint* fbo, GLsizei* current_width, GLsizei* current_height,
                          GLsizei width, GLsizei height) {
    if (width <= 0 || height <= 0) return false;
    if (*texture != 0 && *current_width == width && *current_height == height) return true;
    if (*fbo != 0) {
        glDeleteFramebuffers(1, fbo);
        *fbo = 0;
    }
    if (*texture != 0) {
        glDeleteTextures(1, texture);
        *texture = 0;
    }

    glGenTextures(1, texture);
    glBindTexture(GL_TEXTURE_2D, *texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    glGenFramebuffers(1, fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *texture, 0);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("librashader GL: framebuffer incomplete (0x%x)", status);
        if (*fbo != 0) {
            glDeleteFramebuffers(1, fbo);
            *fbo = 0;
        }
        if (*texture != 0) {
            glDeleteTextures(1, texture);
            *texture = 0;
        }
        return false;
    }
    *current_width = width;
    *current_height = height;
    return true;
}

bool EnsureGlShaderChainInput(GLsizei width, GLsizei height) {
    return EnsureGlChainTexture(&g_gl_chain.input_texture, &g_gl_chain.input_fbo, &g_gl_chain.input_width,
                                &g_gl_chain.input_height, width, height);
}

bool EnsureGlShaderChainTarget(GLsizei width, GLsizei height) {
    return EnsureGlChainTexture(&g_gl_chain.target_texture, &g_gl_chain.target_fbo, &g_gl_chain.target_width,
                                &g_gl_chain.target_height, width, height);
}

void RestoreGlStateAfterShaderChain() {
    // librashader leaves clobbered GL state behind. The core does not use
    // sampler objects, so in particular any sampler it left bound on a texture
    // unit would make the core's own textures sample as black next frame.
    for (GLuint unit = 0; unit < 16; ++unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindSampler(unit, 0);
    }
    glActiveTexture(GL_TEXTURE0);
    glUseProgram(0);
    glBindVertexArray(0);
    EmuCoreR_ResetGLProgramCache();
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
}

bool EnsureGlShaderChain() {
    if (!emucorer::shader_chain::IsEnabled()) return false;
    const std::string path = emucorer::shader_chain::PresetPath();
    if (path.empty()) return false;

    const uint64_t generation = emucorer::shader_chain::Generation();
    if (g_gl_chain.chain != nullptr && g_gl_chain.preset == path && g_gl_chain.generation == generation) {
        return true;
    }
    if (g_gl_chain.failed && g_gl_chain.preset == path && g_gl_chain.generation == generation) {
        return false;
    }

    DestroyGlShaderChain();
    g_gl_chain.preset = path;
    g_gl_chain.generation = generation;

    libra_shader_preset_t preset = nullptr;
    if (libra_error_t err = libra_preset_create(path.c_str(), &preset)) {
        ReportGlShaderChainError("preset load", err);
        g_gl_chain.failed = true;
        return false;
    }

    filter_chain_gl_opt_t options = {};
    options.version = LIBRASHADER_CURRENT_VERSION;
    options.glsl_version = 0;
    options.use_dsa = false;
    options.force_no_mipmaps = false;
    options.disable_cache = false;

    libra_gl_filter_chain_t chain = nullptr;
    if (libra_error_t err = libra_gl_filter_chain_create(&preset, &GlShaderChainLoader, &options, &chain)) {
        ReportGlShaderChainError("chain create", err);
        g_gl_chain.failed = true;
        return false;
    }
    g_gl_chain.chain = chain;
    g_gl_chain.frame_count = 0;
    LOGI("librashader GL: loaded preset '%s'", path.c_str());
    return true;
}
#endif  // EMUCORER_HAVE_LIBRASHADER

constexpr const char* kEffectVertexSource = R"(#version 300 es
precision highp float;
uniform vec4 u_dst_rect;
uniform vec4 u_src_rect;
out vec2 v_uv;
void main() {
    vec2 uv = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)) * 2.0;
    vec2 pos = u_dst_rect.xy + uv * u_dst_rect.zw;
    gl_Position = vec4(pos.x * 2.0 - 1.0, 1.0 - pos.y * 2.0, 0.0, 1.0);
    // FBO textures use a bottom-left origin, so flip V while keeping the
    // destination rect anchored to the top-left.
    v_uv = vec2(u_src_rect.x + uv.x * u_src_rect.z,
                u_src_rect.y + (1.0 - uv.y) * u_src_rect.w);
}
)";

constexpr const char* kEffectFragmentSource = R"(#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 o_color;
uniform sampler2D u_tex;
uniform vec4 u_dst_rect;
uniform vec4 u_src_rect;
uniform vec2 u_out_size;
uniform int u_effect;

const float PI = 3.14159265358979323846;

vec3 sample_sharp(vec2 uv) {
    vec2 tex_size = vec2(textureSize(u_tex, 0));
    vec2 pixels = uv * tex_size;
    vec2 center = floor(pixels) + 0.5;
    vec2 out_px = max(u_dst_rect.zw * u_out_size, vec2(1.0));
    vec2 scale = max(out_px / max(tex_size, vec2(1.0)), vec2(1.0));
    vec2 offset = clamp((pixels - center) * scale, vec2(-0.5), vec2(0.5));
    return texture(u_tex, (center + offset) / tex_size).rgb;
}

vec3 apply_crt(vec2 uv, vec2 out_pix) {
    vec3 c = texture(u_tex, uv).rgb;
    float scan = 0.82 + 0.18 * cos(out_pix.y * PI);
    float phase = mod(out_pix.x, 3.0);
    vec3 mask = vec3(1.0);
    if (phase < 1.0)
        mask = vec3(1.22, 0.86, 0.86);
    else if (phase < 2.0)
        mask = vec3(0.86, 1.22, 0.86);
    else
        mask = vec3(0.86, 0.86, 1.22);
    return c * scan * mask;
}

vec3 apply_lcd(vec2 uv, vec2 out_pix) {
    vec3 c = texture(u_tex, uv).rgb;
    float phase = mod(out_pix.x, 3.0);
    vec3 mask = phase < 1.0 ? vec3(1.15, 0.4, 0.4) : (phase < 2.0 ? vec3(0.4, 1.15, 0.4) : vec3(0.4, 0.4, 1.15));
    float grid = mod(out_pix.y, 3.0) < 1.0 ? 1.0 : 0.62;
    return c * mask * grid;
}

void main() {
    vec3 color;
    if (u_effect == 3) {
        color = sample_sharp(v_uv);
    } else if (u_effect == 1) {
        color = apply_crt(v_uv, gl_FragCoord.xy);
    } else if (u_effect == 2) {
        color = apply_lcd(v_uv, gl_FragCoord.xy);
    } else if (u_effect == 4) {
        color = texture(u_tex, v_uv).rgb;
    } else {
        color = texture(u_tex, v_uv).rgb;
    }
    o_color = vec4(color, 1.0);
}
)";

bool CompileGlShader(GLenum type, const char* source, GLuint* out_shader) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) return false;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[512];
        GLsizei length = 0;
        glGetShaderInfoLog(shader, sizeof(log), &length, log);
        LOGE("Shader compile failed: %s", log);
        glDeleteShader(shader);
        return false;
    }
    *out_shader = shader;
    return true;
}

bool EnsureGlEffectProgram() {
    if (g_gl_effect.program != 0) return true;
    if (g_gl_effect.failed) return false;

    GLuint vertex_shader = 0;
    GLuint fragment_shader = 0;
    if (!CompileGlShader(GL_VERTEX_SHADER, kEffectVertexSource, &vertex_shader) ||
        !CompileGlShader(GL_FRAGMENT_SHADER, kEffectFragmentSource, &fragment_shader)) {
        if (vertex_shader != 0) glDeleteShader(vertex_shader);
        g_gl_effect.failed = true;
        return false;
    }

    const GLuint program = glCreateProgram();
    glAttachShader(program, vertex_shader);
    glAttachShader(program, fragment_shader);
    glLinkProgram(program);
    glDeleteShader(vertex_shader);
    glDeleteShader(fragment_shader);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512];
        GLsizei length = 0;
        glGetProgramInfoLog(program, sizeof(log), &length, log);
        LOGE("Shader link failed: %s", log);
        glDeleteProgram(program);
        g_gl_effect.failed = true;
        return false;
    }

    g_gl_effect.program = program;
    g_gl_effect.u_dst_rect = glGetUniformLocation(program, "u_dst_rect");
    g_gl_effect.u_src_rect = glGetUniformLocation(program, "u_src_rect");
    g_gl_effect.u_out_size = glGetUniformLocation(program, "u_out_size");
    g_gl_effect.u_effect = glGetUniformLocation(program, "u_effect");
    g_gl_effect.u_texture = glGetUniformLocation(program, "u_tex");
    glGenVertexArrays(1, &g_gl_effect.vao);
    LOGI("GL shader effect program ready");
    return true;
}

void DestroyGlEffectProgram() {
    if (g_gl_effect.vao != 0) glDeleteVertexArrays(1, &g_gl_effect.vao);
    if (g_gl_effect.program != 0) glDeleteProgram(g_gl_effect.program);
    g_gl_effect = GlEffectState{};
}


// True when the user selected the frontend "Stretch" (fill) mode. The
// SwanStation core does not understand this value and falls back to Auto, so
// the presenter honours it directly by disabling aspect correction.
bool AspectRatioStretchRequested() {
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    const auto it = g_frontend.options.find(option_key::kAspectRatio);
    return it != g_frontend.options.end() && it->second == "Stretch";
}

struct PresentRect {
    int x;
    int y;
    int width;
    int height;
};

// Aspect-corrected fit of a display of [display_aspect] inside [win_width] x
// [win_height], letterboxed or pillarboxed and centred.
PresentRect FitDisplayRect(int win_width, int win_height, double display_aspect) {
    if (display_aspect <= 0.0 || !std::isfinite(display_aspect)) display_aspect = 4.0 / 3.0;
    const double window_aspect = static_cast<double>(win_width) / static_cast<double>(win_height);
    int width;
    int height;
    if (window_aspect > display_aspect) {
        height = win_height;
        width = static_cast<int>(win_height * display_aspect + 0.5);
    } else {
        width = win_width;
        height = static_cast<int>(win_width / display_aspect + 0.5);
    }
    if (width < 1) width = 1;
    if (height < 1) height = 1;
    if (width > win_width) width = win_width;
    if (height > win_height) height = win_height;
    PresentRect rect{};
    rect.x = (win_width - width) / 2;
    rect.y = (win_height - height) / 2;
    rect.width = width;
    rect.height = height;
    return rect;
}

// User-requested display crop, in source pixels.
struct DisplayCropRect {
    int left;
    int top;
    int right;
    int bottom;
};

DisplayCropRect CurrentDisplayCrop() {
    return {g_frontend.crop_left.load(std::memory_order_relaxed),
            g_frontend.crop_top.load(std::memory_order_relaxed),
            g_frontend.crop_right.load(std::memory_order_relaxed),
            g_frontend.crop_bottom.load(std::memory_order_relaxed)};
}

// Drops pixel rows/columns from the source frame while keeping at least one
// pixel in each direction, so the scaler never receives an empty frame.
DisplayCropRect ClampDisplayCrop(DisplayCropRect crop, unsigned width, unsigned height) {
    if (crop.left < 0) crop.left = 0;
    if (crop.top < 0) crop.top = 0;
    if (crop.right < 0) crop.right = 0;
    if (crop.bottom < 0) crop.bottom = 0;
    if (width > 0) {
        if (crop.left >= static_cast<int>(width)) crop.left = static_cast<int>(width) - 1;
        if (crop.right > static_cast<int>(width) - 1 - crop.left) {
            crop.right = static_cast<int>(width) - 1 - crop.left;
        }
    }
    if (height > 0) {
        if (crop.top >= static_cast<int>(height)) crop.top = static_cast<int>(height) - 1;
        if (crop.bottom > static_cast<int>(height) - 1 - crop.top) {
            crop.bottom = static_cast<int>(height) - 1 - crop.top;
        }
    }
    return crop;
}

bool IsCropActive(const DisplayCropRect& crop) {
    return crop.left > 0 || crop.top > 0 || crop.right > 0 || crop.bottom > 0;
}

void PresentHardwareFrameEffect(int effect, int win_width, int win_height, const PresentRect& dst,
                                int src_x, int src_y, int src_width, int src_height) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    // The oversize fullscreen triangle is only clipped to the destination rect
    // by the scissor, so enable it around the draw. Without this the extra
    // rasterized pixels sample the source rect's edge (CLAMP_TO_EDGE) and paint
    // the letterbox bars with the frame's edge colours instead of leaving them
    // black. The viewport is anchored to the rect in GL's bottom-left space so
    // the vertex shader can work with a unit destination rectangle.
    const int dst_gl_y = win_height - dst.y - dst.height;
    glEnable(GL_SCISSOR_TEST);
    glScissor(dst.x, dst_gl_y, dst.width, dst.height);
    glViewport(dst.x, dst_gl_y, dst.width, dst.height);

    glUseProgram(g_gl_effect.program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_gl.fbo_texture);
    const GLint filter = (effect == 4) ? GL_NEAREST : GL_LINEAR;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glUniform1i(g_gl_effect.u_texture, 0);
    glUniform4f(g_gl_effect.u_dst_rect, 0.0f, 0.0f, 1.0f, 1.0f);
    glUniform4f(g_gl_effect.u_src_rect,
                g_gl.fbo_width > 0 ? static_cast<float>(src_x) / static_cast<float>(g_gl.fbo_width) : 0.0f,
                g_gl.fbo_height > 0 ? static_cast<float>(src_y) / static_cast<float>(g_gl.fbo_height) : 0.0f,
                g_gl.fbo_width > 0 ? static_cast<float>(src_width) / static_cast<float>(g_gl.fbo_width) : 1.0f,
                g_gl.fbo_height > 0 ? static_cast<float>(src_height) / static_cast<float>(g_gl.fbo_height) : 1.0f);
    glUniform2f(g_gl_effect.u_out_size, static_cast<float>(dst.width), static_cast<float>(dst.height));
    glUniform1i(g_gl_effect.u_effect, effect);
    glBindVertexArray(g_gl_effect.vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glUseProgram(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glDisable(GL_SCISSOR_TEST);
}

bool CreatePresentFramebuffer() {
    retro_system_av_info info{};
    retro_get_system_av_info(&info);
    const GLsizei width = static_cast<GLsizei>(info.geometry.max_width > 0 ? info.geometry.max_width : 1024);
    const GLsizei height = static_cast<GLsizei>(info.geometry.max_height > 0 ? info.geometry.max_height : 512);
    if (g_gl.fbo != 0 && g_gl.fbo_width == width && g_gl.fbo_height == height) return true;

    if (g_gl.fbo != 0) { glDeleteFramebuffers(1, &g_gl.fbo); g_gl.fbo = 0; }
    if (g_gl.fbo_texture != 0) { glDeleteTextures(1, &g_gl.fbo_texture); g_gl.fbo_texture = 0; }
    if (g_gl.fbo_depth != 0) { glDeleteRenderbuffers(1, &g_gl.fbo_depth); g_gl.fbo_depth = 0; }

    glGenTextures(1, &g_gl.fbo_texture);
    glBindTexture(GL_TEXTURE_2D, g_gl.fbo_texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenRenderbuffers(1, &g_gl.fbo_depth);
    glBindRenderbuffer(GL_RENDERBUFFER, g_gl.fbo_depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);

    glGenFramebuffers(1, &g_gl.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_gl.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_gl.fbo_texture, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, g_gl.fbo_depth);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("EGL: present framebuffer incomplete (0x%x)", status);
        return false;
    }
    g_gl.fbo_width = width;
    g_gl.fbo_height = height;
    retro_system_av_info avi{};
    retro_get_system_av_info(&avi);
    LOGI("Present framebuffer %dx%d (internal render %ux%u)", width, height,
         avi.geometry.base_width, avi.geometry.base_height);
    return true;
}

// Present only the dimensions supplied by this video callback. Preserve the
// shared context state so the core's GL state cache remains valid.
void PresentHardwareFrame(unsigned width, unsigned height) {
    if (!g_gl.fbo || !width || !height) return;
    ANativeWindow* window = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        window = g_frontend.window;
        if (window) ANativeWindow_acquire(window);
    }
    if (!window) return;
    const int ww = ANativeWindow_getWidth(window);
    const int wh = ANativeWindow_getHeight(window);
    ANativeWindow_release(window);
    if (ww <= 0 || wh <= 0) return;
    const int sw = std::min(static_cast<int>(width), g_gl.fbo_width);
    const int sh = std::min(static_cast<int>(height), g_gl.fbo_height);
    if (sw <= 0 || sh <= 0) return;
    retro_system_av_info info{};
    retro_get_system_av_info(&info);
    const DisplayCropRect crop = ClampDisplayCrop(CurrentDisplayCrop(), sw, sh);
    double aspect = info.geometry.aspect_ratio;
    if (aspect <= 0.0) aspect = static_cast<double>(sw) / sh;
    aspect *= (static_cast<double>(sw - crop.left - crop.right) / sw) /
              (static_cast<double>(sh - crop.top - crop.bottom) / sh);
    const PresentRect dst = AspectRatioStretchRequested()
        ? PresentRect{0, 0, ww, wh} : FitDisplayRect(ww, wh, aspect);
    GLint read_fbo = 0, draw_fbo = 0;
    GLfloat clear_color[4];
    GLboolean color_mask[4];
    const GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read_fbo);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw_fbo);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, clear_color);
    glGetBooleanv(GL_COLOR_WRITEMASK, color_mask);
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, g_gl.fbo);
    const bool bottom_left = g_gl.callback && g_gl.callback->bottom_left_origin;
    const int y0 = bottom_left ? crop.bottom : sh - crop.bottom;
    const int y1 = bottom_left ? sh - crop.top : crop.top;
    const int dy = wh - dst.y - dst.height;
    glBlitFramebuffer(crop.left, y0, sw - crop.right, y1,
                      dst.x, dy, dst.x + dst.width, dy + dst.height,
                      GL_COLOR_BUFFER_BIT, GL_LINEAR);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(read_fbo));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(draw_fbo));
    glClearColor(clear_color[0], clear_color[1], clear_color[2], clear_color[3]);
    glColorMask(color_mask[0], color_mask[1], color_mask[2], color_mask[3]);
    if (scissor) glEnable(GL_SCISSOR_TEST);
}

bool CreateEglContext(ANativeWindow* window) {
    if (g_gl.display == EGL_NO_DISPLAY) {
        g_gl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (g_gl.display == EGL_NO_DISPLAY || eglInitialize(g_gl.display, nullptr, nullptr) != EGL_TRUE) {
            LOGE("EGL: eglInitialize failed (0x%x)", eglGetError());
            return false;
        }
        const EGLint config_attribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 24, EGL_NONE,
        };
        EGLint config_count = 0;
        if (eglChooseConfig(g_gl.display, config_attribs, &g_gl.config, 1, &config_count) != EGL_TRUE ||
            config_count < 1) {
            LOGE("EGL: eglChooseConfig failed (0x%x)", eglGetError());
            return false;
        }
        const EGLint context_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        g_gl.context = eglCreateContext(g_gl.display, g_gl.config, EGL_NO_CONTEXT, context_attribs);
        if (g_gl.context == EGL_NO_CONTEXT) {
            LOGE("EGL: eglCreateContext failed (0x%x)", eglGetError());
            return false;
        }
    }
    if (g_gl.surface != EGL_NO_SURFACE) {
        eglMakeCurrent(g_gl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(g_gl.display, g_gl.surface);
        g_gl.surface = EGL_NO_SURFACE;
    }
    g_gl.surface = eglCreateWindowSurface(g_gl.display, g_gl.config, window, nullptr);
    if (g_gl.surface == EGL_NO_SURFACE) {
        LOGE("EGL: eglCreateWindowSurface failed (0x%x)", eglGetError());
        return false;
    }
    if (eglMakeCurrent(g_gl.display, g_gl.surface, g_gl.surface, g_gl.context) != EGL_TRUE) {
        LOGE("EGL: eglMakeCurrent failed (0x%x)", eglGetError());
        return false;
    }
    return true;
}

// Runs on the frame worker thread. Creates the EGL surface for the current
// window and tells the core its context is ready (which also rebuilds GL
// resources whenever the surface is recreated).
bool EnsureHardwareContext() {
    if (vulkan::IsRequested()) {
        ANativeWindow* window = nullptr;
        uint32_t generation = 0;
        {
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            window = g_frontend.window;
            generation = g_frontend.window_generation;
        }
        if (window == nullptr) return false;
        ANativeWindow_acquire(window);
        const bool ready = vulkan::EnsureContext(window, generation);
        ANativeWindow_release(window);
        return ready;
    }

    if (g_gl.callback == nullptr || g_gl.failed || g_gl.pending == false) return g_gl.ready;

    ANativeWindow* window = nullptr;
    uint32_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        window = g_frontend.window;
        generation = g_frontend.window_generation;
    }
    if (window == nullptr) return false;
    if (g_gl.ready && generation == g_gl.window_generation) return true;

    if (!CreateEglContext(window)) {
        g_gl.failed = true;
        return false;
    }
    // The core renders into an offscreen framebuffer; allocate it while the
    // context is current and before the core builds its resources.
    CreatePresentFramebuffer();
    g_gl.window_generation = generation;
    g_gl.ready = true;
    g_gl.pending = false;
    if (g_gl.callback->context_reset != nullptr) g_gl.callback->context_reset();
    return true;
}

void DestroyHardwareContext() {    if (g_gl.display != EGL_NO_DISPLAY) {
        if (g_gl.surface != EGL_NO_SURFACE && g_gl.context != EGL_NO_CONTEXT)
            eglMakeCurrent(g_gl.display, g_gl.surface, g_gl.surface, g_gl.context);
        if (g_gl.fbo != 0) glDeleteFramebuffers(1, &g_gl.fbo);
        if (g_gl.fbo_texture != 0) glDeleteTextures(1, &g_gl.fbo_texture);
        if (g_gl.fbo_depth != 0) glDeleteRenderbuffers(1, &g_gl.fbo_depth);
        DestroyGlEffectProgram();
#if defined(EMUCORER_HAVE_LIBRASHADER)
        DestroyGlShaderChain();
#endif
        eglMakeCurrent(g_gl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_gl.surface != EGL_NO_SURFACE) eglDestroySurface(g_gl.display, g_gl.surface);
        if (g_gl.context != EGL_NO_CONTEXT) eglDestroyContext(g_gl.display, g_gl.context);
        eglTerminate(g_gl.display);
    }
    g_gl = GlRenderState{};
}

// Releases the core's hardware renderer through its context_destroy callback.
// Without this the core keeps the hardware-renderer flags across sessions and
// the next boot initialises a GPU before any context exists, which crashes.
void DestroyHardwareRendererContext() {
    if (vulkan::IsRequested()) {
        vulkan::NotifyContextDestroy();
        return;
    }
    if (g_gl.callback == nullptr || g_gl.callback->context_destroy == nullptr) return;

    EGLSurface teardown_surface = EGL_NO_SURFACE;
    bool made_current = false;
    if (g_gl.display != EGL_NO_DISPLAY && g_gl.context != EGL_NO_CONTEXT) {
        if (g_gl.surface != EGL_NO_SURFACE &&
            eglMakeCurrent(g_gl.display, g_gl.surface, g_gl.surface, g_gl.context) == EGL_TRUE) {
            made_current = true;
        } else {
            const EGLint attribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            teardown_surface = eglCreatePbufferSurface(g_gl.display, g_gl.config, attribs);
            if (teardown_surface != EGL_NO_SURFACE &&
                eglMakeCurrent(g_gl.display, teardown_surface, teardown_surface, g_gl.context) == EGL_TRUE) {
                made_current = true;
            }
        }
    }
    g_gl.callback->context_destroy();
    if (made_current) eglMakeCurrent(g_gl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (teardown_surface != EGL_NO_SURFACE) eglDestroySurface(g_gl.display, teardown_surface);
}

// ---------------------------------------------------------------------------
// Logging bridge.
// ---------------------------------------------------------------------------
void RetroLogCallback(enum retro_log_level level, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case RETRO_LOG_DEBUG: priority = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO: priority = ANDROID_LOG_INFO; break;
        case RETRO_LOG_WARN: priority = ANDROID_LOG_WARN; break;
        case RETRO_LOG_ERROR: priority = ANDROID_LOG_ERROR; break;
        default: priority = ANDROID_LOG_DEBUG; break;
    }
    __android_log_vprint(priority, LOG_TAG "-Core", fmt, args);
    va_end(args);
}

// ---------------------------------------------------------------------------
// Environment callback.
// ---------------------------------------------------------------------------
// The core changes geometry.max_width/max_height when the internal resolution
// scale changes and announces it through SET_SYSTEM_AV_INFO. The core queries
// get_current_framebuffer() every frame, so resizing our backing FBO here makes
// the new resolution take effect at runtime without a full context reset.
void ResizePresentFramebufferIfCurrent() {
    if (g_gl.display == EGL_NO_DISPLAY || g_gl.context == EGL_NO_CONTEXT) return;
    if (eglGetCurrentContext() != g_gl.context) return;
    CreatePresentFramebuffer();
}

bool HandleHardwareRender(void* data) {
    auto* cb = static_cast<retro_hw_render_callback*>(data);
    if (cb == nullptr) return false;

    if (cb->context_type == RETRO_HW_CONTEXT_VULKAN) {
        if (!vulkan::AcceptHardwareRender(cb)) {
            LOGI("Rejecting Vulkan hardware context (software fallback)");
            return false;
        }
        return true;
    }

    // OpenGL ES is negotiated through EGL. Other context types are rejected so
    // the core keeps its software renderer.
    if (cb->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
        cb->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) {
        LOGI("Rejecting hardware context type %u (software fallback)", cb->context_type);
        return false;
    }

    cb->get_proc_address = [](const char* name) -> void (*)() {
        return reinterpret_cast<void (*)()>(eglGetProcAddress(name));
    };
    cb->get_current_framebuffer = []() -> uintptr_t {
        return static_cast<uintptr_t>(g_gl.fbo);
    };
    cb->depth = true;
    cb->stencil = false;

    // The frame worker has not started yet at this point (SET_HW_RENDER fires
    // inside retro_load_game), so publishing here is safe.
    static retro_hw_render_callback owned_callback{};
    owned_callback = *cb;
    g_gl.callback = &owned_callback;
    g_gl.pending = true;
    g_gl.failed = false;
    LOGI("Accepted OpenGL ES hardware renderer request");
    return true;
}

// GooseDroid boot probe: definitions and defaults come from the loaded core.
struct GooseOption { std::string fallback; std::vector<std::string> values; };
std::unordered_map<std::string, GooseOption> goose_options;
const std::unordered_map<std::string, std::string> goose_legacy_keys = {
    {"goosestation_renderer", "swanstation_GPU_Renderer"},
    {"goosestation_resolution_scale", "swanstation_GPU_ResolutionScale"},
    {"goosestation_aspect_ratio", "swanstation_Display_AspectRatio"},
    {"goosestation_bios_path_ntsc_j", "swanstation_BIOS_PathNTSCJ"},
    {"goosestation_bios_path_ntsc_u", "swanstation_BIOS_PathNTSCU"},
    {"goosestation_bios_path_pal", "swanstation_BIOS_PathPAL"},
    {"goosestation_bios_fast_boot", "swanstation_BIOS_PatchFastBoot"},
    {"goosestation_widescreen_hack", "swanstation_GPU_WidescreenHack"},
    {"goosestation_texture_filter", "swanstation_GPU_TextureFilter"},
    {"goosestation_gpu_software_readbacks", "swanstation_GPU_UseSoftwareRendererForReadbacks"},
    {"goosestation_crop_mode", "swanstation_Display_CropMode"},
    {"goosestation_region", "swanstation_Console_Region"},
    {"goosestation_cpu_execution_mode", "swanstation_CPU_ExecutionMode"},
    {"goosestation_cpu_fastmem_mode", "swanstation_CPU_FastmemMode"},
    {"goosestation_cpu_recompiler_icache", "swanstation_CPU_RecompilerICache"},
    {"goosestation_cdrom_region_check", "swanstation_CDROM_RegionCheck"},
    {"goosestation_cdrom_readahead_sectors", "swanstation_CDROM_ReadaheadSectors"},
    {"goosestation_cdrom_mute_cd_audio", "swanstation_CDROM_MuteCDAudio"},
    {"goosestation_pgxp_enable", "swanstation_GPU_PGXPEnable"},
    {"goosestation_pgxp_vertex_cache", "swanstation_GPU_PGXPVertexCache"},
    {"goosestation_pgxp_preserve_proj_fp", "swanstation_GPU_PGXPPreserveProjFP"},
    {"goosestation_pgxp_tolerance", "swanstation_GPU_PGXPTolerance"},
    {"goosestation_pgxp_disable_2d", "swanstation_GPU_PGXPDisable2D"},
    {"goosestation_pgxp_transparent_depth", "swanstation_GPU_PGXPTransparentDepth"},
    {"goosestation_pgxp_depth_clear_threshold", "swanstation_GPU_PGXPDepthClearThreshold"},
    {"goosestation_pgxp_culling", "swanstation_GPU_PGXPCulling"},
    {"goosestation_pgxp_texture_correction", "swanstation_GPU_PGXPTextureCorrection"},
    {"goosestation_pgxp_color_correction", "swanstation_GPU_PGXPColorCorrection"},
    {"goosestation_pgxp_depth_buffer", "swanstation_GPU_PGXPDepthBuffer"},
    {"goosestation_pgxp_cpu", "swanstation_GPU_PGXPCPU"},
    {"goosestation_apply_compatibility_settings", "swanstation_Main_ApplyGameSettings"},
    {"goosestation_multitap", "swanstation_ControllerPorts_MultitapMode"},
};
template<class Definition> void RegisterGooseOptions(const Definition* defs) {
    if (!defs) return;
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    for (auto* d = defs; d->key; ++d) {
        GooseOption opt;
        for (const auto& v : d->values) {
            if (!v.value) break;
            opt.values.emplace_back(v.value);
        }
        opt.fallback = d->default_value ? d->default_value :
            (opt.values.empty() ? "" : opt.values.front());
        goose_options[d->key] = std::move(opt);
    }
}
// Caller holds g_frontend.mutex. Returned strings are stored, never temporaries.
const char* GooseOptionValue(const char* key) {
    auto def = goose_options.find(key);
    auto legacy = goose_legacy_keys.find(key);
    auto val = g_frontend.options.find(legacy == goose_legacy_keys.end() ? key : legacy->second);
    if (def == goose_options.end()) {
        return val == g_frontend.options.end() ? nullptr : val->second.c_str();
    }
    if (val != g_frontend.options.end()) {
        std::string candidate = val->second;
        for (const auto& allowed : def->second.values) {
            if (allowed == candidate ||
                (candidate == "true" && (allowed == "enabled" || allowed == "Enabled")) ||
                (candidate == "false" && (allowed == "disabled" || allowed == "Disabled")))
                return allowed.c_str();
        }
        // BIOS options may accept a filename not present in their enumerated list.
        if (std::string(key).find("goosestation_bios_path_") == 0)
            return val->second.c_str();
    }
    return def->second.fallback.c_str();
}

bool EnvironmentCallback(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            if (data == nullptr) return false;
            const auto format = *static_cast<const enum retro_pixel_format*>(data);
            if (format == RETRO_PIXEL_FORMAT_0RGB1555 || format == RETRO_PIXEL_FORMAT_RGB565 ||
                format == RETRO_PIXEL_FORMAT_XRGB8888) {
                std::lock_guard<std::mutex> lock(g_frontend.mutex);
                g_frontend.pixel_format = static_cast<int>(format);
                return true;
            }
            return false;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* var = static_cast<retro_variable*>(data);
            if (var == nullptr || var->key == nullptr) return false;
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            var->value = GooseOptionValue(var->key);
            static std::unordered_map<std::string, std::string> diagnostic_values;
            const std::string effective = var->value ? var->value : "<unset>";
            const auto previous = diagnostic_values.find(var->key);
            if (previous == diagnostic_values.end() || previous->second != effective) {
                LOGI("GOOSE-OPTION %s=%s", var->key, effective.c_str());
                diagnostic_values[var->key] = effective;
            }
            return var->value != nullptr;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
            RegisterGooseOptions(static_cast<const retro_core_option_definition*>(data));
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
            auto* opts = static_cast<const retro_core_options_intl*>(data);
            if (!opts) return false;
            RegisterGooseOptions(opts->us);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
            auto* opts = static_cast<const retro_core_options_v2*>(data);
            if (!opts) return false;
            RegisterGooseOptions(opts->definitions);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            auto* opts = static_cast<const retro_core_options_v2_intl*>(data);
            if (!opts || !opts->us) return false;
            RegisterGooseOptions(opts->us->definitions);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return false;

        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            return true;

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: {
            if (data == nullptr) return false;
            *static_cast<unsigned*>(data) = 2;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            if (data == nullptr) return false;
            // Report "changed" exactly once after a runtime option write so the
            // core's UpdateSettings() re-reads the options, then clear it.
            *static_cast<bool*>(data) = g_frontend.options_dirty.exchange(false);
            return true;
        }

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            if (data == nullptr) return false;
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            *static_cast<const char**>(data) = g_frontend.system_dir.c_str();
            return true;
        }

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            if (data == nullptr) return false;
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            *static_cast<const char**>(data) = g_frontend.save_dir.c_str();
            return true;
        }

        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: {
            if (data == nullptr) return false;
            std::lock_guard<std::mutex> lock(g_frontend.mutex);
            *static_cast<const char**>(data) = g_frontend.core_assets_dir.c_str();
            return true;
        }

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            if (data == nullptr) return false;
            static_cast<retro_log_callback*>(data)->log = RetroLogCallback;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION: {
            if (data == nullptr) return false;
            *static_cast<unsigned*>(data) = 1;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;

        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: {
            if (data == nullptr) return false;
            // bit0 = video, bit1 = audio.
            *static_cast<int*>(data) = 3;
            return true;
        }

        case RETRO_ENVIRONMENT_SET_HW_RENDER:
            return HandleHardwareRender(data);

        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
            if (data == nullptr) return false;
            return vulkan::AcceptNegotiationInterface(
                static_cast<const retro_hw_render_context_negotiation_interface*>(data));
        }

        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE: {
            if (data == nullptr) return false;
            return vulkan::FillHardwareRenderInterface(reinterpret_cast<retro_hw_render_interface**>(data));
        }

        case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
            if (data == nullptr) return false;
            auto* negotiation = static_cast<retro_hw_render_context_negotiation_interface*>(data);
            if (negotiation->interface_type == RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) {
                negotiation->interface_version = RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN_VERSION;
            } else {
                negotiation->interface_version = 0;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
            if (data == nullptr) return false;
            int renderer;
            {
                std::lock_guard<std::mutex> lock(g_frontend.mutex);
                renderer = g_frontend.requested_renderer;
            }
            *static_cast<unsigned*>(data) = renderer == core_renderer::kVulkan ? RETRO_HW_CONTEXT_VULKAN
                                                                               : RETRO_HW_CONTEXT_OPENGLES3;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: {
            if (data == nullptr) return false;
            auto* rumble = static_cast<retro_rumble_interface*>(data);
            rumble->set_rumble_state = [](unsigned port, enum retro_rumble_effect effect, uint16_t strength) {
                if (port > 1u) return false;
                if (effect == RETRO_RUMBLE_STRONG) {
                    g_frontend.rumble_strong[port].store(static_cast<uint8_t>(strength >> 8));
                } else {
                    g_frontend.rumble_weak[port].store(static_cast<uint8_t>(strength >> 8));
                }
                return true;
            };
            return true;
        }

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            g_frontend.av_info_refresh_pending.store(true);
            ResizePresentFramebufferIfCurrent();
            return true;

        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            if (!data) return false;
            const auto* msg = static_cast<const retro_message*>(data);
            QueueGooseNotice(msg->msg, static_cast<unsigned>(std::min<uint64_t>(15000, static_cast<uint64_t>(msg->frames) * 1000 / 60)));
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            if (!data) return false;
            const auto* msg = static_cast<const retro_message_ext*>(data);
            // LOG-only messages are not intended for the on-screen display.
            if (msg->target != RETRO_MESSAGE_TARGET_LOG)
                QueueGooseNotice(msg->msg, msg->duration);
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
        case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
        default:
            return false;
    }
}

// ---------------------------------------------------------------------------
// Video callback: convert + present the software framebuffer.
// ---------------------------------------------------------------------------
int32_t WindowWidth(ANativeWindow* window) {
    return window != nullptr ? ANativeWindow_getWidth(window) : 0;
}

int32_t WindowHeight(ANativeWindow* window) {
    return window != nullptr ? ANativeWindow_getHeight(window) : 0;
}

void PresentSoftwareFrame(const void* data, unsigned width, unsigned height, size_t pitch) {
    ANativeWindow* window = nullptr;
    int pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        window = g_frontend.window;
        pixel_format = g_frontend.pixel_format;
    }
    if (window == nullptr || data == nullptr || width == 0 || height == 0) return;

    // Crop trims source pixels before scaling: move the read pointer to the
    // first kept pixel and shrink the sampling region, leaving pitch intact.
    const DisplayCropRect crop = ClampDisplayCrop(CurrentDisplayCrop(), width, height);
    const bool crop_active = IsCropActive(crop);
    const unsigned full_width = width;
    const unsigned full_height = height;
    const auto* src_bytes = static_cast<const uint8_t*>(data);
    if (crop_active) {
        const size_t bytes_per_pixel = pixel_format == RETRO_PIXEL_FORMAT_XRGB8888 ? 4u : 2u;
        src_bytes += static_cast<size_t>(crop.top) * pitch +
                     static_cast<size_t>(crop.left) * bytes_per_pixel;
        width -= static_cast<unsigned>(crop.left + crop.right);
        height -= static_cast<unsigned>(crop.top + crop.bottom);
    }

    const int32_t win_width = WindowWidth(window);
    const int32_t win_height = WindowHeight(window);
    if (win_width <= 0 || win_height <= 0) return;

    if (ANativeWindow_setBuffersGeometry(window, win_width, win_height, WINDOW_FORMAT_RGBA_8888) != 0) {
        return;
    }

    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window, &buffer, nullptr) != 0) return;

    auto* dst = static_cast<uint32_t*>(buffer.bits);
    const int dst_stride = buffer.stride;
    const int dst_width = buffer.width;
    const int dst_height = buffer.height;

    // Aspect-preserving fit with letterboxing/pillarboxing. The core reports
    // the intended display aspect ratio separately from the raw pixel size.
    retro_system_av_info av_info{};
    retro_get_system_av_info(&av_info);
    double display_aspect = av_info.geometry.aspect_ratio;
    if (crop_active && width > 0 && height > 0) {
        // The reported aspect covers the full frame, so rescale it for the
        // trimmed region instead of stretching the remaining pixels.
        display_aspect *= (static_cast<double>(width) / static_cast<double>(full_width)) /
                          (static_cast<double>(height) / static_cast<double>(full_height));
    }
    if (AspectRatioStretchRequested()) {
        display_aspect = static_cast<double>(dst_width) / static_cast<double>(dst_height);
    }
    const PresentRect fit = FitDisplayRect(dst_width, dst_height, display_aspect);
    const int fit_w = fit.width;
    const int fit_h = fit.height;
    const int offset_x = fit.x;
    const int offset_y = fit.y;

    // Clear to black.
    for (int y = 0; y < dst_height; ++y) {
        uint32_t* row = dst + static_cast<size_t>(y) * dst_stride;
        for (int x = 0; x < dst_width; ++x) row[x] = 0xFF000000u;
    }

    const int effect = g_shader_effect.load(std::memory_order_relaxed);
    auto scale_channel = [](float value) -> uint32_t {
        if (value <= 0.0f) return 0;
        if (value >= 255.0f) return 255;
        return static_cast<uint32_t>(value + 0.5f);
    };

    for (int y = 0; y < fit_h; ++y) {
        const unsigned src_y = static_cast<unsigned>((static_cast<int64_t>(y) * height) / fit_h);
        uint32_t* dst_row = dst + static_cast<size_t>(offset_y + y) * dst_stride + offset_x;
        const uint8_t* src_row = src_bytes + static_cast<size_t>(src_y) * pitch;
        const int out_y = offset_y + y;
        for (int x = 0; x < fit_w; ++x) {
            const unsigned src_x = static_cast<unsigned>((static_cast<int64_t>(x) * width) / fit_w);
            // WINDOW_FORMAT_RGBA_8888 stores bytes R,G,B,A, i.e. on a
            // little-endian host the packed word is A<<24 | B<<16 | G<<8 | R.
            uint32_t rgba = 0xFF000000u;
            if (pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
                const auto px = reinterpret_cast<const uint16_t*>(src_row)[src_x];
                const uint32_t r = ((px >> 11) & 0x1F) << 3;
                const uint32_t g = ((px >> 5) & 0x3F) << 2;
                const uint32_t b = (px & 0x1F) << 3;
                rgba |= (b << 16) | (g << 8) | r;
            } else if (pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
                const uint32_t px = reinterpret_cast<const uint32_t*>(src_row)[src_x];
                const uint32_t r = (px >> 16) & 0xFF;
                const uint32_t g = (px >> 8) & 0xFF;
                const uint32_t b = px & 0xFF;
                rgba |= (b << 16) | (g << 8) | r;
            } else {  // 0RGB1555
                const auto px = reinterpret_cast<const uint16_t*>(src_row)[src_x];
                const uint32_t r = ((px >> 10) & 0x1F) << 3;
                const uint32_t g = ((px >> 5) & 0x1F) << 3;
                const uint32_t b = (px & 0x1F) << 3;
                rgba |= (b << 16) | (g << 8) | r;
            }

            if (effect == 1 || effect == 2) {
                const int out_x = offset_x + x;
                float r = static_cast<float>(rgba & 0xFFu);
                float g = static_cast<float>((rgba >> 8) & 0xFFu);
                float b = static_cast<float>((rgba >> 16) & 0xFFu);
                if (effect == 1) {
                    const float scan = (out_y & 1) == 0 ? 1.0f : 0.80f;
                    r *= scan;
                    g *= scan;
                    b *= scan;
                    const int phase = out_x % 3;
                    if (phase == 0) {
                        r *= 1.15f; g *= 0.88f; b *= 0.88f;
                    } else if (phase == 1) {
                        r *= 0.88f; g *= 1.15f; b *= 0.88f;
                    } else {
                        r *= 0.88f; g *= 0.88f; b *= 1.15f;
                    }
                } else {
                    const float grid = (out_y % 3) == 0 ? 1.0f : 0.62f;
                    const int phase = out_x % 3;
                    r *= grid * (phase == 0 ? 1.15f : 0.45f);
                    g *= grid * (phase == 1 ? 1.15f : 0.45f);
                    b *= grid * (phase == 2 ? 1.15f : 0.45f);
                }
                rgba = 0xFF000000u | (scale_channel(b) << 16) | (scale_channel(g) << 8) | scale_channel(r);
            }

            dst_row[x] = rgba;
        }
    }

    ANativeWindow_unlockAndPost(window);
}

static std::atomic<int> g_fast_forward_multiplier{1};

void RetroVideoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    // Optional frontend frame skip: after presenting one frame, drop the next
    // N refresh callbacks so the host does less presentation work per second.
    const int frame_skip = std::max(g_frontend.frame_skip.load(std::memory_order_relaxed),
                                    g_fast_forward_multiplier.load(std::memory_order_relaxed) - 1);
    if (frame_skip > 0)
    {
        static thread_local int skip_counter = 0;
        if (skip_counter > 0)
        {
            skip_counter--;
            return;
        }
        skip_counter = frame_skip;
    }

    if (data == nullptr) {
        if (g_gl.ready && g_gl.display != EGL_NO_DISPLAY && g_gl.surface != EGL_NO_SURFACE)
            eglSwapBuffers(g_gl.display, g_gl.surface);
        return;
    }
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        if (vulkan::IsActive()) {
            bool window_attached = false;
            {
                std::lock_guard<std::mutex> lock(g_frontend.mutex);
                window_attached = g_frontend.window != nullptr;
            }
            if (!window_attached) return;
            // Query the core only after new geometry was announced or an
            // option changed; the aspect stays valid in between.
            if (g_frontend.av_info_refresh_pending.exchange(false)) {
                retro_system_av_info info{};
                retro_get_system_av_info(&info);
                g_frontend.aspect_ratio = info.geometry.aspect_ratio;
            }
            vulkan::Present(width, height, g_frontend.aspect_ratio, AspectRatioStretchRequested());
            return;
        }
        // Hardware path: the core rendered into the frontend framebuffer; blit
        // it to the window, then present.
        PresentHardwareFrame(width, height);
        if (g_gl.ready && g_gl.display != EGL_NO_DISPLAY && g_gl.surface != EGL_NO_SURFACE)
            eglSwapBuffers(g_gl.display, g_gl.surface);
        return;
    }

    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        g_frontend.frame_width = width;
        g_frontend.frame_height = height;
    }
    PresentSoftwareFrame(data, width, height, pitch);
}

// ---------------------------------------------------------------------------
// Audio callback: append into the ring buffer consumed by the output stream.
// ---------------------------------------------------------------------------
void AudioRingEnsureCapacity(size_t additional_frames) {
    const size_t used = (g_frontend.audio_write_frame + kAudioRingCapacityFrames -
                         g_frontend.audio_read_frame) % kAudioRingCapacityFrames;
    if (used + additional_frames <= kAudioRingCapacityFrames) return;
    // Drop the oldest frames to make room rather than stalling the core. Mark
    // the discontinuity so the output ramps instead of clicking.
    const size_t drop = std::min(used + additional_frames - kAudioRingCapacityFrames, used);
    g_frontend.audio_read_frame = (g_frontend.audio_read_frame + drop) % kAudioRingCapacityFrames;
    g_frontend.audio_declick_frames.store(kAudioDeclickFrames, std::memory_order_relaxed);
}

size_t RetroAudioSampleBatch(const int16_t* data, size_t frames) {
    if (g_fast_forward_multiplier.load(std::memory_order_relaxed) > 1) return frames;
    if (data == nullptr || frames == 0) return frames;
    std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
    AudioRingEnsureCapacity(frames);
    for (size_t i = 0; i < frames; ++i) {
        const size_t slot = (g_frontend.audio_write_frame + i) % kAudioRingCapacityFrames;
        g_frontend.audio_ring[slot * 2 + 0] = data[i * 2 + 0];
        g_frontend.audio_ring[slot * 2 + 1] = data[i * 2 + 1];
    }
    g_frontend.audio_write_frame = (g_frontend.audio_write_frame + frames) % kAudioRingCapacityFrames;
    return frames;
}

void RetroAudioSample(int16_t left, int16_t right) {
    const int16_t frame[2] = {left, right};
    RetroAudioSampleBatch(frame, 1);
}

// ---------------------------------------------------------------------------
// Input callbacks.
// ---------------------------------------------------------------------------
void RetroInputPoll() {
    // Kotlin pushes state asynchronously; nothing to sample here.
}

int16_t RetroInputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port > 1u) return 0;
    switch (device) {
        case RETRO_DEVICE_JOYPAD: {
            if (index != 0) return 0;
            // Map libretro joypad ids onto the active-low PS1 bitmask the
            // Kotlin layer produces (0 = pressed).
            static const int8_t ps1_bit_for_id[16] = {
                14,  // B      -> Cross
                15,  // Y      -> Square
                0,   // SELECT -> Select
                3,   // START  -> Start
                4,   // UP     -> Up
                6,   // DOWN   -> Down
                7,   // LEFT   -> Left
                5,   // RIGHT  -> Right
                13,  // A      -> Circle
                12,  // X      -> Triangle
                10,  // L      -> L1
                11,  // R      -> R1
                8,   // L2     -> L2
                9,   // R2     -> R2
                1,   // L3     -> L3
                2,   // R3     -> R3
            };
            const uint16_t buttons = g_frontend.pad_buttons[port].load();
            uint16_t active = 0;
            for (unsigned b = 0; b < 16; ++b) {
                if ((buttons & (1u << ps1_bit_for_id[b])) == 0) active |= static_cast<uint16_t>(1u << b);
            }
            if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(active);
            if (id >= 16u) return 0;
            return (active & (1u << id)) != 0 ? 1 : 0;
        }
        case RETRO_DEVICE_ANALOG: {
            if (id == RETRO_DEVICE_ID_ANALOG_X) {
                const int axis = (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) ? 2 : 0;
                return g_frontend.pad_analog[port][axis].load();
            }
            if (id == RETRO_DEVICE_ID_ANALOG_Y) {
                const int axis = (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) ? 3 : 1;
                return g_frontend.pad_analog[port][axis].load();
            }
            return 0;
        }
        case RETRO_DEVICE_LIGHTGUN:
            return 0;
        default:
            return 0;
    }
}

// ---------------------------------------------------------------------------
// AAudio output.
// ---------------------------------------------------------------------------
struct AudioOutput {
    AAudioStream* stream = nullptr;
    std::atomic<bool> started{false};
    std::atomic<int32_t> sample_rate{44100};
    std::atomic<int64_t> last_error{0};
    std::atomic<uint64_t> queued_frames{0};
    std::atomic<uint64_t> accepted_frames{0};
    std::atomic<uint64_t> callback_frames{0};
    std::atomic<uint64_t> silence_frames{0};
    std::atomic<int32_t> state{0};
    std::atomic<int32_t> device_buffer_frames{0};
    std::atomic<int32_t> pacing_high_water_frames{0};
    std::mutex mutex;
};

aaudio_data_callback_result_t AudioDataCallback(AAudioStream*, void* user_data, void* audio_data,
                                                int32_t num_frames) {
    auto* output = static_cast<AudioOutput*>(user_data);
    auto* out = static_cast<int16_t*>(audio_data);
    if (output == nullptr || out == nullptr || num_frames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;

    std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
    const size_t available = (g_frontend.audio_write_frame + kAudioRingCapacityFrames -
                              g_frontend.audio_read_frame) % kAudioRingCapacityFrames;
    const size_t to_read = std::min(static_cast<size_t>(num_frames), available);
    const float gain = g_frontend.audio_gain.load(std::memory_order_relaxed);
    int32_t declick = g_frontend.audio_declick_frames.exchange(0, std::memory_order_relaxed);

    for (size_t i = 0; i < to_read; ++i) {
        const size_t slot = (g_frontend.audio_read_frame + i) % kAudioRingCapacityFrames;
        float frame_gain = gain;
        if (declick > 0) {
            // Ramp back in after a discontinuity so the first real sample
            // after silence cannot click.
            frame_gain *= 1.0f - static_cast<float>(declick) / static_cast<float>(kAudioDeclickFrames);
            --declick;
        }
        out[i * 2 + 0] = static_cast<int16_t>(g_frontend.audio_ring[slot * 2 + 0] * frame_gain);
        out[i * 2 + 1] = static_cast<int16_t>(g_frontend.audio_ring[slot * 2 + 1] * frame_gain);
    }
    g_frontend.audio_read_frame = (g_frontend.audio_read_frame + to_read) % kAudioRingCapacityFrames;

    if (to_read < static_cast<size_t>(num_frames)) {
        // Ramp the real tail down before padding with silence; a hard cut
        // from a non-zero sample is audible as a click.
        const size_t fade = std::min<size_t>(to_read, static_cast<size_t>(kAudioDeclickFrames));
        for (size_t i = 0; i < fade; ++i) {
            const size_t index = to_read - fade + i;
            const float ramp = static_cast<float>(fade - i) / static_cast<float>(fade);
            out[index * 2 + 0] = static_cast<int16_t>(out[index * 2 + 0] * ramp);
            out[index * 2 + 1] = static_cast<int16_t>(out[index * 2 + 1] * ramp);
        }

        std::memset(out + to_read * 2, 0,
                    (static_cast<size_t>(num_frames) - to_read) * 2 * sizeof(int16_t));
        output->silence_frames.fetch_add(static_cast<uint64_t>(num_frames) - to_read);
        g_frontend.audio_declick_frames.store(kAudioDeclickFrames, std::memory_order_relaxed);
    } else {
        g_frontend.audio_declick_frames.store(declick, std::memory_order_relaxed);
    }
    output->callback_frames.fetch_add(static_cast<uint64_t>(num_frames));
    output->queued_frames.store(static_cast<uint64_t>(available - to_read));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioErrorCallback(AAudioStream*, void* user_data, aaudio_result_t error) {
    auto* output = static_cast<AudioOutput*>(user_data);
    if (output == nullptr) return;
    output->last_error.store(error);
    output->started.store(false);
    output->state.store(0);
}

std::string RendererOptionForInteger(int renderer) {
    switch (renderer) {
        case core_renderer::kVulkan:
            return "Vulkan";
        case core_renderer::kOpenGl:
            return "OpenGL";
        case core_renderer::kSoftware:
        default:
            return "Software";
    }
}

}  // namespace

extern "C" {
JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_pollCoreNotice(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(goose_notice_mutex);
    const auto now = std::chrono::steady_clock::now();
    while (!goose_notices.empty()) {
        auto notice = std::move(goose_notices.front());
        goose_notices.pop_front();
        const auto age = std::chrono::duration_cast<std::chrono::milliseconds>(now - notice.received).count();
        if (age >= notice.duration) continue;
        const std::string result = std::to_string(notice.duration - age) + "\n" + notice.text;
        return env->NewStringUTF(result.c_str());
    }
    return nullptr;
}


// Implemented by achievements_bridge.cpp: rcheevos client hooks.
extern void EmuCoreRAchievementsInitializeJava(JNIEnv* env);
extern void EmuCoreRAchievementsSetJavaVm(JavaVM* vm);
extern void EmuCoreRAchievementsOnFrame();
extern void EmuCoreRAchievementsOnSessionEnd();

// ---------------------------------------------------------------------------
// JNI: lifecycle.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    LOGI("GooseDroid boot probe loaded (api=%u)", retro_api_version());
    EmuCoreRAchievementsSetJavaVm(vm);
    JNIEnv* env = nullptr;
    if (vm != nullptr && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        EmuCoreRAchievementsInitializeJava(env);
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_nativeInit(JNIEnv* env, jobject, jstring systemDir,
                                                         jstring saveDir, jstring coreAssetsDir) {
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    auto to_string = [&](jstring value) -> std::string {
        if (value == nullptr) return {};
        const char* chars = env->GetStringUTFChars(value, nullptr);
        std::string result = chars != nullptr ? chars : "";
        if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
        return result;
    };
    g_frontend.system_dir = to_string(systemDir);
    g_frontend.save_dir = to_string(saveDir);
    g_frontend.core_assets_dir = to_string(coreAssetsDir);
    if (g_frontend.audio_ring.empty()) {
        g_frontend.audio_ring.assign(kAudioRingCapacityFrames * 2, 0);
    }
    LOGI("nativeInit system=%s save=%s assets=%s", g_frontend.system_dir.c_str(),
         g_frontend.save_dir.c_str(), g_frontend.core_assets_dir.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_createSession(JNIEnv*, jobject) {
    {
        std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
        if (!g_frontend.core_initialized) {
            {
                std::lock_guard<std::mutex> notices_lock(goose_notice_mutex);
                goose_notices.clear();
            }
            retro_set_environment(EnvironmentCallback);
            retro_set_video_refresh(RetroVideoRefresh);
            retro_set_audio_sample(RetroAudioSample);
            retro_set_audio_sample_batch(RetroAudioSampleBatch);
            retro_set_input_poll(RetroInputPoll);
            retro_set_input_state(RetroInputState);
            GooseDiagnosticSnapshot("before-init");
            retro_init();
            GooseDiagnosticSnapshot("after-init");
            g_frontend.core_initialized = true;
        }
    }
    // Default both controller ports to a DualShock. The core keeps it in
    // digital mode until a game enables analog/rumble, and games that probe
    // the pad type at boot (Tekken 3) need a DualShock from power-on.
    retro_set_controller_port_device(0, RETRO_DEVICE_PS_DUALSHOCK);
    retro_set_controller_port_device(1, RETRO_DEVICE_PS_DUALSHOCK);
    // Non-zero opaque handle. The core itself is a singleton.
    return reinterpret_cast<jlong>(&g_frontend);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_destroySession(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    EmuCoreRAchievementsOnSessionEnd();
    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    DestroyHardwareRendererContext();
    if (g_frontend.game_loaded) {
        retro_unload_game();
        g_frontend.game_loaded = false;
    }
    if (g_frontend.core_initialized) {
        retro_deinit();
        g_frontend.core_initialized = false;
    }
    DestroyHardwareContext();
    vulkan::Destroy();
}

// ---------------------------------------------------------------------------
// JNI: configuration.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_nativeSetShaderEffect(JNIEnv*, jobject, jint effect) {
    const int clamped = effect < 0 ? 0 : (effect > 5 ? 5 : static_cast<int>(effect));
    g_shader_effect.store(clamped, std::memory_order_relaxed);
    vulkan::SetShaderEffect(clamped);
    LOGI("Shader effect = %d", clamped);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_nativeSetShaderPreset(JNIEnv* env, jobject, jstring path,
                                                                    jboolean enabled) {
    std::string value;
    if (path != nullptr) {
        const char* chars = env->GetStringUTFChars(path, nullptr);
        if (chars != nullptr) {
            value = chars;
            env->ReleaseStringUTFChars(path, chars);
        }
    }
    emucorer::shader_chain::SetPreset(std::move(value), enabled == JNI_TRUE);
    LOGI("Shader preset enabled=%d path=%s", enabled == JNI_TRUE ? 1 : 0,
         emucorer::shader_chain::PresetPath().c_str());
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_nativeSetOption(JNIEnv* env, jobject, jstring key,
                                                              jstring value) {
    if (key == nullptr || value == nullptr) return;
    const char* key_chars = env->GetStringUTFChars(key, nullptr);
    const char* value_chars = env->GetStringUTFChars(value, nullptr);
    if (key_chars != nullptr && value_chars != nullptr) {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        g_frontend.options[key_chars] = value_chars;
        g_frontend.options_dirty.store(true);
        // Option changes can move the display crop or aspect, so the cached
        // AV info has to be taken again from the core on the next frame.
        g_frontend.av_info_refresh_pending.store(true);
    }
    if (value_chars != nullptr) env->ReleaseStringUTFChars(value, value_chars);
    if (key_chars != nullptr) env->ReleaseStringUTFChars(key, key_chars);
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_nativeGetOption(JNIEnv* env, jobject, jstring key) {
    if (key == nullptr) return nullptr;
    const char* key_chars = env->GetStringUTFChars(key, nullptr);
    std::string value;
    bool found = false;
    if (key_chars != nullptr) {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        const auto it = g_frontend.options.find(key_chars);
        if (it != g_frontend.options.end()) {
            value = it->second;
            found = true;
        }
    }
    if (key_chars != nullptr) env->ReleaseStringUTFChars(key, key_chars);
    return found ? env->NewStringUTF(value.c_str()) : nullptr;
}

// BIOS is handled by the core's directory scanner: the Kotlin layer places the
// selected image in the system directory before calling this.
JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_loadBios(JNIEnv* env, jobject, jlong handle,
                                                       jstring path) {
    if (handle == 0 || path == nullptr) return -1;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    const std::string bios_path = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    if (bios_path.empty()) return -2;
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    // Fallback for callers without regional settings. Preserve explicit choices
    // and Auto-Detect supplied by the GooseStation catalogue.
    const size_t slash = bios_path.find_last_of("/\\");
    const std::string name = slash == std::string::npos ? bios_path : bios_path.substr(slash + 1);
    g_frontend.options.emplace(option_key::kBiosNtscU, name);
    g_frontend.options.emplace(option_key::kBiosNtscJ, name);
    g_frontend.options.emplace(option_key::kBiosPal, name);
    LOGI("BIOS configured: %s", bios_path.c_str());
    return 0;
}

// Boots the core with no content. SwanStation treats an empty/none path as a
// BIOS-only boot (System::Boot handles an empty filename), which creates the
// GPU and display so retro_run() is safe. Without this the frame loop runs
// before retro_load_game and segfaults on the null GPU.
JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_loadBiosOnly(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1;
    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    if (g_frontend.game_loaded) {
        retro_unload_game();
        g_frontend.game_loaded = false;
    }
    retro_game_info info{};
    info.path = nullptr;
    info.data = nullptr;
    info.size = 0;
    info.meta = nullptr;
    GooseDiagnosticSnapshot("before-load");
    if (!retro_load_game(&info)) {
        LOGE("retro_load_game (BIOS only) failed");
        return -3;
    }
    GooseDiagnosticSnapshot("after-load");
    g_frontend.game_loaded = true;
    LOGI("BIOS-only boot ok");
    return 0;
}

// ---------------------------------------------------------------------------
// JNI: content.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_loadDisc(JNIEnv* env, jobject, jlong handle,
                                                       jstring path) {
    if (handle == 0 || path == nullptr) return -1;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    const std::string game_path = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    if (game_path.empty()) return -2;

    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    if (g_frontend.game_loaded) {
        retro_unload_game();
        g_frontend.game_loaded = false;
    }
    retro_game_info info{};
    info.path = game_path.c_str();
    info.data = nullptr;
    info.size = 0;
    info.meta = nullptr;
    GooseDiagnosticSnapshot("before-load");
    if (!retro_load_game(&info)) {
        LOGE("retro_load_game failed for %s", game_path.c_str());
        return -3;
    }
    GooseDiagnosticSnapshot("after-load");
    g_frontend.game_loaded = true;
    LOGI("retro_load_game ok: %s", game_path.c_str());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_loadDiscFd(JNIEnv*, jobject, jlong handle, jint fd,
                                                         jlong offset, jlong size) {
    if (handle == 0 || fd < 0) return -1;
    (void)offset;
    (void)size;
    // /proc/self/fd/<fd> stays valid as long as the caller keeps the descriptor
    // open, which the Kotlin layer guarantees for the duration of the session.
    const std::string game_path = "/proc/self/fd/" + std::to_string(fd);
    std::lock_guard<std::mutex> lock(g_frontend.core_mutex);
    if (g_frontend.game_loaded) {
        retro_unload_game();
        g_frontend.game_loaded = false;
    }
    retro_game_info info{};
    info.path = game_path.c_str();
    GooseDiagnosticSnapshot("before-load");
    if (!retro_load_game(&info)) {
        LOGE("retro_load_game failed for fd %d (%s)", fd, game_path.c_str());
        return -3;
    }
    GooseDiagnosticSnapshot("after-load");
    g_frontend.game_loaded = true;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_reset(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1;
    retro_reset();
    return 0;
}

// ---------------------------------------------------------------------------
// JNI: per-frame execution.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_runFrame(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    EnsureHardwareContext();
    static std::atomic<unsigned> diagnostic_frames{0};
    const unsigned diagnostic_frame = diagnostic_frames.fetch_add(1);
    if (diagnostic_frame < 3) GooseDiagnosticSnapshot("before-run");
    retro_run();
    if (diagnostic_frame < 3) GooseDiagnosticSnapshot("after-run");
    EmuCoreRAchievementsOnFrame();
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setSurface(JNIEnv* env, jobject, jlong handle,
                                                         jobject surface, jint renderer) {
    if (handle == 0) return -1;
    ANativeWindow* probe_window = nullptr;
    uint32_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        if (g_frontend.window != nullptr) {
            ANativeWindow_release(g_frontend.window);
            g_frontend.window = nullptr;
        }
        if (surface != nullptr) {
            g_frontend.window = ANativeWindow_fromSurface(env, surface);
        }
        g_frontend.window_generation++;
        g_frontend.requested_renderer = renderer;
        g_frontend.options[option_key::kRenderer] = RendererOptionForInteger(renderer);
        probe_window = g_frontend.window;
        generation = g_frontend.window_generation;
        if (probe_window != nullptr) ANativeWindow_acquire(probe_window);
    }
    LOGI("setSurface renderer=%d window=%s", renderer,
         probe_window != nullptr ? "attached" : "detached");

    int result = 0;
    if (probe_window != nullptr && renderer == core_renderer::kVulkan &&
        !vulkan::Prepare(probe_window, generation)) {
        LOGE("Vulkan is unavailable for the requested surface");
        result = -2;
    }
    if (probe_window != nullptr) ANativeWindow_release(probe_window);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_ensureHardwareContext(JNIEnv*, jobject) {
    // Called from the frame worker before draining state operations so the GL
    // context (thread-affine) exists before serialization touches the GPU.
    return EnsureHardwareContext() ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// JNI: input.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setPadButtons(JNIEnv*, jobject, jlong handle,
                                                            jint port, jint buttons) {
    if (handle == 0 || port < 0 || port > 1) return;
    g_frontend.pad_buttons[port].store(static_cast<uint16_t>(buttons & 0xFFFF));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setPadAnalog(JNIEnv*, jobject, jlong handle, jint port,
                                                           jint lx, jint ly, jint rx, jint ry) {
    if (handle == 0 || port < 0 || port > 1) return;
    auto to_axis = [](jint value) -> int16_t {
        const int centered = static_cast<int>(value) - 128;
        int scaled = centered * 256;
        if (scaled > 32767) scaled = 32767;
        if (scaled < -32768) scaled = -32768;
        return static_cast<int16_t>(scaled);
    };
    g_frontend.pad_analog[port][0].store(to_axis(lx));
    g_frontend.pad_analog[port][1].store(to_axis(ly));
    g_frontend.pad_analog[port][2].store(to_axis(rx));
    g_frontend.pad_analog[port][3].store(to_axis(ry));
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setPadAnalogMode(JNIEnv*, jobject, jlong handle,
                                                               jint port, jboolean enabled) {
    if (handle == 0 || port < 0 || port > 1) return;
    g_frontend.pad_analog_mode[port].store(enabled == JNI_TRUE);
    retro_set_controller_port_device(static_cast<unsigned>(port),
                                     enabled == JNI_TRUE ? RETRO_DEVICE_PS_DUALSHOCK : RETRO_DEVICE_JOYPAD);
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_getPadState(JNIEnv*, jobject, jlong handle, jint port) {
    if (handle == 0 || port < 0 || port > 1) return -1;
    const int analog = g_frontend.pad_analog_mode[port].load() ? 1 : 0;
    const int large = g_frontend.rumble_strong[port].load();
    const int small = g_frontend.rumble_weak[port].load();
    return (analog << 16) | (large << 8) | small;
}

// ---------------------------------------------------------------------------
// JNI: save states (libretro serialization).
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_saveState(JNIEnv* env, jobject, jlong handle,
                                                        jstring path) {
    if (handle == 0 || path == nullptr) return -1;
    const size_t size = retro_serialize_size();
    if (size == 0) return -2;
    std::vector<uint8_t> buffer(size);
    if (!retro_serialize(buffer.data(), buffer.size())) return -3;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    int result = -4;
    if (chars != nullptr) {
        FILE* file = fopen(chars, "wb");
        if (file != nullptr) {
            const size_t written = fwrite(buffer.data(), 1, buffer.size(), file);
            fclose(file);
            result = written == buffer.size() ? 0 : -5;
        }
        env->ReleaseStringUTFChars(path, chars);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_loadState(JNIEnv* env, jobject, jlong handle,
                                                        jstring path) {
    if (handle == 0 || path == nullptr) return -1;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    int result = -2;
    if (chars != nullptr) {
        FILE* file = fopen(chars, "rb");
        if (file != nullptr) {
            fseek(file, 0, SEEK_END);
            const long length = ftell(file);
            fseek(file, 0, SEEK_SET);
            if (length > 0) {
                std::vector<uint8_t> buffer(static_cast<size_t>(length));
                const size_t read = fread(buffer.data(), 1, buffer.size(), file);
                if (read == buffer.size() && retro_unserialize(buffer.data(), buffer.size())) {
                    result = 0;
                } else {
                    result = -3;
                }
            }
            fclose(file);
        }
        env->ReleaseStringUTFChars(path, chars);
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_createMemoryCard(JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return -1;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    int result = -2;
    if (chars != nullptr) {
        FILE* file = fopen(chars, "rb");
        if (file != nullptr) {
            fclose(file);
            result = 0;
        } else {
            file = fopen(chars, "wb");
            if (file != nullptr) {
                const std::vector<uint8_t> formatted = BuildFormattedMemoryCard();
                const size_t written = fwrite(formatted.data(), 1, formatted.size(), file);
                fclose(file);
                result = written == formatted.size() ? 0 : -3;
            }
        }
        env->ReleaseStringUTFChars(path, chars);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setMemoryCardPath(JNIEnv* env, jobject, jint slot,
                                                                jstring path) {
    if (slot < 0 || slot > 1) return;
    std::string value;
    if (path != nullptr) {
        const char* chars = env->GetStringUTFChars(path, nullptr);
        if (chars != nullptr) {
            value = chars;
            env->ReleaseStringUTFChars(path, chars);
        }
    }
    LOGW("GooseDroid probe: custom memory-card paths unsupported; core manages its cards");
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setTextureReplacementsPathOverride(JNIEnv* env,
                                                                                jobject,
                                                                                jstring path) {
    std::string value;
    if (path != nullptr) {
        const char* chars = env->GetStringUTFChars(path, nullptr);
        if (chars != nullptr) {
            value = chars;
            env->ReleaseStringUTFChars(path, chars);
        }
    }
    LOGW("GooseDroid probe: texture replacement path override unsupported");
}

JNIEXPORT jboolean JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_hasDiscMedia(JNIEnv*, jobject, jlong handle) {
    if (handle == 0 || !g_frontend.game_loaded) return JNI_FALSE;
    return g_frontend.game_loaded ? JNI_TRUE : JNI_FALSE;
}

// Disc metadata for the library layer: "title\nserial\nserial" (the app uses
// the serial; the title stays filename-derived because the core has no gamedb).
JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_getDiscMetadata(JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;
    const char* code = nullptr; // Metadata extraction is not exposed by this core.
    const std::string serial = code != nullptr ? code : "";
    env->ReleaseStringUTFChars(path, chars);
    if (serial.empty()) return nullptr;
    const std::string payload = serial + "\n" + serial + "\n" + serial;
    return env->NewStringUTF(payload.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_getDiscMetadataFd(JNIEnv* env, jobject, jint fd,
                                                               jlong /*offset*/, jlong /*size*/) {
    const char* code = nullptr; // UI falls back to filename metadata.
    if (code == nullptr) return nullptr;
    const std::string serial = code;
    if (serial.empty()) return nullptr;
    const std::string payload = serial + "\n" + serial + "\n" + serial;
    return env->NewStringUTF(payload.c_str());
}

// ---------------------------------------------------------------------------
// JNI: cheats. The Kotlin layer writes the active GameShark-style codes into a
// PCSX .cht container; each [*Section] becomes one libretro cheat whose
// instructions are applied every emulated frame by the core.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_loadCheats(JNIEnv* env, jobject, jstring path) {
    retro_cheat_reset();
    if (path == nullptr) return;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return;
    std::ifstream file(chars);
    env->ReleaseStringUTFChars(path, chars);
    if (!file.is_open()) return;

    std::string line;
    std::string code;
    unsigned index = 0;
    auto flush = [&]() {
        if (!code.empty()) {
            retro_cheat_set(index++, true, code.c_str());
            code.clear();
        }
    };
    while (std::getline(file, line)) {
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ' || line.back() == '\t'))
            line.pop_back();
        const size_t start = line.find_first_not_of(" \t");
        if (start == std::string::npos) continue;
        const std::string trimmed = line.substr(start);
        if (trimmed[0] == '[') {
            flush();
            continue;
        }
        if (trimmed[0] == '/' && trimmed.size() > 1 && trimmed[1] == '/') continue;
        if (code.empty()) {
            code = trimmed;
        } else {
            code += ' ';
            code += trimmed;
        }
    }
    flush();
    LOGI("Loaded %u cheats", index);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_clearCheats(JNIEnv*, jobject) {
    retro_cheat_reset();
}

// ---------------------------------------------------------------------------
// JNI: diagnostics.
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_getSystemInfo(JNIEnv* env, jobject) {
    retro_system_info info{};
    retro_get_system_info(&info);
    std::string name = info.library_name != nullptr ? info.library_name : "SwanStation";
    // AGP's debug native build defines _DEBUG, which the core surfaces as
    // "SwanStation Debug"/"SwanStation DebugFast". Present the stable name.
    const size_t debug_suffix = name.find(" Debug");
    if (debug_suffix != std::string::npos) name.erase(debug_suffix);
    std::string text = name;
    text += " ";
    text += info.library_version != nullptr ? info.library_version : "unknown";
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_getDiagnostics(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    char buffer[512];
    snprintf(buffer, sizeof(buffer),
             "{\"core\":\"SwanStation\",\"initialized\":%d,\"game\":%d,\"renderer\":%d,\"w\":%u,\"h\":%u}",
             g_frontend.core_initialized ? 1 : 0, g_frontend.game_loaded ? 1 : 0,
             g_frontend.requested_renderer, g_frontend.frame_width, g_frontend.frame_height);
    return env->NewStringUTF(buffer);
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_apiVersion(JNIEnv*, jobject) {
    return static_cast<jint>(RETRO_API_VERSION);
}

JNIEXPORT jintArray JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_getDisplayRect(JNIEnv* env, jobject, jlong handle) {
    if (handle == 0) return nullptr;
    retro_system_av_info info{};
    retro_get_system_av_info(&info);
    std::lock_guard<std::mutex> lock(g_frontend.mutex);
    const unsigned width = g_frontend.frame_width != 0 ? g_frontend.frame_width : info.geometry.base_width;
    const unsigned height = g_frontend.frame_height != 0 ? g_frontend.frame_height : info.geometry.base_height;
    jintArray result = env->NewIntArray(4);
    if (result == nullptr) return nullptr;
    const jint values[4] = {0, 0, static_cast<jint>(width), static_cast<jint>(height)};
    env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

// Destination rectangle the current presenter letterboxes the emulated frame
// into, in window pixels: {left, top, right, bottom}. Mirrors the GL, Vulkan
// and software present paths (aspect ratio, display crop, stretch) so app-side
// overlays such as the side artwork frame the emulated image exactly instead
// of guessing from the raw frame pixel size.
JNIEXPORT jfloatArray JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_getPresentRect(JNIEnv* env, jobject) {
    ANativeWindow* window = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_frontend.mutex);
        window = g_frontend.window;
    }
    if (window == nullptr) return nullptr;
    const int win_width = ANativeWindow_getWidth(window);
    const int win_height = ANativeWindow_getHeight(window);
    if (win_width <= 0 || win_height <= 0) return nullptr;

    retro_system_av_info info{};
    retro_get_system_av_info(&info);
    const unsigned base_width = info.geometry.base_width != 0 ? info.geometry.base_width : 1u;
    const unsigned base_height = info.geometry.base_height != 0 ? info.geometry.base_height : 1u;

    double display_aspect = info.geometry.aspect_ratio;
    if (display_aspect <= 0.0 || !std::isfinite(display_aspect)) display_aspect = 4.0 / 3.0;

#if defined(EMUCORER_HAVE_LIBRASHADER)
    const bool shader_chain_active =
        emucorer::shader_chain::IsEnabled() && !emucorer::shader_chain::PresetPath().empty();
#else
    constexpr bool shader_chain_active = false;
#endif
    if (!shader_chain_active) {
        const DisplayCropRect crop = ClampDisplayCrop(CurrentDisplayCrop(), base_width, base_height);
        if (IsCropActive(crop)) {
            const unsigned cropped_width = base_width - crop.left - crop.right;
            const unsigned cropped_height = base_height - crop.top - crop.bottom;
            if (cropped_width > 0 && cropped_height > 0) {
                display_aspect *= (static_cast<double>(cropped_width) / static_cast<double>(base_width)) /
                                  (static_cast<double>(cropped_height) / static_cast<double>(base_height));
            }
        }
    }

    const PresentRect dst = AspectRatioStretchRequested()
        ? PresentRect{0, 0, win_width, win_height}
        : FitDisplayRect(win_width, win_height, display_aspect);

    jfloatArray result = env->NewFloatArray(4);
    if (result == nullptr) return nullptr;
    const jfloat values[4] = {static_cast<jfloat>(dst.x), static_cast<jfloat>(dst.y),
                              static_cast<jfloat>(dst.x + dst.width),
                              static_cast<jfloat>(dst.y + dst.height)};
    env->SetFloatArrayRegion(result, 0, 4, values);
    return result;
}

JNIEXPORT jdouble JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_getFrameRate(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return 0.0;
    retro_system_av_info info{};
    retro_get_system_av_info(&info);
    return info.timing.fps;
}

JNIEXPORT jlongArray JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_getAvInfo(JNIEnv* env, jobject, jlong handle) {
    if (handle == 0) return nullptr;
    retro_system_av_info info{};
    retro_get_system_av_info(&info);
    const jlong values[6] = {
        static_cast<jlong>(info.geometry.base_width),
        static_cast<jlong>(info.geometry.base_height),
        static_cast<jlong>(info.geometry.max_width),
        static_cast<jlong>(info.geometry.max_height),
        static_cast<jlong>(info.timing.fps),
        static_cast<jlong>(info.timing.sample_rate),
    };
    jlongArray result = env->NewLongArray(6);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}

// ---------------------------------------------------------------------------
// JNI: AAudio output (NativeAudioPcmSink contract).
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setAudioOutputLatencyMs(JNIEnv*, jobject,
                                                                     jint milliseconds) {
    int clamped = milliseconds;
    if (clamped < 1) clamped = 1;
    if (clamped > 500) clamped = 500;
    g_frontend.audio_output_latency_ms.store(clamped);
    LOGI("Audio output latency = %d ms", clamped);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setAudioLowLatency(JNIEnv*, jobject, jboolean enabled) {
    const bool value = enabled == JNI_TRUE;
    g_frontend.audio_low_latency.store(value);
    LOGI("Audio low latency = %d", value ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setFrameSkip(JNIEnv*, jobject, jint frames) {
    int clamped = frames;
    if (clamped < 0) clamped = 0;
    if (clamped > 4) clamped = 4;
    g_frontend.frame_skip.store(clamped, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setDisplayCrop(JNIEnv*, jobject, jint left, jint top,
                                                            jint right, jint bottom) {
    const auto clamp = [](jint value) {
        if (value < 0) return 0;
        if (value > 64) return 64;
        return static_cast<int>(value);
    };
    const int l = clamp(left);
    const int t = clamp(top);
    const int r = clamp(right);
    const int b = clamp(bottom);
    g_frontend.crop_left.store(l, std::memory_order_relaxed);
    g_frontend.crop_top.store(t, std::memory_order_relaxed);
    g_frontend.crop_right.store(r, std::memory_order_relaxed);
    g_frontend.crop_bottom.store(b, std::memory_order_relaxed);
    vulkan::SetDisplayCrop(l, t, r, b);
}

JNIEXPORT jlong JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_createAudioOutput(JNIEnv*, jobject) {
    auto* output = new AudioOutput();
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        delete output;
        return 0;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 44100);
    const int latency_ms = g_frontend.audio_output_latency_ms.load();
    const int32_t capacity_frames =
        static_cast<int32_t>((static_cast<int64_t>(latency_ms) * 44100) / 1000);
    if (capacity_frames > 0) {
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, capacity_frames);
    }
    const bool want_low_latency = g_frontend.audio_low_latency.load();
    AAudioStreamBuilder_setPerformanceMode(
        builder, want_low_latency ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
                                  : AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setDataCallback(builder, AudioDataCallback, output);
    AAudioStreamBuilder_setErrorCallback(builder, AudioErrorCallback, output);
    aaudio_result_t opened = AAudioStreamBuilder_openStream(builder, &output->stream);
    if (opened != AAUDIO_OK && want_low_latency) {
        // Some devices refuse the low latency request outright. Retry in the
        // shared path instead of leaving the user without audio output.
        LOGI("AAudio low latency open failed (%d); retrying in shared mode", opened);
        output->stream = nullptr;
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
        opened = AAudioStreamBuilder_openStream(builder, &output->stream);
    }
    AAudioStreamBuilder_delete(builder);
    if (opened != AAUDIO_OK || output->stream == nullptr) {
        LOGE("AAudio open failed: %d", opened);
        delete output;
        return 0;
    }
    output->sample_rate.store(AAudioStream_getSampleRate(output->stream));
    // Size the device buffer from the burst, but never let it collapse to the
    // platform minimum: emulation frames are not isochronous, and a couple of
    // bursts is not enough to absorb shader work or a garbage collection
    // pause. The floor keeps crackling away even in low latency mode.
    const int32_t burst_frames = AAudioStream_getFramesPerBurst(output->stream);
    int32_t device_buffer = std::clamp(burst_frames * 2, 512, 1024);
    const int32_t stream_capacity = AAudioStream_getBufferCapacityInFrames(output->stream);
    if (stream_capacity > 0) device_buffer = std::min(device_buffer, stream_capacity);
    else if (capacity_frames > 0) device_buffer = std::min(device_buffer, capacity_frames);
    const aaudio_result_t resized = AAudioStream_setBufferSizeInFrames(output->stream, device_buffer);
    if (resized > 0) device_buffer = resized;
    output->device_buffer_frames.store(device_buffer);
    // The frame loop paces against this level. Keeping it several device
    // buffers deep leaves room for a frame-time spike such as first-use shader
    // compilation to be absorbed by the queue instead of cutting the audio.
    output->pacing_high_water_frames.store(
        std::min(device_buffer * 4, std::min(6144, static_cast<int32_t>(kAudioRingCapacityFrames) - 2048)));
    LOGI("AAudio output mode = %d, rate = %d Hz, device buffer = %d frames, pacing high water = %d frames",
         static_cast<int>(AAudioStream_getPerformanceMode(output->stream)), output->sample_rate.load(), device_buffer,
         output->pacing_high_water_frames.load());
    return reinterpret_cast<jlong>(output);
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_destroyAudioOutput(JNIEnv*, jobject, jlong handle) {
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    if (output == nullptr) return;
    std::lock_guard<std::mutex> lock(output->mutex);
    if (output->stream != nullptr) {
        AAudioStream_requestStop(output->stream);
        AAudioStream_close(output->stream);
        output->stream = nullptr;
    }
    delete output;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_startAudioOutput(JNIEnv*, jobject, jlong handle) {
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    if (output == nullptr || output->stream == nullptr) return -1;
    const aaudio_result_t result = AAudioStream_requestStart(output->stream);
    output->started.store(result == AAUDIO_OK);
    output->state.store(result == AAUDIO_OK ? 1 : 0);
    return result == AAUDIO_OK ? 0 : -2;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_pauseAudioOutput(JNIEnv*, jobject, jlong handle) {
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    if (output == nullptr || output->stream == nullptr) return -1;
    const aaudio_result_t result = AAudioStream_requestPause(output->stream);
    output->started.store(false);
    output->state.store(result == AAUDIO_OK ? 2 : 0);
    return result == AAUDIO_OK ? 0 : -2;
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_flushAudioOutput(JNIEnv*, jobject, jlong handle) {
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    if (output == nullptr || output->stream == nullptr) return -1;
    const aaudio_result_t result = AAudioStream_requestFlush(output->stream);
    {
        std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
        g_frontend.audio_read_frame = 0;
        g_frontend.audio_write_frame = 0;
    }
    output->queued_frames.store(0);
    return (result == AAUDIO_OK || result == AAUDIO_ERROR_INVALID_STATE) ? 0 : -2;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_resetAudioQueue(JNIEnv*, jobject) {
    // The ring outlives individual sessions, so a new game would otherwise
    // start by playing whatever the previous game left behind.
    std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
    g_frontend.audio_read_frame = 0;
    g_frontend.audio_write_frame = 0;
}

JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setAudioGain(JNIEnv*, jobject, jfloat gain) {
    float clamped = gain;
    if (!(clamped >= 0.0f)) clamped = 0.0f;
    if (clamped > 1.0f) clamped = 1.0f;
    g_frontend.audio_gain.store(clamped, std::memory_order_relaxed);
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_audioOutputBufferedFrames(JNIEnv*, jobject, jlong handle) {
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    if (output == nullptr || output->stream == nullptr) return -1;
    if (output->last_error.load() != 0) return -1;
    std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
    return static_cast<jint>((g_frontend.audio_write_frame + kAudioRingCapacityFrames -
                              g_frontend.audio_read_frame) % kAudioRingCapacityFrames);
}

JNIEXPORT jint JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_audioOutputPacingHighWaterFrames(JNIEnv*, jobject,
                                                                              jlong handle) {
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    return output != nullptr ? output->pacing_high_water_frames.load() : 0;
}

JNIEXPORT jlongArray JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_audioOutputStats(JNIEnv* env, jobject, jlong handle) {
    auto* output = reinterpret_cast<AudioOutput*>(handle);
    if (output == nullptr) return nullptr;
    const jlong values[8] = {
        static_cast<jlong>(output->state.load()),
        output->last_error.load(),
        static_cast<jlong>(output->sample_rate.load()),
        0,
        static_cast<jlong>(output->queued_frames.load()),
        static_cast<jlong>(output->accepted_frames.load()),
        static_cast<jlong>(output->callback_frames.load()),
        static_cast<jlong>(output->silence_frames.load()),
    };
    jlongArray result = env->NewLongArray(8);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 8, values);
    return result;
}

}  // extern "C"

extern "C" JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_setFastForwardMultiplier(JNIEnv*, jobject, jint multiplier) {
    const int next = std::clamp(static_cast<int>(multiplier), 1, 8);
    if (g_fast_forward_multiplier.exchange(next) != next) {
        std::lock_guard<std::mutex> lock(g_frontend.audio_mutex);
        g_frontend.audio_read_frame = 0;
        g_frontend.audio_write_frame = 0;
    }
}
