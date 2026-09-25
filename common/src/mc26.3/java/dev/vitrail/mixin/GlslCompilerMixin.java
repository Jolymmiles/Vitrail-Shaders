package dev.vitrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import dev.vitrail.cache.ModuleCache;
import dev.vitrail.glsl.LoadClock;
import dev.vitrail.render.PackNames;
import dev.vitrail.render.RawLocals;
import dev.vitrail.render.ShaderDebugInfo;
import net.minecraft.client.renderer.ShaderDefines;
import org.lwjgl.util.shaderc.Shaderc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

/**
 * Gives the compiler's output its zeroes before anything reflects it, decides whether shaderc
 * writes debug information, and clocks what a module costs.
 * <p>
 * The 26.3 half. The compiler here is {@code GlslCompiler.compileToSpv}, which the pipeline builder
 * calls once per stage and which hands back a {@code SPIRVModule} reflected on demand, where 26.2's
 * {@code createIntermediary} reflected on the spot. The same three things happen at the matching
 * places:
 * <ul>
 * <li>the debug information is skipped where the options for one compile are built, since 26.3
 * builds them per compile rather than once in the constructor;</li>
 * <li>the zeroes and the stripped names are written into the copy of shaderc's output the module
 * is built around, before any reflection can read it;</li>
 * <li>the clock runs around the whole compile.</li>
 * </ul>
 * What 26.2 also did here and this does not: serve a unit from {@link ModuleCache}, which keeps
 * nothing on this game yet, and ask shaderc for a geometry stage, which this game's pipeline has no
 * room for yet; both are said where they live.
 */
@Mixin(GlslCompiler.class)
public abstract class GlslCompilerMixin {

	/**
	 * Skips the one call that asks shaderc for debug information, unless somebody asked for it
	 * back. {@link ShaderDebugInfo} carries the switch and what it costs.
	 */
	@WrapOperation(method = "createBaseShaderOptions", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/util/shaderc/Shaderc;"
							+ "shaderc_compile_options_set_generate_debug_info(J)V"))
	private void vitrail$skipDebugInfo(long options, Operation<Void> original) {
		ShaderDebugInfo.announce();
		if (ShaderDebugInfo.asked()) {
			original.call(options);
		}
	}

	/**
	 * Gives this engine's units what the 26.2 compiler gave every unit and 26.3's gives none: the
	 * locations of a stage's inputs and outputs assigned where the text names none, and the two
	 * OpenGL builtins a pack spells by their OpenGL names read as their Vulkan ones. A pack's GLSL was
	 * written for OpenGL, which links the stages by name and knows {@code gl_VertexID}; 26.3 builds
	 * its own shaders with explicit locations and the Vulkan names, so it turned both off. The two
	 * stages are then linked by name in {@code PipelineBuilderMixin}, as 26.2's rebind did. The game's
	 * own units, and every other mod's, are compiled with the options 26.3 gives them.
	 */
	@WrapOperation(method = "compileToSpv", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/frontend/shaders/GlslCompiler;"
							+ "createBaseShaderOptions()J"))
	private long vitrail$legacyOptions(GlslCompiler compiler, Operation<Long> original,
			@Local(argsOnly = true, ordinal = 0) String name) {
		long options = original.call(compiler);
		if (RawLocals.ours(debugName(name))) {
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
			Shaderc.shaderc_compile_options_add_macro_definition(options, "gl_VertexID",
					"gl_VertexIndex");
			Shaderc.shaderc_compile_options_add_macro_definition(options, "gl_InstanceID",
					"gl_InstanceIndex");
		}

		return options;
	}

	/**
	 * Between shaderc and the module, on the copy the game made of the compiler's output: every
	 * variable the pack can read before writing gets the zero it reads under Iris, and the names
	 * MoltenVK chokes on go. {@link RawLocals} and {@link PackNames} carry the switches and the why.
	 * Each pass frees the buffer it replaces, so one buffer reaches the module, which frees it.
	 */
	@WrapOperation(method = "compileToSpv", require = 1,
			at = @At(value = "NEW",
					target = "com/mojang/renderpearl/frontend/shaders/SPIRVModule"))
	private SPIRVModule vitrail$zeroLocals(ByteBuffer spirv, ShaderType type,
			Operation<SPIRVModule> original, @Local(argsOnly = true, ordinal = 0) String name) {
		String filename = debugName(name);
		return original.call(PackNames.patch(filename, RawLocals.patch(filename, spirv)), type);
	}

	/**
	 * Clocks the compile and counts it, under the state of the zero pass taken once at its head, so
	 * a load flipping the switch meanwhile cannot patch one unit under two states.
	 */
	@WrapMethod(method = "compileToSpv", require = 1)
	private SpvModule vitrail$module(String name, String source, ShaderType type,
			ShaderDefines defines, ShaderSource shaderSource, Operation<SpvModule> original) {
		long began = System.nanoTime();
		RawLocals.begin();
		try {
			ModuleCache.building(debugName(name));
			return original.call(name, source, type, defines, shaderSource);
		} finally {
			RawLocals.end();
			LoadClock.module(System.nanoTime() - began);
		}
	}

	/**
	 * The name the 26.2 compiler was handed, {@code Identifier.toDebugFileName}, which is what the
	 * two passes recognise this engine's modules by. 26.3 hands the identifier's plain string.
	 */
	@Unique
	private static String debugName(String name) {
		return name.replace('/', '_').replace(':', '_');
	}
}
