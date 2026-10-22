#!/bin/sh
# Build the Jolt Physics C wrapper (joltc, which fetches the engine itself) and
# the joltc shim into native/lib. Needs git, cmake and clang (CC and CXX
# pick others, for joltc and the shim alike; the other bindings default to
# clang too). macOS and Linux: the shim uses pthreads and this script is
# POSIX sh.
# deps.edn declares the libraries as optional natives, so nothing else in
# the workspace depends on this having run. Takes a few minutes the first
# time; the joltc checkout and build directory are kept for rebuilds.
set -e
cd "$(dirname "$0")"

JOLTC_REPO="${JOLTC_REPO:-https://github.com/amerkoleci/joltc.git}"
JOLTC_SHA="${JOLTC_SHA:-886e088675bae3a086f8318c7803f8ee962c2f2c}"   # 2026-07-13, Jolt Physics 5.6.0

if [ ! -d joltc/.git ]; then
  echo "fetching joltc $JOLTC_SHA"
  git init -q joltc
  (cd joltc && git remote add origin "$JOLTC_REPO" && git fetch -q --depth 1 origin "$JOLTC_SHA" && git checkout -q FETCH_HEAD)
fi

# clang, as for the other bindings: on x86-64 Linux GCC 16's build of Jolt
# stepped a crowd of two hundred characters 1.7 times slower (2026-10-21)
cc="${CC:-clang}"
cxx="${CXX:-clang++}"
cxx_path="$(command -v "$cxx")"

# a checkout that moved keeps a CMake cache configured for its old path, and
# one configured with another compiler cannot be switched; start the build
# directory over rather than let cmake refuse it
if [ -f joltc/build/CMakeCache.txt ]; then
  if ! grep -q "CMAKE_HOME_DIRECTORY:INTERNAL=$PWD/joltc\$" joltc/build/CMakeCache.txt; then
    echo "the build directory was configured elsewhere; starting it over"
    rm -rf joltc/build
  elif ! grep -q "CMAKE_CXX_COMPILER:[A-Z]*=$cxx_path\$" joltc/build/CMakeCache.txt; then
    echo "the build directory was configured with another compiler; starting it over"
    rm -rf joltc/build
  fi
fi

cmake -S joltc -B joltc/build -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_COMPILER="$cc" -DCMAKE_CXX_COMPILER="$cxx_path" \
  -DJPH_BUILD_SHARED=ON -DJPH_SAMPLES=OFF -DJPH_TESTS=OFF \
  -DUSE_ASSERTS=OFF -DGENERATE_DEBUG_SYMBOLS=OFF > /dev/null
cmake --build joltc/build -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

mkdir -p lib
case "$(uname)" in
  Darwin) ext=dylib; rpath="-Wl,-rpath,@loader_path" ;;
  *)      ext=so;    rpath="-Wl,-rpath,\$ORIGIN" ;;
esac
# each library is written beside the old one and renamed over it: a process
# that has the old one loaded (`jolt native` itself, since deps.edn declares
# them) keeps its copy instead of crashing on one rewritten under it
cp joltc/build/lib/libjoltc.$ext lib/libjoltc.$ext.new
mv -f lib/libjoltc.$ext.new lib/libjoltc.$ext
"$cc" -O2 -shared -fPIC -I joltc/include -o lib/libjoltc_shim.$ext.new joltc_shim.c -L lib -ljoltc $rpath -lpthread
mv -f lib/libjoltc_shim.$ext.new lib/libjoltc_shim.$ext
echo "built lib/libjoltc.$ext and lib/libjoltc_shim.$ext"
