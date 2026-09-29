package dev.vitrail.addon;

import dev.vitrail.addon.AddonRegistry.Entry;
import dev.vitrail.api.DistantFrame;
import dev.vitrail.api.DistantMesh;
import dev.vitrail.api.DistantSection;
import dev.vitrail.api.DistantSections;
import dev.vitrail.api.DistantTerrainSource;
import dev.vitrail.api.DistantWindow;
import dev.vitrail.dh.DhLods.Piece;
import dev.vitrail.dh.DhLods.Section;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One registered far terrain source as the engine sees it: every question put to the add-on goes
 * through {@link AddonRegistry#call}, so that a source that throws is cut off and answers nothing
 * afterwards, and what it hands back is turned into the sections the far terrain is drawn from.
 * <p>
 * Every answer here is the one the engine falls back on when there is none: not present, not
 * usable, no distance, no window, no sections. A frame that meets a source that has just been cut
 * off therefore simply has no far terrain from it, which is a picture, where a throw out of a frame
 * would not be.
 */
public final class FarSource {

	private static final Logger LOGGER = LoggerFactory.getLogger("Vitrail");

	/** What a frame draws, out of the four lists of {@link DistantSections}. */
	public record Lists(
			List<Section> opaque,
			List<Section> water,
			List<Section> shadowOpaque,
			List<Section> shadowWater) {

		/** The frame of a source with nothing to draw. */
		public static final Lists NONE = new Lists(List.of(), List.of(), List.of(), List.of());
	}

	private final Entry<DistantTerrainSource> entry;
	private final MeshTable meshes = new MeshTable();

	/** Said once: a mesh in a list that this engine did not hand out. */
	private boolean foreignSaid;

	FarSource(Entry<DistantTerrainSource> entry) {
		this.entry = entry;
	}

	/** The id of the add-on this source belongs to. */
	public String addon() {
		return this.entry.addon();
	}

	/** Where the source's meshes live. */
	public MeshTable meshes() {
		return this.meshes;
	}

	/** Whether a call into the source has thrown, after which it is never asked again. */
	public boolean cutOff() {
		return this.entry.cutOff();
	}

	/** Hands the source its mesh table, which is the first thing it is ever asked. */
	void attach() {
		AddonRegistry.call(this.entry, "take its far terrain mesh table", source -> source.attach(this.meshes));
	}

	/** Whether the source has far terrain in this session. */
	public boolean present() {
		Boolean answer = ask("say whether it has far terrain", DistantTerrainSource::present);

		return answer != null && answer;
	}

	/** Whether the source can draw in this frame. */
	public boolean usable() {
		Boolean answer = ask("say whether it can draw far terrain", DistantTerrainSource::usable);

		return answer != null && answer;
	}

	/**
	 * How far the source reaches, in blocks, or -1 where it has no answer, which is what the pack
	 * is given the game's own distance for.
	 */
	public int renderDistanceBlocks() {
		Integer answer = ask("give its far terrain's reach", DistantTerrainSource::renderDistanceBlocks);

		return answer == null || answer <= 0 ? -1 : answer;
	}

	/**
	 * The z row of the volume the source's far terrain is drawn in, as the scale and the offset that
	 * turn an eye distance into a depth, made out of the window it gives. The pair is the one
	 * {@code DhDepth.zRow} answers for Distant Horizons, so everything downstream of it is the same
	 * code: the planes are then worked out of the row again, {@code DhDepth.planes} being the
	 * arithmetic that undoes this one.
	 * <p>
	 * A window that is no window is no row. False, and {@code dest} left alone.
	 */
	public boolean zRow(Vector2f dest) {
		DistantWindow window = ask("give its far terrain's window", DistantTerrainSource::window);
		if (window == null) {
			return false;
		}

		float near = window.near();
		float far = window.far();
		// Written as the negation of the wanted order so that a NaN, which compares false against
		// everything, is refused as well as a plane behind the camera.
		if (!(near > 0.0F && far > near) || Float.isInfinite(far)) {
			return false;
		}

		float span = far - near;
		dest.set(near / span, near * far / span);

		return true;
	}

	/**
	 * The far terrain of one frame. The source's call and the turning of its answer into sections
	 * are one guarded step, so a list holding a null is the add-on's failure and cuts it off like a
	 * throw would.
	 */
	public Lists frame(DistantFrame view) {
		Lists lists = ask("list its far terrain for a frame", source -> lists(source.frame(view), view.shadows()));

		return lists == null ? Lists.NONE : lists;
	}

	/**
	 * Copies what the add-on has handed over to the GPU and gives back what it freed, or gives
	 * everything back once the source has been cut off.
	 */
	public void drain(MeshTable.Gpu gpu) {
		if (cutOff()) {
			this.meshes.close(gpu);
		} else {
			this.meshes.drain(gpu);
		}
	}

	private <R> @Nullable R ask(String what, Function<DistantTerrainSource, R> question) {
		AtomicReference<R> answer = new AtomicReference<>();

		return AddonRegistry.call(this.entry, what, source -> answer.set(question.apply(source)))
				? answer.get()
				: null;
	}

	private Lists lists(@Nullable DistantSections sections, boolean shadows) {
		if (sections == null) {
			return Lists.NONE;
		}

		List<Section> opaque = sections(sections.opaque());
		List<Section> water = sections(sections.water());
		if (!shadows) {
			return new Lists(opaque, water, List.of(), List.of());
		}

		// The same list is converted once: a source that does not tell the light from the camera
		// hands over one list twice.
		List<Section> shadowOpaque = sections.shadowOpaque() == sections.opaque()
				? opaque
				: sections(sections.shadowOpaque());
		List<Section> shadowWater = sections.shadowWater() == sections.water()
				? water
				: sections(sections.shadowWater());

		return new Lists(opaque, water, shadowOpaque, shadowWater);
	}

	/**
	 * The sections of one list that have something to draw, in the order listed. A mesh that is not
	 * on the GPU yet or is gone is left out, and a section left with no mesh is left out with them.
	 */
	private List<Section> sections(List<DistantSection> listed) {
		List<Section> sections = new ArrayList<>(listed.size());

		// One list refilled section by section, for the reason DhLods does the same: the record
		// copies what it is handed, and a far view lists thousands of sections.
		List<Piece> pieces = new ArrayList<>();
		for (DistantSection section : listed) {
			pieces.clear();
			for (DistantMesh mesh : section.meshes()) {
				if (mesh instanceof MeshTable.Handle handle) {
					Piece piece = handle.piece();
					if (piece != null) {
						pieces.add(piece);
					}
				} else if (!this.foreignSaid) {
					this.foreignSaid = true;
					LOGGER.warn("Add-on {} listed a far terrain mesh of class {}, which is not one it got from "
							+ "its DistantMeshes, so that mesh is not drawn", addon(), mesh.getClass().getName());
				}
			}

			if (!pieces.isEmpty()) {
				sections.add(new Section(section.x(), section.y(), section.z(), pieces));
			}
		}

		return sections;
	}
}
