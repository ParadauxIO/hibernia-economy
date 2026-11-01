import java.net.*;
import java.nio.file.*;
import java.io.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.*;
import org.bukkit.NamespacedKey;
import net.kyori.adventure.nbt.*;
import static org.mockito.Mockito.*;

/** Runs the real JAR handler with no external Adventure NBT visibility. */
public class PackagingProbe {
  static ItemStack item(CompoundBinaryTag root) throws Exception {
    var bytes = new ByteArrayOutputStream();
    BinaryTagIO.writer().write(root, bytes, BinaryTagIO.Compression.GZIP);
    var item = mock(ItemStack.class); var meta = mock(ItemMeta.class);
    when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta);
    when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    when(item.serializeAsBytes()).thenReturn(bytes.toByteArray());
    return item;
  }
  public static void main(String[] args) throws Exception {
    try (var loader = new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()}, PackagingProbe.class.getClassLoader()) {
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("net.kyori.adventure.nbt.")) throw new ClassNotFoundException("External NBT intentionally unavailable: " + name);
        if (name.startsWith("com.minecraftcitiesnetwork.musicbox.")) {
          Class<?> c = findLoadedClass(name); if(c == null) c = findClass(name); if(resolve) resolveClass(c); return c;
        }
        return super.loadClass(name,resolve);
      }
    }) {
      var type = loader.loadClass("com.minecraftcitiesnetwork.musicbox.utils.nbt.BukkitNbtHandler");
      var handler = type.getConstructor().newInstance();
      var get = type.getMethod("getNbt",ItemStack.class,String.class);
      var pdc = item(CompoundBinaryTag.empty());
      when(pdc.getItemMeta().getPersistentDataContainer().get(NamespacedKey.fromString("musicbox:song_hash"),PersistentDataType.INTEGER)).thenReturn(-197);
      check(-197,get.invoke(handler,pdc,"song_hash")); verify(pdc,never()).serializeAsBytes();
      var tag = CompoundBinaryTag.builder().putInt("song_hash",-123456).build();
      var modern = item(CompoundBinaryTag.builder().put("components",CompoundBinaryTag.builder().put("minecraft:custom_data",tag).build()).build());
      var legacy = item(CompoundBinaryTag.builder().put("tag",tag).build());
      check(-123456,get.invoke(handler,modern,"song_hash")); check(-123456,get.invoke(handler,legacy,"song_hash"));
      verify(modern,never()).setItemMeta(any()); verify(legacy,never()).setItemMeta(any());
      var empty = item(CompoundBinaryTag.empty()); check(0,get.invoke(handler,empty,"song_hash"));
      when(empty.serializeAsBytes()).thenReturn(new byte[]{1,2,3}); check(0,get.invoke(handler,empty,"song_hash"));
      check(0,get.invoke(handler,null,"song_hash"));
      var clone=mock(ItemStack.class); var meta=mock(ItemMeta.class); var data=mock(PersistentDataContainer.class);
      when(pdc.clone()).thenReturn(clone); when(clone.getItemMeta()).thenReturn(meta); when(meta.getPersistentDataContainer()).thenReturn(data);
      check(clone,type.getMethod("setNbt",ItemStack.class,String.class,Integer.class).invoke(handler,pdc,"song_hash",123));
      verify(data).set(NamespacedKey.fromString("musicbox:song_hash"),PersistentDataType.INTEGER,123); verify(pdc,never()).setItemMeta(any());
      System.out.println("PASS: PDC precedence; migrated custom_data; legacy tag; empty; malformed; null; write preserves original");
    }
  }
  static void check(Object expected,Object actual) { if(!java.util.Objects.equals(expected,actual)) throw new AssertionError(expected+" != "+actual); }
}
