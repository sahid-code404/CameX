#include <jni.h>

#include <array>
#include <cstdint>
#include <numeric>

namespace {

constexpr char kNativeVersion[] = "camex-native/0.1.0";

constexpr bool self_test() noexcept {
    constexpr std::array<std::uint32_t, 4> values{1U, 2U, 3U, 4U};
    return std::accumulate(values.begin(), values.end(), 0U) == 10U;
}

static_assert(self_test(), "CameX native compile-time self-test failed");

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sahidcode404_camex_nativebridge_NativeBridge_nativeVersion(
    JNIEnv* environment,
    jobject /* bridge */) {
    return environment->NewStringUTF(kNativeVersion);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sahidcode404_camex_nativebridge_NativeBridge_nativeSelfTest(
    JNIEnv* /* environment */,
    jobject /* bridge */) {
    return self_test() ? JNI_TRUE : JNI_FALSE;
}
