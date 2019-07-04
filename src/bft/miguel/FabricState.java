package bft.miguel;

import org.hyperledger.fabric.protos.common.Common;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by miguel on 07/03/2019.
 */
public class FabricState {

    private HashMap<String, ArrayList<Common.Block>> channelBlocks;

    public FabricState() {
        this.channelBlocks = new HashMap<>();
    }

    public void addBlock(String channelID, Common.Block block) {
        if(!channelBlocks.containsKey(channelID))
            channelBlocks.put(channelID, new ArrayList<>());

        channelBlocks.get(channelID).add(block);
    }

    public HashMap<String, ArrayList<Common.Block>> getChannelBlocks() {
        return channelBlocks;
    }


}
