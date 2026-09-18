package dev.sebastiano.jewel.tooling.mcp.bootstrap;

import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Starts the bridge for one explicitly selected IDE profile and project. */
public final class Boot {
  private Boot() {}

  public static void main(String[] args) {
    try { run(args); }
    catch (Exception failure) {
      // Do not print exceptions: transport diagnostics can contain the bearer credential.
      System.err.println("Jewel Tooling cannot connect. Enable MCP for this project and copy its client configuration again.");
      System.exit(1);
    }
  }

  private static void run(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Expected descriptor and project identity");
    var descriptor = Path.of(args[0]).toAbsolutePath().normalize();
    var properties = Discovery.read(descriptor, args[1]);
    var endpoint = URI.create(properties.getProperty("endpoint"));
    if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
        || endpoint.getPort() < 1 || !"/mcp".equals(endpoint.getPath())
        || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
      throw new IllegalArgumentException("Invalid local endpoint");
    }
    var runtime = Path.of(properties.getProperty("runtimePath")).toAbsolutePath().normalize();
    if (!runtime.getParent().equals(descriptor.getParent())) throw new IllegalArgumentException("Runtime is outside private support directory");
    Discovery.checkPrivate(runtime, false);
    if (Files.size(runtime) > 100 * 1024 * 1024) throw new IllegalArgumentException("Runtime exceeds size limit");
    if (!Discovery.digest(Files.readAllBytes(runtime)).equals(properties.getProperty("runtimeDigest"))) {
      throw new IllegalArgumentException("Runtime digest does not match");
    }
    var token = properties.getProperty("token", "");
    if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid credential");
    System.setProperty("kotlin-logging.logStartupMessage", "false");
    try (var loader = new URLClassLoader(new java.net.URL[] { runtime.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
      loader.loadClass("dev.sebastiano.jewel.tooling.mcp.runtime.Bridge")
        .getMethod("run", String.class, String.class).invoke(null, endpoint.toString(), token);
    }
  }
}
