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
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.HostId;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ARP;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IPv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.Dictionary;

import static org.onlab.util.Tools.get;

@Component(immediate = true)
public class AppComponent {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private ApplicationId appId;

    private PacketProcessor processor;

    protected Map<IPv4, MacAddress> arpTable = Maps.newConcurrentMap();

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
        @Override
        public void process(PacketContext pc) {
            Ethernet eth = pc.inPacket().parsed();
            //ConnectPoint cp = pc.inPacket().receivedFrom();
            //HostId hid = cp.hostId();
            ARP arpPacket = (ARP) eth.getPayload();

            if (eth.getEtherType() == eth.TYPE_ARP) {

                // final ARP a=(ARP)eth.deserialize().getPayload();
                 log.info("ETH: {}",arpPacket.getSenderProtocolAddress());
                //log.info("ETH: {}",hid.mac());

            } else if (eth.getEtherType() == eth.TYPE_IPV4) {
                // IPv4 ipPacket = (IPv4) eth.getPayload();
            }

        }

        private void addARP(IPv4 ip, MacAddress srcMac) {
            arpTable.putIfAbsent(ip, srcMac);
            log.info("Add IP address: {},MAC: {}", ip, srcMac);

        }
    }
}
