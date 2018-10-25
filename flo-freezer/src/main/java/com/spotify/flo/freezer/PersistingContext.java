/*-
 * -\-\-
 * flo-freezer
 * --
 * Copyright (C) 2016 - 2017 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.flo.freezer;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import com.spotify.flo.EvalContext;
import com.spotify.flo.Fn;
import com.spotify.flo.Task;
import com.spotify.flo.TaskId;
import com.spotify.flo.context.ForwardingEvalContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link EvalContext} that serializes and persist tasks. Any call to {@link #evaluate(Task)}
 * will persist the task and recurse to also do so for all input tasks. No task in the dependency
 * tree will actually be invoked. Instead {@link #evaluate(Task)} will return a {@link Value} that
 * always fails.
 *
 * <p>After the returned {@link Value} has failed, all persisted file paths can be received through
 * {@link #getFiles()}.
 */
public class PersistingContext extends ForwardingEvalContext {

  static {
    // Best effort. Hope that ObjectOutputStream has not been loaded yet :pray:
    System.setProperty("sun.io.serialization.extendedDebugInfo", "true");
  }

  private static final Logger LOG = LoggerFactory.getLogger(PersistingContext.class);

  private final Path basePath;
  private final Map<TaskId, Path> files = new LinkedHashMap<>();

  public PersistingContext(Path basePath, EvalContext delegate) {
    super(delegate);
    this.basePath = Objects.requireNonNull(basePath);
  }

  public Map<TaskId, Path> getFiles() {
    return files;
  }

  @Override
  public <T> Value<T> evaluateInternal(Task<T> task, EvalContext context) {
    // materialize lazy inputs
    task.inputs();

    Path file = taskFile(task.id());
    files.put(task.id(), file);
    try {
      serialize(task, file);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return super.evaluateInternal(task, context);
  }

  @Override
  public <T> Value<T> invokeProcessFn(TaskId taskId, Fn<T> processFn) {
    final Promise<T> promise = promise();
    LOG.info("Will not invoke {}", taskId);
    promise.fail(new Persisted());
    return promise.value();
  }

  public static void serialize(Object object, Path file) throws Exception {
    try {
      serialize(object, Files.newOutputStream(file, WRITE, CREATE_NEW));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] serialize(Object object) {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    serialize(object, baos);
    return baos.toByteArray();
  }

  public static void serialize(Object object, OutputStream outputStream) {
    try (ObjectOutputStream oos = new ObjectOutputStream(outputStream)) {
      oos.writeObject(object);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> T deserialize(byte[] bytes) {
    return deserialize(new ByteArrayInputStream(bytes));
  }

  public static <T> T deserialize(Path filePath) throws IOException {
    return deserialize(Files.newInputStream(filePath));
  }

  @SuppressWarnings("unchecked")
  public static <T> T deserialize(InputStream inputStream) {
    try (ObjectInputStream ois = new ContextualObjectInputStream(inputStream)) {
      return (T) ois.readObject();
    } catch (IOException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public static String cleanForFilename(TaskId taskId) {
    return taskId.toString()
        .toLowerCase()
        .replaceAll("[,#()]+", "_")
        .replaceAll("[^a-z0-9_]*", "")
    ;
  }

  private Path taskFile(TaskId taskId) {
    return basePath.resolve(cleanForFilename(taskId));
  }

  // https://github.com/apache/beam/blob/master/sdks/java/core/src/main/java/org/apache/beam/sdk/util/SerializableUtils.java#L162
  private static final class ContextualObjectInputStream extends ObjectInputStream {
    private ContextualObjectInputStream(final InputStream in) throws IOException {
      super(in);
    }

    @Override
    protected Class<?> resolveClass(final ObjectStreamClass classDesc)
        throws IOException, ClassNotFoundException {
      // note: staying aligned on JVM default but can need class filtering here to avoid 0day issue
      final String n = classDesc.getName();
      final ClassLoader classloader = findClassLoader();
      try {
        return Class.forName(n, false, classloader);
      } catch (final ClassNotFoundException e) {
        return super.resolveClass(classDesc);
      }
    }

    @Override
    protected Class resolveProxyClass(final String[] interfaces)
        throws IOException, ClassNotFoundException {
      final ClassLoader classloader = findClassLoader();

      final Class[] cinterfaces = new Class[interfaces.length];
      for (int i = 0; i < interfaces.length; i++) {
        cinterfaces[i] = classloader.loadClass(interfaces[i]);
      }

      try {
        return Proxy.getProxyClass(classloader, cinterfaces);
      } catch (final IllegalArgumentException e) {
        throw new ClassNotFoundException(null, e);
      }
    }

    public static ClassLoader findClassLoader() {
      return findClassLoader(Thread.currentThread().getContextClassLoader());
    }

    public static ClassLoader findClassLoader(final ClassLoader proposed) {
      ClassLoader classLoader = proposed;
      if (classLoader == null) {
        classLoader = PersistingContext.class.getClassLoader();
      }
      if (classLoader == null) {
        classLoader = ClassLoader.getSystemClassLoader();
      }
      return classLoader;
    }
  }
}
