cmake_minimum_required(VERSION 3.22.1)
project(souchastnik)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp подключается как git submodule и ПИНУЕТСЯ по коммиту.
# API llama.cpp ломается регулярно; llama_bridge.cpp написан под ревизию,
# указанную в tools/llama-cpp-pin.txt. Обновление сабмодуля -- всегда
# отдельный коммит с проверкой сборки.
#
#   git submodule add https://github.com/ggml-org/llama.cpp third_party/llama.cpp
#
# ВАЖНО: нужна ревизия с поддержкой Qwen3.5 / Gated DeltaNet.
# Старые сборки просто не загрузят архитектуру qwen35.
add_subdirectory(${CMAKE_SOURCE_DIR}/../../../../third_party/llama.cpp
                 ${CMAKE_BINARY_DIR}/llama.cpp
                 EXCLUDE_FROM_ALL)

add_library(souchastnik SHARED llama_bridge.cpp)

target_link_libraries(souchastnik
    llama
    ggml
    android
    log)
