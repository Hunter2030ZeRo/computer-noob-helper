#include <jni.h>
#include <opencv2/imgproc.hpp>
#include <cstdint>
#include <stdexcept>
#include <vector>
#include <algorithm>

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_commaengdoughme_NativeVision_handRgb(
        JNIEnv* env, jobject, jobject yBuffer, jobject uBuffer, jobject vBuffer,
        jint width, jint height, jintArray strideArray, jint outWidth, jint outHeight) {
    try {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096 ||
            outWidth <= 0 || outHeight <= 0 || outWidth > 512 || outHeight > 512 ||
            !strideArray || env->GetArrayLength(strideArray) != 6) {
            throw std::invalid_argument("Invalid hand frame dimensions or strides");
        }
        jint strides[6];
        env->GetIntArrayRegion(strideArray, 0, 6, strides);
        jobject buffers[3] = {yBuffer, uBuffer, vBuffer};
        uint8_t* data[3];
        for (int i = 0; i < 3; ++i) {
            const int w = i == 0 ? width : (width + 1) / 2;
            const int h = i == 0 ? height : (height + 1) / 2;
            const int row = strides[i * 2], pixel = strides[i * 2 + 1];
            const int64_t rowBytes = int64_t(w - 1) * pixel + 1;
            const int64_t required = int64_t(h - 1) * row + rowBytes;
            if (!buffers[i] || row <= 0 || pixel <= 0 || rowBytes > row) throw std::invalid_argument("Invalid YUV plane");
            data[i] = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffers[i]));
            if (!data[i] || env->GetDirectBufferCapacity(buffers[i]) < required) throw std::invalid_argument("Truncated YUV plane");
        }
        std::vector<jint> pixels(size_t(outWidth) * outHeight);
        for (int y = 0; y < outHeight; ++y) for (int x = 0; x < outWidth; ++x) {
            const int sx = x * width / outWidth, sy = y * height / outHeight;
            const int luma = std::max(0, int(data[0][int64_t(sy) * strides[0] + int64_t(sx) * strides[1]]) - 16);
            const int u = int(data[1][int64_t(sy / 2) * strides[2] + int64_t(sx / 2) * strides[3]]) - 128;
            const int v = int(data[2][int64_t(sy / 2) * strides[4] + int64_t(sx / 2) * strides[5]]) - 128;
            const auto r = std::clamp((298 * luma + 409 * v + 128) >> 8, 0, 255);
            const auto g = std::clamp((298 * luma - 100 * u - 208 * v + 128) >> 8, 0, 255);
            const auto b = std::clamp((298 * luma + 516 * u + 128) >> 8, 0, 255);
            pixels[size_t(y) * outWidth + x] = static_cast<jint>(0xff000000u | uint32_t(r) << 16 | uint32_t(g) << 8 | uint32_t(b));
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
