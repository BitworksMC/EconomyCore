package net.tnemc.core;

/*
 * The New Economy
 * Copyright (C) 2022 - 2025 Daniel "creatorfromhell" Vidmar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.tnemc.core.channel.BalanceHandler;
import net.tnemc.core.config.DataConfig;
import net.tnemc.core.currency.item.ItemDenomination;
import net.tnemc.item.AbstractItemStack;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.io.storage.engine.StorageSettings;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * Implementation details kept outside the core plugin facade.
 */
final class TNECoreSupport {

  private TNECoreSupport() {
  }

  static StorageRegistration prepareStorage(final boolean syncEnabled, final String syncType) {

    if(syncEnabled) {
      PluginCore.instance().getChannelMessageManager().register(new BalanceHandler());
      PluginCore.instance().getChannelMessageManager().register(new net.tnemc.core.channel.MessageHandler());
    }
    return new StorageRegistration(storageSettings(syncType), redisPool(syncEnabled, syncType));
  }

  private static StorageSettings storageSettings(final String syncType) {

    return new StorageSettings(
            DataConfig.yaml().getString("Data.Database.File"),
            DataConfig.yaml().getString("Data.Database.SQL.Host"),
            DataConfig.yaml().getInt("Data.Database.SQL.Port"),
            DataConfig.yaml().getString("Data.Database.SQL.DB"),
            DataConfig.yaml().getString("Data.Database.SQL.User"),
            DataConfig.yaml().getString("Data.Database.SQL.Password"),
            DataConfig.yaml().getString("Data.Database.Prefix"),
            DataConfig.yaml().getBoolean("Data.Database.SQL.PublicKey"),
            DataConfig.yaml().getBoolean("Data.Database.SQL.SSL"),
            "TNE",
            DataConfig.yaml().getInt("Data.Pool.MaxSize"),
            DataConfig.yaml().getLong("Data.Pool.MaxLife"),
            DataConfig.yaml().getLong("Data.Pool.Timeout"),
            syncType
    );
  }

  private static JedisPool redisPool(final boolean syncEnabled, final String syncType) {

    if(!syncEnabled || !syncType.equalsIgnoreCase("redis")) {
      return null;
    }
    final JedisPoolConfig config = redisConfig();
    final String redisUser = optionalCredential("Data.Sync.Redis.User");
    final String redisPass = optionalCredential("Data.Sync.Redis.Password");
    return new JedisPool(config, DataConfig.yaml().getString("Data.Sync.Redis.Host"),
                         DataConfig.yaml().getInt("Data.Sync.Redis.Port"),
                         DataConfig.yaml().getInt("Data.Sync.Redis.Timeout"),
                         redisUser,
                         redisPass,
                         DataConfig.yaml().getInt("Data.Sync.Redis.Index"),
                         DataConfig.yaml().getBoolean("Data.Sync.Redis.SSL"));
  }

