import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension

// Applies InkOfflineImportsTransform to every variant of the module that
// packages ML Kit's digital-ink library: :app outside Play, the
// :feature:handwriting split on Play. Scope ALL, because the classes it edits
// come from the library's AAR; isInstrumentable keeps it to that one package.
// Frames are recomputed for the edited methods only, since one of them gains
// an early return.
extensions.getByType(AndroidComponentsExtension::class.java).apply {
    onVariants(selector().all()) { variant ->
        variant.instrumentation.transformClassesWith(
            InkOfflineImportsTransform::class.java,
            InstrumentationScope.ALL,
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
        )
    }
}
