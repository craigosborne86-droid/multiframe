#!/usr/bin/env python3
"""Build unchanged production ARM64 fusion/alignment JNI for deterministic host replay.

Android bitmap/output entry points are intentionally unavailable. This is a bench
library, never packaged in the APK; its timings are not phone-performance evidence.
"""
from pathlib import Path
import argparse
import json
import platform
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
args = parser.parse_args()
assert platform.system() == "Darwin" and platform.machine() == "arm64", "ARM64 macOS bench only"
root = Path(__file__).resolve().parents[2]
out = args.output.resolve()
(out / "include/android").mkdir(parents=True, exist_ok=True)
(out / "include/android/log.h").write_text("""#pragma once
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
inline int __android_log_print(int, const char*, const char*, ...) { return 0; }
""")
(out / "include/android/bitmap.h").write_text("""#pragma once
#include <jni.h>
#include <cstdint>
struct AndroidBitmapInfo { uint32_t width, height, stride; int32_t format; uint32_t flags; };
#define ANDROID_BITMAP_RESULT_SUCCESS 0
#define ANDROID_BITMAP_FORMAT_RGBA_8888 1
inline int AndroidBitmap_getInfo(JNIEnv*, jobject, AndroidBitmapInfo*) { return -1; }
inline int AndroidBitmap_lockPixels(JNIEnv*, jobject, void**) { return -1; }
inline int AndroidBitmap_unlockPixels(JNIEnv*, jobject) { return -1; }
""")
(out / "bridge.cpp").write_text("""#include "bayer_merge.cpp"
// Diagnostic access to actual accumulated weights. No fusion arithmetic changes.
extern "C" JNIEXPORT void JNICALL
Java_dev_multiframe_camera_bench_BurstReplayBench_readWeights(JNIEnv* env, jobject, jlong handle, jfloatArray dst) {
    auto* acc = reinterpret_cast<Accumulator*>(handle);
    if (!acc || env->GetArrayLength(dst) != acc->width * acc->height) return;
    env->SetFloatArrayRegion(dst, 0, acc->width * acc->height, acc->weight.data());
}
extern "C" JNIEXPORT void JNICALL
Java_dev_multiframe_camera_bench_BurstReplayBench_readResolved(JNIEnv* env, jobject, jlong handle, jfloatArray dst) {
    auto* acc = reinterpret_cast<Accumulator*>(handle);
    if (!acc || env->GetArrayLength(dst) != acc->width * acc->height) return;
    std::vector<float> value(acc->sum.size());
    for (size_t i = 0; i < value.size(); ++i) value[i] = acc->sum[i] / acc->weight[i];
    env->SetFloatArrayRegion(dst, 0, value.size(), value.data());
}
""")
java = Path(subprocess.check_output(["/usr/libexec/java_home"], text=True).strip())
cpp = root / "app/src/main/cpp"
command = ["clang++", "-std=c++20", "-O3", "-ffast-math", "-dynamiclib",
           "-undefined", "dynamic_lookup", "-I" + str(out / "include"),
           "-I" + str(java / "include"), "-I" + str(java / "include/darwin"),
           "-I" + str(cpp), str(out / "bridge.cpp"), str(cpp / "Align.cpp"),
           str(cpp / "SubPixel.cpp"), "-o", str(out / "libmultiframe.dylib")]
(out / "build-command.json").write_text(json.dumps(command, indent=2) + "\n")
subprocess.run(command, check=True)
print(out / "libmultiframe.dylib")
