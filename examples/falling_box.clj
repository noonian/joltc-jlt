(ns falling-box
  "A box dropped on a floor: the least that makes a Jolt Physics world through
  `joltc.ffi`, with the shim reading the transforms and the contacts.
  Run from the joltc-jlt directory, after `jolt native`:

      jolt -A:examples -m falling-box"
  (:require [jolt.ffi :as ffi]
            [joltc.shim :as shim]
            [joltc.ffi :as j]))

;; two object layers, each its own broad-phase layer: what never moves, and
;; what does
(def layer-static 0)
(def layer-moving 1)

(def max-bodies 64)
(def max-contacts 256)

(defn- unsigned [i] (bit-and 0xFFFFFFFF (long i)))

(defn -main [& _]
  (shim/load-libraries!)
  (when-not (j/JPH_Init) (throw (ex-info "JPH_Init failed" {})))
  (with-open [arena (ffi/confined-arena)]
    (let [vec3 (fn [x y z] (doto (ffi/alloc arena j/JPH_Vec3) (ffi/write j/JPH_Vec3 {:x x :y y :z z})))
          upright (doto (ffi/alloc arena j/JPH_Quat) (ffi/write j/JPH_Quat {:x 0.0 :y 0.0 :z 0.0 :w 1.0}))

          ;; which layers collide; the system owns and deletes these three
          bpl (j/JPH_BroadPhaseLayerInterfaceTable_Create 2 2)
          _ (j/JPH_BroadPhaseLayerInterfaceTable_MapObjectToBroadPhaseLayer bpl layer-static 0)
          _ (j/JPH_BroadPhaseLayerInterfaceTable_MapObjectToBroadPhaseLayer bpl layer-moving 1)
          pairs (j/JPH_ObjectLayerPairFilterTable_Create 2)
          _ (j/JPH_ObjectLayerPairFilterTable_EnableCollision pairs layer-static layer-moving)
          _ (j/JPH_ObjectLayerPairFilterTable_EnableCollision pairs layer-moving layer-moving)
          ovb (j/JPH_ObjectVsBroadPhaseLayerFilterTable_Create bpl 2 pairs 2)

          system (j/JPH_PhysicsSystem_Create
                  (doto (ffi/alloc arena j/JPH_PhysicsSystemSettings)
                    (ffi/write j/JPH_PhysicsSystemSettings
                               {:maxBodies max-bodies :numBodyMutexes 0 :maxBodyPairs 1024
                                :maxContactConstraints 1024 :_padding 0
                                :broadPhaseLayerInterface bpl :objectLayerPairFilter pairs
                                :objectVsBroadPhaseLayerFilter ovb})))
          ;; zero or less is the engine's default, a worker thread per core less
          ;; one; those threads are where the contact listener is called
          jobs (j/JPH_JobSystemThreadPool_Create
                (doto (ffi/alloc arena j/JobSystemThreadPoolConfig)
                  (ffi/write j/JobSystemThreadPoolConfig
                             {:maxJobs j/JPH_MAX_PHYSICS_JOBS :maxBarriers j/JPH_MAX_PHYSICS_BARRIERS
                              :numThreads 0})))
          bodies (j/JPH_PhysicsSystem_GetBodyInterface system)

          ;; a body is a shape, where it starts, how it moves and its layer;
          ;; user data is the caller's, and comes back with each contact
          add-box (fn [half-extents position motion layer user-data]
                    (let [[hx hy hz] half-extents
                          [x y z] position
                          shape (j/JPH_BoxShape_Create (vec3 hx hy hz) 0.05)
                          settings (j/JPH_BodyCreationSettings_Create3 shape (vec3 x y z) upright motion layer)
                          _ (j/JPH_BodyCreationSettings_SetUserData settings user-data)
                          id (j/JPH_BodyInterface_CreateAndAddBody bodies settings j/JPH_Activation_Activate)]
                      (j/JPH_BodyCreationSettings_Destroy settings)
                      {:id id :shape shape}))
          floor (add-box [50.0 0.5 50.0] [0.0 -0.5 0.0] j/JPH_MotionType_Static layer-static 1)
          box (add-box [0.5 0.5 0.5] [0.0 5.0 0.0] j/JPH_MotionType_Dynamic layer-moving 2)

          ;; the shim: a contact buffer on the system, and room to read into
          contacts (shim/joltc_shim_contacts_create system max-contacts)
          ids (ffi/alloc arena (* 4 max-bodies))
          transforms (ffi/alloc arena (* 4 7 max-bodies))
          c-ints (ffi/alloc arena (* 4 5 max-contacts))
          c-floats (ffi/alloc arena (* 4 7 max-contacts))]
      (try
        (dotimes [tick 120]
          (let [err (j/JPH_PhysicsSystem_Update system (/ 1.0 60.0) 1 jobs)]
            (when-not (zero? err) (throw (ex-info "JPH_PhysicsSystem_Update failed" {:error err}))))

          ;; every awake body's transform, in one call
          (let [n (shim/joltc_shim_read_transforms system ids transforms max-bodies)]
            (when (and (pos? n) (zero? (mod tick 20)))
              (let [id (unsigned (aget (ffi/read-array ids :uint32 n) 0))
                    [px py pz] (ffi/read-array transforms :float 7)]
                (println (format "tick %3d  body %d at (%.2f %.2f %.2f)" tick id px py pz)))))

          ;; what touched and what parted during that update
          (let [n (shim/joltc_shim_contacts_drain contacts c-ints c-floats max-contacts)]
            (when (pos? n)
              (let [is (ffi/read-array c-ints :uint32 (* 5 n))
                    fs (ffi/read-array c-floats :float (* 7 n))]
                (dotimes [i n]
                  (let [at (fn [k] (unsigned (aget is (+ k (* 5 i)))))
                        f (fn [k] (aget fs (+ k (* 7 i))))]
                    (if (= 1 (at 0))
                      (println (format "tick %3d  added: bodies %d and %d, user data %d and %d, normal (%.1f %.1f %.1f), depth %.3f"
                                       tick (at 1) (at 2) (at 3) (at 4) (f 0) (f 1) (f 2) (f 6)))
                      ;; a removed event names the pair and nothing else; a
                      ;; body falling asleep ends its contacts too
                      (println (format "tick %3d  removed: bodies %d and %d" tick (at 1) (at 2)))))))))
          (let [dropped (shim/joltc_shim_contacts_dropped contacts)]
            (when (pos? dropped) (println "dropped" dropped "contacts: raise max-contacts"))))
        (finally
          ;; the listener before the system, the bodies before their shapes
          (shim/joltc_shim_contacts_destroy system contacts)
          (doseq [{:keys [id shape]} [box floor]]
            (j/JPH_BodyInterface_RemoveAndDestroyBody bodies id)
            (j/JPH_Shape_Destroy shape))
          (j/JPH_PhysicsSystem_Destroy system)
          (j/JPH_JobSystem_Destroy jobs)
          (j/JPH_Shutdown))))))
