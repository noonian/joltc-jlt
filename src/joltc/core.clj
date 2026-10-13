(ns joltc.core
  "Loading joltc (the C API over Jolt Physics) and the joltc shim, both
  built by `jolt native` (native/build.sh) into native/lib and loaded here
  at run time, not declared under :jolt/native, so a project without the
  build still loads. The generated binding is `joltc.ffi`.

  The shim is this library's own C (native/joltc_shim.c), declared at the
  end of this namespace. It exists because a per-frame loop needs two
  things the binding alone does not give it on this runtime: contacts,
  which Jolt reports from its job threads inside JPH_PhysicsSystem_Update,
  where a jolt callback would deadlock against the collector, so the shim
  buffers them in C and the stepping thread drains them afterwards; and
  every active body's transform in one call instead of one call and two
  struct reads per body. The README's \"The shim\" section has the account
  and the limits."
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn- os-key []
  (let [n (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond (str/includes? n "mac") :darwin (str/includes? n "win") :windows :else :linux)))

(defn- lib-file [dir base]
  (str dir "/" (case (os-key) :darwin (str "lib" base ".dylib") :windows (str base ".dll") (str "lib" base ".so"))))

(defn- beside-source
  "This library's directory through the classpath (where its source
  file is), so a project that depends on it, by :local/root or as a git
  dependency, finds the build under it."
  []
  (when-let [u (io/resource "joltc/core.clj")]
    (-> (io/file (.getPath u)) .getParentFile .getParentFile .getParentFile .getPath)))

(def candidate-dirs
  "Where `jolt native` puts its output: native/lib under the working
  directory, then beside this library's source; JOLTC_LIB_DIR overrides."
  (cond-> ["native/lib"]
    (beside-source) (conj (str (beside-source) "/native/lib"))))

(defonce ^:private loaded (atom nil))

(defn load-libraries!
  "Load libjoltc and the shim once; returns the directory used. The build
  makes both, so a directory holding only one of them is not a build and
  is passed over."
  []
  (or @loaded
      (let [dirs (remove nil? (cons (System/getenv "JOLTC_LIB_DIR") candidate-dirs))
            missing (fn [dir] (remove #(.exists (io/file %)) [(lib-file dir "joltc") (lib-file dir "joltc_shim")]))
            found (some #(when (empty? (missing %)) %) dirs)]
        (when-not found
          (throw (ex-info "joltc: not built; run `jolt native` in joltc-jlt (or set JOLTC_LIB_DIR)"
                          {:tried (vec dirs)
                           :missing (vec (mapcat missing dirs))})))
        (ffi/load-library (lib-file found "joltc"))
        (ffi/load-library (lib-file found "joltc_shim"))
        (reset! loaded found))))

(defn available?
  "True when the native libraries can be loaded."
  []
  (try (load-libraries!) true (catch Exception _ false)))

;;; ---------------------------------------------------------------- the shim
;;
;; native/joltc_shim.c, whose opening comment is the contract. All six are
;; for the thread that steps the system, between updates. Body ids and user
;; data come back as unsigned 32-bit values: read the id and int buffers with
;; (ffi/read-array p :uint32 n) and mask each with 0xFFFFFFFF, since a Jolt
;; body id keeps a sequence number in its top bits and reads back negative
;; from an int-array otherwise.

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
