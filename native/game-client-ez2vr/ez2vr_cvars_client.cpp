// game/client/ez2vr/ez2vr_cvars_client.cpp — client-side ez2vr_* registrations.
//
// DROP-IN for the client project. Presentation policy only: these never affect
// simulation, so they live here and NOT in the server file (double registration
// of one name in-process asserts — see ez2vr_cvars.h).

#include "cbase.h"
#include "hud.h"
#include "ez2vr_cvars.h"

ConVar ez2vr_performance_mode( "ez2vr_performance_mode", "0", FCVAR_ARCHIVE,
	"0 quality, 1 balanced, 2 performance. Selects the ez2vr_mobile.cfg opt-in set. Menu-owned." );
ConVar ez2vr_ffr_level( "ez2vr_ffr_level", "0", FCVAR_ARCHIVE,
	"XR fixed-foveated rendering level 0..3. Passed to the XR runtime. Menu-owned." );

// FUTURE (register here when the VR layer lands; cataloged in vr_defaults.cfg):
//   ez2vr_hud_distance, ez2vr_hud_in_world, ez2vr_viewmodel_offset_forward,
//   ez2vr_world_scale, ez2vr_grip_threshold, ez2vr_gunpoint[_range],
//   ez2vr_menu_laser_width, ez2vr_nightvision_fov, volumes, vignette set.
