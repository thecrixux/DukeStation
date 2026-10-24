// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreR-Proprietary
//
// RetroAchievements bridge for the Android frontend.
//
// rcheevos' rc_client is driven from the emulation thread: one do_frame per
// emulated frame, with HTTP requests executed on a worker thread and their
// responses pumped back on the client thread. Kotlin polls the JSON state and
// event queue and owns the account token persistence.

#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

#include <libretro.h>

#include "rc_api_request.h"
#include "rc_client.h"
#include "rc_consoles.h"
#include "rc_error.h"
#include "rc_hash.h"
#include "rc_libretro.h"

#define RA_TAG "EmuCoreR-RA"
#define RALOGI(...) __android_log_print(ANDROID_LOG_INFO, RA_TAG, __VA_ARGS__)
#define RALOGW(...) __android_log_print(ANDROID_LOG_WARN, RA_TAG, __VA_ARGS__)
#define RALOGE(...) __android_log_print(ANDROID_LOG_ERROR, RA_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Java HTTP bridge
// ---------------------------------------------------------------------------
JavaVM* g_vm = nullptr;
jclass g_http_class = nullptr;
jclass g_http_response_class = nullptr;
jmethodID g_http_request_method = nullptr;
jfieldID g_http_status_field = nullptr;
jfieldID g_http_body_field = nullptr;

struct PendingHttpRequest
{
  std::string url;
  std::string post_data;
  std::string content_type;
  rc_client_server_callback_t callback = nullptr;
  void* callback_data = nullptr;
};

struct CompletedHttpResponse
{
  rc_client_server_callback_t callback = nullptr;
  void* callback_data = nullptr;
  std::string body;
  int status = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
};

// ---------------------------------------------------------------------------
// Client state
// ---------------------------------------------------------------------------
struct AchievementsState
{
  std::recursive_mutex mutex;
  rc_client_t* client = nullptr;
  rc_libretro_memory_regions_t memory_regions{};
  bool memory_initialized = false;
  bool enabled = false;
  bool hardcore = false;
  bool unofficial = false;
  bool encore = false;
  std::string last_error;
  std::deque<std::string> events;
  std::atomic<bool> has_game{false};

  // HTTP worker.
  std::thread http_thread;
  std::mutex http_mutex;
  std::condition_variable http_cv;
  std::deque<PendingHttpRequest> http_queue;
  std::deque<CompletedHttpResponse> http_completed;
  bool http_stopping = false;
  bool http_started = false;
};

AchievementsState g_state;

std::string JsonEscape(const char* value)
{
  if (value == nullptr)
    return {};

  std::string out;
  out.reserve(std::strlen(value));
  for (const char* p = value; *p != '\0'; ++p)
  {
    const unsigned char ch = static_cast<unsigned char>(*p);
    switch (ch)
    {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (ch < 0x20)
        {
          char buffer[8];
          std::snprintf(buffer, sizeof(buffer), "\\u%04x", ch);
          out += buffer;
        }
        else
        {
          out += static_cast<char>(ch);
        }
        break;
    }
  }
  return out;
}

void AppendJsonString(std::string* out, const char* value)
{
  *out += '"';
  *out += JsonEscape(value);
  *out += '"';
}

void PushEvent(const std::string& event)
{
  constexpr size_t MAX_EVENTS = 64;
  if (g_state.events.size() >= MAX_EVENTS)
    g_state.events.pop_front();
  g_state.events.push_back(event);
}

void PushSimpleEvent(const char* type)
{
  std::string event = "{\"type\":\"";
  event += type;
  event += "\"}";
  PushEvent(event);
}

// ---------------------------------------------------------------------------
// Memory access
// ---------------------------------------------------------------------------
void GetCoreMemoryInfo(uint32_t id, rc_libretro_core_memory_info_t* info)
{
  info->data = static_cast<uint8_t*>(retro_get_memory_data(id));
  info->size = (info->data != nullptr) ? retro_get_memory_size(id) : 0;
}

