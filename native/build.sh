#!/bin/sh
# Build the Jolt Physics C wrapper (joltc, which fetches Jolt itself) and
# the joltc shim into native/lib. Needs git, cmake and a C/C++ compiler
# (CC picks the one that compiles the shim; cmake finds its own). macOS and
# Linux: the shim uses pthreads and this script is POSIX sh.
# The libraries are loaded at run time by joltc.core, so nothing else in
# the workspace depends on this having run. Takes a few minutes the first
# time; the joltc checkout and build directory are kept for rebuilds.
set -e
cd "$(dirname "$0")"

JOLTC_REPO="${JOLTC_REPO:-https://github.com/amerkoleci/joltc.git}"
JOLTC_SHA="${JOLTC_SHA:-886e088675bae3a086f8318c7803f8ee962c2f2c}"   # 2026-07-13, Jolt 5.6.0

if [ ! -d joltc/.git ]; then
  echo "fetching joltc $JOLTC_SHA"
  git init -q joltc
  (cd joltc && git remote add origin "$JOLTC_REPO" && git fetch -q --depth 1 origin "$JOLTC_SHA" && git checkout -q FETCH_HEAD)
fi

# a checkout that moved keeps a CMake cache configured for its old path;
# start the build directory over rather than let cmake refuse it
if [ -f joltc/build/CMakeCache.txt ] && ! grep -q "CMAKE_HOME_DIRECTORY:INTERNAL=$PWD/joltc\$" joltc/build/CMakeCache.txt; then
  echo "the build directory was configured elsewhere; starting it over"
  rm -rf joltc/build
fi

cmake -S joltc -B joltc/build -DCMAKE_BUILD_TYPE=Release \
  -DJPH_BUILD_SHARED=ON -DJPH_SAMPLES=OFF -DJPH_TESTS=OFF \
  -DUSE_ASSERTS=OFF -DGENERATE_DEBUG_SYMBOLS=OFF > /dev/null
cmake --build joltc/build -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

mkdir -p lib
case "$(uname)" in
  Darwin) ext=dylib; rpath="-Wl,-rpath,@loader_path" ;;
  *)      ext=so;    rpath="-Wl,-rpath,\$ORIGIN" ;;
esac
cp joltc/build/lib/libjoltc.$ext lib/
cc="${CC:-cc}"
"$cc" -O2 -shared -fPIC -I joltc/include -o lib/libjoltc_shim.$ext joltc_shim.c -L lib -ljoltc $rpath -lpthread
echo "built lib/libjoltc.$ext and lib/libjoltc_shim.$ext"
