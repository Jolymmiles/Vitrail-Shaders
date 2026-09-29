package dev.vitrail.api;

/**
 * Where an add-on puts what it provides, only while {@link VitrailAddon#register} runs.
 * <p>
 * Each kind may be registered any number of times. Pieces of one kind are called in the order
 * the add-ons were found and, within one add-on, in the order they were registered.
 */
public interface AddonRegistrar {

	/** Defines posted to every program of every pack. */
	void defines(DefineSource source);

	/** Images served to pack programs under names of the add-on's choosing. */
	void images(ImageSource source);

	/** Files added to a pack, and changes to the pack's own files, as it is read. */
	void sources(SourcePatcher patcher);

	/** Work recorded into the frame's command buffer at fixed points of the pack's frame. */
	void stages(StageListener listener);

	/** Far terrain drawn by the pack's {@code dh_*} programs. */
	void distant(DistantTerrainSource source);

	/** A copy of the terrain meshes as Sodium builds them, and the attributes they must carry. */
	void terrain(TerrainMeshListener listener);
}
