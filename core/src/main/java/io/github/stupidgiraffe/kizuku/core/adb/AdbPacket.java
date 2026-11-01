package io.github.stupidgiraffe.kizuku.core.adb;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Stock ADB v1 framing; bounds are checked before allocation. Based on the Porter wire adapter. */
public record AdbPacket(int command, int arg0, int arg1, byte[] data) {
    public static final int MAX_PAYLOAD=1024*1024;
    public static final int CNXN=0x4e584e43, AUTH=0x48545541, OPEN=0x4e45504f,
        OKAY=0x59414b4f, CLSE=0x45534c43, WRTE=0x45545257, STLS=0x534c5453;
    public AdbPacket {
        if(data==null)data=new byte[0];
        if(data.length>MAX_PAYLOAD)throw new IllegalArgumentException("ADB packet too large");
    }
    public static AdbPacket text(int command,int a,int b,String value) {
        return new AdbPacket(command,a,b,(value+"\0").getBytes(StandardCharsets.UTF_8));
    }
    private static int checksum(byte[] data) { int sum=0;for(byte b:data)sum+=b&255;return sum; }
    public byte[] encode() {
        return ByteBuffer.allocate(24+data.length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.length)
            .putInt(checksum(data)).putInt(~command).put(data).array();
    }
    public static AdbPacket read(DataInputStream input) throws IOException {
        byte[] header=new byte[24];input.readFully(header);
        ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command=b.getInt(),a=b.getInt(),arg=b.getInt(),length=b.getInt(),checksum=b.getInt(),magic=b.getInt();
        if(magic!=~command || length<0 || length>MAX_PAYLOAD)throw new IOException("Invalid ADB frame header");
        byte[] data=new byte[length];input.readFully(data);
        if(checksum(data)!=checksum)throw new IOException("Invalid ADB frame checksum");
        return new AdbPacket(command,a,arg,data);
    }
    @Override public String toString(){return "ADB frame "+command+" ("+data.length+" bytes)";}
}
