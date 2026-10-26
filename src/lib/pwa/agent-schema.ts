type Schema=Record<string,unknown>;
const string=(description:string):Schema => ({type:"string",description});
const number=(description:string):Schema => ({type:"integer",description});
const bool=(description:string):Schema => ({type:"boolean",description});
const array=(description:string):Schema => ({type:"array",description,items:{type:"string"}});
const enumeration=(description:string,values:string[]):Schema => ({...string(description),enum:values});
const requestedTracks:Schema={type:"array",minItems:1,maxItems:50,description:"Every explicitly named song. Do not add unrequested recommendations.",items:{type:"object",properties:{title:string("Exact title"),artist:string("Artist when known")},required:["title"]}};
const track:Schema={type:"object",properties:{title:string("Required track title"),artist:string("Artist hint")}};
const pending=bool("False only for the LAST action. Success ends this turn using factual results. True if more requested tools remain.");
export const executionTools=new Set(["commit_queue","skip_current","like_current","like_track","modify_playlist_current","create_playlist_from_tracks"]);
const queue:Record<string,Schema>={
  intent_mode:enumeration("Required semantic selection boundary",["exact_catalog","exact_track","artist_focus","playlist","open_recommendation","contextual_continuation"]),
  operation:enumeration("Queue operation",["replace_queue","play_now","insert_next"]),query:string("Full natural music/style request"),
  catalog:{type:"object",description:"Named album, soundtrack, musical or work only. Omit for open style requests.",properties:{name:string("Exact object requested"),aliases:array("Complete canonical/original/translated album names verifiable against metadata. Never generic keywords."),search_queries:array("1-4 precise catalog searches")},required:["name","aliases","search_queries"]},
  playlist_name:string("Scope to an existing playlist/cloud disk"),artists:array("Every primary artist requested"),artist_scope:enumeration("Strict for named artists. Focus only for explicitly mixed similar artists. Similar for style similarity.",["Strict","Focus","Similar"]),count:number("First batch size, default12. Insert default1; >1 inserts an ordered batch."),strict_semantics:bool("True only when user explicitly requires every song's genre/language/vocal to be verified; ordinary recommendations false."),
  continuation_mode:enumeration("same_intent keeps this intent after the first batch. disabled only for explicit stop/no continuation or a closing track. Count alone does not disable it.",["same_intent","disabled"]),
  target_title:string("Specific first/next song; preserve full version suffix when copying a returned title"),target_artist:string("Specific first/next artist"),jump_to_inserted:bool("True only for explicitly immediate insertion; false keeps the current song playing"),preserve_current:bool("Default false; true only when the current request explicitly preserves current playback or changes upcoming music"),similar:bool("Only style/current-context similarity, never named catalog"),
  reference_context:enumeration("Context anchor",["current_track","current_style","current_queue","previous_intent","mentioned_track"]),use_current_style_anchor:bool("Use current music/style as a reference"),more_actions_pending:pending,
  must_include_titles:array("Required song titles"),must_include:requestedTracks,first_track:track,closer:track,include_artists:array("Additional artists that must appear"),closer_title:string("Final song"),exclude_terms:array("Artists/languages/styles to avoid"),moods:array("Mood words"),scenes:array("Scene words"),genres:array("Genre words"),languages:array("Language hints"),style:{type:"object"},
};
function tool(name:string,description:string,properties:Record<string,Schema>,required:string[]=[]) {return {type:"function",function:{name,description,parameters:{type:"object",properties:{...properties,...(executionTools.has(name)||name==="draft_queue"?{action_id:string("Matching id from plan_actions; omit for a single action")}: {})},required}}};}
export const agentTools=[
  tool("plan_actions","Before a compound request, list ALL executable actions in dependency order. Never omit later clauses. Every execution carries its action_id.",{actions:{type:"array",items:{type:"object",properties:{id:string("Unique action id"),tool:enumeration("Execution tool",[...executionTools]),description:string("Requested exact target, timing and dependencies")},required:["id","tool","description"]}}},["actions"]),
  tool("final_response","Pure chat, clarification or honest cannot-do response only. Never claim unexecuted playback, queue, like or playlist success.",{message:string("Short response"),unfulfilled_actions:array("Every unfinished planned action id; explain failure in message"),reason:string("Why no further music action is needed")},["message"]),
  tool("list_playlists","List real playlists before opening/scoping a playlist.",{}),
  tool("get_playlist_tracks","Load actual songs from a playlist/cloud disk. Required before committing a named playlist.",{playlist_name:string("Exact playlist name"),playlist_id:number("Known id"),limit:number("Max returned tracks")}),
  tool("search_tracks","Search actual catalog by title, artist or natural query.",{query:string("Search query"),limit:number("1-30")},["query"]),
  tool("draft_queue","Resolve, rank and validate a queue before playback. A valid single action can auto commit; do not recommit autoCommitted=true.",queue,["intent_mode"]),
  tool("commit_queue","Commit a validated draft or actual returned keys. Observe errors and repair without relaxing constraints.",{...queue,draft_id:string("draftId returned by draft_queue"),track_keys:array("Only real keys returned by tools or current queue. All are inserted in order for insert_next.")},["intent_mode"]),
  tool("skip_current","Only explicit skip with no named target song.",{more_actions_pending:pending}),
  tool("like_current","Like/unlike the actual current track.",{like:bool("true to like, false only when explicitly unlike"),more_actions_pending:pending}),
  tool("like_track","Resolve and like/unlike the named track.",{title:string("Track title"),artist:string("Artist hint"),like:bool("Like direction"),more_actions_pending:pending},["title"]),
  tool("modify_playlist_current","Add/remove current track to/from an existing playlist.",{add:bool("true add, false remove"),playlist_name:string("Target playlist name"),more_actions_pending:pending},["playlist_name"]),
  tool("create_playlist_from_tracks","Create a real NetEase playlist and verify every explicitly requested song was added. No recommendations. This song requires actual current-track evidence.",{playlist_name:string("Exact name user requested"),tracks:requestedTracks,more_actions_pending:pending},["playlist_name","tracks"]),
];
