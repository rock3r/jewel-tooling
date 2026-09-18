package dev.sebastiano.jewel.tooling.agent;

import java.io.InputStream;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.jar.JarFile;

/** Loads the recorder only after checking the target JVM. */
public final class Premain {
  private static JarFile bridge;
  private static URLClassLoader implementation;

  private Premain() {}

  public static void premain(String argument, Instrumentation instrumentation) {
    String stage = "JVM_VERSION";
    try {
      String version = System.getProperty("java.specification.version", "0");
      if (version.startsWith("1.")) version = version.substring(2);
      if (Integer.parseInt(version) < 21) {
        failure(argument, "UNSUPPORTED_JVM");
        return;
      }
      stage = "AGENT_LOCATION";
      URL resource = Premain.class.getResource("Premain.class");
      if (resource == null || !"jar".equals(resource.getProtocol())) {
        throw new IllegalStateException("The agent must be loaded from its JAR");
      }
      URL ownJar = ((java.net.JarURLConnection) new URL(resource.toExternalForm()).openConnection()).getJarFileURL();
      Path bridgePath = Paths.get(ownJar.toURI()).getParent().resolve("bridge.jar");
      stage = "BOOTSTRAP_BRIDGE";
      bridge = new JarFile(bridgePath.toFile());
      instrumentation.appendToBootstrapClassLoaderSearch(bridge);
      stage = "PRIVATE_LOADER";
      ClassLoader platform = (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
      implementation = new URLClassLoader(new URL[] {ownJar}, platform);
      Class<?> runtime = Class.forName("dev.sebastiano.jewel.tooling.agent.AgentRuntime", true, implementation);
      stage = "RECORDER_START";
      runtime.getMethod("start", String.class, Instrumentation.class).invoke(null, argument, instrumentation);
    } catch (Throwable problem) {
      failure(argument, "AGENT_START_FAILED", stage, problem);
    }
  }

  private static void failure(String argument, String reason) {
    failure(argument, reason, "JVM_VERSION", null);
  }

  private static void failure(String argument, String reason, String stage, Throwable problem) {
    if (argument == null) return;
    try {
      Path config = Paths.get(argument);
      Path directory = config.getParent();
      if (directory == null || Files.isSymbolicLink(directory) || Files.isSymbolicLink(config)) return;
      Properties input = new Properties();
      try (InputStream stream = Files.newInputStream(config, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = new byte[8193];
        int count = 0;
        int next;
        while (count < bytes.length && (next = stream.read(bytes, count, bytes.length - count)) > 0) count += next;
        if (count > 8192) return;
        input.load(new java.io.StringReader(new String(bytes, 0, count, StandardCharsets.UTF_8)));
      }
      String nonce = input.getProperty("nonce", "");
      if (!nonce.matches("[0-9a-f]{64}")) return;
      Properties result = new Properties();
      result.setProperty("version", "1");
      result.setProperty("nonce", nonce);
      result.setProperty("failure", reason);
      result.setProperty("failureStage", stage);
      if (problem != null) {
        Throwable cause = problem;
        for (int depth = 0; depth < 8 && cause.getCause() != null; depth++) cause = cause.getCause();
        result.setProperty("failureType", cause.getClass().getName());
        StringBuilder frames = new StringBuilder();
        StackTraceElement[] stack = cause.getStackTrace();
        for (int index = 0; index < Math.min(4, stack.length); index++) {
          if (index > 0) frames.append(';');
          frames.append(stack[index].getClassName()).append('.').append(stack[index].getMethodName())
            .append(':').append(stack[index].getLineNumber());
        }
        result.setProperty("failureFrames", frames.toString().substring(0, Math.min(frames.length(), 1024)));
      }
      StringWriter writer = new StringWriter();
      result.store(writer, null);
      Path temporary = directory.resolve("ready.properties.tmp");
      if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
        Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } else {
        Files.createFile(temporary);
        java.nio.file.attribute.AclFileAttributeView acl = Files.getFileAttributeView(
          temporary, java.nio.file.attribute.AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) return;
        java.nio.file.attribute.UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
        if (!acl.getOwner().equals(owner)) return;
        acl.setAcl(java.util.Collections.singletonList(java.nio.file.attribute.AclEntry.newBuilder()
          .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner)
          .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build()));
      }
      Files.write(temporary, writer.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE);
      Files.move(temporary, directory.resolve("ready.properties"), StandardCopyOption.ATOMIC_MOVE);
    } catch (Throwable ignored) { }
  }
}
