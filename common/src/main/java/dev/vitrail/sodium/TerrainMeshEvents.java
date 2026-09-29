package dev.vitrail.sodium;

import dev.vitrail.Vitrail;
import dev.vitrail.addon.AddonRegistry;
import dev.vitrail.api.TerrainMeshListener;
import dev.vitrail.api.TerrainPass;
import dev.vitrail.api.TerrainSection;
import dev.vitrail.api.TerrainSectionMesh;
import dev.vitrail.api.TerrainVertexLayout;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What the add-ons are told about the terrain Sodium builds: a section's meshes as they are handed
 * over for upload, and the sections it drops.
 * <p>
 * <strong>Taken where Sodium hands the section over and nowhere earlier.</strong> The head of
 * {@code RenderRegionManager.uploadResults} is the one point that sees exactly what is about to be
 * uploaded: {@code applyBuildOutputs} has already thrown away the outputs of a section that was
 * removed meanwhile and kept only the newest of two for one section, so an output here is one that
 * will reach the arena, and nothing reaches the arena that does not pass here. The build's own
 * end, where the mesh is finished, sees outputs that are then discarded, and a copy taken there
 * would hold sections that were never drawn.
 * <p>
 * <strong>An output replaces its section whole.</strong> {@code uploadResults} removes the section's
 * vertex data from all three passes before it uploads what the output holds, so a pass the output
 * has no mesh for is a pass that no longer has any, and an output with no mesh at all is a section
 * that became empty. That is reported as a built section with an empty list of meshes and not as a
 * removal, because the section is still there: Sodium keeps it, and its next build will arrive as
 * one more {@code built}.
 * <p>
 * <strong>Every call into an add-on is guarded and none of this is allowed to fail Sodium.</strong>
 * The calls go through {@link AddonRegistry}, which cuts off an add-on that throws. What is built
 * here to hand over is guarded by this class instead, since a section that could not be described
 * is this engine's failure and is not a reason to cut an add-on off.
 * <p>
 * Nothing is done where no add-on registered a listener: the registry is asked and the answer is
 * empty.
 */
public final class TerrainMeshEvents {

	/** Set once a section could not be described, so that the log says it once and not per section. */
	private static boolean refused;

	private TerrainMeshEvents() {
	}

	/**
	 * Hands every built section of a batch of outputs to the listeners. Called at the head of
	 * {@code uploadResults}, before anything is uploaded and before Sodium frees the buffers.
	 */
	public static void built(Collection<BuilderTaskOutput> results) {
		List<AddonRegistry.Entry<TerrainMeshListener>> listeners = AddonRegistry.terrain();
		if (listeners.isEmpty()) {
			return;
		}

		TerrainVertexLayout layout = TerrainMesh.layout();
		int stride = ChunkMeshFormats.getCurrent().getVertexFormat().getVertexSize();
		if (layout.stride() != stride) {
			// The format in force is not one this engine laid out, so the bytes cannot be described.
			// It is what another mod substituting the format would look like, and a wrong description
			// would be built into an acceleration structure without anything saying so.
			refuse("The chunk mesh is " + stride + " bytes a vertex and this engine laid out "
					+ layout.stride() + ", so no terrain mesh is handed to add-ons", null);

			return;
		}

		for (BuilderTaskOutput result : results) {
			// The sorter's outputs carry an index buffer and no vertices, so they are not a copy of
			// anything.
			if (result instanceof ChunkBuildOutput output) {
				hand(listeners, output, layout);
			}
		}
	}

	/** Tells the listeners a section is gone, in section coordinates. */
	public static void removed(int x, int y, int z) {
		List<AddonRegistry.Entry<TerrainMeshListener>> listeners = AddonRegistry.terrain();
		if (listeners.isEmpty()) {
			return;
		}

		AddonRegistry.each(listeners, "take the removal of a terrain section",
				listener -> listener.removed(x, y, z));
	}

	/**
	 * One section to every listener, each with views of its own.
	 * <p>
	 * A view per listener and not one shared, because a buffer's position and byte order are state:
	 * an add-on that reads relatively or sets an order would otherwise hand the next add-on a buffer
	 * in the place it left it.
	 */
	private static void hand(List<AddonRegistry.Entry<TerrainMeshListener>> listeners,
			ChunkBuildOutput output, TerrainVertexLayout layout) {
		for (AddonRegistry.Entry<TerrainMeshListener> entry : listeners) {
			if (entry.cutOff()) {
				continue;
			}

			TerrainSection section = describe(output, layout);
			if (section == null) {
				return;
			}

			AddonRegistry.call(entry, "take a built terrain section", listener -> listener.built(section));
		}
	}

	private static @Nullable TerrainSection describe(ChunkBuildOutput output, TerrainVertexLayout layout) {
		try {
			// By identity and in a fixed order, which is how Sodium itself tells the passes apart. A
			// pass it does not know is not one of these three and is not uploaded either: the upload
			// walks the same three.
			List<TerrainSectionMesh> meshes = new ArrayList<>(3);
			mesh(meshes, output.getMesh(DefaultTerrainRenderPasses.SOLID), TerrainPass.SOLID, layout);
			mesh(meshes, output.getMesh(DefaultTerrainRenderPasses.CUTOUT), TerrainPass.CUTOUT, layout);
			mesh(meshes, output.getMesh(DefaultTerrainRenderPasses.TRANSLUCENT),
					TerrainPass.TRANSLUCENT, layout);

			return new TerrainSection(output.section.getChunkX(), output.section.getChunkY(),
					output.section.getChunkZ(), meshes);
		} catch (RuntimeException failure) {
			refuse("Could not describe the terrain section at " + output.section
					+ " to add-ons, so none is handed any", failure);

			return null;
		}
	}

	private static void mesh(List<TerrainSectionMesh> into, @Nullable BuiltSectionMeshParts parts,
			TerrainPass pass, TerrainVertexLayout layout) {
		if (parts == null) {
			return;
		}

		NativeBuffer data = parts.getVertexData();
		int length = data.getLength();
		if (length == 0) {
			return;
		}

		// A quad is four vertices and nothing else is ever pushed, so a length that is not whole
		// quads is a buffer this engine does not understand and not one to round down.
		if (length % (layout.stride() * 4) != 0) {
			throw new IllegalStateException(length + " bytes is not a whole number of quads at "
					+ layout.stride() + " bytes a vertex");
		}

		// Read-only, because the memory is Sodium's and goes back to it right after; and in the order
		// the encoder wrote its words in, which is the platform's. A duplicate of any byte buffer
		// starts big endian whatever it was cut from, so the order is set on this one.
		ByteBuffer view = data.getDirectBuffer().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
		into.add(new TerrainSectionMesh(pass, view, length / layout.stride(), layout));
	}

	private static void refuse(String message, @Nullable Throwable failure) {
		if (refused) {
			return;
		}

		refused = true;
		Vitrail.logger().error(message, failure);
	}
}
