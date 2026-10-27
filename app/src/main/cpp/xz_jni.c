// .xz decoding for NativeXzInputStream (Kotlin), on top of liblzma.
#include <jni.h>
#include <lzma.h>
#include <stdint.h>
#include <stdlib.h>

JNIEXPORT jlong JNICALL
Java_app_pocketfoundry_NativeXz_open(JNIEnv *env, jclass cls) {
    lzma_stream *s = malloc(sizeof *s);
    if (!s)
        return 0;
    *s = (lzma_stream) LZMA_STREAM_INIT;
    if (lzma_stream_decoder(s, UINT64_MAX, LZMA_CONCATENATED) != LZMA_OK) {
        free(s);
        return 0;
    }
    return (jlong) (intptr_t) s;
}

// Decodes in[inOff, inOff + inLen) into out[outOff, outOff + outLen).
// Returns consumed << 32 | produced, with bit 62 set at the end of the data,
// or -(liblzma error code) on failure.
JNIEXPORT jlong JNICALL
Java_app_pocketfoundry_NativeXz_decode(JNIEnv *env, jclass cls, jlong handle,
                                       jbyteArray in, jint inOff, jint inLen,
                                       jbyteArray out, jint outOff, jint outLen,
                                       jboolean inputEnded) {
    lzma_stream *s = (lzma_stream *) (intptr_t) handle;
    jbyte *inBytes = (*env)->GetPrimitiveArrayCritical(env, in, NULL);
    jbyte *outBytes = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    s->next_in = (const uint8_t *) inBytes + inOff;
    s->avail_in = (size_t) inLen;
    s->next_out = (uint8_t *) outBytes + outOff;
    s->avail_out = (size_t) outLen;
    lzma_ret ret = lzma_code(s, inputEnded ? LZMA_FINISH : LZMA_RUN);
    jlong consumed = inLen - (jlong) s->avail_in;
    jlong produced = outLen - (jlong) s->avail_out;
    (*env)->ReleasePrimitiveArrayCritical(env, out, outBytes, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, in, inBytes, JNI_ABORT);

    if (ret != LZMA_OK && ret != LZMA_STREAM_END)
        return -(jlong) ret;
    return consumed << 32 | produced | (ret == LZMA_STREAM_END ? 1LL << 62 : 0);
}

JNIEXPORT void JNICALL
Java_app_pocketfoundry_NativeXz_close(JNIEnv *env, jclass cls, jlong handle) {
    lzma_stream *s = (lzma_stream *) (intptr_t) handle;
    lzma_end(s);
    free(s);
}
