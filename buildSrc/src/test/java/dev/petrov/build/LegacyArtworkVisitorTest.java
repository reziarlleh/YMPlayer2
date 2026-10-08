package dev.petrov.build;

import org.junit.Test;
import org.objectweb.asm.*;
import static org.junit.Assert.*;

public class LegacyArtworkVisitorTest {
    private byte[] fixture(String descriptor, int bitmapSlot, boolean hasArt) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "androidx/media3/session/LegacyConversions", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            LegacyArtworkVisitor.METHOD, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitLdcInsn(hasArt ? "android.media.metadata.ART" : "android.media.metadata.ALBUM_ART");
        method.visitVarInsn(Opcodes.ALOAD, bitmapSlot);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyArtworkVisitor.BUILDER, "putBitmap", LegacyArtworkVisitor.PUT_BITMAP, false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    private byte[] transform(byte[] bytes) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new LegacyArtworkVisitor(writer), 0);
        return writer.toByteArray();
    }
    @Test public void retainsAlbumArtAndAddsExactlyOneArtBitmap() {
        byte[] output = transform(fixture(LegacyArtworkVisitor.DESCRIPTOR, 5, false));
        int[] calls = {0};
        new ClassReader(output).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        if (name.equals("putBitmap")) calls[0]++;
                    }
                };
            }
        }, 0);
        assertEquals(2, calls[0]);
    }
    @Test public void rejectsChangedSignature() {
        assertThrows(IllegalStateException.class, () -> transform(fixture("()Ljava/lang/Object;", 5, false)));
    }
    @Test public void rejectsChangedBitmapArgument() {
        assertThrows(IllegalStateException.class, () -> transform(fixture(LegacyArtworkVisitor.DESCRIPTOR, 4, false)));
    }
    @Test public void rejectsUpstreamArtOrRepeatedInstrumentation() {
        assertThrows(IllegalStateException.class, () -> transform(fixture(LegacyArtworkVisitor.DESCRIPTOR, 5, true)));
        byte[] first = transform(fixture(LegacyArtworkVisitor.DESCRIPTOR, 5, false));
        assertThrows(IllegalStateException.class, () -> transform(first));
    }
}
