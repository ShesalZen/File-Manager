cmake_minimum_required(VERSION 3.22.1)

# Standalone wrapper used by native CI presets. The Android Gradle plugin
# continues to configure src/main/cpp/CMakeLists.txt directly.
add_subdirectory(src/main/cpp)
