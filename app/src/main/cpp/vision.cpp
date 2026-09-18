#include <jni.h>
#include <opencv2/imgproc.hpp>
#include <cstdint>
#include <stdexcept>
#include <vector>

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_commaengdoughme_NativeVision_preprocess(
        JNIEnv* env, jobject, jobject buffer, jint width, jint height,
        jint rowStride, jint pixelStride, jint rotation, jboolean threshold) {
    try {
        // Validate before any pointer arithmetic; a plane may omit padding in its last row.
        if (!buffer || width <= 0 || height <= 0 || width > 4096 || height > 4096 ||
            rowStride <= 0 || pixelStride <= 0 ||
            (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
            throw std::invalid_argument("Invalid frame dimensions, strides or rotation");
        }
        const int64_t rowBytes = int64_t(width - 1) * pixelStride + 1;
        const int64_t required = int64_t(height - 1) * rowStride + rowBytes;
        auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
        if (!data || rowBytes > rowStride || env->GetDirectBufferCapacity(buffer) < required) {
            throw std::invalid_argument("Y plane must be a sufficiently large direct buffer");
        }
        cv::Mat gray(height, width, CV_8UC1);
        for (int y = 0; y < height; ++y) {
            auto* dst = gray.ptr<uint8_t>(y);
            for (int x = 0; x < width; ++x) {
                dst[x] = data[int64_t(y) * rowStride + int64_t(x) * pixelStride];
            }
        }
        if (rotation == 90) cv::rotate(gray, gray, cv::ROTATE_90_CLOCKWISE);
        if (rotation == 180) cv::rotate(gray, gray, cv::ROTATE_180);
        if (rotation == 270) cv::rotate(gray, gray, cv::ROTATE_90_COUNTERCLOCKWISE);
        if (threshold) {
            cv::adaptiveThreshold(gray, gray, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                                  cv::THRESH_BINARY, 31, 7);
        }
        std::vector<jint> pixels(gray.total());
        for (int y = 0; y < gray.rows; ++y) {
            for (int x = 0; x < gray.cols; ++x) {
                uint32_t v = gray.at<uint8_t>(y, x);
                pixels[size_t(y) * gray.cols + x] = static_cast<jint>(0xff000000u | v << 16 | v << 8 | v);
            }
        }
        auto result = env->NewIntArray(static_cast<jsize>(pixels.size()));
        if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(pixels.size()), pixels.data());
        return result;
    } catch (const std::invalid_argument& e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), e.what());
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
    }
    return nullptr;
}
