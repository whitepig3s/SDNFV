package nctu.winlab.unicastdhcp;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import com.google.common.collect.Maps;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;

import java.util.Map;
import java.util.Optional;

import static org.onlab.util.Tools.get;

@Component(immediate = true)
public class AppComponent {

  private final Logger log = LoggerFactory.getLogger(getClass());
  private final NameConfigListener cfgListener = new NameConfigListener();
  private final ConfigFactory factory =
      new ConfigFactory<ApplicationId, NameConfig>(
          APP_SUBJECT_FACTORY, NameConfig.class, "UnicastDhcpConfig") {
        @Override
        public NameConfig createConfig() {
          return new NameConfig();
        }
      };

  private ApplicationId appId;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected PacketService packetService;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected FlowRuleService flowRuleService;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected NetworkConfigRegistry cfgService;

  @Reference(cardinality = ReferenceCardinality.MANDATORY)
  protected CoreService coreService;

  protected Map<DeviceId, Map<MacAddress, PortNumber>> macTables = Maps.newConcurrentMap();
  private PacketProcessor processor;

  @Activate
  protected void activate() {
    //cfgService.registerProperties(getClass());
    appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
    cfgService.addListener(cfgListener);
    cfgService.registerConfigFactory(factory);
    log.info("Started");

    processor = new SwitchPacketProcessor();
    packetService.addProcessor(processor, PacketProcessor.director(3));

    packetService.requestPackets(DefaultTrafficSelector.builder()
    .matchEthType(Ethernet.TYPE_IPV4).build(), PacketPriority.REACTIVE, appId, Optional.empty());
    packetService.requestPackets(DefaultTrafficSelector.builder()
    .matchEthType(Ethernet.TYPE_ARP).build(), PacketPriority.REACTIVE, appId, Optional.empty());
  }

  @Deactivate
  protected void deactivate() {
    //cfgService.unregisterProperties(getClass(), false);
    cfgService.removeListener(cfgListener);
    cfgService.unregisterConfigFactory(factory);
    log.info("Stopped");
    packetService.removeProcessor(processor);
  }

  private class NameConfigListener implements NetworkConfigListener {
    @Override
    public void event(NetworkConfigEvent event) {
      if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
          && event.configClass().equals(NameConfig.class)) {
        NameConfig config = cfgService.getConfig(appId, NameConfig.class);
        if (config != null) {
          log.info("DHCP server is at {}!", config.name());
        }
      }
    }
  }


  private class SwitchPacketProcessor implements PacketProcessor {
    @Override
    public void process(PacketContext pc) {
        initMacTable(pc.inPacket().receivedFrom(),pc.inPacket().parsed().getSourceMAC());
        actLikeSwitch(pc);
    }

    public void actLikeHub(PacketContext pc) {
        pc.treatmentBuilder().setOutput(PortNumber.FLOOD);
        pc.send();

        MacAddress srcMac = pc.inPacket().parsed().getSourceMAC();
        ConnectPoint cp = pc.inPacket().receivedFrom();
        //log.info("MAC {} is missed on {}! Flood packet!", srcMac,cp.deviceId());
    }

    public void actLikeSwitch(PacketContext pc) {
        Short type = pc.inPacket().parsed().getEtherType();
        if (type != Ethernet.TYPE_IPV4 && type != Ethernet.TYPE_ARP) {
            return;
        }

        ConnectPoint cp = pc.inPacket().receivedFrom();
        Map<MacAddress, PortNumber> macTable = macTables.get(cp.deviceId());
        MacAddress srcMac = pc.inPacket().parsed().getSourceMAC();
        MacAddress dstMac = pc.inPacket().parsed().getDestinationMAC();
        macTable.put(srcMac, cp.port());
        PortNumber outPort = macTable.get(dstMac);

        if (outPort != null) {
            pc.treatmentBuilder().setOutput(outPort);
            FlowRule fr = DefaultFlowRule.builder()
                    .withSelector(DefaultTrafficSelector.builder().matchEthDst(dstMac).build())
                    .withTreatment(DefaultTrafficTreatment.builder().setOutput(outPort).build())
                    .forDevice(cp.deviceId()).withPriority(20) //priority:20
                    .makeTemporary(20) //timeout:20
                    .fromApp(appId).build();

            flowRuleService.applyFlowRules(fr);
            //log.info("MAC {} is matched on {}! Install flow rule!", dstMac,cp.deviceId());
            pc.send();
        } else {
            actLikeHub(pc);
        }
    }

    private void initMacTable(ConnectPoint cp,MacAddress srcMac) {
        macTables.putIfAbsent(cp.deviceId(), Maps.newConcurrentMap());
        Map<MacAddress, PortNumber> macTable = macTables.get(cp.deviceId());
        PortNumber outPort = macTable.get(srcMac);
        /*if (outPort != null) {
            log.info("Add MAC address==> switch: {},MAC: {},port: {}",cp.deviceId(),srcMac,outPort);
        }*/
    }
}
}
