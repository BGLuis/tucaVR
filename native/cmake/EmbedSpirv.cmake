# Converts a SPIR-V binary (.spv) into a C++ header with the bytes embedded
# as a static array, so there is no need to package assets/load a file
# at runtime for a shader that never changes between runs. Invoked through
# `cmake -P` from an add_custom_command (see native/CMakeLists.txt)
# — do not call it directly.
if(NOT DEFINED SPV OR NOT DEFINED HEADER OR NOT DEFINED VARNAME)
    message(FATAL_ERROR "Usage: cmake -DSPV=<in.spv> -DHEADER=<out.h> -DVARNAME=<name> -P EmbedSpirv.cmake")
endif()

file(READ ${SPV} hex_content HEX)
string(REGEX REPLACE "(..)" "0x\\1," hex_bytes ${hex_content})

file(WRITE ${HEADER}
"// Generated at build time from ${SPV} by EmbedSpirv.cmake — do not edit by hand.
#pragma once
#include <cstdint>
alignas(4) static const unsigned char ${VARNAME}[] = { ${hex_bytes} };
static const uint32_t ${VARNAME}_size = sizeof(${VARNAME});
")