  private static JedisPoolConfig redisConfig() {

    final JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(DataConfig.yaml().getInt("Data.Sync.Redis.Pool.MaxSize", 10));
    config.setMaxIdle(DataConfig.yaml().getInt("Data.Sync.Redis.Pool.MaxIdle", 10));
    config.setMinIdle(DataConfig.yaml().getInt("Data.Sync.Redis.Pool.MinIdle", 1));
    config.setMaxWait(Duration.ofMillis(DataConfig.yaml().getLong("Data.Sync.Redis.Pool.MaxWait", 10000L)));
    config.setBlockWhenExhausted(DataConfig.yaml().getBoolean("Data.Sync.Redis.Pool.BlockWhenExhausted", true));
    config.setTimeBetweenEvictionRuns(Duration.ofMillis(DataConfig.yaml().getLong("Data.Sync.Redis.Pool.TimeBetweenEvictionRunsMillis", 30000L)));
    config.setMinEvictableIdleTime(Duration.ofMillis(DataConfig.yaml().getLong("Data.Sync.Redis.Pool.MinEvictableIdleTimeMillis", 300000L)));
    config.setSoftMinEvictableIdleTime(Duration.ofMillis(DataConfig.yaml().getLong("Data.Sync.Redis.Pool.SoftMinEvictableIdleTimeMillis", 60000L)));
    config.setNumTestsPerEvictionRun(DataConfig.yaml().getInt("Data.Sync.Redis.Pool.NumTestsPerEvictionRun", 3));
    config.setTestOnCreate(DataConfig.yaml().getBoolean("Data.Sync.Redis.Pool.TestOnCreate", true));
    config.setTestWhileIdle(DataConfig.yaml().getBoolean("Data.Sync.Redis.Pool.TestWhileIdle", true));
    config.setTestOnBorrow(DataConfig.yaml().getBoolean("Data.Sync.Redis.Pool.TestOnBorrow", true));
    config.setTestOnReturn(DataConfig.yaml().getBoolean("Data.Sync.Redis.Pool.TestOnReturn", false));
    config.setLifo(DataConfig.yaml().getBoolean("Data.Sync.Redis.Pool.Lifo", true));
    config.setJmxEnabled(DataConfig.yaml().getBoolean("Data.Sync.Redis.Pool.JmxEnabled", false));
    config.setJmxNamePrefix(DataConfig.yaml().getString("Data.Sync.Redis.Pool.JmxNamePrefix", "tne_redis_pool"));
    config.setJmxNameBase(DataConfig.yaml().getString("Data.Sync.Redis.Pool.JmxNameBase", "pool"));
    return config;
  }

  private static String optionalCredential(final String path) {

    if(!DataConfig.yaml().contains(path)) {
      return null;
    }
    final String credential = DataConfig.yaml().getString(path);
    return credential == null || credential.equalsIgnoreCase("none") || credential.isBlank()
           ? null : credential;
  }

  static AbstractItemStack<?> denominationToStack(final ItemDenomination denomination, final int amount) {

    AbstractItemStack<?> stack = PluginCore.server().stackBuilder().of(denomination.material(), amount).debug(false);
    stack = applyItemDetails(stack, denomination);
    return applyModelDetails(stack, denomination);
  }

  private static AbstractItemStack<?> applyItemDetails(AbstractItemStack<?> stack,
                                                        final ItemDenomination denomination) {

    if(!denomination.enchantments().isEmpty()) {
      stack = stack.enchant(denomination.enchantments());
    }
    if(!denomination.flags().isEmpty()) {
      stack = stack.flags(denomination.flags());
    }
    if(!denomination.getLore().isEmpty()) {
      stack = stack.lore(denomination.getLore());
    }
    if(denomination.getDamage() > 0) {
      stack = stack.damage(denomination.getDamage());
    }
    if(!denomination.getName().isEmpty()) {
      stack = stack.customName(MiniMessage.miniMessage().deserialize(denomination.getName()));
    }
    if(denomination.getCustomModel() > -1) {
      stack = stack.modelDataOld(denomination.getCustomModel());
    }
    return stack;
  }

  private static AbstractItemStack<?> applyModelDetails(AbstractItemStack<?> stack,
                                                         final ItemDenomination denomination) {

    if(!denomination.provider().equalsIgnoreCase("vanilla")) {
      stack = stack.setItemProvider(denomination.provider()).setProviderItemID(denomination.providerID());
    }
    if(!denomination.itemModel().isEmpty()) {
      stack = stack.itemModel(denomination.itemModel());
    }
    if(hasModelData(denomination)) {
      stack = stack.modelData(denomination.modelColours(), denomination.modelFloats(),
                              denomination.modelBooleans(), denomination.modelStrings());
    }
    if(denomination.maxStack() > 0) {
      stack = stack.maxStackSize(denomination.maxStack());
    }
    return stack;
  }

  private static boolean hasModelData(final ItemDenomination denomination) {

    return !denomination.modelBooleans().isEmpty() || !denomination.modelStrings().isEmpty()
           || !denomination.modelColours().isEmpty() || !denomination.modelFloats().isEmpty();
  }

  record StorageRegistration(StorageSettings settings, JedisPool pool) {
  }
}
