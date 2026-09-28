// game/server/ez2vr/ez2vr_cvars_server.cpp — server-side ez2vr_* registrations.
//
// DROP-IN for a Source SDK 2013 / Mapbase server project (EZ2's ez2/mapbase
// branch or equivalent). Add the file to the server VPC list next to the other
// game/server files. Defaults MUST match vr_defaults.cfg and EZ2VrSettings.

#include "cbase.h"
#include "ez2vr_cvars.h"

// Movement is server-authoritative in SP: both the legacy HL2 player code and
// the future XR input path read these on the server.
ConVar ez2vr_locomotion_mode( "ez2vr_locomotion_mode", "0", FCVAR_ARCHIVE,
	"0 smooth locomotion, 1 teleport, 2 teleport+dash. Menu-owned." );
ConVar ez2vr_turn_mode( "ez2vr_turn_mode", "0", FCVAR_ARCHIVE,
	"0 snap turn, 1 smooth turn. Menu-owned." );
ConVar ez2vr_seated( "ez2vr_seated", "0", FCVAR_ARCHIVE,
	"1 fixes player height for seated play. Menu-owned." );

// Bad Cop kit state. The visor overlay render itself lives client-side later;
// the server owns whether the kit is active (drives suit-power/visor logic).
ConVar ez2vr_visor_overlay( "ez2vr_visor_overlay", "1", FCVAR_ARCHIVE,
	"Combine visor chrome + squad status. Menu-owned." );
ConVar ez2vr_flashlight_mount( "ez2vr_flashlight_mount", "0", FCVAR_ARCHIVE,
	"0 hand-mounted, 1 head-mounted flashlight. Menu-owned." );
ConVar ez2vr_vehicle_aim_mode( "ez2vr_vehicle_aim_mode", "0", FCVAR_ARCHIVE,
	"APC aim: 0 head-aim, 1 hand-aim. Menu-owned." );
