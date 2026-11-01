import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Repair built-in navigation placeholders using the same context as SimpleItem. */
public class Patch {
  static final String BASE = "net/Indyuce/bountyhunters/common/gui/";
  static final String PLACEHOLDERS = BASE + "editable/placeholder/Placeholders";
  static final String DESC = "(L" + BASE + "editable/GeneratedInventory;I)L" + PLACEHOLDERS + ";";
  static byte[] patch(byte[] original) {
    ClassNode cls = new ClassNode();
    new ClassReader(original).accept(cls, 0);
    int count = 0;
    for (MethodNode method : cls.methods) {
      if (!method.name.equals("getPlaceholders") || !method.desc.equals(DESC)) continue;
      long emptyCalls = Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode m
          && m.owner.equals(BASE + "editable/placeholder/EmptyPlaceholders") && m.name.equals("<init>") && m.desc.equals("()V")).count();
      if (emptyCalls != 1) throw new IllegalStateException("Unexpected method body: " + cls.name);
      method.instructions.clear();
      method.tryCatchBlocks.clear();
      if (method.localVariables != null) method.localVariables.clear();
      method.instructions.add(new TypeInsnNode(Opcodes.NEW, PLACEHOLDERS));
      method.instructions.add(new InsnNode(Opcodes.DUP));
      method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
      method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, PLACEHOLDERS, "<init>", "(L" + BASE + "editable/GeneratedInventory;)V", false));
      method.instructions.add(new InsnNode(Opcodes.ARETURN));
      method.maxStack = 3;
      method.maxLocals = 3;
      count++;
    }
    if (count != 1) throw new IllegalStateException("Expected one placeholder method");
    ClassWriter writer = new ClassWriter(0);
    cls.accept(writer);
    return writer.toByteArray();
  }
  public static void main(String[] args) throws Exception {
    Path input = Path.of(args[0]), output = Path.of(args[1]);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
    if (!hash.equals("7ae836d097cb48d5a4494948cc66c9847411b25ce23509af22b57f063ca361cf")) throw new IllegalArgumentException("Unknown input JAR " + hash);
    Set<String> targets = new HashSet<>();
    for (String name : List.of("NextPageItem", "PreviousPageItem", "GoBackItem", "CloseInventoryItem")) targets.add(BASE + "editable/item/builtin/" + name + ".class");
    try (ZipFile src = new ZipFile(input.toFile()); ZipOutputStream dst = new ZipOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW))) {
      for (var entries = src.entries(); entries.hasMoreElements();) {
        ZipEntry entry = entries.nextElement();
        byte[] data = src.getInputStream(entry).readAllBytes();
        if (targets.remove(entry.getName())) data = patch(data);
        ZipEntry out = new ZipEntry(entry.getName());
        out.setTime(entry.getTime());
        dst.putNextEntry(out); dst.write(data); dst.closeEntry();
      }
    }
    if (!targets.isEmpty()) throw new IllegalStateException("Missing classes " + targets);
    System.out.println("Patched four navigation placeholder factories");
  }
}
