package dev.vitrail.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.api.AddonImage;
import dev.vitrail.api.AddonRegistrar;
import dev.vitrail.api.ImageSource;
import dev.vitrail.api.VitrailAddon;
import dev.vitrail.pack.model.ImageInformation;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.SamplerPlan.Kind;
import dev.vitrail.pack.texture.CustomImages;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the rule that decides which image names an add-on really serves: the claim stands unless
 * Vitrail or the pack already gives the name a meaning, the first claim on a name wins, and the
 * pack's own word for a served name, sampler or storage image, is what types it.
 */
class AddonImageNamesTest {

	private static final String COUNTER = "none RED R32UI UNSIGNED_INT false false 512 512";

	@BeforeEach
	@AfterEach
	void empty() {
		AddonRegistry.clear();
		AddonImageNames.reset();
		CustomImages.clear();
	}

	private static ImageSource serving(String... names) {
		return new ImageSource() {
			@Override
			public Set<String> names() {
				return new LinkedHashSet<>(List.of(names));
			}

			@Override
			public @Nullable AddonImage image(String name) {
				return null;
			}
		};
	}

	private static void load(String id, ImageSource... sources) {
		AddonRegistry.load(List.of(addon(id, sources)));
	}

	@Test
	void nothingIsServedWhereNoAddonClaimsAnything() {
		assertFalse(AddonImageNames.active());
		assertFalse(AddonImageNames.serves("light_probe"));
		assertNull(AddonImageNames.owner("light_probe"));
		assertEquals(Kind.UNSERVED, SamplerPlan.classify("light_probe"));
	}

	@Test
	void aClaimedNameIsServedAndTheOthersAreNot() {
		load("lumen", serving("light_probe", "sky_probe"));

		assertTrue(AddonImageNames.active());
		assertTrue(AddonImageNames.serves("light_probe"));
		assertTrue(AddonImageNames.serves("sky_probe"));
		assertFalse(AddonImageNames.serves("other"));
		assertEquals("lumen", AddonImageNames.owner("sky_probe").addon());
		assertNull(AddonImageNames.owner("other"));
	}

	@Test
	void aServedNameIsAKindOfItsOwnSoItIsNeitherUnservedNorTheScene() {
		load("lumen", serving("light_probe"));

		assertEquals(Kind.ADDON, SamplerPlan.classify("light_probe"));
		assertEquals(Kind.ADDON, SamplerPlan.classify("light_probe", "sampler2D"));
		assertEquals(Kind.ADDON, SamplerPlan.classify("light_probe", "image2D"));
		assertEquals(Kind.UNSERVED, SamplerPlan.classify("other"));
		// A full screen program hands a name nothing answers for the scene, and this one is answered.
		assertFalse(SamplerPlan.takesDefault("light_probe", "sampler2D", Set.of(), Set.of()));
		assertTrue(SamplerPlan.takesDefault("other", "sampler2D", Set.of(), Set.of()));
		// A shape the backend cannot bind stays refused whoever serves the name.
		assertEquals(Kind.UNBINDABLE, SamplerPlan.classify("light_probe", "sampler3D"));
	}

	@Test
	void theNamesVitrailServesItselfAreRefused() {
		load("greedy", serving("colortex0", "colortex15", "colorimg3", "gcolor", "depthtex0",
				"depthtex2", "gdepthtex", "shadowtex0", "shadowtex1HW", "shadowcolor0", "watershadow",
				"noisetex", "dhDepthTex1", SamplerPlan.forged("mine"), SamplerPlan.centerDepth(),
				"gtexture", "tex", "texture", "ofTexture", "lightmap", "ofOverlay", "normals",
				"specular"));

		for (String name : List.of("colortex0", "colortex15", "colorimg3", "gcolor", "depthtex0",
				"depthtex2", "gdepthtex", "shadowtex0", "shadowtex1HW", "shadowcolor0", "watershadow",
				"noisetex", "dhDepthTex1", SamplerPlan.forged("mine"), SamplerPlan.centerDepth(),
				"gtexture", "tex", "texture", "ofTexture", "lightmap", "ofOverlay", "normals",
				"specular")) {
			assertNotNull(AddonImageNames.refusal(name), name);
			assertFalse(AddonImageNames.serves(name), name);
			assertFalse(AddonImageNames.storageBinding(name), name);
		}

		// Refused, so still what Vitrail says it is.
		assertEquals(Kind.COLORTEX, SamplerPlan.classify("colortex0"));
		assertEquals(Kind.SHADOW_DEPTH, SamplerPlan.classify("shadowtex0"));
		assertEquals(Kind.NOISE, SamplerPlan.classify("noisetex"));
	}

