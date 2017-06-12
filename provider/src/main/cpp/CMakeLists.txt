# Sets the minimum version of CMake required to build the native
# library. You should either keep the default value or only pass a
# value of 3.4.0 or lower.

cmake_minimum_required(VERSION 3.4.1)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds it for you.
# Gradle automatically packages shared libraries with your APK.

add_library( # Sets the name of the library.
             magic-${MAGIC_NATIVE_VER}

             # Sets the library as a shared library.
             SHARED

             # Provides a relative path to your source file(s).
             # Associated headers in the same location as their source
             # file are automatically included.
             magic/src/magic.c
             magic/src/asprintf.c
             magic/src/vasprintf.c
             magic/src/dprintf.c
             magic/src/fmtcheck.c
             magic/src/getline.c
             magic/src/funcs.c
             magic/src/apprentice.c
             magic/src/fsmagic.c
             magic/src/ascmagic.c
             magic/src/apptype.c
             magic/src/softmagic.c
             magic/src/encoding.c
             magic/src/compress.c
             magic/src/is_tar.c
             magic/src/print.c
             magic/src/readelf.c
             magic/src/readcdf.c
             magic/src/der.c
             magic/src/cdf.c
             magic/src/cdf_time.c
             javamagic.c )

include_directories(prebuilt)

target_compile_definitions(magic-${MAGIC_NATIVE_VER} PRIVATE VERSION=5.29 HAVE_CONFIG_H ZLIBSUPPORT)
target_compile_options(magic-${MAGIC_NATIVE_VER} PRIVATE -Wall -Os -flto -fvisibility=hidden)

# Searches for a specified prebuilt library and stores the path as a
# variable. Because system libraries are included in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library(zlib-lib z)
find_library(log-lib log)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in the
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
                       magic-${MAGIC_NATIVE_VER}

                       # Links the target library to the log library
                       # included in the NDK.
                       ${zlib-lib}
                       ${log-lib})
