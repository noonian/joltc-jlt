(ns joltc.core
  "Loading joltc (the C API over Jolt Physics) and the joltc shim, both
  built by `jolt native` (native/build.sh) into native/lib and loaded here
  at run time, not declared under :jolt/native, so a project without the
  build still loads. The generated binding is `joltc.ffi`; the shim adds the two
  things a physics adapter wants in bulk rather than per body: every
  active transform in one call, and contacts drained from a buffer that
  Jolt's job threads append to, so no callback ever enters the Clojure
  runtime."
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
  "Load libjoltc and the shim once; returns the directory used."
  []
  (or @loaded
      (let [dirs (cons (System/getenv "JOLTC_LIB_DIR") candidate-dirs)
            found (some #(when (and % (.exists (io/file (lib-file % "joltc"))) (.exists (io/file (lib-file % "joltc_shim")))) %)
                        dirs)]
        (when-not found
          (throw (ex-info "joltc: not built; run `jolt native` in joltc-jlt (or set JOLTC_LIB_DIR)"
                          {:tried (vec (remove nil? dirs))})))
        (ffi/load-library (lib-file found "joltc"))
        (ffi/load-library (lib-file found "joltc_shim"))
        (reset! loaded found))))

(defn available?
  "True when the native libraries can be loaded."
  []
  (try (load-libraries!) true (catch Exception _ false)))

;;; ---------------------------------------------------------------- the shim

(ffi/defcfn joltc_shim_read_transforms "joltc_shim_read_transforms" [:pointer :pointer :pointer :uint32] :uint32)
(ffi/defcfn joltc_shim_read_body_transforms "joltc_shim_read_body_transforms" [:pointer :pointer :pointer :uint32] :uint32)
(ffi/defcfn joltc_shim_contacts_create "joltc_shim_contacts_create" [:pointer :uint32] :pointer)
(ffi/defcfn joltc_shim_contacts_destroy "joltc_shim_contacts_destroy" [:pointer :pointer] :void)
(ffi/defcfn joltc_shim_contacts_drain "joltc_shim_contacts_drain" [:pointer :pointer :pointer :uint32] :uint32)
(ffi/defcfn joltc_shim_contacts_dropped "joltc_shim_contacts_dropped" [:pointer] :uint32)
