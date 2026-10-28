add_library(csv INTERFACE)

target_sources(csv INTERFACE
  ${CMAKE_CURRENT_LIST_DIR}/csv.h
)

target_include_directories(csv
    INTERFACE
    .
)
