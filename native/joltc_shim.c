/* The joltc shim: the two things a per-frame physics loop cannot do well
   through the generated binding alone. Built by native/build.sh into
   lib/libjoltc_shim.{dylib,so} next to libjoltc, declared with it under
   :jolt/native in deps.edn, and bound in joltc.core as the joltc_shim_*
   functions. The README's "The shim" section is the longer account; this
   comment is the contract.

   Two projects share a name. Here "Jolt Physics" or "the engine" is the
   physics engine this binds, and "the runtime" is the jolt runtime, the
   Clojure implementation the binding is for.

   Why it exists

   1. Contacts. Jolt Physics reports contacts through a listener it calls
      from inside JPH_PhysicsSystem_Update, on its job system's worker
      threads. A Clojure callback there would arrive on a thread the runtime
      never started, so it would need :collect-safe (an option of the
      runtime's jolt.ffi library), and it would allocate; an allocation that
      reaches a garbage collection has to stop every runtime thread, and the
      thread that called Update is parked in a foreign call that is not
      :blocking, so it never reaches a safe point. Update waits for the job,
      the job waits for the collection, the collection waits for Update. So
      the listener lives here: it appends plain structs to a buffer under a
      mutex, and the stepping thread copies them out afterwards with one
      call. No callback ever enters the Clojure runtime.

   2. Transforms. Reading where every moving body ended up is one foreign
      call and two struct reads per body through the binding. Here it is one
      call for all of them, into two flat arrays.

   Contract

   Threads. The contact listener runs on the engine's job threads and is
   the only part of this file that does; the buffer's mutex is what makes that safe.
   Everything exported is for the thread that steps the system, between
   updates: the transform readers use the engine's no-lock body interface,
   so calling them while JPH_PhysicsSystem_Update runs, or while another thread
   adds, removes or moves bodies, is a data race.

   Transforms are 7 floats per body: px py pz, then the rotation quaternion
   qx qy qz qw. Positions are narrowed to float if joltc was built with
   JPH_DOUBLE_PRECISION (native/build.sh does not build it that way).

   Body ids are the engine's 32-bit JPH_BodyID, index in the low bits and a
   sequence number in the high ones, so an id can be above INT32_MAX; read
   them as unsigned.

   Contact events:
     - ADDED carries both body ids, both bodies' user data, the world space
       normal (from body1 to body2), the first manifold point on body1 and
       the penetration depth. A manifold has up to four points; the rest are
       not recorded.
     - REMOVED carries the two body ids and nothing else (zeros): the engine names
       only the pair, and either body may already be gone.
     - Persisting contacts are not reported. The engine also ends a contact
       when its bodies fall asleep and adds it again when they wake, so REMOVED
       means "no longer being solved", not "no longer touching".
     - Every contact is accepted (the validate hook never rejects a pair).
       Filter with object layers, or on the events.
     - User data is 64 bits in the engine and the drain hands back the low 32 of
       it. That fits an integer id; it does not fit a pointer.
     - The buffer holds `capacity` events. One that arrives when it is full
       is dropped and counted; joltc_shim_contacts_dropped answers the count.
       Drain once per update and size the buffer for the worst update.

   One listener per process. joltc keeps the listener's function table in one
   static (JPH_ContactListener_SetProcs), and joltc_shim_contacts_create
   points it at this file's. Any number of systems may each have a shim
   contact buffer, but a listener with procs of your own, created through
   the binding, cannot exist beside one: whichever called SetProcs last has
   every listener in the process. */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include "joltc.h"

/* The version of this contract: it goes up when a layout or a signature
   here changes. Also the one call that proves both libraries are loaded,
   since this one links libjoltc. */
uint32_t joltc_shim_version(void)
{
    return 1;
}

/* ------------------------------------------------------------ transforms */

/* The transform of every active (awake) rigid body: ids[i] is the body and
   out[7*i .. 7*i+6] its px py pz qx qy qz qw. ids must have room for max
   ids and out for 7 * max floats. Returns how many were written, at most
   max; when more bodies are active than max, the first max of the engine's
   active list are the ones read. Sleeping and static bodies are not active, and soft
   bodies are not rigid. */
