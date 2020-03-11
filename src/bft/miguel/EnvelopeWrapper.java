package bft.miguel;

import bft.miguel.proto.Envelopewrapper;
import org.hyperledger.fabric.protos.common.Common;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Date;

/**
 * Created by miguel on 07/03/2019.
 */
public class EnvelopeWrapper implements Serializable {
    private String channelId;
    private Common.Envelope envelope;
    private long timestamp;

    private EnvType type;

    private Envelopewrapper.EnvelopeWrapper envWrapper;

    public EnvelopeWrapper(String channelId, Common.Envelope envelope) {
        this(channelId, envelope, new Date().getTime(), EnvType.NORMAL);
    }
    public EnvelopeWrapper(String channelId, Common.Envelope envelope, long timestamp, EnvType envType) {
        this.channelId = channelId;
        this.envelope = envelope;
        this.timestamp = timestamp;

        Envelopewrapper.EnvelopeWrapper.Builder builder = Envelopewrapper.EnvelopeWrapper.newBuilder();
        builder.setChannelId(channelId);
        builder.setEnvelope(envelope);

        builder.setTimestamp(timestamp);
        if(envType == EnvType.NORMAL)
            builder.setEnvelopeType(Envelopewrapper.EnvelopeWrapper.EnvelopeType.NORMAL);
        else
            builder.setEnvelopeType(Envelopewrapper.EnvelopeWrapper.EnvelopeType.CONFIG);

        this.envWrapper = builder.build();
    }

    public EnvelopeWrapper(Envelopewrapper.EnvelopeWrapper envWrapper) {
        this.channelId = envWrapper.getChannelId();
        this.envelope = envWrapper.getEnvelope();
        this.timestamp = envWrapper.getTimestamp();

        if(envWrapper.getEnvelopeType() == Envelopewrapper.EnvelopeWrapper.EnvelopeType.NORMAL)
            this.type = EnvType.NORMAL;
        else
            this.type = EnvType.CONFIG;
    }

    public static EnvelopeWrapper fromStream(InputStream is) throws IOException {
        return new EnvelopeWrapper(Envelopewrapper.EnvelopeWrapper.parseDelimitedFrom(is));
    }

    public static String print(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ ");
        for (byte b : bytes) {
            sb.append(String.format("0x%02X ", b));
        }
        sb.append("]");
        return sb.toString();
    }

    public void sendEnvelope(OutputStream os) throws IOException {
        byte[] byteArray = this.envWrapper.toByteArray();
        int size = byteArray.length;
        System.out.println("Sending envelope with size " + size);
        System.out.println(print(ByteBuffer.allocate(4).putInt(size).array()));

        DataOutputStream dataOutputStream = new DataOutputStream(os);

        synchronized(EnvelopeWrapper.class) {
            dataOutputStream.writeInt(size);
            dataOutputStream.flush();
            dataOutputStream.write(byteArray);
            dataOutputStream.flush();
        }

//        System.out.println(this.envWrapper.toByteString().toStringUtf8());

//        this.envWrapper.writeDelimitedTo(os);
    }

    public Common.Envelope getEnvelope() {
        return envelope;
    }

    public void setEnvelope(Common.Envelope envelope) {
        this.envelope = envelope;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public EnvType getType() {
        return type;
    }

    public void setType(EnvType type) {
        this.type = type;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public Envelopewrapper.EnvelopeWrapper getEnvWrapper() {
        return envWrapper;
    }


    public enum EnvType {
        NORMAL, CONFIG
    }
}
