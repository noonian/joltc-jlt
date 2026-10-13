(ns joltc.shim
  "The shim, and whether the native libraries are there.

  Two libraries, both built by the `native` task (native/build.sh) into
  native/lib: libjoltc, which is joltc (the C API over Jolt Physics), and
  libjoltc_shim, this library's own C (native/joltc_shim.c). deps.edn
  declares both under :jolt/native as :optional, so the runtime loads
  them at startup when they have been built and a project without the
  build still loads; `available?` says which. The generated binding is
  `joltc.ffi`.

  The shim exists because a frame loop needs two things the binding alone
  does not give it. Contacts: Jolt Physics reports them from its worker
  threads inside JPH_PhysicsSystem_Update, where a Clojure callback would
  deadlock against the runtime's garbage collector, so the shim buffers
  them in C and the stepping thread drains them afterwards. And every
  active body's transform in one call, instead of one call and two struct
  reads per body. The README's \"The shim\" section has the account and
  the limits."
  (:require [jolt.ffi :as ffi]))

;;; ---------------------------------------------------------------- the shim
;;
;; native/joltc_shim.c, whose opening comment is the contract. Apart from
;; the version, these are for the thread that steps the system, between
;; updates. Body ids and user data come back as unsigned 32-bit values:
;; read the id and int buffers with (ffi/read-array p :uint32 n) and mask
;; each with 0xFFFFFFFF, since a body id keeps a sequence number in its top
;; bits and reads back negative from an int-array otherwise.

(ffi/defcfn joltc_shim_version
  "[] -> n. The version of the shim's contract, which goes up when one of
  its layouts or signatures changes."
  "joltc_shim_version" [] :uint32)

(ffi/defcfn joltc_shim_read_transforms
  "[system ids out max] -> n. Every active rigid body's transform in one
  call: ids (room for max uint32) gets the body ids and out (room for
  7 * max floats) gets px py pz qx qy qz qw for each. Sleeping and static
  bodies are not active."
  "joltc_shim_read_transforms" [:pointer :pointer :pointer :uint32] :uint32)

(ffi/defcfn joltc_shim_read_body_transforms
  "[system ids out count] -> count. The transforms of the count live
  bodies named in ids (uint32 each), active or not, 7 floats each into out."
  "joltc_shim_read_body_transforms" [:pointer :pointer :pointer :uint32] :uint32)

(ffi/defcfn joltc_shim_contacts_create
  "[system capacity] -> contacts, or NULL when out of memory. A buffer for
  capacity contact events, with its listener installed on the system in
  place of any other. joltc keeps one table of listener procs for the
  process and this points it at the shim's, so a contact listener with
  procs of your own cannot exist beside it. Destroy before the system."
  "joltc_shim_contacts_create" [:pointer :uint32] :pointer)

(ffi/defcfn joltc_shim_contacts_destroy
  "[system contacts]. Take the listener off the system and free the buffer."
  "joltc_shim_contacts_destroy" [:pointer :pointer] :void)

(ffi/defcfn joltc_shim_contacts_drain
  "[contacts ints floats max] -> n. Copy the oldest n <= max buffered events
  out and take them off the buffer. Event i fills five of ints (room for
  5 * max int32): type (1 added, 2 removed), body1, body2, and the low 32
  bits of each body's user data; and seven of floats (room for 7 * max):
  the world space normal from body1 to body2, the first manifold point on
  body1, and the penetration depth. A removed event has only its type and
  body ids. Contacts that persist are not reported."
  "joltc_shim_contacts_drain" [:pointer :pointer :pointer :uint32] :uint32)

(ffi/defcfn joltc_shim_contacts_dropped
  "[contacts] -> n. Events dropped because the buffer was full since the
  last call, which resets the count."
  "joltc_shim_contacts_dropped" [:pointer] :uint32)

;;; ------------------------------------------------------------ availability

(def shim-version
  "The shim contract this namespace was written against."
  1)

(defn- loaded-shim-version
  "The loaded shim's version, or nil when it is not loaded. The shim links
  libjoltc, so an answer means both are there."
  []
  (try (long (joltc_shim_version)) (catch Throwable _ nil)))

(defn available?
  "True when libjoltc and the shim are loaded and the shim is the version
  this namespace expects."
  []
  (= shim-version (loaded-shim-version)))

(defn load-libraries!
  "Throw unless the native libraries are loaded, saying what to do about
  it. The runtime loaded them at startup if they were built, so there is
  nothing to load here; the name is from when there was."
  []
  (let [v (loaded-shim-version)]
    (cond
      (nil? v)
      (throw (ex-info (str "joltc: the native libraries are not built. Run the `native` task in the "
                           "joltc-jlt checkout (for a git dependency, `jolt path` shows where it is) "
                           "and start again: they are loaded at startup.")
                      {}))
      (not= v shim-version)
      (throw (ex-info (str "joltc: the built shim is version " v " and this source expects " shim-version
                           ". Run the `native` task in the joltc-jlt checkout again.")
                      {:built v :expected shim-version}))
      :else true)))
