#include <ctype.h>
#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#define STATUS_CLEAR "CLEAR"
#define STATUS_FAILED "FAILED"
#define STATUS_HOLD "HOLD"

static bool is_ascii_whitespace(unsigned char value) {
    return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f' || value == '\v';
}

static bool normalized_equals(const char *value, const char *expected) {
    return strcmp(value, expected) == 0;
}

static void uppercase_ascii_in_place(char *value) {
    while (*value != '\0') {
        *value = (char)toupper((unsigned char)*value);
        value++;
    }
}

static char *copy_utf_chars(JNIEnv *env, jstring source, const char **utf_chars) {
    *utf_chars = (*env)->GetStringUTFChars(env, source, NULL);
    if (*utf_chars == NULL) {
        return NULL;
    }

    size_t length = strlen(*utf_chars);
    char *buffer = (char *)malloc(length + 1u);
    if (buffer == NULL) {
        (*env)->ReleaseStringUTFChars(env, source, *utf_chars);
        *utf_chars = NULL;
        return NULL;
    }

    memcpy(buffer, *utf_chars, length + 1u);
    return buffer;
}

static void release_utf_copy(JNIEnv *env, jstring source, const char **utf_chars, char *copy) {
    if (*utf_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, source, *utf_chars);
        *utf_chars = NULL;
    }
    free(copy);
}

static const char *canonical_status_from_ascii_buffer(char *status_copy) {
    size_t write_index = 0u;

    for (size_t read_index = 0u; status_copy[read_index] != '\0'; read_index++) {
        unsigned char value = (unsigned char)status_copy[read_index];
        if (is_ascii_whitespace(value) || value == '-' || value == '_') {
            continue;
        }
        status_copy[write_index++] = (char)toupper(value);
    }
    status_copy[write_index] = '\0';

    if (write_index == 0u) {
        return STATUS_HOLD;
    }
    if (normalized_equals(status_copy, "CLEAR") ||
        normalized_equals(status_copy, "PASS") ||
        normalized_equals(status_copy, "PASSED") ||
        normalized_equals(status_copy, "RELEASED")) {
        return STATUS_CLEAR;
    }
    if (normalized_equals(status_copy, "FAILED") ||
        normalized_equals(status_copy, "FAIL") ||
        normalized_equals(status_copy, "REJECTED")) {
        return STATUS_FAILED;
    }
    if (normalized_equals(status_copy, "HOLD") ||
        normalized_equals(status_copy, "ONHOLD") ||
        normalized_equals(status_copy, "PENDING") ||
        normalized_equals(status_copy, "WAITING")) {
        return STATUS_HOLD;
    }
    return STATUS_HOLD;
}

JNIEXPORT jstring JNICALL
Java_nz_co_mixport_customsvision_data_NativeScannerBridge_nativeNormalizeBarcode(
    JNIEnv *env,
    jobject thiz,
    jstring raw_value
) {
    (void)thiz;
    if (raw_value == NULL) {
        return (*env)->NewStringUTF(env, "");
    }

    const char *utf_chars = NULL;
    char *input_copy = copy_utf_chars(env, raw_value, &utf_chars);
    if (input_copy == NULL) {
        return (*env)->NewStringUTF(env, "");
    }

    size_t input_length = strlen(input_copy);
    char *normalized = (char *)malloc(input_length + 1u);
    if (normalized == NULL) {
        release_utf_copy(env, raw_value, &utf_chars, input_copy);
        return (*env)->NewStringUTF(env, "");
    }

    size_t write_index = 0u;
    for (size_t read_index = 0u; read_index < input_length; read_index++) {
        unsigned char value = (unsigned char)input_copy[read_index];
        if (value == '\0' || is_ascii_whitespace(value)) {
            continue;
        }
        normalized[write_index++] = (char)value;
    }
    normalized[write_index] = '\0';

    if (write_index > 2u && normalized[0] == '*' && normalized[write_index - 1u] == '*') {
        memmove(normalized, normalized + 1, write_index - 2u);
        write_index -= 2u;
        normalized[write_index] = '\0';
    }

    uppercase_ascii_in_place(normalized);
    jstring result = (*env)->NewStringUTF(env, normalized);

    free(normalized);
    release_utf_copy(env, raw_value, &utf_chars, input_copy);
    return result;
}

JNIEXPORT jstring JNICALL
Java_nz_co_mixport_customsvision_data_NativeScannerBridge_nativeCanonicalClearanceStatus(
    JNIEnv *env,
    jobject thiz,
    jstring status
) {
    (void)thiz;
    if (status == NULL) {
        return (*env)->NewStringUTF(env, STATUS_HOLD);
    }

    const char *utf_chars = NULL;
    char *status_copy = copy_utf_chars(env, status, &utf_chars);
    if (status_copy == NULL) {
        return (*env)->NewStringUTF(env, STATUS_HOLD);
    }

    const char *result = canonical_status_from_ascii_buffer(status_copy);
    jstring output = (*env)->NewStringUTF(env, result);

    release_utf_copy(env, status, &utf_chars, status_copy);
    return output;
}

JNIEXPORT jstring JNICALL
Java_nz_co_mixport_customsvision_data_NativeScannerBridge_nativeOverallClearanceStatus(
    JNIEnv *env,
    jobject thiz,
    jstring nzcs_status,
    jstring mpi_status
) {
    (void)thiz;
    const char *nzcs_utf = NULL;
    const char *mpi_utf = NULL;
    char *nzcs_copy = copy_utf_chars(env, nzcs_status, &nzcs_utf);
    char *mpi_copy = copy_utf_chars(env, mpi_status, &mpi_utf);

    if (nzcs_copy == NULL || mpi_copy == NULL) {
        free(nzcs_copy);
        free(mpi_copy);
        if (nzcs_utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, nzcs_status, nzcs_utf);
        }
        if (mpi_utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, mpi_status, mpi_utf);
        }
        return (*env)->NewStringUTF(env, STATUS_HOLD);
    }

    const char *normalized_nzcs = canonical_status_from_ascii_buffer(nzcs_copy);
    const char *normalized_mpi = canonical_status_from_ascii_buffer(mpi_copy);
    const char *overall = STATUS_HOLD;

    if (normalized_equals(normalized_nzcs, STATUS_FAILED) || normalized_equals(normalized_mpi, STATUS_FAILED)) {
        overall = STATUS_FAILED;
    } else if (normalized_equals(normalized_nzcs, STATUS_CLEAR) && normalized_equals(normalized_mpi, STATUS_CLEAR)) {
        overall = STATUS_CLEAR;
    }

    jstring result = (*env)->NewStringUTF(env, overall);
    release_utf_copy(env, nzcs_status, &nzcs_utf, nzcs_copy);
    release_utf_copy(env, mpi_status, &mpi_utf, mpi_copy);
    return result;
}
