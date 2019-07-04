package bft.miguel;

import bft.util.BFTCommon;
import bft.util.BlockCutter;
import bft.util.ECDSAKeyLoader;
import bft.util.MSPManager;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.common.Configtx;
import org.hyperledger.fabric.protos.msp.Identities;
import org.hyperledger.fabric.protos.orderer.Configuration;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.helper.Config;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.*;

/**
 * Created by miguel on 07/03/2019.
 */
public class BFTFabricHelper {

    private final Logger logger;

    private MSPManager mspManager;


    //@TODO Some of these fields will not be used. However, they are set in the BFTSmart config file so we have to process them
    //Loadconfig fields
    private String mspid;
    private int parallelism;
    private int blocksPerThread;
    private boolean bothSigs;
    private boolean envValidation;
    private int interval;
    private boolean filterMalformed;
    private long timeWindow;
    private TreeSet receivers;
    private Common.Block sysGenesis;
    private String sysChannel;
    private String configDir;

    private X509Certificate certificate = null;
    private PrivateKey privKey = null;
        private byte[] serializedCert = null;
    private Identities.SerializedIdentity ident;


    private int id;

    private final CryptoPrimitives crypto;
    private Map<String,Common.BlockHeader> lastBlockHeaders;

    private FabricState fabricState;
    Random rand = new Random(System.nanoTime());


