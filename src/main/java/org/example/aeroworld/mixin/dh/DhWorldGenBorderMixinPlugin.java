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
		boolean present;
		try
		{
			Class.forName(DH_TARGET_CLASS, false, this.getClass().getClassLoader());
			present = true;
		}
		catch (Throwable t)
		{
			present = false;
		}
		this.distantHorizonsPresent = present;
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
