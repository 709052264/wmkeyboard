// The document scanner's image work, on OpenCV's core and imgproc only: find
// the page in a photo, flatten it, clean it up. Shared by the Android JNI
// bridge (wm_docscan_jni.cpp) and the desktop check (desktop/docscan_cli.cpp),
// so what the Mac verifies is the code the phone runs.
//
// Every image here is 8-bit RGBA, the byte order of an Android ARGB_8888
// bitmap's pixels.
#pragma once

#include <opencv2/core.hpp>

namespace wmdocscan {

// What detectPage found: the page's corners in the image's own pixels,
// clockwise from top left (top left, top right, bottom right, bottom left),
// and how sure it is, 0..1. found is false when nothing page-shaped showed
// up; the corners are then the whole image.
struct Quad {
    cv::Point2f corners[4];
    float score = 0.f;
    bool found = false;
};

Quad detectPage(const cv::Mat& rgba);

// The four corners' page drawn flat into dst, which the caller sized.
// Corners in src pixels, in detectPage's order.
void warpPage(const cv::Mat& src, const cv::Point2f corners[4], cv::Mat& dst);

// The size a page with these corners comes out at: the longer of each pair
// of opposite sides, never more than maxSide on either axis.
cv::Size pageSize(const cv::Point2f corners[4], int maxSide);

enum Enhance {
    kOriginal = 0,
    // Paper to white and shadows out, ink in its own colours.
    kColour = 1,
    // The same, in grey.
    kGrey = 2,
    // Black ink on white paper, nothing in between.
    kBlackWhite = 3,
};

// In place. kOriginal leaves the image alone.
void enhance(cv::Mat& rgba, int mode);

}  // namespace wmdocscan
