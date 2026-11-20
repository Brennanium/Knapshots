plugins { alias(libs.plugins.kotlin.jvm) }
dependencies { compileOnly("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}") }
