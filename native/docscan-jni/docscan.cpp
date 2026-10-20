// Page detection, perspective flattening and clean-up. See docscan.hpp.
//
// Detection is the classic contour approach, run several ways because no one
// edge map finds every page: a white sheet on a white desk shows up only to a
// gentle Canny, a page on wood grain only to a region threshold, a sheet on a
// coloured cloth only in the colour channels. Each map's contours become
// convex hulls, each hull is simplified to four corners where it can be, and
// a quad is kept only when its corners are plausible and the edge map
// actually runs along its sides. The biggest well-supported quad wins.

#include "docscan.hpp"

#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cmath>
#include <vector>

namespace wmdocscan {
namespace {

// Long side detection runs at. Corners come back scaled up, and a few pixels
// of error at full size is what the corner handles are for.
constexpr int kWorkSide = 640;
// Smallest page worth finding, as a share of the frame.
constexpr double kMinArea = 0.10;
// No corner sharper than ~53 degrees or blunter than ~127: |cos| above this
// is a trapezoid no camera angle makes of a rectangle.
constexpr double kMaxCornerCos = 0.6;
// Share of a quad's sides an edge map must cover for the quad to count.
constexpr double kMinSupport = 0.55;

using Points = std::vector<cv::Point>;

// Clockwise from top left, in image coordinates (y grows downwards).
void order(cv::Point2f p[4]) {
    const cv::Point2f c = (p[0] + p[1] + p[2] + p[3]) * 0.25f;
    std::sort(p, p + 4, [&c](const cv::Point2f& a, const cv::Point2f& b) {
        return std::atan2(a.y - c.y, a.x - c.x) < std::atan2(b.y - c.y, b.x - c.x);
    });
    int first = 0;
    for (int i = 1; i < 4; ++i) {
        if (p[i].x + p[i].y < p[first].x + p[first].y) first = i;
    }
    std::rotate(p, p + first, p + 4);
}

double cornerCos(const cv::Point& a, const cv::Point& b, const cv::Point& c) {
    const double x1 = a.x - b.x, y1 = a.y - b.y, x2 = c.x - b.x, y2 = c.y - b.y;
    const double n = std::sqrt((x1 * x1 + y1 * y1) * (x2 * x2 + y2 * y2));
    return n > 0 ? (x1 * x2 + y1 * y2) / n : 1.0;
}

// The share of the quad's outline that lies on the edge map, or 0 when fewer
// than two of its sides follow a real edge. A side along the frame's border
// counts as covered (a page that fills the picture has no edge there to
// find), but only beside real edges: a quad made of the border alone is the
// frame, not a page.
double support(const Points& quad, const cv::Mat& edges) {
    constexpr int kSamples = 48;
    const int w = edges.cols, h = edges.rows;
    int hit = 0, realSides = 0;
    for (int i = 0; i < 4; ++i) {
        const cv::Point a = quad[i], b = quad[(i + 1) % 4];
        int real = 0;
        for (int s = 0; s < kSamples; ++s) {
            const double t = (s + 0.5) / kSamples;
            const int x = static_cast<int>(std::lround(a.x + (b.x - a.x) * t));
            const int y = static_cast<int>(std::lround(a.y + (b.y - a.y) * t));
            if (x <= 2 || y <= 2 || x >= w - 3 || y >= h - 3) {
                ++hit;
            } else if (edges.at<uchar>(y, x)) {
                ++hit;
                ++real;
            }
        }
        if (real * 2 >= kSamples) ++realSides;
    }
    if (realSides < 2) return 0.0;
    return static_cast<double>(hit) / (4 * kSamples);
}

struct Best {
    Points quad;
    double score = 0;
    double support = 0;
};

// Every quad the map's contours make, kept in best when it beats what is there.
void collect(const cv::Mat& map, const cv::Mat& edges, Best& best) {
    const double frame = static_cast<double>(map.cols) * map.rows;
    std::vector<Points> contours;
    cv::findContours(map, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
    Points hull, approx;
    for (const Points& contour : contours) {
        if (contour.size() < 4) continue;
        cv::convexHull(contour, hull);
        const double hullArea = cv::contourArea(hull);
        if (hullArea < kMinArea * frame) continue;
        const double perimeter = cv::arcLength(hull, true);
        approx.clear();
        for (double eps = 0.02; eps <= 0.1; eps += 0.01) {
            cv::approxPolyDP(hull, approx, eps * perimeter, true);
            if (approx.size() <= 4) break;
        }
        if (approx.size() != 4 || !cv::isContourConvex(approx)) continue;
        double worst = 0;
        for (int i = 0; i < 4; ++i) {
            worst = std::max(worst, std::fabs(cornerCos(approx[(i + 3) % 4], approx[i], approx[(i + 1) % 4])));
        }
        if (worst > kMaxCornerCos) continue;
        const double area = cv::contourArea(approx) / frame;
        const double covered = support(approx, edges);
        if (covered < kMinSupport) continue;
        // Area first, but a quad whose sides the edges barely follow loses to
        // a slightly smaller one they trace all the way round.
        const double score = area * covered * covered;
        if (score > best.score) {
            best.quad = approx;
            best.score = score;
            best.support = covered;
        }
    }
}

uchar median(const cv::Mat& gray) {
    int hist[256] = {0};
    for (int y = 0; y < gray.rows; ++y) {
        const uchar* row = gray.ptr<uchar>(y);
        for (int x = 0; x < gray.cols; ++x) ++hist[row[x]];
    }
    const int half = gray.rows * gray.cols / 2;
    int seen = 0;
    for (int v = 0; v < 256; ++v) {
        seen += hist[v];
        if (seen >= half) return static_cast<uchar>(v);
    }
    return 128;
}

cv::Mat autoCanny(const cv::Mat& blurred) {
    const double m = median(blurred);
    cv::Mat out;
    cv::Canny(blurred, out, std::max(10.0, 0.66 * m), std::max(30.0, 1.33 * m));
    return out;
}

int odd(int v) { return v | 1; }

// The light the paper is lit by: what blank paper would read at each pixel,
// for each of red, green, blue and grey (so paper with a tint comes out
// white too). Measured on a coarse grid at a reduced size, from the brightest
// unsaturated pixels of each cell (text is dark, a coloured box or stamp is
// saturated, so neither is mistaken for paper), with cells that hold too
// little paper filled in from their neighbours, then smoothed and scaled
// back up. A dilation-based estimate cannot tell a large coloured box from a
// shadow and washes it out to white; this one keeps it.
std::vector<cv::Mat> paperLight(const cv::Mat& rgba) {
    constexpr int kSide = 512;
    constexpr int kCell = 8;
    constexpr int kPlanes = 4;  // red, green, blue, grey
    const double f = std::min(1.0, static_cast<double>(kSide) / std::max(rgba.cols, rgba.rows));
    cv::Mat small;
    cv::resize(rgba, small, cv::Size(), f, f, cv::INTER_AREA);
    const int gw = (small.cols + kCell - 1) / kCell, gh = (small.rows + kCell - 1) / kCell;
    std::vector<cv::Mat> grids(kPlanes);
    for (auto& g : grids) g = cv::Mat(gh, gw, CV_32F, cv::Scalar(-1));
    std::vector<uchar> values[kPlanes];
    for (int gy = 0; gy < gh; ++gy) {
        for (int gx = 0; gx < gw; ++gx) {
            for (auto& v : values) v.clear();
            const int y1 = std::min(small.rows, (gy + 1) * kCell), x1 = std::min(small.cols, (gx + 1) * kCell);
            int cells = 0;
            for (int y = gy * kCell; y < y1; ++y) {
                const cv::Vec4b* row = small.ptr<cv::Vec4b>(y);
                for (int x = gx * kCell; x < x1; ++x, ++cells) {
                    const cv::Vec4b& p = row[x];
                    const int hi = std::max({p[0], p[1], p[2]}), lo = std::min({p[0], p[1], p[2]});
                    // Saturation below ~0.25: grey, white or black, not colour.
                    if (hi == 0 || (hi - lo) * 4 >= hi) continue;
                    for (int c = 0; c < 3; ++c) values[c].push_back(p[c]);
                    values[3].push_back(static_cast<uchar>((77 * p[0] + 150 * p[1] + 29 * p[2]) >> 8));
                }
            }
            if (values[0].size() * 4 < static_cast<size_t>(cells)) continue;
            for (int c = 0; c < kPlanes; ++c) {
                auto nth = values[c].begin() + static_cast<long>(values[c].size() * 9 / 10);
                std::nth_element(values[c].begin(), nth, values[c].end());
                grids[c].at<float>(gy, gx) = *nth;
            }
        }
    }
    std::vector<cv::Mat> light(kPlanes);
    for (int c = 0; c < kPlanes; ++c) {
        cv::Mat& grid = grids[c];
        // Fill the cells without paper from their filled neighbours, ring by ring.
        for (bool missing = true; missing;) {
            missing = false;
            bool filled = false;
            cv::Mat next = grid.clone();
            for (int gy = 0; gy < gh; ++gy) {
                for (int gx = 0; gx < gw; ++gx) {
                    if (grid.at<float>(gy, gx) >= 0) continue;
                    float sum = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int y = gy + dy, x = gx + dx;
                            if (y < 0 || x < 0 || y >= gh || x >= gw) continue;
                            const float v = grid.at<float>(y, x);
                            if (v >= 0) {
                                sum += v;
                                ++n;
                            }
                        }
                    }
                    if (n) {
                        next.at<float>(gy, gx) = sum / n;
                        filled = true;
                    } else {
                        missing = true;
                    }
                }
            }
            grid = next;
            // Not one cell had paper: no light to correct for.
            if (missing && !filled) {
                grid.setTo(255.f, grid < 0);
                missing = false;
            }
        }
        cv::Mat smooth;
        grid.convertTo(smooth, CV_8U);
        cv::medianBlur(smooth, smooth, 3);
        cv::GaussianBlur(smooth, smooth, cv::Size(3, 3), 0);
        cv::resize(smooth, light[c], rgba.size(), 0, 0, cv::INTER_CUBIC);
        cv::max(light[c], 1, light[c]);
    }
    return light;
}

// Paper to white whatever the light was, then the ink a little darker.
void flatten(cv::Mat& channel, const cv::Mat& light) {
    cv::divide(channel, light, channel, 255.0);
    channel.convertTo(channel, -1, 1.3, -0.3 * 255.0);
}

}  // namespace

