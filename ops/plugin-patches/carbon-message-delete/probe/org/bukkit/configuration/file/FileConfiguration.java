package org.bukkit.configuration.file;
/** Probe-only config adapter: reads the two simple quoted scalar formats from staging. */
public class FileConfiguration {
 public net.kyori.adventure.text.Component getComponent(String key,net.kyori.adventure.text.serializer.ComponentDecoder decoder) {
  try {
   for(String line:java.nio.file.Files.readAllLines(java.nio.file.Path.of(System.getProperty("carbon.probe.config")))) {
    if(line.startsWith(key+":")) {
     String value=line.substring(key.length()+1).trim();
     if(!value.startsWith("\"")||!value.endsWith("\"")||value.contains("\\")) throw new IllegalStateException("Probe expects simple quoted format");
     return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(value.substring(1,value.length()-1));
    }
   }
   throw new IllegalStateException("Missing format: "+key);
  }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
 }
}
