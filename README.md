## BFT Fabric Ordering Service

This is a wrapper around the project available [here](https://github.com/bft-smart/fabric-orderingservice).

The original project enables the usage of [BFT-SMaRt](https://github.com/bft-smart/library) as a Fabric Ordering Service. In order to do so, the original project wraps BFT-SMaRt in a **Frontend** component wrapper that makes itself available as an Orderer Peer to Fabric Peers. 

Since their project consists of a wrapper of BFT-SMaRt with Fabric ordering-specific code, it wasn't possible to adapt further BFT algorithms without changing that code. This fork changes their implementation to allow connecting said **Frontend** with further consensus algorithms, which are deployed independently, through a TCP socket connection on a given port. 

How to run our custom-made frontend: 

1. From the project base folder, Run
```bash
cd docker_images
./create-docker-images.sh bftchannel clean
```

This will build BftFabricProxy Java component from scratch, and create all the necessary Docker images to run the system

2. Run the Consensus algorithm component independently. This component needs to listen for connections in a given port and indeterminally await transactions. These transactions are to be treated by the consensus algorithm as raw data, with no particular structure. The size of the payload of each transaction is encoded in the first 8 bytes of each message sent by the Frontend.

3. Start the Frontend docker component
```bash

```

To get started, check out the [overview page](https://github.com/bft-smart/fabric-orderingservice/wiki/Overview) and our [quick start guide](https://github.com/bft-smart/fabric-orderingservice/wiki/Quick-Start-v1.3). You can also [learn to compile the code](https://github.com/bft-smart/fabric-orderingservice/wiki/Compiling). For a more in-dept look at how to configure a distributed deployment with this ordering service, you can [read our guide](https://github.com/bft-smart/fabric-orderingservice/wiki/Configuring-a-deployment-v1.3). For an in-depth look at the ordering service architecture and performance, check out the [DSN'18 paper](http://www.di.fc.ul.pt/~bessani/publications/dsn18-hlfsmart.pdf).

***Feel free to contact us if you have any questions!***
