// Runs xlit.cpp on the build machine against a desktop ONNX Runtime, to check
// the search against the reference (scripts/export_indicxlit.py --check in
// the data repository) without a phone. Not part of the Android build.
//
//   host_test <encoder.onnx> <decoder.onnx> <allowed ids file> <beam> < ids
//
// stdin: one word per line as space-separated source ids. stdout: per word,
// tab-separated readings as space-separated target ids, then the score.
#include <chrono>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "xlit.h"

int main(int argc, char** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: host_test encoder decoder allowed beam < ids\n");
        return 2;
    }
    std::string error;
    auto model = wmxlit::Model::open(argv[1], argv[2], 1, &error);
    if (!model) {
        std::fprintf(stderr, "open: %s\n", error.c_str());
        return 1;
    }
    std::vector<uint8_t> allowed;
    std::ifstream flags(argv[3]);
    int f;
    while (flags >> f) allowed.push_back(static_cast<uint8_t>(f));
    const int beam = std::atoi(argv[4]);
    std::string line;
    double total = 0;
    int words = 0;
    while (std::getline(std::cin, line)) {
        std::istringstream in(line);
        std::vector<int64_t> src;
        int64_t id;
        while (in >> id) src.push_back(id);
        auto start = std::chrono::steady_clock::now();
        auto found = model->transliterate(src, allowed, beam, static_cast<int>(2 * src.size() + 4));
        total += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        words++;
        for (size_t i = 0; i < found.size(); i++) {
            if (i) std::cout << '\t';
            for (size_t k = 0; k < found[i].tokens.size(); k++) std::cout << (k ? " " : "") << found[i].tokens[k];
            std::cout << ':' << found[i].score;
        }
        std::cout << '\n';
    }
    std::fprintf(stderr, "%d words, %.2f ms each\n", words, words ? total / words : 0.0);
    return 0;
}
