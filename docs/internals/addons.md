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

**Most calls are on the render thread, and the first reading of the pack is the exception.** The
pack is first read on a loader worker while the game starts, so what decides what it is told is
asked there: `DefineSource.write` and `revision`, `DistantTerrainSource.attach` and the first
`present`, and the first ask of `ImageSource.names` and `TerrainMeshListener.attributes` (the last
two are on the render thread instead when no pack is read before them). Every later call of those,
and every call of the other pieces, is on the render thread; the patchers are the one piece that
is asked from any thread.

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

An `ImageSource` serves images to the pack's programs by name. The pack declares the name itself,
`uniform sampler2D name;` to read it or `layout(rgba8) uniform image2D name;` to store into it, and
the declaration is normally put there by the add-on's `SourcePatcher`. Which of the two it is comes
from that declaration and from nothing the add-on says.

**Who owns a name.** `AddonImageNames` reads every source's `names()` once, through
`AddonRegistry.call`, on the first question after registration, and answers the rest from a map: the
descriptor push asks for every descriptor of every pass of the game, so the answer to "is this name
an add-on's" has to be a map read. A name is served only when nobody else gives it a meaning.
Refused, with one warning and no effect: every name `SamplerPlan.builtIn` knows (colour targets,
`colorimgN`, the depths, the shadow map and its colours, `noisetex`, the far terrain's depth, the
names the translation forges), the names the geometry passes bind themselves (the atlas spellings,
`lightmap`, the overlay, the PBR maps), the pack's own `image.` names (`CustomImages.named`) and the
textures the pack ships (`PackTextures.supplied`, recorded by `PackProgram.textures` as each program
is read). Of two claims on one name the source registered first keeps it. A source whose `names()`
throws is cut off and claims nothing.

**How a name is classified.** `SamplerPlan.Kind.ADDON` sits after every built-in answer in
`SamplerPlan.classify`, so the pack's images and shipped files, which the longer overloads settle
first, keep their names. It exists because `UNSERVED` is wrong twice over: a full screen program
gives an unserved `sampler2D` the scene (`takesDefault`), which would let a name the add-on serves
read `colortex0` on the frames the add-on has nothing, and the chain's log would list the name as one
the engine has no answer for. What a pass binds first for such a name is only a placeholder (the one
black texel every `UNSERVED` name gets); the real answer is swapped in at the push.

