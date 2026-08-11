# Converte um binario SPIR-V (.spv) num header C++ com os bytes embutidos
# como array estatico, para nao precisar empacotar assets/carregar arquivo
# em runtime para um shader que nunca muda entre execucoes. Invocado via
# `cmake -P` a partir de um add_custom_command (ver native/CMakeLists.txt)
# — nao chamar diretamente.
if(NOT DEFINED SPV OR NOT DEFINED HEADER OR NOT DEFINED VARNAME)
    message(FATAL_ERROR "Uso: cmake -DSPV=<in.spv> -DHEADER=<out.h> -DVARNAME=<nome> -P EmbedSpirv.cmake")
endif()

file(READ ${SPV} hex_content HEX)
string(REGEX REPLACE "(..)" "0x\\1," hex_bytes ${hex_content})

file(WRITE ${HEADER}
"// Gerado em build-time a partir de ${SPV} pelo EmbedSpirv.cmake — nao editar a mao.
#pragma once
#include <cstdint>
alignas(4) static const unsigned char ${VARNAME}[] = { ${hex_bytes} };
static const uint32_t ${VARNAME}_size = sizeof(${VARNAME});
")
