import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.security.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class PatchFamilies {
    static final String BASE = "com/minecraftcitiesnetwork/families/";
    static final String HELPER = BASE + "patch/SafeBackground";
    static int changes;
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
        if (!digest.equals("af934df430cba1ce04abbc881b70dc759fd12ec630a34379b6c8e296dfb25c3b")) throw new IllegalArgumentException("Unrecognized input: " + digest);
        try (ZipFile source = new ZipFile(input.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            for (ZipEntry entry : Collections.list(source.entries())) {
                byte[] bytes = source.getInputStream(entry).readAllBytes();
                if (entry.getName().equals(BASE + "common/handler/familytree/TreeRendering.class")) {
                    ClassNode node = read(bytes);
                    for (MethodNode method : node.methods) if (method.name.equals("backgroundId") && method.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                        method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
                        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "backgroundId", method.desc, false));
                        method.instructions.add(new InsnNode(Opcodes.ARETURN));
                        changes++;
                    }
                    bytes = write(node);
                }
                if (entry.getName().equals(BASE + "config/FamiliesConfig.class")) {
                    ClassNode node = read(bytes);
                    for (MethodNode method : node.methods) if (method.name.equals("load") && method.desc.equals("()V")) {
                        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                            InsnList hook = new InsnList();
                            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BASE + "config/FamiliesConfig", "settings", "()L" + BASE + "config/FamiliesSettings;", false));
                            hook.add(new InsnNode(Opcodes.DUP));
                            hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BASE + "config/FamiliesSettings", "backgrounds", "()Ljava/util/List;", false));
                            hook.add(new InsnNode(Opcodes.SWAP));
                            hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BASE + "config/FamiliesSettings", "defaultBackground", "()Ljava/lang/String;", false));
                            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "configure", "(Ljava/util/List;Ljava/lang/String;)V", false));
                            method.instructions.insertBefore(instruction, hook); changes++;
                        }
                    }
                    bytes = write(node);
                }
                output.putNextEntry(new ZipEntry(entry.getName())); output.write(bytes); output.closeEntry();
            }
            for (String name : List.of(HELPER + ".class", HELPER + "$Settings.class")) {
                output.putNextEntry(new ZipEntry(name)); output.write(Files.readAllBytes(Path.of(args[2], name))); output.closeEntry();
            }
        }
        if (changes != 2) { Files.delete(Path.of(args[1])); throw new IllegalStateException("Expected 2 changes, got " + changes); }
    }
    static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
}
