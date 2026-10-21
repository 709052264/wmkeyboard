# The llama.cpp release libwmllama is built from, sourced by build.sh and
# host-test.sh. Bump all four together, then rebuild and commit the libraries.
LLAMA_TAG=b11753
LLAMA_COMMIT=d8d92973c8c21bb4dd5d592f0fec26fe2cd1e654
LLAMA_SHORT=d8d9297
LLAMA_BUILD=11753
# What the OpenCL variant (libwmllama_opencl.so) compiles and links against:
# Khronos's headers, and its ICD loader built only to have a libOpenCL.so to
# link to. Neither ships: on the phone the library finds the vendor's own
# libOpenCL.so by that name.
OPENCL_HEADERS_TAG=v2025.07.22
OPENCL_HEADERS_COMMIT=8a97ebc88daa3495d6f57ec10bb515224400186f
OPENCL_LOADER_TAG=v2025.07.22
OPENCL_LOADER_COMMIT=ad770a1b64c6b8d5f2ed4e153f22e4f45939f27f