	@Test
	void theNamesThePackGivesAMeaningToAreRefusedToo() {
		List<ImageInformation> images = new ArrayList<>();
		assertNull(ImageInformation.parse("pack_volume", COUNTER, Map.of(), images));
		CustomImages.install(new ImageInformation.Reading(images, List.of()));
		AddonImageNames.packTextures(Set.of("pack_lut"));
		load("greedy", serving("pack_volume", "pack_lut", "free_name"));

		assertEquals("the pack declares an image of that name", AddonImageNames.refusal("pack_volume"));
		assertEquals("the pack ships a texture under that name", AddonImageNames.refusal("pack_lut"));
		assertNull(AddonImageNames.refusal("free_name"));
		assertFalse(AddonImageNames.serves("pack_volume"));
		assertFalse(AddonImageNames.serves("pack_lut"));
		assertTrue(AddonImageNames.serves("free_name"));
		// The pack's own name keeps its kind, and a shipped file keeps the name over the add-on.
		assertEquals(Kind.CUSTOM_IMAGE, SamplerPlan.classify("pack_volume", "sampler2D"));
		assertEquals(Kind.PACK_TEXTURE,
				SamplerPlan.classify("pack_lut", "sampler2D", Set.of("pack_lut"), Set.of()));
	}

	@Test
	void theFirstClaimOnANameKeepsItAcrossAddonsAndWithinOne() {
		AddonRegistry.load(List.of(
				addon("first", serving("shared", "own_a")),
				addon("second", serving("shared", "own_b"), serving("own_a"))));

		assertEquals("first", AddonImageNames.owner("shared").addon());
		assertEquals("first", AddonImageNames.owner("own_a").addon());
		assertEquals("second", AddonImageNames.owner("own_b").addon());
	}

	@Test
	void aSourceWhoseNamesThrowIsCutOffAndClaimsNothing() {
		ImageSource broken = new ImageSource() {
			@Override
			public Set<String> names() {
				throw new IllegalStateException("planted");
			}

			@Override
			public @Nullable AddonImage image(String name) {
				return null;
			}
		};
		AddonRegistry.load(List.of(addon("broken", broken), addon("fine", serving("kept"))));

		assertFalse(AddonImageNames.serves("anything"));
		assertTrue(AddonImageNames.serves("kept"));
		assertTrue(AddonRegistry.images().get(0).cutOff());
	}

	@Test
	void namesWithNoTextAreIgnored() {
		Set<String> odd = new LinkedHashSet<>();
		odd.add(null);
		odd.add("");
		odd.add("  ");
		odd.add("real");
		ImageSource source = new ImageSource() {
			@Override
			public Set<String> names() {
				return odd;
			}

			@Override
			public @Nullable AddonImage image(String name) {
				return null;
			}
		};
		AddonRegistry.load(List.of(addon("odd", source)));

		assertTrue(AddonImageNames.serves("real"));
		assertFalse(AddonImageNames.serves(""));
		assertFalse(AddonImageNames.serves("  "));
	}

	@Test
	void aServedNameIsAStorageBindingOnlyWhereAProgramDeclaresItAsAnImage() {
		load("lumen", serving("light_probe", "light_store"));

		assertFalse(AddonImageNames.storageBinding("light_store"));
		assertFalse(AddonImageNames.declaresStorage());

		AddonImageNames.declaredAsImage("light_store");
		AddonImageNames.declaredAsImage("not_served");

		assertTrue(AddonImageNames.storageBinding("light_store"));
		assertFalse(AddonImageNames.storageBinding("light_probe"));
		assertFalse(AddonImageNames.storageBinding("not_served"));
		assertTrue(AddonImageNames.declaresStorage());
	}

	private static VitrailAddon addon(String id, ImageSource... sources) {
		return new VitrailAddon() {
			@Override
			public String id() {
				return id;
			}

			@Override
			public void register(AddonRegistrar registrar) {
				for (ImageSource source : sources) {
					registrar.images(source);
				}
			}
		};
	}
}
