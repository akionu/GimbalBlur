add_library(DDM4006 INTERFACE)

target_sources(DDM4006 INTERFACE
  ${CMAKE_CURRENT_LIST_DIR}/DDM4006.cpp
)

target_include_directories(DDM4006
    INTERFACE
    .
)
