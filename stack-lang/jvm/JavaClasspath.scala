package jvm

import java.net.{ URL, URLClassLoader }
import java.nio.file.Paths

/** The classpath the Java FFI reflects over: the JDK, plus any `--java-lib` jars.
  *
  * The parent is deliberately the *platform* class loader rather than the
  * compiler's own. The compiler runs on the same JVM it reflects over, so using
  * the system loader would silently make the Jo compiler itself, ASM, and the
  * Scala library part of the importable FFI surface — the opposite of what
  * gating Java interop is for.
  *
  * Classes are resolved but never initialized (`initialize = false`): naming a
  * class in a Jo `import` must not run its static initializer inside the
  * compiler.
  */
final class JavaClasspath(jars: List[String]):
  private val loader: ClassLoader =
    val urls: Array[URL] = jars.map(jar => Paths.get(jar).toAbsolutePath.toUri.toURL).toArray
    new URLClassLoader(urls, ClassLoader.getPlatformClassLoader)

  /** The class with this binary name (`java.util.Map$Entry`), if there is one. */
  def load(binaryName: String): Option[Class[?]] =
    try Some(Class.forName(binaryName, false, loader))
    catch
      // A missing class is the ordinary answer, not an error: it is how the
      // resolver tells a package segment apart from a class name.
      case _: ClassNotFoundException => None
      // A class that is present but unusable (missing supertype, bad version)
      // is equally not something to crash the compiler over.
      case _: LinkageError => None
end JavaClasspath
