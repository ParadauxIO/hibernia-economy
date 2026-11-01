import java.lang.reflect.*;
import java.util.*;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.audience.Audience;
public class Probe {
 static Object proxy(String name,InvocationHandler h)throws Exception {return Proxy.newProxyInstance(Probe.class.getClassLoader(),new Class[]{Class.forName(name)},h);}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  Class<?> clazz=Class.forName("net.draycia.carbonMessageDelete.CarbonMessageDelete");Object plugin=clazz.getConstructor().newInstance();
  List<Object> deleted=new ArrayList<>();
  Object player=proxy("net.draycia.carbon.api.users.CarbonPlayer",(o,m,a)->{if(m.getName().equals("hasPermission"))return true;if(m.getName().equals("deleteMessage")){Objects.requireNonNull(a[0],"signature");deleted.add(a[0]);return null;}return null;});
  Object server=proxy("net.draycia.carbon.api.CarbonServer",(o,m,a)->m.getName().equals("players")?List.of(player):null);
  Object carbon=proxy("net.draycia.carbon.api.CarbonChat",(o,m,a)->m.getName().equals("server")?server:null);
  Field f=clazz.getDeclaredField("carbonChat");f.setAccessible(true);f.set(plugin,carbon);
  Method callback=Arrays.stream(clazz.getDeclaredMethods()).filter(m->m.getName().equals("lambda$onEnable$0")).findFirst().get();callback.setAccessible(true);
  Method render=Arrays.stream(clazz.getDeclaredMethods()).filter(m->m.getName().equals("lambda$onEnable$1")).findFirst().get();render.setAccessible(true);
  SignedMessage.Signature sig=SignedMessage.signature(new byte[256]);
  for(int step=0;step<3;step++){
   int mode=new int[]{1,0,2}[step]; final int state=mode;
   SignedMessage message=mode==0?null:(SignedMessage)Proxy.newProxyInstance(Probe.class.getClassLoader(),new Class[]{SignedMessage.class},(o,m,a)->m.getName().equals("signature")?(state==1?null:sig):null);
   Object event=proxy("net.draycia.carbon.api.event.events.CarbonChatEvent",(o,m,a)->m.getName().equals("signedMessage")?message:null);
   if(mode<2){
    callback.invoke(plugin,event,player);check(deleted.isEmpty(),"unsigned callback must not delete");
    Component result=(Component)render.invoke(plugin,event,player,player,Component.text("message"),Component.text("original"));
    check(result.equals(Component.empty().append(new org.bukkit.configuration.file.FileConfiguration().getComponent("invalid_button_format",null)).append(Component.text("message"))),"unsigned message must render exact unavailable button and message, mode="+mode);
    callback.invoke(plugin,event,player);check(deleted.isEmpty(),"unsigned callback must not delete");
   }else{
    Component result=(Component)render.invoke(plugin,event,player,player,Component.text("message"),Component.text("original"));
    check(result.children().get(0).clickEvent(null).equals(new org.bukkit.configuration.file.FileConfiguration().getComponent("button_format",null)),"signed message must render configured active format");
    callback.invoke(plugin,event,player);check(deleted.size()==1&&deleted.get(0)==sig,"signed callback must delete original signature");
   }
  }
  clazz.getMethod("deleteMessage",SignedMessage.Signature.class).invoke(plugin,new Object[]{null});check(deleted.size()==1,"null direct call must not delete");
  System.out.println("PASS absent and unsigned messages render unavailable button; null callbacks/direct calls are safe; signed callback preserves deletion");
 }
}