uint32_t ReadMemory(uint32_t address, uint8_t* buffer, uint32_t num_bytes, rc_client_t*)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  if (!g_state.memory_initialized)
    return 0;

  return rc_libretro_memory_read(&g_state.memory_regions, address, buffer, num_bytes);
}

// ---------------------------------------------------------------------------
// HTTP worker
// ---------------------------------------------------------------------------
void PerformJavaRequest(JNIEnv* env, const PendingHttpRequest& request, CompletedHttpResponse* response)
{
  if (env == nullptr || g_http_class == nullptr || g_http_response_class == nullptr ||
      g_http_request_method == nullptr)
    return;

  jstring url = env->NewStringUTF(request.url.c_str());
  jstring post_data = request.post_data.empty() ? nullptr : env->NewStringUTF(request.post_data.c_str());
  jstring content_type = request.content_type.empty() ? nullptr : env->NewStringUTF(request.content_type.c_str());

  jobject result = env->CallStaticObjectMethod(g_http_class, g_http_request_method, url, post_data,
                                               content_type);
  if (env->ExceptionCheck())
  {
    env->ExceptionDescribe();
    env->ExceptionClear();
    result = nullptr;
  }

  if (result != nullptr)
  {
    response->status = env->GetIntField(result, g_http_status_field);
    auto body = static_cast<jbyteArray>(env->GetObjectField(result, g_http_body_field));
    if (body != nullptr)
    {
      const jsize length = env->GetArrayLength(body);
      response->body.resize(static_cast<size_t>(length));
      if (length > 0)
        env->GetByteArrayRegion(body, 0, length, reinterpret_cast<jbyte*>(response->body.data()));
      env->DeleteLocalRef(body);
    }
    env->DeleteLocalRef(result);
  }

  if (url != nullptr)
    env->DeleteLocalRef(url);
  if (post_data != nullptr)
    env->DeleteLocalRef(post_data);
  if (content_type != nullptr)
    env->DeleteLocalRef(content_type);
}

