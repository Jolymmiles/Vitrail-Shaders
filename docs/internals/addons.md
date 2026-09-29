# Add-ons

Another mod can feed the pack through `dev.vitrail.api` instead of mixing into Vitrail's own
classes. This page is how Vitrail keeps that package's promises; what an add-on may rely on is the
package's javadoc.

## Finding and registering add-ons

An add-on implements `VitrailAddon`. On Fabric it is declared under the entrypoint `"vitrail"`, on
NeoForge as a `META-INF/services/dev.vitrail.api.VitrailAddon` provider. The loader module lists
them (`VitrailPlatform.addons()`), each created on its own so that one whose class fails to load
costs only itself, and `Vitrail.initClient` hands the list to `AddonRegistry.load`.

`AddonRegistry` takes an add-on whole or not at all: what it registers is gathered while its
`register` runs and joined to the lists only when it returns. A linkage error counts as the
add-on's failure, because that is what an add-on built against another version of this package
throws. Every later call into an add-on goes through `AddonRegistry.each` or `call`: a piece that
throws is cut off for the session, logged once with the add-on's id, and the others still run.

## Defines

A `DefineSource` writes into a map of its own, and `AddonDefines.gather` folds the maps into one:
add-on order, then registration order within an add-on. The map is the machine's `addonDefines`,
built by `PackDefines.gather` beside everything else the machine is, and `EngineDefines.table`
appends it after the biome and category symbols. That is the whole of the wiring, and it is enough
because every reader takes the table as `table(machine())`: the preprocessor's liveness
(`SettingSet`, `DimensionSet`), the key a kept opening is held under, the key a translation is filed
under and the `#define` lines the translator writes back are one table, so an add-on's define is in
each of them from the moment it is in that one. Its position is last and its order is the order it
was gathered, because the translation key feeds the table in its own order.

**What an add-on may not pose is settled where the map is folded, and it is wider than the table.**
`DefineNames.reservedIn` refuses the names the machine's table holds, the names it would hold with
each capability it can withhold present, and every member of the four families the machine picks
one of (`MC_OS_`, `MC_GL_VENDOR_`, `MC_GL_RENDERER_`, `MC_TEXTURE_FORMAT_`). The wider set is the
point: `DISTANT_HORIZONS` and `IRIS_FEATURE_PER_BUFFER_BLENDING` mean something by being absent, and
an add-on that could pose them where the engine does not would make the absence a lie. A name the
preprocessor would not read, or a value with a line break in it, is refused as well, because the
translator writes `#define NAME value` on one line and a break would put the rest of the value into
the program. Between add-ons the first to pose a name keeps it, unless the values agree. Each
refusal is logged once per add-on and name for the session, since the defines are gathered at every
load. `EngineDefines.table` still puts an add-on's define only where the name is free, so an
environment built by hand keeps the same promise without going through the fold.

**A source whose defines change reloads the pack, and it says so with `revision()`.** `settle`
records every source's revision and whether it has been cut off, `stale` compares them, and
`PackChoice.reloadIfTheWorldMoved` reads again on a difference, before which `PackDefines.install`
takes the new table: the order in `PackChoice.load`, install before `OpenedPack.open`, is untouched
and still what keeps the expander's copy of the table and the translator's the same. As with the
far terrain, what is recorded is the live answer at settle and not the one the read compiled with,
so a change inside the half second of a read waits for the next one. A source that throws, in
`write` or in `revision`, is cut off, its defines leave the table and its recorded state changes, so
the pack is read once more without them and settles on that. Nothing is watched before the first
settle: nothing has been read against the sources yet.

## Pack sources

The one door into a pack is `ShaderPackSource.open`, and the registry's patchers are laid over the
pack there, so that the settings screen, the loader, the kept opening and the per-program openings
of the family worker all read the same pack. Nothing above it knows a file was added or changed, and
nothing is written back.

**Patchers are called from more than one thread.** Openings are not confined to the render thread:
the workers that read the rest of a pack's programs while the world is played open the archive
themselves, so two openings can run at once and a patcher's methods can overlap. Everything one
opening keeps (the patched lines, the added files, the applying set) belongs to that opening's
thread alone, and what is shared between openings is the hash memory, which is synchronised, and the
log of refusals already said.

**An opening settles which patchers apply, once.** With no patcher registered it does nothing. With
one it builds the `PackIdentity`, asks each `appliesTo`, then takes `addedFiles` and the first
`fingerprint` of the ones that do. The set is fixed for the opening, which a kept opening carries
across loads: whatever can change between two loads belongs in `fingerprint`, not in `appliesTo`.
`SourcePatches` holds these decisions.

**The identity hash is worked out only for an opening that has a patcher to give it to, and only
when the pack has moved.** It is SHA-256 over every file under `shaders/` that lands inside the pack,
each as its path from the shaders root and the digest of its bytes, in path order, so a folder, a
zip of it and a zip packed one folder down agree. A load opens one pack dozens of times, and
hashing reads every texture, so `PackHash` remembers the last few packs by a cheap stamp (the
archive's size and time for a zip, every file's for a folder) and reads the bytes again only when
the stamp differs. An edit that keeps a file's size and time is missed, as it is by `KeptPack`.

