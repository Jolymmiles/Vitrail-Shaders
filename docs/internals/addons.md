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

## Images

## Frame stages

## Far terrain

## Terrain meshes
