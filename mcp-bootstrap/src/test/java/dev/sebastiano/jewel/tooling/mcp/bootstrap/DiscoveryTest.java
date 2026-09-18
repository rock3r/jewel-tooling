package dev.sebastiano.jewel.tooling.mcp.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DiscoveryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void publishesPrivatelyAndRemovesOnlyItsGeneration() throws Exception {
    var root = temporary.newFolder().toPath().resolve("private");
    var project = temporary.newFolder().toPath();
    Path descriptor;
    try (var discovery = Discovery.open(root, project)) {
      descriptor = discovery.descriptorPath();
      discovery.publish(Map.of("endpoint", "http://127.0.0.1:1234/mcp", "token", "secret"));
      assertEquals("secret", Discovery.read(descriptor, discovery.projectId()).getProperty("token"));
      assertThrows(java.io.IOException.class, () -> Discovery.read(descriptor, "another-project"));
      assertThrows(java.io.IOException.class, () -> Discovery.open(root, project));
    }
    assertFalse(Files.exists(descriptor));
    try (var next = Discovery.open(root, project)) { assertEquals(descriptor, next.descriptorPath()); }
  }

  @Test public void rejectsPublicDescriptorAndSymbolicLinks() throws Exception {
    var root = temporary.newFolder().toPath().resolve("private");
    var project = temporary.newFolder().toPath();
    try (var discovery = Discovery.open(root, project)) {
      discovery.publish(Map.of("token", "secret"));
      var descriptor = discovery.descriptorPath();
      if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(descriptor, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(java.io.IOException.class, () -> Discovery.read(descriptor, discovery.projectId()));
        Files.setPosixFilePermissions(descriptor, PosixFilePermissions.fromString("rw-------"));
      }
    }
  }

  @Test public void symlinkProjectHasTheSameIdentity() throws Exception {
    var root = temporary.newFolder().toPath().resolve("private");
    var project = temporary.newFolder().toPath();
    var alias = project.resolveSibling("alias");
    try { Files.createSymbolicLink(alias, project); }
    catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) { org.junit.Assume.assumeNoException(unsupported); }
    String id;
    try (var first = Discovery.open(root, project)) { id = first.projectId(); }
    try (var second = Discovery.open(root, alias)) { assertEquals(id, second.projectId()); }
  }

  @Test public void installedRuntimeIsPrivateAndCannotEscape() throws Exception {
    var root = temporary.newFolder().toPath().resolve("private");
    var file = Discovery.install(root, "runtime.jar", new byte[] {1, 2, 3});
    Discovery.checkPrivate(file, false);
    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(file));
    assertThrows(java.io.IOException.class, () -> Discovery.install(root, "../escape.jar", new byte[0]));
  }
}