**A patch is applied where lines are made, once per file.** `readLines` decodes a file, hands the
lines to `SourcePatches.apply` and memoises what comes back, so `IncludeExpander`, `ShaderProperties`,
`PropertiesFile`, `PackLang` and `SourceMentions` all see the patched lines and an include expands
what the patch made. The patchers run in add-on order, each on the last one's answer, and the path
they are given is the file's path from the pack's root, `shaders/lib/settings.glsl`. Lines holding a
line break are split, since everything downstream takes an element for a line. `searchableText`
does not go through `readLines` and is not memoised, so a file it reads is patched by the same call:
`apply` remembers the files a patch changed, and whichever of the two asks first, the other is given
the same lines and the patcher is not asked twice. What a mention scan searches for a patched file
is those lines joined again, which is decoded as UTF-8 where the raw bytes are read as Latin-1 and
cannot change where an ASCII name is found; a file no patch changed is searched as the bytes it is.
`bytes`, `head` and `size` read images and raw data, which a patch of lines does not describe, so
they are only taught about added files.

**An added file is a path under the shader root with nothing stored at it, and every lookup says it
is there.** The path is `shadersRoot.resolve(relative)`, the same kind of `Path` as the pack's own,
so `rel` and everything that takes a `Path` work unchanged. `resolveAgainst` accepts it before it
asks the disk, which covers `resolveInsideShaders`, `resolveRelativeTo` and `file`, and asks for it
ignoring case after the pack's own files have had their turn. `sourceFiles` and `otherFiles` list it
by its extension, in the fixed order, and `ProgramSet.enumerate` walks `sourceFiles`.
`topLevelDirectories` names the directory an added file stands in, since a dimension is a directory
and an add-on may bring the first file of one. Added files are not patched, and they count against
the text total when first read.

**Refused added paths are logged once per add-on, pack and path.** A path that does not begin with
`shaders/`, or holds an empty or dot segment, a backslash or a NUL, is not a path under the shaders.
A path the pack has anything at, file or folder, is a patch and not an addition. A path that would
land outside the pack, through a link in a folder pack, fails the same `landsInside` every lookup
passes. A file past the ceiling a source of the pack has is refused, and the 64 MiB text cap counts
it like any other. A second add-on adding a path the first did is refused.

**A kept opening is served only while the patches it was made under still stand.** The held
opening's applying patchers are asked for their `fingerprint` at every load and compared with the
ones recorded when it was opened (`ShaderPackSource.patchesMoved`), beside the checks `KeptPack`
already makes, and the units it flattened are dropped with the opening when they differ. The
patchers are asked of the opening rather than folded into `Key` because which of them apply is
decided by the pack's identity, and when the files have not moved, which the check before has just
said, the identity is the opening's own: working out a new one would mean mounting the archive, which
is what a kept opening is there to avoid. A patcher cut off after the opening's fingerprint was taken
stands as such in the next one and ends the hold, since the opening then holds some files patched
and some as they were.

**A patcher that misbehaves is cut off, and what it leaves behind is a readable pack.** Every call
goes through `AddonRegistry.call`, and a null answer or a null in a list of lines counts as a throw.
A patcher cut off while listing its files adds none of them; one cut off while patching leaves the
files read after that as the pack wrote them; one cut off before the reading starts is left out of
the whole opening, which is then as good as any and is held as any is.

## Images

## Frame stages

## Far terrain

## Terrain meshes

An add-on that builds something out of the terrain, an acceleration structure for a ray tracer being
the case this was written for, needs two things of Sodium: the geometry it meshed, and a few
elements in every vertex that the compact one does not carry. Both are `TerrainMeshListener`, and
they are two mechanisms that meet in the layout.

### Attributes an add-on forces into the vertex

`attributes()` is a set of `TerrainAttribute`, the four names a pack reads (`mc_Entity`,
`mc_midTexCoord`, `at_midBlock`, and `at_tangent` with the normal). The union over every listener is
what the mesh carries beside what the pack reads. `TerrainAttributes.forced` reads it once and keeps
it, because two places answer with it and have to agree for the whole session: the pack's chunk
programs are translated against a mesh that includes it, and the mesh is built from it. It is read
where the first of those two asks, which is after every add-on has registered: the loader registers
them in its client entry point, before the pack is read and long before Sodium builds its renderer.
An empty registry is not kept, so a read that came too early cannot fix the answer at nothing.

**It is counted in twice, and only the two together are consistent.** The vertex stage of a
translated program declares exactly the elements of the mesh it will be drawn against, and
`TerrainProgram.carries` puts a pack away whose declared list differs from the format the renderer
bound. A forced element the programs did not declare would therefore not add a word to the mesh: it
would put the whole pack away, and where it sits before an element the pack reads it would first
shift that element's location in silence, the failure [vertex inputs are matched by
name](terrain.md#vertex-inputs-are-matched-by-name-and-one-direction-is-silent) describes. So the
forced elements go into `PackProgram.loadTerrain` and are unioned with what the six programs read,
and all six declare them; and `TerrainMesh.settle` unions the same set into what the pack published,
which builds the format and is the only thing that does where no pack draws the terrain.
`TerrainLayout.withForced` is where that second union is written. The translation cache is keyed on
the bound elements, so a program translated without a forced element and one translated with it
never share an entry.

Where no pack's terrain program is drawing, the mesh is Sodium's own four elements and the forced
ones, and Sodium's own chunk shader draws through it as it draws through any extended format: it
declares the four it knows and the new elements come last. `BLOCK_ID` is then every block's unmapped
value, there being no `block.properties`, with the fluid bit still set on a fluid's quads. The
stride changes with the set and the world is built again, as it is for a pack that reads another
element: the format is taken at the head of `initRenderer` and nowhere else, and `TerrainDraw` asks
for a rebuild whenever the list the programs declare moves.

A forced element that no program reads is declared by all six and read by none, the standing an
element one of the six leaves to the others already has. Whether the compiled module keeps such an
input, on which the location of everything after it depends, is what the off-game harness measures
for the elements the corpus leaves unread. It has not been measured for one that every program
leaves unread.
