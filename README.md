# joltc-jlt

[Jolt Physics](https://github.com/jrouwe/JoltPhysics) for the
[jolt](https://jolt-lang.net) runtime, through
[joltc](https://github.com/amerkoleci/joltc), its C API: the generated
binding `joltc.ffi` (every function and struct of `joltc.h`), the loader
`joltc.core`, and a small C shim that does in bulk what a physics adapter
wants per frame rather than per body: every active transform in one
call, and contacts drained from a buffer Jolt's job threads append to,
so no callback ever enters the Clojure runtime.

Nothing third-party is committed here. `jolt native` fetches joltc at
the pinned commit in `native/build.sh` (which fetches Jolt itself
through CMake), builds `libjoltc` and the shim into `native/lib`, and
keeps the checkout for rebuilds and for regenerating the binding. The
libraries are loaded at run time by `joltc.core`, not declared under
`:jolt/native`, so a project that depends on this one loads without the
build and `(joltc.core/available?)` says whether physics will work;
`JOLTC_LIB_DIR` points at a build elsewhere.

```sh
jolt native      # git, cmake and a C++ compiler; a few minutes the first time
jolt test        # the layout test against the C compiler
jolt -M:gen      # regenerate src/joltc/ffi.jolt and gen/targets/ from the checkout
```

Depend on it by `:local/root` from a checkout beside your project, or by
`:git/url` and `:git/sha`; in either case run `jolt native` once in that
checkout (jolt has no prep step for dependencies), and the loader finds
`native/lib` beside the source through the classpath.

The physics protocol over this binding lives in gameth's `physics/`
library; the design notes there (`docs/design.md`, the physics section)
record what the shim exists for and the joltc gotchas.
