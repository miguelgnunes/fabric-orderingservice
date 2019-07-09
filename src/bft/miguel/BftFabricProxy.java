package bft.miguel;

import bft.util.BFTCommon;
import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.util.KeyLoader;
import com.etsy.net.JUDS;
import com.etsy.net.UnixDomainSocket;
import com.etsy.net.UnixDomainSocketServer;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.common.Configtx;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by miguel on 22/02/2019.
 */


//@TODO BFTNode instances are started by a docker conf script. In this startup the node is provided with an id and keys to sign blocks. This must be changed so the Proxy does all the signing work. How to load the keys of the BFTNode onto the Proxy?
public class BftFabricProxy {

    private static UnixDomainSocketServer recvServer = null;
    private static ServerSocket sendServer = null;

    private static ServerSocket honeyBadgerServer = null;
    private static Socket honeyBadgerSocket = null;

    private static OutputStream honeyBadgerOs = null;
    private static InputStream honeyBadgerIs = null;

    private static DataInputStream is;
    private static UnixDomainSocket recvSocket = null;
    private static Socket sendSocket = null;
    private static ExecutorService executor = null;
    private static Map<String, DataOutputStream> outputs;
    private static Map<String, Timer> timers;

    //    private static Map<String, Long> BatchTimeout;
    private static int frontendID;
    private static int nextID;


    private static Logger logger;
    private static String configDir;
    private static CryptoPrimitives crypto;

    private static BFTFabricHelper fabricHelper;
    public static HashMap<String, BlockCutter> blockCutters;

    //measurements
    private static final int interval = 10000;
    private static long envelopeMeasurementStartTime = -1;
    private static final long blockMeasurementStartTime = -1;
    private static final long sigsMeasurementStartTime = -1;
    private static int countEnvelopes = 0;
    private static final int countBlocks = 0;
    private static final int countSigs = 0;


    //This is the default channel that will be used, as per the first connection from an orderer
    //This is for testing purposes only, as this Proxy library should adapt to multiple channels
    private static String channel;

