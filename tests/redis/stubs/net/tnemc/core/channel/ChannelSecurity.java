package net.tnemc.core.channel;

import com.google.common.io.ByteArrayDataOutput;
import java.io.DataInput;
import java.io.IOException;

public final class ChannelSecurity {
  public static String token = "fixture-token";
  public static void writeToken(final ByteArrayDataOutput output) { output.writeUTF(token); }
  public static boolean validate(final DataInput input, final String type) throws IOException {
    return input.readUTF().equals(token);
  }
}