    public BFTFabricHelper(int id) throws NoSuchProviderException, NoSuchAlgorithmException, CryptoException, InvalidArgumentException, IllegalAccessException, InstantiationException, ClassNotFoundException, IOException, InvalidKeySpecException, CertificateException {
        //@TODO This id is used to retrieve ECDSA keys. These keys are copied to BFTNode docker but not BFTProxy. This is missing
        this.id = id;

        this.logger = LoggerFactory.getLogger(BFTFabricHelper.class);
        lastBlockHeaders = new TreeMap<>();

        this.fabricState = new FabricState();

        this.crypto = new CryptoPrimitives();
        this.crypto.init();

        ECDSAKeyLoader loader = new ECDSAKeyLoader(this.id, this.configDir, this.crypto.getProperties().getProperty(Config.SIGNATURE_ALGORITHM));
        privKey = loader.loadPrivateKey();
        certificate = loader.loadCertificate();
        serializedCert = BFTCommon.getSerializedCertificate(certificate);
        ident = BFTCommon.getSerializedIdentity(mspid, serializedCert);

        BFTCommon.init(crypto);

        try {
            loadConfig();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (CertificateException e) {
            e.printStackTrace();
        }
    }

    private void loadConfig() throws IOException, CertificateException {

        configDir = BFTCommon.getBFTSMaRtConfigDir("NODE_CONFIG_DIR");

        LineIterator it = FileUtils.lineIterator(new File(this.configDir + "node.config"), "UTF-8");

        Map<String,String> configs = new TreeMap<>();

        while (it.hasNext()) {

            String line = it.nextLine();

            if (!line.startsWith("#") && line.contains("=")) {

                String[] params = line.split("\\=");

                configs.put(params[0], params[1]);

            }
        }

        it.close();

        mspid = configs.get("MSPID");
        parallelism = Integer.parseInt(configs.get("PARELLELISM"));
        blocksPerThread = Integer.parseInt(configs.get("BLOCKS_PER_THREAD"));
        bothSigs = Boolean.parseBoolean(configs.get("BOTH_SIGS"));
        envValidation = Boolean.parseBoolean(configs.get("ENV_VALIDATION"));
        interval = Integer.parseInt(configs.get("THROUGHPUT_INTERVAL"));
        filterMalformed = Boolean.parseBoolean(configs.get("FILTER_MALFORMED"));

        FileInputStream input = new FileInputStream(new File(configs.get("GENESIS")));
        sysGenesis = Common.Block.parseFrom(IOUtils.toByteArray(input));

        Common.Envelope env = Common.Envelope.parseFrom(sysGenesis.getData().getData(0));
        Common.Payload payload = Common.Payload.parseFrom(env.getPayload());
        Common.ChannelHeader chanHeader = Common.ChannelHeader.parseFrom(payload.getHeader().getChannelHeader());
        sysChannel = chanHeader.getChannelId();

        String window = configs.get("TIME_WINDOW");
        timeWindow = BFTCommon.parseDuration(window).toMillis();

        String[] IDs = configs.get("RECEIVERS").split("\\,");
        int[] recvs = Arrays.asList(IDs).stream().mapToInt(Integer::parseInt).toArray();

        //MIGUEL Receivers are the active frontends (BFTProxy) to which the node should send new blocks.
        this.receivers = new TreeSet<>();
        for (int o : recvs) {
            this.receivers.add(o);
        }

    }

    public EnvelopeWrapper convertEnvelope(String channelId, byte[] envelope) throws InvalidProtocolBufferException {
        return convertEnvelope(channelId, Common.Envelope.parseFrom(envelope));
    }
    public EnvelopeWrapper convertEnvelope(String channelId, Common.Envelope envelope) {
        return new EnvelopeWrapper(channelId, envelope);
    }


    public void addBlock(String channelID, Common.Block block) {
        fabricState.addBlock(channelID, block);
        lastBlockHeaders.put(channelID, block.getHeader());
    }

    public Common.Block createBlock(String channelID, List<EnvelopeWrapper> envelopes) throws IOException, BFTCommon.BFTException, NoSuchAlgorithmException, NoSuchProviderException, CryptoException, InvalidKeyException, SignatureException {

        byte[][] envBatch = new byte[envelopes.size()][];
        for(int i = 0; i < envelopes.size(); i++) {
            envBatch[i] = envelopes.get(i).getEnvelope().toByteArray();
        }
        Common.Block nextBlock = BFTCommon.createNextBlock(lastBlockHeaders.get(channelID).getNumber() + 1, crypto.hash(BFTCommon.encodeBlockHeaderASN1(lastBlockHeaders.get(channelID))), envBatch);

        Common.BlockData.Builder filtered = Common.BlockData.newBuilder();
        for (EnvelopeWrapper envWrapper: envelopes) {

            try {

                Common.Envelope envelope = envWrapper.getEnvelope();

                mspManager.validateEnvelope(envelope, channelID, envWrapper.getTimestamp(), timeWindow);
                filtered.addData(envelope.toByteString());

            } catch (BFTCommon.BFTException ex) {

                logger.info("Envelope validation failed, discarding.");
            }

        }

        nextBlock = nextBlock.toBuilder().setData(filtered).build();

//        Common.Block block = Common.Block.getDefaultInstance();
//        block.toBuilder().setData(filtered).build();
//        block.

        byte[] nonces = new byte[rand.nextInt(10)];
        rand.nextBytes(nonces);
        //create signatures
        Common.Metadata blockSig = BFTCommon.createMetadataSignature(privKey, ident.toByteArray(), nonces, null, nextBlock.getHeader());
        Common.Metadata configSig = null;

        Common.LastConfig.Builder last = Common.LastConfig.newBuilder();
        last.setIndex(mspManager.getLastConfig(channelID));

        if (bothSigs) {

            configSig = BFTCommon.createMetadataSignature(privKey, ident.toByteArray(), nonces, last.build().toByteArray(), nextBlock.getHeader());
        } else {

            Common.MetadataSignature.Builder dummySig =
                    Common.MetadataSignature.newBuilder().setSignature(ByteString.EMPTY).setSignatureHeader(ByteString.EMPTY);

            configSig = Common.Metadata.newBuilder().setValue(last.build().toByteString()).addSignatures(dummySig).build();

        }

        fabricState.addBlock(channelID, nextBlock);
        return nextBlock;
    }

    //@TODO Is the timestamp created valid? As in, is this the only function where this timestamp will be required? Same in processSysConfig
    public Common.Block processConfig(String channelID, Common.Envelope env, Configtx.ConfigUpdateEnvelope confEnv) throws IOException, BFTCommon.BFTException, CryptoException, NoSuchProviderException, NoSuchAlgorithmException {

        mspManager = mspManager.clone();


        long timestamp = new Date().getTime();

        Configtx.ConfigUpdate confUpdate = Configtx.ConfigUpdate.parseFrom(confEnv.getConfigUpdate());

        mspManager.verifyPolicies(channelID, false, confUpdate.getReadSet(), confUpdate.getWriteSet(), confEnv, timestamp);

        if (envValidation) mspManager.validateEnvelope(env, channelID, timestamp, timeWindow);

        logger.info("Reconfiguration envelope for channel "+ channelID +" is valid, generating configuration with readset and writeset");

        Configtx.Config newConfig = mspManager.generateNextConfig(channelID, confUpdate.getReadSet(), confUpdate.getWriteSet());
        Configtx.ConfigEnvelope newConfEnv = BFTCommon.makeConfigEnvelope(newConfig, env);

        //The fabric codebase inserts the lastupdate structure into a signed envelope. I cannot do this here because the signatures
        //are different for each replica, thus producing different blocks. Even if I modified the frontend to be aware of this corner
        //case just like it is done for block signatures, envelopes are supposed to contain only one signature rather than a set of them.
        //My solution was to simply not sign the envelope. At least until Fabric v1.2, the codebase seems to accept unsigned envelopes.
        Common.Envelope newEnvelope = BFTCommon.makeUnsignedEnvelope(newConfEnv.toByteString(), ByteString.EMPTY, Common.HeaderType.CONFIG, 0, channelID, 0,
                timestamp);

        Common.Block block = BFTCommon.createNextBlock(lastBlockHeaders.get(channelID).getNumber() + 1, crypto.hash(BFTCommon.encodeBlockHeaderASN1(lastBlockHeaders.get(channelID))), new byte[][] { newEnvelope.toByteArray() });

        updateChannel(mspManager, channelID, block.getHeader().getNumber(), newConfEnv, newConfig, timestamp);

        lastBlockHeaders.put(channelID, block.getHeader());

        return block;
    }

    public Common.Block processSysConfig(String channelID, Common.Envelope env, Configtx.ConfigUpdateEnvelope confEnv) throws IOException, BFTCommon.BFTException, NoSuchProviderException, NoSuchAlgorithmException, CryptoException {

        long timestamp = new Date().getTime();

        Configtx.ConfigUpdate confUpdate = Configtx.ConfigUpdate.parseFrom(confEnv.getConfigUpdate());

        mspManager.verifyPolicies(channelID, true, confUpdate.getReadSet(), confUpdate.getWriteSet(), confEnv, timestamp);

        // We do not perform envelope validation for the envelope that is going to be appended to the system channel, since it is created by
        // its own organization/msp. The fabric codebase does re-apply the filters and hence authenticates the envelope to perform a sanity
        // check, but the the prime reason for re-applying the filters is to check that the size of the envelope is still within the size
        // limit. Furthermore, since this ordering service cannot sign the envelope, it should not validate it either (see comments below
        // regarding why the envelope is not signed)
        //if (envValidation) mspManager.validateEnvelope(env, channel, msgCtx.getTimestamp(), timeWindow);

        logger.info("Orderer transaction envelope for system channel is valid, generating genesis with readset and writeset for channel "+ confUpdate.getChannelId());

        Configtx.Config newConfig = mspManager.newChannelConfig(channelID,confUpdate.getReadSet(),confUpdate.getWriteSet());
        Configtx.ConfigEnvelope newConfEnv = BFTCommon.makeConfigEnvelope(newConfig, env);

        //The fabric codebase inserts the lastupdate structure into a signed envelope. We cannot do this here because the signatures
        //are different for each replica, thus producing different blocks. Even if I modified the frontend to be aware of this corner
        //case just like it is done for block signatures, envelopes are supposed to contain only one signature rather than a set of them.
        //My solution was to simply not sign the envelope. At least until Fabric v1.2, the codebase seems to accept unsigned envelopes.
        Common.Envelope envelopeClone = BFTCommon.makeUnsignedEnvelope(newConfEnv.toByteString(), ByteString.EMPTY, Common.HeaderType.CONFIG, 0, confUpdate.getChannelId(), 0,
                timestamp);

        Common.Block genesis = BFTCommon.createNextBlock(0, null, new byte[][]{envelopeClone.toByteArray()});

        envelopeClone = BFTCommon.makeUnsignedEnvelope(envelopeClone.toByteString(), ByteString.EMPTY, Common.HeaderType.ORDERER_TRANSACTION, 0, channelID, 0,
                timestamp);

        Common.Block block = BFTCommon.createNextBlock(lastBlockHeaders.get(channelID).getNumber() + 1, crypto.hash(BFTCommon.encodeBlockHeaderASN1(lastBlockHeaders.get(channelID))), new byte[][] { envelopeClone.toByteArray() });

        newChannel(mspManager, lastBlockHeaders, confUpdate.getChannelId(), genesis, timestamp);

        addBlock(channelID, block);

        lastBlockHeaders.put(channelID, block.getHeader());

        return block;
    }

    private void updateChannel(MSPManager mspManager, String channelID, long number, Configtx.ConfigEnvelope newConfEnv, Configtx.Config newConfig, long timestamp) throws BFTCommon.BFTException {

        long preferredMaxBytes = -1;
        long maxMessageCount = -1;

        try {

            logger.info("Updating state for channel " + channelID);

            Map<String,Configtx.ConfigGroup> groups = newConfig.getChannelGroup().getGroupsMap();

            Configuration.BatchSize batchsize = Configuration.BatchSize.parseFrom(groups.get("Orderer").getValuesMap().get("BatchSize").getValue());

            preferredMaxBytes = batchsize.getPreferredMaxBytes();
            maxMessageCount = batchsize.getMaxMessageCount();

            mspManager.updateChannel(channelID, number, newConfEnv, newConfig, timestamp);


        } catch (BFTCommon.BFTException ex) {

            throw ex;

        } catch (Exception ex) {

            throw new BFTCommon.BFTException("Failed to update channel " + channelID + ": " + ex.getLocalizedMessage());
        }

        // if we are here, we will successfully atomically update the channel
        if (preferredMaxBytes > -1 && maxMessageCount > -1) {
            bft.miguel.BlockCutter bc = BftFabricProxy.blockCutters.get(channelID);
            bc.setPreferredMaxBytes(preferredMaxBytes);
            bc.setMaxMessageCount(maxMessageCount);
        }

        logger.info("Latest block with config update for "+ channelID +": #" + number);

    }

    private void newChannel(MSPManager mspManager, Map<String,Common.BlockHeader> lastBlockHeaders, String channelID, Common.Block genesis, long timestamp) throws BFTCommon.BFTException {

        if (channelID != null && genesis != null && lastBlockHeaders.get(channelID) == null) {

            logger.info("Creating channel " + channelID);

            long preferredMaxBytes = -1;
            long maxMessageCount = -1;

            try{

                Configtx.ConfigEnvelope conf = BFTCommon.extractConfigEnvelope(genesis);

                Configuration.BatchSize batchSize = BFTCommon.extractBachSize(conf.getConfig());

                preferredMaxBytes = batchSize.getPreferredMaxBytes();
                maxMessageCount = batchSize.getMaxMessageCount();

                mspManager.newChannel(channelID, genesis.getHeader().getNumber(), conf, timestamp);


            } catch (BFTCommon.BFTException ex) {

                throw ex;

            } catch (Exception ex) {

                throw new BFTCommon.BFTException("Failed to update channel " + channelID + ": " + ex.getLocalizedMessage());
            }

            // if we are here, we will successfully atomically update the channel
            if (preferredMaxBytes > -1 && maxMessageCount > -1) {

                lastBlockHeaders.put(channelID, genesis.getHeader());
                if(!BftFabricProxy.blockCutters.containsKey(channelID))
                    BftFabricProxy.blockCutters.put(channelID, new bft.miguel.BlockCutter(preferredMaxBytes, maxMessageCount));
            }

            logger.info("New channel ID: " + channelID);
            logger.info("Genesis header number: " + lastBlockHeaders.get(channelID).getNumber());
            //logger.info("Genesis header previous hash: " + Hex.encodeHexString(lastBlockHeaders.get(channelID).getPreviousHash().toByteArray()));
            logger.info("Genesis header data hash: " + Hex.encodeHexString(lastBlockHeaders.get(channelID).getDataHash().toByteArray()));
            //logger.info("Genesis header ASN1 encoding: " + Arrays.toString(BFTCommon.encodeBlockHeaderASN1(lastBlockHeaders.get(channelID))));
        }
    }


    public String getSysChannel() {
        return sysChannel;
    }
}
