package nctu.winlab.ProxyArp;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.NetworkConfigRegistry;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.PortNumber;
import org.onosproject.net.HostId;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ARP;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.Dictionary;
import java.nio.ByteBuffer;

import static org.onlab.util.Tools.get;

@Component(immediate = true)
public class AppComponent {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private ApplicationId appId;

    private PacketProcessor processor;

    protected Map<Ip4Address, MacAddress> arpTable = Maps.newConcurrentMap();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.ProxyArp");
        processor = new ProxyProcessor();
        packetService.addProcessor(processor, PacketProcessor.director(3));
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(processor);
        log.info("Stopped");
    }

    private class ProxyProcessor implements PacketProcessor {
        Ethernet req;
        ConnectPoint srcConnectPoint;

        @Override
        public void process(PacketContext pc) {
            Ethernet eth = pc.inPacket().parsed();
            if (eth.getEtherType() == eth.TYPE_ARP) {
                ARP arpPacket = (ARP) eth.getPayload();
                Ip4Address srcip = Ip4Address.valueOf(arpPacket.getSenderProtocolAddress());
                Ip4Address dstip = Ip4Address.valueOf(arpPacket.getTargetProtocolAddress());
                MacAddress srcmac = eth.getSourceMAC();
                arpTable.put(srcip, srcmac);
                if (arpPacket.getOpCode() == ARP.OP_REQUEST) {
                    req = eth;
                    srcConnectPoint = pc.inPacket().receivedFrom();
                    if (!arpTable.containsKey(dstip)) {
                        log.info("TABLE MISS. Send request to edge ports");
                        pc.treatmentBuilder().setOutput(PortNumber.FLOOD);
                        pc.send();
                    } else {
                        log.info("TABLE HIT. Requested MAC = {}", srcmac);
                        ReplyARP(req, srcConnectPoint);
                    }
                } else if (arpPacket.getOpCode() == ARP.OP_REPLY) {
                    MacAddress dstmac = eth.getDestinationMAC();
                    log.info("RECV REPLY. Requested MAC = {}", dstmac);
                }
            }
        }

        private void ReplyARP(Ethernet req, ConnectPoint srcConnectPoint) {
            ARP arpPacket = (ARP) req.getPayload();
            Ip4Address dstip = Ip4Address.valueOf(arpPacket.getTargetProtocolAddress());
            Ethernet ethReply = ARP.buildArpReply(dstip, arpTable.get(dstip), req);
            TrafficTreatment.Builder builder = DefaultTrafficTreatment.builder();
            builder.setOutput(srcConnectPoint.port());
            packetService.emit(new DefaultOutboundPacket(srcConnectPoint.deviceId(), builder.build(),
                    ByteBuffer.wrap(ethReply.serialize())));
        }

    }
}