Quad detectPage(const cv::Mat& rgba) {
    Quad out;
    const float w = static_cast<float>(rgba.cols), h = static_cast<float>(rgba.rows);
    out.corners[0] = {0.f, 0.f};
    out.corners[1] = {w - 1, 0.f};
    out.corners[2] = {w - 1, h - 1};
    out.corners[3] = {0.f, h - 1};
    if (rgba.empty()) return out;

    const double f = std::min(1.0, static_cast<double>(kWorkSide) / std::max(rgba.cols, rgba.rows));
    cv::Mat small;
    cv::resize(rgba, small, cv::Size(), f, f, cv::INTER_AREA);
    cv::Mat gray;
    cv::cvtColor(small, gray, cv::COLOR_RGBA2GRAY);
    // Text and ruled lines closed away, so the page reads as one bright
    // surface and its outline is the strongest edge on it.
    const cv::Mat textKernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(7, 7));
    cv::Mat plain;
    cv::morphologyEx(gray, plain, cv::MORPH_CLOSE, textKernel);
    const cv::Mat grow = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));

    cv::Mat blurred, soft;
    cv::GaussianBlur(plain, blurred, cv::Size(5, 5), 0);
    cv::GaussianBlur(plain, soft, cv::Size(9, 9), 0);

    std::vector<cv::Mat> maps;
    // Canny at thresholds from the picture's own median.
    cv::Mat sharp = autoCanny(blurred);
    // A gentle one for a pale page on a pale desk.
    cv::Mat gentle;
    cv::Canny(soft, gentle, 12, 36);
    // The colour channels', for a page on a background of its brightness.
    cv::Mat colour = cv::Mat::zeros(gray.size(), CV_8U);
    {
        std::vector<cv::Mat> channels;
        cv::split(small, channels);
        for (int c = 0; c < 3; ++c) {
            cv::Mat ch;
            cv::morphologyEx(channels[c], ch, cv::MORPH_CLOSE, textKernel);
            cv::GaussianBlur(ch, ch, cv::Size(5, 5), 0);
            cv::bitwise_or(colour, autoCanny(ch), colour);
        }
    }
    // The bright region itself, for a page on a textured background whose
    // grain swamps every edge map.
    cv::Mat region;
    cv::threshold(blurred, region, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
    cv::morphologyEx(region, region, cv::MORPH_OPEN, cv::getStructuringElement(cv::MORPH_RECT, cv::Size(5, 5)));

    cv::Mat edges = sharp | gentle | colour;
    cv::dilate(edges, edges, cv::getStructuringElement(cv::MORPH_RECT, cv::Size(5, 5)));
    cv::Mat regionEdges;
    cv::morphologyEx(region, regionEdges, cv::MORPH_GRADIENT, grow);
    cv::dilate(regionEdges, regionEdges, grow);
    cv::Mat allEdges = edges | regionEdges;

    for (cv::Mat* map : {&sharp, &gentle, &colour}) {
        cv::dilate(*map, *map, grow);
        maps.push_back(*map);
    }
    maps.push_back(region);

    Best best;
    for (const cv::Mat& map : maps) collect(map, allEdges, best);
    if (best.quad.size() != 4) return out;

    const float up = static_cast<float>(1.0 / f);
    for (int i = 0; i < 4; ++i) {
        out.corners[i] = {std::clamp(best.quad[i].x * up, 0.f, w - 1), std::clamp(best.quad[i].y * up, 0.f, h - 1)};
    }
    order(out.corners);
    out.score = static_cast<float>(best.support);
    out.found = true;
    return out;
}