    //MISSING: SETUP CHANNELS WITH HONEYBADGER NODES INSTEAD OF SMART-BFT
    public static void main(String args[]) throws ClassNotFoundException, IllegalAccessException, InstantiationException, CryptoException, InvalidArgumentException, NoSuchAlgorithmException, NoSuchProviderException, IOException, InvalidKeySpecException, CertificateException {

        if (args.length < 3) {
            System.out.println("Use: java bft.miguel.BftFabricProxy <proxy id> <pool size> <send port>");
            System.exit(-1);
        }


        int pool = Integer.parseInt(args[1]);
        int sendPort = Integer.parseInt(args[2]);
        int honeyBadgerPort = 5000;
        if (args.length > 3)
            honeyBadgerPort = Integer.parseInt(args[3]);

        System.out.println("Fetching hlf proxy...");
        Path proxy_ready = FileSystems.getDefault().getPath(System.getProperty("java.io.tmpdir"), "hlf-proxy-" + sendPort + ".ready");

        Files.deleteIfExists(proxy_ready);

        configDir = BFTCommon.getBFTSMaRtConfigDir("FRONTEND_CONFIG_DIR");
        System.out.println("Config dir: " + configDir);

        if (System.getProperty("logback.configurationFile") == null)
            System.setProperty("logback.configurationFile", configDir + "logback.xml");

        Security.addProvider(new BouncyCastleProvider());

        BftFabricProxy.logger = LoggerFactory.getLogger(BftFabricProxy.class);

        frontendID = Integer.parseInt(args[0]);
        nextID = frontendID + 1;

        crypto = new CryptoPrimitives();
        crypto.init();
        BFTCommon.init(crypto);

        blockCutters = new HashMap<>();

        fabricHelper = new BFTFabricHelper(frontendID);


        try {

            logger.info("Creating UNIX socket...");

            Path socket_file = FileSystems.getDefault().getPath(System.getProperty("java.io.tmpdir"), "hlf-pool-" + sendPort + ".sock");

            Files.deleteIfExists(socket_file);

            //MIGUEL Receive and send server for the orderer tool, NOT THE BFT NODES
            recvServer = new UnixDomainSocketServer(socket_file.toString(), JUDS.SOCK_STREAM, pool);
            sendServer = new ServerSocket(sendPort);

            FileUtils.touch(proxy_ready.toFile()); //Indicate the Go component that the java component is ready

            logger.info("Waiting for local connections and parameters...");

            //MIGUEL orderer tool receiving socket
            recvSocket = recvServer.accept();
            is = new DataInputStream(recvSocket.getInputStream());

            executor = Executors.newFixedThreadPool(pool);

            for (int i = 0; i < pool; i++) {

                UnixDomainSocket socket = recvServer.accept();

                executor.execute(new BftFabricProxy.ReceiverThread(socket, nextID));

                nextID++;
            }

            outputs = new TreeMap<>();
            timers = new TreeMap<>();
//            BatchTimeout = new TreeMap<>();

            new SenderThread().start();


            logger.info("Setting up honeybadgerBft connection in port " + honeyBadgerPort +  "...");
            //SETTING UP CONNECTION WITH PYTHON HONEYBADGERBFT

            honeyBadgerServer = new ServerSocket(honeyBadgerPort);
            honeyBadgerSocket = honeyBadgerServer.accept();
            honeyBadgerOs = honeyBadgerSocket.getOutputStream();
            honeyBadgerIs = honeyBadgerSocket.getInputStream();

            logger.info("Connected to honeybadger component");
            logger.info("Java component is ready");

            //MIGUEL what are the channels for?
            while (true) { // wait for the creation of new channels

                sendSocket = sendServer.accept();

                DataOutputStream os = new DataOutputStream(sendSocket.getOutputStream());

                channel = readString(is);

                outputs.put(channel, os);

                logger.info("Setting up system for new channel '" + channel + "'");

                nextID++;

            }


        } catch (IOException e) {

            logger.error("Failed to launch frontend", e);
            System.exit(1);
        }
    }

    private static String readString(DataInputStream is) throws IOException {

        byte[] bytes = readBytes(is);

        return new String(bytes);

    }

    private static boolean readBoolean(DataInputStream is) throws IOException {

        byte[] bytes = readBytes(is);

        return bytes[0] == 1;

    }

    private static byte[] readBytes(DataInputStream is) throws IOException {

        long size = readLong(is);

        logger.debug("Read number of bytes: " + size);

        byte[] bytes = new byte[(int) size];

        is.read(bytes);

        logger.debug("Read all bytes!");

        return bytes;

    }

    private static long readLong(DataInputStream is) throws IOException {
        byte[] buffer = new byte[8];

        is.read(buffer);

        //This is for little endian
        //long value = 0;
        //for (int i = 0; i < by.length; i++)
        //{
        //   value += ((long) by[i] & 0xffL) << (8 * i);
        //}
        //This is for big endian
        long value = 0;
        for (int i = 0; i < buffer.length; i++) {
            value = (value << 8) + (buffer[i] & 0xff);
        }

        return value;
    }

    private static long readInt() throws IOException {

        byte[] buffer = new byte[4];
        long value = 0;

        is.read(buffer);

        for (int i = 0; i < buffer.length; i++) {
            value = (value << 8) + (buffer[i] & 0xff);
        }

        return value;

    }


    //MIGUEL Used to receive transactions from orderer library. It packages them to format known by BFTSmart
    private static class ReceiverThread extends Thread {

        private int id;
        private UnixDomainSocket recv;
        private DataInputStream input;

        public ReceiverThread(UnixDomainSocket recv, int id) throws IOException {

            this.id = id;
            this.recv = recv;
            this.input = new DataInputStream(this.recv.getInputStream());
        }

