#include <jni.h>
#include <cstdint>
#include <vector>
#include <ctime>
#include "psemu/psemu.h"
static psemu_t* ptr(jlong h) { return reinterpret_cast<psemu_t*>(static_cast<intptr_t>(h)); }
static std::vector<uint8_t> bytes(JNIEnv* e, jbyteArray a) {
    if (!a) return {};
    std::vector<uint8_t> b(e->GetArrayLength(a));
    e->GetByteArrayRegion(a, 0, b.size(), reinterpret_cast<jbyte*>(b.data())); return b;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_sbro_emucorer_pocket_PocketNative_open(JNIEnv* e,jclass,jbyteArray bios,jbyteArray content) {
    auto b=bytes(e,bios), c=bytes(e,content);
    auto* p=psemu_create(); if(!p) return 0;
    if(psemu_load_bios(p,b.data(),b.size())!=PSEMU_OK || psemu_load_content(p,c.data(),c.size())!=PSEMU_OK) { psemu_destroy(p); return 0; }
    psemu_reset(p);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(p));
}
extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorer_pocket_PocketNative_close(JNIEnv*,jclass,jlong h) { if(h) psemu_destroy(ptr(h)); }
extern "C" JNIEXPORT jint JNICALL Java_com_sbro_emucorer_pocket_PocketNative_tick(JNIEnv* e,jclass,jlong h,jint buttons,jintArray pixels,jshortArray audio) {
    auto* p=ptr(h); if(!p || psemu_cpu_faulted(p)) return -1;
    psemu_set_buttons(p,buttons);
    if(psemu_settings_offsets_known(p) && !psemu_app_running(p)) {
        time_t now=time(nullptr); tm t{}; localtime_r(&now,&t);
        psemu_set_datetime(p,t.tm_year+1900,t.tm_mon+1,t.tm_mday,t.tm_hour,t.tm_min,t.tm_sec,t.tm_wday+1);
    }
    psemu_run(p,PSEMU_REFERENCE_CLOCK_HZ/32);
    if(psemu_cpu_faulted(p)) return -1;
    jint out[1024]; auto* fb=psemu_get_framebuffer(p);
    for(int i=0;i<1024;i++) out[i]=(fb[i/8]&(1<<(i%8))) ? static_cast<jint>(0xFF253322) : static_cast<jint>(0xFFB8C9A1);
    e->SetIntArrayRegion(pixels,0,1024,out);
    int16_t pcm[1024]; auto n=psemu_get_audio_samples(p,pcm,1024);
    e->SetShortArrayRegion(audio,0,n,pcm); return n;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_sbro_emucorer_pocket_PocketNative_card(JNIEnv* e,jclass,jlong h) {
    if(!h || psemu_cpu_faulted(ptr(h))) return nullptr;
    std::vector<uint8_t> b(PSEMU_FLASH_SIZE); psemu_save_flash_image(ptr(h),b.data(),b.size());
    auto a=e->NewByteArray(b.size()); if(a) e->SetByteArrayRegion(a,0,b.size(),reinterpret_cast<jbyte*>(b.data())); return a;
}