uint32_t joltc_shim_read_transforms(JPH_PhysicsSystem* system, JPH_BodyID* ids, float* out, uint32_t max)
{
    uint32_t n = JPH_PhysicsSystem_GetNumActiveBodies(system, JPH_BodyType_Rigid);
    if (n > max) n = max;
    if (n == 0) return 0;
    JPH_PhysicsSystem_GetActiveBodies(system, JPH_BodyType_Rigid, ids, n);
    JPH_BodyInterface* bi = JPH_PhysicsSystem_GetBodyInterfaceNoLock(system);
    for (uint32_t i = 0; i < n; i++) {
        JPH_RVec3 p;
        JPH_Quat q;
        JPH_BodyInterface_GetPositionAndRotation(bi, ids[i], &p, &q);
        float* o = out + 7 * i;
        o[0] = (float)p.x; o[1] = (float)p.y; o[2] = (float)p.z;
        o[3] = q.x; o[4] = q.y; o[5] = q.z; o[6] = q.w;
    }
    return n;
}

/* The transforms of the bodies named in ids, active or not, in the order
   given: out must have room for 7 * count floats. Every id must be a live
   body. Returns count. */
uint32_t joltc_shim_read_body_transforms(JPH_PhysicsSystem* system, const JPH_BodyID* ids, float* out, uint32_t count)
{
    JPH_BodyInterface* bi = JPH_PhysicsSystem_GetBodyInterfaceNoLock(system);
    for (uint32_t i = 0; i < count; i++) {
        JPH_RVec3 p;
        JPH_Quat q;
        JPH_BodyInterface_GetPositionAndRotation(bi, ids[i], &p, &q);
        float* o = out + 7 * i;
        o[0] = (float)p.x; o[1] = (float)p.y; o[2] = (float)p.z;
        o[3] = q.x; o[4] = q.y; o[5] = q.z; o[6] = q.w;
    }
    return count;
}

/* -------------------------------------------------------------- contacts */

#define JOLTC_SHIM_CONTACT_ADDED 1
#define JOLTC_SHIM_CONTACT_REMOVED 2

typedef struct joltc_shim_contact {
    uint32_t type;
    JPH_BodyID body1;
    JPH_BodyID body2;
    uint64_t user1;
    uint64_t user2;
    float normal[3];    /* from body1 to body2, world space */
    float point[3];     /* the first manifold point on body1, world space */
    float depth;
} joltc_shim_contact;

typedef struct joltc_shim_contacts {
    pthread_mutex_t lock;
    joltc_shim_contact* items;
    uint32_t count;
    uint32_t capacity;
    uint32_t dropped;
    JPH_ContactListener* listener;
} joltc_shim_contacts;

static void push_contact(joltc_shim_contacts* c, const joltc_shim_contact* item)
{
    pthread_mutex_lock(&c->lock);
    if (c->count < c->capacity) {
        c->items[c->count++] = *item;
    } else {
        c->dropped++;
    }
    pthread_mutex_unlock(&c->lock);
}

static JPH_ValidateResult on_validate(void* userData, const JPH_Body* body1, const JPH_Body* body2,
                                      const JPH_RVec3* baseOffset, const JPH_CollideShapeResult* collisionResult)
{
    (void)userData; (void)body1; (void)body2; (void)baseOffset; (void)collisionResult;
    return JPH_ValidateResult_AcceptAllContactsForThisBodyPair;
}

static void on_added(void* userData, const JPH_Body* body1, const JPH_Body* body2,
                     const JPH_ContactManifold* manifold, JPH_ContactSettings* settings)
{
    (void)settings;
    joltc_shim_contact item;
    memset(&item, 0, sizeof item);
    item.type = JOLTC_SHIM_CONTACT_ADDED;
    item.body1 = JPH_Body_GetID(body1);
    item.body2 = JPH_Body_GetID(body2);
    item.user1 = JPH_Body_GetUserData((JPH_Body*)body1);
    item.user2 = JPH_Body_GetUserData((JPH_Body*)body2);
    JPH_Vec3 n;
    JPH_ContactManifold_GetWorldSpaceNormal(manifold, &n);
    item.normal[0] = n.x; item.normal[1] = n.y; item.normal[2] = n.z;
    if (JPH_ContactManifold_GetPointCount(manifold) > 0) {
        JPH_RVec3 p;
        JPH_ContactManifold_GetWorldSpaceContactPointOn1(manifold, 0, &p);
        item.point[0] = (float)p.x; item.point[1] = (float)p.y; item.point[2] = (float)p.z;
    }
    item.depth = JPH_ContactManifold_GetPenetrationDepth(manifold);
    push_contact((joltc_shim_contacts*)userData, &item);
}

