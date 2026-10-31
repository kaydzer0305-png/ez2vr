// game/server/ez2vr/ez2vr_cvars_server.cpp — server-side ez2vr_* registrations.
//
// DROP-IN for a Source SDK 2013 / Mapbase server project (EZ2's ez2/mapbase
// branch or equivalent). Add the file to the server VPC list next to the other
// game/server files. Defaults MUST match vr_defaults.cfg and EZ2VrSettings.

#include "cbase.h"
#include "ez2vr_cvars.h"
#include "player.h"

#include <math.h>

// Movement is server-authoritative in SP: both the legacy HL2 player code and
// the XR input path (launcher/android/ezquest_vr_sourceinput.cpp) read these.
ConVar ez2vr_locomotion_mode( "ez2vr_locomotion_mode", "0", FCVAR_ARCHIVE,
	"0 smooth locomotion, 1 teleport, 2 teleport+dash. Menu-owned." );
ConVar ez2vr_turn_mode( "ez2vr_turn_mode", "0", FCVAR_ARCHIVE,
	"0 snap turn, 1 smooth turn. Menu-owned." );
ConVar ez2vr_turn_snap_angle( "ez2vr_turn_snap_angle", "30", FCVAR_ARCHIVE,
	"Snap-turn step in degrees when ez2vr_turn_mode is 0. Menu catalog default.",
	true, 15.0f, true, 90.0f );
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

//-----------------------------------------------------------------------------
// On-foot blink used by locomotion modes 1 (teleport) and 2 (longer dash blink).
// The XR thread cannot move the player itself; it injects this into the engine
// command buffer. Args: <forward -1..1> <right -1..1> <distance units> <dash 0/1>.
// Direction is flattened eye yaw, so it follows the headset. No-op in a vehicle
// (the APC still wants +forward, which smooth mode already sends).
//-----------------------------------------------------------------------------
static void CC_EZ2VRBlink( const CCommand &args )
{
	if ( args.ArgC() < 4 )
		return;

	CBasePlayer *pPlayer = UTIL_GetLocalPlayer();
	if ( !pPlayer || !pPlayer->IsAlive() || pPlayer->IsInAVehicle() )
		return;

	float fwd = (float)atof( args[1] );
	float right = (float)atof( args[2] );
	float dist = (float)atof( args[3] );
	const int dash = ( args.ArgC() >= 5 ) ? atoi( args[4] ) : 0;

	if ( dist < 32.f )
		dist = 32.f;
	if ( dist > 256.f )
		dist = 256.f;

	const float scale = sqrtf( fwd * fwd + right * right );
	if ( scale < 0.01f )
		return;
	fwd /= scale;
	right /= scale;

	QAngle ang = pPlayer->EyeAngles();
	ang.x = 0.f;
	ang.z = 0.f;
	Vector vFwd, vRight;
	AngleVectors( ang, &vFwd, &vRight, NULL );
	Vector wish = vFwd * fwd + vRight * right;
	wish.z = 0.f;
	if ( wish.LengthSqr() < 0.0001f )
		return;
	VectorNormalize( wish );

	const Vector mins = pPlayer->GetPlayerMins();
	const Vector maxs = pPlayer->GetPlayerMaxs();
	const Vector start = pPlayer->GetAbsOrigin();
	const Vector end = start + wish * dist;

	trace_t tr;
	UTIL_TraceHull( start, end, mins, maxs, MASK_PLAYERSOLID, pPlayer,
		COLLISION_GROUP_PLAYER_MOVEMENT, &tr );
	if ( tr.startsolid || ( tr.fraction * dist ) < 8.f )
		return;

	Vector dest = tr.endpos;
	if ( tr.fraction < 1.0f )
		dest -= wish * 8.f;

	trace_t drop;
	UTIL_TraceHull( dest + Vector( 0, 0, 16 ), dest - Vector( 0, 0, 64 ),
		mins, maxs, MASK_PLAYERSOLID, pPlayer, COLLISION_GROUP_PLAYER_MOVEMENT, &drop );
	if ( !drop.startsolid && drop.fraction < 1.0f )
		dest = drop.endpos;

	Vector vel = vec3_origin;
	if ( dash )
		vel = wish * 280.f;
	pPlayer->Teleport( &dest, NULL, &vel );
}

static ConCommand ez2vr_blink( "ez2vr_blink", CC_EZ2VRBlink,
	"On-foot VR blink along flattened eye yaw. Args: <forward -1..1> <right -1..1> <distance> <dash 0/1>.",
	FCVAR_GAMEDLL | FCVAR_CLIENTCMD_CAN_EXECUTE | FCVAR_SERVER_CAN_EXECUTE );
