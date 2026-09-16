package org.example.aeroworld.mixin.dh;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Делает конфиг миксинов на Distant Horizons мягкой зависимостью.
 * Если класс DH недоступен в classpath (мод не установлен), миксин
 * вообще не пытается применяться — никаких ClassNotFoundException
 * ни при загрузке, ни при трансформации.
 */
public final class DhWorldGenBorderMixinPlugin implements IMixinConfigPlugin
{
	private static final String DH_TARGET_CLASS =
			"com.seibel.distanthorizons.common.wrappers.worldGeneration.BatchGenerationEnvironment_neoforge";

	private boolean distantHorizonsPresent;

	@Override
	public void onLoad(String mixinPackage)
	{
		// ВАЖНО: не использовать Class.forName здесь. Даже с initialize=false
		// это резолвит и линкует класс DH через classloader, что заставляет
		// его загружаться слишком рано (до того, как DH готов к трансформации
		// своих собственных классов) -> "was loaded too early" от DH.
		// Проверяем присутствие класса только по наличию .class-ресурса на
		// classpath, вообще не трогая classloading DH.
		String resource = DH_TARGET_CLASS.replace('.', '/') + ".class";
		this.distantHorizonsPresent = this.getClass().getClassLoader().getResource(resource) != null;
	}

	@Override
	public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName)
	{
		return this.distantHorizonsPresent;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }

	@Override
	public List<String> getMixins() { return null; }

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}