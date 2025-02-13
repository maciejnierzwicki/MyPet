/*
 * This file is part of MyPet
 *
 * Copyright © 2011-2020 Keyle
 * MyPet is licensed under the GNU Lesser General Public License.
 *
 * MyPet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MyPet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package de.Keyle.MyPet.compat.v1_20_R2.entity;

import com.google.common.base.Predicates;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.Util;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.entity.MyPetMinecraftEntity;
import de.Keyle.MyPet.api.entity.MyPetType;
import de.Keyle.MyPet.api.util.Compat;
import de.Keyle.MyPet.api.util.ReflectionUtil;
import lombok.SneakyThrows;
import net.minecraft.core.DefaultedMappedRegistry;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.v1_20_R2.CraftWorld;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Compat("v1_20_R2")
public class EntityRegistry extends de.Keyle.MyPet.api.entity.EntityRegistry {

	BiMap<MyPetType, Class<? extends EntityMyPet>> entityClasses = HashBiMap.create();
	Map<MyPetType, EntityType> entityTypes = new HashMap<>();
	private DefaultedRegistry<EntityType> custReg = null;

	protected void registerEntityType(MyPetType petType, String key, DefaultedRegistry<EntityType<?>> entityRegistry) {
		EntityDimensions size = entityRegistry.get(new ResourceLocation(key.toLowerCase())).getDimensions();
		EntityType leType;
		if(!entityRegistry.containsKey(ResourceLocation.tryParse("mypet_" + key.toLowerCase()))) {
			leType = Registry.register(entityRegistry, "mypet_" + key.toLowerCase(), EntityType.Builder.createNothing(MobCategory.CREATURE).noSave().noSummon().sized(size.width, size.height).build(key));
		} else {
			leType = entityRegistry.get(ResourceLocation.tryParse("mypet_" + key.toLowerCase()));
		}
		entityTypes.put(petType, leType);
		EntityType<? extends LivingEntity> types = (EntityType<? extends LivingEntity>) entityRegistry.get(new ResourceLocation(key));
		registerDefaultAttributes(entityTypes.get(petType), types);
		overwriteEntityID(entityTypes.get(petType), getEntityTypeId(petType, entityRegistry), entityRegistry);
	}

	@SneakyThrows
	public static void registerDefaultAttributes(EntityType<? extends LivingEntity> customType, EntityType<? extends LivingEntity> rootType) {
		MyAttributeDefaults.registerCustomEntityType(customType, rootType);
	}

	protected void registerEntity(MyPetType type, DefaultedRegistry<EntityType<?>> entityRegistry) {
		Class<? extends EntityMyPet> entityClass = ReflectionUtil.getClass("de.Keyle.MyPet.compat.v1_20_R2.entity.types.EntityMy" + type.name());
		entityClasses.forcePut(type, entityClass);

		String key = type.getTypeID().toString();
		registerEntityType(type, key, entityRegistry);
	}

	public MyPetType getMyPetType(Class<? extends EntityMyPet> clazz) {
		return entityClasses.inverse().get(clazz);
	}

	@Override
	public MyPetMinecraftEntity createMinecraftEntity(MyPet pet, org.bukkit.World bukkitWorld) {
		EntityMyPet petEntity = null;
		Class<? extends MyPetMinecraftEntity> entityClass = entityClasses.get(pet.getPetType());
		Level world = ((CraftWorld) bukkitWorld).getHandle();

		try {
			Constructor<?> ctor = entityClass.getConstructor(Level.class, MyPet.class);
			Object obj = ctor.newInstance(world, pet);
			if (obj instanceof EntityMyPet) {
				petEntity = (EntityMyPet) obj;
			}
		} catch (Exception e) {
			MyPetApi.getLogger().info(ChatColor.RED + Util.getClassName(entityClass) + "(" + pet.getPetType() + ") is no valid MyPet(Entity)!");
			e.printStackTrace();
		}

		return petEntity;
	}

	@Override
	public boolean spawnMinecraftEntity(MyPetMinecraftEntity entity, org.bukkit.World bukkitWorld) {
		if (entity != null) {
			Level world = ((CraftWorld) bukkitWorld).getHandle();
			return world.addFreshEntity(((EntityMyPet) entity), CreatureSpawnEvent.SpawnReason.CUSTOM);
		}
		return false;
	}

	@Override
	public void registerEntityTypes() {
		//Let's prepare the Vanilla-Registry
		DefaultedRegistry<EntityType<?>> entityRegistry = getRegistry(BuiltInRegistries.ENTITY_TYPE);
		Field frozenDoBe = ReflectionUtil.getField(MappedRegistry.class,"l"); //frozen
		Field intrusiveHolderCacheField = ReflectionUtil.getField(MappedRegistry.class,"m"); //intrusiveHolderCache or unregisteredIntrusiveHolders or intrusiveValueToEntry
		MethodHandle ENTITY_REGISTRY_SETTER = ReflectionUtil.createStaticFinalSetter(BuiltInRegistries.class, "h"); //ENTITY_TYPE

		if(custReg != null) {
			//Gotta put the original Registry in. Just for a moment
			try {
				ENTITY_REGISTRY_SETTER.invoke(entityRegistry);
			} catch (Throwable e) {
			}
		}
		//We are now working with the Vanilla-Registry
		ReflectionUtil.setFinalFieldValue(frozenDoBe, entityRegistry, false);
		ReflectionUtil.setFinalFieldValue(intrusiveHolderCacheField, entityRegistry, new IdentityHashMap());

		//Now lets handle the Bukkit-Registry
		//First copy the old registry
		SimpleMyPetRegistry customBukkitRegistry = new SimpleMyPetRegistry(org.bukkit.Registry.ENTITY_TYPE);

		for (MyPetType type : MyPetType.all()) {
			//The fun part
			registerEntity(type, entityRegistry);

			/*
			A Tutorial on how to trick Spigot:
				Instead of falling back to the "Unknown"-Type, Spigot now does not accept "null" anymore
				(see https://hub.spigotmc.org/stash/projects/SPIGOT/repos/craftbukkit/commits/02d49078870f39892e7e2ce2916e17a879e9a3f0#src/main/java/org/bukkit/craftbukkit/entity/CraftEntityType.java)
				This means that Mypet-Entites (aka mypet_pig etc) can't be converted.
				This means that the "hack" MyPet used at the end of 1.20.1 doesn't work anymore.
				And just replacing the type of the pet when it spawns doesn't work either.
				This is bad.

				Now onto the *trickery*.
				Basically:
				We basically tell Bukkit that we are a BlockDisplay. Yep.
				This means everything will be created properly in the beginning, will be handled (kinda) properly with other plugins
				and also when the pet dies.
				It's stupid that we have to do this but it seems to work -> I'm happy.
			 */
			customBukkitRegistry.addCustomKeyAndEntry(NamespacedKey.fromString("mypet_" + type.getTypeID().toString()), org.bukkit.entity.EntityType.BLOCK_DISPLAY, (entity) -> entity != org.bukkit.entity.EntityType.UNKNOWN);
		}

		//Post-Handle Bukkit-Registry
		customBukkitRegistry.build();
		MethodHandle BUKKIT_ENTITY_REGISTRY_SETTER = ReflectionUtil.createStaticFinalSetter(org.bukkit.Registry.class, "ENTITY_TYPE"); //ENTITY_TYPE
		try {
			BUKKIT_ENTITY_REGISTRY_SETTER.invoke(customBukkitRegistry);
		} catch (Throwable e) {
			e.printStackTrace();
		}

		//Post-Handle Vanilla Registry
		entityRegistry.freeze();
		if(custReg != null) {
			//Gotta put the custom Registry back into place
			try {
				ENTITY_REGISTRY_SETTER.invoke(custReg);
			} catch (Throwable e) {
			}
			custReg = null;
		}
	}

	public <T> T getEntityType(MyPetType petType) {
		return (T) this.entityTypes.get(petType);
	}

	@Override
	public void unregisterEntityTypes() {
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public DefaultedRegistry<EntityType<?>> getRegistry(DefaultedRegistry dMappedRegistry) {
		if (!dMappedRegistry.getClass().getName().equals(DefaultedMappedRegistry.class.getName())) {
			MyPetApi.getLogger().info("Custom entity registry found: " + dMappedRegistry.getClass().getName());
			if(custReg == null) {
				custReg = dMappedRegistry;
			}
			for (Field field : dMappedRegistry.getClass().getDeclaredFields()) {
				if (field.getType() == DefaultedMappedRegistry.class || field.getType() == MappedRegistry.class) {
					field.setAccessible(true);
					try {
						DefaultedRegistry<EntityType<?>> reg = (DefaultedRegistry<EntityType<?>>) field.get(dMappedRegistry);

						if (!reg.getClass().getName().equals(DefaultedMappedRegistry.class.getName())) {
							reg = getRegistry(reg);
						}
						return reg;
					} catch (IllegalAccessException e) {
						e.printStackTrace();
					}
				}
			}
		}
		return dMappedRegistry;
	}

	protected void overwriteEntityID(EntityType types, int id, DefaultedRegistry<EntityType<?>> entityRegistry) {
		try {
			Field bgF = MappedRegistry.class.getDeclaredField("e"); //This is toId
			bgF.setAccessible(true);
			Object map = bgF.get(entityRegistry);
			Class<?> clazz = map.getClass();
			Method mapPut = clazz.getDeclaredMethod("put", Object.class, int.class);
			mapPut.setAccessible(true);
			mapPut.invoke(map, types, id);
		} catch (ReflectiveOperationException ex) {

			ex.printStackTrace();
		}

	}

	protected int getEntityTypeId(MyPetType type, DefaultedRegistry<EntityType<?>> entityRegistry) {
		EntityType<?> types = entityRegistry.get(new ResourceLocation(type.getTypeID().toString()));
		return entityRegistry.getId(types);
	}

	static final class SimpleMyPetRegistry<T extends Enum<T> & Keyed> implements org.bukkit.Registry<T> {

		private Map<NamespacedKey, T> map;
		private ImmutableMap.Builder<NamespacedKey, T> builder;

		protected SimpleMyPetRegistry(@NotNull Class<T> type) {
			this(type, Predicates.<T>alwaysTrue());
		}

		protected SimpleMyPetRegistry(@NotNull Class<T> type, @NotNull Predicate<T> predicate) {
			builder = ImmutableMap.builder();

			for (T entry : type.getEnumConstants()) {
				if (predicate.test(entry)) {
					builder.put(entry.getKey(), entry);
				}
			}
		}

		protected SimpleMyPetRegistry(@NotNull org.bukkit.Registry<T> oldReg) {
			builder = ImmutableMap.builder();

			oldReg.stream()
					.forEach(e -> {
						builder.put(e.getKey(), e);
					});
		}

		public void addCustomKeyAndEntry(@NotNull NamespacedKey key, @NotNull T entry) {
			addCustomKeyAndEntry(key, entry, Predicates.<T>alwaysTrue());
		}

		public void addCustomKeyAndEntry(@NotNull NamespacedKey key, @NotNull T entry, @NotNull Predicate<T> predicate) {
			if(builder!=null) {
				if (predicate.test(entry)) {
					builder.put(key, entry);
				}
			}
		}

		public void build() {
			map = builder.build();
			builder = null;
		}

		@Nullable
		@Override
		public T get(@NotNull NamespacedKey key) {
			return map.get(key);
		}

		@NotNull
		@Override
		public Stream<T> stream() {
			return StreamSupport.stream(spliterator(), false);
		}

		@NotNull
		@Override
		public Iterator<T> iterator() {
			return map.values().iterator();
		}
	}
}