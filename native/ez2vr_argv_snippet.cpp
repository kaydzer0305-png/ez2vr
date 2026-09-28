// native/ez2vr_argv_snippet.cpp — REFERENCE, not compiled here.
//
// PROBLEM: EZ2EngineActivity exports the full launch command line in the
// EZ2_GAME_ARGS environment variable:
//   -game EntropyZero2 -mobile +exec ez2vr_boot.cfg +exec ez2vr_mobile.cfg +ez2vr_* …
// The Android launcher native bootstrap (liblauncher / launcher_main) starts
// with a fixed argv. This snippet folds the env string into argv BEFORE the
// engine parses the command line, so +exec and +convar pairs behave exactly
// like a desktop command line.
//
// CALL IT in the launcher bootstrap before COM_InitArgv / LauncherMain.
// Tested pattern; adapt symbol names to your tree (nillerusr: launcher/
// android bootstrap; Mapbase SDK launcher: launcher.cpp LauncherMain).

#include <stdlib.h>
#include <string.h>
#include <vector>
#include <string>

// Tokenize honoring double quotes. "-game \"My Mod\"" stays one argument.
static void EZ2VR_TokenizeCommandLine( const char *src, std::vector<std::string> &out )
{
	if ( !src || !*src )
		return;
	std::string cur;
	bool inQuote = false;
	for ( const char *p = src; *p; ++p )
	{
		if ( *p == '"' )
		{
			inQuote = !inQuote;
		}
		else if ( *p == ' ' && !inQuote )
		{
			if ( !cur.empty() )
			{
				out.push_back( cur );
				cur.clear();
			}
		}
		else
		{
			cur.push_back( *p );
		}
	}
	if ( !cur.empty() )
		out.push_back( cur );
}

// Merges EZ2_GAME_ARGS behind the bootstrap argv. Bootstrap args win on
// conflict because the engine's +exec/+convar handling applies left to right
// and these are appended — EXCEPT -game, which the engine takes from its
// FIRST occurrence: so if the bootstrap already carries -game, strip ours.
// Returns a Markus-style argc/argv pair owned by the returned vector storage;
// keep 'storage' alive for process lifetime (leak on purpose, like COM).
static void EZ2VR_AppendEnvArgs( int &argc, char **&argv,
                                 std::vector<std::string> &storage,
                                 std::vector<char *> &ptrs )
{
	const char *env = getenv( "EZ2_GAME_ARGS" );
	if ( !env || !*env )
		return;

	bool bootstrapHasGame = false;
	for ( int i = 0; i < argc; ++i )
	{
		if ( argv[i] && strcmp( argv[i], "-game" ) == 0 )
		{
			bootstrapHasGame = true;
			break;
		}
	}

	std::vector<std::string> toks;
	EZ2VR_TokenizeCommandLine( env, toks );
	// Copy current argv into owned storage first.
	for ( int i = 0; i < argc; ++i )
		storage.push_back( argv[i] ? argv[i] : "" );
	// Append env tokens, skipping a duplicate -game pair.
	for ( size_t i = 0; i < toks.size(); ++i )
	{
		if ( bootstrapHasGame && toks[i] == "-game" )
		{
			++i; // skip the value too
			continue;
		}
		storage.push_back( toks[i] );
	}
	ptrs.clear();
	for ( size_t i = 0; i < storage.size(); ++i )
		ptrs.push_back( const_cast<char *>( storage[i].c_str() ) );
	argc = (int)ptrs.size();
	argv = ptrs.data();
}

// SEARCH-PATH NOTE (no code needed): +exec ez2vr_boot.cfg resolves through the
// normal game search path. The Java side mounts the overlay root as the mod's
// write/loose path (EZ2_WRITE_GAME_PATH) and the staged vr layers as content
// roots, so the cfgs are found exactly like retail cfgs. If an exec prints
// "couldn't exec", the mount — not this snippet — is the suspect.
