package dev.sebastiano.jewel.tooling.mcp.bootstrap;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Owns private endpoint discovery for one IDE profile and project. */
public final class Discovery implements AutoCloseable {
  private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
  private final Path descriptor;
  private final FileChannel channel;
  private final FileLock lock;
  private final String projectId;
  private final String generation = UUID.randomUUID().toString();
  private boolean closed;

  private Discovery(Path descriptor, FileChannel channel, FileLock lock, String projectId) {
    this.descriptor = descriptor;
    this.channel = channel;
    this.lock = lock;
    this.projectId = projectId;
  }

  public static Discovery open(Path directory, Path project) throws IOException {
    privateDirectory(directory);
    var realProject = project.toRealPath();
    var attrs = Files.readAttributes(realProject, BasicFileAttributes.class);
    var store = Files.getFileStore(realProject);
    // The file key also identifies differently cased paths on case-insensitive volumes.
    var identity = attrs.fileKey();
    if (identity == null) throw new IOException("Project filesystem identity is unavailable");
    var id = digest((store.name() + "\n" + store.type() + "\n" + identity).getBytes(StandardCharsets.UTF_8));
    var lockPath = directory.resolve(id + ".lock");
    if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) createPrivateFile(lockPath);
    checkPrivate(lockPath, false);
    var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    try {
      var lock = channel.tryLock();
      if (lock == null) throw new IOException("This project already has an enabled endpoint in this IDE profile");
      return new Discovery(directory.resolve(id + ".properties"), channel, lock, id);
    } catch (IOException | OverlappingFileLockException failure) {
      channel.close();
      throw new IOException("Cannot acquire the project discovery lock", failure);
    }
  }

  public String projectId() { return projectId; }
  public String generation() { return generation; }
  public Path descriptorPath() { return descriptor; }

  public synchronized void publish(Map<String, String> fields) throws IOException {
    if (closed) throw new IOException("Discovery is closed");
    var data = new Properties();
    data.putAll(fields);
    data.setProperty("projectId", projectId);
    data.setProperty("generation", generation);
    var writer = new StringWriter();
    data.store(writer, "Jewel Tooling private endpoint. Do not share this file.");
    atomicWrite(descriptor, writer.toString().getBytes(StandardCharsets.UTF_8));
  }

  public static Properties read(Path descriptor, String expectedId) throws IOException {
    checkPrivate(descriptor.getParent(), true);
    checkPrivate(descriptor, false);
    if (Files.size(descriptor) > 16384) throw new IOException("Discovery file exceeds its size limit");
    var properties = new Properties();
    properties.load(new StringReader(Files.readString(descriptor, StandardCharsets.UTF_8)));
    if (!expectedId.equals(properties.getProperty("projectId"))) throw new IOException("Project identity does not match");
    return properties;
  }

  public static Path install(Path directory, String filename, byte[] bytes) throws IOException {
    privateDirectory(directory);
    if (!filename.matches("[a-zA-Z0-9.-]+\\.jar")) throw new IOException("Invalid support filename");
    var file = directory.resolve(filename);
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      checkPrivate(file, false);
      if (digest(Files.readAllBytes(file)).equals(digest(bytes))) return file;
    }
    atomicWrite(file, bytes);
    return file;
  }

  public static String digest(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  public static void checkPrivate(Path path, boolean directory) throws IOException {
    if (Files.isSymbolicLink(path)) throw new IOException("Private support paths cannot be symbolic links");
    if (directory ? !Files.isDirectory(path) : !Files.isRegularFile(path)) throw new IOException("Private support path is unavailable");
    var owner = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)) throw new IOException("Private support path has a different owner");
    var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      var expected = directory ? DIRECTORY_MODE : FILE_MODE;
      if (!posix.readAttributes().permissions().equals(expected)) throw new IOException("Private support permissions must allow only the owner");
      return;
    }
    var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl == null) throw new IOException("Private support permissions are unsupported");
    for (var entry : acl.getAcl()) {
      if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)) throw new IOException("Private support ACL permits another principal");
    }
  }

  private static void privateDirectory(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      var posix = Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class);
      if (posix != null) Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
      else { Files.createDirectory(path); secureAcl(path); }
    }
    checkPrivate(path, true);
  }

  private static void createPrivateFile(Path path) throws IOException {
    var posix = Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class);
    if (posix != null) Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_MODE));
    else { Files.createFile(path); secureAcl(path); }
  }

  private static void secureAcl(Path path) throws IOException {
    var view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) throw new IOException("Private support permissions are unsupported");
    var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
    view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
      .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
  }

  private static void atomicWrite(Path path, byte[] bytes) throws IOException {
    var temporary = path.resolveSibling("." + UUID.randomUUID() + ".tmp");
    createPrivateFile(temporary);
    try {
      Files.write(temporary, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      checkPrivate(path, false);
    } finally { Files.deleteIfExists(temporary); }
  }

  @Override public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      if (Files.exists(descriptor, LinkOption.NOFOLLOW_LINKS)
          && generation.equals(read(descriptor, projectId).getProperty("generation"))) Files.delete(descriptor);
    } finally {
      try { lock.release(); } finally { channel.close(); }
    }
  }
}
