package io.bitsquare.trade.actor;

import io.bitsquare.BitSquare;
import io.bitsquare.bank.BankAccountType;
import io.bitsquare.locale.Country;
import io.bitsquare.trade.Offer;
import io.bitsquare.trade.actor.Event.OfferAdded;
import io.bitsquare.trade.actor.Event.OfferRemoved;
import io.bitsquare.trade.actor.Event.OffersFound;
import io.bitsquare.trade.actor.command.CreateOffer;
import io.bitsquare.trade.actor.command.GetOffers;
import io.bitsquare.trade.actor.command.RemoveOffer;
import io.bitsquare.util.JsonMapper;

import java.io.File;
import java.io.IOException;

import java.net.InetAddress;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import net.tomp2p.connection.Ports;
import net.tomp2p.dht.FutureGet;
import net.tomp2p.dht.FuturePut;
import net.tomp2p.dht.FutureRemove;
import net.tomp2p.dht.PeerBuilderDHT;
import net.tomp2p.dht.PeerDHT;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureBootstrap;
import net.tomp2p.p2p.Peer;
import net.tomp2p.p2p.PeerBuilder;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.storage.Data;
import net.tomp2p.utils.Utils;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;

public class DHTManager extends AbstractActor {

    private final LoggingAdapter log = Logging.getLogger(context().system(), this);

    // TODO move into app setup
    // timeout in ms
    private final Long bootstrapTimeout = 10000L;

    public static Props LOCAL_TEST = getPeerIdProps(new Number160(4001), 4001, Arrays.asList(
            new PeerAddress(new Number160(4000), InetAddress.getLoopbackAddress(), 4000, 4000)));

    public static Props LOCAL_BOOTSTRAP = getPeerIdProps(new Number160(4000), 4000, null);


    public static Props getKeyPairProps(KeyPair keyPair, Integer port, Collection<PeerAddress> bootstrapTo)
            throws NoSuchAlgorithmException {
        Number160 peerId = Utils.makeSHAHash(keyPair.getPublic().getEncoded());
        return Props.create(DHTManager.class, bootstrapTo, peerId, port);
    }

    public static Props getPeerIdProps(Number160 peerId, Integer port, Collection<PeerAddress> bootstrapTo) {
        return Props.create(DHTManager.class, bootstrapTo, peerId, port);
    }

    private final File dataDir = new File(System.getProperty("user.home") + "/Library/" + BitSquare.getAppName());
    private final Collection<PeerAddress> bootstrapPeers;

    private final Peer peer;
    private final PeerDHT peerDHT;

    public DHTManager(Collection<PeerAddress> bootstrapPeers, Number160 peerId, Integer port) throws IOException,
            NoSuchAlgorithmException {

        this.peer = new PeerBuilder(peerId).ports(port != null ? port : new Ports().tcpPort()).start();
        this.bootstrapPeers = bootstrapPeers;
        this.peerDHT = new PeerBuilderDHT(peer).start();

        // TODO add code to discover non-local peers
        // FutureDiscover futureDiscover = peer.discover().peerAddress(bootstrapPeers.).start();
        // futureDiscover.awaitUninterruptibly();

        if (bootstrapPeers != null) {
            FutureBootstrap futureBootstrap = peer.bootstrap().bootstrapTo(this.bootstrapPeers).start();
            futureBootstrap.awaitUninterruptibly(bootstrapTimeout);
        }

        receive(ReceiveBuilder.
                        match(CreateOffer.class, co -> {
                            log.debug("Received message: {}", co);
                            Offer o = co.getOffer();
                            //String locationCode = createOfferLocationCode(o.);
                            String locationCode = createOfferLocationCode(o.getBankAccountCountry(), o.getBankAccountType());
                            log.debug("put location code:" + locationCode);
                            peerDHT.put(Number160.createHash(locationCode))
                                    .data(new Data(o.toString().getBytes()))
                                    .start()
                                    .addListener(new BaseFutureAdapter<FuturePut>() {
                                        @Override
                                        public void operationComplete(FuturePut future) throws Exception {
                                            if (future.isSuccess()) {
                                                sender().tell(new OfferAdded(o), self());
                                            }
                                            else {
                                                log.error(future.toString());
                                                // TODO handle non-success
                                            }
                                        }

                                        @Override
                                        public void exceptionCaught(Throwable t) throws Exception {
                                            log.error(t.toString());
                                            // TODO handle exception
                                        }
                                    });
                        })
                        .match(RemoveOffer.class, ro -> {
                            log.debug("Received message: {}", ro);
                            Offer o = ro.getOffer();

                            peerDHT.remove(Number160.createHash(createOfferLocationCode(o.getBankAccountCountry(),o.getBankAccountType())))
                                    .contentKey(Number160.createHash(o.toString())).start()
                                    .addListener(new BaseFutureAdapter<FutureRemove>() {
                                        @Override
                                        public void operationComplete(FutureRemove future) throws Exception {
                                            if (future.isSuccess()) {
                                                sender().tell(new OfferRemoved(o), self());
                                            }
                                            else {
                                                log.error(future.toString());
                                                // TODO handle non-success
                                            }
                                        }

                                        @Override
                                        public void exceptionCaught(Throwable t) throws Exception {
                                            log.error(t.toString());
                                            // TODO handle exception
                                        }
                                    });
                        })
                        .match(GetOffers.class, go -> {
                            log.debug("Received message: {}", go);
                            String locationCode = createOfferLocationCode(go.getAccount().getCountry(),
                                    go.getAccount().getBankAccountType());
                            ActorRef sender = context().sender();
                            log.debug("get location code:" + locationCode);
                            peerDHT.get(Number160.createHash(locationCode))
                                    .all()
                                    .start()
                                    .addListener(new BaseFutureAdapter<FutureGet>() {
                                        @Override
                                        public void operationComplete(FutureGet future) throws Exception {
                                            if (future.isSuccess()) {
                                                List<Offer> offers = new ArrayList<Offer>();
                                                future.dataMap().values().forEach(d -> {
                                                    if (d.buffer() != null) {
                                                        Offer foundOffer = new JsonMapper<Offer>(Offer
                                                                .class).fromJsonBytes(d.toBytes());
                                                        log.debug(foundOffer.toString());
                                                        offers.add(foundOffer);
                                                    }
                                                });
                                                sender.tell(new OffersFound(offers), self());
                                            }
                                            else {
                                                log.error(future.toString());
                                                // TODO handle non-success
                                            }
                                        }

                                        @Override
                                        public void exceptionCaught(Throwable t) throws Exception {
                                            log.error(t.toString());
                                            // TODO handle exception
                                        }
                                    });
                        })
                        .matchAny(o -> log.info("received unknown message")).build()
        );
    }


    private String createOfferLocationCode(Country country, BankAccountType type) {
//        StringBuilder sb = new StringBuilder();
//        AccountCustodian custodian = account.getCustodian();
//        PhysicalAddress address = custodian.getAddress();
//        sb.append(address.getCountryCode().toUpperCase()).append(":");
//        sb.append(account.getCurrency().toUpperCase()).append(":");
//        sb.append(custodian.getName().toUpperCase());

        return country.toString() + type;
    }

    @Override
    public void postStop() throws Exception {
        log.debug("postStop");
        peerDHT.shutdown();
        super.postStop();
    }
}

