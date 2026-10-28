cmake_minimum_required(VERSION 3.13)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

project(ddm4006_ctrl C CXX)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

add_subdirectory(lib)

add_executable(home
  home.cpp
  origin_utils.cpp
)
target_link_libraries(home
  DDM4006
)

add_executable(ddm4006_ctrl_trig_align
  ddm4006_ctrl_trig_align.cpp
  origin_utils.cpp
)
target_link_libraries(ddm4006_ctrl_trig_align
  DDM4006
  csv
)
