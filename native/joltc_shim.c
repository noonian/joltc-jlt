/* The joltc shim: the two things a physics adapter wants in bulk rather
   than per body. Built by native/build.sh into
   lib/libjoltc_shim.{dylib,so} next to libjoltc, and loaded by
   joltc.core at run time.

   1. read_transforms: every active rigid body's position and rotation into
      one float array with one call, instead of a C call and two struct
      reads per body.
   2. contacts: a contact listener that appends added and removed contacts
      to a buffer under a mutex (Jolt calls it from its job threads), and a
      drain that copies the buffer out. No callback ever enters the
      Clojure runtime. */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include "joltc.h"

/* ------------------------------------------------------------ transforms */

/* Fill ids and out (7 floats per body: px py pz qx qy qz qw) for up to max
   active rigid bodies. Returns how many were written. */
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

/* Fill out with the transforms of the given bodies (7 floats each), active
   or not. Returns count. */
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

typedef struct gameth_contact {
    uint32_t type;
    JPH_BodyID body1;
    JPH_BodyID body2;
    uint64_t user1;
    uint64_t user2;
    float normal[3];    /* from body1 to body2, world space */
    float point[3];     /* the first manifold point on body1, world space */
    float depth;
} gameth_contact;

typedef struct gameth_contacts {
    pthread_mutex_t lock;
    gameth_contact* items;
    uint32_t count;
    uint32_t capacity;
    uint32_t dropped;
    JPH_ContactListener* listener;
} gameth_contacts;

static void push_contact(gameth_contacts* c, const gameth_contact* item)
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
    gameth_contact item;
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
    push_contact((gameth_contacts*)userData, &item);
}

static void on_persisted(void* userData, const JPH_Body* body1, const JPH_Body* body2,
                         const JPH_ContactManifold* manifold, JPH_ContactSettings* settings)
{
    (void)userData; (void)body1; (void)body2; (void)manifold; (void)settings;
}

static void on_removed(void* userData, const JPH_SubShapeIDPair* pair)
{
    gameth_contact item;
    memset(&item, 0, sizeof item);
    item.type = JOLTC_SHIM_CONTACT_REMOVED;
    item.body1 = pair->Body1ID;
    item.body2 = pair->Body2ID;
    push_contact((gameth_contacts*)userData, &item);
}

static JPH_ContactListener_Procs procs = { on_validate, on_added, on_persisted, on_removed };

/* Create a contact buffer with room for `capacity` events and install its
   listener on the system. Destroy with joltc_shim_contacts_destroy
   before the system goes. */
gameth_contacts* joltc_shim_contacts_create(JPH_PhysicsSystem* system, uint32_t capacity)
{
    gameth_contacts* c = (gameth_contacts*)calloc(1, sizeof(gameth_contacts));
    if (!c) return NULL;
    c->items = (gameth_contact*)calloc(capacity, sizeof(gameth_contact));
    if (!c->items) { free(c); return NULL; }
    c->capacity = capacity;
    pthread_mutex_init(&c->lock, NULL);
    JPH_ContactListener_SetProcs(&procs);     /* the procs are shared by every listener */
    c->listener = JPH_ContactListener_Create(c);
    JPH_PhysicsSystem_SetContactListener(system, c->listener);
    return c;
}

void joltc_shim_contacts_destroy(JPH_PhysicsSystem* system, gameth_contacts* c)
{
    if (!c) return;
    JPH_PhysicsSystem_SetContactListener(system, NULL);
    JPH_ContactListener_Destroy(c->listener);
    pthread_mutex_destroy(&c->lock);
    free(c->items);
    free(c);
}

/* Copy and clear the buffered events. ints gets 5 per event (type, body1,
   body2, user1 low 32 bits, user2 low 32 bits) and floats 7 per event
   (normal xyz, point xyz, depth). Returns how many were copied; events
   beyond max stay buffered. */
uint32_t joltc_shim_contacts_drain(gameth_contacts* c, int32_t* ints, float* floats, uint32_t max)
{
    pthread_mutex_lock(&c->lock);
    uint32_t n = c->count < max ? c->count : max;
    for (uint32_t i = 0; i < n; i++) {
        const gameth_contact* e = &c->items[i];
        ints[5 * i] = (int32_t)e->type;
        ints[5 * i + 1] = (int32_t)e->body1;
        ints[5 * i + 2] = (int32_t)e->body2;
        ints[5 * i + 3] = (int32_t)(e->user1 & 0xffffffffu);
        ints[5 * i + 4] = (int32_t)(e->user2 & 0xffffffffu);
        floats[7 * i] = e->normal[0]; floats[7 * i + 1] = e->normal[1]; floats[7 * i + 2] = e->normal[2];
        floats[7 * i + 3] = e->point[0]; floats[7 * i + 4] = e->point[1]; floats[7 * i + 5] = e->point[2];
        floats[7 * i + 6] = e->depth;
    }
    if (n < c->count) memmove(c->items, c->items + n, (c->count - n) * sizeof(gameth_contact));
    c->count -= n;
    pthread_mutex_unlock(&c->lock);
    return n;
}

uint32_t joltc_shim_contacts_dropped(gameth_contacts* c)
{
    pthread_mutex_lock(&c->lock);
    uint32_t d = c->dropped;
    c->dropped = 0;
    pthread_mutex_unlock(&c->lock);
    return d;
}
