package org.rocksdb.test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;

public class SMRocksJunitRunner {
  public static void main(String[] args) throws Exception {
    String[] testClassNames;
    if (args.length > 0) {
      testClassNames = args;
    } else {
      List<String> classNamesList =
          discoverClassNames(SMRocksJunitRunner.class);
      List<String> testClassNamesList = filterTestClassNames(classNamesList);
      Collections.sort(testClassNamesList);
      testClassNames = testClassNamesList.toArray(new String[0]);
    }
    RocksJunitRunner.main(testClassNames);
  }

  /**
   * Discovers the names of all the classes that are siblings to the root class,
   * whether in the same directory or the same JAR file.
   *
   * @param rootClass class to use to bootstrap the test discovery process.
   *        Only tests in the same directory structure or JAR file will be
   *        discovered.
   * @returns the fully-qualified names of all discovered classes
   * @throws Exception if there is a problem accessing the class files
   */
  private static List<String> discoverClassNames(Class<?> rootClass)
      throws Exception {
    String resourcePath = rootClass.getName().replace('.', '/') + ".class";
    URL resourceUrl = rootClass.getClassLoader().getResource(resourcePath);
    if (resourceUrl == null) {
      // This should be impossible -- the class file for the root class should
      // exist either on the filesystem or in a JAR.
      throw new AssertionError(
          "Cannot load class file for " + rootClass.getName());
    }
    final List<String> classNames = new ArrayList<>();
    try (AutoCloseable fs = new DemandFileSystem(resourceUrl.toURI())) {
      URI rootUri = new URI(resourceUrl.toString().replace(resourcePath, ""));
      final Path rootPath = Paths.get(rootUri);
      Set<FileVisitOption> options = Collections.emptySet();
      Files.walkFileTree(
          rootPath,
          options,
          Integer.MAX_VALUE,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(
                Path path, BasicFileAttributes attrs) throws IOException {
              String suffix = ".class";
              if (path.toString().endsWith(suffix)) {
                String relativePath = rootPath.relativize(path).toString();
                int endIndex = relativePath.length() - suffix.length();
                String classPath = relativePath.substring(0, endIndex);
                classNames.add(classPath.replace('/', '.'));
              }
              return FileVisitResult.CONTINUE;
            }
          });
    }
    return classNames;
  }

  /**
   * Returns the names of all the provided classes that can be used as Junit 4
   * test classes.
   */
  private static List<String> filterTestClassNames(List<String> classNames) {
    List<String> testClassNames = new ArrayList<>();
    for (String className : classNames) {
      Class<?> clazz;
      try {
        clazz = Class.forName(className);
      } catch (ReflectiveOperationException e) {
        System.err.println("Failed to load class " + className);
        continue;
      }
      if (Modifier.isAbstract(clazz.getModifiers())) {
        continue; // Cannot instantiate.
      }
      if (clazz.getAnnotation(RunWith.class) != null) {
        testClassNames.add(className);
        continue; // Using a custom runner, should work.
      }
      try {
        clazz.getConstructor();
      } catch (NoSuchMethodException e) {
        continue; // Cannot instantiate.
      }
      for (Method method : clazz.getMethods()) {
        if (method.getAnnotation(Test.class) != null) {
          testClassNames.add(className);
          break;
        }
      }
    }
    return testClassNames;
  }

  /**
   * A wrapper that opens a new filesystem if necessary to access a URI.
   * Newly-opened filesystems are closed when the DemandFileSystem object
   * is closed.
   */
  private static class DemandFileSystem implements AutoCloseable {
    final FileSystem fileSystem;

    DemandFileSystem(URI uri) throws IOException {
      FileSystem tempFileSystem = null;
      try {
        // Check if the filesystem is already open.
        FileSystems.getFileSystem(uri);
      } catch (FileSystemNotFoundException e) {
        // We need to open a new filesystem.
        Map<String, ?> env = Collections.emptyMap();
        tempFileSystem = FileSystems.newFileSystem(uri, env);
      }
      this.fileSystem = tempFileSystem;
    }

    @Override
    public void close() throws IOException {
      if (fileSystem != null) {
        fileSystem.close();
      }
    }
  }
}
