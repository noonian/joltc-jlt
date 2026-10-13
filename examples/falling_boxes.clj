(ns falling-boxes
  "Boxes tumbling onto a floor, drawn with raylib: `falling-box` with something
  to look at. Jolt Physics moves the boxes, the shim reads every transform in
  one call and says which boxes touched, and raylib-jlt draws them. A box turns
  gold for a moment when it hits something. SPACE drops another.

  Needs raylib 6.0 installed (`brew install raylib`), and `jolt native` run
  here first. From the joltc-jlt directory:

      jolt -A:raylib -m falling-boxes"
  (:require [jolt.ffi :as ffi]
            [joltc.shim :as shim]
            [joltc.ffi :as j]
            [net.b12n.raylib.all :as rl]))

(def layer-static 0)
(def layer-moving 1)

(def max-boxes 200)
(def max-contacts 1024)
(def flash-frames 12)

(defn- random-quat
  "A rotation by a random angle about a random axis, as [x y z w]."
  []
  (let [[x y z] (repeatedly 3 #(- (rand) 0.5))
        len (Math/sqrt (+ (* x x) (* y y) (* z z) 1e-9))
        half (* Math/PI (rand))
        s (/ (Math/sin half) len)]
    [(* x s) (* y s) (* z s) (Math/cos half)]))

(defn- draw-box
  "A unit cube at a transform. raylib's matrix stack turns by an angle in
  degrees about an axis, so the quaternion is taken apart into those."
  [[px py pz qx qy qz qw] color]
  (let [w (max -1.0 (min 1.0 qw))
        s (Math/sqrt (- 1.0 (* w w)))]
    (rl/rl-push-matrix)
    (rl/rl-translatef px py pz)
    (when (> s 1e-6)
      (rl/rl-rotatef (Math/toDegrees (* 2.0 (Math/acos w))) (/ qx s) (/ qy s) (/ qz s)))
    (rl/cube! :size 1.0 :color color)
    (rl/rl-pop-matrix)))

(defn -main
  "With a number, stop after that many frames instead of when the window closes."
  [& [frames]]
  (shim/load-libraries!)
  (when-not (j/JPH_Init) (throw (ex-info "JPH_Init failed" {})))
  (with-open [arena (ffi/confined-arena)]
    (let [frame-limit (some-> frames parse-long)
          vec3 (fn [x y z] (doto (ffi/alloc arena j/JPH_Vec3) (ffi/write j/JPH_Vec3 {:x x :y y :z z})))
          quat (fn [[x y z w]] (doto (ffi/alloc arena j/JPH_Quat) (ffi/write j/JPH_Quat {:x x :y y :z z :w w})))

          ;; the world, as in falling-box
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
                               {:maxBodies (inc max-boxes) :numBodyMutexes 0 :maxBodyPairs 4096
                                :maxContactConstraints 4096 :_padding 0
                                :broadPhaseLayerInterface bpl :objectLayerPairFilter pairs
                                :objectVsBroadPhaseLayerFilter ovb})))
          jobs (j/JPH_JobSystemThreadPool_Create
                (doto (ffi/alloc arena j/JobSystemThreadPoolConfig)
                  (ffi/write j/JobSystemThreadPoolConfig
                             {:maxJobs j/JPH_MAX_PHYSICS_JOBS :maxBarriers j/JPH_MAX_PHYSICS_BARRIERS
                              :numThreads 0})))
          bodies (j/JPH_PhysicsSystem_GetBodyInterface system)

          add-body (fn [shape position rotation motion layer user-data]
                     (let [[x y z] position
                           settings (j/JPH_BodyCreationSettings_Create3 shape (vec3 x y z) (quat rotation) motion layer)
                           _ (j/JPH_BodyCreationSettings_SetUserData settings user-data)
                           id (j/JPH_BodyInterface_CreateAndAddBody bodies settings j/JPH_Activation_Activate)]
                       (j/JPH_BodyCreationSettings_Destroy settings)
                       id))
          floor-shape (j/JPH_BoxShape_Create (vec3 50.0 0.5 50.0) 0.05)
          box-shape (j/JPH_BoxShape_Create (vec3 0.5 0.5 0.5) 0.05)
          floor (add-body floor-shape [0.0 -0.5 0.0] [0.0 0.0 0.0 1.0] j/JPH_MotionType_Static layer-static 0)

          ;; the shim's buffers. ids holds the boxes in the order they were
          ;; made, so transform i is box i's, asleep or not
          contacts (shim/joltc_shim_contacts_create system max-contacts)
          ids (ffi/alloc arena (* 4 max-boxes))
          transforms (ffi/alloc arena (* 4 shim/transform-floats max-boxes))
          c-ints (ffi/alloc arena (* 4 shim/contact-ints max-contacts))
          c-floats (ffi/alloc arena (* 4 shim/contact-floats max-contacts))

          box-count (atom 0)
          ;; box index -> frames of gold left
          flashes (atom {})
          ;; a box's user data is its index and one more, the floor's is 0
          drop-box! (fn [y]
                      (when (< @box-count max-boxes)
                        (let [i @box-count
                              id (add-body box-shape [(- (* 3.0 (rand)) 1.5) y (- (* 3.0 (rand)) 1.5)] (random-quat)
                                           j/JPH_MotionType_Dynamic layer-moving (inc i))]
                          (ffi/write ids :uint32 id (* 4 i))
                          (swap! box-count inc))))]
      (dotimes [i 12] (drop-box! (+ 4.0 (* 1.5 i))))
      (rl/window! :width 960 :height 600 :title "joltc-jlt: falling boxes")
      (rl/set-target-fps 60)
      (try
        (loop [frame 0]
          (when (and (not (rl/window-should-close?))
                     (or (nil? frame-limit) (< frame frame-limit)))
            (when (rl/key-pressed? rl/KEY-SPACE) (drop-box! 10.0))

            (let [err (j/JPH_PhysicsSystem_Update system (/ 1.0 60.0) 1 jobs)]
              (when-not (zero? err) (throw (ex-info "JPH_PhysicsSystem_Update failed" {:error err}))))

            ;; what touched during that update: light up both boxes
            (swap! flashes (fn [m] (into {} (keep (fn [[i left]] (when (> left 1) [i (dec left)]))) m)))
            (let [n (shim/joltc_shim_contacts_drain contacts c-ints c-floats max-contacts)]
              (when (pos? n)
                (doseq [[type _body1 _body2 user1 user2]
                        (partition shim/contact-ints (ffi/read-array c-ints :uint32 (* shim/contact-ints n)))
                        :when (= shim/contact-added type)
                        user [user1 user2]
                        :when (pos? user)]
                  (swap! flashes assoc (dec user) flash-frames))))

            ;; where every box is, in one call
            (let [n @box-count
                  _ (shim/joltc_shim_read_body_transforms system ids transforms n)
                  boxes (partition shim/transform-floats
                                   (ffi/read-array transforms :float (* shim/transform-floats n)))
                  lit @flashes]
              (rl/begin-drawing)
              (rl/clear-background rl/RAYWHITE)
              (rl/with-camera-3d {:pos-x 11.0 :pos-y 8.0 :pos-z 11.0 :target-y 1.5}
                (fn []
                  (rl/cube! :pos [0.0 -0.5 0.0] :size [24.0 1.0 24.0] :color rl/LIGHTGRAY)
                  (doseq [[i transform] (map-indexed vector boxes)]
                    (draw-box transform (if (lit i) rl/GOLD rl/SKYBLUE)))))
              (rl/text! (str n " boxes   SPACE drops another") :x 12 :y 36 :size 20 :color rl/DARKGRAY)
              (rl/fps! :x 12 :y 10)
              (rl/end-drawing))
            (recur (inc frame))))
        (finally
          (rl/close-window)
          ;; the listener before the system, the bodies before their shapes
          (shim/joltc_shim_contacts_destroy system contacts)
          (dotimes [i @box-count]
            (j/JPH_BodyInterface_RemoveAndDestroyBody bodies (bit-and 0xFFFFFFFF (long (ffi/read ids :uint32 (* 4 i))))))
          (j/JPH_BodyInterface_RemoveAndDestroyBody bodies floor)
          (j/JPH_Shape_Destroy box-shape)
          (j/JPH_Shape_Destroy floor-shape)
          (j/JPH_PhysicsSystem_Destroy system)
          (j/JPH_JobSystem_Destroy jobs)
          (j/JPH_Shutdown))))))
