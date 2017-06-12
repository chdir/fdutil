# Sets the minimum version of CMake required to build the native
# library. You should either keep the default value or only pass a
# value of 3.4.0 or lower.

cmake_minimum_required(VERSION 3.4.1)

set(LINKER_FLAGS "-fsanitize=address")
set(LD_FLAGS ${LINKER_FLAGS})
set(LDFLAGS ${LINKER_FLAGS})
set(CMAKE_SHARED_LINKER_FLAGS ${LINKER_FLAGS})

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds it for you.
# Gradle automatically packages shared libraries with your APK.

set(EXECUTABLE_OUTPUT_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../libs/${ANDROID_ABI}")

add_executable(fdhelper-${FDHELPER_NATIVE_VER} fdhelper.c cleanhelper.c)

add_library(libsepol STATIC util/libsepol/src/assertion.c
                            	util/libsepol/src/avrule_block.c
                            	util/libsepol/src/avtab.c
                            	util/libsepol/src/boolean_record.c
                            	util/libsepol/src/booleans.c
                            	util/libsepol/src/conditional.c
                            	util/libsepol/src/constraint.c
                            	util/libsepol/src/context.c
                            	util/libsepol/src/context_record.c
                            	util/libsepol/src/debug.c
                            	util/libsepol/src/ebitmap.c
                            	util/libsepol/src/expand.c
                            	util/libsepol/src/handle.c
                            	util/libsepol/src/hashtab.c
                            	util/libsepol/src/hierarchy.c
                            	util/libsepol/src/iface_record.c
                            	util/libsepol/src/interfaces.c
                            	util/libsepol/src/link.c
                            	util/libsepol/src/mls.c
                            	util/libsepol/src/module.c
                            	util/libsepol/src/module_to_cil.c
                            	util/libsepol/src/node_record.c
                            	util/libsepol/src/nodes.c
                            	util/libsepol/src/polcaps.c
                            	util/libsepol/src/policydb.c
                            	util/libsepol/src/policydb_convert.c
                            	util/libsepol/src/policydb_public.c
                            	util/libsepol/src/port_record.c
                            	util/libsepol/src/ports.c
                            	util/libsepol/src/roles.c
                            	util/libsepol/src/services.c
                            	util/libsepol/src/sidtab.c
                            	util/libsepol/src/symtab.c
                            	util/libsepol/src/user_record.c
                            	util/libsepol/src/users.c
                            	util/libsepol/src/util.c
                            	util/libsepol/src/write.c)

include_directories(util/libsepol/include)
include_directories(util/libsepol/src)

target_compile_options(libsepol PRIVATE "-D_GNU_SOURCE -Wall -Os -W -Wundef -Wshadow -Wmissing-noreturn -Wmissing-format-attribute -x c -std=gnu89")

# Searches for a specified prebuilt library and stores the path as a
# variable. Because system libraries are included in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
              log-lib

              # Specifies the name of the NDK library that
              # you want CMake to locate.
              log )

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in the
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
                       fdhelper-${FDHELPER_NATIVE_VER}

                       # Links the target library to the log library
                       # included in the NDK.
                       ${log-lib}

                       libsepol)
