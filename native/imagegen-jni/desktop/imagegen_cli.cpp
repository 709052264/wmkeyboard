// Desktop check of imagegen.cpp: runs a sticker model bundle's three modules
// on inputs read from raw files and writes each output as raw float32, so
// check.sh can compare them with the Python reference (MNN's own bindings
// and diffusers). Not part of the app.
//
//   imagegen_cli describe <file.mnn>
//   imagegen_cli run <file.mnn> <out.f32> <name>:<i32|f32>:<d0,d1,...>:<file> ...
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "../imagegen.hpp"

static std::vector<int> dims(const std::string& s) {
    std::vector<int> r;
    std::stringstream ss(s);
    std::string p;
    while (std::getline(ss, p, ',')) r.push_back(std::stoi(p));
    return r;
}

int main(int argc, char** argv) {
    if (argc >= 3 && std::string(argv[1]) == "describe") {
        std::cout << wmimagegen::describe(argv[2]) << "\n";
        return 0;
    }
    if (argc < 5 || std::string(argv[1]) != "run") {
        std::cerr << "usage: imagegen_cli describe <file> | run <file> <out> name:type:dims:file ...\n";
        return 2;
    }
    std::vector<std::string> names;
    std::vector<wmimagegen::Tensor> tensors;
    for (int i = 4; i < argc; ++i) {
        std::string a = argv[i];
        size_t p1 = a.find(':'), p2 = a.find(':', p1 + 1), p3 = a.find(':', p2 + 1);
        names.push_back(a.substr(0, p1));
        wmimagegen::Tensor t;
        t.shape = dims(a.substr(p2 + 1, p3 - p2 - 1));
        std::ifstream f(a.substr(p3 + 1), std::ios::binary);
        std::vector<char> bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        if (a.substr(p1 + 1, p2 - p1 - 1) == "i32") {
            t.ints.resize(bytes.size() / 4);
            std::memcpy(t.ints.data(), bytes.data(), bytes.size());
        } else {
            t.floats.resize(bytes.size() / 4);
            std::memcpy(t.floats.data(), bytes.data(), bytes.size());
        }
        tensors.push_back(std::move(t));
    }
    auto m = wmimagegen::Model::load(argv[2], names, {}, 4);
    if (!m) { std::cerr << "load failed\n"; return 1; }
    std::vector<float> out;
    std::vector<int> shape;
    if (!m->run(tensors, out, shape)) { std::cerr << "run failed\n"; return 1; }
    std::ofstream(argv[3], std::ios::binary).write(reinterpret_cast<const char*>(out.data()), out.size() * 4);
    std::cout << "shape";
    for (int d : shape) std::cout << " " << d;
    std::cout << "\n";
    return 0;
}
