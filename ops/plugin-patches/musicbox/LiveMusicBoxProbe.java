import java.io.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Call on the server thread; creates only detached test ItemStacks. */
public final class LiveMusicBoxProbe {
  public static void run(Plugin plugin) throws Exception {
    ClassLoader cl=plugin.getClass().getClassLoader();
    Class<?> handlerType=cl.loadClass("com.minecraftcitiesnetwork.musicbox.utils.nbt.BukkitNbtHandler");
    Object handler=handlerType.getConstructor().newInstance();
    var read=handlerType.getMethod("getNbt",ItemStack.class,String.class);
    var write=handlerType.getMethod("setNbt",ItemStack.class,String.class,Integer.class);
    var plain=new ItemStack(Material.MUSIC_DISC_CAT);
    var meta=plain.getItemMeta(); meta.displayName(net.kyori.adventure.text.Component.text("Detached MusicBox verification item")); plain.setItemMeta(meta);
    check(0,read.invoke(handler,plain,"song_hash"));
    var modern=(ItemStack)write.invoke(handler,plain,"song_hash",123456);
    check(123456,read.invoke(handler,modern,"song_hash"));
    check(0,read.invoke(handler,plain,"song_hash"));
    String n="com.minecraftcitiesnetwork.musicbox.internal.nbt.";
    var io=cl.loadClass(n+"BinaryTagIO"); var compound=cl.loadClass(n+"CompoundBinaryTag");
    var binary=cl.loadClass(n+"BinaryTag"); var builder=cl.loadClass(n+"CompoundBinaryTag$Builder");
    var setter=cl.loadClass(n+"CompoundTagSetter");
    var compression=cl.loadClass(n+"BinaryTagIO$Compression"); Object gzip=compression.getField("GZIP").get(null);
    var nbtRead=cl.loadClass(n+"BinaryTagIO$Reader").getMethod("read",InputStream.class,compression);
    Object root=nbtRead.invoke(io.getMethod("reader").invoke(null),new ByteArrayInputStream(plain.serializeAsBytes()),gzip);
    Object components=compound.getMethod("getCompound",String.class).invoke(root,"components");
    Object custom=compound.getMethod("builder").invoke(null);
    setter.getMethod("putInt",String.class,int.class).invoke(custom,"song_hash",-98765);
    Object componentsBuilder=compound.getMethod("builder").invoke(null); setter.getMethod("put",compound).invoke(componentsBuilder,components);
    setter.getMethod("put",String.class,binary).invoke(componentsBuilder,"minecraft:custom_data",builder.getMethod("build").invoke(custom));
    Object rootBuilder=compound.getMethod("builder").invoke(null); setter.getMethod("put",compound).invoke(rootBuilder,root);
    setter.getMethod("put",String.class,binary).invoke(rootBuilder,"components",builder.getMethod("build").invoke(componentsBuilder));
    var bytes=new ByteArrayOutputStream();
    cl.loadClass(n+"BinaryTagIO$Writer").getMethod("write",compound,OutputStream.class,compression).invoke(io.getMethod("writer").invoke(null),builder.getMethod("build").invoke(rootBuilder),bytes,gzip);
    var legacy=ItemStack.deserializeBytes(bytes.toByteArray());
    var original=legacy.serializeAsBytes(); check(-98765,read.invoke(handler,legacy,"song_hash"));
    if(!java.util.Arrays.equals(original,legacy.serializeAsBytes())) throw new AssertionError("Legacy read mutated item");
    var precedence=(ItemStack)write.invoke(handler,legacy,"song_hash",777); check(777,read.invoke(handler,precedence,"song_hash"));
    plugin.getLogger().info("LIVE MUSICBOX PASS: plain disc, PDC read/write, original unchanged, migrated legacy custom_data, PDC precedence");
  }
  private static void check(Object expected,Object actual) {if(!java.util.Objects.equals(expected,actual)) throw new AssertionError(expected+" != "+actual);}
}
