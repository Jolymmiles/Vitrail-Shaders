package dev.vitrail.render;

import dev.vitrail.addon.MeshTable;
import dev.vitrail.dh.DhLods.Piece;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;

import java.nio.ByteBuffer;

/**
 * The GPU side of a far terrain source's meshes: what {@link MeshTable} calls to copy one mesh into
 * buffers of the game's device and to give them back.
 * <p>
 * The copy is the device's own {@code createBuffer} with data, which records a transfer into the
 * frame's command buffer through a staging block, so it is made on the render thread and outside any
 * pass, at the head of the frame where {@link DistantTerrain#beginFrame} runs. Giving a buffer back
 * is closing it: on this backend that queues the buffer on the same two-deep destruction queue
 * {@code GpuRecording#destroyLater} queues on, so it is destroyed only after the submissions that
 * may read it are done and nothing here has to count frames of its own.
 */
final class DistantUploads implements MeshTable.Gpu {

	private final GpuDevice device;

	DistantUploads(GpuDevice device) {
		this.device = device;
	}

	@Override
	public Piece create(ByteBuffer vertices, ByteBuffer indices, int indexCount) {
		GpuBuffer vertexBuffer = this.device.createBuffer(() -> "Vitrail add-on far terrain vertices",
				GpuBuffer.USAGE_VERTEX, vertices);
		try {
			GpuBuffer indexBuffer = this.device.createBuffer(() -> "Vitrail add-on far terrain indices",
					GpuBuffer.USAGE_INDEX, indices);

			return new Piece(vertexBuffer, indexBuffer, indexCount);
		} catch (RuntimeException e) {
			// The half that was made goes back with the failure, or it would be a buffer nothing holds.
			vertexBuffer.close();
			throw e;
		}
	}

	@Override
	public void destroy(Piece piece) {
		piece.vertices().close();
		piece.indices().close();
	}
}
