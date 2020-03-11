## BFT Fabric Ordering Service

This is a wrapper around the project available [here](https://github.com/bft-smart/fabric-orderingservice).

The original project enables the usage of [BFT-SMaRt](https://github.com/bft-smart/library) as a Fabric Ordering Service. In order to do so, the original project wraps BFT-SMaRt in a **Frontend** component wrapper that makes itself available as an Orderer Peer to Fabric Peers. 

Since their project consists of a wrapper of BFT-SMaRt with Fabric ordering-specific code, it wasn't possible to adapt further BFT algorithms without changing that code. This fork changes their implementation to allow connecting said **Frontend** with further consensus algorithms, which are deployed independently, through a TCP socket connection on a given port. 

How to run our custom-made frontend: 

1. From the project base folder, Run
```bash
$ cd docker_images
$ ./create-docker-images.sh bftchannel clean
```

This will build BftFabricProxy Java component from scratch, and create all the necessary Docker images to run the system

2. Run the Consensus algorithm component independently, like our adapted [HoneybadgerBFT](https://github.com/miguelgnunes/HoneyBadgerBFT-Python.git). This component needs to listen for connections in a given port -- let's choose **5000** for this example -- and indeterminally await envelopes. These envelopes are to be treated by the consensus algorithm as raw data, with no particular structure. The size of the payload of each envelope is encoded in the first 8 bytes of each message sent by the Frontend.

3. Start the Frontend docker component
```bash
$ docker run -i -t --rm --network=bftchannel --name=bft.frontend.1000 bftsmart/fabric-frontend:amd64-1.3.0 1000 1 5000
```

The Frontend is now running identified as *bft.frontend.1000* in the *bftchannel* network. **1000** is the frontend id, **1** is the number of threads relaying envelopes to the Proxy and **5000** is the port where the consensus algorithm is listening to.

You now have an ordering node available in the *bftchannel* network. To setup the rest of the Fabric network for testing purposes, we refer to BFT-SMaRt authors' [quick start guide](https://github.com/bft-smart/fabric-orderingservice/wiki/Quick-Start-v1.3).
