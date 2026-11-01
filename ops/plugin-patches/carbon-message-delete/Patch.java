import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public class Patch implements Opcodes {
 static final String OWNER="net/draycia/carbonMessageDelete/CarbonMessageDelete", MESSAGE="net/kyori/adventure/chat/SignedMessage", SIG="L"+MESSAGE+"$Signature;", EVENT="net/draycia/carbon/api/event/events/CarbonChatEvent";
 public static void main(String[] args) throws Exception {
  ClassNode c=new ClassNode(); new ClassReader(Files.readAllBytes(Path.of(args[0]))).accept(c,0);
  int render=0,callback=0,delete=0;
  for(MethodNode m:c.methods) {
   if(m.name.equals("lambda$onEnable$1")) for(AbstractInsnNode n:m.instructions.toArray()) if(n instanceof MethodInsnNode call && call.owner.equals(EVENT) && call.name.equals("signedMessage")) {
    m.instructions.insert(n,new MethodInsnNode(INVOKESTATIC,OWNER,"signedOrNull","(L"+MESSAGE+";)L"+MESSAGE+";",false)); render++;
   }
   if(m.name.equals("lambda$onEnable$0")) {
    m.instructions.clear(); m.tryCatchBlocks.clear(); if(m.localVariables!=null)m.localVariables.clear();
    m.instructions.add(new VarInsnNode(ALOAD,1)); m.instructions.add(new MethodInsnNode(INVOKEINTERFACE,EVENT,"signedMessage","()L"+MESSAGE+";",true));
    m.instructions.add(new MethodInsnNode(INVOKESTATIC,OWNER,"signedOrNull","(L"+MESSAGE+";)L"+MESSAGE+";",false));
    m.instructions.add(new VarInsnNode(ASTORE,3)); m.instructions.add(new VarInsnNode(ALOAD,3)); LabelNode end=new LabelNode(); m.instructions.add(new JumpInsnNode(IFNULL,end));
    m.instructions.add(new VarInsnNode(ALOAD,0)); m.instructions.add(new VarInsnNode(ALOAD,3)); m.instructions.add(new MethodInsnNode(INVOKEINTERFACE,MESSAGE,"signature","()"+SIG,true));
    m.instructions.add(new MethodInsnNode(INVOKEVIRTUAL,OWNER,"deleteMessage","("+SIG+")V",false)); m.instructions.add(end);m.instructions.add(new InsnNode(RETURN)); callback++;
   }
   if(m.name.equals("deleteMessage")) { InsnList guard=new InsnList();LabelNode ok=new LabelNode();guard.add(new VarInsnNode(ALOAD,1));guard.add(new JumpInsnNode(IFNONNULL,ok));guard.add(new InsnNode(RETURN));guard.add(ok);m.instructions.insert(guard);delete++; }
  }
  if(render!=1||callback!=1||delete!=1)throw new IllegalStateException("Unexpected method counts");
  MethodNode helper=new MethodNode(ACC_PRIVATE|ACC_STATIC,"signedOrNull","(L"+MESSAGE+";)L"+MESSAGE+";",null,null);
  LabelNode missing=new LabelNode(); helper.instructions.add(new VarInsnNode(ALOAD,0));helper.instructions.add(new JumpInsnNode(IFNULL,missing));helper.instructions.add(new VarInsnNode(ALOAD,0));helper.instructions.add(new MethodInsnNode(INVOKEINTERFACE,MESSAGE,"signature","()"+SIG,true));helper.instructions.add(new JumpInsnNode(IFNULL,missing));helper.instructions.add(new VarInsnNode(ALOAD,0));helper.instructions.add(new InsnNode(ARETURN));helper.instructions.add(missing);helper.instructions.add(new InsnNode(ACONST_NULL));helper.instructions.add(new InsnNode(ARETURN));c.methods.add(helper);
  ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS); c.accept(w);Files.write(Path.of(args[1]),w.toByteArray());
 }
}
