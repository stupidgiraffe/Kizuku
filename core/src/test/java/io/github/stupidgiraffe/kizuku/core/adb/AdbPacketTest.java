package io.github.stupidgiraffe.kizuku.core.adb;
import org.junit.Test;
import java.io.*;
import java.nio.*;
import static org.junit.Assert.*;

public class AdbPacketTest {
    @Test public void decodesIndependentLittleEndianAuthFixture() throws Exception {
        byte[] bytes={65,85,84,72,1,0,0,0,0,0,0,0,3,0,0,0,6,0,0,0,(byte)190,(byte)170,(byte)171,(byte)183,1,2,3};
        AdbPacket p=AdbPacket.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        assertEquals(0x48545541,p.command());assertEquals(1,p.arg0());assertArrayEquals(new byte[]{1,2,3},p.data());
    }
    @Test public void rejectsOversizedPayloadBeforeTryingToReadIt() {
        byte[] header=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0x48545541).putInt(1).putInt(0).putInt(Integer.MAX_VALUE).putInt(0).putInt(~0x48545541).array();
        assertThrows(IOException.class,()->AdbPacket.read(new DataInputStream(new ByteArrayInputStream(header))));
    }
    @Test public void rejectsBadMagicAndChecksum() {
        byte[] badMagic={65,85,84,72,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
        assertThrows(IOException.class,()->AdbPacket.read(new DataInputStream(new ByteArrayInputStream(badMagic))));
        byte[] badSum={65,85,84,72,1,0,0,0,0,0,0,0,3,0,0,0,7,0,0,0,(byte)190,(byte)170,(byte)171,(byte)183,1,2,3};
        assertThrows(IOException.class,()->AdbPacket.read(new DataInputStream(new ByteArrayInputStream(badSum))));
    }
    @Test public void truncatedPayloadIsNotAValidMessage() {
        byte[] header=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0x48545541).putInt(1).putInt(0).putInt(20).putInt(0).putInt(~0x48545541).array();
        assertThrows(EOFException.class,()->AdbPacket.read(new DataInputStream(new ByteArrayInputStream(header))));
    }
    @Test public void openServiceHasTrailingNulWithoutLoggingItsCommand() {
        AdbPacket p=AdbPacket.text(0x4e45504f,1,0,"shell:id");
        assertEquals(9,p.data().length);assertEquals(0,p.data()[8]);assertFalse(p.toString().contains("shell:id"));
    }
}
