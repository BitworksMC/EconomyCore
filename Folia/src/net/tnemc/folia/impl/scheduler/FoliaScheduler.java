package net.tnemc.folia.impl.scheduler;

/*
 * The New Economy
 * Copyright (C) 2022 - 2024 Daniel "creatorfromhell" Vidmar
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

import net.tnemc.paper.PaperCore;
import net.tnemc.plugincore.core.compatibility.scheduler.Chore;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreExecution;
import net.tnemc.plugincore.core.compatibility.scheduler.ChoreTime;
import net.tnemc.plugincore.core.compatibility.scheduler.SchedulerProvider;
import org.bukkit.Bukkit;

import java.util.concurrent.TimeUnit;

/**
 * FoliaScheduler
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class FoliaScheduler extends SchedulerProvider<FoliaChore> {

  /**
   * Used to create a task, which will execute after the specified delay.
   *
   * @param task        The task to run.
   * @param delay       The delay, in ticks.
   * @param environment The execution environment for the task.
   */
  @Override
  public void createDelayedTask(final Runnable task, final ChoreTime delay, final ChoreExecution environment) {

    if(environment == ChoreExecution.MAIN_THREAD) {
      if(delay.asTicks() <= 0) {
        Bukkit.getGlobalRegionScheduler().execute(PaperCore.instance().getPlugin(), task);
      } else {
        Bukkit.getGlobalRegionScheduler().runDelayed(PaperCore.instance().getPlugin(), scheduledTask->task.run(), delay.asTicks());
      }
    } else if(delay.asTicks() <= 0) {
      Bukkit.getAsyncScheduler().runNow(PaperCore.instance().getPlugin(), scheduledTask->task.run());
    } else {
      Bukkit.getAsyncScheduler().runDelayed(PaperCore.instance().getPlugin(), scheduledTask->task.run(),
                                            delay.asTicks() * 50L, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Used to create a task, which repeats after a specified period.
   *
   * @param task        The task to run.
   * @param delay       The delay to run the task, in ticks.
   * @param period      The period to run the task.
   * @param environment The execution environment for the task.
   *
   * @return The associated {@link Chore} with this task.
   */
  @Override
  public FoliaChore createRepeatingTask(final Runnable task, final ChoreTime delay, final ChoreTime period, final ChoreExecution environment) {

    if(environment == ChoreExecution.MAIN_THREAD) {
      return new FoliaChore(Bukkit.getGlobalRegionScheduler()
                                    .runAtFixedRate(PaperCore.instance().getPlugin(), scheduledTask->task.run(),
                                                    Math.max(1L, delay.asTicks()), Math.max(1L, period.asTicks())),
                            environment);
    }
    return new FoliaChore(Bukkit.getAsyncScheduler()
                                  .runAtFixedRate(PaperCore.instance().getPlugin(), (scheduledTask)->task.run(),
                                                  Math.max(1L, delay.asTicks() * 50L), Math.max(1L, period.asTicks() * 50L),
                                                  TimeUnit.MILLISECONDS),
                          environment);
  }
}