        public void run() {

            String channelID;
            boolean isConfig;
            byte[] envelope;
            while (true) {


                try {


                    channelID = readString(this.input);
                    isConfig = readBoolean(this.input);
                    envelope = readBytes(this.input);

                    System.out.println("Received envelope" + " for channel id " + channelID + (isConfig ? " (type config)" : " (type normal)"));
                    logger.debug("Received envelope" + Arrays.toString(envelope) + " for channel id " + channelID + (isConfig ? " (type config)" : " (type normal)"));


                    //MIGUEL After receiving new envelope from orderer, checks if it's config. If not, sends to BFT


                    Common.Envelope env = Common.Envelope.parseFrom(envelope);
                    Common.Payload payload = Common.Payload.parseFrom(env.getPayload());

                    Common.Block block = null;

                    if (isConfig) {
                        Configtx.ConfigUpdateEnvelope confEnv = Configtx.ConfigUpdateEnvelope.parseFrom(payload.getData());

                        Configtx.ConfigUpdate confUpdate = Configtx.ConfigUpdate.parseFrom(confEnv.getConfigUpdate());
                        System.out.println("confUpdate channelId is " + confUpdate.getChannelId());
                        boolean isConfigUpdate = confUpdate.getChannelId().equals(channelID);

                        if (isConfigUpdate)
                            block = fabricHelper.processConfig(channelID, env, confEnv);

                            //In case we have a sysChannel update
                        else if (!isConfigUpdate && channelID.equals(fabricHelper.getSysChannel())) {
                            block = fabricHelper.processSysConfig(channelID, env, confEnv, confUpdate);

                        } else {

                            String msg = "Envelope contained channel creation request, but was submitted to a non-system channel (" + channel + ")";
                            logger.info(msg);
                            throw new BFTCommon.BFTException(msg);
                        }

                        fabricHelper.addBlock(channelID, block);
                        sendNewBlock(channelID, block);

                    } else {
                        EnvelopeWrapper envWrapper = fabricHelper.convertEnvelope(channelID, envelope);
                        envWrapper.sendEnvelope(honeyBadgerOs);
                    }


                } catch (IOException ex) {
                    logger.error("Error while receiving envelope from Go component", ex);
                    continue;
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                } catch (BFTCommon.BFTException e) {
                    e.printStackTrace();
                } catch (NoSuchProviderException e) {
                    e.printStackTrace();
                } catch (CryptoException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    //MIGUEL SenderThread is used to forward blocks to the hyperledger orderer tool. It packages the blocks from BFTNode to hyperledger-ready format
    private static class SenderThread extends Thread {

        public void run() {

            while (true) {
                try {

                    if(honeyBadgerIs == null) {
                        Thread.sleep(1000);
                        continue;
                    }
                    //@TODO Receive envelopes and form a block with it
                    EnvelopeWrapper envelope = EnvelopeWrapper.fromStream(honeyBadgerIs);

                    logger.info("Received envelope");

                    if (envelope != null) {

                        String channelID = envelope.getChannelId();

                        BlockCutter bc = blockCutters.get(channelID);

                        bc.addEnvelope(envelope);

                        if (bc.isBlock()) {
                            Common.Block block = fabricHelper.createBlock(channelID, bc.cutBlock());
                            sendNewBlock(channelID, block);
                            //                        os.write(isConfig ? (byte) 1 : (byte) 0);
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (NoSuchAlgorithmException e) {
                    e.printStackTrace();
                } catch (InvalidKeyException e) {
                    e.printStackTrace();
                } catch (SignatureException e) {
                    e.printStackTrace();
                } catch (CryptoException e) {
                    e.printStackTrace();
                } catch (BFTCommon.BFTException e) {
                    e.printStackTrace();
                } catch (NoSuchProviderException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

    }


    //Used to send block to orderer library
    private static void sendNewBlock(String channelID, Common.Block block) {
        System.out.println("Sending block to channel id " + channelID);

        byte[] bytes = block.toByteArray();
        DataOutputStream os = outputs.get(channelID);

        try {
            os.writeLong(bytes.length);
            os.write(bytes);
            os.writeLong(1);
            os.write((byte) 0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
