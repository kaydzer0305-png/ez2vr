// game/shared/ez2vr/ez2vr_cvars.h — native side of the ez2vr_* contract.
//
// The Java menu (EZ2VrSettings) owns the VALUES; this header owns the
// DEFAULTS. The two must match vr_defaults.cfg. All gameplay convars are
// FCVAR_ARCHIVE so they persist into config.cfg like any Source setting.
//
// SPLIT (load-bearing): client and server link separate ConVar lists into one
// process ICvar, so the same name must never be REGISTERED twice. Movement and
// kit state are server-authoritative in single-player; presentation is client.
//   server: locomotion, turn, seated, visor, flashlight_mount, vehicle_aim
//   client: performance_mode, ffr_level (render/present policy)

#ifndef EZ2VR_CVARS_H
#define EZ2VR_CVARS_H

#ifdef GAME_DLL
// ---- server-authoritative (game/server/ez2vr/ez2vr_cvars_server.cpp) ----
extern class ConVar ez2vr_locomotion_mode;   // 0 smooth, 1 teleport, 2 dash
extern class ConVar ez2vr_turn_mode;          // 0 snap, 1 smooth
extern class ConVar ez2vr_seated;             // 0 stand, 1 seated
extern class ConVar ez2vr_visor_overlay;      // Bad Cop visor chrome + squad status
extern class ConVar ez2vr_flashlight_mount;   // 0 hand, 1 head
extern class ConVar ez2vr_vehicle_aim_mode;   // 0 head-aim, 1 hand-aim (APC)
#else
// ---- client/presentation (game/client/ez2vr/ez2vr_cvars_client.cpp) ----
extern class ConVar ez2vr_performance_mode;   // 0 quality, 1 balanced, 2 performance
extern class ConVar ez2vr_ffr_level;          // 0 off .. 3 max (XR foveation)
#endif

#endif // EZ2VR_CVARS_H
