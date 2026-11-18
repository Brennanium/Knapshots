package dev.knapshots.testing.editor.junit

import dev.knapshots.testing.editor.EditorHostProvider
import dev.knapshots.testing.editor.EditorSourceHost
import dev.knapshots.testing.editor.withEditorHost
import java.lang.reflect.Method
import java.util.ServiceLoader
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext

/** Installs the project's single service-registered editor host provider around each test. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(EditorHostExtension::class)
public annotation class EditorTest

public class EditorHostExtension : InvocationInterceptor {
  override fun interceptTestMethod(
    invocation: InvocationInterceptor.Invocation<Void>,
    invocationContext: ReflectiveInvocationContext<Method>,
    extensionContext: ExtensionContext,
  ) {
    // Do not initialize an IDE for tests that never call assertEditor.
    val host = EditorSourceHost { request ->
      val providers = ServiceLoader.load(EditorHostProvider::class.java, extensionContext.requiredTestClass.classLoader).toList()
      check(providers.size == 1) {
        "Expected exactly one EditorHostProvider service; found ${providers.size}. Configure a project editor host provider."
      }
      providers.single().createHost().open(request)
    }
    withEditorHost(host) { invocation.proceed() }
  }
}
