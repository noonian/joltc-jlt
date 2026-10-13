# joltc-jlt

[Jolt Physics](https://github.com/jrouwe/JoltPhysics), the physics engine,
for the [jolt](https://jolt-lang.net) runtime, the Clojure implementation,
through [joltc](https://github.com/amerkoleci/joltc), the engine's C API.

Two unrelated projects share a name here, so this document never uses it
bare: the engine is always **Jolt Physics**, and the Clojure
implementation is **the jolt runtime**, or just the runtime. `jolt` in
code type is the runtime's command line, and `jolt.ffi` its foreign
function library.

Three parts:

- `joltc.ffi`, the generated binding: every function, struct, enum and
  constant of `joltc.h` (1261 functions, 71 structs), written by
  [jolt-bindgen](https://github.com/noonian/jolt-bindgen) and not edited by
  hand.
- `joltc.core`, the loader: finds the built libraries and loads them at run
  time.
- The shim, about two hundred lines of C beside the binding, for the two
  things a frame loop needs that the binding alone does not give it on
  this runtime: contact events, and every moving body's transform in one
  call. [The shim](#the-shim) below says why and what it costs.

This is the raw C API under its own names (`JPH_PhysicsSystem_Create`,
`JPH_BodyInterface_AddImpulse`), not a Clojure-shaped physics library.
joltc's header and the Jolt Physics documentation are the reference for
what the functions do.

## Building

Nothing third-party is committed here. `jolt native` fetches joltc at the
commit pinned in `native/build.sh` (joltc fetches Jolt Physics itself
through CMake), builds `libjoltc` and the shim into `native/lib`, and keeps the
checkout for rebuilds and for regenerating the binding.

```sh
jolt native                       # git, cmake and a C++ compiler; a few minutes the first time
jolt test                         # the struct layouts against what the C compiler measured
jolt -A:examples -m falling-box   # a box dropped on a floor
```

`JOLTC_REPO` and `JOLTC_SHA` point the build at another joltc, and `CC` at
another compiler for the shim. A different joltc commit wants the binding
regenerated to match ([below](#regenerating-the-binding)).

### Platforms

The binding is extracted per target, and only one of the three has been
built and run:

| Target | Binding | Native build | Checked |
|---|---|---|---|
| macOS arm64 | extracted on the machine | `jolt native` | built, layout test passes, example runs |
| Linux x86_64 | cross-extracted from macOS | `jolt native` should work as written; not yet run | no |
| Windows x86_64 | cross-extracted from macOS | none: the build is a POSIX shell script and the shim uses pthreads | no |

On Linux, `jolt native && jolt test && jolt -M:gen --check` is the whole
check, and a report of how it goes is welcome. Any other platform (Intel
Macs, Linux arm64) has no extracted target, and `joltc.ffi` says so when
it loads.

## Using it

**Clone it and build it yourself.** There is no other practical way to
consume this library today. It is not just source: it works only after
its native build has run, and the runtime has no prep step that would run
a dependency's build for you. So the way to depend on it is a checkout of
your own, built once, and named by path:

```sh
git clone https://github.com/noonian/joltc-jlt
cd joltc-jlt && jolt native
```

```clojure
;; your project's deps.edn, with the checkout beside it
{:deps {io.github.noonian/joltc-jlt {:local/root "../joltc-jlt"}}}
```

A git dependency (`{:git/sha "<commit sha>"}`) does resolve, and your
project starts, but with `(joltc.core/available?)` false: the runtime
fetched the source into its cache and nothing built it. You can build it
there (`jolt path` in your project prints the checkout, under
`~/.jolt/gitlibs/`, and `jolt native` in the directory above its `src`
does the build), but that is a build inside a cache, once per pinned sha
and again on every machine. A checkout of your own is the honest
arrangement until the runtime can build a dependency's natives.

### How the libraries are found

This project's `deps.edn` declares both libraries under `:jolt/native`,
by path relative to itself:

```clojure
:jolt/native [{:name "joltc" :optional true
               :darwin ["native/lib/libjoltc.dylib"]
               :linux ["native/lib/libjoltc.so"]}
              {:name "joltc_shim" :optional true
               :darwin ["native/lib/libjoltc_shim.dylib"]
               :linux ["native/lib/libjoltc_shim.so"]}]
```

The runtime resolves those paths against the `deps.edn` that wrote them,
so they hold for a project that depends on this one, and it loads the
libraries at startup, before any namespace, so there is no loader to
call. That needs the jolt runtime at 0.8.4 or later, which `deps.edn`
declares as `:jolt/min-version`, so an older runtime refuses to load the
project instead of failing to find the libraries.

- **They are `:optional`.** A project that depends on this one starts
  without the build, and `(joltc.core/available?)` says whether physics
  will work. `(joltc.core/load-libraries!)` throws with what to do when it
  will not.
- **They load at startup**, so after a first `jolt native` a running REPL
  has to be started again.
- **To use a build somewhere else**, declare the same two `:name`s in your
  own project's `deps.edn` with your own paths. A project's declaration
  wins over a dependency's.
- **A standalone binary** made by `jolt build` looks for
  `native/lib/libjoltc.dylib` and its neighbour relative to the directory
  it is run from, so ship that directory beside it.

### An example

[`examples/falling_box.clj`](examples/falling_box.clj) is a whole program:
two collision layers, a system, a job system, a floor, a box, 120 steps,
and everything destroyed in order. Its middle is the per-frame shape:

```clojure
(require '[jolt.ffi :as ffi] '[joltc.core :as jc] '[joltc.ffi :as j])

(jc/load-libraries!)               ; throws unless the native build is there
(j/JPH_Init)                      ; once per process
;; ... create the system, the job system and the bodies ...

(def contacts (jc/joltc_shim_contacts_create system 256))

;; each frame
(j/JPH_PhysicsSystem_Update system (/ 1.0 60.0) 1 jobs)

(let [n (jc/joltc_shim_read_transforms system ids transforms max-bodies)]
  ;; ids: n body ids; transforms: 7 floats each, px py pz qx qy qz qw
  (ffi/read-array ids :uint32 n)
  (ffi/read-array transforms :float (* 7 n)))

(let [n (jc/joltc_shim_contacts_drain contacts c-ints c-floats 256)]
  ;; c-ints: 5 per event; c-floats: 7 per event
  ...)
```

```
tick   0  body 8388609 at (0.00 5.00 0.00)
tick  40  body 8388609 at (0.00 2.68 0.00)
tick  58  added: bodies 8388608 and 8388609, user data 1 and 2, normal (-0.0 1.0 -0.0), depth 0.086
tick  80  body 8388609 at (-0.00 0.48 0.00)
tick  90  removed: bodies 8388608 and 8388609
```

## The shim

`native/joltc_shim.c`, built into `libjoltc_shim` beside `libjoltc` and
declared in `joltc.core`. It is the only code here that is
neither joltc's nor generated from it, so it owes an explanation.

### Why it exists: contacts

Jolt Physics reports contacts through a listener: four C function pointers
that it calls from inside `JPH_PhysicsSystem_Update`, on the worker threads of its
job system, while it holds the locks of the bodies involved. The binding
can express that. `joltc.ffi` has `JPH_ContactListener_Procs`, and
`jolt.ffi/callback` makes a C function pointer out of a Clojure function.
On this runtime it is the wrong thing to do, for a reason that is the
runtime's and not the engine's:

1. A callback that arrives on a thread the runtime never started has to be
   declared `:collect-safe`, which reactivates the thread on the way in.
   Without it, Clojure code runs on a thread the garbage collector does
   not know about, and the process dies of a memory fault no handler can catch.
2. Once in, the callback allocates like any other Clojure code, and an
   allocation that reaches a collection has to stop every thread the
   runtime is running.
3. The thread that called `JPH_PhysicsSystem_Update` is one of them, and
   it is parked inside a foreign call that is not `:blocking`. Such a
   thread stays active for the whole call and never reaches a safe point.
4. So the collection waits for `Update` to return, `Update` waits for the
   job that is running the callback, and the callback waits for the
   collection. Nothing crashes. The process stops, on whichever frame a
   contact callback first happens to trip a collection.

That is the rule `jolt.ffi` documents for `:collect-safe` callbacks,
applied to Jolt Physics; it was not reproduced here. Marking `Update` `:blocking`
would break the cycle, and would leave a foreign crossing, a thread
activation and a possible stop-the-world collection per contact, inside
the solver, on several threads at once. Nor is there a single-threaded
mode to retreat to: joltc's thread pool reads a thread count of zero as
one per core.

The shim takes the listener out of the runtime altogether. Its four
functions are C: the added and removed hooks append a small struct to a
buffer under a mutex, and that is all that happens on the engine's
threads.
After `Update` returns, the stepping thread asks for the buffer with one
call. No callback ever enters the Clojure runtime, at any thread count.

### Why it exists: transforms

After a step, a renderer wants to know where every body that moved now
is. Through the binding that is `JPH_BodyInterface_GetPositionAndRotation`
and two struct reads for each body. `joltc_shim_read_transforms` asks the
engine for its active bodies and writes all of their ids and transforms into two
flat arrays in one call.

Measured with 1000 awake bodies (an M1 Pro, jolt runtime 0.8.20):

| | per frame |
|---|---|
| the shim's one call | 7 µs |
| 1000 `GetPositionAndRotation` calls | 43 µs |
| the shim's call, with both arrays copied out by `ffi/read-array` | 1.0 ms |
| per body, each struct copied out by `ffi/read-array` | 1.3 ms |
| per body, each struct read as a map by `ffi/read` | 2.8 ms |

So the call itself is six times cheaper, and the frame as a whole 1.3 to
2.8 times cheaper, because getting floats out of foreign memory costs far
more than either way of putting them there. This half of the shim is a
convenience with a modest saving: one call, one layout, and it tells you
which bodies are awake. The contacts half is the one a program cannot do
without.

### What it gives

These are for the thread that steps the system, between updates.

| Function | |
|---|---|
| `joltc_shim_read_transforms [system ids out max]` | Every active rigid body: `ids` gets the body ids, `out` 7 floats each (`px py pz qx qy qz qw`). Returns the count. |
| `joltc_shim_read_body_transforms [system ids out count]` | The same 7 floats for the bodies named in `ids`, awake or not. |
| `joltc_shim_contacts_create [system capacity]` | A buffer for `capacity` events, its listener installed on the system. `NULL` when out of memory. |
| `joltc_shim_contacts_drain [contacts ints floats max]` | Copies the oldest events out, in order, and takes them off the buffer. Returns the count; the rest stay for the next call. |
| `joltc_shim_contacts_dropped [contacts]` | Events lost to a full buffer since the last call. |
| `joltc_shim_contacts_destroy [system contacts]` | Takes the listener off and frees the buffer. Before the system is destroyed. |
| `joltc_shim_version []` | The version of this contract, which goes up when a layout or a signature changes. `joltc.core/available?` checks it. |

A drained event is five `int32` and seven `float`:

| `ints` | | `floats` | |
|---|---|---|---|
| 0 | type: 1 added, 2 removed | 0 to 2 | normal, world space, from body 1 to body 2 |
| 1 | body 1's id | 3 to 5 | first manifold point on body 1, world space |
| 2 | body 2's id | 6 | penetration depth |
| 3 | body 1's user data, low 32 bits | | |
| 4 | body 2's user data, low 32 bits | | |

### What it does not do

The shim is a policy, not a general listener. Each of these was a choice
for a frame loop that keeps integer ids in user data, and each is a
reason you might outgrow it:

- **User data is truncated to 32 bits.** Jolt Physics carries 64. An integer id
  fits; a pointer does not.
- **One manifold point.** A manifold has up to four; an added event
  records the first and the manifold's depth.
- **Removed events carry only the two body ids.** The engine names the pair
  and no more, and either body may already be gone, so the user data and the
  floats are zero. Keep your own map from body id to whatever you need.
- **Persisting contacts are not reported**, only the frame a contact
  begins and the frame it ends.
- **A contact also ends when its bodies fall asleep**, and begins again
  when they wake: that is the engine's doing, and it is what the `removed` at
  tick 90 above is. Removed means no longer being solved, not no longer
  touching.
- **Every contact is accepted.** The validate hook never rejects a pair,
  and nothing can change a contact's friction or restitution. Filter with
  object layers, or on the events.
- **A full buffer drops events** and counts them. Drain every update, and
  size the buffer for the worst one.
- **One listener for the process.** joltc keeps a contact listener's
  function table in a single static, and `joltc_shim_contacts_create`
  points it at the shim's. Any number of systems can each have a shim
  buffer, but a listener with procs of your own cannot exist beside one:
  whichever set the table last has every listener in the process.
- **The transform readers do not lock.** They use the engine's no-lock
  body interface, so they are for the stepping thread, between updates, with
  nothing else adding or moving bodies.
- **Positions are floats**, as joltc is built here. A double precision
  build would have them narrowed.
- **Rigid bodies only.** Soft bodies are not in the active list it reads.
- **macOS and Linux**, for the pthread mutex.

If you need what it leaves out (contact filtering, every manifold point,
64-bit user data), the pattern is the thing to keep: write the listener in
C, buffer on the engine's threads, drain from yours. `native/joltc_shim.c` is
short enough to copy and change.

## Notes on joltc

Things the header does not say, found by using it:

- **A body id is not a small integer.** `JPH_BodyID` is 32 bits with the
  body's index in the low bits and a sequence number above (the first body
  of a fresh system is 8388608, `0x800000`). Read an id buffer with
  `(ffi/read-array p :uint32 n)` and mask each element with `0xFFFFFFFF`:
  the array it answers is an `int-array`, and an id with the top bit set
  reads back negative otherwise.
- **`ffi/write` of a struct wants every field**, including the ones joltc
  names `_padding`.
- **The system owns its three layer filters.** `JPH_PhysicsSystem_Destroy`
  deletes the broad-phase layer interface, the object layer pair filter
  and the object-vs-broad-phase filter it was created with. Destroying
  them yourself is a double free.
- **A body does not own its shape** as far as the caller's reference
  goes: `JPH_Shape_Destroy` after `JPH_BodyInterface_RemoveAndDestroyBody`,
  and `JPH_BodyCreationSettings_Destroy` as soon as the body is made.
- **A job system always has worker threads.**
  `JPH_JobSystemThreadPool_Create` passes a positive `numThreads` through
  and turns zero or less into the engine's default, one per core less one. Zero
  does not mean "run the jobs on my thread".
- **`JPH_Init` once per process**, before anything else, and
  `JPH_Shutdown` after the last system is gone.
- **Nothing in the binding is `:blocking`.** A call that takes long
  (`JPH_PhysicsSystem_Update` on a large world) holds every other
  runtime thread off the garbage collector until it returns.

## Regenerating the binding

`src/joltc/ffi.jolt`, `test/joltc/layout_test.clj` and everything under
`gen/` except the manifest are generated. `gen/manifest.edn` says what
from, and `gen/report.edn` what came out.

```sh
jolt -M:gen           # regenerate from the checkout `jolt native` made; needs clang
jolt -M:gen --check   # extract this machine afresh and compare with what is committed
```

To move to another joltc: change `JOLTC_SHA` in `native/build.sh` and
`:source-label` in the manifest, remove `native/joltc`, then
`jolt native`, `jolt -M:gen` and `jolt test`.
