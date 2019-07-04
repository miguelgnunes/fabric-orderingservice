package bft.miguel;

import java.util.LinkedList;
import java.util.List;

/**
 * Created by miguel on 12/03/2019.
 */
public class BlockCutter {


    private LinkedList<EnvelopeWrapper> envelopes;
    private long preferredMaxBytes;
    private long maxMessageCount;

    public BlockCutter(long preferredMaxBytes, long maxMessageCount) {
        this.envelopes = new LinkedList<>();
        this.preferredMaxBytes = preferredMaxBytes;
        this.maxMessageCount = maxMessageCount;
    }

    public void addEnvelope(EnvelopeWrapper envelope) {
        this.envelopes.add(envelope);
    }

    public boolean isBlock() {
        if(this.envelopes.size() == maxMessageCount)
            return true;
        int byteSize = 0;
        for(int i = 0; i < envelopes.size(); i++)
            byteSize += envelopes.get(i).getEnvelope().toByteArray().length;
        if(byteSize > getPreferredMaxBytes())
            return true;

        return false;
    }

    long getPreferredMaxBytes() {
        return preferredMaxBytes;
    }

    void setPreferredMaxBytes(long preferredMaxBytes) {
        this.preferredMaxBytes = preferredMaxBytes;
    }

    List<EnvelopeWrapper> cutBlock() {
        LinkedList<EnvelopeWrapper> envs = new LinkedList<>(envelopes);
        envelopes = new LinkedList<>();
        return envs;
    }

    public void setMaxMessageCount(long maxMessageCount) {
        this.maxMessageCount = maxMessageCount;
    }
}
