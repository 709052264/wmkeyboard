// Desktop check of the scanner's image work: the same docscan.cpp the phone
// runs, on a photo read from a binary PPM. Prints the corners it found and
// writes, beside out_prefix, the photo with the page outlined, the flattened
// page, and the page through each clean-up mode. check.sh drives it.
//
//   docscan_cli photo.ppm out/photo
#include <opencv2/imgproc.hpp>

#include <chrono>
#include <cstdio>
#include <fstream>
#include <string>

#include "../docscan.hpp"

namespace {

bool readPpm(const std::string& path, cv::Mat& rgba) {
    std::ifstream in(path, std::ios::binary);
    std::string magic;
    int w = 0, h = 0, max = 0;
    in >> magic >> w >> h >> max;
    in.get();
    if (!in || magic != "P6" || max != 255 || w <= 0 || h <= 0) return false;
    cv::Mat rgb(h, w, CV_8UC3);
    in.read(reinterpret_cast<char*>(rgb.data), static_cast<std::streamsize>(rgb.total() * 3));
    if (!in) return false;
    cv::cvtColor(rgb, rgba, cv::COLOR_RGB2RGBA);
    return true;
}

void writePpm(const std::string& path, const cv::Mat& rgba) {
    cv::Mat rgb;
    cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << rgb.cols << " " << rgb.rows << "\n255\n";
    out.write(reinterpret_cast<const char*>(rgb.data), static_cast<std::streamsize>(rgb.total() * 3));
}

double ms(std::chrono::steady_clock::time_point since) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - since).count();
}

}  // namespace

int main(int argc, char** argv) {
    if (argc != 3) {
        std::fprintf(stderr, "usage: %s photo.ppm out_prefix\n", argv[0]);
        return 2;
    }
    cv::Mat photo;
    if (!readPpm(argv[1], photo)) {
        std::fprintf(stderr, "cannot read %s\n", argv[1]);
        return 1;
    }
    const std::string prefix = argv[2];

    auto t = std::chrono::steady_clock::now();
    const wmdocscan::Quad quad = wmdocscan::detectPage(photo);
    const double detectMs = ms(t);
    std::printf("found=%d score=%.2f detect=%.1fms corners", quad.found ? 1 : 0, quad.score, detectMs);
    for (const auto& c : quad.corners) std::printf(" %.0f,%.0f", c.x, c.y);
    std::printf("\n");

    cv::Mat outlined = photo.clone();
    std::vector<cv::Point> poly;
    for (const auto& c : quad.corners) poly.emplace_back(cvRound(c.x), cvRound(c.y));
    const int thick = std::max(3, std::max(photo.cols, photo.rows) / 300);
    cv::polylines(outlined, poly, true, quad.found ? cv::Scalar(0, 200, 0, 255) : cv::Scalar(255, 0, 0, 255), thick);
    writePpm(prefix + "_outline.ppm", outlined);

    const cv::Size size = wmdocscan::pageSize(quad.corners, 3000);
    cv::Mat page(size, CV_8UC4);
    t = std::chrono::steady_clock::now();
    wmdocscan::warpPage(photo, quad.corners, page);
    std::printf("page=%dx%d warp=%.1fms", page.cols, page.rows, ms(t));
    writePpm(prefix + "_page.ppm", page);

    const char* names[] = {"", "colour", "grey", "bw"};
    for (int mode = 1; mode <= 3; ++mode) {
        cv::Mat clean = page.clone();
        t = std::chrono::steady_clock::now();
        wmdocscan::enhance(clean, mode);
        std::printf(" %s=%.1fms", names[mode], ms(t));
        writePpm(prefix + "_" + names[mode] + ".ppm", clean);
    }
    std::printf("\n");
    return 0;
}
