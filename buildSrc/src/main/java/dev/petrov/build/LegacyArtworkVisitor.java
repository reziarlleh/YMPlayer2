package dev.petrov.build;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Media3 1.11.0 compatibility: publish the already decoded image under ART too.
 * No extra bitmap loading, session, or runtime access to Media3 internals. */
public final class LegacyArtworkVisitor extends ClassVisitor {
    public static final String CLASS_NAME = "androidx.media3.session.LegacyConversions";
    static final String METHOD = "convertToMediaMetadataCompat";
    static final String DESCRIPTOR = "(Landroidx/media3/common/MediaMetadata;Ljava/lang/String;Landroid/net/Uri;JLandroid/graphics/Bitmap;)Landroidx/media3/session/legacy/MediaMetadataCompat;";
    static final String BUILDER = "androidx/media3/session/legacy/MediaMetadataCompat$Builder";
    static final String PUT_BITMAP = "(Ljava/lang/String;Landroid/graphics/Bitmap;)L" + BUILDER + ";";
    private int methods;
    private int insertions;

    public LegacyArtworkVisitor(ClassVisitor next) { super(Opcodes.ASM9, next); }

    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
            String signature, String[] exceptions) {
        MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor)) return next;
        if ((access & Opcodes.ACC_STATIC) == 0) throw incompatible();
        methods++;
        return new MethodVisitor(Opcodes.ASM9, next) {
            private boolean albumArt;
            private boolean bitmapArgument;
            @Override public void visitLdcInsn(Object value) {
                if ("android.media.metadata.ART".equals(value)) throw incompatible();
                albumArt = "android.media.metadata.ALBUM_ART".equals(value);
                bitmapArgument = false;
                super.visitLdcInsn(value);
            }
            @Override public void visitVarInsn(int opcode, int slot) {
                bitmapArgument = albumArt && opcode == Opcodes.ALOAD && slot == 5;
                super.visitVarInsn(opcode, slot);
            }
            @Override public void visitMethodInsn(int opcode, String owner, String name,
                    String descriptor, boolean isInterface) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                if (albumArt && bitmapArgument && opcode == Opcodes.INVOKEVIRTUAL
                        && BUILDER.equals(owner) && "putBitmap".equals(name)
                        && PUT_BITMAP.equals(descriptor)) {
                    // putBitmap leaves the builder on the stack; keep it for the original POP.
                    super.visitLdcInsn("android.media.metadata.ART");
                    super.visitVarInsn(Opcodes.ALOAD, 5);
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    insertions++;
                }
                albumArt = false;
                bitmapArgument = false;
            }
        };
    }

    @Override public void visitEnd() {
        if (methods != 1 || insertions != 1) throw incompatible();
        super.visitEnd();
    }
    private static IllegalStateException incompatible() {
        return new IllegalStateException("Media3 ART compatibility contract changed: re-audit LegacyConversions before updating Media3.");
    }
}
