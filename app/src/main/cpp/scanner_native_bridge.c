#include <jni.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

static bool is_ascii_whitespace(jchar value) {
    return value == ' ' || value == '\n' || value == '\r' || value == '\t' || value == '\f';
}

static jchar to_ascii_upper(jchar value) {
    if (value >= 'a' && value <= 'z') {
        return (jchar)(value - ('a' - 'A'));
    }
    return value;
}

static bool normalize_ascii_token(
    const jchar* source,
    jsize length,
    char* target,
    size_t target_capacity,
    size_t* target_length
) {
    size_t cursor = 0;
    for (jsize index = 0; index < length; index++) {
        jchar value = source[index];
        if (value == 0 || is_ascii_whitespace(value) || value == '-' || value == '_') {
            continue;
        }
        if (value > 0x7F || cursor + 1 >= target_capacity) {
            return false;
        }
        target[cursor++] = (char)to_ascii_upper(value);
    }
    target[cursor] = '\0';
    *target_length = cursor;
    return true;
}

static jstring new_ascii_string(JNIEnv* env, const char* value) {
    return (*env)->NewStringUTF(env, value);
}

static const char* canonical_clearance_status_ascii(const char* normalized) {
    if (normalized[0] == '\0') {
        return "HOLD";
    }
    if (
        strcmp(normalized, "CLEAR") == 0 ||
        strcmp(normalized, "PASS") == 0 ||
        strcmp(normalized, "PASSED") == 0 ||
        strcmp(normalized, "RELEASED") == 0
    ) {
        return "CLEAR";
    }
    if (
        strcmp(normalized, "FAILED") == 0 ||
        strcmp(normalized, "FAIL") == 0 ||
        strcmp(normalized, "REJECTED") == 0
    ) {
        return "FAILED";
    }
    if (
        strcmp(normalized, "HOLD") == 0 ||
        strcmp(normalized, "ONHOLD") == 0 ||
        strcmp(normalized, "PENDING") == 0 ||
        strcmp(normalized, "WAITING") == 0
    ) {
        return "HOLD";
    }
    return "HOLD";
}

JNIEXPORT jstring JNICALL
Java_nz_co_mixport_customsvision_nativebridge_ScannerNativeBridge_nativeNormalizeScannerBarcode(
    JNIEnv* env,
    jobject thiz,
    jstring raw_value
) {
    (void)thiz;
    if (raw_value == NULL) {
        return new_ascii_string(env, "");
    }

    const jsize length = (*env)->GetStringLength(env, raw_value);
    const jchar* source = (*env)->GetStringChars(env, raw_value, NULL);
    if (source == NULL) {
        return NULL;
    }

    jchar* buffer = (jchar*)malloc(sizeof(jchar) * (size_t)length);
    if (buffer == NULL) {
        (*env)->ReleaseStringChars(env, raw_value, source);
        return NULL;
    }

    jsize normalized_length = 0;
    for (jsize index = 0; index < length; index++) {
        jchar value = source[index];
        if (value == 0 || is_ascii_whitespace(value)) {
            continue;
        }
        buffer[normalized_length++] = to_ascii_upper(value);
    }

    (*env)->ReleaseStringChars(env, raw_value, source);

    jsize start = 0;
    if (normalized_length > 2 && buffer[0] == '*' && buffer[normalized_length - 1] == '*') {
        start = 1;
        normalized_length -= 2;
    }

    jstring result = (*env)->NewString(env, buffer + start, normalized_length);
    free(buffer);
    return result;
}

JNIEXPORT jstring JNICALL
Java_nz_co_mixport_customsvision_nativebridge_ScannerNativeBridge_nativeCanonicalScannerClearanceStatus(
    JNIEnv* env,
    jobject thiz,
    jstring raw_value
) {
    (void)thiz;
    if (raw_value == NULL) {
        return new_ascii_string(env, "HOLD");
    }

    const jsize length = (*env)->GetStringLength(env, raw_value);
    const jchar* source = (*env)->GetStringChars(env, raw_value, NULL);
    if (source == NULL) {
        return NULL;
    }

    char normalized[64];
    size_t normalized_length = 0;
    const bool ok = normalize_ascii_token(source, length, normalized, sizeof(normalized), &normalized_length);
    (*env)->ReleaseStringChars(env, raw_value, source);
    if (!ok) {
        return NULL;
    }

    return new_ascii_string(env, canonical_clearance_status_ascii(normalized));
}

JNIEXPORT jstring JNICALL
Java_nz_co_mixport_customsvision_nativebridge_ScannerNativeBridge_nativeOverallScannerClearanceStatus(
    JNIEnv* env,
    jobject thiz,
    jstring nzcs_status,
    jstring mpi_status
) {
    (void)thiz;

    const char* normalized_nzcs = "HOLD";
    const char* normalized_mpi = "HOLD";
    char nzcs_buffer[64];
    char mpi_buffer[64];
    size_t normalized_length = 0;

    if (nzcs_status != NULL) {
        const jsize length = (*env)->GetStringLength(env, nzcs_status);
        const jchar* source = (*env)->GetStringChars(env, nzcs_status, NULL);
        if (source == NULL) {
            return NULL;
        }
        const bool ok = normalize_ascii_token(source, length, nzcs_buffer, sizeof(nzcs_buffer), &normalized_length);
        (*env)->ReleaseStringChars(env, nzcs_status, source);
        if (!ok) {
            return NULL;
        }
        normalized_nzcs = canonical_clearance_status_ascii(nzcs_buffer);
    }

    if (mpi_status != NULL) {
        const jsize length = (*env)->GetStringLength(env, mpi_status);
        const jchar* source = (*env)->GetStringChars(env, mpi_status, NULL);
        if (source == NULL) {
            return NULL;
        }
        const bool ok = normalize_ascii_token(source, length, mpi_buffer, sizeof(mpi_buffer), &normalized_length);
        (*env)->ReleaseStringChars(env, mpi_status, source);
        if (!ok) {
            return NULL;
        }
        normalized_mpi = canonical_clearance_status_ascii(mpi_buffer);
    }

    if (strcmp(normalized_nzcs, "FAILED") == 0 || strcmp(normalized_mpi, "FAILED") == 0) {
        return new_ascii_string(env, "FAILED");
    }
    if (strcmp(normalized_nzcs, "CLEAR") == 0 && strcmp(normalized_mpi, "CLEAR") == 0) {
        return new_ascii_string(env, "CLEAR");
    }
    return new_ascii_string(env, "HOLD");
}
