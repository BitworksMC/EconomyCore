package net.tnemc.core.manager;

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

import com.vdurmont.semver4j.Semver;
import net.tnemc.plugincore.PluginCore;
import net.tnemc.plugincore.core.utils.UpdateChecker;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Updater
 *
 * @author creatorfromhell
 * @since 0.1.2.0
 */
public class Updater extends UpdateChecker {

  private static final URI RELEASE_ENDPOINT = URI.create(
          "https://api.github.com/repos/BitworksMC/EconomyCore/releases/latest");
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  final String ver;
  final Semver releaseVersion;
  final Semver pluginVersion;

  public Updater() {

    final String installedVersion = PluginCore.engine().version();
    ver = latestRelease(installedVersion);
    releaseVersion = new Semver(ver, Semver.SemverType.LOOSE);
    pluginVersion = new Semver(installedVersion, Semver.SemverType.LOOSE);
  }

  @Override
  public boolean isEarlyBuild() {

    return pluginVersion.compareTo(releaseVersion) > 0;
  }

  @Override
  public boolean needsUpdate() {

    return releaseVersion.compareTo(pluginVersion) > 0;
  }

  @Override
  public String stable() {

    return stability(PluginCore.engine().version(), PluginCore.engine().build());
  }

  public static String stability(final String version, final String build) {

    if(new Semver(version + "-" + build, Semver.SemverType.LOOSE).isStable()) {
      return "Stable";
    }
    return "Not Stable";
  }

  @Override
  public String getBuild() {

    return ver;
  }

  private static String latestRelease(final String installedVersion) {

    try {
      return requestLatestRelease(installedVersion);
    } catch(final InterruptedException e) {
      Thread.currentThread().interrupt();
      logFailure(e);
    } catch(final IOException | ParseException | IllegalArgumentException e) {
      logFailure(e);
    }
    return installedVersion;
  }

  private static String requestLatestRelease(final String installedVersion)
          throws IOException, InterruptedException, ParseException {

    final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    final HttpRequest request = HttpRequest.newBuilder(RELEASE_ENDPOINT)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "EconomyCore/" + installedVersion)
            .GET()
            .build();
    final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if(response.statusCode() != 200) {
      throw new IOException("GitHub returned HTTP " + response.statusCode());
    }
    return releaseTag(response.body());
  }

  private static String releaseTag(final String responseBody) throws ParseException {

    final Object parsed = new JSONParser().parse(responseBody);
    if(!(parsed instanceof JSONObject release)) {
      throw new IllegalArgumentException("GitHub returned an unexpected response");
    }
    final Object tag = release.get("tag_name");
    if(!(tag instanceof String) || ((String)tag).isBlank()) {
      throw new IllegalArgumentException("GitHub release is missing tag_name");
    }
    final String version = ((String)tag).replaceFirst("^[vV]", "");
    new Semver(version, Semver.SemverType.LOOSE);
    return version;
  }

  private static void logFailure(final Exception exception) {

    PluginCore.log().warning("Unable to check BitworksMC/EconomyCore GitHub releases: "
                             + exception.getMessage());
  }
}