**The push, graphics.** `PushedDescriptor.begin` (a whole file per game, beside the game's bind
group entry on 26.2 and the uniform's name on 26.3) already asked `StorageImages.bound` and
`StorageBuffers.bound` once per entry. When the pack has no image of that name it now asks
`AddonImages.bound`, which comes back in the same `StorageImages.Bound` shape, so that
`VulkanRenderPassMixin` swaps the view, drops the sampler for a storage binding and rewrites the
descriptor type exactly as it does for the pack's own images, and no second road exists. The source
is asked at every push of the name and not once a frame, because a push is the one moment known to
be inside the pass that will read the image. For everyone with no add-on that serves images the whole
third lookup is one volatile read.

**The push, compute.** `PackCompute.pushDescriptors` asks the same `AddonImages.bound` where it asks
`StorageImages.bound`, and takes the answer down the same road: a storage binding is a storage image
descriptor, anything else a combined image sampler. The compute's sampler is the nearest, clamped
one `samplerFor` gives a name no image directive declared.

**The bind group layout.** The layout of a pipeline is made from names, before any image exists and
before the add-on has been asked for one, and a descriptor whose type disagrees with its layout is
not a wrong picture but a lost device. So the storage type cannot come from `AddonImage.storage`,
which is per frame and per image. It comes from the shader: `PackProgram.Loaded` records every
uniform of an image type it carries (`AddonImageNames.declaredAsImage`), and the three places that
type a layout ask `AddonImageNames.storageBinding`, which is true for a served name some program
declares as an image: `VulkanBindGroupLayoutMixin` on 26.2, `VulkanRenderPipelineMixin` on 26.3 and
`PackCompute.createLayout`. The record only grows, because a release landing after the next pack's
first program would erase that pack's; the cost is that an add-on has to declare one name the same
way in every pack, which the patcher that writes the declaration does anyway.

**What a name with no image reads.** `AddonImages.choose` binds the image the source handed over
where the shader can use it as declared, and otherwise a stand-in the size of the screen: opaque
black for a sampled name, and for a storage name a scratch image the pack may write and nothing
reads. Never the placeholder texel: packs read these names with `texelFetch` at any pixel, and a
texel that is not there reads whatever the driver likes. The two stand-ins are `AddonStandIns`,
owned by `ColorTargets`, allocated in `ensure` only while some add-on claims an image name (and the
scratch only while a program declares one as a storage image), cleared with the constants, sized
with the screen and released with the targets. They are separate images because a store into a
shared one would turn every later read of nothing into whatever the pack wrote. The scratch is
RGBA8; a shader whose layout qualifier says another format stores into it reinterpreted, which only
happens on a frame the source served nothing for a storage name, and the bytes are read by nobody. A
storage answer whose image is not `storage`, or one with no view or no extent, is refused the same
way, with one warning per name.

**The layout an image must be in.** `VK_IMAGE_LAYOUT_GENERAL`, sampled or stored, always. Measured
on both games' backends: `VulkanRenderPass.pushDescriptors` writes `imageLayout(1)` for every
combined image sampler, the pass attachments are `GENERAL` too, `VulkanGpuTexture` moves a new image
from `UNDEFINED` to `GENERAL` in its constructor, `StorageImages` does the same for the pack's
images, and `PackCompute` writes `GENERAL` for every image it pushes. Nothing binds
`SHADER_READ_ONLY_OPTIMAL`, and no barrier Vitrail records changes a layout, so an add-on image in
that layout is a mismatch with its own descriptor. The add-on moves the image to `GENERAL` when it
creates it and leaves it there; the javadoc of `ImageSource` and `AddonImage` says so as the
contract. Memory is covered without the add-on: what it wrote in a stage is made visible to the
passes after it by the barrier recorded after the listeners (see Frame stages), and what it wrote in
an earlier frame by the game's barrier after every pass. The image is sampled nearest and clamped,
like the pack's other images without a directive, and has to stay alive until the last frame that
bound it has left the GPU, which the backend keeps two of in flight.

Not done, on purpose: three dimensional images (`AddonImage` has no depth, and a `sampler3D`
declaration stays refused as unbindable), mip chains, filtered sampling, and any answer that depends
on which pass asks.

## Frame stages

A `StageListener` records into the frame's command buffer at fixed points of the pack's frame. There
are two. `BEFORE_DEFERRED` is called from `PackChain.drawEarly` immediately before
`drawRange(..., Cut.BEFORE_TRANSLUCENTS)`, which runs the deferred passes. By then `takeOpaque` has
copied `depthtex1`, the centre depth and the motion vectors have been drawn, and none of the passes
those calls opened is left open. `AFTER_PROGRAM` is called from inside `drawRange`, after a program
a listener named, and has a section of its own below. Nothing runs where no listener is registered:
`AddonStages.wanted` is `AddonRegistry.any()`, which allocates nothing and is false for a player with
no add-on, then the stage list.

**Getting a buffer that records.** `AddonStages.record`, the one road both stages take, does what
`PackCompute` does before it records outside a pass, in the same order: `GeometryHold.flush`, which
ends the pass object the geometry keeps open across draws (ending the pass under it would leave that
object to close a pass the encoder no longer has); `GpuRecording.endPass`; and then the encoder's
backend and its command buffer through `CommandEncoderAccessor` and `VulkanCommandEncoderAccessor`.
`BEFORE_DEFERRED` also calls `GraphicsApi.suspendLevelPass`, which ends the level's pass on 26.3 and
does nothing on 26.2; `AFTER_PROGRAM` does not, and the reason is under it. Between the last two it
pays `ColorTargets.flushPending`: a target still owed its clear is emptied by the load-op of the
first pass that attaches it, which would erase what a listener wrote there and hand a listener the
frame before's image to read.

**What the listener leaves behind.** Nothing has to be put back, and the reasons are read off the
game's backend on both versions. Every render pass sets its own viewport and scissor when it is
made, binds its pipeline in `setPipeline` and marks its descriptors dirty there, and pushes them
before each draw; every dispatch of the pack binds its own compute pipeline and pushes its own
descriptors. So a pipeline, a descriptor set or a piece of dynamic state a listener left bound is
never read by anything of Vitrail's. What would be read is memory and layouts. Memory is Vitrail's:
the game's own barrier (all commands, memory read and write) is recorded before the listeners and
again after them, which is what the game records after each of its own passes and what the compute
dispatch records before its own. Layouts are the listener's: every image it touches ends in
`GENERAL`, as everything else in the frame is. A listener may not begin or end a pass, leave a debug
label open, submit or reset the buffer; a pass it opened through the game's encoder anyway is ended
after it, because the next pass cannot open over one. A listener that throws is cut off by
`AddonRegistry.each` and the frame goes on with the barrier recorded and any pass ended, which is
what the `finally` in `record` is for.

**`FrameContext`.** It is made for one call and closed when the call returns, and asking a closed one
for the command buffer or a target throws, so that a stale buffer is an exception in the add-on and
not a device loss. `frame()` counts frames drawn: `FrameClock` in `AddonStages`, one number per
session that starts at 0 and does not wrap, because the pack's `frameCounter` does both and starts
over with a load. The clock is read once for a frame and not once for a stage: the first stage the
chain calls in a frame takes the number (`PackChain.stageFrame`), the others of the frame are handed
the same one, and `closeFrame` lowers it with every other per frame flag. `program()` is the
program `AFTER_PROGRAM` follows, and null anywhere else. `vulkan()` is read off the game's device once and kept while the device is the same
object: every field is a public member of `VulkanDevice` (`instance().vkInstance()`,
`vkDevice().getPhysicalDevice()`, `vkDevice()`, `vma()`, `graphicsQueue().vkQueue()` and
`queueFamilyIndex()`), so no accessor was needed and the mixin lists are unchanged.

**Which target `target(name)` answers.** `TargetRef` accepts `colortex0` to `colortex15` and
`depthtex0` to `depthtex2`, spelled exactly; the legacy names are the pack's business.
`AddonImage` carries the `VkImageView`, the `VkImage`, the `VkFormat` (`VulkanConst.toVk`), the size
of the base level and whether the surface was created storable. A colour target the pack turns over
has two halves, and the one handed over is the half the next pass of the chain reads: the step
of that pass, read through `Bound.read(index)`. At `BEFORE_DEFERRED` that pass is
`programs.get(world)`, and the half is the one the opaque geometry wrote, moved by whatever
`flip.deferred_pre` the pack states, so what a listener writes is what the deferred passes read
first. Where the chain has no pass left, or the pass is the final, which has no step of its own and
reads the half the frame ends on, the half is `flippedAtEnd`'s: the main one unless the pack turns
the target over at the end. For a storable target the view is the base level alone, the one a
storage descriptor takes; otherwise the whole chain. The depths are the images the pass before the
stage read under those names, settled by the rule a pass binds them by (`PackPass.depth`) and not
by a second one: `depthtex0` is the depth the range hands its passes, `depthtex1` the opaque copy
`takeOpaque` made, and `depthtex2` the copy from before the hand where one was taken and the opaque
one where not, each falling back to the range's depth while nothing has filled it. At
`BEFORE_DEFERRED` that range's depth is the opaque copy, so all three are what the deferred passes
bind. Null while an image has not been written, and never the one-texel constant a pass falls back
to: a coordinate past that texel is not an image an add-on can use.

### After a named program

**The case it was written for.** A ray tracer's add-on patches a pack, through a `SourcePatcher`, so
that one of the pack's own programs (the bridge, say a `deferred3` the patch adds) writes a summary
of the G-buffer into an image the add-on owns. The add-on then has to run its own GPU work right
after THAT program and before the next one reads what it produces. `BEFORE_DEFERRED` is one moment
of the frame and cannot say which. `FrameStage.AFTER_PROGRAM` is called after every program a
listener names, `FrameContext.program()` says which one it was, and
`StageListener.programs()` is how a listener names them.

**A name is the pack's own, and one program has one name.** `StagePrograms.nameOf` is
`ProgramNames.computeBase`, the mapping the chain already places computes by, so the two cannot
disagree: the families `drawRange` runs are `begin`, `prepare`, `deferred`, `composite` and `final`,
numbered as the pack numbers them, and `deferred0` is `deferred`. A compute file names the program
it hangs off, `composite3_a` is `composite3`, because that is the moment its work belongs to.
Geometry, `shadow`, `shadowcomp` and `setup` programs are not among them: none is a pass the walk
runs, and a name that comes to none of the five families is logged with the add-on's id and never
called.

**The answer is settled once per load, and the frame only looks it up.** `PackChain.build`, where
the passes are made, asks every stage listener for `programs()` through `AddonRegistry.call`
(`StagePrograms.settle`), so a listener that throws or answers null is cut off there like anywhere
else. What is kept is the names the chain runs: the programs of its passes and of its standalone
computes. A name the pack does not run is logged at info with the pack's name and dropped, which is
what an add-on sees when its patch did not apply. The result is a map from program to the listeners
that named it, in registry order, and it is empty for a player with no listener naming a program,
which is then the whole cost: the walk's one question, `StagePrograms.wants(pass.program())`, answers
false on an empty map before it looks at the name. A listener cut off later leaves `wants` false when
it was the last one for a program, so a cut-off add-on costs no barriers either. A load asks again,
because a load builds again; a frame never does.

**Where it is called.** From the two places `drawRange` runs a program. After a pass, once its
`draw` or `drawFinal` has returned and the walk has taken it out of the chains it kept, and so after
its computes as well: the computes hanging off a pass are dispatched before the pass, as Iris
runs them, so "after the program's computes" and "after its pass" are one moment. And after
`dispatchAlone` for a program that has a compute file and no pass, which is all it runs. Every cut
walks through the same code, so the begins are reached from the head of the frame, the prepares
from behind the shadow stage, the deferred passes from `drawEarly` and the composites and the final
from `run`, and all of them under one rule. It is not called for the seed, which is not a program.

**What is true when it is called.** Everything `BEFORE_DEFERRED` promises, by the same code: no pass
open, the full barrier before and after, the owed clears paid. The last is needed here as much as
there: a pass pays every pending clear when it opens, but a program that is only a compute has paid
none, and a listener that writes a target must not have the next pass's load-op empty it. The walk
forgets which chains it has filled after a listener returns, as it does after a compute, because a
listener may have written a target a chain was built from.

**Which halves it hands over.** The next pass that DRAWS: `programs.get(at + 1)` after a pass, and
the pass the compute was placed before, `Standalone.at`, after a compute-only program. A compute-only
program standing between the two is not counted, since it has no half of its own to hand over.
`FrameContext`'s section says what the depths are and why.

**The level's pass is not suspended, on either game.** On 26.3 the whole level is drawn through one
pass, and a stage that records outside a pass has to be sure it is not open. Nothing a program can
be called from has it open: `frameGraphSetup` builds the frame graph, which is before any pass of it
runs; `afterOpaqueFeatures` suspends the pass before it calls `drawBeforeTranslucents`; and
`afterLevel` runs after the level has closed it. Inside a range nothing opens it again, since only a
call the game makes on it does and no such call is made between two programs, and every pass the
walk opens goes through `createRenderPass`, which suspends it first (`CommandEncoderMixin`). The
walk's own computes record outside a pass on that footing without suspending anything, and a
stage is a compute's twin. `BEFORE_DEFERRED` keeps its call, which on 26.3 is a null check and on
26.2 nothing. On 26.2 every phase closes its own pass and there is nothing to suspend.

**Order within a frame.** The stages run in the order of the frame: the `AFTER_PROGRAM` of the begins
and the prepares, then `BEFORE_DEFERRED`, then the `AFTER_PROGRAM` of the deferred programs, the
composites and the final, all under one `frame()` number.

## Far terrain

A far terrain source (`DistantTerrainSource`) is another mod's far terrain drawn in the place
Distant Horizons' is: with the pack's own `dh_terrain`, `dh_water` and `dh_shadow`, under a pack
told `DISTANT_HORIZONS` is defined, its `dhDepthTex` names filled from what was drawn. Nothing about
the pack side is new. What is new is who supplies the sections, and the whole design is the rule
that the add-on owns the terrain and Vitrail owns the GPU.

**Distant Horizons wins whenever it is present, and then nothing new runs.** `FarSources.select`
takes `DhDepth.present()` as an argument and answers null for it, so DH stays the source exactly as
before, with its reflective reads and its proxy over `IDhTerrainRenderer`. Without it the first
source that says `present()` is the one, in the order the add-ons were found, and the ones after
it are not asked. A session with no add-on source pays one list-is-empty read in each place the
facade stands, and reaches no add-on code.

**`render/DistantTerrain` is the facade the engine reads instead of `DhDepth` and `DhLods`.**
`PackDefines` asks it `present()`, `DistantProgram.warmAhead` asks `drawable()`, `PackChain` routes
`install` and `handBack` through it, and `FrameState` takes its reads from a `Reading`. DH's reading
is `DhDepth`'s calls in their old order. The FrameState rule that the distance, the z row and the
"still readable" flag are taken together and published together is kept by settling ONE source per
frame: an add-on source is cut off by a throw exactly where DH latches off, and `coherent()` is how
a frame notices that either happened between the reads. The window an add-on gives (near and far,
in blocks) is turned into the z row DH's matrix would have carried, `(near / (far - near), near *
far / (far - near))`, and FrameState takes the planes back out of the row with `DhDepth.planes`, the
arithmetic that undoes it. So every consumer downstream of the row is the code DH already ran. One
difference is a gain and not a divergence: DH's planes are the previous frame's, and an add-on's
are read the frame they are used in.

**The geometry is copied once and lives in buffers Vitrail makes.** The add-on hands `upload` a
vertex and an index buffer in DH's 16-byte layout (documented on `DistantMeshes`) and gets a handle.
`MeshTable` keeps the bookkeeping and has no device: any thread may `upload` and `free`, and both
only put a handle on a queue. The copy is made at the call, into direct memory the handle owns, so
the caller reuses its buffers at once and a handle that is dropped can never leak native memory;
the indices are checked against the vertex count in that same pass, because a read past a vertex
buffer on the GPU ends in a lost device that names nobody. The render thread calls `drain` at the
head of the frame, from `EngineStages.frameGraphSetup` where no pass is open (the same place
`PbrTextures.load` records its uploads), and copies up to 16 MiB of meshes with
`GpuDevice.createBuffer(label, usage, data)`, which is a staging copy into the frame's command
buffer. The budget is checked after each mesh so one larger than 16 MiB still goes by itself. The
transfer ends a held geometry pass through `CommandEncoderMixin`, so it belongs at the head of the
frame and not inside it. A mesh that is not on the GPU yet is left out of the lists rather than
waited for, which is what `ready()` lets an add-on replace a tile without a hole.

**Freeing rides the game's own retirement.** `free` marks the handle and queues it; `drain`
closes the buffers on the render thread at the head of a later frame. Closing a `GpuBuffer` on the
Vulkan backend queues it on the same two-deep destruction queue `GpuRecording.destroyLater` uses,
so it is destroyed after the submissions that may still read it, and the frame that listed a mesh
can still draw it, the light's stage at its tail included. The copies come before the retirements
in `drain`, which closes the race of a free landing in the middle of that mesh's own copy without a
lock. A source that is cut off has everything given back at the next drain, and the session's end
gives back the rest (`PackChain.close`). The meshes are the add-on's and outlive every pack, so a
pack release frees none of them.

**A frame is three draws and a list, and Vitrail hooks the game at one new point.**
`frameGraphSetup` calls `DistantTerrain.beginFrame`, which drains and then asks the source for the
frame's `DistantSections`, only when the pack really draws a far terrain (`DistantDraw.drawsFarTerrain`)
and the source says it is usable. The opaque half is drawn by `EngineStages.beforeOpaqueBlocks`,
the head of the opaque chunk group, which is where DH's own hook draws; the water half at the end
of `afterTranslucentFeatures`, once the translucent layer is composed and copied, which is where
DH's water lands relative to everything a pack reads. Both go through the same `DistantDraw.draw`
door DH's proxy uses, so seeding the world's depth under the water, the takes into `dhDepthTex0`
and `dhDepthTex1`, the program preparation and every refusal are shared. The one new hook is the
stage before the opaque group, a `@WrapOperation` on the first `renderGroup` call of the main pass
(`lambda$addMainPass$0` on 26.2, `executeSolid` on 26.3) in the common `LevelRendererMixin`. It is
common rather than per loader because both loaders reach it the same way: every stage event they
have is an "after". The lambda name and the call's ordinal were read off both the bare and the
NeoForge-patched jars.

**The light gets lists of its own.** DH's sections are the ones its frustum kept for the camera, so
a hill behind the camera casts no shadow in front of it, which `DistantDraw.shadow` documents as a
divergence from Iris. A source is under no such limit: `DistantSections` carries `shadowOpaque` and
`shadowWater`, `DistantDraw.feedShadow` hands them over at the head of the frame, and `rotate` takes
them in place of the camera's at the frame's close. A source that hands only the camera's lists gets
them back for the light, converted once. `DistantFrame.shadows()` says whether the pack draws the
far terrain into its map at all, so a source that builds the lists apart may skip them.

**A source that misbehaves costs itself and nothing else.** Every question goes through
`AddonRegistry.call`, so a throw or a linkage error cuts the source off and its answers fall back to
the ones the frame already has for "no far terrain": not present, no distance, no window, no
sections. A list holding a null, or a window that is not `0 < near < far` and finite, is the
add-on's failure and is handled like the same. A mesh that is not one of the table's own is left
out and said once.

What stays out of this mechanism, because it is not far terrain: the block refinement metalith
hooks into `TerrainDraw`, `ColorTargets.coverage`, `GeometryProgram.descriptor`,
`DistantDraw.record` and `EngineStages.afterOpaqueBlocks`, and any vertex stride but 16.

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

### The copy

`TerrainMeshEvents.built` runs at the head of the public `RenderRegionManager.uploadResults`, on the
render thread, before anything is uploaded. That is the one point that sees exactly what is about to
be uploaded: `processChunkBuildResults` has already had `applyBuildOutputs` discard the output of a
section removed meanwhile and any output older than one already applied, so an output that reaches
`uploadResults` is one that will reach the arena, and nothing reaches the arena without passing
there. The end of the build task sees outputs that are then thrown away. `BuilderTaskOutput.destroy`
frees the buffers right after `processChunkBuilds` finishes, so a listener's views are valid for the
call and no longer, which the API says.

Only `ChunkBuildOutput` is reported. The sorter's other outputs carry an index buffer and no
vertices: reordering the quads of a translucent mesh as the camera moves adds, removes and moves no
vertex, so a copy of positions has no need to hear of it. Each of the three passes is looked up by
identity in `DefaultTerrainRenderPasses`, the way `uploadResults` walks them; a pass a mod added is
not one of the three and is not uploaded either.

The view is `NativeBuffer.getDirectBuffer` made read-only and put in native byte order, the order
the encoder wrote its words in. The order has to be set, because a duplicate of a byte buffer starts
big endian whatever it was cut from. Each listener gets views of its own, since position and byte
order are state and a listener that reads relatively would otherwise hand the next one a buffer in
the place it left it. What cannot be described, a mesh whose length is not whole quads or a format
this engine did not lay out, is logged once and nothing is handed over: it is this engine's failure
and is never charged to the add-on.

**An output replaces its section whole.** `uploadResults` removes the section's vertex data from all
three passes before it uploads what the output holds, so a pass the output has no mesh for is a pass
that has none any more, and a section that became empty is an output with no meshes at all. That is
reported as `built` with an empty list of meshes and not as a removal, because the section is still
there and Sodium keeps it; it is also how Sodium itself handles it, with no case of its own. A
section that is empty when it is created gets no output and no event, and there is nothing to
forget.

**Removal is `RenderSection.delete`**, the one road every section leaves by. A chunk unloading goes
through `onSectionRemoved`, but a renderer torn down, which is every dimension change and every
rebuild of the world, goes through `deleteAll` without it. A section removed while the storage is
queueing stays in it until the queue is flushed, and a teardown in between deletes it a second time,
so the disposed flag is what makes the call once per section. `removed` is made for sections that
never had a mesh as well, and an add-on takes it as "forget this section if it is held".

### What the copy is and is not

**It is the whole mesh, and Sodium does not draw the whole mesh.** At draw time Sodium drops the
groups of quads facing away from where the camera stands (`getVisibleFaces`, when block face culling
is on). The copy holds them, which is what a ray needs and a rasteriser does not.

**It is what is rasterised and no more.** A translucent quad is recorded by the sorter and, unless
the sorter refuses it as degenerate, is still pushed to the vertex buffer, so it is in the copy; a
degenerate one is dropped there and here alike. Where the sorter changed the quads themselves, which
it may do to sort them exactly, `createModifiedTranslucentMesh` builds the buffer out of the changed
quads and that is what is copied. The index data only decides the order the quads are blended in.
The writers of it that were read, the shared quad index buffer and the static topological one, index
whole quads of the buffer, and neither addresses a vertex it does not hold.

**Vertices are not in build order, and a quad is implicit.** A mesh is the buffers Sodium keeps for
each side a quad is filed under, laid end to end. Within a side the quads are in the order the
blocks were meshed, y outermost, then z, then x. Across sides the order is one Sodium chose for
drawing: the quads that face no axis first and then the sides in the order +X, +Y, +Z, -X, -Y, -Z,
or, where it reorders the sides, the ones the camera could see at the time of the build before the
rest, which makes the order depend on where the camera stood. The table that says where a side
starts is not part of the copy, so an add-on takes a pass as an unordered set of quads. A quad is
four consecutive vertices at the layout's stride, in the order the source model gave its corners,
and the renderer draws it as the triangles (0, 1, 2) and (2, 3, 0) of its shared index buffer. The
fluid renderer writes some quads twice, the second with its corners reversed and filed under the
opposite side, for a surface seen from either side.

### The layout

`TerrainLayout` counts where the appended elements sit, and `TerrainMesh` lays its format and its
encoder out from it, so the description an add-on is handed and the bytes are one computation. The
order is `SodiumVertex.ATTRIBUTES`' from the block id on: `BLOCK_ID`, `MID_TEX_COORD`, `MID_BLOCK`,
`TANGENT_FRAME`, and the separated colour, which answers no attribute but takes its word all the
same. The mesh's constructor holds that order against the encoder's and the layout against the
format it built, element by element; a disagreement leaves the terrain on Sodium's own format with
an error in the log, where the alternative is a wrong description in an add-on's hands.
`TerrainMesh.layout` follows `current` and moves at the same instant.

**The compact vertex, checked against Sodium 0.9.2-beta.1 on 26.2 and 0.9.2 on 26.3, whose encoders
are identical.** The position is two words at offset 0 holding, per coordinate,
`(int) ((8 + p) / 32 * 2^20) & 0xFFFFF`, the high ten bits of x, y and z at bits 0, 10 and 20 of the
first word and the low ten in the second; `SodiumVertex.prologue` undoes it with
`(top * 1024 + bottom) * (32 / 2^20) - 8`. The value is cut and not rounded, so a coordinate is
stored to a step of 2^-15 of a block, and a position outside `[-8, 24)` wraps. `p` is measured from
the section's minimum corner: the block renderer adds the block's place in its section, `x & 15`, to
the model's own coordinates and the fluid renderer does the same, so it is neither the region's
corner nor the world's. Light and data at offset 16 are one byte each: block light, sky light,
material bits, and `(x & 7) << 5 | (z & 7) << 2 | (y & 3)` of the section, its place in a region of
8 by 4 by 8, which is a function of the section's coordinates and needed by nobody who has them. The
texture coordinate at offset 12 is two 16-bit halves, u in the low one: the low 15 bits are
`round(uv * 32768)` moved one step towards the middle of the quad's four corners (up by one below the
middle, down by one at or above it, which is what bit 15 marks), as `CompactChunkVertex.encodeTexture`
writes it, and the mean of the four is `MID_TEX_COORD`. The API's javadoc states all of it.