void HttpWorkerMain()
{
  JNIEnv* env = nullptr;
  if (g_vm != nullptr && g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
    env = nullptr;

  for (;;)
  {
    PendingHttpRequest request;
    {
      std::unique_lock<std::mutex> lock(g_state.http_mutex);
      g_state.http_cv.wait(lock, [] { return g_state.http_stopping || !g_state.http_queue.empty(); });
      if (g_state.http_queue.empty())
      {
        if (g_state.http_stopping)
          break;
        continue;
      }
      request = std::move(g_state.http_queue.front());
      g_state.http_queue.pop_front();
    }

    CompletedHttpResponse response;
    response.callback = request.callback;
    response.callback_data = request.callback_data;
    PerformJavaRequest(env, request, &response);

    {
      std::lock_guard<std::mutex> lock(g_state.http_mutex);
      g_state.http_completed.push_back(std::move(response));
    }
  }

  if (env != nullptr && g_vm != nullptr)
    g_vm->DetachCurrentThread();
}

void StartHttpWorkerLocked()
{
  if (g_state.http_started)
    return;
  g_state.http_started = true;
  g_state.http_stopping = false;
  g_state.http_thread = std::thread(HttpWorkerMain);
}

void StopHttpWorkerLocked()
{
  if (!g_state.http_started)
    return;

  {
    std::lock_guard<std::mutex> lock(g_state.http_mutex);
    g_state.http_stopping = true;
    g_state.http_queue.clear();
  }
  g_state.http_cv.notify_all();
  if (g_state.http_thread.joinable())
    g_state.http_thread.join();
  g_state.http_started = false;
}

void ServerCall(const rc_api_request_t* request, rc_client_server_callback_t callback, void* callback_data,
                rc_client_t*)
{
  if (request == nullptr || request->url == nullptr || callback == nullptr)
    return;

  PendingHttpRequest pending;
  pending.url = request->url;
  if (request->post_data != nullptr)
    pending.post_data = request->post_data;
  if (request->content_type != nullptr)
    pending.content_type = request->content_type;
  pending.callback = callback;
  pending.callback_data = callback_data;

  StartHttpWorkerLocked();
  {
    std::lock_guard<std::mutex> lock(g_state.http_mutex);
    g_state.http_queue.push_back(std::move(pending));
  }
  g_state.http_cv.notify_one();
}

void PumpHttpResponsesLocked()
{
  std::deque<CompletedHttpResponse> completed;
  {
    std::lock_guard<std::mutex> lock(g_state.http_mutex);
    if (g_state.http_completed.empty())
      return;
    completed.swap(g_state.http_completed);
  }

  for (CompletedHttpResponse& response : completed)
  {
    rc_api_server_response_t server_response{};
    server_response.body = response.body.c_str();
    server_response.body_length = response.body.size();
    server_response.http_status_code = response.status;
    response.callback(&server_response, response.callback_data);
  }
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------
const char* AchievementBucketName(uint8_t bucket)
{
  switch (bucket)
  {
    case RC_CLIENT_ACHIEVEMENT_BUCKET_LOCKED: return "locked";
    case RC_CLIENT_ACHIEVEMENT_BUCKET_UNLOCKED: return "unlocked";
    case RC_CLIENT_ACHIEVEMENT_BUCKET_UNSUPPORTED: return "unsupported";
    case RC_CLIENT_ACHIEVEMENT_BUCKET_UNOFFICIAL: return "unofficial";
    case RC_CLIENT_ACHIEVEMENT_BUCKET_RECENTLY_UNLOCKED: return "recently_unlocked";
    case RC_CLIENT_ACHIEVEMENT_BUCKET_ACTIVE_CHALLENGE: return "active_challenge";
    case RC_CLIENT_ACHIEVEMENT_BUCKET_ALMOST_THERE: return "almost_there";
    case RC_CLIENT_ACHIEVEMENT_BUCKET_UNSYNCED: return "unsynced";
    default: return "unknown";
  }
}

// RetroAchievements injects a zero-point "Warning: Unknown Emulator" entry into
// the core list for clients it does not trust. It must never appear in the UI.
bool IsUnsupportedEmulatorWarning(const rc_client_achievement_t* achievement)
{
  if (achievement == nullptr)
    return false;
  if (achievement->id == 101000001)
    return true;
  if (achievement->title != nullptr && strcmp(achievement->title, "Warning: Unknown Emulator") == 0)
    return true;
  return achievement->description != nullptr &&
         strstr(achievement->description, "Hardcore unlocks cannot be earned") != nullptr;
}

void EventHandler(const rc_client_event_t* event, rc_client_t* client)
{
  if (event == nullptr)
    return;

  switch (event->type)
  {
    case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED:
    {
      const rc_client_achievement_t* achievement = event->achievement;
      if (achievement == nullptr || IsUnsupportedEmulatorWarning(achievement))
        break;

      char badge[256] = "";
      rc_client_achievement_get_image_url(achievement, RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED, badge, sizeof(badge));

      std::string json = "{\"type\":\"achievement_triggered\",\"id\":";
      json += std::to_string(achievement->id);
      json += ",\"title\":";
      AppendJsonString(&json, achievement->title);
      json += ",\"description\":";
      AppendJsonString(&json, achievement->description);
      json += ",\"points\":" + std::to_string(achievement->points);
      json += ",\"badgeUrl\":";
      AppendJsonString(&json, badge);
      json += "}";
      PushEvent(json);
      break;
    }

    case RC_CLIENT_EVENT_GAME_COMPLETED:
      PushSimpleEvent("game_completed");
      break;

    case RC_CLIENT_EVENT_SERVER_ERROR:
    {
      const rc_client_server_error_t* error = event->server_error;
      const std::string message = (error != nullptr && error->error_message != nullptr) ? error->error_message : "";
      g_state.last_error = message;
      std::string json = "{\"type\":\"server_error\",\"message\":";
      AppendJsonString(&json, message.c_str());
      json += "}";
      PushEvent(json);
      break;
    }

    case RC_CLIENT_EVENT_DISCONNECTED:
      PushSimpleEvent("disconnected");
      break;

    case RC_CLIENT_EVENT_RECONNECTED:
      PushSimpleEvent("reconnected");
      break;

    case RC_CLIENT_EVENT_RESET:
      // Enabling hardcore mode mid-game invalidates the current session.
      rc_client_reset(client);
      PushSimpleEvent("hardcore_reset");
      break;

    case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW:
    case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW:
    case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE:
    case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_HIDE:
    case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE:
    case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW:
    case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE:
    case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE:
      // The in-game overlay is not rendered by the native core; ignore.
      break;

    default:
      break;
  }
}

void LoginCallback(int result, const char* error_message, rc_client_t* client, void*)
{
  (void)client;
  if (result == RC_OK)
  {
    g_state.last_error.clear();
    const rc_client_user_t* user = rc_client_get_user_info(client);
    std::string json = "{\"type\":\"login_success\",\"username\":";
    AppendJsonString(&json, user != nullptr ? user->username : "");
    json += "}";
    PushEvent(json);
  }
  else
  {
    g_state.last_error = error_message != nullptr ? error_message : "Login failed";
    std::string json = "{\"type\":\"login_failed\",\"message\":";
    AppendJsonString(&json, g_state.last_error.c_str());
    json += "}";
    PushEvent(json);
  }
}

void LoadGameCallback(int result, const char* error_message, rc_client_t* client, void*)
{
  if (result == RC_OK)
  {
    g_state.last_error.clear();
    const rc_client_game_t* game = rc_client_get_game_info(client);
    char badge[512] = "";
    if (game != nullptr)
      rc_client_game_get_image_url(game, badge, sizeof(badge));
    std::string json = "{\"type\":\"game_loaded\",\"id\":";
    json += std::to_string(game != nullptr ? game->id : 0);
    json += ",\"title\":";
    AppendJsonString(&json, game != nullptr ? game->title : "");
    json += ",\"badgeUrl\":";
    AppendJsonString(&json, badge);
    json += "}";
    PushEvent(json);
  }
  else
  {
    g_state.last_error = error_message != nullptr ? error_message : "Game load failed";
    std::string json = "{\"type\":\"game_load_failed\",\"message\":";
    AppendJsonString(&json, g_state.last_error.c_str());
    json += "}";
    PushEvent(json);
  }
}

// ---------------------------------------------------------------------------
// Client lifecycle
// ---------------------------------------------------------------------------
// Implemented by the libretro core (libretro_host_interface.cpp): a CD reader
// on top of the core's CDImage so rcheevos can hash CHD and every other
// container the core can mount.
// GooseDroid probe: fail disc hashing explicitly; no SwanStation private API.
void* RC_CCONV DiscReaderOpenTrack(const char*, uint32_t) { return nullptr; }
size_t RC_CCONV DiscReaderReadSector(void*, uint32_t, void*, size_t) { return 0; }
uint32_t RC_CCONV DiscReaderFirstTrackSector(void*) { return 0; }
void RC_CCONV DiscReaderCloseTrack(void*) {}

void EnsureHashSupportLocked()
{
  static bool hash_support_initialized = false;
  if (hash_support_initialized)
    return;

  hash_support_initialized = true;
  // rc_client does not install the default file/CD readers itself; without
  // this every identify attempt fails with "hash generation failed".
  rc_hash_init_custom_filereader(nullptr);

  rc_hash_cdreader_t cdreader{};
  cdreader.open_track = DiscReaderOpenTrack;
  cdreader.read_sector = DiscReaderReadSector;
  cdreader.first_track_sector = DiscReaderFirstTrackSector;
  cdreader.close_track = DiscReaderCloseTrack;
  rc_hash_init_custom_cdreader(&cdreader);
}

void EnsureClientLocked()
{
  if (g_state.client != nullptr)
    return;

  EnsureHashSupportLocked();
  g_state.client = rc_client_create(ReadMemory, ServerCall);
  if (g_state.client == nullptr)
  {
    RALOGE("rc_client_create failed");
    return;
  }

  rc_client_set_event_handler(g_state.client, EventHandler);
  rc_client_set_hardcore_enabled(g_state.client, g_state.hardcore ? 1 : 0);
  rc_client_set_unofficial_enabled(g_state.client, g_state.unofficial ? 1 : 0);
  rc_client_set_encore_mode_enabled(g_state.client, g_state.encore ? 1 : 0);
}

void ReleaseMemoryLocked()
{
  if (g_state.memory_initialized)
  {
    rc_libretro_memory_destroy(&g_state.memory_regions);
    g_state.memory_initialized = false;
  }
  std::memset(&g_state.memory_regions, 0, sizeof(g_state.memory_regions));
}

}  // namespace

// ---------------------------------------------------------------------------
// Exported to native_bridge.cpp
// ---------------------------------------------------------------------------
extern "C" void EmuCoreRAchievementsInitializeJava(JNIEnv* env)
{
  if (env == nullptr)
    return;

  jclass response_class = env->FindClass("com/sbro/emucorer/core/AchievementsHttpResponse");
  if (response_class == nullptr)
  {
    RALOGE("AchievementsHttpResponse class not found");
    env->ExceptionClear();
    return;
  }
  g_http_response_class = static_cast<jclass>(env->NewGlobalRef(response_class));
  env->DeleteLocalRef(response_class);
  if (g_http_response_class == nullptr)
    return;

  g_http_status_field = env->GetFieldID(g_http_response_class, "statusCode", "I");
  g_http_body_field = env->GetFieldID(g_http_response_class, "body", "[B");

  jclass http_class = env->FindClass("com/sbro/emucorer/core/AchievementsHttp");
  if (http_class == nullptr)
  {
    RALOGE("AchievementsHttp class not found");
    env->ExceptionClear();
    return;
  }
  g_http_class = static_cast<jclass>(env->NewGlobalRef(http_class));
  env->DeleteLocalRef(http_class);
  if (g_http_class == nullptr)
    return;

  g_http_request_method = env->GetStaticMethodID(
    g_http_class, "request",
    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lcom/sbro/emucorer/core/AchievementsHttpResponse;");
}

extern "C" void EmuCoreRAchievementsSetJavaVm(JavaVM* vm)
{
  g_vm = vm;
}

extern "C" void EmuCoreRAchievementsOnFrame()
{
  if (!g_state.has_game.load(std::memory_order_relaxed))
    return;

  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  PumpHttpResponsesLocked();
  if (g_state.client == nullptr || !g_state.enabled || !g_state.has_game.load(std::memory_order_relaxed))
    return;

  rc_client_do_frame(g_state.client);
}

extern "C" void EmuCoreRAchievementsOnSessionEnd()
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  g_state.has_game.store(false, std::memory_order_relaxed);
  if (g_state.client != nullptr)
    rc_client_unload_game(g_state.client);
  ReleaseMemoryLocked();
}

extern "C" void EmuCoreRAchievementsShutdown()
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  if (g_state.client != nullptr)
  {
    rc_client_destroy(g_state.client);
    g_state.client = nullptr;
  }
  ReleaseMemoryLocked();
  StopHttpWorkerLocked();
  g_state.has_game.store(false, std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsSetEnabled(JNIEnv*, jobject, jboolean enabled)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  g_state.enabled = enabled == JNI_TRUE;
  if (g_state.enabled && !g_state.has_game.load(std::memory_order_relaxed))
    EnsureClientLocked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsSetHardcore(JNIEnv*, jobject, jboolean enabled)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  g_state.hardcore = enabled == JNI_TRUE;
  if (g_state.client != nullptr)
    rc_client_set_hardcore_enabled(g_state.client, g_state.hardcore ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsSetUnofficial(JNIEnv*, jobject, jboolean enabled)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  g_state.unofficial = enabled == JNI_TRUE;
  if (g_state.client != nullptr)
    rc_client_set_unofficial_enabled(g_state.client, g_state.unofficial ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsSetEncore(JNIEnv*, jobject, jboolean enabled)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  g_state.encore = enabled == JNI_TRUE;
  if (g_state.client != nullptr)
    rc_client_set_encore_mode_enabled(g_state.client, g_state.encore ? 1 : 0);
}

namespace {

jstring LoginWithCredentials(JNIEnv* env, jobject, jstring user, jstring secret, bool use_token)
{
  if (user == nullptr || secret == nullptr)
    return nullptr;

  const char* user_chars = env->GetStringUTFChars(user, nullptr);
  const char* secret_chars = env->GetStringUTFChars(secret, nullptr);
  if (user_chars == nullptr || secret_chars == nullptr)
  {
    if (user_chars != nullptr)
      env->ReleaseStringUTFChars(user, user_chars);
    if (secret_chars != nullptr)
      env->ReleaseStringUTFChars(secret, secret_chars);
    return nullptr;
  }

  const std::string user_value = user_chars;
  const std::string secret_value = secret_chars;
  env->ReleaseStringUTFChars(user, user_chars);
  env->ReleaseStringUTFChars(secret, secret_chars);

  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  EnsureClientLocked();
  if (g_state.client == nullptr)
    return nullptr;

  StartHttpWorkerLocked();
  if (use_token)
    rc_client_begin_login_with_token(g_state.client, user_value.c_str(), secret_value.c_str(), LoginCallback, nullptr);
  else
    rc_client_begin_login_with_password(g_state.client, user_value.c_str(), secret_value.c_str(), LoginCallback,
                                        nullptr);
  PumpHttpResponsesLocked();

  const rc_client_user_t* info = rc_client_get_user_info(g_state.client);
  return info != nullptr ? env->NewStringUTF(info->token != nullptr ? info->token : "") : nullptr;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsLoginWithPassword(JNIEnv* env, jobject thiz, jstring user,
                                                                           jstring password)
{
  return LoginWithCredentials(env, thiz, user, password, false);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsLoginWithToken(JNIEnv* env, jobject thiz, jstring user,
                                                                        jstring token)
{
  return LoginWithCredentials(env, thiz, user, token, true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsLogout(JNIEnv*, jobject)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  if (g_state.client != nullptr)
    rc_client_logout(g_state.client);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsLoadGame(JNIEnv* env, jobject, jstring path)
{
  if (path == nullptr)
    return;

  const char* chars = env->GetStringUTFChars(path, nullptr);
  if (chars == nullptr)
    return;
  const std::string game_path = chars;
  env->ReleaseStringUTFChars(path, chars);
  if (game_path.empty())
    return;

  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  EnsureClientLocked();
  if (g_state.client == nullptr)
    return;

  // The core has to be running for SYSTEM_RAM to be readable.
  ReleaseMemoryLocked();
  if (rc_libretro_memory_init(&g_state.memory_regions, nullptr, GetCoreMemoryInfo, RC_CONSOLE_PLAYSTATION))
    g_state.memory_initialized = true;
  g_state.last_error.clear();

  StartHttpWorkerLocked();
  rc_client_begin_identify_and_load_game(g_state.client, RC_CONSOLE_PLAYSTATION, game_path.c_str(), nullptr, 0,
                                         LoadGameCallback, nullptr);
  g_state.has_game.store(true, std::memory_order_relaxed);
  PumpHttpResponsesLocked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsUnloadGame(JNIEnv*, jobject)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  g_state.has_game.store(false, std::memory_order_relaxed);
  if (g_state.client != nullptr)
    rc_client_unload_game(g_state.client);
  ReleaseMemoryLocked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsPump(JNIEnv*, jobject)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  PumpHttpResponsesLocked();
  if (g_state.client != nullptr && g_state.has_game.load(std::memory_order_relaxed) && g_state.enabled)
    rc_client_idle(g_state.client);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsStateJson(JNIEnv* env, jobject)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  PumpHttpResponsesLocked();

  rc_client_t* client = g_state.client;
  const rc_client_user_t* user = client != nullptr ? rc_client_get_user_info(client) : nullptr;
  const rc_client_game_t* game = client != nullptr ? rc_client_get_game_info(client) : nullptr;
  rc_client_user_game_summary_t summary{};
  if (client != nullptr)
    rc_client_get_user_game_summary(client, &summary);

  // Keep the totals consistent with the filtered achievement list.
  uint32_t warning_total = 0;
  uint32_t warning_unlocked = 0;
  if (client != nullptr)
  {
    rc_client_achievement_list_t* warning_list = rc_client_create_achievement_list(
      client, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
    if (warning_list != nullptr)
    {
      for (uint32_t bucket_index = 0; bucket_index < warning_list->num_buckets; ++bucket_index)
      {
        const rc_client_achievement_bucket_t& bucket = warning_list->buckets[bucket_index];
        for (size_t i = 0; i < bucket.num_achievements; ++i)
        {
          const rc_client_achievement_t* achievement = bucket.achievements[i];
          if (!IsUnsupportedEmulatorWarning(achievement))
            continue;
          ++warning_total;
          if (achievement->state == RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED)
            ++warning_unlocked;
        }
      }
      rc_client_destroy_achievement_list(warning_list);
    }
  }
  long total_achievements = static_cast<long>(summary.num_core_achievements) - static_cast<long>(warning_total);
  if (total_achievements < 0)
    total_achievements = 0;
  long unlocked_achievements =
    static_cast<long>(summary.num_unlocked_achievements) - static_cast<long>(warning_unlocked);
  if (unlocked_achievements < 0)
    unlocked_achievements = 0;

  char rich_presence[256] = "";
  if (client != nullptr)
    rc_client_get_rich_presence_message(client, rich_presence, sizeof(rich_presence));

  std::string json = "{";
  json += "\"available\":true";
  json += ",\"enabled\":";
  json += g_state.enabled ? "true" : "false";
  json += ",\"hardcore\":";
  json += g_state.hardcore ? "true" : "false";
  json += ",\"unofficial\":";
  json += g_state.unofficial ? "true" : "false";
  json += ",\"encore\":";
  json += g_state.encore ? "true" : "false";
  json += ",\"loggedIn\":";
  json += user != nullptr ? "true" : "false";
  json += ",\"gameLoaded\":";
  json += game != nullptr ? "true" : "false";
  json += ",\"loadState\":";
  json += std::to_string(client != nullptr ? rc_client_get_load_game_state(client) : 0);
  json += ",\"richPresence\":";
  AppendJsonString(&json, rich_presence);
  json += ",\"lastError\":";
  AppendJsonString(&json, g_state.last_error.c_str());
  json += ",\"user\":";
  if (user != nullptr)
  {
    char avatar[512] = "";
    rc_client_user_get_image_url(user, avatar, sizeof(avatar));
    json += "{\"username\":";
    AppendJsonString(&json, user->username);
    json += ",\"displayName\":";
    AppendJsonString(&json, user->display_name);
    json += ",\"token\":";
    AppendJsonString(&json, user->token);
    json += ",\"score\":" + std::to_string(user->score);
    json += ",\"scoreSoftcore\":" + std::to_string(user->score_softcore);
    json += ",\"avatarUrl\":";
    AppendJsonString(&json, avatar);
    json += "}";
  }
  else
  {
    json += "null";
  }
  json += ",\"game\":";
  if (game != nullptr)
  {
    char badge[512] = "";
    rc_client_game_get_image_url(game, badge, sizeof(badge));
    json += "{\"id\":" + std::to_string(game->id);
    json += ",\"title\":";
    AppendJsonString(&json, game->title);
    json += ",\"badgeUrl\":";
    AppendJsonString(&json, badge);
    json += "}";
  }
  else
  {
    json += "null";
  }
  json += ",\"summary\":{\"total\":" + std::to_string(total_achievements);
  json += ",\"unlocked\":" + std::to_string(unlocked_achievements);
  json += ",\"unsupported\":" + std::to_string(summary.num_unsupported_achievements);
  json += ",\"points\":" + std::to_string(summary.points_core);
  json += ",\"pointsUnlocked\":" + std::to_string(summary.points_unlocked);
  json += ",\"beatenTime\":" + std::to_string(static_cast<long long>(summary.beaten_time));
  json += ",\"completedTime\":" + std::to_string(static_cast<long long>(summary.completed_time));
  json += "}}";
  return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsAchievementsJson(JNIEnv* env, jobject)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  if (g_state.client == nullptr)
    return env->NewStringUTF("[]");

  rc_client_achievement_list_t* list = rc_client_create_achievement_list(
    g_state.client, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
  if (list == nullptr)
    return env->NewStringUTF("[]");

  std::string json = "[";
  bool first_item = true;
  for (uint32_t bucket_index = 0; bucket_index < list->num_buckets; ++bucket_index)
  {
    const rc_client_achievement_bucket_t& bucket = list->buckets[bucket_index];
    for (size_t i = 0; i < bucket.num_achievements; ++i)
    {
      const rc_client_achievement_t* achievement = bucket.achievements[i];
      if (achievement == nullptr || IsUnsupportedEmulatorWarning(achievement))
        continue;

      char badge[512] = "";
      char badge_locked[512] = "";
      rc_client_achievement_get_image_url(achievement, achievement->state, badge, sizeof(badge));
      rc_client_achievement_get_image_url(achievement, RC_CLIENT_ACHIEVEMENT_STATE_INACTIVE, badge_locked,
                                          sizeof(badge_locked));

      if (!first_item)
        json += ",";
      first_item = false;
      json += "{\"id\":" + std::to_string(achievement->id);
      json += ",\"title\":";
      AppendJsonString(&json, achievement->title);
      json += ",\"description\":";
      AppendJsonString(&json, achievement->description);
      json += ",\"points\":" + std::to_string(achievement->points);
      json += ",\"state\":" + std::to_string(achievement->state);
      json += ",\"bucket\":";
      AppendJsonString(&json, AchievementBucketName(achievement->bucket));
      json += ",\"unlocked\":" + std::string(achievement->state == RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED ? "true" : "false");
      json += ",\"unlockTime\":" + std::to_string(static_cast<long long>(achievement->unlock_time));
      json += ",\"measuredProgress\":";
      AppendJsonString(&json, achievement->measured_progress);
      json += ",\"measuredPercent\":" + std::to_string(achievement->measured_percent);
      json += ",\"rarity\":" + std::to_string(achievement->rarity);
      json += ",\"type\":" + std::to_string(achievement->type);
      json += ",\"badgeUrl\":";
      AppendJsonString(&json, badge);
      json += ",\"badgeLockedUrl\":";
      AppendJsonString(&json, badge_locked);
      json += "}";
    }
  }
  json += "]";

  rc_client_destroy_achievement_list(list);
  return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sbro_emucorer_core_NativeCoreBridge_achievementsPollEventsJson(JNIEnv* env, jobject)
{
  std::lock_guard<std::recursive_mutex> lock(g_state.mutex);
  PumpHttpResponsesLocked();

  if (g_state.events.empty())
    return env->NewStringUTF("[]");

  std::string json = "[";
  bool first = true;
  for (const std::string& event : g_state.events)
  {
    if (!first)
      json += ",";
    first = false;
    json += event;
  }
  json += "]";
  g_state.events.clear();
  return env->NewStringUTF(json.c_str());
}
