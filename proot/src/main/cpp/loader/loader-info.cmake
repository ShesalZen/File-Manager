if(NOT DEFINED READELF_TOOL OR NOT DEFINED LOADER OR NOT DEFINED OUTPUT)
    message(FATAL_ERROR "READELF_TOOL, LOADER, and OUTPUT are required")
endif()

execute_process(
    COMMAND "${READELF_TOOL}" -s "${LOADER}"
    OUTPUT_VARIABLE SYMBOL_TABLE
    ERROR_VARIABLE READELF_ERROR
    RESULT_VARIABLE READELF_RESULT
)
if(NOT READELF_RESULT EQUAL 0)
    message(FATAL_ERROR "readelf failed: ${READELF_ERROR}")
endif()

set(POKEDATA_ADDRESS "0")
set(START_ADDRESS "0")
string(REPLACE "\r\n" "\n" SYMBOL_TABLE "${SYMBOL_TABLE}")
string(REPLACE ";" "|" SYMBOL_TABLE "${SYMBOL_TABLE}")
string(REPLACE "\n" ";" SYMBOL_LINES "${SYMBOL_TABLE}")

foreach(SYMBOL_LINE IN LISTS SYMBOL_LINES)
    if(SYMBOL_LINE MATCHES "pokedata_workaround[ \t]*$")
        if(SYMBOL_LINE MATCHES ":[ \t]+([0-9a-fA-F]+)")
            set(POKEDATA_ADDRESS "0x${CMAKE_MATCH_1}")
        endif()
    endif()
    if(SYMBOL_LINE MATCHES "[ \t]_start[ \t]*$")
        if(SYMBOL_LINE MATCHES ":[ \t]+([0-9a-fA-F]+)")
            set(START_ADDRESS "0x${CMAKE_MATCH_1}")
        endif()
    endif()
endforeach()

file(WRITE "${OUTPUT}"
"#include <unistd.h>\n\
#include \"arch.h\"\n\
#ifdef HAS_POKEDATA_WORKAROUND\n\
const ssize_t offset_to_pokedata_workaround = (ssize_t)(${POKEDATA_ADDRESS} - ${START_ADDRESS});\n\
#endif\n")
