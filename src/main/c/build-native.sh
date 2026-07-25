#!/usr/bin/env bash
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 <resource-output-directory> [--test-initialization-faults]" >&2
    exit 2
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
    echo "JAVA_HOME is not set" >&2
    exit 2
fi

case "$(uname -s)" in
    Linux)
        operating_system=linux
        include_platform=linux
        library_name=libvthreadtime.so
        compiler_arguments=()
        linker_arguments=(-shared -Wl,-z,relro,-z,now -Wl,--no-undefined)
        ;;
    Darwin)
        operating_system=macos
        include_platform=darwin
        library_name=libvthreadtime.dylib
        compiler_arguments=(-mmacosx-version-min=15.0)
        linker_arguments=(-dynamiclib -Wl,-install_name,@rpath/libvthreadtime.dylib)
        ;;
    *)
        echo "Unsupported operating system: $(uname -s)" >&2
        exit 2
        ;;
esac

case "$(uname -m)" in
    aarch64 | arm64)
        architecture=aarch64
        ;;
    amd64 | x86_64)
        architecture=x86_64
        ;;
    *)
        echo "Unsupported architecture: $(uname -m)" >&2
        exit 2
        ;;
esac

case "${2:-}" in
    "")
        ;;
    --test-initialization-faults)
        compiler_arguments+=(-DVTHREADTIME_TEST_INITIALIZATION_FAULTS)
        ;;
    *)
        echo "Unknown build mode: $2" >&2
        exit 2
        ;;
esac

script_directory=$(cd "$(dirname "$0")" && pwd)
output_directory="$1/io/airlift/vthreadtime/native/${operating_system}-${architecture}"
mkdir -p "$output_directory"
libraries=(-L"${JAVA_HOME}/lib/server" -ljvm)

compiler=${CC:-cc}
if "$compiler" -std=c23 -x c -fsyntax-only /dev/null >/dev/null 2>&1; then
    c_standard=c23
# GCC versions before 14 use the draft spelling for C23 mode.
elif "$compiler" -std=c2x -x c -fsyntax-only /dev/null >/dev/null 2>&1; then
    c_standard=c2x
else
    echo "A C23 compiler is required: $compiler" >&2
    exit 2
fi

"$compiler" \
    -std="$c_standard" \
    -O3 \
    -fPIC \
    -fvisibility=hidden \
    -Wall \
    -Wextra \
    -Werror \
    -Wpedantic \
    -Wstrict-prototypes \
    -pthread \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/${include_platform}" \
    "${compiler_arguments[@]}" \
    "${linker_arguments[@]}" \
    "$script_directory/vthreadtime.c" \
    "${libraries[@]}" \
    -o "$output_directory/$library_name"
