package dev.petrov.build;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;

public abstract class LegacyArtworkTransform implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    @Override public boolean isInstrumentable(ClassData data) {
        return LegacyArtworkVisitor.CLASS_NAME.equals(data.getClassName());
    }
    @Override public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new LegacyArtworkVisitor(next);
    }
}