static void on_persisted(void* userData, const JPH_Body* body1, const JPH_Body* body2,
                         const JPH_ContactManifold* manifold, JPH_ContactSettings* settings)
{
    (void)userData; (void)body1; (void)body2; (void)manifold; (void)settings;
}

static void on_removed(void* userData, const JPH_SubShapeIDPair* pair)
{
    joltc_shim_contact item;
    memset(&item, 0, sizeof item);
    item.type = JOLTC_SHIM_CONTACT_REMOVED;
    item.body1 = pair->Body1ID;
    item.body2 = pair->Body2ID;
    push_contact((joltc_shim_contacts*)userData, &item);
}

static JPH_ContactListener_Procs procs = { on_validate, on_added, on_persisted, on_removed };

/* Create a contact buffer with room for `capacity` events and install its
   listener on the system, replacing whatever listener the system had and
   pointing joltc's process-wide listener procs at this file's (see "One
   listener per process" above). Returns NULL when out of memory. Destroy it
   with joltc_shim_contacts_destroy before the system is destroyed. */
joltc_shim_contacts* joltc_shim_contacts_create(JPH_PhysicsSystem* system, uint32_t capacity)
{
    joltc_shim_contacts* c = (joltc_shim_contacts*)calloc(1, sizeof(joltc_shim_contacts));
    if (!c) return NULL;
    c->items = (joltc_shim_contact*)calloc(capacity, sizeof(joltc_shim_contact));
    if (!c->items) { free(c); return NULL; }
    c->capacity = capacity;
    pthread_mutex_init(&c->lock, NULL);
    JPH_ContactListener_SetProcs(&procs);     /* the procs are shared by every listener */
    c->listener = JPH_ContactListener_Create(c);
    JPH_PhysicsSystem_SetContactListener(system, c->listener);
    return c;
}

/* Take the listener off the system and free the buffer, with whatever was
   still in it. Not while the system is updating. A NULL buffer is ignored. */
void joltc_shim_contacts_destroy(JPH_PhysicsSystem* system, joltc_shim_contacts* c)
{
    if (!c) return;
    JPH_PhysicsSystem_SetContactListener(system, NULL);
    JPH_ContactListener_Destroy(c->listener);
    pthread_mutex_destroy(&c->lock);
    free(c->items);
    free(c);
}

/* Copy the oldest buffered events out, in the order they arrived, and take
   them off the buffer. Event i fills ints[5*i .. 5*i+4] with type (1 added,
   2 removed), body1, body2, and the low 32 bits of each body's user data,
   and floats[7*i .. 7*i+6] with the normal's xyz, the point's xyz and the
   depth. The ids and user data are unsigned values in int32 slots: read
   them as uint32. Returns how many were copied, at most max; the rest stay
   buffered for the next call. */
uint32_t joltc_shim_contacts_drain(joltc_shim_contacts* c, int32_t* ints, float* floats, uint32_t max)
{
    pthread_mutex_lock(&c->lock);
    uint32_t n = c->count < max ? c->count : max;
    for (uint32_t i = 0; i < n; i++) {
        const joltc_shim_contact* e = &c->items[i];
        ints[5 * i] = (int32_t)e->type;
        ints[5 * i + 1] = (int32_t)e->body1;
        ints[5 * i + 2] = (int32_t)e->body2;
        ints[5 * i + 3] = (int32_t)(e->user1 & 0xffffffffu);
        ints[5 * i + 4] = (int32_t)(e->user2 & 0xffffffffu);
        floats[7 * i] = e->normal[0]; floats[7 * i + 1] = e->normal[1]; floats[7 * i + 2] = e->normal[2];
        floats[7 * i + 3] = e->point[0]; floats[7 * i + 4] = e->point[1]; floats[7 * i + 5] = e->point[2];
        floats[7 * i + 6] = e->depth;
    }
    if (n < c->count) memmove(c->items, c->items + n, (c->count - n) * sizeof(joltc_shim_contact));
    c->count -= n;
    pthread_mutex_unlock(&c->lock);
    return n;
}

/* How many events were dropped because the buffer was full since this was
   last asked; asking resets the count. */
uint32_t joltc_shim_contacts_dropped(joltc_shim_contacts* c)
{
    pthread_mutex_lock(&c->lock);
    uint32_t d = c->dropped;
    c->dropped = 0;
    pthread_mutex_unlock(&c->lock);
    return d;
}