cv::Size pageSize(const cv::Point2f c[4], int maxSide) {
    auto dist = [](const cv::Point2f& a, const cv::Point2f& b) {
        return std::hypot(a.x - b.x, a.y - b.y);
    };
    double w = std::max(dist(c[0], c[1]), dist(c[3], c[2]));
    double h = std::max(dist(c[0], c[3]), dist(c[1], c[2]));
    const double over = std::max(w, h) / maxSide;
    if (over > 1) {
        w /= over;
        h /= over;
    }
    return {std::max(1, static_cast<int>(std::lround(w))), std::max(1, static_cast<int>(std::lround(h)))};
}

void warpPage(const cv::Mat& src, const cv::Point2f corners[4], cv::Mat& dst) {
    const float w = static_cast<float>(dst.cols - 1), h = static_cast<float>(dst.rows - 1);
    const cv::Point2f to[4] = {{0.f, 0.f}, {w, 0.f}, {w, h}, {0.f, h}};
    const cv::Mat m = cv::getPerspectiveTransform(corners, to);
    // dst already has its size and type, so OpenCV writes into its pixels
    // (the caller's bitmap) rather than allocating new ones.
    cv::warpPerspective(src, dst, m, dst.size(), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
}

void enhance(cv::Mat& rgba, int mode) {
    if (mode != kColour && mode != kGrey && mode != kBlackWhite) return;
    const std::vector<cv::Mat> light = paperLight(rgba);
    if (mode == kColour) {
        std::vector<cv::Mat> channels;
        cv::split(rgba, channels);
        for (int c = 0; c < 3; ++c) flatten(channels[c], light[c]);
        cv::merge(channels, rgba);
        return;
    }
    cv::Mat gray;
    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
    flatten(gray, light[3]);
    if (mode == kBlackWhite) {
        // Black where the page is darker than its surroundings and dark in
        // its own right (the local test finds strokes, the level keeps the
        // paper's own grain and noise from speckling it), and wherever it is
        // very dark: the local test alone hollows out a filled box.
        cv::medianBlur(gray, gray, 3);
        const int block = odd(std::max(15, std::min(gray.cols, gray.rows) / 30));
        cv::Mat local, light, dark;
        cv::adaptiveThreshold(gray, local, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C, cv::THRESH_BINARY, block, 15);
        cv::threshold(gray, light, 200, 255, cv::THRESH_BINARY);
        cv::threshold(gray, dark, 96, 255, cv::THRESH_BINARY);
        gray = (local | light) & dark;
    }
    cv::cvtColor(gray, rgba, cv::COLOR_GRAY2RGBA);
}

}  // namespace wmdocscan
